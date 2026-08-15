package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

// E3, deviation D43: `typealias Handler = (Event) -> Unit`.
//
// D31 filed it as absent, and E1 recorded one open question that nobody had measured in three
// batches: **is a nested `typealias` valid for the target Kotlin version?** It is. Measured, one file
// per cell, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three identical:
//
//     typealias S = String                                clean
//     class C     { typealias S = String }                clean      — and `C.S` resolves
//     object O    { typealias S = String }                clean
//     interface I { typealias S = String }                clean
//     data class C(val a: Int) { typealias S = String }   clean
//     class C { companion object { typealias S = String } }   clean
//     class O { class N { typealias S = String } }        clean
//     class O { inner class N { typealias S = String } }  clean
//     enum class E { A; typealias S = String }            clean
//     typealias L<T> = List<T>                            clean      — and nested
//
//     annotation class A { typealias S = String }         members are prohibited in annotation
//                                                          classes.
//     fun f() { typealias S = String }                    the feature "local type aliases" is
//     enum class E { A { typealias S = String } }          experimental and should be enabled
//     val v = object { typealias S = String }              explicitly … '-Xlocal-type-aliases'
//
// So the nested form is offered, the local form is not, and the two anonymous bodies count as local.

/**
 * The modifiers a `TypeAliasSpec` may carry, **read off KotlinPoet's own `ALLOWABLE_MODIFIERS`**
 * rather than written out here.
 *
 * E2f's rule, and this is the round that had the chance to break it again: a guard whose correctness
 * depends on a set is only as sound as the set, and a hand-enumerated one makes a valid argument
 * unsound without changing a word of it. `TypeAliasSpec.Builder.Companion.ALLOWABLE_MODIFIERS` is
 * `private`, so it is not readable from here — but it is readable by reflection, and
 * `TypeAliasesTest` does exactly that and fails if this list and KotlinPoet's set stop being equal.
 *
 * The set is `{PUBLIC, INTERNAL, PRIVATE, ACTUAL}`, and it is the **language's** set minus one:
 * `protected typealias S = String` is clean in a class body on all three frontends and KotlinPoet
 * refuses it, which is [typeAliasProtectedIsUnrenderable]. Everything outside both sets — all 27
 * remaining `KModifier` values, `EXPECT` included — is *modifier 'x' is not applicable to
 * 'typealias'* on all three frontends, measured across 96 cells.
 */
internal val TYPE_ALIAS_MODIFIERS: Set<KModifier> =
    setOf(KModifier.PUBLIC, KModifier.INTERNAL, KModifier.PRIVATE, KModifier.ACTUAL)

/** See [TYPE_ALIAS_MODIFIERS]. */
internal fun typeAliasProtectedIsUnrenderable(name: String): Nothing = error(
    "`typealias`: '$name' is PROTECTED, which is valid Kotlin in a class body — " +
        "`class C { protected typealias S = String }` is clean on the JVM, on Kotlin/JS and on " +
        "Kotlin/Wasm alike — and KotlinPoet 2.3.0 cannot render it: `TypeAliasSpec.Builder` allows " +
        "only PUBLIC, INTERNAL, PRIVATE and ACTUAL. Use PRIVATE or INTERNAL, or declare '$name' " +
        "with no visibility modifier.",
)

/** See [TYPE_ALIAS_MODIFIERS]. */
internal fun typeAliasModifierNotApplicable(name: String, modifier: KModifier): Nothing = kindRefusal(
    "`typealias`",
    "'$name' is ${modifier.name} and a type alias is a name for a type, with nothing for the " +
        "modifier to describe",
    "modifier '${modifier.name.lowercase()}' is not applicable to 'typealias'",
    "A type alias takes a visibility — PUBLIC, INTERNAL or PRIVATE — and ACTUAL, and nothing else.",
)

/** See the file comment: a local type alias is behind `-Xlocal-type-aliases`, and this DSL passes no flag. */
internal fun localTypeAliasIsExperimental(container: String, name: String): Nothing = error(
    "`typealias`: '$name' is declared in $container, which makes it a **local** type alias — " +
        "\"the feature \\\"local type aliases\\\" is experimental and should be enabled explicitly … " +
        "'-Xlocal-type-aliases'\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. This DSL emits " +
        "source for compilers it does not configure and passes no flag, so it does not render one. " +
        "Declare '$name' at file level or in a named type — a nested `typealias` is ordinary Kotlin " +
        "and is what this construct is for.",
)

/**
 * What the generated `` `typealias` `` runs.
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a fourth scope breaks
 * the build here rather than falling through (D17).
 *
 * **No shadow, and that is the same call `` `class` `` makes.** A local type alias is valid Kotlin
 * behind a flag rather than invalid Kotlin, so an `@Deprecated(ERROR)` overload would freeze a
 * temporary state into a surface Task 22 locks permanently — D20's argument, applied to the one
 * construct in E3 it fits. The refusal is a run-time [IllegalStateException] naming the flag, and
 * `TypeAliasesTest` carries the canary.
 */
internal fun Scope.declareTypeAlias(
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName,
    typeVariables: List<TypeVariableName>,
    kdoc: String?,
) {
    val declared = modifiers.toList()
    // The modifier family, before anything that reads the container, exactly as [declareType] orders
    // it: *may this declaration form carry this modifier at all?* is container-independent.
    declared.firstOrNull { it !in TYPE_ALIAS_MODIFIERS }?.let {
        if (it == KModifier.PROTECTED) typeAliasProtectedIsUnrenderable(name)
        typeAliasModifierNotApplicable(name, it)
    }
    // …and the **pair** half of the same axis, which this construct escaped when E3 shipped it.
    //
    // Every other declaration form reaches [checkVisibilityPair] through [checkModifiers]. A type
    // alias cannot: [checkModifiers] runs [DeclarationForm]'s table, and a `typealias` has no row
    // there — its allowed set is KotlinPoet's `ALLOWABLE_MODIFIERS`, read by reflection above, which
    // is a *narrower* set than the language's and so cannot be folded into that table without
    // turning the `PROTECTED` render gap into a language claim. So the pair rule is called directly,
    // which is the whole fix: one fact, and every form that needs it asks for it.
    //
    // Measured, one file per row, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10:
    //
    //     public private typealias S = String              modifier 'public' is incompatible with
    //     class C { public private typealias S = String }   'private'.
    //     public internal private typealias S = String     …with 'internal' — the first pair is
    //                                                       the one reported
    //
    //     public typealias S = String     private typealias S = String     clean
    //     internal typealias S = String   class C { private typealias S = String }   clean
    checkVisibilityPair("`typealias`", "'$name'", declared)
    // Before the name is registered, so a refused alias does not burn one — the ordering Task 12
    // gave a rejected binding. The `when` at the bottom answers for a [BlockScope] too, which makes
    // it D17's exhaustiveness branch rather than a second reader: by the time it runs, the name is
    // already in [Scope.declaredTypeNames] and the `TypeAliasSpec` already built.
    if (this is BlockScope) localTypeAliasIsExperimental("a block body", name)
    if (isAnonymousBody) {
        // An anonymous body is a *local* class's body, so a type alias in one is a local type alias
        // and draws the same diagnostic — not the "prohibited here" a nested classifier draws.
        localTypeAliasIsExperimental("${article((this as TypeScope).kindName)} ${this.kindName}", name)
    }
    // An `annotation class` holds nothing but its primary-constructor parameters, and a type alias is
    // no exception: *members are prohibited in annotation classes*. The same predicate `` `fun` ``,
    // `` `init` `` and the supertypes read.
    if (!membersAllowed) annotationHoldsNoMembers("`typealias`", "'$name'")
    // The interface body's own rule, through the same predicate `declareType` and `buildFun` read:
    // `interface I { internal typealias S = String }` is *modifier 'internal' is not applicable
    // inside 'interface'* and the `private` twin is clean.
    if (KModifier.INTERNAL in declared && !internalAllowed) {
        interfaceBodyRefusal("`typealias`", "'$name'", KModifier.INTERNAL)
    }
    // A type alias *is* a type name, so it shares [Scope.declaredTypeNames] with the classifiers:
    // `typealias S = String` beside `class S` is *redeclaration* on all three frontends, as is a
    // second alias of the same name.
    check(name !in declaredTypeNames) {
        "A type named \"$name\" is already declared in this scope."
    }
    declaredTypeNames += name
    // E1's deferred slot, landed with the construct it was waiting for. Variance and `reified` are
    // both refused: a type alias declares no variance of its own and is not an inline function's
    // business, which is the same pair `` `fun` `` answers and the opposite of a class's.
    checkTypeVariables(
        "`typealias`",
        name,
        typeVariables,
        varianceAllowed = false,
        reifiedAllowed = false,
        isEnum = false,
    )
    // …and the one rule of the type-parameter family that no other declaration has, so it is here
    // rather than in [checkTypeVariables]. Measured, all three frontends identical:
    //
    //     typealias L<T : Number> = List<T>   bounds on type alias parameters are prohibited.
    //     typealias L<out T> = List<T>        variance annotations are only allowed for type
    //     typealias L<in T> = Comparable<T>    parameters of classes and interfaces.
    //     typealias L<reified T> = List<T>    applying reified modifier to a type parameter of a
    //                                          type alias makes no sense.
    //     typealias L<T> = List<T>            clean — the control
    //
    // A bare `<T>` is `T : Any?` in KotlinPoet's model, so "no bounds" is that one-element list and
    // not an empty one; comparing against `typeVariable(name)`'s own output rather than spelling the
    // sentinel keeps this true if KotlinPoet changes what a bare type variable holds.
    typeVariables.forEach { tv ->
        check(tv.bounds == TypeVariableName(tv.name).bounds) {
            "`typealias`: type parameter \"${tv.name}\" of '$name' declares an upper bound, and " +
                "Kotlin allows none on a type alias — \"bounds on type alias parameters are " +
                "prohibited\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. Drop the bound; " +
                "the type it aliases carries its own."
        }
    }
    val spec = TypeAliasSpec.builder(name, type)
        .addModifiers(declared)
        .addTypeVariables(typeVariables)
        .apply {
            kdoc?.let { addKdoc(docBlock(it)) }
            annotations?.list?.forEach { addAnnotation(it) }
        }
        .build()
    when (this) {
        is FileScope -> builder.addTypeAlias(spec)
        is TypeScope -> builder.addTypeAlias(spec)
        // Unreachable — the guard above answers first — and kept because the `when` has no `else`
        // and a fourth [Scope] must break the build here rather than fall through (D17).
        is BlockScope -> localTypeAliasIsExperimental("a block body", name)
    }
}

/**
 * The render gap a control row found, and the reason it is checked in [TypeScope.finish] rather than
 * at the `` `typealias` `` call.
 *
 * An `enum class` body needs a `;` between its entries and everything else, and Kotlin wants one even
 * when there are no entries at all. Measured, all three frontends identical:
 *
 *     enum class E { A,        typealias S = String }   syntax error: Expecting ';' after the last
 *     enum class E {           typealias S = String }    enum entry or '}' to close enum class body.
 *     enum class E {           val p: Int = 1 }
 *
 *     enum class E { A, ;      typealias S = String }   clean
 *     enum class E { A, ;  val p: Int = 1 ; typealias S = String }   clean
 *
 * KotlinPoet writes that `;` when the enum has a **property, a function, a nested type or an
 * initializer block** — and a `typeAliasSpec` is none of the four, so an enum whose only member is a
 * type alias renders without it. Derived by building all eight combinations against the real builder
 * and reading whether the semicolon appears, not from the documentation.
 *
 * So the refusal is conditional on what else the enum ends up holding, which is exactly why it cannot
 * be eager: `` `class`(ENUM, "E") { `typealias`("S", STRING); `val`("p", INT, init = 1.lit) } `` is
 * valid and the `typealias` call cannot know that yet. Same deferral D25's three checks take.
 */
internal fun TypeScope.checkEnumTypeAliasRenders() {
    val semicolon = builder.propertySpecs.isNotEmpty() || builder.funSpecs.isNotEmpty() ||
        builder.typeSpecs.isNotEmpty() || hasInitializerBlock
    check(semicolon) {
        "`typealias`: this `enum class`'s only member is a type alias, and KotlinPoet 2.3.0 renders " +
            "that without the `;` an enum body needs — \"Expecting ';' after the last enum entry or " +
            "'}' to close enum class body\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. It " +
            "writes the `;` for a property, a function, a nested type or an `init` block and not for " +
            "a type alias. Give the enum one of those four as well, or move the alias to file level " +
            "or to an enclosing type."
    }
}
