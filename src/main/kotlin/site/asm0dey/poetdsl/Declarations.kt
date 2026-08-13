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
 */
internal fun Scope.declareType(
    builder: TypeSpec.Builder,
    kindName: String,
    localAllowed: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    body: TypeScope.() -> Unit,
) {
    val scope = TypeScope(builder.addModifiers(modifiers.toList()), names.child(), id.child("type"))
    scope.addAll(annotations)
    scope.body()
    val spec = scope.finish()
    when (this) {
        is FileScope -> this.builder.addType(spec)
        is TypeScope -> this.builder.addType(spec)
        is BlockScope -> {
            check(localAllowed) {
                "A local $kindName is not valid Kotlin. Declare it at file or type level."
            }
            // A local class *is* valid Kotlin, but KotlinPoet 2.3.0 cannot render one. See
            // `localClassIsUnrenderable` below; the guard is here rather than at the call site
            // so the two reasons stay distinguishable in the message.
            localClassIsUnrenderable(kindName)
        }
    }
}

/**
 * Rejects a local class instead of emitting Kotlin that does not compile (Global Constraint 26).
 *
 * `TypeSpec.emit` hardcodes `implicitModifiers = setOf(PUBLIC)` when it calls
 * `CodeWriter.emitModifiers` (KotlinPoet 2.3.0, `TypeSpec.kt:182-185`), and
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
private fun localClassIsUnrenderable(kindName: String): Nothing = error(
    "A local $kindName cannot be rendered: KotlinPoet 2.3.0 emits an explicit visibility " +
        "modifier on every type, and Kotlin allows none on a local class. Declare it at file " +
        "or type level.",
)

/**
 * `class Name { … }` — top-level in a file, nested in a type, depending on the innermost scope.
 * The local-class case is declared here too, but cannot be rendered yet — see [declareType].
 */
context(s: Scope)
public fun `class`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", localAllowed = true, null, null, body)
}

/** `class Name { … }` with modifiers, e.g. `` `class`(DATA.toModifiers(), "User") { … } ``. */
context(s: Scope)
public fun `class`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", localAllowed = true, null, modifiers, body)
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
    s.declareType(TypeSpec.objectBuilder(name), "named object", localAllowed = false, null, null, body)
}

/** `object Name { … }` with modifiers. Not valid inside a function body. */
context(s: Scope)
public fun `object`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.objectBuilder(name), "named object", localAllowed = false, null, modifiers, body)
}

/** `interface Name { … }`. Not valid inside a function body. */
context(s: Scope)
public fun `interface`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.interfaceBuilder(name), "interface", localAllowed = false, null, null, body)
}

/** `interface Name { … }` with modifiers. Not valid inside a function body. */
context(s: Scope)
public fun `interface`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.interfaceBuilder(name), "interface", localAllowed = false, null, modifiers, body)
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
    return Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id)
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
