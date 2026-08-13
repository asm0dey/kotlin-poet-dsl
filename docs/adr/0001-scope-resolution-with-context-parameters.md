# ADR 0001 — Scope resolution with context parameters

**Status:** accepted
**Date:** 2026-08-13
**Context source:** empirical, Kotlin 2.4.10 (`kotlinc` local), probes in scratchpad `spike/probe*.kt`

## Facts established by experiment

All four probes ran against Kotlin 2.4.10. None of these behaviours are documented on
kotlinlang.org; each was measured, not inferred.

1. **`@DslMarker` does not filter context-argument resolution.** A `context(o: Outer)`
   function called inside an `@InnerDsl`-marked receiver lambda nested in an
   `@OuterDsl`-marked one compiles clean — no error, no warning — and runs.
   Consequence: for a DSL whose builders all take `context(Scope)`, `@DslMarker`
   protects nothing.

2. **A `@Deprecated(level = ERROR)` member on the inner scope class does shadow the
   outer context function**, with a custom message:
   `error: 'fun outerOnly(): Nothing' is deprecated. `fun` cannot be declared inside a function body.`
   Members outrank context-parameter functions in resolution.

3. **Two same-signature overloads distinguished only by context-parameter type are
   ambiguous** when both scopes are in scope:
   `error: overload resolution ambiguity between candidates: context(t: TypeScope) fun decl(): String / context(b: BlockScope) fun decl(): String`
   There is no innermost-wins rule between distinct overloads.

4. **One declaration parameterized on a common supertype resolves to the innermost
   scope value, with no ambiguity.** `context(d: DeclScope) fun decl(name: String)`
   called at file, type and block nesting levels printed `file:a`, `type:b`, `block:c`.

## Decision

**One declaration per construct, parameterized on the narrowest scope supertype that
covers every level where the construct is valid, dispatching on the runtime scope.**
Rejected alternatives: distinct names per level (`fun` vs `localFun` — breaks the
"reads like the Kotlin it generates" premise), and dropping block-level declarations
(forces raw `expression("…")` strings for local helpers).

Shape:

- A `Scope` supertype implemented by `FileScope`, `TypeScope`, `BlockScope`.
- **Constructs valid at more than one level are declared once** on the narrowest common
  supertype and dispatch on the runtime scope. Innermost wins, which is also the correct
  Kotlin semantics: `` `fun` `` inside a body is a *local function*, inside a type a
  member, at file level a top-level function.
- **Constructs valid at only an outer level** (`constructorParam`) stay declared on that
  scope and get a `@Deprecated(level = ERROR)` shadow member on the inner scopes, so
  calling them from the wrong place is a compile error with a useful message.

### `Scope` is sealed

```kotlin
public sealed interface Scope
```

implemented by `FileScope`, `TypeScope` and `BlockScope` only. Every dispatching `when` is
exhaustive, so adding a scope makes the compiler list every construct that forgot a case.
Sealing is the reversible direction: opening later breaks nobody, sealing later breaks
everybody. Consumers still extend the DSL the way the spec intends — by writing
`context(b: BlockScope) fun Expr.orThrow(…)` helpers — they just cannot invent a fourth
scope.

### The `@DslMarker` annotations stay

They no longer guard context-parameter functions (fact 1), but they still do their
documented job for **member**-based APIs: `WhenScope.branch`, `IfChain.elseIf`,
`TryChain.catch`. Without the markers, `branch(…)` would resolve to the outer `WhenScope`
receiver from inside a nested block body.

## Consequences

- The spec's claim that `@DslMarker` makes cross-level mistakes compile errors is false
  as written and must be rewritten in both the spec and the README.
- No parallel `Stmts`-style mirror API is needed; that part of the context-parameter
  rationale survives intact.
- Constructs that are legal Kotlin at the inner level (local `fun`, local `class`, local
  `object`) become features rather than errors.
