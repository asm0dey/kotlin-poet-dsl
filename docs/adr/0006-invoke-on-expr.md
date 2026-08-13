# ADR 0006 — `invoke` on `Expr` means calling the value

**Status:** accepted
**Date:** 2026-08-13

## Context

The spec reserves `invoke()` for emission — defined on `Stmt`, `FunSpec`, `TypeSpec` and
`PropertySpec`, never on `Expr` — so that `f()` can only mean "emit this declaration".

That left generated *value* calls unspelled. `f(1)` where `f` is a local holding a lambda,
or a function-typed parameter being invoked, was reachable only through
`expression("%L(%L)", f, arg)`, which loses scope checking and reads like a raw string.

Member calls are a separate case and unaffected: the member name is not known when the
generator compiles, so it travels as a string or a reflection reference —
`xs.call("forEach")`, `xs.call(List<*>::isEmpty)`.

## Decision

**Define `operator fun Expr.invoke(vararg args: Expr): Expr`, meaning "call this value".**

```kotlin
val f = `val`("f", init = lambda { +call("work") })
+f(1.lit)      // f(1)
+f()           // f()
```

The spec's reservation was about reader confusion, not compiler ambiguity — the receiver
types are disjoint. The distinction now follows the library's existing everywhere-rule:
`invoke` on a spec returns `Unit` and emits; `invoke` on an `Expr` returns an `Expr` and
emits nothing.

## Consequences

- `f()` written as a bare statement inside a block produces an `Expr` that is silently
  discarded — the same trap as any other pure expression in this DSL, and the same fix:
  `+f()`. The README's emission-model section covers it once for all `Expr`-returning API.
- Precedence: the result is `Prec.POSTFIX`, so `(a ?: b)(1)` parenthesizes correctly.
- `Expr.invoke` with a trailing lambda (`f { … }`) follows ADR 0005's `param` rule, like
  the other lambda-taking call forms.
