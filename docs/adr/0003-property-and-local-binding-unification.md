# ADR 0003 — `val`/`var` unified across scopes

**Status:** accepted
**Date:** 2026-08-13
**Depends on:** [ADR 0001](0001-scope-resolution-with-context-parameters.md), [ADR 0002](0002-construct-validity-per-scope.md)

## Context

Under ADR 0001, `val`/`var` is one declaration on `Scope`, dispatching on the runtime
scope. But a local binding and a property are not the same thing:

- KotlinPoet requires an explicit type on every `PropertySpec`; a local `val` can let
  Kotlin infer.
- Only a property takes `by` (delegate), annotations with use-site targets, and
  visibility modifiers.
- Only a local participates in the block's `NameScope` uniquifier at statement level.

## Decision

**One signature taking the union of parameters; invalid combinations fail at generation
time with a message naming the construct and the scope.**

```kotlin
context(s: Scope)
public fun `val`(
  name: String,
  type: TypeName? = null,
  init: Expr? = null,
  by: Expr? = null,
): Expr
```

with the annotation/modifier variants layered on top exactly as in ADR 0004 — that is,
`annotations: Annotations` and `modifiers: KModifier`/`Modifiers` are **non-defaulted,
positional, leading** parameters of separate overloads, not nullable defaults, so the
spec's positional style `` `val`(PRIVATE, "name", STRING) `` resolves.

Validation rules, all `IllegalStateException` at generation time:

| Condition | Message |
|---|---|
| `type == null` outside a `BlockScope` | `Property 'x' requires an explicit type; KotlinPoet cannot infer it.` |
| `by != null` and `init != null` | `Property 'x' cannot have both an initializer and a delegate.` |
| `by != null` in a `BlockScope` with `type == null` | allowed — Kotlin infers a local delegated property |
| annotations or modifiers in a `BlockScope` | `A local binding cannot carry annotations or modifiers.` |

## Consequences

- Call sites read the same at every level, which is the point of ADR 0001.
- The rejected alternatives were: requiring an explicit type everywhere (verbose generated
  locals, and the generator author must know types Kotlin would infer), and splitting
  `val` (local) from `property` (declaration) — which contradicts the spec's alias table,
  where `property` is documented as an alias of the declaration-level `` `val` ``.
- `property` therefore stays an alias, not a separate construct. `prop` remains property
  *access* on an `Expr` — a different thing with a deliberately similar name.
