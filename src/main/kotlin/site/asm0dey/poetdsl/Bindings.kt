package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName

// `val`, `var` and the `property` alias are generated into `DeclarationVariants.kt` by
// `buildSrc/src/main/kotlin/ArityGenerator.kt`, in ADR 0004's six variants — which is what makes
// an annotated property expressible at all: every hand-written entry point passed `null` into
// [bind]'s `annotations` slot. The compound assignments below are hand-written, because they have
// no variants: a local carries neither annotations nor modifiers.

/**
 * The handle a binding hands back: the (possibly uniquified) name it was actually declared
 * under, tagged with the scope that declared it so ADR 0008 can judge it at every later use.
 */
private fun Scope.handle(unique: String, type: TypeName?, mutable: Boolean? = null): Expr =
    Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id, mutable = mutable)

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
    return handle(unique, type ?: init?.type, mutable)
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
    typeVariables: List<TypeVariableName>,
    receiver: TypeName?,
    setterParam: String,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
): PropertySpec {
    checkNotNull(type) {
        "Property '$name' requires an explicit type; KotlinPoet cannot infer it."
    }
    val keyword = if (mutable) "`var`" else "`val`"
    checkProperty(keyword, name, mutable, init, by, typeVariables, receiver, setter, getter)
    // An extension property is keyed by receiver *and* name: `val String.size` and `val Int.size`
    // are two different declarations and both are legal in one file, while two of the same name on
    // the same receiver is the compile error D21 rejects. A plain property's key is its bare name,
    // exactly as before.
    val key = receiver?.let { "$it." }.orEmpty() + name
    check(key !in declaredPropertyNames) {
        "A property named \"$key\" is already declared in this scope."
    }
    declaredPropertyNames += key
    // `uniqueMemberName`, not `names.unique`: a property is visible in every member body, so it is
    // declared at the member level — but its own initializer is evaluated where a plain primary
    // constructor parameter is also in scope, so it still has to step over one (D30).
    //
    // An *extension* property is exempt, and has to be: its name is only ever reached through a
    // receiver, so it shadows nothing and nothing shadows it. Uniquifying it renamed `val Int.size`
    // to `size2` merely because `val String.size` had been declared above it (measured) — two
    // declarations Kotlin keeps apart by receiver, and a rename here would invent a public API name
    // for a collision that does not exist.
    val declaredName = if (receiver == null) uniqueMemberName(name) else name
    return PropertySpec.builder(declaredName, type, modifiers.toList())
        .mutable(mutable)
        .apply {
            addTypeVariables(typeVariables)
            receiver?.let { receiver(it) }
            init?.let { initializer("%L", it.code) }
            by?.let { delegate("%L", it.code) }
            annotations?.list?.forEach { addAnnotation(it) }
            addAccessors(this@propertyOf, name, type, setterParam, setter, getter)
        }
        .build()
}

/**
 * Builds whichever accessor bodies were passed, against [parent] as the enclosing scope.
 *
 * Both go through [buildFun], so an accessor body is a nested block exactly like a member
 * function's: its names chain from the member level and its [ScopeId] is a child of it, which is
 * what makes ADR 0008 judge a handle used inside one — in either direction, and reached through a
 * *property* rather than a function — with no change to [checkOwned]. A null [parent] is
 * [propertySpec]'s detached case, and [buildFun] gives it the same detached-root treatment it gives
 * [funSpec].
 *
 * The accessor is passed the *property's* name rather than `get()`/`set()`:
 * `FunSpec.getterBuilder()` names itself, and the name is what lets ADR 0008's rejection message say
 * which property's accessor a smuggled handle turned up in.
 */
internal fun PropertySpec.Builder.addAccessors(
    parent: Scope?,
    name: String,
    type: TypeName,
    setterParam: String,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
) {
    getter?.let { body ->
        getter(buildFun(name, FunKind.GETTER, null, null, emptyList(), emptyList(), null, null, parent) { body() })
    }
    setter?.let { body ->
        setter(
            buildFun(
                name, FunKind.SETTER, null, null, listOf(param(setterParam, type)), emptyList(), null, null, parent,
            ) { (value) -> body(value) },
        )
    }
}

/**
 * Everything E2a's slots make expressible that is not valid Kotlin, rejected before KotlinPoet sees
 * it (Global Constraint 26). KotlinPoet catches exactly one of these itself — `PropertySpec`'s
 * `require(mutable || setter == null)` — and as an `IllegalArgumentException` whose message names
 * neither the property nor the construct.
 *
 * The `field` keyword deliberately gets no construct of its own: `expression("field")` already
 * renders it, and a `field` construct would be valid only inside an accessor body, so it would need
 * a `BlockScope` shadow it could not have — the accessor body *is* a `BlockScope` — reopening ADR
 * 0002 for two words.
 *
 * One rejection here is deliberately **narrower than the language rule it comes from**: a `var` with
 * exactly one custom accessor and no initializer or delegate. Kotlin's actual rule is that any
 * property whose accessors reach the backing field needs an initializer, and that is not decidable
 * from this signature — a custom setter writing `field` reaches it too, and `field` arrives as an
 * opaque `expression("field")`. The pair guarded below is the half that *is* decidable; the rest is
 * left to the caller's own compile rather than guessed at, because guessing wrong there refuses
 * valid generator code. See the comment at the check.
 */
internal fun checkProperty(
    keyword: String,
    name: String,
    mutable: Boolean,
    init: Expr?,
    by: Expr?,
    typeVariables: List<TypeVariableName>,
    receiver: TypeName?,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
) {
    check(mutable || setter == null) {
        "$keyword: '$name' is a `val` and has no setter. Declare it with `var`, or drop the setter."
    }
    // "Delegated property cannot have accessors with non-default implementations" — the delegate
    // *is* the accessor pair.
    check(by == null || (getter == null && setter == null)) {
        "$keyword: '$name' is delegated with `by`, and a delegated property cannot have accessors. " +
            "Drop the delegate, or drop the accessors."
    }
    // A `var` that customises exactly one accessor gets the **default** other one, which reads or
    // writes the backing field — and a property that has a backing field and no initializer does not
    // compile anywhere: `Property must be initialized.` at file level, in a class, an object, a
    // companion object and an anonymous object; `Property in interface cannot have a backing field.`
    // in an interface; `Property with getter implementation cannot be abstract.` under `abstract`;
    // `'lateinit' modifier is not allowed on properties with a custom getter or setter.` under
    // `lateinit`; and the same under `external`/`expect`. All measured with kctfork, which is why
    // this needs no modifier or container awareness to be sound.
    //
    // **This is the decidable pair only, and deliberately stops there.** The general rule —
    // "a property whose accessors touch the backing field needs an initializer" — is undecidable
    // here: a *custom* setter that writes `field` forces an initializer too, and `field` is spelled
    // `expression("field")`, an opaque `CodeBlock` this DSL cannot look inside. Completing the rule
    // would start refusing valid generator code, which is the expensive direction. Do not "finish"
    // this check.
    if (mutable && receiver == null && init == null && by == null) {
        val missing = if (getter == null) "getter" else "setter"
        val present = if (getter == null) "setter" else "getter"
        check((getter == null) == (setter == null)) {
            "$keyword: '$name' has a $present but no $missing, so Kotlin generates the $missing, " +
                "which needs a backing field, which needs an initializer. Add an initializer, or " +
                "write the $missing as well."
        }
    }
    if (receiver != null) {
        check(init == null) {
            "$keyword: '$name' is an extension property, which has no backing field, so it cannot " +
                "have an initializer. Move the value into the getter."
        }
        check(getter != null || by != null) {
            "$keyword: '$name' is an extension property, which has no backing field, so it needs a " +
                "getter (or a delegate)."
        }
        check(!mutable || setter != null || by != null) {
            "$keyword: '$name' is a mutable extension property, which has no backing field, so it " +
                "needs a setter as well as a getter (or a delegate)."
        }
    }
    // A property's type parameters take no declaration-site variance and no `reified`, for the same
    // reasons a function's do not.
    checkTypeVariables(keyword, name, typeVariables, varianceAllowed = false, reifiedAllowed = false)
    if (typeVariables.isEmpty()) return
    checkNotNull(receiver) {
        "$keyword: '$name' declares type parameters but has no receiver. Kotlin allows a property's " +
            "type parameter only where its receiver type uses it."
    }
    typeVariables.forEach { variable ->
        check(receiver.mentions(variable)) {
            "$keyword: type parameter \"${variable.name}\" of '$name' is not used in the receiver " +
                "type. Kotlin allows a property's type parameter only where its receiver type uses it."
        }
    }
}

/**
 * Whether [variable] occurs anywhere in this type — `T` itself, `List<T>`, `out T`, `(T) -> Unit`.
 *
 * Compared by name rather than by value: the [TypeVariableName] written into the receiver may carry
 * different bounds from the one in the `typeVariables` list, and Kotlin resolves it by name.
 */
private fun TypeName.mentions(variable: TypeVariableName): Boolean = when (this) {
    is TypeVariableName -> name == variable.name || bounds.any { it.mentions(variable) }
    is ParameterizedTypeName -> typeArguments.any { it.mentions(variable) }
    is WildcardTypeName -> (inTypes + outTypes).any { it.mentions(variable) }
    is LambdaTypeName ->
        this.receiver?.mentions(variable) == true ||
            parameters.any { it.type.mentions(variable) } ||
            returnType.mentions(variable)

    else -> false
}

/**
 * One binding construct for all three scopes, taking the union of their parameters and rejecting
 * the combinations that scope cannot express (ADR 0003).
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a fourth scope
 * breaks the build here rather than falling through silently (D17).
 */
internal fun Scope.bind(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
    typeVariables: List<TypeVariableName> = emptyList(),
    receiver: TypeName? = null,
    setterParam: String = "value",
    setter: (BlockScope.(Expr) -> Unit)? = null,
    getter: (BlockScope.() -> Unit)? = null,
): Expr {
    check(init == null || by == null) {
        "Binding '$name' cannot have both an initializer and a delegate."
    }
    return when (this) {
        is BlockScope -> {
            check(annotations == null && modifiers == null) {
                "A local binding ('$name') cannot carry annotations or modifiers."
            }
            // A local variable has no accessors ("Local variable with getter/setter"), no receiver
            // and no type parameters — all three are property-only in Kotlin, and all three would
            // otherwise be silently dropped here, since `bindLocal` builds a `CodeBlock` and has
            // nowhere to put them.
            check(typeVariables.isEmpty() && receiver == null && setter == null && getter == null) {
                "A local binding ('$name') cannot have accessors, an extension receiver or type " +
                    "parameters; only a property can."
            }
            bindLocal(mutable, name, type, init, by)
        }

        is FileScope -> {
            val spec = propertyOf(
                mutable, annotations, modifiers, name, type, init, by,
                typeVariables, receiver, setterParam, setter, getter,
            )
            builder.addProperty(spec)
            handle(spec.name, type, mutable)
        }

        is TypeScope -> {
            val spec = propertyOf(
                mutable, annotations, modifiers, name, type, init, by,
                typeVariables, receiver, setterParam, setter, getter,
            )
            builder.addProperty(spec)
            handle(spec.name, type, mutable)
        }
    }
}

/** `a = b`. Named because `=` is not overloadable. */
context(b: BlockScope)
public infix fun Expr.assign(value: Expr) {
    check(mutable != false) {
        "assign: '${name ?: this}' is a val and cannot be reassigned."
    }
    b.checkOwned(this)
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("%L·=·%L", code, value.code))
}

/** `a op= b`, for the five compound assignments Kotlin defines. */
private fun BlockScope.compound(target: Expr, op: String, opName: String, value: Expr) {
    check(target.mutable != false) {
        "$opName: '${target.name ?: target}' is a val and cannot be reassigned."
    }
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
    b.compound(this, "+=", "plusAssign", value)
}

/** `a -= b`. */
context(b: BlockScope)
public operator fun Expr.minusAssign(value: Expr) {
    b.compound(this, "-=", "minusAssign", value)
}

/** `a *= b`. */
context(b: BlockScope)
public operator fun Expr.timesAssign(value: Expr) {
    b.compound(this, "*=", "timesAssign", value)
}

/** `a /= b`. */
context(b: BlockScope)
public operator fun Expr.divAssign(value: Expr) {
    b.compound(this, "/=", "divAssign", value)
}

/** `a %= b`. */
context(b: BlockScope)
public operator fun Expr.remAssign(value: Expr) {
    b.compound(this, "%=", "remAssign", value)
}
