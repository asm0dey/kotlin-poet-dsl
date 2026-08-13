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
 *   Always uniquified against the enclosing scope.
 */
context(b: BlockScope)
public fun `for`(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) {
    b.checkOwned(items)
    val chosen = b.names.unique(name ?: items.name?.let(::singularize) ?: "item")
    b.flushPending()
    b.builder.beginControlFlow("for·(%L·in·%L)", chosen, items.code)
    val inner = b.child("for")
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

/** `break`. */
context(b: BlockScope)
public fun `break`() {
    b.emitCode(CodeBlock.of("break"))
}

/** Alias of [`break`]. */
context(b: BlockScope)
public fun brk() {
    `break`()
}

/** `continue`. */
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
