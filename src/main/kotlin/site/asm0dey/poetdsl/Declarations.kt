package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * Adds a type declaration to whichever scope is innermost: a top-level type in a file, a
 * nested type in a type. Kotlin allows local classes but not local named objects, interfaces,
 * enums or annotation classes — [localAllowed] says which is which, and Task 20's shadow
 * members turn the invalid cases into compile errors.
 *
 * The local-class case is valid Kotlin that KotlinPoet 2.3.0 cannot render, so it currently
 * throws too, with a different message — see [localClassIsUnrenderable].
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a future
 * fourth scope breaks the build here rather than falling through silently (D17).
 *
 * The nested type gets a *fresh* root [NameScope] rather than `names.child()`: a nested
 * (non-`inner`) class cannot see an enclosing type's members, so there is nothing for one of
 * its constructor parameters to shadow, and chaining would only rename it for no reason,
 * changing the generated public API. `typeSpec`'s detached path already does this; this makes
 * the attached path agree. `inner class` is the one case where a same-named parameter really
 * would shadow an outer member — accepted as-is rather than special-cased: renaming the
 * parameter would change the public API, which is worse than the shadowing. Member *bodies*
 * (Task 19's local functions and beyond) still chain through `BlockScope.child`, which is the
 * case ADR 0009's shadowing rationale actually describes — this only changes how a *type's own*
 * scope is rooted.
 */
internal fun Scope.declareType(
    builder: TypeSpec.Builder,
    kindName: String,
    name: String,
    localAllowed: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    body: TypeScope.() -> Unit,
) {
    if (this is BlockScope) {
        check(localAllowed) {
            "A local $kindName is not valid Kotlin. Declare it at file or type level."
        }
    }
    // Types only: Kotlin permits function overloads, so duplicate `fun` names are legal and
    // must never go through `declaredTypeNames`.
    check(name !in declaredTypeNames) {
        "A $kindName named \"$name\" is already declared in this scope."
    }
    declaredTypeNames += name

    val scope = TypeScope(builder.addModifiers(modifiers.toList()), NameScope(null), id.child("type"))
    scope.addAll(annotations)
    scope.body()
    val spec = scope.finish()
    when (this) {
        is FileScope -> this.builder.addType(spec)
        is TypeScope -> this.builder.addType(spec)
        is BlockScope -> {
            // A local class *is* valid Kotlin, but KotlinPoet 2.3.0 cannot render one. See
            // `localClassIsUnrenderable` below; the guard is here rather than at the call site
            // so the two reasons stay distinguishable in the message.
            localClassIsUnrenderable()
        }
    }
}

/**
 * Rejects a local class instead of emitting Kotlin that does not compile (Global Constraint 26).
 *
 * `TypeSpec.emit` hardcodes `implicitModifiers = setOf(PUBLIC)` when it calls
 * `CodeWriter.emitModifiers` (KotlinPoet 2.3.0, `TypeSpec.kt:183-186`), and
 * `CodeWriter.shouldEmitPublicModifier` (`CodeWriter.kt:670-690`) then emits an explicit
 * visibility keyword for **every** `TypeSpec`: `public` when none was set, or the one that was.
 * `CodeBlock.of("%L", spec)` therefore always yields `public class Name`, and Kotlin rejects
 * *any* visibility modifier on a local class — measured for `public`, `private`, `internal` and
 * `protected`, all "Modifier 'x' is not applicable to 'local class'". KotlinPoet exposes no way
 * to suppress it (`TypeSpec.emit` is internal, and the `omitImplicitModifiers` escape hatch in
 * `CodeWriter.emitLiteral` applies only to `FunSpec`), and stripping the keyword afterwards
 * would be string surgery on rendered output, which the backend constraint forbids and which
 * would break `%T` import resolution.
 *
 * Flip this to the emission call the moment the backend can render a local class; the
 * `local class rendering is still blocked by KotlinPoet` test in `TypeScopeTest` is the canary
 * that fails when that happens. Task 19's local functions hit the identical wall.
 */
private fun localClassIsUnrenderable(): Nothing = error(
    "A local class cannot be rendered: KotlinPoet 2.3.0 emits an explicit visibility " +
        "modifier on every type, and Kotlin allows none on a local class. Declare it at file " +
        "or type level.",
)

/**
 * `class Name { … }` — top-level in a file, nested in a type, depending on the innermost scope.
 * The local-class case is declared here too, but cannot be rendered yet — see [declareType].
 */
context(s: Scope)
public fun `class`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", name, localAllowed = true, null, null, body)
}

/** `class Name { … }` with modifiers, e.g. `` `class`(DATA.toModifiers(), "User") { … } ``. */
context(s: Scope)
public fun `class`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", name, localAllowed = true, null, modifiers, body)
}

/** Alias of [`class`]. */
context(s: Scope)
public fun klass(name: String, body: TypeScope.() -> Unit) {
    `class`(name, body)
}

/** Alias of [`class`]. */
context(s: Scope)
public fun klass(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    `class`(modifiers, name, body)
}

/** `object Name { … }`. Not valid inside a function body — Kotlin has no local named objects. */
context(s: Scope)
public fun `object`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.objectBuilder(name), "named object", name, localAllowed = false, null, null, body)
}

/** `object Name { … }` with modifiers. Not valid inside a function body. */
context(s: Scope)
public fun `object`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(
        TypeSpec.objectBuilder(name),
        "named object",
        name,
        localAllowed = false,
        null,
        modifiers,
        body,
    )
}

/** `interface Name { … }`. Not valid inside a function body. */
context(s: Scope)
public fun `interface`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.interfaceBuilder(name), "interface", name, localAllowed = false, null, null, body)
}

/** `interface Name { … }` with modifiers. Not valid inside a function body. */
context(s: Scope)
public fun `interface`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(
        TypeSpec.interfaceBuilder(name),
        "interface",
        name,
        localAllowed = false,
        null,
        modifiers,
        body,
    )
}

/**
 * Whether a constructor parameter also declares a property, and if so whether it is mutable.
 * `null` in [constructorParam]'s `kind` slot means a plain parameter with no property.
 *
 * The plan spells this slot `KModifier?` and its call sites pass `KModifier.VAL`/`KModifier.VAR`,
 * but KotlinPoet 2.3.0's `KModifier` has no such entries — `val`/`var` are not modifiers in
 * KotlinPoet's model, which expresses a constructor property as a `PropertySpec` whose
 * `mutable` flag and `%N` initializer tie it back to the parameter. A dedicated enum makes the
 * three legal states exactly the three representable ones, so no runtime kind check is needed
 * or possible. Imported entry-wise (`import site.asm0dey.poetdsl.ParamKind.VAL`) it reads at
 * the call site exactly as the plan writes it. See deviation D19.
 */
public enum class ParamKind {
    /** A read-only property: `val name: T`. */
    VAL,

    /** A mutable property: `var name: T`. */
    VAR,
}

/**
 * Adds a parameter to the primary constructor. [ParamKind.VAL]/[ParamKind.VAR] also add the
 * matching property; null makes it a plain parameter. Returns a handle visible to every
 * sibling member — no nesting, no arity ceiling.
 *
 * One of the two exceptions to the emission rule (`Unit` returns emit, `Expr` returns do
 * not): this both emits and hands back a handle. `` `val` ``/`` `var` `` is the other.
 */
context(t: TypeScope)
public fun constructorParam(kind: ParamKind? = null, name: String, type: TypeName): Expr =
    t.addConstructorParam(kind, null, name, type)

/** [constructorParam] with annotations on the parameter. */
context(t: TypeScope)
public fun constructorParam(
    kind: ParamKind?,
    annotations: Annotations,
    name: String,
    type: TypeName,
): Expr = t.addConstructorParam(kind, annotations, name, type)

/** Alias of [constructorParam]. */
context(t: TypeScope)
public fun ctorParam(kind: ParamKind? = null, name: String, type: TypeName): Expr =
    constructorParam(kind, name, type)

/** Alias of [constructorParam]. */
context(t: TypeScope)
public fun ctorParam(kind: ParamKind?, annotations: Annotations, name: String, type: TypeName): Expr =
    constructorParam(kind, annotations, name, type)

internal fun TypeScope.addConstructorParam(
    kind: ParamKind?,
    annotations: Annotations?,
    name: String,
    type: TypeName,
): Expr {
    // A second constructor parameter named `name` is a compile error in Kotlin with no valid
    // output to preserve, so it is rejected outright rather than renamed to `name2` (ADR 0009,
    // amended by D21) — the same treatment `propertyOf` gives a duplicate property, and for the
    // same reason `declaredConstructorParamNames` is its own set rather than shared with
    // `declaredPropertyNames`: a parameter colliding with a *property* name is a cross-construct
    // collision that still has to uniquify.
    check(name !in declaredConstructorParamNames) {
        "A constructor parameter named \"$name\" is already declared in this scope."
    }
    // The other half of the guard in `constructor`, for the reverse writing order. Same broken
    // output either way, so the same message names both constructs.
    check(!hasSecondaryCtor) { PRIMARY_PLUS_SECONDARY_IS_UNREPRESENTABLE }
    declaredConstructorParamNames += name
    val unique = names.unique(name)
    val param = ParameterSpec.builder(unique, type)
        .apply { annotations?.list?.forEach { addAnnotation(it) } }
        .build()
    ctor.addParameter(param)
    hasCtor = true
    if (kind != null) {
        builder.addProperty(
            PropertySpec.builder(unique, type)
                .mutable(kind == ParamKind.VAR)
                .initializer("%N", param)
                .build(),
        )
    }
    // A bare parameter (kind == null, no property) is still immutable — Kotlin has no `var`
    // constructor parameter without a property — so it is `false`, same as ParamKind.VAL.
    return Expr(
        CodeBlock.of("%L", unique),
        type,
        Prec.ATOM,
        unique,
        id,
        mutable = kind == ParamKind.VAR,
    )
}

/** Detached type builder; returns a KotlinPoet spec, so interop with hand-written KotlinPoet is free. */
public fun typeSpec(modifiers: Modifiers? = null, name: String, body: TypeScope.() -> Unit): TypeSpec {
    val scope = TypeScope(
        TypeSpec.classBuilder(name).addModifiers(modifiers.toList()),
        NameScope(null),
        ScopeId(null, "type"),
    )
    scope.body()
    return scope.finish()
}

// --- functions --------------------------------------------------------------------------------

/**
 * A function parameter. Type-position annotations come free: `param("x", INT.annotated<Positive>())`.
 *
 * [name] is a *request*, not a guarantee: a parameter that would shadow a binding of the enclosing
 * scope is uniquified when the function is built, exactly as a lambda parameter is (ADR 0009). The
 * handle the body receives always carries the name actually rendered.
 */
public fun param(name: String, type: TypeName): ParameterSpec = ParameterSpec.builder(name, type).build()

/**
 * The single implementation behind every function overload, including the detached builders.
 *
 * Names live in a *child* [NameScope] of the parent's, so a parameter that would shadow an
 * enclosing property or constructor parameter is renamed at declaration — and the name is released
 * again when the body ends, the same shape `lambdaOf`, `` `for` `` and `TryChain`'s catch parameters
 * use. Renaming means rebuilding the [ParameterSpec], since the signature and the body handle have
 * to agree on the name.
 *
 * The body block's [ScopeId] is a child of the parent's, so [checkOwned] accepts a handle declared
 * by any enclosing scope — a constructor parameter or property of the surrounding type, for
 * instance — and rejects one smuggled out of a sibling body (ADR 0008). With no parent, the body is
 * a detached root and records foreign scopes instead of rejecting them, exactly like [typeSpec].
 *
 * Return-type inference follows ADR 0007 and is done here because this is the only place that sees
 * both the explicit [returns] and the types the body recorded.
 */
internal fun buildFun(
    name: String,
    isConstructor: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    params: List<ParameterSpec>,
    returns: TypeName?,
    parent: Scope?,
    body: BlockScope.(List<Expr>) -> Unit,
): FunSpec {
    val names = (parent?.names ?: NameScope(null)).child()
    val id = (parent?.id ?: ScopeId(null, "root")).child("fun($name)")
    val recorded = mutableListOf<TypeName?>()
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = names,
        id = id,
        returns = recorded,
        detachedRoot = parent == null,
    )

    // Two parameters of one function with the same name is a compile error in Kotlin with no valid
    // output to preserve, so it is rejected rather than uniquified to `x2` (D21) — the same call
    // `addConstructorParam` and `propertyOf` make. This is *not* the shadowing case below: a
    // parameter colliding with an *enclosing* binding still renames, because there the output is
    // valid and only the name has to move. Function *names* are deliberately exempt from any such
    // check: Kotlin permits overloads.
    params.map { it.name }.groupingBy { it }.eachCount().forEach { (paramName, count) ->
        check(count == 1) {
            "param: a parameter named \"$paramName\" is already declared in this function."
        }
    }

    val declared = params.map { p ->
        val unique = names.unique(p.name)
        if (unique == p.name) p else p.toBuilder(name = unique).build()
    }
    // `%N` rather than `%L`: KotlinPoet escapes a name that is a Kotlin keyword, so
    // `param("value", …)` renders as `` `value` `` in both the signature and the body.
    // A Kotlin function parameter cannot be reassigned, so the handle is a `val`, not
    // "mutability unknown" (D22) — the same call `catch` parameters make.
    val handles = declared.map { p ->
        Expr(CodeBlock.of("%N", p), p.type, Prec.ATOM, p.name, id, mutable = false)
    }
    scope.body(handles)
    scope.flushPending()

    // A constructor's body is a `Unit` body, so `return 1` in one is `e: Return type mismatch`.
    // Inference is skipped for a constructor (see `inferReturnType`), which means nothing else
    // would ever look at what the body recorded — `recorded` is non-empty only if a *value*
    // `return` ran, since the valueless `ret()` records nothing and is legal here. Reachable
    // through a spliced fragment too (`ctor { +stmts { ret(1.lit) } }`), which replays its
    // recorded types into this list at the splice.
    check(!isConstructor || recorded.isEmpty()) {
        "constructor: a constructor cannot return a value. Remove the returned expression, or " +
            "move the code to a function."
    }

    val builder = if (isConstructor) FunSpec.constructorBuilder() else FunSpec.builder(name)
    return builder
        .apply {
            annotations?.list?.forEach { addAnnotation(it) }
            addModifiers(modifiers.toList())
            declared.forEach { addParameter(it) }
            inferReturnType(name, isConstructor, returns, recorded)?.let { returns(it) }
            addCode(scope.builder.build())
        }
        .build()
}

/**
 * ADR 0007's rule, in order: an explicit [returns] wins; no recorded return means `Unit` with the
 * type omitted; all recorded types known and equal means that type; anything else is an error.
 *
 * A constructor has no return type at all, so nothing is inferred for one.
 *
 * The two failures get different messages because they need different fixes read differently: one
 * says the DSL could not know a type, the other says the author's own `return`s disagree. Both name
 * the function and both point at `returns = …`, per the ADR.
 */
private fun inferReturnType(
    name: String,
    isConstructor: Boolean,
    returns: TypeName?,
    recorded: List<TypeName?>,
): TypeName? = when {
    isConstructor -> null
    returns != null -> returns
    recorded.isEmpty() -> null
    else -> {
        val distinct = recorded.distinct()
        check(distinct.size == 1) {
            "Cannot infer the return type of '$name': its returns have different types " +
                "(${distinct.joinToString { it?.toString() ?: "unknown" }}). Pass returns = … explicitly."
        }
        checkNotNull(distinct.single()) {
            "Cannot infer the return type of '$name': the returned expression's type is unknown. " +
                "Pass returns = … explicitly."
        }
    }
}

/**
 * Adds a function to whichever scope is innermost: top-level in a file, a member of a type, or —
 * were it renderable — a local function in a block.
 *
 * Unlike a type name, a *function* name is never checked for duplicates: Kotlin permits overloads,
 * so two `` `fun`("show", …) `` calls in one container are legal and must not go through
 * [Scope.declaredTypeNames].
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a future fourth
 * scope breaks the build here rather than falling through silently (D17).
 */
internal fun Scope.declareFun(spec: FunSpec) {
    when (this) {
        is FileScope -> builder.addFunction(spec)
        is TypeScope -> builder.addFunction(spec)
        // A local function *is* valid Kotlin, but KotlinPoet 2.3.0 cannot render one, the same
        // wall Task 10's local classes hit. The guard sits here, at the dispatch, so the reason
        // stays attached to the branch it disables.
        is BlockScope -> localFunIsUnrenderable()
    }
}

/**
 * Rejects a local function instead of emitting Kotlin that does not compile (Global Constraint 26).
 *
 * `CodeWriter.emitLiteral` splices a `FunSpec` with `implicitModifiers = setOf(KModifier.PUBLIC)`
 * unless `omitImplicitModifiers` is set (KotlinPoet 2.3.0, `CodeWriter.kt:416-427`), and the only
 * caller that sets it is `FileSpec`'s script body (`FileSpec.kt:202`) — never a `CodeBlock` nested
 * in a function body. `CodeWriter.shouldEmitPublicModifier` then emits `public`, and Kotlin rejects
 * every visibility modifier on a local function. Declaring one explicitly does not help: `private`,
 * `internal` and `protected` suppress the implicit `public` but are themselves illegal there.
 * KotlinPoet exposes no way to reach `omitImplicitModifiers` (`FunSpec.emit` is internal), and
 * stripping the keyword afterwards would be string surgery on rendered output, which the backend
 * constraint forbids and which would break `%T` import resolution.
 *
 * Flip this to `emitCode(CodeBlock.of("%L", spec))` the moment the backend can render a local
 * function; the `local function rendering is still blocked by KotlinPoet` test in `FunctionsTest`
 * is the canary that fails when that happens.
 */
private fun localFunIsUnrenderable(): Nothing = error(
    "A local function cannot be rendered: KotlinPoet 2.3.0 emits an implicit `public` on every " +
        "function spliced into a code block, and Kotlin allows no visibility modifier on a local " +
        "function. Declare it at file or type level.",
)

/**
 * `fun name() { … }` — top-level, member or local, depending on the innermost scope. The
 * local case is dispatched here too, but cannot be rendered yet — see [declareFun].
 */
context(s: Scope)
public fun `fun`(name: String, returns: TypeName? = null, body: BlockScope.() -> Unit) {
    s.declareFun(buildFun(name, false, null, null, emptyList(), returns, s) { body() })
}

/** `fun name(p1: T) { … }`; the body receives the parameter's handle. */
context(s: Scope)
public fun `fun`(
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, null, listOf(p1), returns, s) { (a) -> body(a) })
}

/** [`fun`] with modifiers, e.g. `` `fun`(PRIVATE.toModifiers(), "greet", param("who", STRING)) ``. */
context(s: Scope)
public fun `fun`(
    modifiers: Modifiers,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, modifiers, listOf(p1), returns, s) { (a) -> body(a) })
}

/** The list form: for more than eight parameters, or a list computed at generation time. */
context(s: Scope)
public fun `fun`(
    name: String,
    params: List<ParameterSpec>,
    returns: TypeName? = null,
    body: BlockScope.(List<Expr>) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, null, params, returns, s, body))
}

/** Alias of [`fun`]. */
context(s: Scope)
public fun func(name: String, returns: TypeName? = null, body: BlockScope.() -> Unit) {
    `fun`(name, returns, body)
}

/**
 * The message both halves of the primary/secondary guard raise.
 *
 * A class with a primary constructor requires every secondary constructor to delegate to it with
 * `: this(…)`. KotlinPoet can express that (`FunSpec.Builder.callThisConstructor`), but this DSL
 * exposes no way to say *which* arguments to pass, and there is no correct default — so the pair is
 * rejected outright rather than rendered as `public constructor(other: String) { … }` under a
 * primary constructor, which is `e: Primary constructor call expected.` (Global Constraint 26).
 */
private const val PRIMARY_PLUS_SECONDARY_IS_UNREPRESENTABLE: String =
    "constructor: a class cannot have both a primary constructor (from constructorParam) and a " +
        "secondary `constructor`, because the DSL cannot express the required `: this(…)` " +
        "delegation call. Fold the parameters into one constructor."

/** A constructor written as a member; it has no return type and none is inferred. */
context(t: TypeScope)
public fun `constructor`(body: BlockScope.() -> Unit) {
    check(!t.hasCtor) { PRIMARY_PLUS_SECONDARY_IS_UNREPRESENTABLE }
    t.hasSecondaryCtor = true
    t.builder.addFunction(buildFun("<init>", true, null, null, emptyList(), null, t) { body() })
}

/** [`constructor`] with one parameter; the body receives its handle. */
context(t: TypeScope)
public fun `constructor`(p1: ParameterSpec, body: BlockScope.(Expr) -> Unit) {
    check(!t.hasCtor) { PRIMARY_PLUS_SECONDARY_IS_UNREPRESENTABLE }
    t.hasSecondaryCtor = true
    t.builder.addFunction(buildFun("<init>", true, null, null, listOf(p1), null, t) { (a) -> body(a) })
}

/** Alias of [`constructor`]. */
context(t: TypeScope)
public fun ctor(body: BlockScope.() -> Unit) {
    `constructor`(body)
}

/** Detached function builder; returns a KotlinPoet spec, so interop is free. */
public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
): FunSpec = buildFun(name, false, null, modifiers, emptyList(), returns, null) { body() }

/** [funSpec] with one parameter; the body receives its handle. */
public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
): FunSpec = buildFun(name, false, null, modifiers, listOf(p1), returns, null) { (a) -> body(a) }

/**
 * Detached property builder. The type is mandatory for the same reason it is on a `` `val` ``
 * property: KotlinPoet cannot infer one (ADR 0003).
 */
public fun propertySpec(
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
): PropertySpec {
    check(init == null || by == null) {
        "propertySpec: '$name' cannot have both an initializer and a delegate."
    }
    val spec = PropertySpec.builder(name, type, modifiers.toList())
    init?.let { spec.initializer("%L", it.code) }
    by?.let { spec.delegate("%L", it.code) }
    return spec.build()
}
