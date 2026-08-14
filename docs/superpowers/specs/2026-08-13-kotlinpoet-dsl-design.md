# KotlinPoet DSL — Design

**Date:** 2026-08-13
**Status:** Approved design, ready for implementation planning

## Purpose

A Kotlin DSL over [KotlinPoet](https://github.com/square/kotlinpoet) that makes generator code read like the Kotlin it generates.

KotlinPoet already has builders for declarations. Its weak spot is function bodies: `beginControlFlow("for (item in objects)")` / `endControlFlow()`, raw strings, manual indent tracking, and no way to reference a variable except by spelling its name again. The existing `hanggrian/kotlinpoet-dsl` prettifies declaration builders but leaves bodies as strings.

This library covers the whole surface — files, types, members, bodies — with typed handles for names and an expression model that keeps `%T`/`%M` placeholders intact so imports resolve automatically.

Target: a publishable general-purpose library, usable from KSP processors, standalone generators, and one-off scripts. KSP interop needs no bridging code — `Expr` carries a `TypeName`, and `kotlinpoet-ksp` already provides `KSType.toTypeName()`.

## Constraints

- **Kotlin baseline: 2.4+.** Context parameters are stable there, and the DSL depends on them.
- **KotlinPoet is the only backend.** No own IR, no post-processing passes.
- **Expression coverage is core-level.** Arithmetic, comparison, logical, elvis, calls, property access, literals, type references, lambdas. Everything else goes through the `expr("…")` escape hatch.
- **No compile-time type checking of generated code.** Handles carry a `TypeName` at runtime for inference and diagnostics, not for generator-time type safety. Phantom types (`Expr<Int>`) were rejected: generated code routinely references types that do not exist when the generator compiles.

## Language-feature stance

`@DslMarker` and context parameters are used **to the maximum**, not sprinkled where convenient. Concretely:

- **A distinct `@DslMarker` annotation per scope level** — `@FileDsl`, `@TypeDsl`, `@BlockDsl` — rather than one shared marker. They guard the **member**-based APIs (`WhenScope.branch`, `IfChain.elseIf`, `TryChain.catch`), which is where implicit outer receivers actually leak.

  They do **not** guard context-parameter functions: measured on Kotlin 2.4.10, `@DslMarker` has no effect on context-argument resolution at all — a `context(o: Outer)` function called inside a marked inner receiver lambda compiles clean, without even a warning. Since every builder in this DSL takes its scope as a context parameter, cross-level protection comes from elsewhere; see [ADR 0001](../../adr/0001-scope-resolution-with-context-parameters.md).

- **One declaration per construct, on a sealed `Scope` supertype.** Two overloads distinguished only by context-parameter type are an *ambiguity error* wherever both scopes are in scope, so per-scope overloads are impossible. A single declaration parameterized on a common supertype instead resolves to the innermost scope value — which is also the right semantics: `` `fun` `` inside a body is a local function, inside a type a member, at file level a top-level function.

- **Constructs invalid at a level are compile errors via shadow members.** A `@Deprecated(level = ERROR)` member on the scope class outranks the context function. The shadow must mirror the real overload's signature exactly — a `vararg Any?` catch-all silently fails to shadow, which is worse than no guard at all.
- **Context parameters carry scope; receivers carry the subject.** Any function that needs a scope declares it as `context(b: BlockScope)`, never as a receiver. The receiver slot is reserved for the thing being operated on (`Expr`, `Stmt`, `TypeName`), which is what makes user-written extensions like `context(b: BlockScope) fun Expr.orThrow(…)` possible at all.
- **Multiple context parameters where a construct genuinely spans scopes** — e.g. `context(f: FileScope, b: BlockScope)` for a helper that emits a statement and registers an import.
- **Internal plumbing rides in context too.** `NameScope` and the current `ScopeId` are context parameters, not threaded arguments, so internal helpers keep the same shape as public API.
- **Public API is designed to be extended this way.** Anything the library declares with a context parameter is a pattern a user can copy verbatim in their own codegen helpers; a helper someone writes should be indistinguishable from a built-in.

This is the reason for the Kotlin 2.4 baseline, and the trade is deliberate: consumers below 2.4 cannot use the library at all.

## Architecture

Three scope types, each with its own `@DslMarker` annotation so implicit outer receivers are blocked:

```
FileScope → TypeScope → BlockScope
```

Scopes write directly into KotlinPoet builders. `BlockScope` holds a `CodeBlock.Builder` plus a `NameScope`; control flow calls `beginControlFlow` / `nextControlFlow` / `endControlFlow`. Indentation is KotlinPoet's job.

### Core types

```kotlin
class Expr internal constructor(
  internal val code: CodeBlock,
  internal val type: TypeName? = null,
  internal val prec: Int = Prec.ATOM,
  internal val name: String? = null,
  internal val scope: ScopeId? = null,
)

class Stmt internal constructor(internal val code: CodeBlock)

@JvmInline value class Modifiers(internal val set: Set<KModifier>)
@JvmInline value class Annotations(internal val list: List<AnnotationSpec>)
```

`prec` drives automatic parenthesization: `(a + b) * c` gets parens, `a + b * c` does not.

## Emission model

**`Unit`-returning API emits. `Expr`-returning API does not.** This mirrors Kotlin's own statement/expression split — `a += b` has no value in Kotlin, `a.foo()` does — so the rule is already known, and the IDE shows the return type.

```kotlin
item.call("length")            // Expr — nothing emitted
+item.call("length")           // emitted
stmt(item.call("length"))      // same
statement(item.call("length")) // same
total += item.prop("length")   // Unit — emitted
val n = `val`("n", INT, x)     // emits AND returns a handle — the one exception
```

Every construct also has a **pure form**: the same syntax inside a detached-scope builder.

```kotlin
val guard: Stmt = stmts { `if`(x eq nul) { +ret(nul) } }
`fun`("f") { +guard }
```

Because statement builders are declared with `context(b: BlockScope)`, there is exactly one declaration per construct — no parallel `Stmts` mirror object to keep in sync. `stmts { }` runs the same functions against a detached `BlockScope` and returns a `Stmt`.

The five compound-assignment operators (`+= -= *= /= %=`) exist in emitting form only: Kotlin requires `plusAssign` to return `Unit`, so operator syntax can never be pure. Their pure twins are named functions:

```kotlin
total += x                        // emitting
stmts { total += x }              // pure, same output
stmts { total assign (total + x) }
```

### Emitting a pre-built spec

Four equivalent spellings, all accepted:

```kotlin
+f  ·  f()  ·  emit(f)  ·  add(f)
```

`invoke()` on `Stmt`, `FunSpec`, `TypeSpec` and `PropertySpec` returns `Unit` and emits. `invoke()` on an `Expr` returns an `Expr` and means **calling that value** — generated `f(1)` where `f` holds a lambda or a function-typed parameter. The two never collide: receiver types are disjoint, and the Unit-emits/Expr-doesn't rule already tells you which is which. A generated *member* call is still `x.call("f")` or `call("f")`, because the member name is unknown when the generator compiles.

## Why context parameters

Statement builders take `context(b: BlockScope)` rather than a `BlockScope` receiver. This buys:

1. **One declaration per construct** — the pure form is the same function run against a detached scope, so there is no mirror API to maintain.
2. **User-written scope-aware extensions on `Expr`**, impossible with plain receivers because the extension slot is already spent:
   ```kotlin
   context(b: BlockScope)
   fun Expr.orThrow(msg: String) = `if`(this eq nul) { +`throw`(…) }
   ```
   With receivers only, such a helper would have to be a member-extension inside `BlockScope`, declarable only by this library.
3. **Helpers requiring two scopes at once** (block plus file, e.g. to register an import).

Context parameters cannot prevent a smuggled handle: `context(b: BlockScope)` proves *some* block is in scope, not that it owns the handle. Proving ownership needs rank-2 types, which Kotlin lacks. Leak detection stays a runtime check (see Safety).

## Expressions

`Expr` values are pure; none of them emit.

| Kotlin | DSL | Reason for the difference |
|---|---|---|
| `a + b`, `-`, `*`, `/`, `%` | same | overloadable |
| `a == b`, `a != b` | `a eq b`, `a neq b` | `equals` must return `Boolean` |
| `a < b`, `<=`, `>`, `>=` | `a lt b`, `le`, `gt`, `ge` | `compareTo` must return `Int` |
| `a && b`, `\|\|`, `!a` | `a and b`, `a or b`, `a.not()` | not overloadable |
| `a ?: b` | `a elvis b` | not overloadable |
| `a = b` | `a assign b` | not overloadable |
| `a.foo(x)` | `a.call("foo", x)` | member names unknown at generator compile time |
| `a.foo` | `a.prop("foo")` | same |
| `a?.foo()`, `a?.foo` | `a.safeCall("foo")`, `a.safeProp("foo")` | |

Literals and references:

```kotlin
1.literal  ·  "s".literal        // %S, escaping handled — alias: .lit
true.literal  ·  nullLiteral     // alias: nul
reference<Collaborator>()        // ClassName — %T, import resolved — alias: ref
member("kotlin", "lazy")         // MemberName — %M, import resolved — alias: mem
expression("%T.of(%L)", cls, x)  // escape hatch, placeholders intact — alias: expr
STRING.nullable                  // TypeName sugar for copy(nullable = true)
```

`reference` returns a `ClassName` and `member` a `MemberName`, so both drop into type positions and `%T`/`%M` slots unchanged. For expression position — a static call, a companion reference — use `reference<System>().expression()`.

Examples throughout this document use the short aliases for readability; both spellings are permanent public API.

Calls, three receiverless forms plus member calls:

```kotlin
call("calculate")                    // calculate() — bare, no import
call(mem("kotlin", "lazy")) { … }    // lazy { … }
call(::lazy) { … }                   // same, via callable reference
x.call("isNotEmpty")
x.call(String::isNotEmpty)           // typo-safe when the API is on the classpath
u.prop(User::name)
```

### Callable references

`kotlin-reflect` is an `implementation` dependency. `KFunction.javaMethod?.declaringClass` yields package and owner: top-level functions become `MemberName(pkg, name)` → `%M` with the import resolved; member functions contribute a bare name, qualified by the receiver `Expr`. `KProperty` works the same way.

Two documented limitations:

- References are a **name source only**. `Expr` is untyped, so `someInt.call(String::isNotEmpty)` compiles and generates `someInt.isNotEmpty()` — invalid code that fails only when the generated output is compiled. Same risk class as `call("isNotEmpty")`.
- Inline functions with reified type parameters (`arrayOf`, `emptyArray`, `typeOf`) cannot be referenced — Kotlin rejects `::arrayOf`. Use `mem("kotlin", "arrayOf")`.

### Lambdas

```kotlin
items.call("map", param = "item") { item -> +item.prop("name") }   // items.map { item -> item.name }
items.call("map") { p -> +p.prop("name") }                         // items.map { it.name } — implicit it
items.call("fold", 0.lit, params = listOf("acc", "x")) { acc, x -> +(acc + x) }
lambda { +call("calculate") }                                      // { calculate() } as a standalone value
```

A lambda body is a detached `BlockScope`; its last emitted statement is the lambda's value, exactly as in Kotlin. Parameter arities 0–8.

The **rendered** parameter name comes from `param =`/`params =`, never from the name the caller happens to bind in their own Kotlin lambda. Omitting it renders the handle as `it` and emits no parameter list. This is deliberate: a scope-level `it` property is silently shadowed by Kotlin's own `it` inside nested lambdas, which would emit the wrong handle with no warning. See [ADR 0005](../../adr/0005-lambda-parameter-naming.md).

### Not modelled

Ranges, `in`, `is`, casts, spread, labels, explicit generic arguments on calls, string templates. All reachable through `expr("…")`, which preserves `%T`/`%M` so imports still resolve:

```kotlin
expr("%L.filterIsInstance<%T>()", xs, ref<Foo>())
```

Strings inside `expr` bypass scope checking. Accepted trade-off.

## Statements and control flow

All of these are `context(BlockScope)` functions.

```kotlin
statement(x) · stmt(x) · +x
`return`(x) · `return`() · `break` · `continue` · `throw`(x)   // aliases: ret, brk, cont, throwIt
`val`(name, type, init)     // returns handle; type = null to omit
`var`(name, type, init)
x assign y · x += y · x -= y · x *= y · x /= y · x %= y
```

Loops and branches:

```kotlin
`for`(items) { item -> … }
`for`(items, name = "user") { user -> … }
`while`(cond) { … }
doWhile(cond) { … }

`if`(x lt 0.lit) {
  ret(false.lit)
}.elseIf(x gt 100.lit) {
  ret(false.lit)
}.`else` {
  ret(true.lit)
}

`when`(subject) {
  branch(1.lit) { … }
  branch(2.lit, 3.lit) { … }
  `else` { … }
}
whenTrue { branch(cond) { … } }

`try` { … }.`catch`("e", ref<IOException>()) { e -> … }.finally { … }
```

**If-chain implementation.** KotlinPoet's control-flow API is linear, so `if` emits `beginControlFlow` plus its body and leaves the block open, returning an `IfChain`. `BlockScope` tracks the pending open block and flushes `endControlFlow()` before the next emission or at block close. `elseIf` / `else` call `nextControlFlow` instead. An unbalanced chain is impossible to express; a pending chain left open at block close is flushed, not an error.

## Declarations

Positional order everywhere matches Kotlin source: **annotations, modifiers, name, …**

```kotlin
file("com.example", "User") {
  annotate<JvmName>("value" to "UserKt".lit)      // @file:JvmName — FileScope defaults target to FILE

  `class`(ann<Serializable>(), DATA, "User") {
    val username = ctorParam(VAL, "username", STRING)
    val email    = ctorParam(ann<Email>(), VAL, "email", STRING)

    `val`(ann<Email>(), "secondaryEmail", STRING.nullable, init = nul)
    `val`(ann<XAnnotation>(ALL), "x", INT, by = call(mem("kotlin", "lazy")) { +call("calculate") })

    `fun`(PRIVATE + SUSPEND, "greet", param("greeting", STRING)) { greeting ->
      +logger.call("info", greeting)
      +logger.call("info", username)               // ctor handle in scope
    }
  }
}
```

Property declarations take `init =` for an initializer and `by =` for a delegate, mapping to KotlinPoet's `PropertySpec.initializer` and `PropertySpec.delegate`. KotlinPoet requires an explicit type on every property, so `by lazy` cannot infer — the type is mandatory in the DSL even where Kotlin would infer it.

`constructorParam(VAL | VAR | null, …)` (alias `ctorParam`) adds a `ParameterSpec` to the primary constructor and, for `VAL`/`VAR`, a matching `PropertySpec` with `initializer("%N", param)`. It returns a handle visible to every sibling member — no nesting, no arity ceiling. Passing `null` makes it a plain parameter.

Pure declaration forms follow the same detached-scope pattern as `stmts { }`, and return KotlinPoet specs directly, so interop with hand-written KotlinPoet is free:

```kotlin
val f: FunSpec = funSpec("helper") { ret(1.lit) }
val t: TypeSpec = typeSpec(DATA, "User") { … }
file("com.example", "Api") { +f }
```

One detached builder per declaration kind (`funSpec`, `typeSpec`, `propertySpec`) — not a mirror API.

### Function and constructor arities

Parameters are lambda-bound, so they cannot be `vararg` — the lambda's arity must match. `` `fun` `` and `` `constructor` `` are generated for arities **0–8**, in three modifier variants (none, single `KModifier`, `Modifiers`) and two annotation variants: 54 overloads per family. Because each is declared once on `Scope` rather than per scope type, that is the whole set.

Variants are distinguished by **presence and type**, never by defaults: `annotations` and `modifiers` are non-null positional parameters ahead of `name`. Defaulted nullable parameters would make `` `fun`(name = "f") `` match all six variants at once.

A `buildSrc` Gradle task emits `FunArity.kt`, `CtorArity.kt` and the shadow members into `build/generated/source/dsl`, written with plain KotlinPoet — no bootstrap circularity.

Above 8 parameters, or for dynamically sized parameter lists:

```kotlin
`fun`("wide", params = listOf(param("a", INT), …)) { ps ->   // ps: List<Expr>
  +ps[0]
}
```

### Return type inference

`` `return`(x) `` infers the function's return type when `x.type` is known — literals, parameters, and declared `val`/`var` all carry one. Unknown type (`` `return`(x.call("foo")) `` — the callee's return type is unknowable) raises a build-time error naming the fix: pass `returns = …`. No return at all means `Unit`, with the type omitted from output. The rule is *infer when provable, error when not* — never silently wrong.

### Modifiers

A `Modifiers` value class combined with `+`, always immediately before the name:

```kotlin
`class`(DATA, "User") { … }
`class`(SEALED + INTERNAL, "Repo") { … }
`fun`(PRIVATE + SUSPEND, "process", param("x", INT)) { x -> … }
`val`(PRIVATE, "name", STRING)
```

`operator fun KModifier.plus(KModifier): Modifiers`. Generated single-`KModifier` variants mean `SEALED` alone needs no wrapping.

### Annotations

An `Annotatable` interface implemented by every scope. Two complementary forms:

```kotlin
// parameter form — for the common case
`var`(ann<Inject>(SET) + ann<VisibleForTesting>(SET), LATEINIT, "collaborator", ref<Collaborator>())

// trailing-lambda form — for conditional, computed, or looped annotations
`val`("name", STRING) {
  annotate<SerialName>("value" to "user_name".lit)
  if (deprecated) annotate<Deprecated>("message" to msg.lit)
}
```

Both apply when used together. Signatures:

```kotlin
annotation<T>(target: UseSiteTarget? = null, vararg args: Expr)   // alias: ann
annotation<T>(vararg named: Pair<String, Expr>)
annotation(cls: ClassName, …)                                     // runtime-known types
```

Arguments are `Expr`, not strings, so `%T`/`%M` survive and imports resolve.

`UseSiteTarget` re-exports KotlinPoet's enum. Kotlin 2.2's `@all:` meta-target is included: **KotlinPoet 2.3.0 ships `UseSiteTarget.ALL`**, verified against the published API, so no shim is needed.

KotlinPoet emits one annotation per line rather than Kotlin's bracketed `@set:[A B]` form. The output is semantically identical; no raw escape is provided.

Type-position annotations go through `annotated`, this DSL's bridge from `annotation`/`ann` to a `TypeName`: `param("x", INT.annotated(ann<Positive>("min" to 0.lit)))`. (The spelling this line carried originally — `INT.annotated<Positive>()` — matched no overload; KotlinPoet 2.3.0 has no reified form, and its `AnnotationSpec`-taking overloads cannot be reached from outside the module because `Annotations.list` is `@PublishedApi internal`. See the E1 fix round.)

### Aliases

Every backticked keyword has a full-word alias, so bulk codegen need not fight backticks:

| Backticked | Alias |
|---|---|
| `` `fun` `` | `func` |
| `` `class` `` | `klass` |
| `` `val` `` (property) | `property` |
| `` `if` `` | `ifThen` |
| `` `for` `` | `forIn` |
| `` `when` `` | `whenOn` |
| `` `try` `` | `tryCatch` |
| `` `throw` `` | `throwIt` |
| `` `return` `` | `ret` |
| `` `break` `` | `brk` |
| `` `continue` `` | `cont` |
| `annotation` | `ann` |
| `member` | `mem` |
| `expression` | `expr` |
| `reference` | `ref` |
| `.literal` | `.lit` |
| `nullLiteral` | `nul` |
| `statement` | `stmt` |
| `constructorParam` | `ctorParam` |
| `` `constructor` `` | `ctor` |
| `typeReference` | `typeRef` |
| `typeVariable` | `typeVar` |
| `functionType` | `funType` |
| `arrayLiteral` | `arrayLit` |
| `parameterizedBy` | `of` |

The convention is **full word is canonical, short form is the alias** — `annotation`/`ann`, `member`/`mem`, `expression`/`expr`, `reference`/`ref`, `literal`/`lit`, `statement`/`stmt`, `constructorParam`/`ctorParam`. Where the natural full name is a Kotlin keyword it is backticked and canonical (`` `return` ``/`ret`, `` `break` ``/`brk`, `` `continue` ``/`cont`, `` `constructor` ``/`ctor`). Full alias table is fixed during implementation; both spellings are supported permanently. Note that `property` (declaration) and `prop` (property *access* on an `Expr`) are deliberately different names — they live in different scopes but would read alike.

**`parameterizedBy`/`of` is the one row that is not a short form but a different word**, and deliberately so: the nesting the construct exists for reads as `MAP.of(STRING, LIST.of(user))` and is unusable spelled out. Its KDoc says so. `className` has no alias at all, which the rule in the next paragraph already covers.

**The table above is a fixed list, not a blanket rule: a construct with no natural short form gets no alias.** The operator names already show this — `eq`, `lt`, `and`, `not`, `elvis` are canonical with nothing to shorten — and D26's `superclass`/`superinterface` are the same case, decided by the human: the two candidates worth considering, `extends`/`implements`, import Java's spelling and read wrong inside an `` `interface` `` body, where `superinterface` means *extends*. An alias audit should not flag either as a gap.

## Naming and scope

Every binder registers its name in the enclosing `NameScope`. Collisions get a numeric suffix: `item`, `item2`, `item3`.

Loop variable defaults come from singularizing the iterable handle's name (`items` → `item`, `users` → `user`); with no name available, `item`. An explicit `name =` always wins.

`NameScope`s nest with the scopes, so a local that would shadow a property is uniquified at declaration (`username` → `username2`) and shadowing never arises. Nothing is qualified with `this.` — one naming mechanism, not two. See [ADR 0009](../../adr/0009-naming-and-shadowing.md).

## Safety and errors

Three layers:

- **`@DslMarker`** — one annotation per scope level (`@FileDsl`, `@TypeDsl`, `@BlockDsl`), guarding the member-based APIs (`WhenScope.branch`, `IfChain.elseIf`, `TryChain.catch`). It does **not** guard context-parameter functions; see the language-feature stance above.
- **Shadow members.** A construct invalid at a level is a compile error there, produced by a `@Deprecated(level = ERROR)` member mirroring the real overload. The set is small: `object`, `interface` and `constructorParam` inside a `BlockScope`. The reverse direction needs nothing — no `BlockScope` value exists at file or type level, so `` `if`(…) `` outside a block is already an unresolved reference.
- **Runtime ownership check, verified at splice time.** `Expr` carries its owning `ScopeId`, and a pure-form `Stmt` carries the set of scopes it referenced. Emission validates them against the target scope, which is the only place ownership can be judged — a detached builder does not yet know where its output will land. The message names the handle and its declaring construct. This catches handles smuggled out through a Kotlin `var`.

All failures are build-time `IllegalStateException`s naming the offending construct: out-of-scope handle, un-inferable return type, unresolvable callable reference. Partial or silently wrong output is never produced.

## Testing

**Golden tests** are the backbone: build a spec, render `FileSpec.toString()`, compare against expected Kotlin source. Coverage: one per construct, a precedence matrix (nested arithmetic / comparison / elvis combinations), shadowing and uniquification cases, import resolution, annotation targets, arity boundaries (0, 1, 22, 23, 26, list form).

**Compile tests** using `kotlin-compile-testing` for the cases where "looks right" is not proof: precedence, imports, shadowing, lambdas, delegated properties. A handful only — they are slow.

**Escape-hatch tests** confirming `expr("…")` preserves `%T`/`%M` and that raw strings bypass scope checking as documented.

## Module layout

Single Gradle module, Kotlin JVM, `api(kotlinpoet)`, `implementation(kotlin-reflect)`, `explicitApi()`, binary-compatibility-validator.

```
Expr.kt  Prec.kt  Literals.kt        — expression model
Calls.kt  Lambdas.kt  Refs.kt        — calls, lambdas, callable references
Stmt.kt  BlockScope.kt  ControlFlow.kt
Decls.kt  FileScope.kt  TypeScope.kt
Annotations.kt  Modifiers.kt
Names.kt                             — scope stack, uniquifier, singularizer
buildSrc/…/ArityGenerator.kt         — emits FunArity.kt, CtorArity.kt
```

## Open implementation tasks

1. ~~Determine which KotlinPoet version exposes `UseSiteTarget.ALL`~~ — resolved: 2.3.0 has it, no shim.
2. Spike `KFunction`/`KProperty` → `MemberName` resolution across top-level functions, member functions, extension functions, and properties. Document what fails.
3. ~~Fix the final alias table~~ — fixed executably: one assertion per row in `AliasTest`.

## Decision record

Eleven ADRs in [`docs/adr/`](../../adr/) carry the decisions that superseded parts of this
document, each backed by behaviour measured against Kotlin 2.4.10 rather than inferred
from documentation. [`docs/glossary.md`](../../glossary.md) fixes the vocabulary.
