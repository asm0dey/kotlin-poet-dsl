package site.asm0dey.poetdsl

/**
 * The nesting level a builder runs in. Sealed on purpose: every dispatching `when` is
 * exhaustive, so adding a scope makes the compiler list the constructs that forgot a case.
 *
 * A construct valid at more than one level is declared **once** on `Scope` and dispatches
 * on the runtime type — the innermost scope value wins. Two overloads distinguished only
 * by context-parameter type would be an ambiguity error instead. See ADR 0001.
 *
 * A sealed class rather than an interface: subclasses share [names] and [id], and Kotlin
 * does not allow `internal` members on an interface.
 */
public sealed class Scope protected constructor(
    internal val names: NameScope,
    internal val id: ScopeId,
)
