package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

// --- for ---------------------------------------------------------------------------------------

/**
 * `for (name in items) { … }`.
 *
 * The loop variable is a fresh handle scoped to the loop body: it cannot be used once the loop
 * ends (ADR 0008), which is why it is bound in a child scope rather than [b] itself.
 *
 * @param name the loop variable's rendered name (ADR 0005). Defaults to the singular of the
 *   iterable handle's name (`items` → `item`), falling back to `item` when the handle has none.
 *   Uniquified against the loop body's own child scope, not [b] itself — matching [lambdaOf]'s
 *   treatment of a lambda parameter — so the name is free again once the loop closes. Because
 *   [NameScope.child] chains to its parent, this still never collides with an enclosing name.
 */
context(b: BlockScope)
public fun `for`(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) {
    b.checkOwned(items)
    b.flushPending()
    val inner = b.child("for")
    val chosen = inner.names.unique(name ?: items.name?.let(::singularize) ?: "item")
    b.builder.beginControlFlow("for·(%L·in·%L)", chosen, items.code)
    inner.body(Expr(CodeBlock.of("%L", chosen), name = chosen, scope = inner.id))
    inner.flushPending()
    b.builder.add(inner.builder.build())
    b.builder.endControlFlow()
}

/** Alias of [`for`]. */
context(b: BlockScope)
public fun forIn(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) {
    `for`(items, name, body)
}

// --- while / doWhile -----------------------------------------------------------------------------

/** `while (condition) { … }`. */
context(b: BlockScope)
public fun `while`(condition: Expr, body: BlockScope.() -> Unit) {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("while·(%L)", condition.code)
    b.runNested("while", body = body)
    b.builder.endControlFlow()
}

/**
 * `do { … } while (condition)`.
 *
 * KotlinPoet 2.3.0's [CodeBlock.Builder.endControlFlow] takes no format arguments, so the
 * trailing `while (condition)` cannot be attached through it. `unindent()` plus a literal
 * `}·while·(…)` line produces the identical brace and indentation as [CodeBlock.Builder.endControlFlow]
 * would, without that missing overload.
 */
context(b: BlockScope)
public fun doWhile(condition: Expr, body: BlockScope.() -> Unit) {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("do")
    b.runNested("doWhile", body = body)
    b.builder.unindent()
    b.builder.add("}·while·(%L)\n", condition.code)
}

// --- break / continue ------------------------------------------------------------------------

/** `break`. Does not check that it is inside a loop; an out-of-place `break` is left for `kotlinc` to reject. */
context(b: BlockScope)
public fun `break`() {
    b.emitCode(CodeBlock.of("break"))
}

/** Alias of [`break`]. */
context(b: BlockScope)
public fun brk() {
    `break`()
}

/** `continue`. Does not check that it is inside a loop; an out-of-place `continue` is left for `kotlinc` to reject. */
context(b: BlockScope)
public fun `continue`() {
    b.emitCode(CodeBlock.of("continue"))
}

/** Alias of [`continue`]. */
context(b: BlockScope)
public fun cont() {
    `continue`()
}

// --- throw -------------------------------------------------------------------------------------

/** `throw value`. */
context(b: BlockScope)
public fun `throw`(value: Expr) {
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("throw·%L", value.code))
}

/** Alias of [`throw`]. */
context(b: BlockScope)
public fun throwIt(value: Expr) {
    `throw`(value)
}

// --- return --------------------------------------------------------------------------------------

/**
 * `return value`.
 *
 * Records `value.type` into [BlockScope.returns] (ADR 0007), so a function whose body infers its
 * return type from its `return` statements sees this one. `stmts { }` shares one `returns` list
 * across its whole tree (unless a lambda body isolates it via `child(isolateReturns = true)`), but
 * the pure form itself does not read that list back out anywhere yet — a `return` recorded inside a
 * `stmts { }` fragment does not currently reach the function it is later spliced into. That gap is
 * inherent to `stmts` returning only a `Stmt` (code plus used scopes, no `returns`) and is left for
 * Task 19, which wires `fun` bodies to inferred return types.
 */
context(b: BlockScope)
public fun `return`(value: Expr) {
    b.checkOwned(value)
    b.returns += value.type
    b.emitCode(CodeBlock.of("return·%L", value.code))
}

/** `return`, with no value. */
context(b: BlockScope)
public fun `return`() {
    b.emitCode(CodeBlock.of("return"))
}

/** Alias of the value-returning [`return`]. */
context(b: BlockScope)
public fun ret(value: Expr) {
    `return`(value)
}

/** Alias of the no-value [`return`]. */
context(b: BlockScope)
public fun ret() {
    `return`()
}

// --- if / elseIf / else ---------------------------------------------------------------------

/**
 * An `if` whose block is still open. Emitting anything else in the enclosing scope, or
 * closing that scope, flushes the chain. A chain that is still open cannot render unbalanced
 * braces; once closed — by a flush or by [close] — every entry point rejects it instead of
 * reattaching its branches to whatever `if` now happens to be open.
 */
public class IfChain internal constructor(private val owner: BlockScope) : PendingFlow {
    /**
     * True once this chain has been closed, by a flush elsewhere in [owner] or by a direct
     * [close] call. A closed handle held past that point is stale: [owner]'s builder has moved
     * on (possibly into a different, unrelated `if`), so blindly calling `nextControlFlow` or
     * `endControlFlow` on it again would either throw KotlinPoet's opaque unindent error or —
     * worse, the common case at nesting depth > 0 — silently reattach the branch to whatever
     * control flow happens to be open at the time, with no exception at all.
     */
    private var closed: Boolean = false

    /** `else if (condition) { … }`. */
    public fun elseIf(condition: Expr, body: BlockScope.() -> Unit): IfChain {
        check(!closed) { "elseIf: this if/elseIf/else chain is already closed and cannot take another branch." }
        owner.checkOwned(condition)
        owner.builder.nextControlFlow("else·if·(%L)", condition.code)
        owner.runNested("elseIf", body = body)
        return this
    }

    /** `else { … }`. */
    public fun `else`(body: BlockScope.() -> Unit) {
        check(!closed) { "else: this if/elseIf/else chain is already closed and cannot take another branch." }
        owner.builder.nextControlFlow("else")
        owner.runNested("else", body = body)
    }

    override fun close() {
        check(!closed) { "close: this if/elseIf/else chain is already closed." }
        closed = true
        // A normal flush (BlockScope.flushPending) already nulled owner.pending before calling
        // here, so this is a no-op on that path. It only fires when close() is called directly,
        // bypassing the flush — clearing owner.pending here keeps it from staying stale and
        // triggering a second, misattributed close() the next time this scope flushes.
        if (owner.pending === this) owner.pending = null
        owner.builder.endControlFlow()
    }
}

/** `if (condition) { … }`, chainable with [IfChain.elseIf] and [IfChain.`else`]. */
context(b: BlockScope)
public fun `if`(condition: Expr, body: BlockScope.() -> Unit): IfChain {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("if·(%L)", condition.code)
    b.runNested("if", body = body)
    return IfChain(b).also { b.pending = it }
}

/** Alias of [`if`]. */
context(b: BlockScope)
public fun ifThen(condition: Expr, body: BlockScope.() -> Unit): IfChain = `if`(condition, body)

// --- when ------------------------------------------------------------------------------------

/**
 * The inside of a `when`. Only branches may be declared here.
 *
 * Unlike [IfChain], a `when`'s flow opens and closes entirely inside the single [`when`]/
 * [whenTrue] call that creates this scope: [branch] and [`else`] each call
 * `beginControlFlow`/`endControlFlow` on [owner]'s builder as a matched pair before returning,
 * and nothing outside a `branch`/`else` call can run in between — the body lambda's receiver is
 * `WhenScope`, not [BlockScope], so it cannot reach any of [owner]'s own statement builders.
 * That means this scope is never assigned to [BlockScope.pending] and no flush ever closes it
 * early; the *only* way to reach a stale instance is to capture `this` out of the body lambda
 * into an outer variable and call [branch] or [`else`] on it after the owning [`when`]/[whenTrue]
 * call has already returned — the same handle escape [IfChain] guards against. [closed] catches
 * exactly that: it flips once the owning call's body has run, and both branch-continuation
 * methods check it first, naming this construct, before touching [owner]'s builder. Because this
 * scope is never [BlockScope.pending], the identity check `owner.pending === this` that closes
 * [IfChain] would be a no-op here — there is nothing to desynchronize from — so the per-instance
 * flag alone is both necessary and sufficient.
 */
@BlockDsl
public class WhenScope internal constructor(internal val owner: BlockScope) {
    /**
     * True once the [`when`]/[whenTrue] call that created this scope has run its body to
     * completion. See the class doc for why a flag suffices without an `owner.pending` check.
     */
    internal var closed: Boolean = false

    /** One branch. Several conditions are comma-joined, as in Kotlin. */
    public fun branch(vararg conditions: Expr, body: BlockScope.() -> Unit) {
        check(!closed) { "branch: this when has already closed and cannot take another branch." }
        check(conditions.isNotEmpty()) {
            "A when branch needs at least one condition; use `else` for the fallback."
        }
        conditions.forEach(owner::checkOwned)
        val heads = conditions.map { it.code }.reduce { acc, code -> CodeBlock.of("%L,·%L", acc, code) }
        owner.builder.beginControlFlow("%L·->", heads)
        owner.runNested("branch", body = body)
        owner.builder.endControlFlow()
    }

    /** The `else ->` branch. */
    public fun `else`(body: BlockScope.() -> Unit) {
        check(!closed) { "else: this when has already closed and cannot take another branch." }
        owner.builder.beginControlFlow("else·->")
        owner.runNested("else", body = body)
        owner.builder.endControlFlow()
    }
}

/** `when (subject) { … }`. */
context(b: BlockScope)
public fun `when`(subject: Expr, body: WhenScope.() -> Unit) {
    b.checkOwned(subject)
    b.flushPending()
    b.builder.beginControlFlow("when·(%L)", subject.code)
    val scope = WhenScope(b)
    scope.body()
    scope.closed = true
    b.builder.endControlFlow()
}

/** Alias of [`when`]. */
context(b: BlockScope)
public fun whenOn(subject: Expr, body: WhenScope.() -> Unit) {
    `when`(subject, body)
}

/** Subjectless `when { … }`, where each branch condition is a boolean expression. */
context(b: BlockScope)
public fun whenTrue(body: WhenScope.() -> Unit) {
    b.flushPending()
    b.builder.beginControlFlow("when")
    val scope = WhenScope(b)
    scope.body()
    scope.closed = true
    b.builder.endControlFlow()
}

// --- try / catch / finally ----------------------------------------------------------------------

/**
 * A `try` whose block is still open. Emitting anything else in the enclosing scope, or closing
 * that scope, flushes the chain. Chainable with [catch] and [finally], the same shape as
 * [IfChain] — and, with this construct, [BlockScope.pending] gets its *second* implementor.
 *
 * That is new: until now [IfChain] alone occupied [BlockScope.pending], so the identity check
 * `owner.pending === this` and a per-instance [closed] flag were provably interchangeable (see
 * [IfChain]'s doc). With two implementors that interchangeability still holds for [catch] and
 * [finally] — at the moment either runs on a *live* chain, [owner]'s `pending` is either still
 * this exact instance or has already moved on to something else entirely (a different `TryChain`,
 * an `IfChain`, or null), so the two checks agree. But it does **not** hold for [close] itself:
 * [BlockScope.flushPending] nulls `pending` *before* calling `close()`, so on the normal flush
 * path `owner.pending === this` is already false the instant `close()` runs — an identity check
 * there would reject every legitimate flush. [close] needs its own [closed] flag regardless of
 * how many implementors [PendingFlow] has; [IfChain] already made that choice for the same
 * reason, and this class keeps it for both entry points that can be called directly, out of
 * order, on a chain some *other* flush already closed. Every entry point below checks [closed]
 * before touching [owner]'s builder or any shared state, so a stale `TryChain` — whether closed by
 * its own [close], by a flush that moved on to an unrelated statement, or by a flush that opened a
 * competing `IfChain` on the same owner — is rejected outright instead of reattaching its clause
 * to whatever control flow now happens to be open.
 */
public class TryChain internal constructor(private val owner: BlockScope) : PendingFlow {
    /** See the class doc: mirrors [IfChain.closed], independently re-derived for this class. */
    private var closed: Boolean = false

    /**
     * Names reserved by this chain's own `catch` parameters, shared across every [catch] call on
     * this instance so a second `e` uniquifies to `e2` (they are siblings of *this* `try`, not of
     * unrelated ones) — but a child of [owner]'s own names, never [owner.names] itself, so nothing
     * declared here is visible once this chain is discarded. That is the same "frees when the
     * body ends" shape as [`for`]'s loop variable and [lambdaOf]'s parameters, just shared across
     * more than one body because Kotlin's own catch clauses are siblings of each other.
     */
    private val paramNames: NameScope = owner.names.child()

    /** `catch (name: type) { … }`. The handle is passed to the body. */
    public fun `catch`(name: String, type: TypeName, body: BlockScope.(Expr) -> Unit): TryChain {
        check(!closed) { "catch: this try/catch/finally chain is already closed and cannot take another clause." }
        val unique = paramNames.unique(name)
        owner.builder.nextControlFlow("catch·(%L:·%T)", unique, type)
        val inner = BlockScope(
            builder = CodeBlock.builder(),
            names = paramNames.child(),
            id = owner.id.child("catch"),
            returns = owner.returns,
            detachedRoot = owner.detachedRoot,
            referenced = owner.referenced,
            captured = owner.captured,
        )
        // A catch parameter is a `val` in Kotlin — kotlinc rejects `catch (var e: T)` outright —
        // so unlike a derived expression this mutability is genuinely known, not merely unset;
        // `mutable = false` lets `assign` reject a reassignment with a domain error instead of
        // deferring to kotlinc (D22).
        inner.body(
            Expr(
                CodeBlock.of("%L", unique),
                type = type,
                prec = Prec.ATOM,
                name = unique,
                scope = inner.id,
                mutable = false,
            ),
        )
        inner.flushPending()
        owner.builder.add(inner.builder.build())
        return this
    }

    /** `finally { … }`. */
    public fun finally(body: BlockScope.() -> Unit) {
        check(!closed) { "finally: this try/catch/finally chain is already closed and cannot take another clause." }
        owner.builder.nextControlFlow("finally")
        owner.runNested("finally", body = body)
    }

    override fun close() {
        check(!closed) { "close: this try/catch/finally chain is already closed." }
        closed = true
        // Mirrors IfChain.close(): a normal flush already nulled owner.pending before calling
        // here, so this is a no-op on that path. It only fires when close() is called directly,
        // keeping owner.pending from staying stale and triggering a second, misattributed close()
        // the next time this scope flushes.
        if (owner.pending === this) owner.pending = null
        owner.builder.endControlFlow()
    }
}

/** `try { … }`, chainable with [TryChain.catch] and [TryChain.finally]. */
context(b: BlockScope)
public fun `try`(body: BlockScope.() -> Unit): TryChain {
    b.flushPending()
    b.builder.beginControlFlow("try")
    b.runNested("try", body = body)
    return TryChain(b).also { b.pending = it }
}

/** Alias of [`try`]. */
context(b: BlockScope)
public fun tryCatch(body: BlockScope.() -> Unit): TryChain = `try`(body)
