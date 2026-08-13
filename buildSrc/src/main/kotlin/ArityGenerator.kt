import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

// Generates the mechanical part of the DSL's public surface: ADR 0004's six declaration variants
// for every declaration construct, arities 0-MAX_ARITY for the two parameter-taking ones, and
// ADR 0002's @Deprecated(ERROR) shadow members.
//
// Everything comes out of one `Overload` list. The shadows are *filtered* from that same list
// rather than written out a second time, which is the whole point of generating them here: a
// shadow whose signature does not match a real overload does not shadow it — it silently falls
// through to the real function (measured, ADR 0002) — and a hand-maintained second list is
// exactly how that mismatch happens. Deviation D7.

private const val PKG = "site.asm0dey.poetdsl"

/** ADR 0004: beyond eight parameters, callers use the list form. */
private const val MAX_ARITY = 8

// --- the variant table --------------------------------------------------------------------------

/**
 * One of ADR 0004's six shapes, distinguished by *presence and type*, never by defaults: a
 * defaulted `modifiers: Modifiers? = null` would make every variant match a bare call at once.
 */
private data class Variant(val annotated: Boolean, val modifiers: String?) {
    /** The variant's own leading parameters, in ADR 0004's order: annotations, then modifiers. */
    fun params(): List<String> = buildList {
        if (annotated) add("annotations: Annotations")
        modifiers?.let { add("modifiers: $it") }
    }

    val annotationsArg: String get() = if (annotated) "annotations" else "null"

    val modifiersArg: String get() = when (modifiers) {
        null -> "null"
        "KModifier" -> "modifiers.toModifiers()"
        else -> "modifiers"
    }

    /** Reads as a suffix on a KDoc first sentence. */
    val doc: String get() = when {
        annotated && modifiers == "KModifier" -> " with annotations and a modifier"
        annotated && modifiers != null -> " with annotations and modifiers"
        annotated -> " with annotations"
        modifiers == "KModifier" -> " with a modifier"
        modifiers != null -> " with modifiers"
        else -> ""
    }
}

private val VARIANTS: List<Variant> = listOf(
    Variant(false, null),
    Variant(false, "KModifier"),
    Variant(false, "Modifiers"),
    Variant(true, null),
    Variant(true, "KModifier"),
    Variant(true, "Modifiers"),
)

/** [constructorParam] takes no modifiers, so only the annotated/not axis of the table applies. */
private val ANNOTATION_VARIANTS: List<Variant> = listOf(Variant(false, null), Variant(true, null))

/**
 * One name a construct is callable under. The first spelling in a construct's list is canonical
 * and carries no [aliasNote]; every other spelling is an alias, and its sentence is rendered into
 * the alias's KDoc — restoring the "Alias of X" documentation the hand-written declarations had,
 * from the same table that generates the declaration, so it cannot drift from the alias list.
 */
private data class Spelling(val value: String, val aliasNote: String? = null)

/** The default alias sentence. Override per [Spelling] when more is needed (e.g. `property`). */
private fun aliasOf(canonical: String): String = "Alias of [$canonical]."

/** Prefixes [body] with [nm]'s alias sentence, if it has one. */
private fun docFor(nm: Spelling, body: String): String =
    if (nm.aliasNote != null) "${nm.aliasNote} $body" else body

// --- one overload, and the two ways it is rendered -----------------------------------------------

/**
 * A single generated declaration. [shadow], when set, is the message of the `BlockScope` shadow
 * that mirrors this exact signature — see [renderShadow].
 */
private data class Overload(
    val doc: String,
    val context: String,
    val name: String,
    val params: List<String>,
    val returns: String?,
    val body: String,
    val shadow: String? = null,
)

/** The real declaration. `returns == null` means a `Unit` block body. */
private fun Overload.render(): String = buildString {
    appendLine("/** $doc */")
    appendLine(context)
    appendLine("public fun $name(")
    params.forEach { appendLine("    $it,") }
    if (returns == null) {
        appendLine(") {")
        body.trimEnd().lines().forEach { appendLine(if (it.isEmpty()) "" else "    $it") }
        appendLine("}")
    } else {
        appendLine("): $returns =")
        body.trimEnd().lines().forEach { appendLine(if (it.isEmpty()) "" else "    $it") }
    }
    appendLine()
}

/**
 * The `BlockScope` shadow for the same signature.
 *
 * The parameter list is copied from the real overload, not re-derived: ADR 0002 measured that a
 * `vararg Any?` catch-all silently fails to shadow, because a trailing lambda does not bind into
 * it and resolution falls through to the real function. Only an exact match guards.
 */
private fun Overload.renderShadow(): String = buildString {
    appendLine("@Deprecated(")
    appendLine("    \"$shadow\",")
    appendLine("    level = DeprecationLevel.ERROR,")
    appendLine(")")
    appendLine("public fun BlockScope.$name(")
    params.forEach { appendLine("    $it,") }
    appendLine("): Nothing = throw UnsupportedOperationException()")
    appendLine()
}

// --- the constructs ------------------------------------------------------------------------------

/** A type declaration: `class`, `object`, `interface`, and their aliases. */
private data class TypeConstruct(
    val names: List<Spelling>,
    val kindName: String,
    val builder: String,
    val localAllowed: Boolean,
    val shadow: String?,
    /**
     * Whether the declaration takes primary-constructor parameters in its signature (D23). Only a
     * class has a primary constructor: an object and an interface have none, so they stay at the
     * single no-parameter shape they have always had.
     */
    val primaryParams: Boolean,
)

private val TYPES: List<TypeConstruct> = listOf(
    TypeConstruct(
        names = listOf(Spelling("`class`"), Spelling("klass", aliasOf("`class`"))),
        kindName = "class",
        builder = "TypeSpec.classBuilder(name)",
        localAllowed = true,
        // No shadow, deliberately: a local class *is* valid Kotlin (ADR 0002's matrix), and the
        // only thing stopping it is KotlinPoet 2.3.0's renderer. `declareType` rejects it at run
        // time with a message that names that reason and a canary test that fires when the
        // backend is fixed; an ERROR-deprecated overload would freeze a temporary backend defect
        // into the locked public API, and `fun` has the identical defect and no shadow either.
        shadow = null,
        primaryParams = true,
    ),
    TypeConstruct(
        names = listOf(Spelling("`object`")),
        kindName = "named object",
        builder = "TypeSpec.objectBuilder(name)",
        localAllowed = false,
        shadow = "A named object cannot be local in Kotlin. Declare it at file or type level, " +
            "or use an anonymous object.",
        primaryParams = false,
    ),
    TypeConstruct(
        names = listOf(Spelling("`interface`")),
        kindName = "interface",
        builder = "TypeSpec.interfaceBuilder(name)",
        localAllowed = false,
        shadow = "An interface cannot be local in Kotlin. Declare it at file or type level.",
        primaryParams = false,
    ),
)

private fun TypeConstruct.overloads(): List<Overload> = names.flatMap { nm ->
    (if (primaryParams) ARITIES else listOf<Int?>(0)).flatMap { arity ->
        VARIANTS.map { v ->
            Overload(
                doc = docFor(
                    nm,
                    if (arity == 0) {
                        "`$kindName Name { … }`${v.doc}."
                    } else {
                        "`$kindName Name(…) { … }`${v.doc}, with " +
                            "${arityDoc(arity, "primary-constructor parameter")}."
                    },
                ),
                context = "context(s: Scope)",
                name = nm.value,
                params = v.params() + listOf("name: String") + arityParams(arity) +
                    listOf("body: ${bodyType(arity, "TypeScope")}"),
                returns = null,
                body = """
                    |s.declareType(
                    |    $builder,
                    |    "$kindName",
                    |    name,
                    |    localAllowed = $localAllowed,
                    |    ${v.annotationsArg},
                    |    ${v.modifiersArg},
                    |    ${paramList(arity)},
                    |    ${forwarder(arity)},
                    |)
                """.trimMargin(),
                shadow = shadow,
            )
        }
    }
}

/** A binding: `val`, `var` and their aliases. Local, member or top-level — [bind] dispatches. */
private data class BindConstruct(val names: List<Spelling>, val mutable: Boolean, val keyword: String)

private val BINDINGS: List<BindConstruct> = listOf(
    BindConstruct(
        names = listOf(
            Spelling("`val`"),
            // Not the default "Alias of [`val`]." sentence: `prop` (property *access*) already
            // exists in this DSL, and a user reaching for `property` needs the disambiguation,
            // not just the alias fact. This is the one KDoc the generator must not drop.
            Spelling(
                "property",
                "Alias of the declaration-level [`val`]. `prop` is property *access* — a " +
                    "different thing.",
            ),
        ),
        mutable = false,
        keyword = "val",
    ),
    BindConstruct(listOf(Spelling("`var`")), mutable = true, keyword = "var"),
)

private fun BindConstruct.overloads(): List<Overload> = names.flatMap { nm ->
    VARIANTS.map { v ->
        Overload(
            doc = docFor(nm, "`$keyword name: T = init`${v.doc}: a local in a block, a property otherwise."),
            context = "context(s: Scope)",
            name = nm.value,
            params = v.params() + listOf(
                "name: String",
                "type: TypeName? = null",
                "init: Expr? = null",
                "by: Expr? = null",
            ),
            returns = "Expr",
            body = "s.bind($mutable, ${v.annotationsArg}, ${v.modifiersArg}, name, type, init, by)",
        )
    }
}

// The pieces `class`, `fun` and `constructor` share: each takes 0-MAX_ARITY `ParameterSpec`s — or,
// past the cap, one `List<ParameterSpec>` — and hands the body one `Expr` handle per parameter.

/**
 * The shapes every parameter-taking construct is generated in: arities 0-[MAX_ARITY], then `null`
 * for the list form, whose body takes the handles as a `List<Expr>` (D24). The list form is in this
 * table rather than hand-written so that it gets ADR 0004's six variants like every other shape —
 * without them a `data class` or a `private constructor` with more than eight parameters would be
 * inexpressible, which is the very gap D24 exists to close.
 */
private val ARITIES: List<Int?> = (0..MAX_ARITY).toList() + null

private fun arityParams(arity: Int?): List<String> =
    if (arity == null) listOf("params: List<ParameterSpec>") else (1..arity).map { "p$it: ParameterSpec" }

private fun bodyType(arity: Int?, receiver: String = "BlockScope"): String = when {
    arity == null -> "$receiver.(List<Expr>) -> Unit"
    arity == 0 -> "$receiver.() -> Unit"
    else -> "$receiver.(${List(arity) { "Expr" }.joinToString(", ")}) -> Unit"
}

/**
 * Adapts the `(List<Expr>) -> Unit` body the machinery calls to the fixed-arity body.
 *
 * Deviation D1: it reads the list **by index**. Destructuring it — `{ (a1, a2, …) -> … }`, which
 * is what the plan drafted — does not compile past arity 5, because the stdlib gives `List` only
 * `component1()`-`component5()`. The list form needs no adapter at all: it already has that shape.
 */
private fun forwarder(arity: Int?): String = when {
    arity == null -> "body"
    arity == 0 -> "{ body() }"
    else -> "{ args -> body(${(0 until arity).joinToString(", ") { "args[$it]" }}) }"
}

private fun paramList(arity: Int?): String = when {
    arity == null -> "params"
    arity == 0 -> "emptyList()"
    else -> "listOf(${(1..arity).joinToString(", ") { "p$it" }})"
}

/** Reads as a KDoc clause: "no parameters" / "one parameter" / "3 parameters". */
private fun arityDoc(arity: Int?, noun: String = "parameter"): String = when (arity) {
    null -> "a list of ${noun}s; the body receives their handles"
    0 -> "no ${noun}s"
    1 -> "one $noun; the body receives its handle"
    else -> "$arity ${noun}s; the body receives their handles"
}

private val FUN_NAMES: List<Spelling> = listOf(Spelling("`fun`"), Spelling("func", aliasOf("`fun`")))

private fun funOverloads(): List<Overload> = FUN_NAMES.flatMap { nm ->
    ARITIES.flatMap { arity ->
        VARIANTS.map { v ->
            Overload(
                doc = docFor(nm, "`fun name(…) { … }`${v.doc}, with ${arityDoc(arity)}."),
                context = "context(s: Scope)",
                name = nm.value,
                params = v.params() + listOf("name: String") + arityParams(arity) +
                    listOf("returns: TypeName? = null", "body: ${bodyType(arity)}"),
                returns = null,
                body = """
                    |s.declareFun(
                    |    buildFun(
                    |        name,
                    |        false,
                    |        ${v.annotationsArg},
                    |        ${v.modifiersArg},
                    |        ${paramList(arity)},
                    |        returns,
                    |        s,
                    |        ${forwarder(arity)},
                    |    ),
                    |)
                """.trimMargin(),
            )
        }
    }
}

private val CTOR_NAMES: List<Spelling> =
    listOf(Spelling("`constructor`"), Spelling("ctor", aliasOf("`constructor`")))

private fun ctorOverloads(): List<Overload> = CTOR_NAMES.flatMap { nm ->
    ARITIES.flatMap { arity ->
        VARIANTS.map { v ->
            Overload(
                doc = docFor(
                    nm,
                    "`constructor(…) { … }`${v.doc}, with ${arityDoc(arity)}. No return type is inferred.",
                ),
                context = "context(t: TypeScope)",
                name = nm.value,
                params = v.params() + arityParams(arity) + listOf("body: ${bodyType(arity)}"),
                returns = null,
                // The guards live on every overload rather than in `buildFun`: they are about what
                // the *type* already has, and `buildFun` never sees the TypeScope. They are one
                // call rather than inline `check`s so that the rules — and D25's coming relaxation
                // of the primary/secondary one — are stated once, in `Declarations.kt`.
                body = """
                    |t.beginSecondaryConstructor()
                    |t.builder.addFunction(
                    |    buildFun(
                    |        "<init>",
                    |        true,
                    |        ${v.annotationsArg},
                    |        ${v.modifiersArg},
                    |        ${paramList(arity)},
                    |        null,
                    |        t,
                    |        ${forwarder(arity)},
                    |    ),
                    |)
                """.trimMargin(),
            )
        }
    }
}

/**
 * `constructorParam` / `ctorParam`. Generated here rather than hand-written so that its shadows —
 * the reason ADR 0002 lists it at all — come off the same list as its real overloads (D7).
 *
 * `kind` keeps its default only in the un-annotated variant: with `annotations` following it,
 * a defaulted `kind` could never be skipped positionally anyway.
 */
private val CTOR_PARAM_NAMES: List<Spelling> =
    listOf(Spelling("constructorParam"), Spelling("ctorParam", aliasOf("constructorParam")))

private fun ctorParamOverloads(): List<Overload> = CTOR_PARAM_NAMES.flatMap { nm ->
    ANNOTATION_VARIANTS.map { v ->
        Overload(
            doc = docFor(
                nm,
                "A primary-constructor parameter${v.doc}; " +
                    "`ParamKind.VAL`/`VAR` also declares the matching property.",
            ),
            context = "context(t: TypeScope)",
            name = nm.value,
            params = listOf(if (v.annotated) "kind: ParamKind?" else "kind: ParamKind? = null") +
                v.params() + listOf("name: String", "type: TypeName"),
            returns = "Expr",
            body = "t.addConstructorParam(kind, ${v.annotationsArg}, name, type)",
            shadow = "${nm.value} is only valid inside a class or object body.",
        )
    }
}

// --- files ----------------------------------------------------------------------------------------

private val IMPORTS = listOf(
    "com.squareup.kotlinpoet.KModifier",
    "com.squareup.kotlinpoet.ParameterSpec",
    "com.squareup.kotlinpoet.TypeName",
    "com.squareup.kotlinpoet.TypeSpec",
)

/** Wraps rendered declarations in a package header, importing exactly what the text mentions. */
private fun kotlinFile(body: String): String = buildString {
    appendLine("// Generated by ArityGeneratorTask (buildSrc/src/main/kotlin/ArityGenerator.kt). Do not edit.")
    appendLine("package $PKG")
    appendLine()
    val used = IMPORTS.filter { Regex("\\b${it.substringAfterLast('.')}\\b").containsMatchIn(body) }
    if (used.isNotEmpty()) {
        used.forEach { appendLine("import $it") }
        appendLine()
    }
    append(body)
}

private fun render(overloads: List<Overload>): String = overloads.joinToString("") { it.render() }

/** Generates the DSL's mechanical overloads and their shadows into `build/generated`. */
public open class ArityGeneratorTask : DefaultTask() {
    @get:OutputDirectory
    public val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    public fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(PKG.replace('.', '/'))
        dir.mkdirs()

        val declarations = TYPES.flatMap { it.overloads() } +
            BINDINGS.flatMap { it.overloads() } +
            ctorParamOverloads()
        val funs = funOverloads()
        val ctors = ctorOverloads()

        dir.resolve("FunArity.kt").writeText(kotlinFile(render(funs)))
        dir.resolve("CtorArity.kt").writeText(kotlinFile(render(ctors)))
        dir.resolve("DeclarationVariants.kt").writeText(kotlinFile(render(declarations)))

        val shadowed = (declarations + funs + ctors).filter { it.shadow != null }
        dir.resolve("Shadows.kt").writeText(
            kotlinFile(
                "// Compile-time guards: constructs that are not valid Kotlin inside a function body.\n" +
                    "// One per real public overload, derived from the same list that generated them.\n\n" +
                    shadowed.joinToString("") { it.renderShadow() },
            ),
        )
    }
}
