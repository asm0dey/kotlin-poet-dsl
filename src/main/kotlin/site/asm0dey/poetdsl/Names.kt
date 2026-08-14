package site.asm0dey.poetdsl

/**
 * Identity of a DSL scope. Handles carry the [ScopeId] that declared them, so using one
 * where its scope does not apply is rejected when the code is emitted (ADR 0008).
 */
public class ScopeId internal constructor(
    internal val parent: ScopeId?,
    internal val label: String,
) {
    internal fun child(label: String): ScopeId = ScopeId(this, label)

    /** True when [other] is this scope or nested inside it. */
    internal fun isAncestorOf(other: ScopeId?): Boolean {
        var current = other
        while (current != null) {
            if (current === this) return true
            current = current.parent
        }
        return false
    }

    override fun toString(): String = label
}

/**
 * Tracks the names bound in a scope so generated names never collide. Nests with the
 * scopes, so a local that would shadow a member is renamed at declaration and nothing is
 * ever qualified with `this.` (ADR 0009).
 */
internal class NameScope(private val parent: NameScope?) {
    private val taken = mutableSetOf<String>()

    fun isTaken(name: String): Boolean = name in taken || parent?.isTaken(name) == true

    fun declare(name: String) {
        taken += name
    }

    /** Returns [base] if free, otherwise `base2`, `base3`, … Registers the result. */
    fun unique(base: String): String = uniqueAvoiding(base, null)

    /**
     * [unique], additionally stepping over the names taken in [also] — a scope this one does *not*
     * chain to, so `isTaken` would never see them.
     *
     * The one caller is D30's split of [TypeScope]'s names: a plain primary-constructor parameter
     * lives in a child scope, visible in initializers and `init { }` blocks but not in a member
     * body, and a property declared in the parent still has to step over it — `class A(x: Int) {
     * val x = … }` compiles, but every mention of `x` in an initializer would then resolve to the
     * parameter rather than to the property, and ADR 0009's invariant is that nothing is ever
     * qualified with `this.`. Registered in **this** scope, not in [also], so the name stays
     * visible to member bodies.
     *
     * A separately named function rather than a defaulted parameter or an overload: `::unique` is
     * used as a callable reference (`Lambdas.kt`), and both alternatives change what that resolves
     * to.
     */
    fun uniqueAvoiding(base: String, also: NameScope?): String {
        fun taken(name: String): Boolean = isTaken(name) || also?.isTaken(name) == true
        if (!taken(base)) {
            declare(base)
            return base
        }
        var suffix = 2
        while (taken("$base$suffix")) suffix++
        val name = "$base$suffix"
        declare(name)
        return name
    }

    fun child(): NameScope = NameScope(this)
}

/**
 * The name a **member** declared in this scope is rendered under: unique among everything a member
 * body can see, and — in a type — also stepping over the plain primary-constructor parameters that
 * only initializers can see (D30, [TypeScope.initializerNames]).
 *
 * Registered at the member level, so what is declared here stays visible to member bodies and to
 * initializers alike. A file or block scope has no second level, so this is plain [NameScope.unique]
 * there.
 */
internal fun Scope.uniqueMemberName(base: String): String =
    names.uniqueAvoiding(base, (this as? TypeScope)?.initializerNames)

/**
 * Best-effort English singular, for loop-variable defaults. Falls back to `item`.
 *
 * Suffix rules only — there is no irregular-plural table and no dictionary, so `series` becomes
 * `sery` and `children` is left alone. That is a deliberate limit, not an oversight: any table
 * would be an arbitrary subset of English (why `children` and not `phenomena`, `analyses`,
 * `indices`?) and would read as complete when it is not. The rules that *are* here are the ones
 * that hold for the overwhelming majority of collection names a generator produces.
 *
 * Nothing about a generated declaration depends on the result: this only picks the *default*
 * rendered name of a `` `for` `` loop variable, and ADR 0005 gives every construct an explicit
 * `name =` / `param =` argument that overrides it. Uniquification then guarantees the fallback
 * compiles even when it reads badly — `for (children2 in children)`.
 *
 * `internal`, so unlike the rest of this task's surface it is *not* frozen by the API lock and can
 * gain a table later without a binary-compatibility break. `NamesTest` pins the current answers,
 * including the wrong-looking ones, so any such change has to be deliberate.
 */
internal fun singularize(name: String): String = when {
    name.isEmpty() -> "item"
    name.endsWith("ies") && name.length > 3 -> name.dropLast(3) + "y"
    name.endsWith("sses") || name.endsWith("xes") || name.endsWith("ches") || name.endsWith("shes") ->
        name.dropLast(2)
    name.endsWith("ss") -> name
    name.endsWith("s") && name.length > 1 -> name.dropLast(1)
    else -> name
}
