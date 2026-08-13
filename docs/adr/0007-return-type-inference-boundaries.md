# ADR 0007 — Return-type inference boundaries

**Status:** accepted
**Date:** 2026-08-13

## Context

`` `return`(x) `` records `x.type` so a function's return type can be inferred. The plan
shared one `MutableList<TypeName?>` across every child scope, so a `ret` inside a
generated lambda drove the *enclosing function's* inferred return type.

## Decision

- **Control-flow children share the enclosing function's list.** A `return` inside an
  `if`, `for`, `while`, `when` or `try` is a real function return and must inform
  inference.
- **Lambda bodies get a fresh list.** A `return` there records nothing for the enclosing
  function and is emitted verbatim, producing a non-local return — legal when the callee
  is inline, which is the caller's choice exactly as in hand-written Kotlin.

The inference rule itself is unchanged: explicit `returns` wins; no recorded return means
`Unit` with the type omitted; all recorded types known and equal means that type;
anything else is an `IllegalStateException` naming the function and telling the author to
pass `returns = …`. Infer when provable, error when not — never silently wrong.

## Consequences

- `BlockScope.child(label)` takes a flag for which behaviour applies; lambda construction
  is the only caller that passes the isolating variant.
- A guard clause inside a `forEach`-style callback stays expressible.
- A generated non-local return from a non-inline callee is invalid Kotlin, caught when the
  generated file compiles. The DSL does not attempt to know which callees are inline.
