package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ContextParameter
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName

// E3, deviation D43: `context(c: Ctx) fun f()`.
//
// Notable because this DSL is itself built on context parameters, and because D31 predicted it would
// "leak an `@OptIn` requirement to callers, as `UseSiteTarget.ALL` already does".
//
// **It does not, and the difference is where the annotation sits.** Read off the 2.3.0 jar rather
// than off the documentation:
//
//     ContextParameter                          no @ExperimentalKotlinPoetApi anywhere — not on the
//                                                class, not on either constructor, not on `name` or
//                                                `type`
//     ContextParameterizable                    @ExperimentalKotlinPoetApi
//     ContextParameterizable.Builder            @ExperimentalKotlinPoetApi on every method
//     buildContextParameters(…)                 @ExperimentalKotlinPoetApi
//
// So the opt-in is required to *call the builder methods*, which happens in this file, behind
// `@OptIn` on an `internal` function — and a caller who writes `contextParameter("c", ctx)` names
// only [ContextParameter], which carries no annotation and needs none. That is the opposite of
// `UseSiteTarget.ALL`, where the caller has to name the experimental **enum entry** itself, and where
// a typealias gave the DSL nothing to hide behind. `ContextParametersTest` compiles a consumer
// snippet with no `@OptIn` at all, because a reflected annotation set is a claim about the jar and
// the compiler's answer is the claim that matters (E2f's rider).
//
// The language side, measured one file per cell on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10,
// all three identical:
//
//     context(c: Ctx) fun f(): Int = 1                     clean   context(c: Ctx) class C
//     class C { context(c: Ctx) fun f(): Int = 1 }         clean    context parameters on classes
//     interface I { context(c: Ctx) fun f(): Int }         clean    are unsupported.
//     fun h() { context(c: Ctx) fun f(): Int = 1 }         clean   context(c: Ctx) object O
//     context(c: Ctx) val p: Int get() = 1                 clean    …on classes are unsupported.
//     context(c: Ctx) var p: Int get() = 1 set(v) { }      clean   class C { context(c: Ctx)
//     context(c: Ctx, d: D) fun f(): Int = 1               clean     constructor(x: Int) { } }
//     context(_: Ctx) fun f(): Int = 1                     clean    …on constructors are unsupported.
//     context(c: Ctx) fun String.f(): Int = 1              clean   context(c: Ctx) typealias S = String
//     context(c: Ctx) fun <T> f(t: T): Int = 1             clean    …on type aliases are unsupported.
//     context(c: Ctx<T>) val <T> p: Int get() = 1          clean   context(Ctx) fun f(): Int = 1
//     abstract class A { context(c: Ctx) abstract fun f() } clean    context parameters must be named.
//
//     context(c: Ctx) val p: Int = 1        property with context parameters cannot be initialized
//     context(c: Ctx) val p: Int             because it has no backing field.
//     context(c: Ctx) var p: Int get() = 1
//     context(c: Ctx) val p: Int by D()     context parameters on delegated properties are
//                                            unsupported.
//     context(c: Ctx, c: Ctx) fun f()       conflicting declarations.
//     context(c: Ctx) fun f(c: Int)         conflicting declarations.
//
// The four unsupported *positions* get no slot at all rather than a run-time check, which is the
// strongest form the guard can take — the same call E1 made for `object O<T>`.

/**
 * `context(c: Ctx)` — one context parameter of a function or a property.
 *
 * A descriptor, like [param] and [typeVariable]: it attaches to nothing, so it is context-free and a
 * call in a block body is as harmless as `param(…)` there. Pass the values in the `contextParameters`
 * slot of `` `fun` ``/`func`/[funSpec] or `` `val` ``/`` `var` ``/`property`/[propertySpec].
 *
 * [name] may be `"_"`, which Kotlin allows and calls an anonymous context parameter — the receiver is
 * then reachable only through its members. It may **not** repeat another context parameter's name or
 * a value parameter's; Kotlin answers either with *conflicting declarations*, and both are refused
 * where the two lists meet rather than here, since one descriptor cannot see the other.
 */
public fun contextParameter(name: String, type: TypeName): ContextParameter {
    check(name.isNotBlank()) {
        "contextParameter: the name is blank. Kotlin requires a named context parameter — " +
            "`context(Ctx)` is \"context parameters must be named. Use '_' to declare an anonymous " +
            "context parameter\" — so pass a name, or \"_\"."
    }
    return ContextParameter(name, type)
}

/** Alias of [contextParameter]. */
public fun contextParam(name: String, type: TypeName): ContextParameter = contextParameter(name, type)

/**
 * The two rules that need more than one descriptor to see, checked where the lists meet.
 *
 * [valueParameterNames] is empty for a property, which has none.
 */
internal fun checkContextParameters(
    construct: String,
    owner: String,
    contextParameters: List<ContextParameter>,
    valueParameterNames: List<String>,
) {
    contextParameters.map { it.name }.filterNot { it == "_" }
        .groupingBy { it }.eachCount().forEach { (name, count) ->
            check(count == 1) {
                "$construct: '$owner' declares a context parameter named \"$name\" more than once, " +
                    "which Kotlin answers with \"conflicting declarations\". Rename one, or make it " +
                    "\"_\" if the value is never named."
            }
        }
    contextParameters.forEach { cp ->
        check(cp.name == "_" || cp.name !in valueParameterNames) {
            "$construct: '$owner' has a context parameter and a value parameter both named " +
                "\"${cp.name}\", which Kotlin answers with \"conflicting declarations\". Rename one, " +
                "or make the context parameter \"_\"."
        }
    }
}

/**
 * The two rules a property's context parameters add, and — as importantly — the two they do **not**.
 *
 * A property with context parameters has no backing field, so it can carry neither an initializer nor
 * a delegate. Both are container-independent, which is why they are checked here rather than through
 * [PropertyContainer]:
 *
 *     context(c: Ctx) val p: Int = 1            property with context parameters cannot be
 *     class C { context(c: Ctx) val p: Int = 1 }  initialized because it has no backing field.
 *     context(c: Ctx) val p: Int by D()         context parameters on delegated properties are
 *                                                unsupported.
 *
 * **Two more guards were written here, falsified, and removed — and the measurement says removing
 * them was not merely tidying.** "A context property needs a getter" and "a context `var` needs both
 * accessors" both survived one-at-a-time falsification, because the container machinery already
 * answers them where they are true; and where the container says a property needs no value, they are
 * **false**:
 *
 *     interface I { context(c: Ctx) val p: Int }                clean, all three frontends
 *     interface I { context(c: Ctx) var p: Int }                clean
 *     abstract class A { context(c: Ctx) abstract val p: Int }  clean
 *     abstract class A { context(c: Ctx) abstract var p: Int }  clean
 *
 *     context(c: Ctx) val p: Int                property with context parameters cannot be
 *     class C { context(c: Ctx) val p: Int }     initialized because it has no backing field.
 *                                               — and [PropertyContainer.needsValue] already
 *                                                 refuses exactly these two and not the four above
 *     interface I { context(c: Ctx) var p: Int get() = 1 }   …no backing field — and the accessor
 *                                                             *pair* rule already refuses it in
 *                                                             every container
 *
 * That is the depth inversion this project has shipped three times: a guard verified at the top level
 * whose answer flips one container down. Falsification found it because the guard was unreachable;
 * the control rows say what would have happened if it had not been.
 *
 * Its own function rather than a widening of E2a's extension-property rule, and deliberately: the
 * diagnostics differ, and the delegate row is the **opposite** way round — an extension property may
 * be delegated and a context one may not.
 */
internal fun checkContextProperty(construct: String, name: String, init: Expr?, by: Expr?) {
    check(by == null) {
        "$construct: '$name' has context parameters and a delegate, which is \"context parameters " +
            "on delegated properties are unsupported\" on the JVM, on Kotlin/JS and on Kotlin/Wasm " +
            "alike. Drop the `by` and write the accessors, or drop the context parameters."
    }
    check(init == null) {
        "$construct: '$name' has context parameters and an initializer. A property with context " +
            "parameters has no backing field — \"property with context parameters cannot be " +
            "initialized because it has no backing field\" — so pass a getter instead of init."
    }
}

/**
 * The two `@OptIn` sites, and the only two in this DSL.
 *
 * `ContextParameterizable.Builder.contextParameters` is `@ExperimentalKotlinPoetApi`; the
 * [ContextParameter] values themselves are not. Keeping the opt-in on two `internal` functions is
 * what stops it reaching a caller — see this file's header for the reflected annotation table and
 * `ContextParametersTest` for the compiled proof.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
internal fun FunSpec.Builder.applyContextParameters(contextParameters: List<ContextParameter>) {
    if (contextParameters.isNotEmpty()) contextParameters(contextParameters)
}

/** See [applyContextParameters]. */
@OptIn(ExperimentalKotlinPoetApi::class)
internal fun PropertySpec.Builder.applyContextParameters(contextParameters: List<ContextParameter>) {
    if (contextParameters.isNotEmpty()) contextParameters(contextParameters)
}
