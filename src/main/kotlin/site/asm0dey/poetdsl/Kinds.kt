package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

// The **classifier-kind** family, as one unit — the axis orthogonal to [Expect]'s.
//
// Every round of E2 asked *what does a container forbid its members?*, with the container's
// `expect`-ness, its `interface`-ness and its `object`-ness as the three answers. This file answers
// the other question: **what does a classifier's own kind forbid in its own body?** `data`,
// `value`, `annotation`, `enum`, `sealed`, `inner` and `fun` are not decorations on a class — each
// one changes what the body may contain, and the DSL takes them as an ordinary [KModifier] on
// `` `class` ``/`` `interface` `` with nothing asking that question anywhere.
//
// The enumeration this file is derived from is the **matrix**: thirteen classifier kinds this DSL
// can express × fifteen body members, at four container positions (file level, in a class, in an
// object, in an interface) — 832 cells, each rendered through the DSL and each judged on `kotlinc`,
// `kotlinc-js` and `kotlinc-wasm` 2.4.10. It is recorded in full as **D41**. What it found:
//
// - **123 cells rendered Kotlin no frontend accepts**, reducing to the nine rules below;
// - **42 cells raised KotlinPoet's own `IllegalArgumentException`** — Global Constraint 26's
//   forbidden type — from two of those rules;
// - **zero cells were false rejections**: every refusal the DSL already made is one all three
//   frontends agree with. The axis was unguarded, not over-guarded.
//
// All three frontends answered identically on every cell of the matrix, so each message below says
// "on the JVM, on Kotlin/JS and on Kotlin/Wasm alike" as [expectRefusal] does.

/**
 * The one refusal this family raises, shaped like [expectRefusal] and for the same reason: a rule
 * seen at seven call sites reads as one rule only if it is spelled once.
 *
 * [what] names the member and the kind that forbids it; [diagnostic] is the frontends' own sentence,
 * quoted verbatim; [remedy] says what to write instead. `error`, so this is an
 * [IllegalStateException] naming the construct — never `require`, and never KotlinPoet's own
 * [IllegalArgumentException] (Global Constraint 26).
 */
internal fun kindRefusal(construct: String, what: String, diagnostic: String, remedy: String): Nothing = error(
    "$construct: $what, so this is \"$diagnostic\" on the JVM, on Kotlin/JS and on Kotlin/Wasm " +
        "alike. $remedy",
)

/**
 * What a message calls the container a declaration is being written into — `"a file"`, `"an
 * annotation class"`, `"a companion object"`.
 *
 * Built from [TypeScope.kindName] plus whichever classifier modifier the builder carries, because
 * `kindName` alone is `"class"` for all nine of the class-shaped kinds and the kind is exactly what
 * these refusals are about.
 */
internal fun Scope.containerLabel(): String = when (this) {
    is FileScope -> "a file"
    is BlockScope -> "a block"
    is TypeScope -> {
        val classifier = CLASSIFIER_MODIFIERS.firstOrNull { it in builder.modifiers }
        if (classifier == null || kindName != "class") {
            "${article(kindName)} $kindName"
        } else {
            val word = classifier.name.lowercase()
            "${article(word)} $word class"
        }
    }
}

/**
 * The modifiers that make a `` `class` `` a *different kind of classifier* rather than a class with
 * a property. Ordered so that [containerLabel] names the one Kotlin's own diagnostics name.
 *
 * `OPEN` and `ABSTRACT` are deliberately absent: an `open class` and an `abstract class` forbid
 * nothing in their bodies that a plain class forbids, so naming them would only make a message
 * longer. `SEALED` is present because its secondary constructors are constrained, and `INNER`
 * because both what it may contain and where it may be declared are.
 */
private val CLASSIFIER_MODIFIERS: List<KModifier> = listOf(
    KModifier.ANNOTATION, KModifier.VALUE, KModifier.ENUM, KModifier.DATA, KModifier.SEALED,
    KModifier.INNER,
)

/**
 * Whether a classifier declared in this scope may carry `inner`.
 *
 * `inner` is not a property of the declaration; it is a claim about the declaration's **container**,
 * and it is the only classifier modifier that is. Measured, one file per row, `kotlinc`,
 * `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three identical:
 *
 *     class O           { inner class N }   clean          data class O(val a: Int) { inner class N }
 *     sealed class O    { inner class N }   clean          abstract class O { inner class N }
 *     open class O      { inner class N }   clean          enum class O { ; inner class N }
 *     class O { class M { inner class N } } clean          class O { inner class M { inner class N } }
 *
 *     inner class N                          modifier 'inner' is not applicable inside 'file'.
 *     object O          { inner class N }    …inside 'standalone object'.
 *     interface O       { inner class N }    …inside 'interface'.
 *     class O { companion object { inner class N } }   …inside 'companion object'.
 *     annotation class O { inner class N }   …inside 'annotation class'.
 *     value class O(val a: Int) { inner class N }      value class cannot have inner classes.
 *
 * So the answer is "the container is a class, and not one of the two kinds whose bodies hold no
 * instance state to be inner *to*". [TypeScope.kindName] separates the three builders this DSL uses
 * plus the companion object's; the two exclusions are read off the immediate builder's own
 * modifiers, which is what decides what renders.
 *
 * Nothing here is inherited: `class O { class M { inner class N } }` is clean, so an `inner` class
 * two levels inside an object is fine as long as its *immediate* container is a class.
 */
internal val Scope.innerAllowed: Boolean
    get() = this is TypeScope && kindName == "class" &&
        KModifier.ANNOTATION !in builder.modifiers && KModifier.VALUE !in builder.modifiers

/**
 * The bare noun Kotlin's own *modifier 'inner' is not applicable inside 'x'* names, so that the
 * quoted diagnostic is the sentence the frontends print and not a paraphrase of it. A `value class`
 * gets a different sentence altogether and is answered by [innerNeedsAnEnclosingClass], not here.
 */
private fun Scope.innerContainerNoun(): String = when (this) {
    is FileScope -> "file"
    is BlockScope -> "block"
    is TypeScope -> when (kindName) {
        "named object" -> "standalone object"
        "class" -> if (KModifier.ANNOTATION in builder.modifiers) "annotation class" else kindName
        else -> kindName
    }
}

/** See [innerAllowed]. */
internal fun Scope.innerNeedsAnEnclosingClass(kindName: String, name: String): Nothing {
    // The one container with a sentence of its own: a value class has no identity to be inner to at
    // all, so Kotlin does not phrase it as a modifier-placement rule.
    if (this is TypeScope && KModifier.VALUE in builder.modifiers) {
        kindRefusal(
            "`$kindName`",
            "'$name' is INNER and is declared in a `value class`, which wraps one value and has no " +
                "enclosing instance for it to be inner to",
            "value class cannot have inner classes",
            "Drop INNER — a nested classifier needs no modifier — or declare '$name' inside an " +
                "ordinary class.",
        )
    }
    kindRefusal(
        "`$kindName`",
        "'$name' is INNER and is declared in ${containerLabel()}, which has no enclosing instance " +
            "for it to be inner to",
        "modifier 'inner' is not applicable inside '${innerContainerNoun()}'",
        "Drop INNER — a nested classifier needs no modifier — or declare '$name' inside a class.",
    )
}

/**
 * Whether a **member** may be declared in this scope at all.
 *
 * An `annotation class` is a declaration of a shape, not of a thing with behaviour: it holds its
 * primary-constructor parameters and nothing else. Measured, one file per row, all three frontends
 * identical, and at every depth:
 *
 *     annotation class N { val p: Int = 1 }        members are prohibited in annotation classes.
 *     annotation class N { var p: Int = 1 }
 *     annotation class N { val p: Int get() = 1 }  — a getter is no escape; there is no member of
 *     annotation class N { fun f(): Int = 1 }        any kind
 *     annotation class N { fun f(): Int }
 *     annotation class N { constructor(q: Int) }
 *     annotation class N { init { } }
 *
 * and the controls, clean on all three:
 *
 *     annotation class N(val x: Int)               annotation class N { class Inner }
 *     annotation class N                           annotation class N { companion object }
 *     annotation class N { class Inner { fun f(): Int = 1 } }   — the nested class is an ordinary
 *                                                                 class and holds ordinary members
 *
 * So the rule is about *members*, not about the body: a nested classifier and a companion object are
 * both fine, which is why this is a separate question from [nestedTypesAllowed] and not a stronger
 * form of it. Read off the immediate builder's own `ANNOTATION`, never inherited — the nested class
 * in the last control row is what that buys.
 *
 * Four of these seven rows reached **KotlinPoet's own `IllegalArgumentException`** (*annotation class
 * N cannot declare member function f*, `TypeSpec.kt:872-876`), Global Constraint 26's forbidden type,
 * and the other three rendered.
 */
internal val Scope.membersAllowed: Boolean
    get() = !(this is TypeScope && KModifier.ANNOTATION in builder.modifiers)

/** See [membersAllowed]. */
internal fun annotationHoldsNoMembers(construct: String, member: String): Nothing = kindRefusal(
    construct,
    "$member is declared in an `annotation class`, which declares a shape and holds nothing but " +
        "its primary-constructor parameters",
    "members are prohibited in annotation classes",
    "Move it to a nested class or to the annotation's companion object, both of which an " +
        "annotation class still holds, or declare it as a primary-constructor parameter with " +
        "param(VAL, …).",
)

/**
 * Whether this classifier may declare a supertype.
 *
 * An `annotation class` may not: it already extends `kotlin.Annotation` and Kotlin lets nothing be
 * added to that. Measured, all three frontends identical, at every depth:
 *
 *     annotation class N : Iface     annotation class cannot have supertypes.
 *     annotation class N : Base      — refused by KotlinPoet first, as `IllegalStateException: only
 *                                      classes can have super classes, not CLASS`, which names
 *                                      neither the construct nor the kind
 *     annotation class N             clean — the control
 *
 * The `superinterface` row **rendered**. The `superclass` row did not, and what changes for it is
 * the exception's message rather than the refusal.
 */
internal val TypeScope.supertypesAllowed: Boolean
    get() = KModifier.ANNOTATION !in builder.modifiers

/** See [supertypesAllowed]. */
internal fun annotationHasNoSupertypes(construct: String): Nothing = kindRefusal(
    construct,
    "this `annotation class` is given a supertype, and an annotation class already extends " +
        "`kotlin.Annotation` and takes nothing else",
    "annotation class cannot have supertypes",
    "Drop the supertype. An annotation is data about a declaration, not a participant in a " +
        "hierarchy; if the shared shape is what is wanted, put it in a nested class.",
)

/**
 * Whether an **abstract member** may be declared in this scope — a function here, and a property
 * through [PropertyContainer.abstractAllowed], which reads this.
 *
 * Exactly Kotlin's list, which is narrower than KotlinPoet's on one row: KotlinPoet's `build()`
 * accepts an abstract member in an `abstract object` (`isAbstract = ABSTRACT in modifiers || SEALED
 * in modifiers || kind == INTERFACE || isEnum`, `TypeSpec.kt:861`), and Kotlin has no such thing.
 * Measured, one file per row, all three frontends identical:
 *
 *     interface I       { abstract fun f(): Int }   clean      enum class E { ; abstract fun f(): Int }
 *     abstract class A  { abstract fun f(): Int }   clean      sealed class S { abstract fun f(): Int }
 *     expect abstract class X { abstract fun f(): Int }        clean
 *
 *     class C           { abstract fun f(): Int }   abstract function 'f' in non-abstract class 'C'.
 *     data class D(val a: Int) { abstract fun f(): Int }
 *     open class O      { abstract fun f(): Int }
 *     object O          { abstract fun f(): Int }
 *     class C { companion object { abstract fun f(): Int } }
 *     expect class E    { abstract fun f(): Int }   — `expect` is not a licence for an abstract
 *                                                     member; `expect abstract class` is
 *     abstract fun f(): Int                         — at file level, *modifier 'abstract' is not
 *                                                     applicable to 'top level function'*
 *
 * Every refused row raised **KotlinPoet's own `IllegalArgumentException`** — *non-abstract type C
 * cannot declare abstract function f*, Global Constraint 26's forbidden type, naming neither this
 * DSL's construct nor the modifier the caller passed. 30 cells of the matrix.
 *
 * One fact, one reader: [PropertyContainer] used to spell this list out privately, which is how the
 * property side came to have it and the function side came to have nothing at all.
 */
internal val Scope.abstractMemberAllowed: Boolean
    get() = this is TypeScope && (
        kindName == "interface" || (
            kindName == "class" && builder.modifiers.any {
                it == KModifier.ABSTRACT || it == KModifier.SEALED || it == KModifier.ENUM
            }
            )
        )

/** See [abstractMemberAllowed]. */
internal fun Scope.abstractNeedsAnAbstractContainer(construct: String, name: String): Nothing =
    kindRefusal(
        construct,
        "'$name' is ABSTRACT and is declared in ${containerLabel()}, which is not abstract, so " +
            "nothing can ever override it",
        "abstract function '$name' in non-abstract class",
        "Declare the container ABSTRACT or SEALED, make it an interface, or give '$name' a body.",
    )

/**
 * Whether a classifier may be declared *inside* this one.
 *
 * An `inner class` is the one container that holds no nested classifier at all, and the exception is
 * exactly one: another `inner` class. Measured, all three frontends identical:
 *
 *     class O { inner class M { inner class N } }    clean      — the exception
 *     class O { inner class M { fun f(): Int = 1 } } clean      — members are unaffected
 *     class O { inner class M { constructor(q: Int) } }  clean
 *     class O { inner class M { class N } }          'Class' is prohibited here.
 *     class O { inner class M { object N } }         'Object' is prohibited here.
 *     class O { inner class M { interface I } }      'Interface' is prohibited here.
 *     class O { inner class M { companion object } } 'Companion object' is prohibited here.
 *
 * Read off the **immediate** builder's own `INNER`, never inherited: the nested `inner class N` two
 * rows up is itself an `inner` container, and a plain `class` declared inside *it* is refused for
 * this same reason rather than for its grandparent's.
 */
internal val Scope.nestedTypesAllowed: Boolean
    get() = !(this is TypeScope && KModifier.INNER in builder.modifiers)

/** See [nestedTypesAllowed]. */
internal fun innerHoldsNoNestedType(kindName: String, name: String): Nothing = kindRefusal(
    "`$kindName`",
    "'$name' is declared inside an `inner class`, which holds no nested classifier",
    // Kotlin's own noun for the declaration it is refusing, which is not this DSL's `kindName` for
    // one of the four: a named object is *'Object'* there.
    "'${if (kindName == "named object") "Object" else kindName.replaceFirstChar(Char::uppercase)}' " +
        "is prohibited here",
    "Declare '$name' INNER as well — an `inner class` nested in an `inner class` is the one shape " +
        "Kotlin allows — or move it out to the enclosing class.",
)
