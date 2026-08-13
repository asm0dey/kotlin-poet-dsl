package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.TypeName
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCompilerApi::class)
class IfChainTest {
    private val x = expression("x")

    @Test
    fun `bare if is flushed at block close`() {
        assertEquals(
            "if (x < 0) {\n  return false\n}\n",
            renderBlock { `if`(x lt 0.lit) { ret(false.lit) } },
        )
    }

    @Test
    fun `if is flushed before the next statement`() {
        assertEquals(
            "if (x < 0) {\n  return false\n}\nafter()\n",
            renderBlock {
                `if`(x lt 0.lit) { ret(false.lit) }
                +call("after")
            },
        )
    }

    @Test
    fun `full chain`() {
        assertEquals(
            "if (x < 0) {\n  return false\n} else if (x > 100) {\n  return false\n} else {\n  return true\n}\n",
            renderBlock {
                `if`(x lt 0.lit) { ret(false.lit) }
                    .elseIf(x gt 100.lit) { ret(false.lit) }
                    .`else` { ret(true.lit) }
            },
        )
    }

    @Test
    fun `chain inside a loop`() {
        assertEquals(
            "for (item in items) {\n  if (item == null) {\n    continue\n  }\n  item.run()\n}\n",
            renderBlock {
                `for`(expression("items")) { item ->
                    `if`(item eq nul) { `continue`() }
                    +item.call("run")
                }
            },
        )
    }

    @Test
    fun `ifThen is an alias`() {
        assertEquals(
            renderBlock { `if`(x) { +call("a") } },
            renderBlock { ifThen(x) { +call("a") } },
        )
    }

    // --- a closed chain rejects reattachment -----------------------------------------------------

    @Test
    fun `elseIf on a chain closed by an unrelated statement at nesting depth greater than zero throws instead of reattaching to the outer if`() {
        lateinit var stale: IfChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                `if`(x) {
                    stale = `if`(x) { +call("inner1") }
                    +call("after")
                    stale.elseIf(x) { +call("inner2") }
                        .`else` { +call("inner3") }
                }
            }
        }
        assertEquals(
            "elseIf: this if/elseIf/else chain is already closed and cannot take another branch.",
            failure.message,
        )
    }

    @Test
    fun `elseIf on a chain closed by an unrelated statement at top level throws the domain check`() {
        lateinit var stale: IfChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                stale = `if`(x) { +call("inner1") }
                +call("after")
                stale.elseIf(x) { +call("inner2") }
            }
        }
        assertEquals(
            "elseIf: this if/elseIf/else chain is already closed and cannot take another branch.",
            failure.message,
        )
    }

    @Test
    fun `calling close directly on an already-closed chain throws`() {
        val block = attachedBlock()
        val chain = with(block) { `if`(x) { +call("inner1") } }
        chain.close()
        val failure = assertFailsWith<IllegalStateException> { chain.close() }
        assertEquals("close: this if/elseIf/else chain is already closed.", failure.message)
    }

    // --- the output is real Kotlin -------------------------------------------------------------

    @Test
    fun `a bare if compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `if`(n gt 0.lit) { ret(n) }
            ret(0.lit)
        }
    }

    @Test
    fun `an if,elseIf,else chain compiles`() {
        assertCompilesAsFun("classify", INT) { n ->
            `if`(n lt 0.lit) { ret((-1).lit) }
                .elseIf(n eq 0.lit) { ret(0.lit) }
                .`else` { ret(1.lit) }
        }
    }

    @Test
    fun `two ifs in sequence compile`() {
        assertCompilesAsFun("f", INT) { n ->
            `if`(n lt 0.lit) { ret((-1).lit) }
            `if`(n gt 0.lit) { ret(1.lit) }
            ret(0.lit)
        }
    }

    @Test
    fun `a nested if inside an if compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `if`(n gt 0.lit) {
                `if`(n gt 100.lit) { ret(2.lit) }
                    .`else` { ret(1.lit) }
            }
            ret(0.lit)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompilesAsFun(name: String, returns: TypeName, body: BlockScope.(Expr) -> Unit) {
    val file = FileSpec.builder("demo", "Demo")
        .addFunction(
            FunSpec.builder(name)
                .addParameter("n", INT)
                .returns(returns)
                .addCode(
                    stmts {
                        val n = expression("n")
                        body(n)
                    }.code,
                )
                .build(),
        )
        .build()
    assertCompiles(file.toString())
}
