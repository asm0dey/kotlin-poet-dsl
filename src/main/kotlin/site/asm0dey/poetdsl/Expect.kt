package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

// The `expect` family, as one unit.
//
// **An `expect` container refuses every member that carries a body or a value.** One sentence, one
// message shape, one predicate — because the three items that were filed as three unrelated open
// items (a `val`/`var` primary-constructor parameter, a function body, an `init` block) are one
// rule seen at three of its call sites, and filing them separately is why the list grew every round.
//
// The enumeration this file is derived from is **every path that reaches
// `TypeSpec.Builder.addProperty`, `addFunction`, `addInitializerBlock`, `primaryConstructor` or
// `superclass`/`addSuperclassConstructorParameter`** — not every path that reaches `checkProperty`,
// which is the framing that missed `addConstructorParam` and would have missed the two members
// nothing had filed at all:
//
// | # | builder call | reached from | the member | asked here? |
// |---|---|---|---|---|
// | 1 | `addProperty` | `bind` → `propertyOf` → [checkProperty] | a property | yes — [PropertyContainer.isExpectContext] |
// | 2 | `addProperty` | [addConstructorParam] | a `val`/`var` primary-constructor parameter | yes — this file |
// | 3 | `addProperty` | `PropertySpec.unaryPlus` | a spliced `PropertySpec` | **no** — the detached boundary, see below |
// | 4 | `addFunction` | `declareFun` ← [buildFun] | a member function | yes — this file |
// | 5 | `addFunction` | `addSecondaryConstructor` ← [buildFun] | a secondary constructor | yes — this file |
// | 6 | `addFunction` | `FunSpec.unaryPlus` | a spliced `FunSpec` | **no** — the detached boundary |
// | 7 | `addInitializerBlock` | [addInitializerBlock] | an `init` block | yes — this file |
// | 8 | `primaryConstructor` | `TypeScope.finish` ← [addConstructorParam] | the primary constructor | yes, at row 2 — a *plain* parameter is legal (measured) |
// | 9 | `addSuperclassConstructorParameter` | `applySuperclass` | the supertype's arguments | yes — this file |
// | 9b | `superclass` | `applySuperclass`, judged in [TypeScope.finish] | the supertype *itself*, one level down | yes — [nestedSupertypeRenderGap], because at depth KotlinPoet writes the parentheses whether or not there are arguments |
// | 10 | `addType` | `declareType`, `addCompanionObject`, `TypeSpec.unaryPlus` | a nested classifier | not a refusal: `expect` is *inherited* here (D36), and the splice is D39 |
//
// Rows 3 and 6 are deliberately not judged, and that is the one place this rule stops: a detached
// `+spec` is built before anything knows where it will be spliced (ADR 0008's Task 21 amendment,
// [PropertyContainer.UNKNOWN], D39), and `+propertySpec(…, init = …)` inside an `expect` type is the
// documented escape hatch `UninitializedPropertyCompileTest` uses to render the very shape this
// family refuses, which is what makes D36 a measurement of this DSL's own output. What that costs is
// recorded there and in the round's report, with all three of its behaviours measured.
//
// Every row below is one kotlinc 2.4.10 run per frontend — `kotlinc`, `kotlinc-js` with
// `kotlin-stdlib-js.klib` and `kotlinc-wasm` with `kotlin-stdlib-wasm-js.klib`, all from the same
// distribution, `-Xmulti-platform` throughout. **All three answer identically on every row**, which
// is why the message says "on the JVM, on Kotlin/JS and on Kotlin/Wasm alike" and not "on the JVM".
//
//     expect class E(val x: Int)                          expected class constructor cannot have a
//     expect class E { class N(val x: Int) }              property parameter.
//     expect class E { companion object { class N(val x: Int) } }
//     expect class E { fun f(): Int = 1 }                 expected declaration cannot have a body.
//     expect class E { class N { fun f(): Int = 1 } }
//     expect class E { companion object { fun f(): Int = 1 } }
//     expect object O { fun f(): Int = 1 }
//     expect interface I { fun f(): Int = 1 }
//     expect fun f(): Int = 1
//     expect class E { constructor(p: Int) { println(p) } }
//     expect class E { init { println(1) } }
//     expect class E { class N { init { } } }             — an *empty* block, too
//     expect object O { init { println(1) } }
//     expect class E : Base(1)                            expected classes cannot initialize
//     expect class E(x: Int) : Base(x)                    supertypes.
//     expect class E { class N : Base(1) }
//     expect class E { class N : Base() }                 — the *empty* argument list, too, which
//     expect class E { class N(z: Int) : Base() }           is what a nested `superclass(Base)`
//     expect class E { companion object : Base() }          renders; see [nestedSupertypeRenderGap]
//     expect class E : Base { constructor(p: Int) : super(p) }    explicit delegation call for
//     expect class E { constructor(p: Int) : this() }             constructor of expected class is
//     expect class E { class N : Base { constructor(p: Int) : super(p) } }         prohibited.
//
// The boundary, measured the same way and clean everywhere — **a member of an `expect` type is a
// signature, and a signature still renders**. Refusing any of these would be the false rejection the
// project's two-directional rule exists to prevent:
//
//     expect class E { fun f(): Int }              expect class E(x: Int)
//     expect class E { class N { fun f(): Int } }  expect class E { class N(x: Int) }
//     expect class E { constructor(p: Int) }       expect class E : Base
//     expect fun f(): Int                          expect class E { fun f(x: Int = 1): Int }
//     expect class E(x: Int) { constructor(p: Long) }      — and *without* a delegation call, which
//     expect class E(x: Int) : Base { constructor(p: Long) }  an ordinary class is refused for
//                                                             ("primary constructor call expected")
//     expect class E { annotation class N(val x: Int) }    — the two kinds whose parameters Kotlin
//     expect class E { value class V(val y: Int) }           *requires* to be `val`; see
//                                                            [PROPERTY_PARAM_KINDS]
//     expect class E { class N : Iface }                   — a superinterface is never parenthesized
//     expect class E { class N : Base { constructor(p: Int) } }  — the one supertype shape KotlinPoet
//                                                                  renders without parentheses
//     expect class E { class N { var String.a: Int } }     — an extension property is a signature
//     expect class E { class N { val String.a: Int } }       here too, at every depth
//
// The last two are why D25's "every secondary constructor must delegate to the primary one" carries
// an `expect` exemption since this round: the delegation call is prohibited here, so requiring one
// would leave a secondary constructor of an `expect` class with a primary constructor unspellable —
// refused from one side without it and from the other side with it.
//
// What KotlinPoet does with the same shapes, measured with raw KotlinPoet 2.3.0 one builder per row,
// and the reason none of it can be the boundary of this rule:
//
// | member | keyword suppressed inside `expect class E` | its own check |
// |---|---|---|
// | property | **direct members only** (`TypeSpec.kt:313` passes `kind.implicitPropertyModifiers(modifiers)`) | `addProperty` `require`s no initializer and no accessors — direct members only, `IllegalArgumentException` |
// | function | **at every depth** (`TypeSpec.kt:335`/`348` pass `modifiers + implicitModifiers`) | `build()` `require`s an empty body — direct members only, `IllegalArgumentException`; `FunSpec.emit` `check`s it again at every depth, from `toString()` |
// | nested type | **never** — `TypeSpec.emit` hardcodes `setOf(PUBLIC)` as its own implicit modifiers (`TypeSpec.kt:184`), which is D39's third row |
// | `init` block | — | `addInitializerBlock` `check`s `EXPECT !in modifiers` — direct members only |
// | superclass arguments | — | **none**: `TypeSpec.emit` silently *drops* them (`TypeSpec.kt:239`), so `expect class E : Base(1)` renders as `expect class E : Base` — **direct members only**, so one level down they are kept *and so are the parentheses around an empty list*, which is [nestedSupertypeRenderGap] |
// | a `val`/`var` primary-constructor parameter | — | `addProperty` `require`s a null initializer under `EXPECT` (`TypeSpec.kt:725-733`) — **direct members only**, with no exemption for the two kinds Kotlin exempts, which is what makes `expect annotation class A(val x: Int)` unrenderable and `expect class E { annotation class N(val x: Int) }` free |
//
// So KotlinPoet's own checks are a fragment of the rule in four different shapes — the wrong
// exception type, the right one with a message naming neither construct, an exception thrown from
// `toString()` rather than from a DSL call, and one case of silently dropped output. Guarding what
// KotlinPoet guards, where it guards it, is what left this family half-closed for three rounds.
//
// **And every one of those "direct members only" rows is why this round exists.** Three guards were
// verified at the top level and inverted one level down, all three in the same direction the
// previous round's method could not see: falsification shows a guard is load-bearing against the
// *test set*, and only a control row — the nearest *valid* neighbour of a refused shape, measured —
// can show it is not over-broad against the *language*. For a guard keyed on a container fact, the
// nested case is the control that matters, because the container's own modifiers are exactly what
// KotlinPoet stops reading one level down.

/**
 * Whether a declaration written *in* this scope is `expect` — the container's half of the rule.
 *
 * **One predicate, read at every site**, because a container fact consulted at some construction
 * sites and not others is this project's recurring defect (five instances now: D29/D30's two notions
 * on one field, `isExpect` reaching two of its three construction sites, the E2 cleanup round's
 * modifier gate living on one branch of an `if`, that round's three container facts each read for
 * one of the two questions they answer, and this round's `addConstructorParam`).
 *
 * [TypeScope.isExpect], not `EXPECT in builder.modifiers`: Kotlin writes no keyword on a classifier
 * nested inside an `expect` one and applies every `expect` rule to it anyway (D36), so the fact is
 * inherited to every depth — including a companion object's body, and including the members of a
 * detached `typeSpec(EXPECT.toModifiers(), …)`. A [FileScope] answers `false`: a *file* is not an
 * `expect` container, and a top-level declaration is `expect` only by carrying the modifier itself,
 * which is the other half of the rule and is read from the declaration rather than from here.
 */
internal val Scope.isExpectContainer: Boolean
    get() = this is TypeScope && isExpect

/**
 * The one refusal the whole family raises.
 *
 * [what] names the construct's member and says which of the two `expect` sources applies;
 * [diagnostic] is the frontends' own sentence, quoted verbatim; [remedy] says what to write instead.
 * The middle clause is invariant and is the rule itself, so that every message in the family reads
 * as one rule rather than as four checks that happen to agree.
 *
 * `error`, so this is an [IllegalStateException] naming the construct — never `require`, and never
 * KotlinPoet's own [IllegalArgumentException] (Global Constraint 26).
 */
internal fun expectRefusal(construct: String, what: String, diagnostic: String, remedy: String): Nothing = error(
    "$construct: $what. An `expect` declaration is a signature — it carries no body and no value — " +
        "so this is \"$diagnostic\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. $remedy",
)

/**
 * The two classifier kinds Kotlin **exempts** from *expected class constructor cannot have a
 * property parameter*, because both of them *require* their primary-constructor parameters to be
 * `val` — so the rule the refusal implements has nothing left to refuse. Measured on `kotlinc`,
 * `kotlinc-js` and `kotlinc-wasm` 2.4.10, one file per row, all three identical:
 *
 *     expect class E { annotation class N(val x: Int) }   clean         — the exemption
 *     expect class E { value class V(val y: Int) }        clean         — the exemption
 *     expect class E { annotation class N(x: Int) }       'val' keyword is missing in annotation
 *                                                         parameter.
 *     expect class E { value class V(y: Int) }            value class primary constructor must only
 *                                                         have final read-only ('val') property
 *                                                         parameters.
 *
 * The set is **measured rather than reasoned**, and the four control rows that keep it to two are
 * every other classifier modifier a nested type can carry — `data`, `inner`, `sealed`, `abstract`,
 * `open` and `enum` are all *expected class constructor cannot have a property parameter* exactly as
 * a bare `class` is, on all three frontends. Copying a plausible-looking list here would have been
 * the mistake the accessor exemption avoided one round ago.
 *
 * Read off the **immediate** builder's own modifiers, never inherited: this is a fact about what
 * *this* declaration renders, the same reading `PropertyContainer.expectAllowed` and
 * `externalAllowed` make, and it is what keeps [annotationOrValueRenderGap] reachable.
 */
internal val PROPERTY_PARAM_KINDS: Set<KModifier> = setOf(KModifier.ANNOTATION, KModifier.VALUE)

/**
 * The other side of [PROPERTY_PARAM_KINDS], and the one place in this family where the refusal is a
 * **backend gap rather than a language rule** — so it does not go through [expectRefusal], whose
 * invariant middle clause would claim a frontend diagnostic that no frontend produces.
 *
 * `expect annotation class A(val x: Int)` and `expect value class V(val y: Int)` are clean on
 * `kotlinc`, `kotlinc-js` and `kotlinc-wasm` alike, and KotlinPoet 2.3.0 renders neither by any
 * route: `TypeSpec.Builder.addProperty` (`TypeSpec.kt:725-733`) `require`s a null `initializer`
 * whenever the builder's *own* modifiers contain `EXPECT`, with no exemption for either kind, and a
 * `val`/`var` primary-constructor parameter is a property carrying a `%N` initializer — the only
 * shape KotlinPoet's `constructorProperties` recognises, and the only way KotlinPoet models
 * `val`/`var` on a parameter at all (D19).
 *
 * At `fa12efe` the same shape raised that `require`'s own `IllegalArgumentException: properties in
 * expect classes can't have initializers`, Global Constraint 26's forbidden type naming neither
 * construct. The refusal is therefore not new; its type and its message are.
 *
 * The escape hatch the message names is measured, not assumed — a spec built without `EXPECT` passes
 * `addProperty`, and `toBuilder().addModifiers(EXPECT)` never runs that `require` again.
 */
internal fun annotationOrValueRenderGap(name: String, keyword: String, kindModifier: KModifier): String {
    val kind = kindModifier.name.lowercase()
    return "constructorParam: '$name' declares a `$keyword` property on the primary constructor of " +
        "an `expect $kind class`, and KotlinPoet 2.3.0 renders no such thing: a `val`/`var` " +
        "primary-constructor parameter is a property with a `%N` initializer, and " +
        "`TypeSpec.Builder.addProperty` rejects every property carrying an initializer when the " +
        "builder's own modifiers contain EXPECT. The Kotlin is valid on all three frontends — " +
        "${if (kindModifier == KModifier.ANNOTATION) "an" else "a"} $kind class parameter must be " +
        "`val`, so the rule against a property parameter in an `expect` class does not reach it — " +
        "which makes this a backend gap and not a language rule. Build the type with " +
        "typeSpec(${kindModifier.name}.toModifiers(), …) and add the modifier afterwards with " +
        ".toBuilder().addModifiers(EXPECT).build(), or declare it inside the `expect` type, where " +
        "Kotlin makes the keyword implicit and this DSL renders the parameter."
}

/**
 * How a member says it is `expect` when **both** sources can apply: its own `EXPECT` modifier (the
 * file level's answer, and a detached builder's) or the `expect` type it is declared in. Spelled
 * once so the two halves of the rule are never described differently.
 */
internal fun expectSubject(subject: String): String =
    "$subject is `expect` — by its own EXPECT modifier, or by the `expect` type it is declared in — and"
