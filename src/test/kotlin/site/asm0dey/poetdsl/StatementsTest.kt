package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementsTest {
    @Test
    fun `unary plus emits a statement`() {
        assertEquals("compute()\n", renderBlock { +call("compute") })
    }

    @Test
    fun `statement and stmt are the same construct`() {
        assertEquals(renderBlock { statement(expression("a")) }, renderBlock { stmt(expression("a")) })
    }

    @Test
    fun `statements emit in order`() {
        assertEquals("first()\nsecond()\n", renderBlock { +call("first"); +call("second") })
    }

    @Test
    fun `a multi-line statement keeps its own indentation`() {
        // Pins `emitCode`'s `add`: `addStatement` would indent the continuation lines two extra
        // levels, rendering this as "f({\n      g()\n    })" and doubling the indentation
        // `lambdaCode` already applies to every lambda body.
        val multi = CodeBlock.builder().add("f({\n").indent().add("g()\n").unindent().add("})").build()
        assertEquals("f({\n  g()\n})\n", renderBlock { +Expr(multi) })
    }

    @Test
    fun `a pure Stmt can be spliced into another block`() {
        val guard: Stmt = stmts { +call("check") }
        assertEquals("check()\n", renderBlock { +guard })
    }

    @Test
    fun `emit add and invoke are equivalent spellings`() {
        val s = stmts { +call("x") }
        assertEquals(renderBlock { +s }, renderBlock { emit(s) })
        assertEquals(renderBlock { +s }, renderBlock { add(s) })
        assertEquals(renderBlock { +s }, renderBlock { s() })
    }

    // --- splice-time ownership (ADR 0008) -------------------------------------------------

    @Test
    fun `a handle from an unrelated scope is rejected at the splice`() {
        val foreign = ScopeId(null, "fun(other)")
        val smuggled = Stmt(CodeBlock.of("leaked\n"), setOf(foreign))
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> { with(block) { +smuggled } }
        assertEquals(
            "Handle from scope 'fun(other)' does not enclose the current scope 'fun f'.",
            failure.message,
        )
        assertEquals("", block.builder.build().toString(), "a rejected splice emits nothing")
    }

    @Test
    fun `an expression carrying a foreign scope is rejected at the splice`() {
        val foreign = ScopeId(null, "fun(other)")
        val smuggled = Expr(CodeBlock.of("leaked"), name = "leaked", scope = foreign)
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> { with(block) { +smuggled.call("use") } }
        assertEquals(
            "Handle from scope 'fun(other)' does not enclose the current scope 'fun f'.",
            failure.message,
        )
        assertEquals("", block.builder.build().toString(), "a rejected statement emits nothing")
    }

    @Test
    fun `a foreign handle is rejected inside a nested block too`() {
        val foreign = ScopeId(null, "fun(other)")
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> {
            block.runNested("if") { +Expr(CodeBlock.of("leaked"), scope = foreign) }
        }
        assertEquals(
            "Handle from scope 'fun(other)' does not enclose the current scope 'if'.",
            failure.message,
        )
    }

    @Test
    fun `a detached root accepts a foreign handle and reports it for the splice`() {
        val foreign = ScopeId(null, "fun(other)")
        val fragment = stmts { +Expr(CodeBlock.of("leaked"), scope = foreign).call("use") }
        assertEquals("leaked.use()\n", fragment.code.toString())
        assertEquals(setOf(foreign), fragment.usedScopes, "the detached root records rather than rejects")
        assertFailsWith<IllegalStateException> { with(attachedBlock()) { +fragment } }
    }

    @Test
    fun `a detached root reports foreign scopes used inside its nested blocks`() {
        val foreign = ScopeId(null, "fun(other)")
        val fragment = stmts { runNested("if") { +Expr(CodeBlock.of("leaked"), scope = foreign) } }
        assertEquals(setOf(foreign), fragment.usedScopes, "nesting must not swallow the record")
    }

    @Test
    fun `a pure form may reference handles from the block it is spliced into`() {
        // Task 12's `val` is the intended spelling of `x`; until it lands the handle is built
        // directly, which exercises the identical path — an Expr carrying the outer scope's id.
        val block = attachedBlock()
        val x = Expr(CodeBlock.of("x"), name = "x", scope = block.id)
        val fragment = stmts { +x.call("inc") }
        assertEquals(setOf(block.id), fragment.usedScopes)
        with(block) { +fragment }
        assertEquals("x.inc()\n", block.builder.build().toString())
    }

    @Test
    fun `a handle from an enclosing scope is accepted in a nested block`() {
        val block = attachedBlock()
        val x = Expr(CodeBlock.of("x"), name = "x", scope = block.id)
        block.runNested("if") { +x.call("inc") }
        assertEquals("x.inc()\n", block.builder.build().toString())
    }

    @Test
    fun `a pure form's own locals are not reported as foreign`() {
        val fragment = stmts {
            val local = Expr(CodeBlock.of("t"), name = "t", scope = id)
            +local.call("inc")
        }
        assertTrue(fragment.usedScopes.isEmpty(), "a scope inside the fragment is not foreign to it")
        val block = attachedBlock()
        with(block) { +fragment }
        assertEquals("t.inc()\n", block.builder.build().toString())
    }

    @Test
    fun `a handle escaping a nested block inside a pure form is still caught at the splice`() {
        var escaped: Expr? = null
        val fragment = stmts {
            runNested("if") { escaped = Expr(CodeBlock.of("t"), name = "t", scope = id) }
            +escaped!!.call("use")
        }
        assertEquals(1, fragment.usedScopes.size, "the inner scope no longer encloses the use site")
        assertFailsWith<IllegalStateException> { with(attachedBlock()) { +fragment } }
    }

    @Test
    fun `a nested fragment's foreign scopes propagate to the outer fragment`() {
        val foreign = ScopeId(null, "fun(other)")
        val inner = stmts { +Expr(CodeBlock.of("leaked"), scope = foreign).call("use") }
        val outer = stmts { +inner }
        assertEquals(setOf(foreign), outer.usedScopes, "the outer fragment inherits the inner's foreign scopes")
    }

    @Test
    fun `placeholders survive emission and splicing, so imports still resolve`() {
        val fragment = stmts { +call(MemberName("com.example", "helper")) }
        val spliced = stmts { +fragment }
        val file = FileSpec.builder("demo", "Demo")
            .addFunction(FunSpec.builder("f").addCode(spliced.code).build())
            .build()
        assertEquals(
            """
            package demo

            import com.example.helper

            public fun f() {
              helper()
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    // --- pending control flow --------------------------------------------------------------

    @Test
    fun `emitting a statement closes a pending control flow first`() {
        val block = attachedBlock()
        val flow = block.openFlow("if (ready)") { +call("inside") }
        assertEquals(0, flow.closes, "building the flow body must not close it")
        with(block) {
            +call("after")
            +call("later")
        }
        assertEquals(1, flow.closes, "the flow closes exactly once")
        assertNull(block.pending)
        assertEquals(
            "if (ready) {\n  inside()\n}\nafter()\nlater()\n",
            block.builder.build().toString(),
        )
    }

    @Test
    fun `splicing a Stmt closes a pending control flow first`() {
        val block = attachedBlock()
        val flow = block.openFlow("if (ready)") { +call("inside") }
        with(block) { +stmts { +call("after") } }
        assertEquals(1, flow.closes)
        assertEquals(
            "if (ready) {\n  inside()\n}\nafter()\n",
            block.builder.build().toString(),
        )
    }

    @Test
    fun `stmts closes a control flow left pending at the end`() {
        val fragment = stmts { openFlow("if (ready)") { +call("inside") } }
        assertEquals("if (ready) {\n  inside()\n}\n", fragment.code.toString())
    }

    @Test
    fun `two flows opened in sequence render balanced output`() {
        val block = attachedBlock()
        val first = block.openFlow("if (a)") { +call("firstBody") }
        val second = block.openFlow("if (b)") { +call("secondBody") }
        with(block) { +call("after") }
        assertEquals(1, first.closes, "the first flow must close when the second one opens")
        assertEquals(1, second.closes, "the second flow must close before the trailing statement")
        assertEquals(
            "if (a) {\n  firstBody()\n}\nif (b) {\n  secondBody()\n}\nafter()\n",
            block.builder.build().toString(),
        )
    }

    @Test
    fun `runNested folds the nested block in, in order, with its flow closed`() {
        val block = attachedBlock()
        with(block) { +call("before") }
        block.runNested("if") {
            +call("first")
            openFlow("while (more)") { +call("spin") }
        }
        with(block) { +call("after") }
        assertEquals(
            "before()\nfirst()\nwhile (more) {\n  spin()\n}\nafter()\n",
            block.builder.build().toString(),
        )
    }
}
