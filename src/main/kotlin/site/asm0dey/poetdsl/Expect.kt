package site.asm0dey.poetdsl

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
// | superclass arguments | — | **none**: `TypeSpec.emit` silently *drops* them (`TypeSpec.kt:239`), so `expect class E : Base(1)` renders as `expect class E : Base` |
//
// So KotlinPoet's own checks are a fragment of the rule in three different shapes — the wrong
// exception type, the right one with a message naming neither construct, an exception thrown from
// `toString()` rather than from a DSL call, and one case of silently dropped output. Guarding what
// KotlinPoet guards, where it guards it, is what left this family half-closed for three rounds.

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
 * How a member says it is `expect` when **both** sources can apply: its own `EXPECT` modifier (the
 * file level's answer, and a detached builder's) or the `expect` type it is declared in. Spelled
 * once so the two halves of the rule are never described differently.
 */
internal fun expectSubject(subject: String): String =
    "$subject is `expect` — by its own EXPECT modifier, or by the `expect` type it is declared in — and"
