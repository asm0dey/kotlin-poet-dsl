package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock

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
 * closing that scope, flushes the chain. An unbalanced chain cannot be expressed.
 */
public class IfChain internal constructor(private val owner: BlockScope) : PendingFlow {
    /** `else if (condition) { … }`. */
    public fun elseIf(condition: Expr, body: BlockScope.() -> Unit): IfChain {
        owner.checkOwned(condition)
        owner.builder.nextControlFlow("else·if·(%L)", condition.code)
        owner.runNested("elseIf", body = body)
        return this
    }

    /** `else { … }`. */
    public fun `else`(body: BlockScope.() -> Unit) {
        owner.builder.nextControlFlow("else")
        owner.runNested("else", body = body)
    }

    override fun close() {
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
