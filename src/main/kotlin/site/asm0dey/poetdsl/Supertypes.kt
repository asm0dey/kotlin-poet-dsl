package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

// Deviation D26: what a type extends and implements.
//
// These are *body-level* constructs, called inside `` `class` { } ``, deliberately: the `class`
// signature already carries ADR 0004's six variants against D23's nine arities plus the list form,
// and another positional slot would multiply that matrix a third time for something written at most
// once per type. A supertype is also rarely the interesting part of a declaration, so it reads
// better on its own line than wedged between the name and the body.
//
// Both take a `TypeName`, which is what `reference<T>()` and `member(…)` already return, so imports
// resolve through KotlinPoet's `%T` as everywhere else.
//
// The public `superclass`/`superinterface` entry points are *generated*, from the same list that
// generates their `@Deprecated(ERROR)` shadows — `buildSrc/src/main/kotlin/ArityGenerator.kt`, D7.
// What stays here is the machinery they call, which is where the guards belong: they are about what
// the type already has.

/**
 * The message the header-arguments guard raises, as D25 leaves it.
 *
 * `class Foo : Bar(1) { constructor(y: Int) : this() }` is not valid Kotlin however the secondary
 * constructor delegates: with no primary constructor to carry it, the header call is
 * `e: Supertype initialization is impossible without a primary constructor.` (measured). KotlinPoet
 * rejects the same shape from its own side (`types without a primary constructor cannot specify
 * secondary constructors and superclass constructor parameters`, an `IllegalArgumentException` from
 * `TypeSpec.Builder.build`), but only once the whole type has been built and with no idea which
 * construct wrote what — so [TypeScope.finish] checks it first, naming both constructs.
 *
 * Both ways out are now open, which is what D25 changed: give the type a primary constructor and
 * keep the arguments here (the secondary constructors then delegate to it with `` `this`(…) ``), or
 * drop them from the header and pass them from a secondary constructor's own `` `super`(…) ``.
 *
 * A function of [kindName] rather than a constant, so it reads the same way its two neighbouring
 * messages do: an `object` that hits it says "a named object", not "a class".
 */
internal fun superclassArgsPlusSecondary(kindName: String): String =
    "superclass: a $kindName cannot pass superclass constructor arguments in its header and also " +
        "declare a secondary `constructor` with no primary constructor to carry them — a supertype " +
        "cannot be initialized in the header without one. Give the $kindName a primary constructor " +
        "(`class`(…, param(VAL, …)) or constructorParam) and keep the arguments here, or drop them " +
        "and pass them from the secondary constructor with `super`(…)."

/**
 * What the generated `superclass` runs: the two things the *type* has to be asked about first.
 *
 * The third — header arguments alongside a secondary constructor — moved to [TypeScope.finish] with
 * D25, because a `constructorParam` written after this call can still make that combination legal
 * and an eager check here would reject valid Kotlin on writing order alone.
 */
internal fun TypeScope.applySuperclass(type: TypeName, args: Array<out Expr>) {
    check(kindName != "interface") {
        "superclass: an interface has no superclass. Use superinterface to extend another interface."
    }
    check(superclassType == null) {
        "superclass: a $kindName can only extend one class, and this one already does."
    }
    // The member of the `expect` family nothing had filed, and the only one whose failure was
    // *silent*: `TypeSpec.emit` drops the superclass constructor arguments when the type carries
    // `EXPECT` (`TypeSpec.kt:239`), so `` `class`(EXPECT, "E") { superclass(Base, 1.lit) } ``
    // rendered `expect class E : Base` and the argument reached no output at all — partial output,
    // which Global Constraint 26 forbids as loudly as invalid output. One level down nothing is
    // dropped and `expect class E { class N : Base(1) }` renders, which all three frontends answer
    // with *expected classes cannot initialize supertypes* — as they do for the header form, with or
    // without a primary constructor. See [Expect].
    //
    // **`args.isNotEmpty()` is the whole rule only here, where the builder carries `EXPECT` itself.**
    // One level down KotlinPoet emits `%T(%L)` and an *empty* argument list still renders the
    // parentheses, so `expect class E { class N : Base }` has no spelling and `expect class E {
    // class N : Base() }` draws the same diagnostic this one does. That is
    // [nestedSupertypeRenderGap], checked in [TypeScope.finish] because the parentheses depend on
    // constructors that can be written after this call. This comment used to say "refusing the
    // *supertype* would be wrong in both directions: `expect class E : Base` is valid everywhere",
    // which is true of the direct case and the opposite of the truth one level down.
    if (isExpectContainer && args.isNotEmpty()) {
        expectRefusal(
            "superclass",
            "$type is given constructor arguments in an `expect` type",
            "expected classes cannot initialize supertypes",
            "Drop the arguments — KotlinPoet renders `: $type` and drops them silently — and " +
                "initialize the supertype on the `actual` declaration.",
        )
    }
    superclassType = type
    builder.superclass(type)
    // One `CodeBlock` per argument, not one joined block: KotlinPoet joins them itself, and keeping
    // them separate is what lets it wrap a long supertype list. `%L` of the expression's own code
    // keeps `%T`/`%M` placeholders intact, so imports still resolve.
    args.forEach { builder.addSuperclassConstructorParameter(CodeBlock.of("%L", it.code)) }
}

/**
 * The member of the `expect` family that only exists **one level down**, and the reason this round
 * exists: a guard verified at the top level whose answer inverts below it.
 *
 * `TypeSpec.emit` renders a superclass as `%T(%L)` and falls back to a bare `%T` in exactly two
 * cases (KotlinPoet 2.3.0, `TypeSpec.kt:234-245`):
 *
 *     if (primaryConstructor != null || funSpecs.none(FunSpec::isConstructor)) {   // :238
 *       if (!areNestedExternal && !modifiers.contains(EXPECT)) {                   // :239
 *         CodeBlock.of("%T(%L)", it, superclassConstructorParametersBlock)
 *       } else { CodeBlock.of("%T", it) }
 *     } else { CodeBlock.of("%T", it) }
 *
 * `modifiers` there is the **immediate** builder's own — and Kotlin puts no `expect` keyword on a
 * classifier nested inside an `expect` one (D36), so at depth the fallback is unreachable through
 * that branch and an *empty* argument list still renders `: Base()`. Measured, one file per row,
 * `kotlinc` / `kotlinc-js` / `kotlinc-wasm` 2.4.10, all three identical:
 *
 *     expect class E { class N : Base() }               supertype initialization is impossible
 *     expect class E { class N { class M : Base() } }    without a primary constructor.  and
 *     expect class E { companion object : Base() }       expected classes cannot initialize
 *                                                        supertypes.
 *     expect class E { class N(z: Int) : Base() }       expected classes cannot initialize supertypes.
 *
 * The control rows, clean on all three, are what keeps the refusal from being over-broad — and three
 * of them are shapes this DSL still renders:
 *
 *     expect class E { class N : Base }                          — no spelling reaches this
 *     expect class E { class N(z: Int) : Base }                  — nor this
 *     expect class E { class N : Iface }                         superinterface, never parenthesized
 *     expect class E { class N : Base { constructor(p: Int) } }  the `:238` fallback, reachable
 *     expect class E : Base                                      the direct case
 *     class Outer { class N : Base() }                           outside `expect`
 *     expect class E { class N }                                 what `superclass(ANY)` renders
 *
 * The last row is the term [TypeScope.finish] carries and this snippet does not show: `TypeSpec.emit`
 * filters `ANY` out of the supertype list at `TypeSpec.kt:235`, one line *above* the `:238` decision,
 * so `superclass(ANY)` emits no supertype clause in any container and this refusal has nothing to
 * refuse. Firing on it refused a shape that renders valid Kotlin, with a message asserting a
 * `: kotlin.Any()` KotlinPoet never writes.
 *
 * So this is half language rule and half **render gap**, and the message says both: the Kotlin the
 * DSL would emit is refused everywhere, *and* the Kotlin the author wanted is unreachable through
 * KotlinPoet in the common shape. The remedy names the one shape that does reach it.
 */
internal fun nestedSupertypeRenderGap(kindName: String, type: TypeName): Nothing = expectRefusal(
    "superclass",
    "$type is extended by a $kindName declared in an `expect` type, and KotlinPoet 2.3.0 renders " +
        "that as `: $type()` — it emits `%T(%L)` for a supertype and drops the parentheses only " +
        "when the type's own modifiers carry EXPECT, which Kotlin forbids on a nested classifier, " +
        "or when the type has secondary constructors and no primary one (`TypeSpec.kt:238-239`)",
    "expected classes cannot initialize supertypes",
    "Use superinterface if $type is an interface; or give this $kindName a secondary `constructor` " +
        "and no constructorParam, which is the one shape KotlinPoet renders as `: $type`; or drop " +
        "the supertype and declare it on the `actual` declaration.",
)

/** What the generated `superinterface` runs. */
internal fun TypeScope.applySuperinterface(type: TypeName) {
    check(type !in builder.superinterfaces) {
        "superinterface: this $kindName already implements $type."
    }
    builder.addSuperinterface(type)
}
