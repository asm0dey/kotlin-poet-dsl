# ADR 0002 — Construct validity per scope

**Status:** proposed
**Date:** 2026-08-13
**Depends on:** [ADR 0001](0001-scope-resolution-with-context-parameters.md)

## Facts established by experiment

Measured with `kotlinc` 2.4.10, one declaration per compile, inside a function body.

| Declaration in a function body | Legal? | Compiler says |
|---|---|---|
| `fun` (local function) | yes | — |
| `class`, `abstract class`, `data class` | yes | — |
| `val` / `var`, incl. `by lazy` and `lateinit` | yes | — |
| named `object` | **no** | `named object 'X' cannot be local. Try to use an anonymous object instead.` |
| `interface` | **no** | `interface 'X' cannot be local. Try to use an anonymous object or abstract class instead.` |
| `annotation class` | **no** | `annotation class cannot be local.` |
| `enum class` | **no** | `modifier 'enum' is not applicable to 'local class'.` |
| `typealias` | **no** (experimental) | `the feature "local type aliases" is experimental … '-Xlocal-type-aliases'` |

`constructorParam` is valid only in a `TypeScope` that has a primary constructor; it has
no meaning at file or block level.

## Validity matrix

| Construct | FileScope | TypeScope | BlockScope | Declared on |
|---|---|---|---|---|
| `fun` / `func` | top-level fun | member fun | local fun | `Scope` |
| `class` / `klass` | top-level | nested | local | `Scope` |
| `val` / `var` | top-level property | member property | local binding | `Scope` |
| `object` | top-level | nested | **invalid** | `DeclarationScope` (file + type) |
| `interface` | top-level | nested | **invalid** | `DeclarationScope` |
| `constructorParam` / `ctorParam` | **invalid** | yes | **invalid** | `TypeScope` |
| statements, control flow, `return` … | **invalid** | **invalid** | yes | `BlockScope` |

Block-level `val`/`var` and type-level `val`/`var` differ in more than target: a property
requires an explicit type (KotlinPoet demands it) while a local can infer, and only a
property accepts `by`/annotations/use-site targets. The single declaration therefore
takes the union of parameters and rejects the invalid combinations at generation time —
e.g. `type = null` in a `TypeScope` is an error naming the property.

## Decision

**A construct called where it is invalid is a compile error, produced by a
`@Deprecated(level = DeprecationLevel.ERROR)` shadow member on the scope class where it
is invalid.** The `when` branch still throws `IllegalStateException` internally, because
a `when` over a sealed hierarchy must be exhaustive, but no user is expected to reach it.

### Shadow members must mirror the real signature exactly

Measured, and it inverts the obvious implementation:

- `fun \`object\`(vararg ignored: Any?): Nothing` — **does not shadow**. The call
  `` `object`("Bad") { } `` compiled clean and silently dispatched to the context
  function. A trailing lambda does not bind into a `vararg Any?`, so the member is not
  applicable and resolution falls through.
- `fun \`object\`(name: String, body: TypeScope.() -> Unit): Nothing` — **shadows
  correctly**: `error: 'fun object(name: String, body: TypeScope.() -> Unit): Nothing' is deprecated. A named object cannot be local in Kotlin.`

Therefore: **one shadow member per public overload**, generated in the same `buildSrc`
task as the arity overloads so it cannot drift. A catch-all shadow is worse than none —
it reads as a guard while silently permitting the mistake.

### The shadow set is small

Only the *inward* direction leaks: an outer scope value stays visible inside inner
lambdas. The outward direction is already a compile error, because no `BlockScope` value
exists at file or type level — `` `if`(…) `` outside a block is an unresolved reference,
with no shadow needed.

| Scope | Needs shadows for |
|---|---|
| `BlockScope` | `object`, `interface`, `constructorParam` (and their aliases), one per overload variant |
| `TypeScope` | none — every file-level construct is also valid on a type |
| `FileScope` | none — it is the outermost scope |

## Consequences

- Whatever is chosen, the invalid set is small and fixed: `object`/`interface` in a block,
  `constructorParam` outside a type, statements outside a block.
- Local `enum`, local `annotation class`, local named `object` and local `typealias` are
  not supported at block level because Kotlin does not support them. The DSL should not
  invent a spelling for output that cannot compile.
