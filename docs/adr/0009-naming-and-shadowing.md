# ADR 0009 — Naming, uniquification and shadowing

**Status:** accepted
**Date:** 2026-08-13

## Decision

**One naming mechanism: uniquification. Nothing is ever qualified with `this.`**

Every binder registers its name in the enclosing `NameScope`, and a colliding name gets a
numeric suffix — `item`, `item2`, `item3`. `NameScope`s nest with the scopes: a member
body's `NameScope` is a child of its type's, so a local that would shadow a property is
renamed at declaration and shadowing never arises.

```kotlin
class User(val username: String) {
  fun greet() {
    val username2 = compute()   // local, renamed
    println(username)           // property, unqualified
  }
}
```

Loop variable defaults are unchanged: explicit `name =` wins, else the singular of the
iterable handle's name (`items` → `item`, `users` → `user`), else `item`.

## Alternatives rejected

- **Always qualify member handles as `this.name`.** Locals would keep the caller's chosen
  name, but every member reference in generated output carries a redundant `this.`.
- **Qualify only when shadowed** — the spec's literal wording. Cleanest output, but an
  `Expr`'s rendering would become a function of the emitting scope, and that has to
  propagate through every composition: `username.call("uppercase")` is built long before
  anyone knows which body it lands in. A viral change to the expression model for a rare
  case.

## Consequences

- The spec's sentence about automatic `this.name` qualification is superseded; README and
  spec both need the correction.
- A caller who writes `` `val`("username", …) `` inside a type that already has
  `username` gets `username2`, and the returned handle carries the new name — so the
  generated code is consistent, just not literally what was asked for.
- Uniquification is already required for loop variables and lambda parameters, so this
  adds no machinery.
- **Amendment (D21).** The paragraph above describes a collision between *different*
  constructs — a local shadowing a property, a property colliding with a constructor
  parameter — where valid Kotlin exists either way and renaming preserves it. It does not
  cover two declarations of the *same* construct in the same container: two properties both
  named `username`, or two constructor parameters both named `id`. That is a compile error
  in Kotlin with no valid output to preserve, so renaming would only invent a public API
  name nobody asked for. `propertyOf` and `addConstructorParam` reject that case outright
  with a named `check`, the same way a duplicate type name is already rejected — cross-construct
  collisions are unaffected and still uniquify as above.
