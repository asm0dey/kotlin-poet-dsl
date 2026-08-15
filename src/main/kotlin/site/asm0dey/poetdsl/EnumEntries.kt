package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec

// E3, deviation D43: the entries of an `enum class`, and the **anonymous body** family they belong to.
//
// D31's audit called this the one *silent* failure in the whole coverage sweep: `` `class`(ENUM,
// "Color") { } `` already produced a valid enum builder — KotlinPoet derives `isEnum` from the
// modifier, `TypeSpec.kt:539` — and nothing could put an entry in it, so the render was a correct
// but empty `enum class`. **The render is valid Kotlin**: `enum class E` and `enum class E { }` are
// both clean on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10 (measured, one file per cell). So
// the audit's "silent failure" is a *capability* gap and not a Global Constraint 26 violation, and
// the fix is this construct rather than a guard on the empty enum — which would have refused output
// every frontend accepts, the failure mode E2 took twelve rounds to stop shipping.
//
// The acceptance spec is the language-side measurement recorded in D43: 49 cells on what an entry
// may carry, 192 cells of `KModifier` × declaration form × the two anonymous body positions, and 27
// neighbouring rows. **All three frontends agreed on every one of the 268.**

/** [TypeScope.kindName] for an enum entry's anonymous body. */
internal const val ENUM_ENTRY: String = "enum entry"

/** [TypeScope.kindName] for an anonymous object's body. */
internal const val ANONYMOUS_OBJECT: String = "anonymous object"

/**
 * Whether this scope is an **anonymous body** — an enum entry's or an anonymous object's.
 *
 * One predicate because the measurement says one rule: across 192 cells (32 `KModifier` values × a
 * `val`, a `var` and a `fun` × the two positions) the two bodies answer **identically on every cell
 * but three**, and those three are `protected`, which has its own reader in [protectedAllowed]. Both
 * are the body of an anonymous class, which is why:
 *
 * - it holds **no nested classifier except an `inner` class** — `A { class N }` is *'Class' is
 *   prohibited here*, `A { object O }` is *named object 'O' cannot be local*, `A { interface I }` is
 *   *'Interface' is prohibited here*, on all three frontends and in both positions; and
 *   `A { inner class N }` is **clean**, on all three frontends and in both positions, because
 *   `inner` makes the declaration a member of the anonymous class rather than a local class. E3
 *   refused that row and the fix round measured it — see [Scope.innerAllowed] for the 128-cell
 *   sweep, which is the one this list was written without;
 * - it holds **no constructor** — *objects cannot have constructors*;
 * - it holds **no companion object** — *modifier 'companion' is not applicable inside 'enum entry'*
 *   and *…inside 'local class'* respectively;
 * - an **`abstract` member is allowed**, which is the surprise and the reason a control row is not
 *   optional: `class C { abstract fun f(): Int }` is *abstract function 'f' in non-abstract class
 *   'C'* and the identical member in either anonymous body is clean on all three frontends.
 *
 * Read off [TypeScope.kindName], never inherited: a nested classifier cannot be declared in one of
 * these bodies at all, so there is nothing below to inherit it.
 */
internal val Scope.isAnonymousBody: Boolean
    get() = this is TypeScope && (kindName == ENUM_ENTRY || kindName == ANONYMOUS_OBJECT)

/**
 * See [isAnonymousBody].
 *
 * **The quoted sentence is keyed on the declared form, not on the container** — which is the
 * opposite of what this function did when E3 shipped it, and it was wrong on four of its six cells.
 * Measured, one file per cell, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, the two positions
 * agreeing on every row:
 *
 *     object { class N }      'Class' is prohibited here.       A { class N }      — same sentence
 *     object { interface I }  'Interface' is prohibited here.   A { interface I }  — same sentence
 *     object { object O }     named object 'O' cannot be        A { object O }     — same sentence
 *                             local. Try to use an anonymous
 *                             object instead.
 *
 * So `'Class' is prohibited here` is right for a class in *either* body and wrong for an interface
 * or an object in either; the container is what the *prose* names and the form is what the
 * *diagnostic* does. [declaredKind] is this DSL's `kindName` for the declaration being refused —
 * `"class"`, `"named object"` or `"interface"` — and [containerKind] is [TypeScope.kindName] for the
 * body it is being written into.
 */
internal fun anonymousBodyHoldsNo(
    construct: String,
    what: String,
    declaredKind: String,
    containerKind: String,
    classifier: KModifier?,
): Nothing =
    kindRefusal(
        construct,
        "$what is declared in ${article(containerKind)} $containerKind, which is the body of an " +
            "anonymous class and holds no nested classifier, no constructor and no companion object",
        anonymousBodyDiagnostic(declaredKind, what, classifier),
        "Declare it in the enclosing type instead, or — for a class — declare it INNER, which is " +
            "the one nested classifier an anonymous body does hold. See `innerAllowed`.",
    )

/**
 * The anonymous body's own questions, asked of a **spliced** spec.
 *
 * `+FunSpec`, `+PropertySpec` and `+TypeSpec` put a pre-built spec straight into the innermost
 * builder and ask the container nothing. That boundary is pre-existing and is **not** closed here in
 * general — a `+typeSpec` into an `inner class` still renders `public class N`, as it did at base —
 * but E3 added two containers and measured three rules for them, and the splice reached past all
 * three. Two of the three were live: `ABSTRACT` raised KotlinPoet's own `IllegalArgumentException`
 * (*non-abstract type null cannot declare abstract property p*, Global Constraint 26's forbidden
 * type) and `PROTECTED` rendered `protected val p` / `protected fun g()` in an enum entry body,
 * which is *modifier 'protected' is not applicable inside 'enum entry'* on all three frontends.
 *
 * **One fact, one reader**: every question below is answered by the predicate the non-splice path
 * already uses, so the two routes cannot drift. [protectedAllowed] rather than [isAnonymousBody] is
 * what asks the `PROTECTED` question, which is the whole reason those are two predicates — the same
 * member is clean in an anonymous object's body and refused in an enum entry's.
 */
internal fun Scope.checkAnonymousBodySplice(construct: String, member: String, modifiers: Set<KModifier>) {
    if (this !is TypeScope || !isAnonymousBody) return
    if (KModifier.ABSTRACT in modifiers) abstractMemberIsUnrenderable(construct, member, kindName)
    if (KModifier.PROTECTED in modifiers && !protectedAllowed) {
        protectedNeedsAClass(construct, "'$member'", noun = null)
    }
}

/**
 * The [TypeSpec] half of [checkAnonymousBodySplice]. `INNER` is the exemption — see
 * [Scope.innerAllowed] for the sweep that says so — and it is a **class**'s exemption, which is the
 * term this was missing: it returned on `INNER` before asking what had been declared, so
 * `+TypeSpec.interfaceBuilder("I").addModifiers(INNER)` and the `object` equivalent walked straight
 * through and rendered `inner interface I` / `inner object O`. Measured, one file per row, all three
 * frontends 2.4.10, in both anonymous bodies:
 *
 *     val v = object { public inner interface I }   modifier 'inner' is not applicable to
 *                                                   'interface'.
 *     val v = object { public inner object O }      …to 'standalone object'.
 *     val v = object { public inner class N }       clean          ← the exemption, unchanged
 *
 * so the sentence is the **modifier-applicability** one and not the container's — asked through
 * [checkModifiers], the same table the non-splice path runs, with `INNER` alone in the list because
 * closing the splice boundary in general is a round of its own (D43). A spliced `inner` class then
 * answers [checkInnerKindPair], for the same reason a declared one does.
 *
 * `TypeSpec.kind` is public API and is what names the declared form, which is what the frontends key
 * their sentence on: a spliced `interface` draws *'Interface' is prohibited here* and a spliced
 * named `object` draws *named object 'O' cannot be local*, neither of which is the class sentence.
 */
internal fun Scope.checkAnonymousBodyTypeSplice(spec: TypeSpec) {
    if (this !is TypeScope || !isAnonymousBody) return
    val name = spec.name ?: "<anonymous>"
    val declaredKind = when (spec.kind) {
        TypeSpec.Kind.OBJECT -> "named object"
        TypeSpec.Kind.INTERFACE -> "interface"
        TypeSpec.Kind.CLASS -> "class"
    }
    if (KModifier.INNER in spec.modifiers) {
        checkModifiers("TypeSpec", declarationForm(declaredKind), "'$name'", listOf(KModifier.INNER))
        checkInnerKindPair("TypeSpec", name, spec.modifiers)
        return
    }
    if (KModifier.COMPANION in spec.modifiers) companionNeedsAClassOrInterface("TypeSpec", name)
    holdsNoNestedType(declaredKind, name, spec.modifiers)
}

/**
 * See [anonymousBodyHoldsNo]: the frontends' own sentence for each declared form.
 *
 * **The form is not this DSL's `kindName`, and that is what this used to key on.** `kindName` is
 * `"class"` for all six class-shaped kinds, so an `annotation class` and an `enum class` were told
 * *'Class' is prohibited here*, which no frontend prints for either. The verdict was right in every
 * cell; the citation was invented — the failure this project's method exists to catch, and its third
 * instance in these two containers.
 *
 * Measured, one file per row, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, both anonymous
 * bodies identical on every row, and each sentence read off the **render** (KotlinPoet writes the
 * `public`, which draws a *modifier 'public' is not applicable to 'local class'* of its own on every
 * row but the annotation one; the sentence quoted here is the one that names the declared form,
 * which is the same choice the plain-class row already made):
 *
 *     object { public annotation class N }         annotation class cannot be local.
 *     object { public enum class N }               modifier 'enum' is not applicable to 'local class'.
 *     object { public sealed class N }             modifier 'sealed' is not applicable to 'local class'.
 *     object { public value class N(val a: Int) }  value class cannot be local or inner.
 *     object { public data class N(val a: Int) }   'Class' is prohibited here.
 *     object { public class N }                    'Class' is prohibited here.
 *
 * So `data` is the one classifier kind that really is a plain class to this sentence, and `sealed` —
 * which the brief for this fix did not name — is one that is not. `interface` and `object` are
 * unaffected by any modifier: `fun interface F` is still *'Interface' is prohibited here* and
 * `data object O` still *named object 'O' cannot be local*, both measured.
 */
private fun anonymousBodyDiagnostic(declaredKind: String, what: String, classifier: KModifier?): String = when {
    declaredKind == "named object" ->
        "named object $what cannot be local. Try to use an anonymous object instead"
    declaredKind == "interface" -> "'Interface' is prohibited here"
    classifier == KModifier.ANNOTATION -> "annotation class cannot be local"
    classifier == KModifier.ENUM -> "modifier 'enum' is not applicable to 'local class'"
    classifier == KModifier.SEALED -> "modifier 'sealed' is not applicable to 'local class'"
    classifier == KModifier.VALUE || classifier == KModifier.INLINE ->
        "value class cannot be local or inner"
    else -> "'Class' is prohibited here"
}

/**
 * What the generated `enumEntry` runs.
 *
 * Three guards fire here and one is deferred to [TypeScope.finish], for the reason every deferred
 * check there has: a `constructorParam` written *after* the entry still supplies the enum's primary
 * constructor, so an eager argument check would answer on writing order alone (D25's shape).
 *
 * The entry's own [TypeScope] chains its [ScopeId] to the enum's, rather than re-parenting at the
 * file the way [declareType] does for a nested type. That is measured, not chosen:
 * `enum class E(val x: Int) { A(1) { fun f(): Int = x } }` is clean on all three frontends, where
 * `class O(val id: Long) { class N { fun f(): Long = id } }` is *outer class … of non-inner class
 * cannot be used as receiver*. An entry's body is an anonymous subclass, so it captures.
 */
internal fun TypeScope.addEnumEntry(
    name: String,
    args: Array<out Expr>,
    kdoc: String?,
    body: (TypeScope.() -> Unit)?,
) {
    // Without this, `` `class`("C") { enumEntry("A") } `` renders `public class C { A, }`, which no
    // frontend accepts — KotlinPoet's `addEnumConstant` asks nothing about the builder's kind, and
    // `isEnum` is derived from the ENUM modifier alone.
    check(KModifier.ENUM in builder.modifiers) {
        "enumEntry: '$name' is declared in ${containerLabel()}, and only an enum class has entries. " +
            "Declare the container `class`(ENUM, …) — KotlinPoet derives `isEnum` from that modifier " +
            "— or make '$name' an ordinary member."
    }
    // **Ordered before the two namespace checks below, and falsification is what said so.** An
    // entry registers its name in `declaredPropertyNames` as well, so with the checks the other way
    // round a second `enumEntry("A")` was caught by the *property* check and this one was
    // unreachable — a guard whose counterpart makes its case unreachable, which one-at-a-time
    // falsification exists to find. The messages are different and the entry's is the right one
    // here.
    //
    // An entry name shares a namespace with the enum's **properties** and its **nested types**, and
    // *not* with its functions: `enum class E { A; fun A(): Int = 1 }` is clean on all three
    // frontends, while the `val A` and the `class A` forms are both *conflicting declarations*. Two
    // registries rather than one, because that asymmetry is the whole content of the rule.
    // KotlinPoet keeps entries in a `Map<String, TypeSpec>`, so a second entry of the same name
    // silently overwrites the first and the enum renders one short. Kotlin's own answer is
    // *conflicting declarations*.
    check(name !in builder.enumConstants) {
        "enumEntry: this enum class already declares an entry named \"$name\", and KotlinPoet keeps " +
            "entries in a map, so the second would silently replace the first."
    }
    check(name !in declaredPropertyNames) {
        "enumEntry: a property named \"$name\" is already declared in this enum class, and an entry " +
            "and a property share a namespace (\"conflicting declarations\"). A *function* of the " +
            "same name does not collide."
    }
    check(name !in declaredTypeNames) {
        "enumEntry: a type named \"$name\" is already declared in this enum class, and an entry and " +
            "a nested type share a namespace (\"conflicting declarations\")."
    }
    declaredPropertyNames += name
    declaredTypeNames += name
    enumEntryArgCounts += name to args.size

    val entry = TypeSpec.anonymousClassBuilder()
    kdoc?.let { entry.addKdoc(docBlock(it)) }
    // One `CodeBlock` per argument, not one joined block — the same shape and the same reason as
    // `applySuperclass`: KotlinPoet joins them itself, and `%L` of the expression's own code keeps
    // `%T`/`%M` placeholders intact so imports still resolve.
    args.forEach { entry.addSuperclassConstructorParameter(CodeBlock.of("%L", it.code)) }
    // The body runs into the entry's own [TypeScope] when there is one; the entry is registered the
    // same way either way, which is why this is one `addEnumConstant` and not one per branch. It was
    // two identical ones, and an `if` whose arms agree is a claim that they differ.
    //
    // `TypeScope.finish` is deliberately *not* called: every check in it is about a header this body
    // has none of — a primary constructor, a superclass and its arguments, a secondary constructor's
    // delegation — and all four constructs that could create one are refused above by
    // [isAnonymousBody] and [supertypesAllowed]. Calling it would re-ask questions whose answers are
    // already fixed.
    body?.let {
        TypeScope(
            entry,
            names.child(),
            id.child("enum entry $name"),
            ENUM_ENTRY,
            fileId,
            isExpect,
            isExternal,
        ).it()
    }
    builder.addEnumConstant(name, entry.build())
}

/**
 * The deferred half: every entry's argument count against the enum's primary constructor, answered in
 * [TypeScope.finish] once the whole body has run.
 *
 * The rule is ordinary Kotlin constructor-call resolution and nothing enum-specific, which is what
 * the 21-cell first axis of D43 establishes. This DSL's arguments are **positional** — `args` is a
 * `vararg Expr`, and there is no spelling for a named one — so the condition is exactly decidable:
 *
 *     enum class E              { A(1) }              too many arguments for 'constructor(): E'.
 *     enum class E(val x: Int)  { A }                 no value passed for parameter 'x'.
 *     enum class E(val x: Int)  { A(1, 2) }           too many arguments for 'constructor(x: Int): E'.
 *     enum class E(val x: Int = 1, val y: Int) { A(1) }   no value passed for parameter 'y'.
 *
 * and the controls, clean on all three frontends: `enum class E { A }`, `E { A() }`, `E(val x: Int)
 * { A(1) }`, `E(val x: Int = 1) { A }`, `E(val x: Int, val y: Int = 2) { A(1) }`.
 *
 * **A `vararg` parameter switches the rule off entirely** rather than approximating it:
 * `enum class E(vararg val x: Int) { A }` and `{ A(1, 2, 3) }` are both clean, so neither half of the
 * count condition holds there and an approximation could only over-refuse.
 */
internal fun TypeScope.checkEnumEntryArgs() {
    val params = if (hasCtor) ctor.parameters else emptyList()
    if (params.any { KModifier.VARARG in it.modifiers }) return
    val required = params.indexOfLast { it.defaultValue == null } + 1
    enumEntryArgCounts.forEach { (name, given) ->
        check(given <= params.size) {
            "enumEntry: \"$name\" is given $given argument${if (given == 1) "" else "s"} and this " +
                "enum class's primary constructor takes ${params.size}, so this is \"too many " +
                "arguments for 'constructor(${params.joinToString { it.name }}): …'\" on the JVM, " +
                "on Kotlin/JS and on Kotlin/Wasm alike. Drop the extra arguments, or declare the " +
                "parameters with constructorParam or in the `class` signature."
        }
        check(given >= required) {
            "enumEntry: \"$name\" is given $given argument${if (given == 1) "" else "s"} and this " +
                "enum class's primary constructor needs ${required}, so this is \"no value passed " +
                "for parameter '${params[given].name}'\" on the JVM, on Kotlin/JS and on " +
                "Kotlin/Wasm alike. Pass the missing argument${if (required - given == 1) "" else "s"}, " +
                "or give the parameter a `default`."
        }
    }
}
