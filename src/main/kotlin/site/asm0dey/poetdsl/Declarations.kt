package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
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
