package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName
// KotlinPoet declares its own `parameterizedBy` as a member extension of `ParameterizedTypeName`'s
// companion (its `get` names are `@JvmName`s), so reaching it from inside the same-named DSL
// function below needs an alias — an unaliased import would resolve back to this file's.
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy as kotlinPoetParameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asTypeName
import kotlin.reflect.typeOf

// Deviation D31/E1: the type vocabulary. Everything in this file is a **descriptor** — a pure
// function of its arguments, with no `context(…)` receiver and nothing emitted. That is what makes
// the whole file usable in a *signature*, at the call site, where the declaration being written does
// not exist yet: `` `class`("Box", param(VAL, "item", T), typeVariables = listOf(T)) ``.
//
// It is also why nothing here needs a `BlockScope` shadow (ADR 0002): a shadow guards a construct
// that resolves through an enclosing scope's context parameter and would silently attach itself to
// the wrong declaration. A descriptor attaches to nothing, so writing one in a block body is exactly
// as harmless as writing `param(…)` there.
//
// `STAR` is deliberately **not** re-exported. KotlinPoet's own `com.squareup.kotlinpoet.STAR` is
// public and sits in the same import block callers already take `INT`, `STRING` and `LIST` from, its
// name is already the best one a star projection can have, and a second spelling of a constant would
// be surface with nothing behind it. `LIST.of(STAR)` reads `List<*>` as written.

// --- naming a type ------------------------------------------------------------------------------

/**
 * `com.example.User` — a class by package and name, for a type that is **not on the generator's own
 * classpath**. The missing twin of [member], and the normal case in code generation: the type being
 * named is usually one this same run is about to write.
 *
 * [reference] is the other direction and is `reified`, so it needs the type to exist at *generation*
 * time — right for `kotlin.String` and for an annotation the generator itself depends on, useless
 * for the output's own types.
 *
 * [nested] names an enclosed type, outermost first: `className("com.example", "Outer", "Inner")` is
 * `com.example.Outer.Inner`. [simpleName] is a required parameter rather than part of the vararg so
 * that a package-only call cannot be written at all — `ClassName("com.example")` is an
 * `IllegalArgumentException` from KotlinPoet, and the DSL has no use for a class with no name.
 */
public fun className(packageName: String, simpleName: String, vararg nested: String): ClassName =
    ClassName(packageName, listOf(simpleName) + nested)

/**
 * `List<String>` from a type on the generator's own classpath, **type arguments intact**.
 *
 * [reference] cannot do this and never could: it is built on `T::class`, which is the erased runtime
 * class, so `reference<List<String>>()` could only ever have named `kotlin.collections.List` and
 * nothing else — which is why it now rejects a type carrying any arguments instead of erasing them.
 * This is built on `typeOf<T>()` instead, which the compiler fills in at the call site, so
 * nullability, nesting, star projections and use-site variance all survive:
 *
 *     typeReference<Map<String, List<Int?>>>()   // Map<String, List<Int?>>, fully qualified
 *     typeReference<List<*>>()                   // kotlin.collections.List<*>
 *     typeReference<Array<out Number>>()         // kotlin.Array<out kotlin.Number>
 *
 * Returns [TypeName], not [ClassName], because a parameterized type is not a class name — so this is
 * a sibling of [reference] rather than a replacement for it (ADR 0010). Use [reference] where a
 * [ClassName] is what the slot wants: `member(reference<System>(), …)`, `annotation(cls, …)`,
 * `superclass(…)`.
 *
 * **A function type does not survive this route.** `typeReference<(String) -> Int>()` is
 * `kotlin.Function1<kotlin.String, kotlin.Int>` — valid Kotlin that compiles, but not what anyone
 * writes, and a receiver is lost outright (`String.() -> Unit` is also `Function1<String, Unit>`).
 * Both measured on KotlinPoet 2.3.0. Use [functionType] for those.
 *
 * For a type the generator run is itself creating, there is nothing to reflect on: compose it, with
 * [className] and [parameterizedBy].
 */
public inline fun <reified T> typeReference(): TypeName = typeOf<T>().asTypeName()

/** Alias of [typeReference]. */
public inline fun <reified T> typeRef(): TypeName = typeReference<T>()

// --- composing a type ---------------------------------------------------------------------------

/**
 * `List<User>` — applies type arguments to a class name.
 *
 * The composition route, and the only one that works for a type the generator is writing rather than
 * importing: `className("com.example", "Box").parameterizedBy(STRING)` names `Box<String>` whether or
 * not `Box` exists yet. Arguments nest and mix freely with everything else here —
 * `MAP.of(STRING, LIST.of(out(NUMBER)))` is `Map<String, List<out Number>>`, and
 * `LIST.of(STAR)` is `List<*>`.
 *
 * Zero arguments is rejected rather than rendered: `List<>` is not Kotlin, and a caller who wants the
 * raw name already has it — this is an extension *on* it.
 */
public fun ClassName.parameterizedBy(vararg arguments: TypeName): ParameterizedTypeName {
    check(arguments.isNotEmpty()) {
        "parameterizedBy: '$this' was given no type arguments, and `$simpleName<>` is not Kotlin. " +
            "Pass at least one, or use the class name on its own."
    }
    return kotlinPoetParameterizedBy(*arguments)
}

/** Alias of [parameterizedBy], for the nesting that reads badly spelled out: `MAP.of(STRING, LIST.of(user))`. */
public fun ClassName.of(vararg arguments: TypeName): ParameterizedTypeName = parameterizedBy(*arguments)

/**
 * `out Number` — a **use-site** variance projection, written on a type argument.
 *
 * This is the `out` in `List<out Number>`, not the `out` in `class Box<out T>`. Declaration-site
 * variance belongs to the type parameter itself and is [typeVariable]'s `variance` argument.
 */
public fun out(type: TypeName): WildcardTypeName = WildcardTypeName.producerOf(type)

/**
 * `in String` — a **use-site** variance projection, written on a type argument.
 *
 * See [out]: declaration-site variance is [typeVariable]'s `variance` argument instead. Spelled with
 * backticks at the declaration because `in` is a hard keyword; a call site may write
 * `` `in`(STRING) `` exactly as it writes `` `val` `` and `` `fun` ``.
 */
public fun `in`(type: TypeName): WildcardTypeName = WildcardTypeName.consumerOf(type)

/**
 * `(String) -> Int` — a function type.
 *
 * The one shape [typeReference] cannot express: reflection sees a function type as
 * `kotlin.Function1<String, Int>`, which compiles but is not what anyone writes, and loses a receiver
 * entirely. Built here from its parts instead, so every form is reachable:
 *
 *     functionType(STRING, returns = INT)                        // (String) -> Int
 *     functionType(returns = UNIT)                               // () -> Unit
 *     functionType(receiver = STRING, returns = UNIT)            // String.() -> Unit
 *     functionType(STRING, returns = UNIT, suspending = true)    // suspend (String) -> Unit
 *
 * [returns] has no default on purpose: Kotlin has no function type without a return type, and `Unit`
 * is a choice the caller should have to write. It is named-only, because it follows a vararg.
 *
 * Parameter *names* — `(name: String) -> Int` — are not expressible here. They are cosmetic in a
 * function type, KotlinPoet reaches them only through `ParameterSpec`s, and nothing in the DSL needs
 * them; see the E1 report.
 */
public fun functionType(
    vararg parameters: TypeName,
    returns: TypeName,
    receiver: TypeName? = null,
    suspending: Boolean = false,
): LambdaTypeName = LambdaTypeName.get(receiver, parameters = parameters, returnType = returns)
    .copy(suspending = suspending)

/** Alias of [functionType]. */
public fun funType(
    vararg parameters: TypeName,
    returns: TypeName,
    receiver: TypeName? = null,
    suspending: Boolean = false,
): LambdaTypeName = functionType(*parameters, returns = returns, receiver = receiver, suspending = suspending)

// --- declaring a type parameter -------------------------------------------------------------------

/**
 * `T`, `T : Any`, `in K`, `reified T` — a **type parameter** of a declaration, and, being a
 * [TypeName], the thing that stands for it everywhere in that declaration's signature and body.
 *
 * One value, used twice: once in the `typeVariables` slot of the declaration that *binds* it, and
 * then wherever the generated Kotlin *mentions* it.
 *
 *     val t = typeVariable("T")
 *     `class`("Box", param(VAL, "item", t), typeVariables = listOf(t)) { }   // class Box<T>(val item: T)
 *
 * That is why it is a descriptor and not a `context(t: TypeScope)` construct: a primary-constructor
 * parameter is evaluated at the call site (D23), before the type exists, so a type parameter it
 * mentions has to exist earlier still. See the E1 report for the options weighed.
 *
 * [bounds] are the upper bounds: `typeVariable("T", ANY)` is `T : Any`, and a self-referential bound
 * works because the value is built before it is used —
 * `typeVariable("T", COMPARABLE.of(typeVariable("T")))` is `T : Comparable<T>`. With no bounds the
 * type parameter is implicitly `Any?`, which is what Kotlin means by a bare `<T>`.
 *
 * [variance] is **declaration-site** variance — the `in`/`out` in `class Cache<in K, out V>` — and is
 * valid only on a class or interface. Kotlin allows none on a function's type parameter, and
 * `` `fun` `` rejects one rather than rendering it. Use-site projections on a type *argument* are
 * [out] and [`in`] instead.
 *
 * [reified] is valid only on an `inline` function's type parameter; `` `fun` `` checks that too.
 *
 * **Not checked: that a type parameter mentioned in a signature is bound by something.** Writing
 * `` `fun`("f", param("x", t)) { } `` and forgetting `typeVariables = listOf(t)` renders
 * `fun f(x: T)`, which `kotlinc` answers with `Unresolved reference 'T'` on the generated file. The
 * DSL does not pre-empt that, deliberately: the set of type parameters legitimately visible at a
 * declaration includes an enclosing class's (and an `inner` class's enclosing class's, and not a
 * `companion object`'s enclosing class's), plus whatever a spliced `` +funSpec `` brought with it, so
 * a check here would reject valid generator code as readily as it caught the mistake. The failure it
 * would catch is loud and local at the caller's next compile, which is the trade this DSL takes only
 * where the alternative is *silently wrong* output rather than *obviously broken* output.
 */
public fun typeVariable(
    name: String,
    vararg bounds: TypeName,
    variance: KModifier? = null,
    reified: Boolean = false,
): TypeVariableName {
    // KotlinPoet's own guard is a `require` reading "IN and OUT are the only sensible variances" —
    // an IllegalArgumentException naming neither this construct nor the type parameter. Global
    // Constraint: `check`/`error`, naming the construct.
    check(variance == null || variance == KModifier.IN || variance == KModifier.OUT) {
        "typeVariable: '$name' has variance $variance, and a Kotlin type parameter takes only " +
            "`in` or `out`. Pass KModifier.IN, KModifier.OUT, or nothing."
    }
    return TypeVariableName(name, bounds = bounds, variance = variance).copy(reified = reified)
}

/** Alias of [typeVariable]. */
public fun typeVar(
    name: String,
    vararg bounds: TypeName,
    variance: KModifier? = null,
    reified: Boolean = false,
): TypeVariableName = typeVariable(name, bounds = bounds, variance = variance, reified = reified)

/**
 * What every declaration that carries type parameters runs before it renders them: the three things
 * KotlinPoet 2.3.0 does not catch, or catches too late and too anonymously.
 *
 * - **Duplicates.** `FunSpec.Builder.addTypeVariable` and `TypeSpec.Builder`'s appends never
 *   deduplicate, so two type parameters named `T` render as `fun <T, T> f()` with no complaint from
 *   either KotlinPoet or this DSL — measured, and invalid Kotlin (Global Constraint 26). Rejected
 *   rather than renamed, for the reason D21 gives for a duplicate constructor parameter: there is no
 *   valid output to preserve, and renaming would invent a public API name nobody asked for.
 * - **Variance where Kotlin allows none.** `fun <out T> f()` is `e: Variance annotations are only
 *   allowed for type parameters of classes and interfaces`. KotlinPoet renders it happily.
 * - **`reified` off an `inline` function.** KotlinPoet *does* reject this, but from
 *   `FunSpec.Builder.build` with `require`, as "only type parameters of inline functions can be
 *   reified!" — no construct, no function name, and the wrong exception type for this DSL.
 * - **Type parameters on an `enum class`.** `enum class E<T>` renders and does not compile: an
 *   enum's entries are singletons of the class itself, so Kotlin gives it no type parameters at
 *   all. `annotation class Ann<T>`, which looks equally unusual, **is** valid Kotlin — compiled, not
 *   assumed, in `TypesCompileTest` — so only the enum case is rejected.
 *
 * @param construct the DSL spelling to name in the message — `` `fun` ``, `` `class` ``.
 * @param owner the declaration's own name, so the message says which one.
 * @param varianceAllowed true for a class or interface, false for a function.
 * @param reifiedAllowed true when the declaration carries `inline`.
 * @param isEnum true when the declaration carries [KModifier.ENUM], which forbids them outright.
 */
internal fun checkTypeVariables(
    construct: String,
    owner: String,
    typeVariables: List<TypeVariableName>,
    varianceAllowed: Boolean,
    reifiedAllowed: Boolean,
    isEnum: Boolean = false,
) {
    check(!isEnum || typeVariables.isEmpty()) {
        "$construct: '$owner' is an enum class with type parameters, and Kotlin allows an enum class " +
            "none — its entries are singletons of the class itself. Drop the type parameters, or " +
            "declare a plain class."
    }
    typeVariables.groupingBy { it.name }.eachCount().forEach { (name, count) ->
        check(count == 1) {
            "$construct: '$owner' declares a type parameter named \"$name\" more than once."
        }
    }
    if (!varianceAllowed) {
        typeVariables.forEach { tv ->
            check(tv.variance == null) {
                "$construct: type parameter \"${tv.name}\" of '$owner' declares `${tv.variance!!.name.lowercase()}` " +
                    "variance, which Kotlin allows only on a class or interface. Drop the variance, or " +
                    "project the type argument at the use site with out(…)/`in`(…)."
            }
        }
    }
    if (!reifiedAllowed) {
        typeVariables.forEach { tv ->
            check(!tv.isReified) {
                "$construct: type parameter \"${tv.name}\" of '$owner' is `reified`, which Kotlin allows " +
                    "only on an `inline` function's type parameter. Add KModifier.INLINE, or drop the " +
                    "`reified`."
            }
        }
    }
}
