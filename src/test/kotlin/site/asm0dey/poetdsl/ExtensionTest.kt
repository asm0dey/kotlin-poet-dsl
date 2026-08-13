package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Written exactly as a library consumer would write it — no internal API, no privileged position.
// If this stops compiling, the library's central design promise is broken.
//
// The bare `call` is written qualified: inside an extension on `Expr`, the implicit receiver makes
// an unqualified `call(name, …)` resolve to `Expr.call(name, …)`, which silently renders
// `x.IllegalStateException("missing")`. See `a bare call inside an Expr extension binds to the
// receiver` below.
context(b: BlockScope)
fun Expr.orThrow(message: String) {
    `if`(this eq nul) {
        `throw`(site.asm0dey.poetdsl.call("IllegalStateException", message.lit))
    }
}

// A pure helper, for the other half of the promise: an extension that *returns* a fragment rather
// than emitting one is written against the same public API and needs no context at all.
fun guardAgainst(value: Expr, message: String): Stmt = stmts {
    `if`(value eq nul) { `throw`(call("IllegalArgumentException", message.lit)) }
}

class ExtensionTest {
    @Test
    fun `a user written scope aware extension works like a built in`() {
        assertEquals(
            "val x = find()\nif (x == null) {\n  throw IllegalStateException(\"missing\")\n}\nx\n",
            renderBlock {
                val x = `val`("x", init = call("find"))
                x.orThrow("missing")
                +x
            },
        )
    }

    @Test
    fun `a user written pure helper splices like a built in fragment`() {
        assertEquals(
            "val x = find()\nif (x == null) {\n  throw IllegalArgumentException(\"missing\")\n}\n",
            renderBlock {
                val x = `val`("x", init = call("find"))
                +guardAgainst(x, "missing")
            },
        )
    }

    /**
     * The other side of "indistinguishable from a built-in": a user-written helper inherits the
     * ownership check too, rather than being a hole in it. The handle below belongs to a sibling
     * block, so the splice rejects it exactly as it would reject a built-in's handle (ADR 0008).
     */
    @Test
    fun `a user written extension does not bypass ownership`() {
        lateinit var smuggled: Expr
        stmts { smuggled = `val`("x", init = call("find")) }
        val failure = assertFailsWith<IllegalStateException> {
            with(attachedBlock()) { smuggled.orThrow("missing") }
        }
        assertEquals(
            "Handle from scope 'block' does not enclose the current scope 'fun f'.",
            failure.message,
        )
    }

    /**
     * The one trap an extension author has to know about, pinned so it stays a documented fact
     * rather than a surprise: Kotlin resolves a call against the implicit receivers first, so
     * inside an extension on [Expr] the bare `call("f")` is `this.call("f")`, not the top-level
     * `call("f")`. Nothing the DSL can change — the two builders are deliberately named the same
     * — so the fix is `member(…)`, an explicit `this`-free receiver, or a fully qualified name.
     */
    @Test
    fun `a bare call inside an Expr extension binds to the receiver`() {
        assertEquals("x.f()", expression("x").bareCall().toString())
    }
}

private fun Expr.bareCall(): Expr = call("f")
