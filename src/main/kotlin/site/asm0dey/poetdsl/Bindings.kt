package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName

/**
 * The handle a binding hands back: the (possibly uniquified) name it was actually declared
 * under, tagged with the scope that declared it so ADR 0008 can judge it at every later use.
 */
private fun Scope.handle(unique: String, type: TypeName?): Expr =
    Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id)

/**
 * `val name: T = init` / `var name by delegate` as a local statement.
 *
 * Both `init` and `by` are validated against this block first: a handle from a scope that does
 * not enclose this one is rejected before anything is emitted (ADR 0008).
 *
 * A local is the only binding Kotlin can infer a type for, so all four combinations of
 * type/initializer are renderable — including the bare `var t: Int` that a later `assign`
 * completes. The one hole is a binding with neither a type nor a value: nothing could be
 * inferred from it, and emitting `val x = null` instead would be silently wrong output.
 */
private fun BlockScope.bindLocal(
    mutable: Boolean,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
): Expr {
    check(type != null || init != null || by != null) {
        "Binding '$name' needs a type, an initializer or a delegate."
    }
    init?.let { checkOwned(it) }
    by?.let { checkOwned(it) }
    val unique = names.unique(name)
    val code = CodeBlock.builder()
        .add("%L·%L", if (mutable) "var" else "val", unique)
        .apply { if (type != null) add(":·%T", type) }
        .apply {
            if (init != null) add("·=·%L", init.code)
            if (by != null) add("·by·%L", by.code)
        }
        .build()
    emitCode(code)
    return handle(unique, type ?: init?.type)
}

/**
 * The `PropertySpec` for a file- or type-level binding.
 *
 * KotlinPoet cannot infer, so the type is mandatory here — the single rule that makes a property
 * more than a local with a different parent (ADR 0003).
 *
 * A duplicate name *is* an error (ADR 0009, amended by D21): two properties named `username` in
 * one container is a compile error in Kotlin, and there is no valid output for renaming to
 * preserve, so the second `username` is rejected rather than invented as `username2`.
 * [Scope.declaredPropertyNames] catches that before the [NameScope] uniquifier ever runs. A
 * property colliding with a *different* construct — most notably a constructor parameter — is
 * untouched by this check and still goes through the uniquifier, exactly as ADR 0009 originally
 * prescribed.
 */
private fun Scope.propertyOf(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
): PropertySpec {
    checkNotNull(type) {
        "Property '$name' requires an explicit type; KotlinPoet cannot infer it."
    }
    check(name !in declaredPropertyNames) {
        "A property named \"$name\" is already declared in this scope."
    }
    declaredPropertyNames += name
    return PropertySpec.builder(names.unique(name), type, modifiers.toList())
        .mutable(mutable)
        .apply {
            init?.let { initializer("%L", it.code) }
            by?.let { delegate("%L", it.code) }
            annotations?.list?.forEach { addAnnotation(it) }
        }
        .build()
}

/**
 * One binding construct for all three scopes, taking the union of their parameters and rejecting
 * the combinations that scope cannot express (ADR 0003).
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a fourth scope
 * breaks the build here rather than falling through silently (D17).
 */
private fun Scope.bind(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
): Expr {
    check(init == null || by == null) {
        "Binding '$name' cannot have both an initializer and a delegate."
    }
    return when (this) {
        is BlockScope -> {
            check(annotations == null && modifiers == null) {
                "A local binding ('$name') cannot carry annotations or modifiers."
            }
            bindLocal(mutable, name, type, init, by)
        }

        is FileScope -> {
            val spec = propertyOf(mutable, annotations, modifiers, name, type, init, by)
            builder.addProperty(spec)
            handle(spec.name, type)
        }

        is TypeScope -> {
            val spec = propertyOf(mutable, annotations, modifiers, name, type, init, by)
            builder.addProperty(spec)
            handle(spec.name, type)
        }
    }
}

/**
 * A read-only binding: a local `val` in a block, a property at file or type level.
 *
 * Emits **and** returns a handle — one of exactly two exceptions to "Unit emits, `Expr` does
 * not" (the other is [constructorParam]). The handle carries the name the binding was actually
 * declared under, which is not always [name]: a colliding name is uniquified (ADR 0009).
 *
 * @param type mandatory for a property (KotlinPoet cannot infer); optional for a local, which
 *   may instead take its type from [init] or from a delegate.
 * @param init the initializer. Mutually exclusive with [by].
 * @param by the delegate expression, as in `by lazy { … }`.
 */
context(s: Scope)
public fun `val`(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    s.bind(false, null, null, name, type, init, by)

/** [`val`] with modifiers, e.g. `` `val`(PRIVATE.toModifiers(), "limit", INT, 10.lit) ``. */
context(s: Scope)
public fun `val`(
    modifiers: Modifiers,
    name: String,
    type: TypeName? = null,
    init: Expr? = null,
    by: Expr? = null,
): Expr = s.bind(false, null, modifiers, name, type, init, by)

/** Alias of the declaration-level [`val`]. `prop` is property *access* — a different thing. */
context(s: Scope)
public fun property(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    `val`(name, type, init, by)

/** A mutable binding: a local `var` in a block, a `var` property at file or type level. */
context(s: Scope)
public fun `var`(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    s.bind(true, null, null, name, type, init, by)

/** [`var`] with modifiers. */
context(s: Scope)
public fun `var`(
    modifiers: Modifiers,
    name: String,
    type: TypeName? = null,
    init: Expr? = null,
    by: Expr? = null,
): Expr = s.bind(true, null, modifiers, name, type, init, by)

/** `a = b`. Named because `=` is not overloadable. */
context(b: BlockScope)
public infix fun Expr.assign(value: Expr) {
    b.checkOwned(this)
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("%L·=·%L", code, value.code))
}

/** `a op= b`, for the five compound assignments Kotlin defines. */
private fun BlockScope.compound(target: Expr, op: String, value: Expr) {
    checkOwned(target)
    checkOwned(value)
    emitCode(CodeBlock.of("%L·%L·%L", target.code, op, value.code))
}

// The five compound assignments exist in emitting form only: Kotlin requires `plusAssign` and
// friends to return `Unit`, so operator syntax can never be pure. The pure twins are
// `stmts { total += x }` and `total assign (total + x)`.

/** `a += b`. */
context(b: BlockScope)
public operator fun Expr.plusAssign(value: Expr) {
    b.compound(this, "+=", value)
}

/** `a -= b`. */
context(b: BlockScope)
public operator fun Expr.minusAssign(value: Expr) {
    b.compound(this, "-=", value)
}

/** `a *= b`. */
context(b: BlockScope)
public operator fun Expr.timesAssign(value: Expr) {
    b.compound(this, "*=", value)
}

/** `a /= b`. */
context(b: BlockScope)
public operator fun Expr.divAssign(value: Expr) {
    b.compound(this, "/=", value)
}

/** `a %= b`. */
context(b: BlockScope)
public operator fun Expr.remAssign(value: Expr) {
    b.compound(this, "%=", value)
}
