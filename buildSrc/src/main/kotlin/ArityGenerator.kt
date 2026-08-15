import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

// Generates the mechanical part of the DSL's public surface: ADR 0004's six declaration variants
// for every declaration construct, arities 0-MAX_ARITY for the three parameter-taking ones, and
// ADR 0002's @Deprecated(ERROR) shadow members. D26's two supertype constructs are here for the
// shadows alone — one overload each, no variants — because a shadow that is not derived from the
// declaration it mirrors is the hand-maintained second list this generator exists to prevent.
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

/**
 * The KDoc block. One line stays on one line; a multi-paragraph doc — which only the supertypes
 * have — is written out as a real KDoc block rather than crammed into `/** … */`.
 */
private fun renderDoc(doc: String): String =
    if ('\n' !in doc) {
        "/** $doc */\n"
    } else {
        buildString {
            appendLine("/**")
            doc.trimEnd().lines().forEach { appendLine(if (it.isEmpty()) " *" else " * $it") }
            appendLine(" */")
        }
    }

/** The real declaration. `returns == null` means a `Unit` block body. */
private fun Overload.render(): String = buildString {
    append(renderDoc(doc))
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
    /**
     * Whether the declaration takes type parameters (D31). A class and an interface do; an `object`
     * does not — `object O<T>` is not Kotlin — so it gets no slot at all rather than a runtime
     * check, which is the strongest form the guard can take.
     */
    val typeParams: Boolean,
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
        typeParams = true,
    ),
    TypeConstruct(
        names = listOf(Spelling("`object`")),
        kindName = "named object",
        builder = "TypeSpec.objectBuilder(name)",
        localAllowed = false,
        shadow = "A named object cannot be local in Kotlin. Declare it at file or type level, " +
            "or use an anonymous object.",
        primaryParams = false,
        typeParams = false,
    ),
    TypeConstruct(
        names = listOf(Spelling("`interface`")),
        kindName = "interface",
        builder = "TypeSpec.interfaceBuilder(name)",
        localAllowed = false,
        shadow = "An interface cannot be local in Kotlin. Declare it at file or type level.",
        primaryParams = false,
        typeParams = true,
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
                    typeVariablesParam(typeParams) + KDOC_PARAM +
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
                    |    ${typeVariablesArg(typeParams)},
                    |    kdoc,
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

/**
 * The accessor half of a binding's KDoc, repeated on every `` `val` ``/`` `var` `` overload because
 * a generated declaration's KDoc is the only documentation its caller ever hovers over.
 *
 * It is where the two spellings E2a deliberately did *not* give constructs to are written down: the
 * backing `field`, and an extension body's `this`.
 */
private val BINDING_ACCESSOR_DOC: String = """
    |
    |
    |A property takes accessors: [getter] is the trailing lambda and [setter] a named argument ahead
    |of it, since two lambdas cannot both be trailing. [setterParam] names the setter's parameter —
    |per ADR 0005 the rendered name comes from the DSL, never from the Kotlin binding the body's
    |handle happens to be assigned to.
    |
    |Inside either body the backing field is `expression("field")` and an extension receiver's `this`
    |is `expression("this")`. Neither gets a construct of its own: a construct valid only inside an
    |accessor body would need a `BlockScope` shadow it cannot have, because an accessor body *is* a
    |`BlockScope`.
    |
    |An extension property — one with a [receiver] — has no backing field, so it needs a getter (or a
    |delegate), needs a setter as well when it is a `var`, and cannot have an initializer.
    |[typeVariables] are allowed only where the receiver type uses them, which is Kotlin's own rule
    |for a property's type parameter.
    |
    |A local binding in a block body takes none of these.
""".trimMargin()

private fun BindConstruct.overloads(): List<Overload> = names.flatMap { nm ->
    VARIANTS.map { v ->
        Overload(
            doc = docFor(
                nm,
                "`$keyword name: T = init`${v.doc}: a local in a block, a property otherwise.",
            ) + BINDING_ACCESSOR_DOC,
            context = "context(s: Scope)",
            name = nm.value,
            params = v.params() + listOf(
                "name: String",
                "type: TypeName? = null",
                "init: Expr? = null",
                "by: Expr? = null",
                // E2a's four slots, all *after* the ones that were already here, so no positional
                // argument that compiled before this shifts into a different parameter. Two lambdas
                // cannot both be trailing: the getter is the common one and takes the trailing
                // position, so the setter is written as a named argument ahead of it.
                "typeVariables: List<TypeVariableName> = emptyList()",
                "receiver: TypeName? = null",
                "setterParam: String = \"value\"",
                "setter: (BlockScope.(Expr) -> Unit)? = null",
                // E2b's slot goes *before* the getter, not after it: the getter is the trailing
                // lambda, and a slot appended past it would stop `` `val`("x", INT) { … } `` from
                // binding its block to the getter at all.
            ) + KDOC_PARAM + CONTEXT_PARAMS + listOf(
                "getter: (BlockScope.() -> Unit)? = null",
            ),
            returns = "Expr",
            body = "s.bind(\n" +
                "    $mutable,\n" +
                "    ${v.annotationsArg},\n" +
                "    ${v.modifiersArg},\n" +
                "    name,\n" +
                "    type,\n" +
                "    init,\n" +
                "    by,\n" +
                "    typeVariables,\n" +
                "    receiver,\n" +
                "    setterParam,\n" +
                "    setter,\n" +
                "    getter,\n" +
                "    kdoc,\n" +
                "    contextParameters,\n" +
                ")",
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

/**
 * D31's type-parameter slot, as a parameter list fragment.
 *
 * A **defaulted** parameter on every existing overload, not a new axis of the table: it adds one
 * `typeVariables: List<TypeVariableName> = emptyList()` to each declaration and **zero** new
 * declarations, which is the whole reason this shape was chosen over anything presence-distinguished
 * (see the E1 report). `returns` on `` `fun` `` is the same shape and the same cost.
 *
 * It sits *after* the arity parameters because it has to — a defaulted parameter cannot precede the
 * non-defaulted `p1: ParameterSpec` list — and *before* `returns`, which is as close to Kotlin's own
 * `fun <T> name(…): R` order as that constraint allows.
 */
/**
 * E2b's KDoc slot, on every declaration construct. Like [typeVariablesParam] it is a **defaulted**
 * parameter on every existing overload and **zero** new declarations.
 *
 * Its position is always "after every slot that was already there, and before the body lambda" — a
 * defaulted slot appended anywhere else moves a positional argument, and a `String?` slot moved into
 * a position where a `String` already resolves is the one shape that rebinds *silently* rather than
 * failing at the caller's own compile (D32, D33). The body lambda has to stay last so that a trailing
 * lambda still binds to it.
 *
 * The text is a plain `String`, and every route from it into KotlinPoet's `addKdoc` goes through
 * `"%L"` — see `docBlock` in `Declarations.kt`. `addKdoc` is a *format* function: `addKdoc("100%
 * done")` raises `IllegalArgumentException: index 1 for '% ' not in range (received 0 arguments)`,
 * and `addKdoc("a %S b", …)` silently eats the next argument. A generator writing prose has no idea
 * it is holding a format string.
 */
private val KDOC_PARAM: List<String> = listOf("kdoc: String? = null")

/**
 * E3's context-parameter slot, on `` `fun` ``/`func` and on `` `val` ``/`` `var` ``/`property`.
 *
 * A **defaulted** parameter like [typeVariablesParam] and [KDOC_PARAM], so it adds **zero** new
 * declarations. Its position is the standing rule's — after every slot that was already there and
 * before the body lambda — which for a binding means after `kdoc` and before the trailing `getter`,
 * and for a function after `returnsKdoc`. A `List<ContextParameter>` cannot bind a lambda, so a
 * trailing lambda still reaches the body and nothing that compiled before this moves.
 *
 * `` `constructor` ``, `` `class` ``, `` `object` ``, `` `interface` `` and `` `typealias` `` get no
 * slot: context parameters are *unsupported* on a constructor, a class, an object and a type alias on
 * all three frontends, so the absence of the slot is the guard.
 */
private val CONTEXT_PARAMS: List<String> = listOf("contextParameters: List<ContextParameter> = emptyList()")

/**
 * The two KDoc tags KotlinPoet models as parameters of `returns`/`receiver` rather than as text —
 * `@return` and `@receiver`. Written as slots rather than left to the caller's own prose because
 * KotlinPoet emits the tags in the order Kotlin documents them (`@receiver`, then `@param` for each
 * documented parameter, then `@return`), which hand-written text in [KDOC_PARAM] cannot get right
 * once a parameter also carries one. `@param` needs no slot here: it rides on `param(…)`'s own
 * `kdoc`.
 */
private val FUN_KDOC_PARAMS: List<String> =
    KDOC_PARAM + listOf("receiverKdoc: String? = null", "returnsKdoc: String? = null")

private fun typeVariablesParam(present: Boolean): List<String> =
    if (present) listOf("typeVariables: List<TypeVariableName> = emptyList()") else emptyList()

/** The matching argument: the slot's value where there is one, an empty list where there is not. */
private fun typeVariablesArg(present: Boolean): String = if (present) "typeVariables" else "emptyList()"

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
                doc = docFor(nm, "`fun name(…) { … }`${v.doc}, with ${arityDoc(arity)}.") +
                    "\n\n[receiver] declares an extension — `fun String.shout()`. The body gets no " +
                    "handle for `this`;\nwrite `expression(\"this\")`, the same escape hatch an " +
                    "accessor body uses for `field`.",
                context = "context(s: Scope)",
                name = nm.value,
                params = v.params() + listOf("name: String") + arityParams(arity) +
                    typeVariablesParam(true) + listOf(
                        "returns: TypeName? = null",
                        // E2a's extension receiver, deliberately *after* `returns` rather than
                        // before it. Both slots are `TypeName?`, so a slot inserted ahead of
                        // `returns` would silently rebind the one positional spelling that still
                        // resolves — `` `fun`("f", listOf(t), STRING) { } `` — from a return type
                        // to a receiver type, rendering `fun String.f()` for code that asked for
                        // `fun f(): String`. D32's breaks were loud; that one would not be. See D33.
                        "receiver: TypeName? = null",
                    ) + FUN_KDOC_PARAMS + CONTEXT_PARAMS + listOf("body: ${bodyType(arity)}"),
                returns = null,
                body = """
                    |s.declareFun(
                    |    buildFun(
                    |        name,
                    |        FunKind.FUNCTION,
                    |        ${v.annotationsArg},
                    |        ${v.modifiersArg},
                    |        ${paramList(arity)},
                    |        typeVariables,
                    |        returns,
                    |        receiver,
                    |        kdoc,
                    |        receiverKdoc,
                    |        returnsKdoc,
                    |        contextParameters,
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
                params = v.params() + arityParams(arity) + KDOC_PARAM +
                    listOf("body: ${bodyType(arity)}"),
                returns = null,
                // The guards live on every overload rather than in `buildFun`: they are about what
                // the *type* already has, and `buildFun` never sees the TypeScope. They are two
                // calls rather than inline `check`s so that the rules are stated once, in
                // `Declarations.kt` — and they are *two* because D25 split them by what is knowable
                // when: the kind check before the body runs, the delegation check after, on the
                // built spec. `addSecondaryConstructor` is also what adds the function to the type.
                body = """
                    |t.beginSecondaryConstructor()
                    |t.addSecondaryConstructor(
                    |    buildFun(
                    |        "<init>",
                    |        FunKind.CONSTRUCTOR,
                    |        ${v.annotationsArg},
                    |        ${v.modifiersArg},
                    |        ${paramList(arity)},
                    |        emptyList(),
                    |        null,
                    |        null,
                    |        kdoc,
                    |        null,
                    |        null,
                    |        // A constructor takes **no** context parameters: `class C { context(c: Ctx)
                    |        // constructor(x: Int) { } }` is *context parameters on constructors are
                    |        // unsupported* on all three frontends, so `` `constructor` `` gets no slot
                    |        // at all rather than a run-time check — the strongest form the guard takes,
                    |        // and the same call E1 made for `object O<T>`.
                    |        emptyList(),
                    |        t,
                    |        ${forwarder(arity)},
                    |    ),
                    |)
                """.trimMargin(),
                // Measured, and it is why every one of these 120 overloads carries a shadow: the
                // `context(t: TypeScope)` does *not* stop a call inside a `fun` body — the enclosing
                // type's context parameter is still in scope there, so the call resolves and the
                // constructor silently attaches to the enclosing type. Only the file-level direction
                // fails on its own ("no context argument for 't: TypeScope' found").
                shadow = "${nm.value} is only valid inside a class body. Written in a block it " +
                    "would silently attach to the enclosing type.",
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
                    "`ParamKind.VAL`/`VAR` also declares the matching property. " +
                    "`default`, `modifiers` and `kdoc` behave as they do on [param], whose KDoc " +
                    "carries the rules — one `vararg` per parameter list, no two parameter " +
                    "modifiers at once, and the double render of a `VAL`/`VAR` parameter's `kdoc`.",
            ),
            context = "context(t: TypeScope)",
            name = nm.value,
            params = listOf(if (v.annotated) "kind: ParamKind?" else "kind: ParamKind? = null") +
                v.params() + listOf(
                    "name: String",
                    "type: TypeName",
                    // E2b's slots, appended after the ones that were already here so that no
                    // positional argument which resolved before this moves. `modifiers` is a single
                    // `KModifier?` rather than a `Modifiers` set because no two parameter modifiers
                    // combine in Kotlin — see `PARAMETER_MODIFIERS` in `Declarations.kt`.
                    "default: Expr? = null",
                    "modifiers: KModifier? = null",
                ) + KDOC_PARAM,
            returns = "Expr",
            body = "t.addConstructorParam(kind, ${v.annotationsArg}, name, type, default, modifiers, kdoc)",
            shadow = "${nm.value} is only valid inside a class or object body.",
        )
    }
}

/**
 * D26's `superclass` / `superinterface`. One overload each — no variants, no arities — and generated
 * here anyway, for the shadows: a shadow list written by hand beside the declarations it mirrors is
 * the second list ADR 0002 measured going wrong, and the rule is one shadow per real public overload,
 * off the same list that emitted it (D7). The bodies are a single call into `Supertypes.kt`, which
 * keeps the guards and the reasoning for them.
 *
 * Both need a shadow for the same measured reason `` `constructor` `` does: `context(t: TypeScope)`
 * does not stop a call in a block body — the enclosing type's context parameter is still in scope, so
 * `` `fun`("f") { superclass(b) } `` resolves and attaches the supertype to the enclosing type.
 */
private val SUPERTYPES: List<Overload> = listOf(
    Overload(
        doc = """
            |`: Base(args)` — the class this type extends, and the arguments its constructor is
            |called with.
            |
            |With no [args] the supertype is written `: Base()`, which is what a no-argument
            |superclass call looks like. Arguments may name this type's own primary-constructor
            |parameters — `class User(val id: Long) : Entity(id)` — which is why the parameters D23
            |puts in the `class` signature are declared before the body runs.
            |
            |An interface has no superclass; extending one is [superinterface]'s job, in an
            |interface body exactly as in a class body.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "superclass",
        params = listOf("type: TypeName", "vararg args: Expr"),
        returns = null,
        body = "t.applySuperclass(type, args)",
        shadow = "superclass is only valid inside a class or object body, on the type itself. " +
            "Written in a block it would silently attach to the enclosing type.",
    ),
    Overload(
        doc = """
            |`: Runnable` — an interface this type implements, or, in an interface body, one it
            |extends.
            |
            |Called once per interface; a second call naming the same one is rejected rather than
            |silently dropped, which is what KotlinPoet's map of superinterfaces would do with it.
            |
            |[by] delegates the interface to an expression — `class C(i: Iface) : Iface by i`, the
            |shape D31 filed as absent. A defaulted slot rather than a second construct, so the
            |generated surface and the shadow list are both unchanged. It is refused in an interface
            |body (*delegation cannot be used in interfaces*) and in a `value class` (*value class
            |cannot implement an interface by delegation*), and allowed everywhere else a supertype
            |is — a class, an object, a companion object, an `enum class` and a nested class alike,
            |measured on all three frontends.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "superinterface",
        params = listOf("type: TypeName", "by: Expr? = null"),
        returns = null,
        body = "t.applySuperinterface(type, by)",
        shadow = "superinterface is only valid inside a class, object or interface body, on the " +
            "type itself. Written in a block it would silently attach to the enclosing type.",
    ),
)

/**
 * D29's `` `init` `` and `companionObject`. Like [SUPERTYPES], generated here for the shadows: both
 * are `TypeScope`-only, and `context(t: TypeScope)` does not stop a call in a member body — the
 * enclosing type's context parameter is still in scope there, so `` `fun`("f") { `init` { … } } ``
 * would resolve and silently attach an initializer block to the enclosing type. Neither goes through
 * the six-variant matrix: an `init` block takes no annotations or modifiers in Kotlin at all, and a
 * companion object takes none in practice, so the companion's name is the only axis either has.
 *
 * The companion object takes E2b's [KDOC_PARAM] and the `init` block does not, which is not an
 * oversight in either direction: a companion object is a declaration and KotlinPoet's `TypeSpec`
 * documents one, while `TypeSpec.Builder.addInitializerBlock` takes a bare `CodeBlock` with nowhere
 * to put documentation — and Kotlin has no KDoc syntax for an initializer block anyway.
 *
 * The named overload's `name: String` and the anonymous one's `kdoc: String? = null` occupy the same
 * position, which is exactly the shape that can rebind silently. Measured rather than reasoned:
 * `companionObject("Factory") { }` still renders `companion object Factory` at head, because Kotlin
 * picks the more specific `String` over `String?`. See D35.
 *
 * `init` is a *soft* keyword — a keyword only in a class body's declaration position — so the
 * function is declared without backticks and calls read as `` `init` { } `` or `init { }`
 * interchangeably. The bodies are a single call into `TypeBody.kt`, which keeps the guards.
 */
private val TYPE_BODY: List<Overload> = listOf(
    Overload(
        doc = """
            |`init { … }` — an initializer block, run as part of every constructor.
            |
            |The place a generated class validates its constructor arguments. The block sees this
            |type's properties and *all* of its primary-constructor parameters, including the plain
            |ones that have no property and are therefore invisible in a member body (D30).
            |
            |Kotlin allows no `return` in an initializer block; a returning one is rejected rather
            |than rendered. A class or an object may have several — they run in declaration order —
            |and an interface may have none.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "init",
        params = listOf("body: BlockScope.() -> Unit"),
        returns = null,
        body = "t.addInitializerBlock(body)",
        shadow = "`init` is only valid inside a class, object or companion object body. Written in " +
            "a block it would silently attach an initializer block to the enclosing type.",
    ),
    Overload(
        doc = """
            |`companion object { … }` — the anonymous companion object, referred to as `Companion`.
            |
            |Where a generated class keeps its factory functions and its constants. The body is an
            |ordinary type body, so `` `fun` ``, `` `val` `` and the rest read inside it exactly as
            |they do in the class. A companion object is a *nested* object: it cannot see the
            |enclosing type's instance members, and its own names are uniquified independently.
            |
            |One per type, and only in a class or an interface — an object cannot have one.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "companionObject",
        params = KDOC_PARAM + listOf("body: TypeScope.() -> Unit"),
        returns = null,
        body = "t.addCompanionObject(null, kdoc, body)",
        shadow = "companionObject is only valid inside a class or interface body. Written in a " +
            "block it would silently attach a companion object to the enclosing type.",
    ),
    Overload(
        doc = """
            |`companion object Name { … }` — the named companion object.
            |
            |The same construct as the anonymous [companionObject], with the name callers refer to
            |it by (`User.Factory.of(…)`) instead of the implicit `Companion`. A separate overload
            |rather than a defaulted parameter, so both forms are distinguished by presence, as
            |every other pair in this DSL is.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "companionObject",
        params = listOf("name: String") + KDOC_PARAM + listOf("body: TypeScope.() -> Unit"),
        returns = null,
        body = "t.addCompanionObject(name, kdoc, body)",
        shadow = "companionObject is only valid inside a class or interface body. Written in a " +
            "block it would silently attach a companion object to the enclosing type.",
    ),
    Overload(
        doc = """
            |`RED(0xFF0000) { … }` — an entry of an `enum class`, D31's one **silent** failure.
            |
            |`` `class`(ENUM, "Color") { } `` already produced a valid enum builder — KotlinPoet
            |derives `isEnum` from the modifier — and there was no way to put an entry in it, so the
            |render was a correct but empty `enum class`. It is valid Kotlin, measured on all three
            |frontends, which is why this is a construct and not a guard.
            |
            |[args] are the entry's own constructor arguments and are checked against the enum's
            |primary constructor once the whole body has run, so a `constructorParam` written after
            |the entry still supplies them. An entry with a [body] is an anonymous subclass of the
            |enum, and its body is a strict subset of a class body: no nested classifier, no
            |constructor, no companion object and no supertype of its own — an entry's supertype is
            |the enum. `protected` is refused there and nowhere else in this DSL; the frontends say
            |*modifier 'protected' is not applicable inside 'enum entry'*.
            |
            |An entry name shares a namespace with this enum's properties and nested types — Kotlin
            |answers a collision with *conflicting declarations* — but not with its functions, which
            |is measured rather than assumed.
        """.trimMargin(),
        context = "context(t: TypeScope)",
        name = "enumEntry",
        params = listOf("name: String", "vararg args: Expr") + KDOC_PARAM +
            listOf("body: (TypeScope.() -> Unit)? = null"),
        returns = null,
        body = "t.addEnumEntry(name, args, kdoc, body)",
        shadow = "enumEntry is only valid inside an enum class body. Written in a block it would " +
            "silently attach an entry to the enclosing enum.",
    ),
)

/**
 * E3's `` `typealias` ``. Through ADR 0004's six variants — a type alias genuinely takes a visibility
 * and genuinely takes annotations, and both are ordinary Kotlin — and through the generator rather
 * than by hand so that the six overloads come off the same table every other declaration does.
 *
 * **No shadow, and the reasoning is `` `class` ``'s rather than `` `object` ``'s.** A *local* type
 * alias is valid Kotlin behind `-Xlocal-type-aliases`, not invalid Kotlin, so an `@Deprecated(ERROR)`
 * overload would freeze a temporary compiler state into a surface Task 22 locks permanently — D20's
 * argument. The refusal is a run-time `IllegalStateException` naming the flag, with a canary test.
 * `` `typealias` `` is also `context(s: Scope)`, so the innermost scope wins and the block case is
 * reached correctly; the shadows exist for `context(t: TypeScope)` constructs, which resolve from a
 * block body and attach to the wrong container.
 *
 * `typealias` is a **hard** keyword — `fun typealias()` is *function declaration must have a name* —
 * so the canonical spelling is backticked, and there is no alias: `typeAlias` is a re-casing rather
 * than a short form, and ADR 0009's alias rule is about short forms.
 */
private fun typeAliasOverloads(): List<Overload> = VARIANTS.map { v ->
    Overload(
        doc = "`typealias Name = T`${v.doc}.\n\n" +
            "Valid at file level and inside a named type — a nested `typealias` is ordinary Kotlin\n" +
            "and `C.S` resolves, which is the question E1 left open and this round measured on all\n" +
            "three frontends. Not valid in a block body, nor in an enum entry's or an anonymous\n" +
            "object's body, all three of which make it a *local* type alias: that is behind\n" +
            "`-Xlocal-type-aliases` in Kotlin 2.4 and this DSL passes no compiler flag.\n\n" +
            "[typeVariables] are E1's deferred slot. A type alias's take no variance, no `reified`\n" +
            "and — uniquely among this DSL's declarations — no upper bound.",
        context = "context(s: Scope)",
        name = "`typealias`",
        params = v.params() + listOf("name: String", "type: TypeName") +
            typeVariablesParam(true) + KDOC_PARAM,
        returns = null,
        body = """
            |s.declareTypeAlias(
            |    ${v.annotationsArg},
            |    ${v.modifiersArg},
            |    name,
            |    type,
            |    typeVariables,
            |    kdoc,
            |)
        """.trimMargin(),
    )
}

// --- files ----------------------------------------------------------------------------------------

private val IMPORTS = listOf(
    "com.squareup.kotlinpoet.ContextParameter",
    "com.squareup.kotlinpoet.KModifier",
    "com.squareup.kotlinpoet.ParameterSpec",
    "com.squareup.kotlinpoet.TypeName",
    "com.squareup.kotlinpoet.TypeSpec",
    "com.squareup.kotlinpoet.TypeVariableName",
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
            ctorParamOverloads() +
            SUPERTYPES +
            TYPE_BODY +
            typeAliasOverloads()
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
