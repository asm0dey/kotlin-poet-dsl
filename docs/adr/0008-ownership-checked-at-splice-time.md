# ADR 0008 — Handle ownership is checked at splice time

**Status:** accepted
**Date:** 2026-08-13

## Context

Safety layer 2 is a runtime check: an `Expr` carries the `ScopeId` that declared it, and
emitting it throws when that scope does not enclose the current one. This catches a handle
smuggled out through a Kotlin `var`.

The pure form breaks the check. `stmts { }` builds a detached scope with no parent, so
outer handles referenced inside it would all look foreign. The plan's answer was to
disable the check in detached scopes — which switches safety layer 2 off for every pure
form, the exact place where handles are most likely to travel.

A context-aware `stmts` is not an option: `context(b: BlockScope) fun stmts(…)` and a
no-context overload are ambiguous wherever a block is in scope (ADR 0001, fact 3), and the
spec's `val guard: Stmt = stmts { … }` is written at generator top level, outside any
scope.

## Decision

**`Stmt` records the `ScopeId` of every handle used to build it; emission validates them
against the target scope.**

```kotlin
public class Stmt internal constructor(
  internal val code: CodeBlock,
  internal val usedScopes: Set<ScopeId>,
)

context(b: BlockScope)
public operator fun Stmt.unaryPlus() {
  usedScopes.forEach { b.checkOwned(it) }
  b.builder.add(code)
}
```

Ownership is judged where it can actually be judged — at the splice — rather than at
construction, where the destination is unknown.

## Consequences

- One `stmts` declaration, no ambiguity, no hole.
- A `Stmt` built and never emitted is validated by nobody, which is correct: nothing was
  generated.
- The same treatment applies to the detached declaration builders (`funSpec`, `typeSpec`,
  `propertySpec`): they record used scopes and are validated when added.
- `expression("…")` still bypasses scope checking, because raw strings carry no handles.
  That remains the documented escape-hatch trade-off.
