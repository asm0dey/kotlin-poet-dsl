# ADR 0005 — Lambda parameter naming and implicit `it`

**Status:** accepted
**Date:** 2026-08-13

## Facts established by experiment

Kotlin 2.4.10. A scope-level `val BlockScope.it: String` property is **silently shadowed**
by Kotlin's own `it` inside any nested zero-parameter lambda that sits within a
single-parameter lambda:

```
plain nesting:      DSL-implicit-it     <- property resolved
inside forIn:       LOOP-HANDLE         <- Kotlin's it
nested in forIn:    LOOP-HANDLE         <- Kotlin's it shadowed the property
```

No warning is emitted. In DSL terms, `` `for`(items) { +it.call("map") { +it.prop("name") } } ``
would emit `items.map { item.name }` — code that compiles and does the wrong thing, the
worst failure mode this library can produce.

## Decision

**No `it` property. Every DSL lambda is arity-1 (or n), and the *rendered* parameter name
is controlled by a `param` argument, independent of the user's own Kotlin lambda
parameter name.**

```kotlin
// emits: items.map { it.name }
items.call("map") { p -> +p.prop("name") }

// emits: items.map { item -> item.name }
items.call("map", param = "item") { item -> +item.prop("name") }
```

Omitted `param` renders the handle as `it` and emits no parameter list. A given `param`
is uniquified against the enclosing `NameScope` and emitted as an explicit parameter.

## Consequences

- Shadowing is structurally impossible: the user's Kotlin binding name never reaches the
  output, and each handle is a distinct value.
- The spec's rule "a single unnamed parameter emits implicit `it`" survives, expressed
  through the absence of `param` rather than through the absence of a lambda parameter.
- Multi-parameter lambdas take `params = listOf("acc", "x")` following the same rule; the
  `fold` example in the spec becomes
  `items.call("fold", 0.lit, params = listOf("acc", "x")) { acc, x -> +(acc + x) }`.
- Loop variables keep the ADR-independent naming rule: explicit `name =` wins, else the
  singular of the iterable handle's name, else `item`.
