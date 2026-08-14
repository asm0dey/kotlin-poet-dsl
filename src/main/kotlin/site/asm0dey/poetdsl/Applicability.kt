package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

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

    /** See [allowed]. */
    val denied: Set<KModifier> get() = KModifier.entries.toSet() - allowed
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
 * - `FUN` on anything but an interface is a **syntax error** — `fun class M` and `fun fun f()` are
 *   both *function declaration must have a name*, because `fun` starts a function declaration and
 *   the parser never reaches the rest;
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
    // Three of the denials draw a sentence of their own rather than *modifier 'x' is not applicable
    // to 'y'*, because the keyword starts a **different declaration** and the parser never reaches
    // the one the caller meant. Each is quoted as measured, kotlinc 2.4.10:
    //
    //     fun class M                     function declaration must have a name.
    //     class C { enum constructor(q: Int) { } }
    //                                     syntax error: 'class' keyword is expected after 'enum'.
    //     class C { fun constructor(q: Int) { } }
    //                                     function 'constructor' without a body must be abstract.
    val diagnostic = when {
        modifier == KModifier.ENUM && form == DeclarationForm.CONSTRUCTOR ->
            "syntax error: 'class' keyword is expected after 'enum'"
        modifier == KModifier.FUN && form == DeclarationForm.CONSTRUCTOR ->
            "function 'constructor' without a body must be abstract"
        modifier == KModifier.FUN -> "function declaration must have a name"
        else -> "modifier '$word' is not applicable to '$noun'"
    }
    kindRefusal(
        construct,
        if (modifier == KModifier.FUN && form == DeclarationForm.CONSTRUCTOR) {
            "$subject carries FUN, so what renders is not a constructor at all but a function " +
                "named `constructor`"
        } else {
            "$subject carries $modifier, which Kotlin accepts on ${article(noun)} $noun nowhere"
        },
        diagnostic,
        "Drop $modifier${applicableForms(modifier)}.",
    )
}

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
 * object — is a separate question and is asked separately.
 */
internal fun constNeedsAVal(construct: String, name: String): Nothing = kindRefusal(
    construct,
    "'$name' is CONST and is declared with `var`, and a compile-time constant cannot be reassigned",
    "modifier 'const' is not applicable to 'vars'",
    "Declare '$name' with `val`, or drop CONST.",
)
