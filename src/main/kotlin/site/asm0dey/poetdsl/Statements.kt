package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock

/**
 * Closes any control-flow block left open by a previous builder.
 *
 * A control-flow builder finishes its body but leaves the block open, so an `else`/`else if`
 * can still attach to it. Anything else appearing in the block — a statement, a spliced
 * [Stmt], the end of the enclosing block — closes it first. Clearing [BlockScope.pending]
 * before calling [PendingFlow.close] makes the flush idempotent: the second caller finds
 * nothing pending and closes nothing.
 */
internal fun BlockScope.flushPending() {
    val open = pending ?: return
    pending = null
    open.close()
}

/**
 * Rejects a scope that does not enclose this one — safety layer 2, the check that catches a
 * handle smuggled out of its block through a Kotlin `var`.
 *
 * A detached root has no parent, so every outer handle would look foreign; it records instead
 * of rejecting, and [stmts] hands the record to the splice, which is the only place ownership
 * can actually be judged (ADR 0008). Only genuinely foreign scopes are recorded — a scope this
 * fragment declared itself already encloses the use site, and reporting it would make the
 * fragment unspliceable anywhere.
 */
internal fun BlockScope.checkOwned(owner: ScopeId) {
    val encloses = owner.isAncestorOf(id)
    if (detachedRoot) {
        if (!encloses) referenced += owner
        return
    }
    check(encloses) {
        "Handle from scope '${owner.label}' does not enclose the current scope '${id.label}'."
    }
}

/** Validates every scope [expr] was built from, in the block it is about to be emitted into. */
internal fun BlockScope.checkOwned(expr: Expr) {
    expr.usedScopes.forEach { checkOwned(it) }
}

/** Closes any pending control flow, then adds [code] as one statement. */
internal fun BlockScope.emitCode(code: CodeBlock) {
    flushPending()
    builder.addStatement("%L", code)
}

/**
 * Runs [body] in a nested block and folds the result back in. The nested block inherits this
 * one's names, id chain and detachedness, so ownership keeps working through the nesting; any
 * flow it left open is closed before the fold.
 */
internal fun BlockScope.runNested(
    label: String,
    isolateReturns: Boolean = false,
    body: BlockScope.() -> Unit,
) {
    val inner = child(label, isolateReturns)
    inner.body()
    inner.flushPending()
    builder.add(inner.builder.build())
}

/** Emits [expr] as a statement. */
context(b: BlockScope)
public fun statement(expr: Expr) {
    b.checkOwned(expr)
    b.emitCode(expr.code)
}

/** Alias of [statement]. */
context(b: BlockScope)
public fun stmt(expr: Expr) {
    statement(expr)
}

/** Alias of [statement]. */
context(b: BlockScope)
public operator fun Expr.unaryPlus() {
    statement(this)
}

/**
 * Splices a pure [Stmt] into this block, validating the handles it was built from against
 * this scope first (ADR 0008). Added with `add`, not `addStatement`: the fragment already
 * carries its own line breaks.
 */
context(b: BlockScope)
public operator fun Stmt.unaryPlus() {
    usedScopes.forEach { b.checkOwned(it) }
    b.flushPending()
    b.builder.add(code)
}

/** Alias of [unaryPlus]. */
context(b: BlockScope)
public operator fun Stmt.invoke() {
    +this
}

/** Alias of [unaryPlus]. */
context(b: BlockScope)
public fun emit(stmt: Stmt) {
    +stmt
}

/** Alias of [unaryPlus]. */
context(b: BlockScope)
public fun add(stmt: Stmt) {
    +stmt
}

/**
 * The pure form: the same statement builders run against a detached scope, returning the
 * result instead of emitting it. Handles referenced inside are validated when the result
 * is spliced, which is the only point where ownership can be judged.
 *
 * Declared without a context parameter, and there is no context-aware twin: the two would be
 * ambiguous wherever a block is in scope (ADR 0001), and the spec writes `val guard: Stmt =
 * stmts { … }` at generator top level, outside any scope.
 */
public fun stmts(body: BlockScope.() -> Unit): Stmt {
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = NameScope(null),
        id = ScopeId(null, "block"),
        returns = mutableListOf(),
        detachedRoot = true,
    )
    scope.body()
    scope.flushPending()
    return Stmt(scope.builder.build(), scope.referenced.toSet())
}
