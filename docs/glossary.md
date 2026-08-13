# Glossary

Domain vocabulary for kotlin-poet-dsl. Terms here are used with exactly these meanings in
the spec, the ADRs, the plan and the source.

## Scopes

**Scope** — sealed supertype of the three nesting levels. A construct valid at more than
one level is declared once on `Scope` and dispatches on the runtime type; the innermost
scope value wins ([ADR 0001](adr/0001-scope-resolution-with-context-parameters.md)).

**FileScope** — the `.kt` file being built. Wraps a `FileSpec.Builder`. Annotations added
here default to the `@file:` use-site target.

**TypeScope** — a class, object or interface body. Wraps a `TypeSpec.Builder` plus the
primary-constructor builder that `constructorParam` feeds.

**BlockScope** — a function, lambda or control-flow body. Wraps a `CodeBlock.Builder`, a
`NameScope`, a `ScopeId`, the return-type inference list, and the pending open control
flow.

**Scope lambda** — a builder's trailing lambda, always declared with the scope as
*receiver* (`BlockScope.() -> Unit`). Builders declare the scope as a *context parameter*.
The receiver satisfies the context argument; that split is what makes user-written
`context(b: BlockScope) fun Expr.orThrow(…)` helpers possible.

## Values

**Expr** — a generated Kotlin expression. Pure: building one emits nothing. Carries an
optional `TypeName` (for return inference), a precedence level (for parenthesization), an
optional source name, and the `ScopeId` that declared it.

**Stmt** — a generated statement produced by the pure form, plus the set of `ScopeId`s it
referenced, validated when it is spliced ([ADR 0008](adr/0008-ownership-checked-at-splice-time.md)).

**Handle** — an `Expr` that refers to a binding the DSL created: a local, a parameter, a
property, a loop variable, a lambda parameter, a caught exception. Handles are what make
the DSL readable — you reference a binding by the value you got back, not by respelling
its name.

**Modifiers** — a value class wrapping an ordered set of `KModifier`, built with `+` and
written immediately before the name.

**Annotations** — a value class wrapping a list of `AnnotationSpec`, built with `+`.

## Mechanisms

**Emitting form vs pure form** — `Unit`-returning API emits into the current scope;
`Expr`-returning API does not. `` `val` ``/`` `var` `` are the single exception: they emit
*and* return a handle. Every construct also has a pure form — the same function run
against a detached scope, via `stmts { }`, `funSpec { }`, `typeSpec { }`,
`propertySpec { }`.

**Variant** — one of the six overloads every declaration construct has, distinguished by
whether it takes leading `annotations` and whether modifiers arrive as a single
`KModifier` or a `Modifiers` ([ADR 0004](adr/0004-overload-variants-and-arity-cap.md)).

**Arity family** — the generated set of overloads covering 0–8 lambda-bound parameters,
for `fun` and for `constructor`. Wider signatures use the list form.

**Shadow member** — a `@Deprecated(level = ERROR)` member on a scope class that makes an
invalid construct a compile error there. Must mirror the real overload's signature
exactly; a `vararg Any?` catch-all silently fails to shadow
([ADR 0002](adr/0002-construct-validity-per-scope.md)).

**Splice** — emitting a pure-form `Stmt` or spec into a live scope with `+`, `emit`, `add`
or `invoke`. Where handle ownership is verified.

**Uniquify** — rename a colliding binding by appending a numeral (`item` → `item2`).
`NameScope`s nest with scopes, so a local never shadows a member
([ADR 0009](adr/0009-naming-and-shadowing.md)).

**Singularize** — derive a loop variable name from the iterable's name (`items` → `item`,
`entries` → `entry`), falling back to `item`.

**Escape hatch** — `expression("…", args, prec = …)`. Preserves `%T`/`%M` so imports still
resolve; raw strings inside it bypass scope checking, which is the documented trade-off.

**Ownership check** — the runtime guard that a handle is only used where its declaring
scope encloses the current one. Catches handles smuggled out through a Kotlin `var`.

**Use-site target** — KotlinPoet's `AnnotationSpec.UseSiteTarget`, re-exported. Includes
Kotlin 2.2's `@all:` meta-target; present in KotlinPoet 2.3.0, so no shim is needed.
