package site.asm0dey.poetdsl

/** Identity of a DSL scope, used to detect handles used outside their declaring scope. */
public class ScopeId internal constructor(
    internal val parent: ScopeId?,
    internal val label: String,
)

/**
 * Per-scope name bookkeeping.
 *
 * Stub for Task 2: exists only so [Scope] has a constructor parameter to hold. Task 3
 * fills this in with uniquification and singularization; do not build that logic here.
 */
public class NameScope internal constructor(
    internal val parent: NameScope?,
)
