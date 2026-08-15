package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec

// The **modifier** family, as one unit — the third of the three axes that govern what this DSL may
// render, beside [Expect]'s (*what does a container forbid its members?*) and [Kinds]'s (*what does
// a classifier's own kind forbid in its own body?*).
//
// This file answers: **which modifiers may the declaration carrying them have at all?** The DSL
// takes an arbitrary [KModifier] on every construct and hands it to KotlinPoet, which writes it out
// — so `` `class`(SUSPEND, "M") `` rendered `public suspend class M`, `` `object`(ABSTRACT, "O") ``
// rendered `public abstract object O`, and `` `class`(FUN, "M") `` rendered `public fun class M`,
// which is not a declaration at all (*function declaration must have a name*).
//
// The enumeration is the **matrix**: 32 `KModifier` values × 7 declaration forms × 4 positions
// (file level, in a class, in a nested class, detached) = 768 cells, each rendered through the DSL
// and each judged on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10. Recorded in full as **D42**.
//
// **What is here and what is deliberately not.** A modifier can be wrong for three different
// reasons, and only the first is this file's:
//
// | reason | example | where it lives |
// |---|---|---|
// | the **form** cannot carry it, ever | `suspend class M`, `vararg object O`, `data fun f()` | here |
// | the **container** cannot carry it here | `protected` at file level, `const val` in a class, `expect` on a nested class | the container families — [PropertyContainer], [innerAllowed], [abstractMemberAllowed] |
// | the **signature or the supertypes** decide | `override fun f()` in a class, `infix fun f()`, `operator fun f()` | nowhere: not decidable from what this DSL is given |
//
// The third row is why `OVERRIDE`, `INFIX` and `OPERATOR` are **absent from every denial below**
// even though the matrix shows all three producing invalid renders. `override fun f()` is *'f'
// overrides nothing* only because the probe's class had no supertype; `infix fun f(x: Int)` and
// `operator fun plus(o: T)` are valid Kotlin, and a guard keyed on the modifier alone would refuse
// them. A guard that also refuses something valid is worse than the invalid render it fixes.
//
// `EXTERNAL` is absent from the classifier denials for D37's reason, re-measured here: `external
// class C`, `external interface I` and `external object O` are all clean on Kotlin/JS and
// Kotlin/Wasm at top level and refused by the JVM alone. *"Not valid Kotlin" must mean "on the
// target platform".*

/**
 * The declaration forms a modifier can be written on, as this DSL spells them.
 *
 * A form, not a kind: `` `class`(DATA, …) ``, `` `class`(ENUM, …) `` and `` `class`(VALUE, …) `` are
 * all [CLASS] here, because *which* modifiers a class may carry does not change with the classifier
 * kind — what changes is what its **body** may hold, which is [Kinds]'s question.
 */
internal enum class DeclarationForm(
    /** The noun Kotlin's own *modifier 'x' is not applicable to 'y'* prints for this form. */
    val noun: String,
    /** Every modifier Kotlin accepts on this form somewhere. Everything else is refused here. */
    val allowed: Set<KModifier>,
) {
    /**
     *     suspend class M      modifier 'suspend' is not applicable to 'class'.
     *     fun class M          function declaration must have a name.  ← a *syntax* error
     *     vararg class M       modifier 'vararg' is not applicable to 'class'.
     *
     * and the controls, each clean where the matrix says so: every visibility, `expect`/`actual`,
     * the four inheritance modifiers, `inner` (in a class), the five classifier kinds, and
     * `external` — the last on Kotlin/JS and Kotlin/Wasm, which is the whole of D37's rule.
     */
    CLASS(
        "class",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.EXPECT, KModifier.ACTUAL,
            KModifier.FINAL, KModifier.OPEN, KModifier.ABSTRACT, KModifier.SEALED,
            KModifier.EXTERNAL, KModifier.INNER,
            KModifier.ENUM, KModifier.ANNOTATION, KModifier.DATA, KModifier.VALUE, KModifier.INLINE,
        ),
    ),

    /**
     *     final interface I    modifier 'final' is not applicable to 'interface'.
     *     data interface I     modifier 'data' is not applicable to 'interface'.
     *     inner interface I    modifier 'inner' is not applicable to 'interface'.
     *     value interface I    modifier 'value' is not applicable to 'interface'.
     *
     * `sealed`, `abstract` and `open` are all accepted on an interface (the last two redundantly);
     * `final` is the one inheritance modifier that is not, because an interface is never final.
     */
    INTERFACE(
        "interface",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.EXPECT, KModifier.ACTUAL,
            KModifier.OPEN, KModifier.ABSTRACT, KModifier.SEALED,
            KModifier.EXTERNAL, KModifier.FUN,
        ),
    ),

    /**
     *     abstract object O    modifier 'abstract' is not applicable to 'standalone object'.
     *     open object O        modifier 'open' is not applicable to 'standalone object'.
     *     sealed object O      modifier 'sealed' is not applicable to 'standalone object'.
     *     value object O       modifier 'value' is not applicable to 'standalone object'.
     *
     * **`data object O` is valid Kotlin** (1.9 and later) and is clean on all three frontends — the
     * one row on this axis where this DSL was refusing something every target compiles. It is
     * allowed here, and [dataClassNeedsAParameter] no longer fires for an object.
     */
    OBJECT(
        "standalone object",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.EXPECT, KModifier.ACTUAL,
            KModifier.FINAL, KModifier.EXTERNAL, KModifier.COMPANION, KModifier.DATA,
        ),
    ),

    /**
     *     data fun f()         modifier 'data' is not applicable to 'top level function'.
     *     enum fun f()         modifier 'enum' is not applicable to 'member function'.
     *     lateinit fun f()     modifier 'lateinit' is not applicable to 'top level function'.
     *     fun fun f()          function declaration must have a name.
     *
     * The noun Kotlin prints is *'top level function'* at file level and *'member function'* in a
     * type body; the refusal reads the scope for it, and quotes the top-level one from the detached
     * [funSpec], which has no scope to read. `override`, `infix` and `operator` are **not** here —
     * see this file's header.
     */
    FUNCTION(
        "top level function",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.EXPECT, KModifier.ACTUAL,
            KModifier.FINAL, KModifier.OPEN, KModifier.ABSTRACT,
            KModifier.EXTERNAL, KModifier.OVERRIDE,
            KModifier.SUSPEND, KModifier.TAILREC, KModifier.INLINE,
            KModifier.INFIX, KModifier.OPERATOR,
        ),
    ),

    /**
     * The thirteen modifiers KotlinPoet's `PropertySpec.Builder.build` accepts are exactly the
     * thirteen Kotlin accepts, and the other nineteen reached **KotlinPoet's own
     * `IllegalArgumentException`** — *unexpected modifier SUSPEND for PROPERTY*, Global Constraint
     * 26's forbidden type, naming neither the construct nor the property. 152 cells of the matrix,
     * every one of them from this one row.
     *
     *     suspend val p: Int = 1   modifier 'suspend' is not applicable to 'top level property with
     *                              backing field'.
     *
     * `INLINE` is the one that is a **render gap** rather than a language rule: `inline val p: Int
     * get() = 1` is valid Kotlin, and KotlinPoet refuses the modifier on a property outright with a
     * message of its own ("You should mark either the getter, the setter, or both"). This DSL gives
     * an accessor no modifier slot, so there is no spelling for it here; the refusal says so.
     */
    PROPERTY(
        "top level property with backing field",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.EXPECT, KModifier.ACTUAL,
            KModifier.FINAL, KModifier.OPEN, KModifier.ABSTRACT,
            KModifier.CONST, KModifier.EXTERNAL, KModifier.OVERRIDE, KModifier.LATEINIT,
        ),
    ),

    /**
     * A secondary constructor takes a visibility and `actual`, and nothing else — the narrowest
     * form on this axis, and the one with the largest denial set.
     *
     *     open constructor(q: Int) { }      modifier 'open' is not applicable to 'constructor'.
     *     expect constructor(q: Int) { }    modifier 'expect' is not applicable to 'constructor'.
     *     enum constructor(q: Int) { }      syntax error: 'class' keyword is expected after 'enum'.
     *     abstract constructor(q: Int) { }  — KotlinPoet's own `IllegalArgumentException`:
     *                                         *non-abstract type Outer cannot declare abstract
     *                                         function constructor()*
     */
    CONSTRUCTOR(
        "constructor",
        setOf(
            KModifier.PUBLIC, KModifier.PROTECTED, KModifier.PRIVATE, KModifier.INTERNAL,
            KModifier.ACTUAL,
        ),
    ),
    ;

    /** See [allowed]. Computed once per form rather than per check — this runs on every declaration. */
    val denied: Set<KModifier> = KModifier.entries.toSet() - allowed
}

/**
 * [TypeScope.kindName] — the string every classifier construct already carries — as a
 * [DeclarationForm]. A `companion object` answers [DeclarationForm.OBJECT]: `companionObject` takes
 * no modifier slot, so nothing reaches this with one, and the branch exists so that the `when` is
 * exhaustive over the four names rather than falling through on a fifth.
 */
internal fun declarationForm(kindName: String): DeclarationForm = when (kindName) {
    "interface" -> DeclarationForm.INTERFACE
    "named object", "companion object" -> DeclarationForm.OBJECT
    else -> DeclarationForm.CLASS
}

/**
 * Refuses a modifier the declaration form cannot carry. See [DeclarationForm].
 *
 * Three of the denials have a sentence of their own rather than *modifier 'x' is not applicable to
 * 'y'*, because that is what the frontends print, and in this project the quoted sentence is the
 * currency:
 *
 * - `FUN` on a class, an object or a function is a **syntax error** — `fun class M` and `fun fun f()`
 *   are both *function declaration must have a name*, because `fun` starts a function declaration
 *   and the parser never reaches the rest — and on a **constructor** it is not a language rule at
 *   all: see [funOnAConstructor];
 * - `ENUM` on a constructor is *syntax error: 'class' keyword is expected after 'enum'*;
 * - `INLINE` on a property is KotlinPoet's own refusal, and a render gap rather than a language
 *   rule.
 *
 * @param noun the form's noun, overridden where the container changes it (*'member function'* rather
 *   than *'top level function'*).
 */
internal fun modifierNotApplicable(
    construct: String,
    form: DeclarationForm,
    subject: String,
    modifier: KModifier,
    noun: String = form.noun,
): Nothing {
    val word = modifier.name.lowercase()
    if (modifier == KModifier.FUN && form == DeclarationForm.CONSTRUCTOR) funOnAConstructor(construct)
    if (modifier == KModifier.INLINE && form == DeclarationForm.PROPERTY) {
        kindRefusal(
            construct,
            "$subject is INLINE and is a property — `inline val p: Int get() = 1` is valid Kotlin, " +
                "but KotlinPoet puts the modifier on the property rather than on an accessor and " +
                "refuses it outright",
            "KotlinPoet doesn't allow setting the inline modifier on properties",
            "This DSL gives an accessor no modifier slot, so there is no spelling for an inline " +
                "accessor here. Drop INLINE — it changes no signature a generator's caller can see.",
        )
    }
    // Two of the denials draw a sentence of their own rather than *modifier 'x' is not applicable
    // to 'y'*, because the keyword starts a **different declaration** and the parser never reaches
    // the one the caller meant. Each is quoted as measured, kotlinc 2.4.10:
    //
    //     fun class M                     function declaration must have a name.
    //     class C { enum constructor(q: Int) { } }
    //                                     syntax error: 'class' keyword is expected after 'enum'.
    val diagnostic = when {
        modifier == KModifier.ENUM && form == DeclarationForm.CONSTRUCTOR ->
            "syntax error: 'class' keyword is expected after 'enum'"
        modifier == KModifier.FUN -> "function declaration must have a name"
        else -> "modifier '$word' is not applicable to '$noun'"
    }
    kindRefusal(
        construct,
        "$subject carries $modifier, which Kotlin accepts on ${article(noun)} $noun nowhere",
        diagnostic,
        "Drop $modifier${applicableForms(modifier)}.",
    )
}

/**
 * `FUN` on a secondary constructor, the one row of [DeclarationForm]'s table that is **not** a
 * language rule — and the one whose sentence depended on a shape the matrix never built.
 *
 * KotlinPoet omits a constructor's body when it is empty (`canBodyBeOmitted`), so a probe written
 * with empty bodies only ever produces the bodyless half. Measured, one file per row, `kotlinc`,
 * `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three agreeing:
 *
 *     class C { fun constructor(q: Int) { println(q) } }   CLEAN
 *     class C { fun constructor(q: Int) }                  function 'constructor' without a body
 *                                                          must be abstract.
 *     class C(val a: Int) {                                function 'constructor' without a body
 *       fun constructor(q: Int) : this(1) { println(q) }   must be abstract, and six syntax errors
 *     }                                                    after it
 *
 * So this is the DSL **declining a spelling**, not Kotlin refusing a shape, and it says so instead of
 * quoting one sentence at all three rows. It is deliberately not gated on the body being empty: the
 * check runs before the body lambda has been called and before D25's delegation call has been
 * written, and `` `constructor` `` means a secondary constructor — rendering a *function* from it
 * would be the "silently wrong output" Global Constraint 26 is about, whichever way the body falls.
 * The remedy names the construct that renders exactly the third row's valid half.
 */
internal fun funOnAConstructor(construct: String): Nothing = error(
    "$construct: this secondary constructor carries FUN, so what would render is not a constructor " +
        "at all but a function named `constructor` — with an empty body that is \"function " +
        "'constructor' without a body must be abstract\" on the JVM, on Kotlin/JS and on Kotlin/Wasm " +
        "alike, because KotlinPoet omits a constructor's empty body, and with a delegation call it " +
        "is a syntax error on all three. This DSL declines the spelling rather than rendering a " +
        "function from a constructor: write `fun`(\"constructor\", …) if a member function by that " +
        "name is what you want, or drop FUN.",
)

/** ", which is applicable to a class and to an interface" — or "" where the modifier fits nothing else. */
private fun applicableForms(modifier: KModifier): String {
    val forms = DeclarationForm.entries.filter { modifier in it.allowed }
    if (forms.isEmpty()) return ""
    return ", which Kotlin accepts on " + forms.joinToString(" and ") { "${article(it.noun)} ${it.noun}" }
}

/**
 * Runs [DeclarationForm]'s table over a declaration's modifier list.
 *
 * Container-independent by construction, so it runs at the **detached** builders too — `typeSpec`,
 * `funSpec`, `propertySpec` — which is the half of this axis a container-keyed rule cannot reach.
 * Nothing here reads a [Scope]; [noun] is the only thing a caller may vary, and only to print the
 * noun the frontend prints.
 */
internal fun checkModifiers(
    construct: String,
    form: DeclarationForm,
    subject: String,
    modifiers: List<KModifier>,
    noun: String = form.noun,
) {
    modifiers.firstOrNull { it in form.denied }?.let {
        modifierNotApplicable(construct, form, subject, it, noun)
    }
}

/**
 * `const` is a `val`'s modifier: *modifier 'const' is not applicable to 'vars'* on all three
 * frontends, in every container. Its *container* rule — top level, a named object or a companion
 * object — is a separate question and is asked separately, through
 * [PropertyContainer.constAllowed].
 */
internal fun constNeedsAVal(construct: String, name: String): Nothing = kindRefusal(
    construct,
    "'$name' is CONST and is declared with `var`, and a compile-time constant cannot be reassigned",
    "modifier 'const' is not applicable to 'vars'",
    "Declare '$name' with `val`, or drop CONST.",
)

/** See [PropertyContainer.constAllowed]. */
internal fun constNeedsATopLevelOrObjectContainer(construct: String, name: String): Nothing = kindRefusal(
    construct,
    "'$name' is CONST and is declared in a class body, where a compile-time constant has no place " +
        "to live — a `const val` belongs to the file or to the object, never to an instance",
    "const 'val' is only allowed on top level, in named objects, in companion objects or " +
        "companion blocks",
    "Declare '$name' at file level, in a named object or in the class's companionObject — or drop " +
        "CONST, which leaves an ordinary `val`.",
)

// --------------------------------------------------------------------------------------------
// The container half of the modifier axis: which modifiers this *place* accepts, as opposed to
// which the declaration form accepts. Both halves are needed and they are separate questions —
// `protected` is applicable to a class, an interface and an object alike as a *form*, and is legal
// only inside a class as a *place*.

/**
 * Whether a declaration written in this scope may be `protected`.
 *
 * `protected` names a visibility to subclasses, so it needs a container that can have one. Measured,
 * one file per row, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three identical:
 *
 *     protected class M                        modifier 'protected' is not applicable inside 'file'.
 *     protected fun f() { }                    …to 'top level function'.
 *     protected val p: Int = 1                 …to 'top level property with backing field'.
 *     object O    { protected val x: Int = 1 } …inside 'standalone object'.
 *     object O    { protected class M }        …inside 'standalone object'.
 *     interface I { protected val x: Int }     …inside 'interface'.
 *     interface I { protected class M }        …inside 'interface'.
 *
 * and the controls, clean on all three:
 *
 *     class Holder { protected class O ; protected object P ; protected interface Q }
 *     open class C { protected val g: Int = 1 ; protected fun j() { } }
 *     class Outer { companion object { protected val p: Int = 1 ; protected fun f() { }
 *                                      protected class M ; protected object Q
 *                                      protected interface I2 } }
 *     interface I { companion object { protected val p: Int = 1 } }
 *
 * So the answer is "the container is a class **or a companion object**". An `enum`, a `sealed`, an
 * `abstract`, a `data` and a `value class` all answer `"class"` in [TypeScope.kindName] and all take
 * a `protected` member; a **standalone** object does not, and neither does an interface.
 *
 * **The companion row is a false rejection this predicate shipped**, and the reason it went unseen is
 * that D42's four positions are a file, a class, a nested class and the detached builder — a
 * companion object is not one of them, so the row was asserted from this DSL's own message rather
 * than from a compiler. A companion object is the one object whose members a subclass of the
 * enclosing type can see, which is exactly what `protected` is about; that it belongs to an
 * *interface* is no obstacle either (the last control row above).
 */
internal val Scope.protectedAllowed: Boolean
    get() = this is TypeScope && (kindName == "class" || kindName == "companion object")

/**
 * The property side of [protectedNeedsAClass], which reads a [PropertyContainer] rather than a
 * [Scope] — the property family's own boundary, so that the detached `propertySpec` answers
 * [PropertyContainer.UNKNOWN] here as it does everywhere else. The noun the frontends print for a
 * property depends on whether it has a backing field, so this one is deliberately quoted short.
 */
internal fun protectedNotAllowedHere(construct: String, name: String): Nothing =
    kindRefusal(
        construct,
        "'$name' is PROTECTED and its container — a file, an interface, an object or a companion " +
            "object — has no subclass for the visibility to mean anything to",
        "modifier 'protected' is not applicable",
        "Use PRIVATE, INTERNAL or PUBLIC, or move the property into a class.",
    )

/** See [protectedAllowed]. [noun] is the frontends' own, which differs per form. */
internal fun Scope.protectedNeedsAClass(construct: String, subject: String, noun: String?): Nothing =
    kindRefusal(
        construct,
        "$subject is PROTECTED and is declared in ${containerLabel()}, which has no subclass for " +
            "the visibility to mean anything to",
        if (noun == null) {
            "modifier 'protected' is not applicable inside '${containerNoun()}'"
        } else {
            "modifier 'protected' is not applicable to '$noun'"
        },
        "Use PRIVATE, INTERNAL or PUBLIC, or move the declaration into a class.",
    )

/**
 * The bare noun *modifier 'x' is not applicable inside 'y'* names — `protected`'s and `companion`'s
 * alike, since the frontends name the container the same way for both.
 *
 * A **block** never reaches here: a local declaration draws the noun of its own *form* rather than
 * its container's — *modifier 'protected' is not applicable to 'local class'*, measured, all three
 * frontends — so [declareType] and [buildFun] both pass that noun in, and a local `object` is
 * refused before this by [declareType]'s `localAllowed` check. The branch answers with the form
 * anyway, for the day one of those changes: *'block'* is a noun no frontend has ever printed.
 */
private fun Scope.containerNoun(): String = when (this) {
    is FileScope -> "file"
    is BlockScope -> "local class"
    is TypeScope -> if (kindName == "named object") "standalone object" else kindName
}

/**
 * Whether a **companion object** may be declared in this scope — a class body or an interface body,
 * and nothing else. Measured, one file per row, all three frontends identical:
 *
 *     companion object O                                        modifier 'companion' is not
 *     object Outer { companion object O }                       applicable inside 'file' /
 *     class Outer { companion object { companion object O } }   'standalone object' /
 *                                                               'companion object'.
 *
 * and the controls, clean on all three: `class C { companion object O }` and
 * `interface I { companion object O }`.
 *
 * The same question `addCompanionObject` has asked since Task 12, read through one predicate now
 * rather than spelled out at each — because `` `object`(COMPANION, …) `` is a **second spelling** of
 * the same construct and never asked it, which is how `companion object O` at file level rendered
 * and how a companion object inside an object reached KotlinPoet's own `IllegalArgumentException`.
 */
internal val Scope.companionAllowed: Boolean
    get() = this is TypeScope && (kindName == "class" || kindName == "interface")

/** See [companionAllowed]. */
internal fun Scope.companionNeedsAClassOrInterface(construct: String, name: String): Nothing =
    kindRefusal(
        construct,
        "'$name' is COMPANION and is declared in ${containerLabel()}, which has no companion object " +
            "to be — only a class and an interface do",
        "modifier 'companion' is not applicable inside '${containerNoun()}'",
        "Declare '$name' in a class or an interface — or drop COMPANION, which leaves an ordinary " +
            "object.",
    )

// --------------------------------------------------------------------------------------------
// The **interface body**, one of the two container positions D42's matrix omitted (the other is a
// companion object, and it cost this file a false rejection — see [protectedAllowed]).
//
// An interface publishes a contract, and Kotlin says two things about the members of one: they are
// `public` or `private` and nothing else, and they are **open**. Four refusals follow, and each is
// keyed on the container rather than on the form, because all four hold for a nested classifier, a
// function and a property alike.

/**
 * Whether a declaration written in this scope may be `internal`.
 *
 * Measured, one file per row, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three
 * identical, and at every depth:
 *
 *     interface I { internal fun f() { } }   modifier 'internal' is not applicable inside
 *     interface I { internal class M }       'interface'.
 *     interface I { internal val p: Int }
 *     class Outer { class Inner { interface I { internal class M } } }
 *
 * and the controls, clean on all three: the same members declared `private`; the same members in a
 * class, in an object and at file level; and — the row that keeps this keyed on the *immediate*
 * container — `interface I { companion object { internal val x: Int = 1 } }`, because a companion
 * object is a container of its own and takes what its interface does not.
 *
 * The function row is also one of the three cells in these two positions that reached **KotlinPoet's
 * own `IllegalArgumentException`** — *modifiers [INTERNAL] must contain none of [INTERNAL,
 * PROTECTED]*, which names neither the construct nor the member.
 */
internal val Scope.internalAllowed: Boolean
    get() = !(this is TypeScope && kindName == "interface")

/**
 * Whether a declaration written in this scope may be `final`.
 *
 * Its own question and its own field beside [internalAllowed], because the two coincide only by
 * today's containers and not by their reasons: `internal` is refused because an interface member's
 * visibility is part of the contract, `final` because an interface member is open. Measured, all
 * three frontends, at every depth:
 *
 *     interface I { final fun f() { } }                           modifier 'final' is not
 *     interface I { final class M }                               applicable inside 'interface'.
 *     class Outer { interface I { final val p: Int get() = 1 } }
 *
 * and the controls, clean on all three: `object O { final val x: Int = 1 }`, the same in a class and
 * in a companion object — including an interface's own, `interface I { companion object { final val
 * y: Int = 1 } }`.
 *
 * Separate from [MEMBER_INHERITANCE_MODIFIERS], which asks whether there is a **hierarchy** at all
 * (the file-level question) and lets a classifier through: `final class M` at file level is ordinary
 * Kotlin and `interface I { final class M }` is not.
 */
internal val Scope.finalAllowed: Boolean
    get() = !(this is TypeScope && kindName == "interface")

/** See [internalAllowed] and [finalAllowed]. */
internal fun interfaceBodyRefusal(construct: String, subject: String, modifier: KModifier): Nothing =
    kindRefusal(
        construct,
        if (modifier == KModifier.INTERNAL) {
            "$subject is INTERNAL and is declared in an interface, whose members are the contract it " +
                "publishes — an interface member is public or private, and nothing else"
        } else {
            "$subject is FINAL and is declared in an interface, whose members are open by definition"
        },
        "modifier '${modifier.name.lowercase()}' is not applicable inside 'interface'",
        if (modifier == KModifier.INTERNAL) {
            "Use PUBLIC or PRIVATE, or move the declaration out of the interface — its companion " +
                "object takes INTERNAL, and so does a class or an object."
        } else {
            "Drop FINAL — an interface member cannot be final — or move the declaration out of the " +
                "interface."
        },
    )

/**
 * `tailrec` and `inline` on an interface's **function**, which the interface refuses for a reason of
 * its own rather than for a visibility: both are prohibited on an open member, and every interface
 * member is open unless it is `private`. Measured, all three frontends:
 *
 *     interface I { tailrec fun f(n: Int) { … } }          tailrec is prohibited on open members.
 *     interface I { inline fun f() { } }                   'inline' modifier on virtual members is
 *                                                          prohibited. Only private or final
 *                                                          members can be inlined.
 *     interface I { private tailrec fun f(n: Int) { … } }  CLEAN
 *     interface I { private inline fun f() { } }           CLEAN
 *     class C { tailrec fun f(n: Int) { … } }              CLEAN
 *     class C { inline fun f() { } }                       CLEAN
 *
 * Kotlin's own sentence names *private or final*, and `final` is not one of the two here — an
 * interface body refuses it outright ([finalAllowed]) — so the remedy names `private` alone.
 */
internal fun openMemberRefusal(construct: String, name: String, modifier: KModifier): Nothing =
    kindRefusal(
        construct,
        "'$name' is $modifier and is declared in an interface, where every member is open unless it " +
            "is PRIVATE",
        if (modifier == KModifier.TAILREC) {
            "tailrec is prohibited on open members"
        } else {
            "'inline' modifier on virtual members is prohibited. Only private or final members can " +
                "be inlined"
        },
        "Declare '$name' PRIVATE, which is the one way an interface member is not open — FINAL is " +
            "not applicable inside an interface at all — or drop $modifier.",
    )

/**
 * A `private` property in an interface body with no accessor. It is a **shape** rule rather than a
 * modifier one — a property with no accessor and no value in an interface is *abstract*, and an
 * abstract member cannot be private. Measured, all three frontends:
 *
 *     interface I { private val p: Int }                             abstract property in
 *     interface I { private var v: Int }                             interface cannot be private.
 *     interface I { private val p: Int get() = 1 }                   CLEAN
 *     interface I { private var v: Int get() = 1; set(value) { } }   CLEAN
 */
internal fun privateInterfacePropertyNeedsAnAccessor(construct: String, name: String): Nothing =
    kindRefusal(
        construct,
        "'$name' is PRIVATE and is declared in an interface with no accessor, which makes it " +
            "abstract, and an abstract member has nothing to be private to",
        "abstract property in interface cannot be private",
        "Give '$name' a getter, or drop PRIVATE — an interface property with no accessor is the " +
            "ordinary abstract one.",
    )

/**
 * `final`, `open` and `override` are a **member's** modifiers: each of them is about overriding, and
 * a top-level declaration is not in a hierarchy. Measured, all three frontends identical:
 *
 *     final fun f() { }      modifier 'final' is not applicable to 'top level function'.
 *     open fun f() { }       modifier 'open' …
 *     override fun f() { }   modifier 'override' …
 *     final val p: Int = 1   modifier 'final' is not applicable to 'top level property with
 *     open val p: Int = 1    backing field'.
 *     override val p: Int = 1
 *
 * and the controls, clean on all three: the same six inside `open class C { … }`, plus
 * `object O { final val x: Int = 1 }` — which is why this is keyed on the *file* and not on
 * "a class", the reading [protectedAllowed] takes and this one deliberately does not.
 *
 * A **classifier** is untouched: `final class M` and `open class M` are ordinary top-level Kotlin.
 */
internal val MEMBER_INHERITANCE_MODIFIERS: List<KModifier> =
    listOf(KModifier.FINAL, KModifier.OPEN, KModifier.OVERRIDE)

// --------------------------------------------------------------------------------------------
// The **fourth axis**, and the two thirds of its `override`/`infix`/`operator` corner that turn out
// to be decidable after all.
//
// D42 filed all three of them under *"the signature or the supertypes decide, therefore not
// decidable from what this DSL is given"*, and that is true of exactly one:
//
// | modifier | what decides it | cells in D42 | here? |
// |---|---|---|---|
// | `override` | the **supertypes** — *'f' overrides nothing* | 9 | no: this DSL is handed a supertype's `ClassName` and never its members |
// | `infix` | the **parameter list**, which [buildFun] holds | 4 | yes |
// | `operator` | the **name**, which is a language constant | 4 | yes |
//
// The header at the top of this file argues only that a guard keyed on *the modifier alone* would
// refuse `infix fun f(x: Int)` and `operator fun plus(o: T)`, which are valid Kotlin. True, and not
// an argument against a guard keyed on **modifier + arity** or **modifier + name**.
//
// The `operator` check is a **necessary** condition and deliberately nothing more: a name outside
// Kotlin's set can never carry the modifier, in any container, at any arity, with or without a
// receiver — so it cannot over-refuse. The per-operator conventions (`get` needs a parameter,
// `compareTo` must return `Int`, `inc` must return a supertype of its receiver) are the frontend's,
// and each would need its own measurement before it could be written here.

/**
 * Kotlin's operator names. Measured one file per row on `kotlinc`, `kotlinc-js` and `kotlinc-wasm`
 * 2.4.10 as `class C { operator fun <name>(x: Int) { } }`, and split on whether the diagnostic is
 * *'operator' modifier is not applicable to function: **illegal function name*** — every name below
 * draws one of the *other* sentences (*must return 'Int'*, *must have no value parameters*, …), which
 * is the frontend judging a shape rather than a name.
 *
 * `mod` and `modAssign` are **not** here: they were operator names once and on 2.4.10 they are
 * *illegal function name* like any other, on all three frontends.
 */
private val OPERATOR_NAMES: Set<String> = setOf(
    "plus", "minus", "times", "div", "rem",
    "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
    "inc", "dec", "unaryPlus", "unaryMinus", "not",
    "equals", "compareTo", "contains", "invoke", "iterator", "next", "hasNext",
    "get", "set", "rangeTo", "rangeUntil",
    "getValue", "setValue", "provideDelegate",
)

/** `component1`, `component2`, … — measured accepted as a *name* for every digit string, `component0` included. */
private val COMPONENT_NAME = Regex("component\\d+")

/** See [OPERATOR_NAMES]. */
internal fun isOperatorName(name: String): Boolean =
    name in OPERATOR_NAMES || COMPONENT_NAME.matches(name)

/** See [OPERATOR_NAMES]. */
internal fun operatorNeedsAnOperatorName(construct: String, name: String): Nothing = kindRefusal(
    construct,
    "'$name' is OPERATOR, and Kotlin's operator names are a closed set that '$name' is not in — the " +
        "modifier says which operator a function implements, so the name is the whole of it",
    "'operator' modifier is not applicable to function: illegal function name",
    "Name '$name' after the operator it implements — plus, minus, times, div, rem and their " +
        "…Assign forms, inc, dec, unaryPlus, unaryMinus, not, equals, compareTo, contains, invoke, " +
        "iterator, next, hasNext, get, set, rangeTo, rangeUntil, getValue, setValue, " +
        "provideDelegate, componentN — or drop OPERATOR, which leaves an ordinary function.",
)

/**
 * `infix` needs **exactly one value parameter**, and it may not be `vararg`. Measured, all three
 * frontends identical, one file per row:
 *
 *     class C { infix fun f() { } }                 'infix' modifier is inapplicable to this
 *     class C { infix fun f(x: Int, y: Int) { } }   function.
 *     class C { infix fun f(vararg x: Int) { } }
 *
 * and the controls, clean on all three:
 *
 *     class C { infix fun f(x: Int) { } }
 *     class C { infix fun f(x: Int = 1) { } }       ← **a default value is not part of the rule**
 *     class C { infix fun <T> f(x: T) { } }
 *     class C { infix suspend fun f(x: Int) { } }
 *
 * The default-value row is the one worth stating: the language documentation says an infix
 * function's parameter "must not have a default value", and on 2.4.10 that shape compiles clean on
 * the JVM, on Kotlin/JS and on Kotlin/Wasm, with no warning. A guard written from the documentation
 * rather than from the compiler would refuse it.
 */
internal fun infixNeedsOneParameter(construct: String, name: String, params: List<ParameterSpec>): Nothing {
    val vararged = params.singleOrNull()?.modifiers?.contains(KModifier.VARARG) == true
    kindRefusal(
        construct,
        if (vararged) {
            "'$name' is INFIX and its one parameter is VARARG, and an infix call passes exactly one " +
                "argument on the right-hand side"
        } else {
            "'$name' is INFIX and declares " +
                (if (params.isEmpty()) "no value parameter" else "${params.size} value parameters") +
                ", where an infix call has exactly one right-hand side"
        },
        "'infix' modifier is inapplicable to this function",
        "Give '$name' exactly one value parameter that is not VARARG, or drop INFIX.",
    )
}

/**
 * `infix` and `operator` both need a **member or an extension**: a top-level function with no
 * receiver is neither, and neither is a local one. Measured, all three frontends identical:
 *
 *     infix fun f(x: Int) { }                        'infix' modifier is inapplicable to this
 *     fun outer() { infix fun h(x: Int) { } }        function.
 *     operator fun plus(x: Int) { }                  'operator' modifier is not applicable to
 *     fun outer() { operator fun plus(x: Int) { } }  function: must be a member or an extension
 *                                                    function.
 *
 * and the controls, clean on all three: `infix fun Int.f(x: Int) { }`,
 * `operator fun Int.times(o: String): Int = 1`, and the same functions declared in a class, an
 * interface, an object or a companion object.
 *
 * Two sentences for one rule, because the two modifiers draw different ones. The **block** row has
 * no control that compiles: this DSL renders no local function at all (see `localFunIsUnrenderable`),
 * so `fun outer() { infix fun Int.h(x: Int) { } }` — clean on all three frontends — is refused there
 * by the render gap and not by this. Which is why the remedy names moving the function rather than
 * adding a receiver where it would not help.
 */
internal fun infixOrOperatorNeedsAMemberOrExtension(
    construct: String,
    name: String,
    modifier: KModifier,
    container: String,
): Nothing = kindRefusal(
    construct,
    "'$name' is $modifier and is declared $container with no extension receiver, so it is neither a " +
        "member nor an extension, and $modifier is a rule about how a call is written against one",
    if (modifier == KModifier.INFIX) {
        "'infix' modifier is inapplicable to this function"
    } else {
        "'operator' modifier is not applicable to function: must be a member or an extension function"
    },
    "Declare '$name' in a class, an interface or an object, or give it an extension receiver — or " +
        "drop $modifier.",
)

/** See [MEMBER_INHERITANCE_MODIFIERS]. */
internal fun memberModifierNeedsAType(
    construct: String,
    subject: String,
    modifier: KModifier,
    noun: String,
): Nothing = kindRefusal(
    construct,
    "$subject is $modifier and is declared at file level, where there is no hierarchy for it to " +
        "be part of",
    "modifier '${modifier.name.lowercase()}' is not applicable to '$noun'",
    "Drop $modifier — a top-level declaration is final and overrides nothing — or move the " +
        "declaration into a class.",
)
