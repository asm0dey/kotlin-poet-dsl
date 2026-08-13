package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCompilerApi::class)
class LoopsTest {
    @Test
    fun `for over a named handle singularizes the loop variable`() {
        assertEquals(
            "val items = load()\nfor (item in items) {\n  item.run()\n}\n",
            renderBlock {
                val items = `val`("items", init = call("load"))
                `for`(items) { item -> +item.call("run") }
            },
        )
    }

    @Test
    fun `explicit name wins`() {
        assertEquals(
            "for (user in users) {\n  user\n}\n",
            renderBlock { `for`(expression("users"), name = "user") { user -> +user } },
        )
    }

    @Test
    fun `loop variable is uniquified against the enclosing scope`() {
        assertEquals(
            "val item = 1\nfor (item2 in items) {\n  item2\n}\n",
            renderBlock {
                `val`("item", init = 1.lit)
                `for`(expression("items")) { item -> +item }
            },
        )
    }

    @Test
    fun `loop variable name is freed once the loop closes`() {
        assertEquals(
            "for (item in items) {\n  item\n}\nval item = 1\n",
            renderBlock {
                `for`(expression("items")) { item -> +item }
                `val`("item", init = 1.lit)
            },
        )
    }

    @Test
    fun `while and doWhile`() {
        assertEquals(
            "var n: kotlin.Int = 0\nwhile (n < 10) {\n  n += 1\n}\ndo {\n  n -= 1\n} while (n > 0)\n",
            renderBlock {
                val n = `var`("n", INT, 0.lit)
                `while`(n lt 10.lit) { n += 1.lit }
                doWhile(n gt 0.lit) { n -= 1.lit }
            },
        )
    }

    @Test
    fun `break continue and throw`() {
        assertEquals(
            "for (item in items) {\n  break\n  continue\n  throw IllegalStateException(\"bad\")\n}\n",
            renderBlock {
                `for`(expression("items")) {
                    `break`()
                    `continue`()
                    `throw`(call("IllegalStateException", "bad".lit))
                }
            },
        )
    }

    @Test
    fun `aliases match the backticked forms`() {
        assertEquals(
            renderBlock { `for`(expression("xs")) { x -> +x } },
            renderBlock { forIn(expression("xs")) { x -> +x } },
        )
        assertEquals(renderBlock { `break`() }, renderBlock { brk() })
        assertEquals(renderBlock { `continue`() }, renderBlock { cont() })
        assertEquals(
            renderBlock { `throw`(call("boom")) },
            renderBlock { throwIt(call("boom")) },
        )
        assertEquals(renderBlock { `return`(1.lit) }, renderBlock { ret(1.lit) })
        assertEquals(renderBlock { `return`() }, renderBlock { ret() })
    }

    @Test
    fun `return with a value is recorded for return-type inference`() {
        val block = attachedBlock()
        with(block) { `return`(1.lit) }
        assertEquals("return 1\n", block.builder.build().toString())
        assertEquals(INT, block.returns.single())
    }

    @Test
    fun `return with no value emits and records nothing`() {
        val block = attachedBlock()
        with(block) { `return`() }
        assertEquals("return\n", block.builder.build().toString())
        assertEquals(0, block.returns.size)
    }

    // --- the pending-flow contract -----------------------------------------------------------

    @Test
    fun `two loops in a row render balanced braces`() {
        assertEquals(
            "for (item in items) {\n  break\n}\nwhile (item) {\n  break\n}\n",
            renderBlock {
                `for`(expression("items")) { `break`() }
                `while`(expression("item")) { `break`() }
            },
        )
    }

    @Test
    fun `a loop closes a control flow left pending by a previous construct`() {
        // openFlow stands in for a construct (like the `if` of Task 16) that leaves a flow open
        // for a possible `else`. A loop opening right after it must flush that flow first, or the
        // first flow's closing brace is silently dropped (the exact failure this task must avoid).
        val block = attachedBlock()
        val flow = block.openFlow("if (ready)") { +call("prep") }
        with(block) { `while`(expression("more")) { `break`() } }
        assertEquals(1, flow.closes, "the pending flow must close before the loop opens")
        assertNull(block.pending, "the loop's own flow does not stay pending")
        assertEquals(
            "if (ready) {\n  prep()\n}\nwhile (more) {\n  break\n}\n",
            block.builder.build().toString(),
        )
    }

    @Test
    fun `a nested loop's own trailing pending flow is closed before folding in`() {
        val block = attachedBlock()
        with(block) {
            `for`(expression("items")) {
                openFlow("if (ready)") { +call("prep") }
            }
        }
        assertEquals(
            "for (item in items) {\n  if (ready) {\n    prep()\n  }\n}\n",
            block.builder.build().toString(),
        )
    }

    // --- the output is real Kotlin -------------------------------------------------------------

    @Test
    fun `nested loops compile`() {
        val file = FileSpec.builder("demo", "Demo")
            .addFunction(
                FunSpec.builder("f")
                    .addParameter("items", LIST.parameterizedBy(LIST.parameterizedBy(INT)))
                    .addCode(
                        stmts {
                            `for`(expression("items")) { row ->
                                `for`(row) { cell ->
                                    val n = `var`("n", INT, cell)
                                    `while`(n gt 0.lit) {
                                        n -= 1.lit
                                        `continue`()
                                    }
                                    `break`()
                                }
                            }
                        }.code,
                    )
                    .build(),
            )
            .build()
        assertCompiles(file.toString())
    }

    @Test
    fun `sequential loops with throw and return compile`() {
        val file = FileSpec.builder("demo", "Demo")
            .addFunction(
                FunSpec.builder("f")
                    .addParameter("items", LIST.parameterizedBy(INT))
                    .returns(INT)
                    .addCode(
                        stmts {
                            val total = `var`("total", INT, 0.lit)
                            `for`(expression("items")) { item ->
                                total += item
                            }
                            `while`(total gt 100.lit) {
                                total -= 1.lit
                            }
                            doWhile(total lt 0.lit) {
                                total += 1.lit
                            }
                            `throw`(call("IllegalStateException", "unreachable".lit))
                            `return`(total)
                        }.code,
                    )
                    .build(),
            )
            .build()
        assertCompiles(file.toString())
    }
}
