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
    check(!hasSuperclass) {
        "superclass: a $kindName can only extend one class, and this one already does."
    }
    hasSuperclass = true
    builder.superclass(type)
    // One `CodeBlock` per argument, not one joined block: KotlinPoet joins them itself, and keeping
    // them separate is what lets it wrap a long supertype list. `%L` of the expression's own code
    // keeps `%T`/`%M` placeholders intact, so imports still resolve.
    args.forEach { builder.addSuperclassConstructorParameter(CodeBlock.of("%L", it.code)) }
}

/** What the generated `superinterface` runs. */
internal fun TypeScope.applySuperinterface(type: TypeName) {
    check(type !in builder.superinterfaces) {
        "superinterface: this $kindName already implements $type."
    }
    builder.addSuperinterface(type)
}
