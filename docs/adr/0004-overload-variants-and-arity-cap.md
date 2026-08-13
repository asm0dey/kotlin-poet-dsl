# ADR 0004 — Overload variant shape and arity cap

**Status:** accepted
**Date:** 2026-08-13
**Depends on:** [ADR 0001](0001-scope-resolution-with-context-parameters.md)

## Context

The spec's positional call style is `` `fun`(PRIVATE + SUSPEND, "greet", param("greeting", STRING)) ``
and `` `class`(DATA, "User") ``. One signature cannot accept both a `KModifier` and a
`Modifiers` in the same slot. Defaulted nullable parameters (`modifiers: Modifiers? = null`)
would additionally make `` `fun`(name = "f") `` match every variant at once — an
ambiguity error.

Separately, parameters are lambda-bound, so they cannot be `vararg`: the lambda's arity
must match the parameter count, which forces one overload per arity.

## Decision

**Variant shape.** Each construct is declared in six variants, distinguished by presence
and type, never by defaults:

| Variant | Leading parameters |
|---|---|
| 1 | *(none)* |
| 2 | `modifiers: KModifier` |
| 3 | `modifiers: Modifiers` |
| 4 | `annotations: Annotations` |
| 5 | `annotations: Annotations, modifiers: KModifier` |
| 6 | `annotations: Annotations, modifiers: Modifiers` |

All are non-null and positional, ahead of `name`. Trailing parameters (`returns`, `type`,
`init`, `by`) keep their defaults — they differ in name, so they do not create ambiguity.

**Arity cap: 0–8.** Beyond eight parameters, callers use the list form
`params = listOf(…)` with `body: BlockScope.(List<Expr>) -> Unit`. The spec's 0–26 range
is not adopted: it costs ~324 public overloads in the API dump, slower compilation of the
generated sources, and noisier completion, in exchange for named handles on signatures
that are rare and usually built from a computed list anyway.

Generated total: 9 arities × 6 variants = 54 `fun` overloads and 54 `constructor`
overloads, once each — ADR 0001's single-declaration-on-`Scope` rule removes the
`FileScope`/`TypeScope` duplication the spec assumed.

## Consequences

- `buildSrc/ArityGenerator.kt` emits `FunArity.kt`, `CtorArity.kt`, and the ADR 0002
  shadow members, all from the same variant table, so the three cannot drift apart.
- A generator that emits a 12-parameter function reads `ps[0]`, `ps[11]` instead of named
  handles. Documented in the README next to the list form.
