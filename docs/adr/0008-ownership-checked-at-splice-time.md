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

A public wrapper type per builder, plus wrapping overloads of every `+`/`emit`/`add`, would
take the KotlinPoet spec *out* of the return position — which is the feature. Three new
permanent public types, added the day before the API is locked, to close a gap that has a
narrower shape than it first looks:

- A handle can only reach a detached builder's body if the caller already holds one from
  another scope — the same "smuggled through a Kotlin `var`" move safety layer 2 exists for.
  `funSpec` and `propertySpec` have no parent scope at all, so their whole body is a detached
  root: it accepts the handle and records it in `referenced`, and nothing later reads that
  record. `typeSpec` is narrower still — every member `` `fun` `` declared inside it is built
  with a non-null parent, so its body is *not* a detached root and a foreign handle used inside
  one is rejected exactly as it would be in an attached type. Only `typeSpec`'s own non-block
  positions — a property initializer or delegate declared directly on the type — go unchecked,
  and by a different route: the property-building path shared by `FileScope` and `TypeScope`
  never calls `checkOwned` at all, attached or detached.
- Everything the builders *emit into* is still checked where it is checked. A `Stmt` spliced
  into a detached `funSpec` body, or an `Expr` used inside it, goes through `checkOwned` against
  that body. What is unchecked is the outermost step (adding the finished spec to a file or
  type) plus, for `typeSpec` only, its own non-block positions.

**A cheaper third option exists, and was not taken.** Wrapper types are not the only way to
close the gap: `buildFun`'s `detachedRoot = parent == null` could instead be hardcoded to
`false`, making a detached body **reject** a foreign handle instead of recording it — the same
treatment an attached body already gets, with no new public type. Measured against the Task 21
suite, that one-line change fails exactly two tests, and both exist to pin the non-goal this
amendment describes: `FunctionsTest.a detached funSpec accepts a foreign handle instead of
rejecting it` and `StatementsTest.a detached declaration builder does not carry the scopes its
body used`. Nothing legitimate breaks, because nothing legitimate can reach a detached body from
outside it: `funSpec`/`propertySpec` take no scope parameter, so a handle from "outside" can only
name a binding declared in some *other*, unrelated function or property — never one the caller
was entitled to bring in. (An earlier KDoc defended the current behaviour as protecting "every
handle the caller legitimately brought in from outside," which does not hold for a detached
builder: there is no legitimate outside handle for one to protect.) This option was not taken
here because it is a **behaviour change**, and this task is documentation-only; it is also not a
binary-compatibility question — nothing about the public signature moves — so it is not locked in
by Task 22 and remains open to revisit on its own merits later.

**Decision:** amend the consequence rather than build the wrappers, and leave the reject-instead
option unexercised for now. The detached declaration builders are documented as an unchecked
boundary, alongside `expression("…")` — both are places where the DSL hands the caller something
raw and stops judging it. `Stmt` and `Expr` keep the check, because they are the DSL's own types
and can afford to carry it.

The KDoc on `funSpec`, `typeSpec` and `propertySpec` states this, and the README carries it in
the limitations section.
