package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

/**
 * Wraps a rendered body in `{ … }`, with `p1, p2 -> ` when there are parameters to declare.
 *
 * The body is added, never `addStatement`ed: a lambda is a value and may be emitted inside a
 * statement, so it must not carry statement markers of its own — see [emitCode].
 */
internal fun lambdaCode(params: List<String>, body: CodeBlock): CodeBlock =
    CodeBlock.builder()
        .add("{")
        .apply { if (params.isNotEmpty()) add("·%L·->", params.joinToString(",·")) }
        .add("\n")
        .indent()
        .add(body)
        .unindent()
        .add("}")
        .build()

/**
 * The block a lambda body is built in.
 *
 * Declared once on [Scope] and dispatching on the runtime type (ADR 0001, D17): the `when` is
 * exhaustive over the sealed hierarchy with no `else`, so a fourth scope breaks the build here
 * instead of silently falling through.
 *
 * Inside a block the body is an ordinary child: it shares the enclosing [BlockScope.referenced]
 * set, so a foreign handle used inside the lambda still reaches the `stmts` root that reports it
 * for the splice (ADR 0008). Outside a block — a lambda at property-initializer or
 * property-delegate position, where only a [FileScope] or [TypeScope] is in scope — there is no
 * enclosing block to inherit from, so the body is its own detached root and [lambdaOf] hands the
 * scopes it recorded to the [Expr] it returns, which carries them to wherever that lambda is
 * finally spliced.
 *
 * Names chain in every case, so a lambda parameter that would shadow an enclosing binding is
 * uniquified rather than shadowing it (ADR 0009). Returns are isolated in every case: a `return`
 * inside a lambda is a non-local return and must not drive the enclosing function's inferred
 * return type (ADR 0007).
 */
private fun Scope.lambdaBlock(): BlockScope = when (this) {
    is BlockScope -> child("lambda", isolateReturns = true)
    is FileScope, is TypeScope -> BlockScope(
        builder = CodeBlock.builder(),
        names = names.child(),
        id = id.child("lambda"),
        returns = mutableListOf(),
        detachedRoot = true,
    )
}

/**
 * Builds a lambda body in a nested scope and returns it as a value, emitting nothing.
 *
 * @param requested one entry per parameter. `null` renders the handle as `it` and emits no
 *   parameter list; a name is uniquified against the enclosing scope and emitted. The rendered
 *   name never comes from the name the caller binds in their own Kotlin lambda (ADR 0005).
 */
internal fun Scope.lambdaOf(requested: List<String?>, body: BlockScope.(List<Expr>) -> Unit): Expr {
    check(requested.size <= 1 || requested.all { it != null }) {
        "lambda: only a single parameter can be left unnamed and render as `it`; " +
            "name every parameter of a multi-parameter lambda."
    }
    val scope = lambdaBlock()
    val rendered = requested.map { it?.let(scope.names::unique) }
    val handles = rendered.map { name ->
        Expr(CodeBlock.of("%L", name ?: "it"), name = name, scope = scope.id)
    }
    scope.body(handles)
    scope.flushPending()
    return Expr(
        code = lambdaCode(rendered.filterNotNull(), scope.builder.build()),
        prec = Prec.ATOM,
        // A block-level body shares its `referenced` set with the enclosing chain, which already
        // reports it; a detached one is the only owner of what it recorded.
        usedScopes = if (this is BlockScope) emptySet() else scope.referenced.toSet(),
    )
}

/** Rejects a `params` list whose size does not match the arity the body binds. */
private fun List<String?>.arity(n: Int): List<String?> {
    check(size == n) {
        "lambda: params names $size parameter${if (size == 1) "" else "s"} but the body binds $n."
    }
    return this
}

/**
 * `receiver.name(args) { … }`. The argument list is omitted when there is none, so the lambda is
 * the only argument — `items.map { … }`, not `items.map() { … }`.
 */
private fun Expr.callLambda(name: String, args: Array<out Expr>, lam: Expr): Expr = Expr(
    code = if (args.isEmpty()) {
        CodeBlock.of("%L.%L·%L", paren(Prec.POSTFIX), name, lam.code)
    } else {
        CodeBlock.of("%L.%L(%L)·%L", paren(Prec.POSTFIX), name, argList(args), lam.code)
    },
    prec = Prec.POSTFIX,
    usedScopes = usedScopes + scopesOf(args) + lam.usedScopes,
)

/** `member(args) { … }`, with `%M` so the import resolves. */
private fun memberLambda(member: MemberName, args: Array<out Expr>, lam: Expr): Expr = Expr(
    code = if (args.isEmpty()) {
        CodeBlock.of("%M·%L", member, lam.code)
    } else {
        CodeBlock.of("%M(%L)·%L", member, argList(args), lam.code)
    },
    prec = Prec.POSTFIX,
    usedScopes = scopesOf(args) + lam.usedScopes,
)

/** `receiver(args) { … }` — calling a value that holds a lambda or a function-typed parameter. */
private fun Expr.invokeLambda(args: Array<out Expr>, lam: Expr): Expr = Expr(
    code = if (args.isEmpty()) {
        CodeBlock.of("%L·%L", paren(Prec.POSTFIX), lam.code)
    } else {
        CodeBlock.of("%L(%L)·%L", paren(Prec.POSTFIX), argList(args), lam.code)
    },
    prec = Prec.POSTFIX,
    usedScopes = usedScopes + scopesOf(args) + lam.usedScopes,
)

// --- lambda ---------------------------------------------------------------------------------
//
// A standalone `{ … }` value. Declared on `Scope`, not on `BlockScope`: a lambda is just as valid
// at property-initializer and property-delegate position, where no block is in scope (D3). Use
// `detachedLambda` outside every scope.
//
// `param` is *required* on the arity-1 form, unlike on `call`: `lambda { … }` would otherwise be
// ambiguous between the arity-0 and arity-1 shapes, since a lambda with no declared parameter is
// applicable to both (measured on Kotlin 2.4.10). Write `lambda(null) { p -> … }` for a standalone
// lambda whose single parameter renders as the implicit `it`.

/** `{ … }` — no parameters. */
context(s: Scope)
public fun lambda(body: BlockScope.() -> Unit): Expr = s.lambdaOf(emptyList()) { body() }

/** `{ param -> … }`, or `{ … }` with the handle rendering as `it` when [param] is null. */
context(s: Scope)
public fun lambda(param: String?, body: BlockScope.(Expr) -> Unit): Expr =
    s.lambdaOf(listOf(param)) { h -> body(h[0]) }

/** `{ p1, p2 -> … }`. */
context(s: Scope)
public fun lambda(params: List<String?>, body: BlockScope.(Expr, Expr) -> Unit): Expr =
    s.lambdaOf(params.arity(2)) { h -> body(h[0], h[1]) }

/** `{ p1, p2, p3 -> … }`. */
context(s: Scope)
public fun lambda(params: List<String?>, body: BlockScope.(Expr, Expr, Expr) -> Unit): Expr =
    s.lambdaOf(params.arity(3)) { h -> body(h[0], h[1], h[2]) }

/** `{ p1, …, p4 -> … }`. */
context(s: Scope)
public fun lambda(params: List<String?>, body: BlockScope.(Expr, Expr, Expr, Expr) -> Unit): Expr =
    s.lambdaOf(params.arity(4)) { h -> body(h[0], h[1], h[2], h[3]) }

/** `{ p1, …, p5 -> … }`. */
context(s: Scope)
public fun lambda(params: List<String?>, body: BlockScope.(Expr, Expr, Expr, Expr, Expr) -> Unit): Expr =
    s.lambdaOf(params.arity(5)) { h -> body(h[0], h[1], h[2], h[3], h[4]) }

/** `{ p1, …, p6 -> … }`. */
context(s: Scope)
public fun lambda(
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = s.lambdaOf(params.arity(6)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5]) }

/** `{ p1, …, p7 -> … }`. */
context(s: Scope)
public fun lambda(
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = s.lambdaOf(params.arity(7)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6]) }

/** `{ p1, …, p8 -> … }` — the widest lambda-bound arity; use the [List] form beyond it. */
context(s: Scope)
public fun lambda(
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = s.lambdaOf(params.arity(8)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]) }

/** `{ p1, …, pn -> … }` for any arity: the handles arrive as a list, in `params` order. */
context(s: Scope)
public fun lambda(params: List<String?>, body: BlockScope.(List<Expr>) -> Unit): Expr =
    s.lambdaOf(params, body)

/**
 * A standalone `{ … }` value built outside every scope, for a fragment assembled up front.
 *
 * The brief's `lambda(body, detached = true)` spelling is gone: the flag was never read, and the
 * overload was ambiguous with the context-parameter [lambda] (D4). Like [stmts], the body is a
 * detached root, so handles from elsewhere are recorded rather than rejected and travel with the
 * returned [Expr] to be judged where it is spliced (ADR 0008).
 */
public fun detachedLambda(body: BlockScope.() -> Unit): Expr {
    val root = BlockScope(
        builder = CodeBlock.builder(),
        names = NameScope(null),
        id = ScopeId(null, "lambda"),
        returns = mutableListOf(),
        detachedRoot = true,
    )
    val lam = root.lambdaOf(emptyList()) { body() }
    return Expr(lam.code, prec = Prec.ATOM, usedScopes = root.referenced.toSet())
}

// --- receiver.name(args) { … } ----------------------------------------------------------------
//
// There is no zero-parameter overload here, and none on `invoke` or the `MemberName` form: a
// lambda with no declared parameter is applicable to both a zero- and a one-parameter function
// type, so `items.map { … }` would be an overload-resolution ambiguity (measured on Kotlin
// 2.4.10; the brief anticipates this). None is needed — a body that declares no parameter uses
// the arity-1 form, whose `param` defaults to null and therefore emits no parameter list either.

/** `receiver.name(args) { param -> … }`; the handle renders as `it` when [param] is null. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    param: String? = null,
    body: BlockScope.(Expr) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(listOf(param)) { h -> body(h[0]) })

/** `receiver.name(args) { p1, p2 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(params.arity(2)) { h -> body(h[0], h[1]) })

/** `receiver.name(args) { p1, p2, p3 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(params.arity(3)) { h -> body(h[0], h[1], h[2]) })

/** `receiver.name(args) { p1, …, p4 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(params.arity(4)) { h -> body(h[0], h[1], h[2], h[3]) })

/** `receiver.name(args) { p1, …, p5 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(params.arity(5)) { h -> body(h[0], h[1], h[2], h[3], h[4]) })

/** `receiver.name(args) { p1, …, p6 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = callLambda(
    name,
    args,
    s.lambdaOf(params.arity(6)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5]) },
)

/** `receiver.name(args) { p1, …, p7 -> … }`. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = callLambda(
    name,
    args,
    s.lambdaOf(params.arity(7)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6]) },
)

/** `receiver.name(args) { p1, …, p8 -> … }` — use the [List] form beyond arity 8. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = callLambda(
    name,
    args,
    s.lambdaOf(params.arity(8)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]) },
)

/** `receiver.name(args) { p1, …, pn -> … }` for any arity; the handles arrive in `params` order. */
context(s: Scope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(List<Expr>) -> Unit,
): Expr = callLambda(name, args, s.lambdaOf(params, body))

// --- member(args) { … } -----------------------------------------------------------------------

/** `member(args) { param -> … }`; the handle renders as `it` when [param] is null. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    param: String? = null,
    body: BlockScope.(Expr) -> Unit,
): Expr = memberLambda(member, args, s.lambdaOf(listOf(param)) { h -> body(h[0]) })

/** `member(args) { p1, p2 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr) -> Unit,
): Expr = memberLambda(member, args, s.lambdaOf(params.arity(2)) { h -> body(h[0], h[1]) })

/** `member(args) { p1, p2, p3 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(member, args, s.lambdaOf(params.arity(3)) { h -> body(h[0], h[1], h[2]) })

/** `member(args) { p1, …, p4 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(member, args, s.lambdaOf(params.arity(4)) { h -> body(h[0], h[1], h[2], h[3]) })

/** `member(args) { p1, …, p5 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(
    member,
    args,
    s.lambdaOf(params.arity(5)) { h -> body(h[0], h[1], h[2], h[3], h[4]) },
)

/** `member(args) { p1, …, p6 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(
    member,
    args,
    s.lambdaOf(params.arity(6)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5]) },
)

/** `member(args) { p1, …, p7 -> … }`. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(
    member,
    args,
    s.lambdaOf(params.arity(7)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6]) },
)

/** `member(args) { p1, …, p8 -> … }` — use the [List] form beyond arity 8. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = memberLambda(
    member,
    args,
    s.lambdaOf(params.arity(8)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]) },
)

/** `member(args) { p1, …, pn -> … }` for any arity; the handles arrive in `params` order. */
context(s: Scope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(List<Expr>) -> Unit,
): Expr = memberLambda(member, args, s.lambdaOf(params, body))

// --- receiver(args) { … } ---------------------------------------------------------------------

/** `receiver(args) { param -> … }`; the handle renders as `it` when [param] is null. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    param: String? = null,
    body: BlockScope.(Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(listOf(param)) { h -> body(h[0]) })

/** `receiver(args) { p1, p2 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params.arity(2)) { h -> body(h[0], h[1]) })

/** `receiver(args) { p1, p2, p3 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params.arity(3)) { h -> body(h[0], h[1], h[2]) })

/** `receiver(args) { p1, …, p4 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params.arity(4)) { h -> body(h[0], h[1], h[2], h[3]) })

/** `receiver(args) { p1, …, p5 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params.arity(5)) { h -> body(h[0], h[1], h[2], h[3], h[4]) })

/** `receiver(args) { p1, …, p6 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params.arity(6)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5]) })

/** `receiver(args) { p1, …, p7 -> … }`. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(
    args,
    s.lambdaOf(params.arity(7)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6]) },
)

/** `receiver(args) { p1, …, p8 -> … }` — use the [List] form beyond arity 8. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(Expr, Expr, Expr, Expr, Expr, Expr, Expr, Expr) -> Unit,
): Expr = invokeLambda(
    args,
    s.lambdaOf(params.arity(8)) { h -> body(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]) },
)

/** `receiver(args) { p1, …, pn -> … }` for any arity; the handles arrive in `params` order. */
context(s: Scope)
public operator fun Expr.invoke(
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(List<Expr>) -> Unit,
): Expr = invokeLambda(args, s.lambdaOf(params, body))
