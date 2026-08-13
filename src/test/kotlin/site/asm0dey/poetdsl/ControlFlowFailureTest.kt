package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the builder looks like after a construct's body threw and the caller carried on.
 *
 * An exception normally aborts the whole build and none of this matters. It starts mattering the
 * moment a generator catches the [IllegalStateException] a DSL check raised — a plausible thing to
 * do when the DSL is driven by external input — and keeps emitting into the same scope. Without
 * `closeOnFailure`, `beginControlFlow`'s brace and indent were never matched, so every later
 * statement rendered inside a block that never closed. Now the failed construct costs an empty
 * block and nothing after it is displaced.
 *
 * The assertions are on the *whole* rendered block, not on a substring: "balanced" is a claim
 * about what follows the failed construct, and only the full text shows it.
 */
class ControlFlowFailureTest {
    /** A handle from a scope that encloses nothing here — the cheapest genuine DSL failure. */
    private val foreign = Expr(CodeBlock.of("leaked"), scope = ScopeId(null, "other"))

    private val subject = expression("s")
    private val condition = expression("c")
    private val items = expression("xs")

    /**
     * Runs [failing] until it throws, then emits one ordinary statement and closes the block,
     * returning what the whole thing rendered as.
     */
    private fun afterFailure(failing: BlockScope.() -> Unit): String {
        val block = attachedBlock()
        assertFailsWith<IllegalStateException> { block.failing() }
        with(block) { +call("after") }
        block.flushPending()
        return block.builder.build().toString()
    }

    @Test
    fun `a failed if body leaves the block balanced`() {
        assertEquals("if (c) {\n}\nafter()\n", afterFailure { `if`(condition) { +foreign } })
    }

    @Test
    fun `a failed try body leaves the block balanced`() {
        assertEquals("try {\n}\nafter()\n", afterFailure { `try` { +foreign } })
    }

    /** A failure in the `when` body itself, between branches, rather than inside one. */
    @Test
    fun `a failed when body leaves the block balanced`() {
        assertEquals("when (s) {\n}\nafter()\n", afterFailure { `when`(subject) { branch { } } })
    }

    @Test
    fun `a failed when branch closes both the branch and the when`() {
        assertEquals(
            "when (s) {\n  c -> {\n  }\n}\nafter()\n",
            afterFailure { `when`(subject) { branch(condition) { +foreign } } },
        )
    }

    @Test
    fun `a failed when else closes both the branch and the when`() {
        assertEquals(
            "when {\n  else -> {\n  }\n}\nafter()\n",
            afterFailure { whenTrue { `else` { +foreign } } },
        )
    }

    @Test
    fun `a failed for body leaves the block balanced`() {
        assertEquals("for (x in xs) {\n}\nafter()\n", afterFailure { `for`(items, "x") { +foreign } })
    }

    @Test
    fun `a failed while body leaves the block balanced`() {
        assertEquals("while (c) {\n}\nafter()\n", afterFailure { `while`(condition) { +foreign } })
    }

    /** `do` is the one construct whose closer is not `endControlFlow`; its `while` must survive too. */
    @Test
    fun `a failed doWhile body keeps its trailing while`() {
        assertEquals("do {\n} while (c)\nafter()\n", afterFailure { doWhile(condition) { +foreign } })
    }

    /**
     * The failure the guard is *for*: the original exception, not something `close()` raised on the
     * way out. `closeOnFailure` rethrows rather than wrapping, so the message still names the check
     * that fired.
     */
    @Test
    fun `the original failure is what propagates`() {
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> {
            with(block) { `if`(condition) { +foreign } }
        }
        assertEquals(
            "Handle from scope 'other' does not enclose the current scope 'if'.",
            failure.message,
        )
    }

    /**
     * A failure three levels down still leaves the *outermost* builder balanced, and the half-built
     * inner blocks leak nothing into it: each nested body builds into its own [CodeBlock.Builder]
     * and is folded in only once it has run to completion, so unwinding drops them wholesale. Depth
     * therefore cannot accumulate unmatched braces.
     */
    @Test
    fun `a failure nested several levels deep discards the partial inner blocks`() {
        assertEquals(
            "for (x in xs) {\n}\nafter()\n",
            afterFailure {
                `for`(items, "x") {
                    `while`(condition) {
                        `if`(condition) { +foreign }
                    }
                }
            },
        )
    }
}
