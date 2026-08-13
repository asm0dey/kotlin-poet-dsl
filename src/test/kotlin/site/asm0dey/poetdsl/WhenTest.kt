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
class WhenTest {
    @Test
    fun `when with single and multiple conditions plus else`() {
        assertEquals(
            "when (subject) {\n" +
                "  1 -> {\n    one()\n  }\n" +
                "  2, 3 -> {\n    few()\n  }\n" +
                "  else -> {\n    many()\n  }\n" +
                "}\n",
            renderBlock {
                `when`(expression("subject")) {
                    branch(1.lit) { +call("one") }
                    branch(2.lit, 3.lit) { +call("few") }
                    `else` { +call("many") }
                }
            },
        )
    }

    @Test
    fun `subjectless when`() {
        assertEquals(
            "when {\n  a < 0 -> {\n    neg()\n  }\n  else -> {\n    pos()\n  }\n}\n",
            renderBlock {
                whenTrue {
                    branch(expression("a") lt 0.lit) { +call("neg") }
                    `else` { +call("pos") }
                }
            },
        )
    }

    @Test
    fun `whenOn is an alias`() {
        val subject = expression("subject")
        assertEquals(
            renderBlock { `when`(subject) { branch(1.lit) { +call("a") } } },
            renderBlock { whenOn(subject) { branch(1.lit) { +call("a") } } },
        )
    }

    @Test
    fun `two whens in sequence flush independently`() {
        assertEquals(
            "when (a) {\n  1 -> {\n    x()\n  }\n}\n" +
                "when (b) {\n  2 -> {\n    y()\n  }\n}\n",
            renderBlock {
                `when`(expression("a")) { branch(1.lit) { +call("x") } }
                `when`(expression("b")) { branch(2.lit) { +call("y") } }
            },
        )
    }

    @Test
    fun `a when nested inside an if compiles and renders`() {
        assertEquals(
            "if (a > 0) {\n  when (a) {\n    1 -> {\n      one()\n    }\n    else -> {\n      other()\n    }\n  }\n}\n",
            renderBlock {
                `if`(expression("a") gt 0.lit) {
                    `when`(expression("a")) {
                        branch(1.lit) { +call("one") }
                        `else` { +call("other") }
                    }
                }
            },
        )
    }

    @Test
    fun `an if nested inside a when branch compiles and renders`() {
        assertEquals(
            "when (a) {\n  1 -> {\n    if (a > 0) {\n      pos()\n    }\n  }\n}\n",
            renderBlock {
                `when`(expression("a")) {
                    branch(1.lit) {
                        `if`(expression("a") gt 0.lit) { +call("pos") }
                    }
                }
            },
        )
    }

    // --- branch() requires at least one condition (D9) -----------------------------------------

    @Test
    fun `branch with no conditions throws IllegalStateException naming the fallback`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                `when`(expression("subject")) {
                    branch { +call("unreachable") }
                }
            }
        }
        assertEquals(
            "A when branch needs at least one condition; use `else` for the fallback.",
            failure.message,
        )
    }

    // --- a closed when rejects reattachment -----------------------------------------------------

    @Test
    fun `branch on a when closed after the body returns throws instead of corrupting the builder`() {
        lateinit var stale: WhenScope
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                `when`(expression("subject")) {
                    stale = this
                    branch(1.lit) { +call("one") }
                }
                +call("after")
                stale.branch(2.lit) { +call("two") }
            }
        }
        assertEquals(
            "branch: this when has already closed and cannot take another branch.",
            failure.message,
        )
    }

    @Test
    fun `else on a when closed after the body returns throws instead of corrupting the builder`() {
        lateinit var stale: WhenScope
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                `when`(expression("subject")) {
                    stale = this
                    branch(1.lit) { +call("one") }
                }
                stale.`else` { +call("fallback") }
            }
        }
        assertEquals(
            "else: this when has already closed and cannot take another branch.",
            failure.message,
        )
    }

    @Test
    fun `branch on a stale when captured while a sibling if chain is pending throws instead of reattaching`() {
        lateinit var stale: WhenScope
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                `when`(expression("subject")) {
                    stale = this
                    branch(1.lit) { +call("one") }
                }
                // A different construct is now pending on the same owner; a stale WhenScope
                // must still be rejected on its own, not by whatever happens to be pending.
                `if`(expression("subject")) { +call("guarded") }
                stale.branch(2.lit) { +call("two") }
            }
        }
        assertEquals(
            "branch: this when has already closed and cannot take another branch.",
            failure.message,
        )
    }

    // --- the output is real Kotlin -------------------------------------------------------------

    @Test
    fun `a when with several branches compiles`() {
        assertCompilesAsFun("classify", INT) { n ->
            `when`(n) {
                branch(0.lit) { ret(0.lit) }
                branch(1.lit, 2.lit) { ret(1.lit) }
                branch(3.lit) { ret(3.lit) }
                `else` { ret((-1).lit) }
            }
            ret(0.lit)
        }
    }

    @Test
    fun `a when with an else compiles`() {
        assertCompilesAsFun("sign", INT) { n ->
            whenTrue {
                branch(n gt 0.lit) { ret(1.lit) }
                `else` { ret(0.lit) }
            }
            ret(0.lit)
        }
    }

    @Test
    fun `two whens in sequence compile`() {
        assertCompilesAsFun("f", INT) { n ->
            `when`(n) { branch(0.lit) { ret(0.lit) } }
            `when`(n) { branch(1.lit) { ret(1.lit) } }
            ret(0.lit)
        }
    }

    @Test
    fun `a when nested inside an if compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `if`(n gt 0.lit) {
                `when`(n) {
                    branch(1.lit) { ret(1.lit) }
                    `else` { ret(0.lit) }
                }
            }
            ret(0.lit)
        }
    }

    @Test
    fun `an if nested inside a when branch compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `when`(n) {
                branch(1.lit) {
                    `if`(n gt 0.lit) { ret(1.lit) }
                    ret(0.lit)
                }
                `else` { ret((-1).lit) }
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
