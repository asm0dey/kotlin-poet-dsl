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
// can express × sixteen body members, at four container positions (file level, in a class, in an
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
 * Whether a declaration written *in* this scope is `external` — the twin of [Scope.isExpectContainer],
 * and read the same way: **one predicate, one reader**.
 *
 * [TypeScope.isExternal], not `EXTERNAL in builder.modifiers`: Kotlin makes every member of an
 * `external` classifier external, writes no keyword on the nested ones, and KotlinPoet omits their
 * bodies at every depth. A [FileScope] answers `false` — a *file* is not an `external` container, and
 * a top-level declaration is `external` only by carrying the modifier itself.
 */
internal val Scope.isExternalContainer: Boolean
    get() = this is TypeScope && isExternal

/**
 * A function whose return type is declared and whose body is empty renders `{ }` — and
 * *missing return statement* is what all three frontends say about it, in **every** container. The
 * one rule the matrix found that is not keyed on the classifier's kind at all: it fired in 13 of the
 * 13 kinds at all four positions, 47 cells, which is what made it look like a kind rule until the
 * rows were laid side by side.
 *
 * The exempt set is exactly the shapes whose body KotlinPoet **omits**, so that what renders is a
 * signature (`FunSpec.emit`, `canNotHaveBody`/`canBodyBeOmitted`, `FunSpec.kt:137-148`):
 *
 * | shape | why the body is omitted |
 * |---|---|
 * | `ABSTRACT` in the function's own modifiers | `canNotHaveBody` |
 * | `EXPECT` in its own modifiers, or in the container's at any depth | `canNotHaveBody`, and D36 |
 * | `EXTERNAL` in its own modifiers, or in the container's at any depth | `canBodyBeOmitted` |
 * | a constructor | `canBodyBeOmitted` |
 * | no return type, or `Unit` | there is nothing to return |
 * | [funSpec]'s detached builder | it has no container, and two of the rows above are the container's |
 *
 * The last row is [PropertyContainer.UNKNOWN]'s rule on the function side: a detached spec cannot be
 * given a container, an `expect`/`external` body is a legitimate destination for it, and
 * `funSpec(name = "f", returns = INT) { }` spliced into one renders `public fun f(): Int` — a
 * signature, and valid. Refusing it here is a refusal of output a target accepts.
 *
 * Measured, one file per row, all three frontends (the `external` rows on Kotlin/JS and
 * Kotlin/Wasm, which is where `external` compiles at all — D37's platform rule):
 *
 *     fun f(): Unit { }                       clean      fun f() { }                        clean
 *     interface I { abstract fun f(): Int }   clean      abstract class A { abstract fun f(): Int }
 *     expect fun f(): Int                     clean      expect class E { fun f(): Int }    clean
 *     external fun f(): Int                   clean      external class C { fun f(): Int }  clean
 *     external class C { class N { fun f(): Int } }      clean — at every depth
 *     external class C { object O { fun f(): Int } }     clean
 *
 *     fun f(): Int { }                        missing return statement.
 *     fun f(): Nothing { }                    missing return statement.
 *
 * `UNIT` is the exemption that is about the *type* rather than the render: KotlinPoet writes
 * `fun f(): Unit { }` and Kotlin accepts it. ADR 0007 already omits an inferred `Unit`, so this
 * reaches only a caller who passed `returns = UNIT` by hand.
 */
internal fun missingReturnStatement(name: String, returnType: Any): Nothing = kindRefusal(
    "`fun`",
    "'$name' declares a return type of `$returnType` and has an empty body, so KotlinPoet renders " +
        "`fun $name(): $returnType { }` — a block body that returns nothing",
    "missing return statement",
    "Return a value with ret(…), or declare '$name' ABSTRACT in a container that can hold an " +
        "abstract member, or EXPECT or EXTERNAL — those three are the shapes whose body KotlinPoet " +
        "omits, leaving a signature. Dropping returns = … makes it a `Unit` function, which needs " +
        "no return at all.",
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
/*
 * E3 widened this by one term, and it is the round's clearest case for a control row.
 *
 * An **anonymous body** — an enum entry's or an anonymous object's — takes an `abstract` member, on
 * all three frontends, where the identical member in a plain class does not. Measured, one file per
 * row, `kotlinc` / `kotlinc-js` / `kotlinc-wasm` 2.4.10:
 *
 *     class C { abstract fun f(): Int }                 abstract function 'f' in non-abstract
 *     class C { abstract val p: Int }                    class 'C'.  /  abstract property 'p' …
 *
 *     enum class E { A { abstract fun f(): Int } }      clean
 *     enum class E { A { abstract val p: Int } }        clean
 *     val v = object { abstract fun f(): Int }          clean
 *     val v = object { abstract val p: Int }            clean
 *
 * Nothing about the anonymous class is abstract-friendly in any useful sense — an entry *is* an
 * instance — but the frontends accept it, at the frontend and through codegen, and this project's
 * standing rule is that a refusal needs a target that rejects the output. So the term is here rather
 * than an argument for leaving it out.
 */
internal val Scope.abstractMemberAllowed: Boolean
    get() = this is TypeScope && (
        kindName == "interface" || (
            kindName == "class" && builder.modifiers.any {
                it == KModifier.ABSTRACT || it == KModifier.SEALED || it == KModifier.ENUM
            }
            )
        )

/**
 * …and the second question the language measurement forced, which is **not** the one above: is an
 * `abstract` member here refused by the *language* or merely unrenderable by KotlinPoet 2.3.0?
 *
 * In an anonymous body it is the second. `TypeSpec.Builder.build` raises
 * `IllegalArgumentException: non-abstract type null cannot declare abstract function f`
 * (`TypeSpec.kt:864`) for any builder that does not carry `ABSTRACT`, and an anonymous builder can
 * never carry it — `addModifiers` is `check(!isAnonymousClass)` and throws for an empty list too, so
 * there is no route to the shape at all. Global Constraint 26 forbids letting that
 * `IllegalArgumentException` surface, and [abstractNeedsAnAbstractContainer]'s sentence would be a
 * *false claim about the language*: the frontends accept the member (see [abstractMemberAllowed]'s
 * rows), so quoting *abstract function 'f' in non-abstract class* would send the reader looking for
 * a diagnostic no compiler prints.
 *
 * So this is D20's shape — valid Kotlin the backend cannot render — and it gets D20's treatment: a
 * refusal that says so, and a canary test that fails when KotlinPoet fixes it.
 */
internal val Scope.abstractMemberIsUnrenderable: Boolean
    get() = isAnonymousBody

/** See [abstractMemberIsUnrenderable]. */
internal fun abstractMemberIsUnrenderable(construct: String, name: String, kindName: String): Nothing = error(
    "$construct: '$name' is ABSTRACT in ${article(kindName)} $kindName, which is valid Kotlin — " +
        "`enum class E { A { abstract fun f(): Int } }` and `val v = object { abstract val p: Int }` " +
        "are clean on the JVM, on Kotlin/JS and on Kotlin/Wasm alike — and KotlinPoet 2.3.0 cannot " +
        "render it: `TypeSpec.Builder.build` requires the enclosing builder to carry ABSTRACT " +
        "(\"non-abstract type null cannot declare abstract function\"), and an anonymous builder " +
        "cannot, since `addModifiers` is `check(!isAnonymousClass)`. Drop ABSTRACT and give '$name' " +
        "a body or a value.",
)

/**
 * See [abstractMemberAllowed].
 *
 * A file is not a non-abstract class and the frontends do not call it one, so the quoted sentence is
 * read off the container rather than hard-coded. Measured, kotlinc 2.4.10, one file per row:
 *
 *     abstract fun f(): Int                   modifier 'abstract' is not applicable to 'top level
 *                                             function'.
 *     fun g() { abstract fun h(): Int }       …to 'local function'.
 *     object O { abstract fun f(): Int }      abstract function 'f' in non-abstract class 'O'.
 *
 * In this project the quoted sentence is the currency; a message that quotes one no frontend prints
 * sends the reader looking for it.
 */
internal fun Scope.abstractNeedsAnAbstractContainer(construct: String, name: String): Nothing {
    val notApplicableTo = when (this) {
        is FileScope -> "top level function"
        is BlockScope -> "local function"
        is TypeScope -> null
    }
    kindRefusal(
        construct,
        "'$name' is ABSTRACT and is declared in ${containerLabel()}, which " +
            if (notApplicableTo == null) {
                "is not abstract, so nothing can ever override it"
            } else {
                "holds no member for anything to override"
            },
        notApplicableTo?.let { "modifier 'abstract' is not applicable to '$it'" }
            ?: "abstract function '$name' in non-abstract class",
        // A file cannot be declared ABSTRACT, so the remedy that names the container is only
        // printed where there is a container to declare. Naming a shape the caller cannot write is
        // the defect the previous round recorded twice.
        if (notApplicableTo == null) {
            "Declare the container ABSTRACT or SEALED, make it an interface, or give '$name' a body."
        } else {
            "Give '$name' a body, or move it into an interface or an ABSTRACT, SEALED or ENUM class."
        },
    )
}

/**
 * A `data class`'s primary constructor is the whole declaration: it must exist, and every parameter
 * in it must declare a property. Measured, one file per row, all three frontends identical, at every
 * depth and through the signature form and the in-body [constructorParam] alike:
 *
 *     data class D(a: Int)                    primary constructor of data class must only have
 *     data class D(val a: Int, b: Int)         property ('val' / 'var') parameters.
 *     data class D                            data class must have at least one primary constructor
 *     data class D { constructor(q: Int) }     parameter.
 *
 * and the controls, clean on all three:
 *
 *     data class D(val a: Int)                data class D(val a: Int, var b: Int)
 *     data class D(val a: Int) { fun f(): Int = 1 ; class N ; companion object }
 *     class C(a: Int)                         — a plain parameter is what an ordinary class takes
 *
 * The count is answered in [TypeScope.finish] rather than eagerly, for the reason every check there
 * is: a `constructorParam` written at the end of the body still supplies the parameter, so an eager
 * check would answer on writing order alone.
 */
internal fun dataClassNeedsPropertyParameters(name: String): Nothing = kindRefusal(
    "constructorParam",
    "'$name' declares no property on the primary constructor of a `data class`, and a data class " +
        "derives `equals`, `hashCode`, `toString` and `copy` from its properties, so a parameter " +
        "that declares none has nothing to contribute",
    "primary constructor of data class must only have property ('val' / 'var') parameters",
    "Declare it with constructorParam(VAL, \"$name\", …) or param(VAL, \"$name\", …) — or VAR — or " +
        "drop the `data` modifier.",
)

/** See [dataClassNeedsPropertyParameters]. */
internal fun dataClassNeedsAParameter(kindName: String): Nothing = kindRefusal(
    "`$kindName`",
    "this `data class` has no primary-constructor parameter, and a data class is defined by the " +
        "properties it derives `equals`, `hashCode`, `toString` and `copy` from",
    "data class must have at least one primary constructor parameter",
    "Give it a parameter — `class`(DATA, …, param(VAL, …)) or constructorParam(VAL, …) — or drop " +
        "the `data` modifier.",
)

/**
 * Which classifier kind forbids a property here from having a **backing field**. See
 * [PropertyContainer.backingFieldDenial].
 */
internal enum class BackingFieldDenial {
    /** An interface holds no state; its properties are abstract. */
    INTERFACE,

    /** A value class *is* one value; a second one would have nowhere to live. */
    VALUE_CLASS,
}

/**
 * A `value class` wraps exactly one value, so a property of its own would need storage the class
 * does not have. Measured, one file per row, all three frontends identical (the JVM adds
 * *value classes without '@JvmInline' annotation are not yet supported* to every row, which is the
 * caller's annotation to add and not this DSL's business — D37's platform rule):
 *
 *     value class V(val a: Int) { val p: Int = 1 }        value class cannot have properties with
 *     value class V(val a: Int) { var p: Int = 1 }          backing fields.
 *     value class V(val a: Int) { lateinit var p: String }
 *     value class V(val a: Int) { val p: Int by lazy { 1 } }  value class cannot have delegated
 *                                                              properties.
 * and the controls, clean on all three:
 *
 *     value class V(val a: Int) { val p: Int get() = 1 }
 *     value class V(val a: Int) { var p: Int get() = 1; set(v) { } }
 *     value class V(val a: Int) { val Int.q: Int get() = 1 }
 *     value class V(val a: Int) { fun f(): Int = 1 ; class N }
 *     value class V(val a: Int) { companion object { val q: Int = 1 } }   — a container of its own,
 *                                                                          exactly as an interface's
 *
 * The primary-constructor property parameter is untouched: it is the value, and it reaches
 * `addConstructorParam` rather than [checkProperty].
 */
internal fun valueClassHoldsNoStorage(
    construct: String,
    name: String,
    carries: String,
    delegated: Boolean,
): Nothing = kindRefusal(
    construct,
    "'$name' carries $carries and is declared in a `value class`, which wraps exactly one value " +
        "and has nowhere to store a second",
    if (delegated) {
        "value class cannot have delegated properties"
    } else {
        "value class cannot have properties with backing fields"
    },
    "Move the value into a getter — `val`(\"$name\", …) { … } — or declare it in the value class's " +
        "companion object, both of which a value class still holds.",
)

/**
 * A `fun interface` has exactly one abstract member and it is the function. An abstract *property*
 * is refused whether the modifier is written or not, because a property with no accessor in an
 * interface body is abstract either way. Measured, all three frontends identical:
 *
 *     fun interface F { fun g(): Int; val p: Int }            functional interface cannot have
 *     fun interface F { fun g(): Int; abstract val p: Int }    abstract properties.
 *
 * and the controls, clean on all three:
 *
 *     fun interface F { fun g(): Int; val p: Int get() = 1 }
 *     fun interface F { fun g(): Int; var p: Int get() = 1; set(v) { } }
 *     fun interface F { fun g(): Int; companion object { val q: Int = 1 } }
 *     fun interface F { fun g(): Int; class N }
 *     interface H { val p: Int }        — an ordinary interface's abstract property is the whole
 *                                         point of an interface, and is untouched
 */
internal fun funInterfaceHoldsNoAbstractProperty(construct: String, name: String): Nothing = kindRefusal(
    construct,
    "'$name' has no accessor and is declared in a `fun interface`, whose one abstract member is " +
        "its function",
    "functional interface cannot have abstract properties",
    "Give '$name' a getter, declare it in the interface's companion object, or drop the FUN " +
        "modifier to make this an ordinary interface.",
)

/**
 * A secondary constructor of an `enum class` must be `private`, and of a `sealed class` `private` or
 * `protected` — and this is **half a render gap**, which is why it is a refusal with a working
 * remedy rather than a silent pass.
 *
 * KotlinPoet emits an explicit visibility keyword on every secondary constructor
 * (`CodeWriter.shouldEmitPublicModifier`, the same mechanism that makes a local class unrenderable —
 * D20), so `` `constructor`(param("q", INT)) { } `` in an `enum class` renders
 * `public constructor(q: Int)` where hand-written Kotlin would have written `constructor(q: Int)`
 * and got the default visibility, which is exactly the one Kotlin requires. Measured, one file per
 * row, all three frontends identical:
 *
 *     enum class E { ; public constructor(q: Int) }       constructor must be private in enum class.
 *     enum class E { ; protected constructor(q: Int) }
 *     enum class E { ; internal constructor(q: Int) }
 *     sealed class S { public constructor(q: Int) }       constructor must be private or protected
 *     sealed class S { internal constructor(q: Int) }      in sealed class.
 *
 * and the controls, clean on all three — which is what makes the remedy real:
 *
 *     enum class E { ; private constructor(q: Int) }      sealed class S { private constructor(q: Int) }
 *     sealed class S { protected constructor(q: Int) }
 *     class C { constructor(q: Int) }                     open class O { constructor(q: Int) }
 *     abstract class A { constructor(q: Int) }            — every other kind is untouched
 *
 * The **primary** constructor is untouched and needs no rule: KotlinPoet writes no visibility
 * keyword into a class header, so `enum class E(val x: Int)` renders and compiles clean.
 */
internal fun constructorVisibility(kindWord: String, allowed: String, diagnostic: String): Nothing =
    kindRefusal(
        "`constructor`",
        "this secondary constructor renders `public` — KotlinPoet writes an explicit visibility " +
            "keyword on every one of them — and a `$kindWord class` constructor must be $allowed",
        diagnostic,
        "Declare it `constructor`(${allowed.uppercase().replace(" OR ", ", ")}, …) — Kotlin's own " +
            "default for a $kindWord class constructor is what KotlinPoet cannot render.",
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
    get() = !(this is TypeScope && (KModifier.INNER in builder.modifiers || isAnonymousBody))

/**
 * See [nestedTypesAllowed] and [isAnonymousBody]. Two containers refuse a nested classifier and they
 * refuse it for different reasons and with different sentences, so the message branches once here
 * rather than at each of the two call sites.
 */
internal fun Scope.holdsNoNestedType(kindName: String, name: String): Nothing {
    if (this is TypeScope && isAnonymousBody) {
        anonymousBodyHoldsNo("`$kindName`", "'$name'", this.kindName)
    }
    innerHoldsNoNestedType(kindName, name)
}

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
