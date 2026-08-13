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

/**
 * The message both halves of the header-arguments guard raise.
 *
 * `class Foo : Bar(1) { constructor(y: Int) }` is not valid Kotlin: the arguments in the header give
 * `Foo` an implicit primary constructor, and a secondary constructor of a class that has a primary
 * one must delegate with `: this(…)`. KotlinPoet rejects the same pair from its own side
 * (`types without a primary constructor cannot specify secondary constructors and superclass
 * constructor parameters`, an `IllegalArgumentException` from `TypeSpec.Builder.build`), but only
 * once the whole type has been built and with no idea which construct wrote what — so this fires
 * first, at the call that creates the conflict, whichever of the two is written second.
 *
 * The other legal home for those arguments is the secondary constructor's own `: super(…)` call,
 * which D25 adds; until then a class that needs both a superclass call and secondary constructors
 * must give the superclass call a primary constructor to hang from.
 */
internal const val SUPERCLASS_ARGS_PLUS_SECONDARY: String =
    "superclass: a class cannot pass superclass constructor arguments in its header and also " +
        "declare a secondary `constructor`, because the arguments make the header a primary " +
        "constructor that the secondary one would have to delegate to. Give the class a primary " +
        "constructor (`class`(…, param(VAL, …)) or constructorParam) and keep the arguments here, " +
        "or drop the arguments."

/**
 * `: Base(args)` — the class this type extends, and the arguments its constructor is called with.
 *
 * With no [args] the supertype is written `: Base()`, which is what a no-argument superclass call
 * looks like. Arguments may name this type's own primary-constructor parameters — `class User(val
 * id: Long) : Entity(id)` — which is why the parameters D23 puts in the `` `class` `` signature are
 * declared before the body runs.
 *
 * An interface has no superclass; extending one is [superinterface]'s job, in an interface body
 * exactly as in a class body.
 */
context(t: TypeScope)
public fun superclass(type: TypeName, vararg args: Expr) {
    check(t.kindName != "interface") {
        "superclass: an interface has no superclass. Use superinterface to extend another interface."
    }
    check(!t.hasSuperclass) {
        "superclass: a ${t.kindName} can only extend one class, and this one already does."
    }
    check(args.isEmpty() || !t.hasSecondaryCtor) { SUPERCLASS_ARGS_PLUS_SECONDARY }
    t.hasSuperclass = true
    t.builder.superclass(type)
    // One `CodeBlock` per argument, not one joined block: KotlinPoet joins them itself, and keeping
    // them separate is what lets it wrap a long supertype list. `%L` of the expression's own code
    // keeps `%T`/`%M` placeholders intact, so imports still resolve.
    args.forEach { t.builder.addSuperclassConstructorParameter(CodeBlock.of("%L", it.code)) }
}

/**
 * `: Runnable` — an interface this type implements, or, in an interface body, one it extends.
 *
 * Called once per interface; a second call naming the same one is rejected rather than silently
 * dropped, which is what KotlinPoet's map of superinterfaces would do with it.
 */
context(t: TypeScope)
public fun superinterface(type: TypeName) {
    check(type !in t.builder.superinterfaces) {
        "superinterface: this ${t.kindName} already implements $type."
    }
    t.builder.addSuperinterface(type)
}
