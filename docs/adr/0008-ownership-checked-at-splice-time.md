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
- ~~The same treatment applies to the detached declaration builders (`funSpec`, `typeSpec`,
  `propertySpec`): they record used scopes and are validated when added.~~ **Amended by Task
  21 — see below.**
- `expression("…")` still bypasses scope checking, because raw strings carry no handles.
  That remains the documented escape-hatch trade-off.

## Amendment (Task 21): the detached *declaration* builders carry no scopes

The struck-through consequence above is not buildable as written, and the shape it asks for
is not worth having.

`stmts` can record scopes because `Stmt` is **this DSL's own type**, introduced by this ADR
for exactly that purpose. The detached declaration builders return `FunSpec`, `TypeSpec` and
`PropertySpec` — KotlinPoet's types, returned deliberately so that interop with hand-written
KotlinPoet is free (it is the entire reason those builders exist). A KotlinPoet spec has
nowhere to put a `Set<ScopeId>`, and `+spec` therefore has nothing to validate.

Making it work would mean a public wrapper type per builder, plus wrapping overloads of every
`+`/`emit`/`add`, and it would take the KotlinPoet spec *out* of the return position — which
is the feature. Three new permanent public types, added the day before the API is locked, to
close a gap that has a narrower shape than it first looks:

- A handle can only reach a detached builder's body if the caller already holds one from
  another scope — the same "smuggled through a Kotlin `var`" move safety layer 2 exists for.
  The body is a detached root, so it accepts the handle and records it in `referenced`, and
  nothing later reads that record.
- Everything the builders *emit into* is still checked. A `Stmt` spliced into a detached
  `funSpec` body, or an `Expr` used inside it, goes through `checkOwned` against that body.
  What is unchecked is only the outermost step: adding the finished spec to a file or type.

**Decision:** amend the consequence rather than build the wrappers. The detached declaration
builders are documented as an unchecked boundary, alongside `expression("…")` — both are
places where the DSL hands the caller something raw and stops judging it. `Stmt` and `Expr`
keep the check, because they are the DSL's own types and can afford to carry it.

The KDoc on `funSpec`, `typeSpec` and `propertySpec` states this, and the README carries it in
the limitations section.
