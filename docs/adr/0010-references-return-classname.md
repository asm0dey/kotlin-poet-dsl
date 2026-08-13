# ADR 0010 — `reference<T>()` returns `ClassName`

**Status:** accepted
**Date:** 2026-08-13

## Context

The spec lists `reference<T>()` (alias `ref`) under "Literals and references" as an
expression producing `%T`. Every actual usage site in the spec is a **type** position:

```kotlin
`var`(ann<Inject>(SET) + ann<VisibleForTesting>(SET), LATEINIT, "collaborator", ref<Collaborator>())
`try` { … }.`catch`("e", ref<IOException>()) { e -> … }
expr("%L.filterIsInstance<%T>()", xs, ref<Foo>())
```

## Decision

```kotlin
public inline fun <reified T> reference(): ClassName
public inline fun <reified T> ref(): ClassName

public fun ClassName.expression(): Expr      // alias: expr()
public fun MemberName.expression(): Expr     // alias: expr()

public val TypeName.nullable: TypeName get() = copy(nullable = true)
```

`ClassName` is a `TypeName`, so it drops into type positions unchanged and serves as a
`%T` argument. Expression position goes through `expression()`.

The `nullable` sugar is added because the spec writes `STRING.nullable`; KotlinPoet
spells it `copy(nullable = true)`.

## Consequences

- Every spec usage site compiles as written.
- `member(pkg, name)` follows the same pattern, returning `MemberName` rather than `Expr`,
  so it can be passed to `call(member)` and to `%M` slots directly.
- Static access reads `ref<System>().expression().prop("out")`, which is wordier than a
  dedicated helper would be. If that turns out to be common, a `staticProp`/`staticCall`
  pair can be added later without breaking anything.
