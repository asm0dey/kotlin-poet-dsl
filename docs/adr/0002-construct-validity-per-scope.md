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

## Verified (Task 20)

The shadows are generated as **extensions on `BlockScope`**, not members, and the extension
does outrank the context function — measured by compiling the test sources with the shadows
in place, on Kotlin 2.4.10:

```
e: TypeScopeTest.kt:251:27 'fun BlockScope.object(name: String, body: TypeScope.() -> Unit): Nothing'
   is deprecated. A named object cannot be local in Kotlin. Declare it at file or type level,
   or use an anonymous object.
e: TypeScopeTest.kt:263:27 'fun BlockScope.interface(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit): Nothing'
   is deprecated. An interface cannot be local in Kotlin. Declare it at file or type level.
```

`ShadowsTest` pins this with `kctfork`, each negative case paired with a positive control one
scope out, so the failure is attributable to the shadow rather than to the snippet.

Widening the receiver's static type to `Scope` takes the extension out of the candidate set and
reaches the `IllegalStateException` branch again — which is why that branch stays, and how the
two `TypeScopeTest` "rejected, naming the construct" tests still exercise it.

Two amendments to the table above, both from Task 20's deviations:

- **`class` and `klass` get no shadow.** A local class *is* valid Kotlin; only KotlinPoet
  2.3.0's renderer blocks it, and `fun` is blocked for the identical reason and is not shadowed
  either. An `@Deprecated(ERROR)` overload would freeze a temporary backend defect into the
  locked public API and would have to be *removed* — a breaking change — the day the backend is
  fixed. `declareType`'s runtime `check` and the `local class rendering is still blocked by
  KotlinPoet` canary stay as the guard and the alarm.
- **`constructorParam`'s `kind` is `ParamKind?`, not `KModifier?`** (D19). `KModifier` has no
  `VAL`/`VAR`; a shadow with the plan's signature would not match the real overload, would not
  be applicable, and resolution would fall through — the exact silent-fallthrough failure this
  ADR was written about.

## Consequences

- Whatever is chosen, the invalid set is small and fixed: `object`/`interface` in a block,
  `constructorParam` outside a type, statements outside a block.
- Local `enum`, local `annotation class`, local named `object` and local `typealias` are
  not supported at block level because Kotlin does not support them. The DSL should not
  invent a spelling for output that cannot compile.
