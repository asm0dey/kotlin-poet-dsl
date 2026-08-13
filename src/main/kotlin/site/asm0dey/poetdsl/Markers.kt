package site.asm0dey.poetdsl

/**
 * Marks the file-level DSL scope.
 *
 * These markers guard the member-based APIs (`WhenScope.branch`, `IfChain.elseIf`,
 * `TryChain.catch`). They do **not** guard context-parameter functions — measured on
 * Kotlin 2.4.10, `@DslMarker` has no effect on context-argument resolution. See ADR 0001.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class FileDsl

/** Marks the type-level DSL scope. See [FileDsl] for what the markers do and do not cover. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class TypeDsl

/** Marks the statement-level DSL scope. See [FileDsl] for what the markers do and do not cover. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class BlockDsl
