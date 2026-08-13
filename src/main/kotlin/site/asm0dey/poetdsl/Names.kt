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
    fun unique(base: String): String {
        if (!isTaken(base)) {
            declare(base)
            return base
        }
        var suffix = 2
        while (isTaken("$base$suffix")) suffix++
        val name = "$base$suffix"
        declare(name)
        return name
    }

    fun child(): NameScope = NameScope(this)
}

/** Best-effort English singular, for loop-variable defaults. Falls back to `item`. */
internal fun singularize(name: String): String = when {
    name.isEmpty() -> "item"
    name.endsWith("ies") && name.length > 3 -> name.dropLast(3) + "y"
    name.endsWith("sses") || name.endsWith("xes") || name.endsWith("ches") || name.endsWith("shes") ->
        name.dropLast(2)
    name.endsWith("ss") -> name
    name.endsWith("s") && name.length > 1 -> name.dropLast(1)
    else -> name
}
