package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.TypeName
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCompilerApi::class)
class TryTest {
    @Test
    fun `try catch finally`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalStateException) {\n  log(e.toString())\n} " +
                "finally {\n  cleanup()\n}\n",
            renderBlock {
                `try` { +call("risky") }
                    .`catch`("e", reference<IllegalStateException>()) { e -> +call("log", e.call("toString")) }
                    .finally { +call("cleanup") }
            },
        )
    }

    @Test
    fun `two catches uniquify the exception variable`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalArgumentException) {\n  a()\n} " +
                "catch (e2: java.lang.IllegalStateException) {\n  b()\n}\n",
            renderBlock {
                `try` { +call("risky") }
                    .`catch`("e", reference<IllegalArgumentException>()) { +call("a") }
                    .`catch`("e", reference<IllegalStateException>()) { +call("b") }
            },
        )
    }

    @Test
    fun `try is flushed before the next statement`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\nafter()\n",
            renderBlock {
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
                +call("after")
            },
        )
    }

    @Test
    fun `try with only finally, no catch`() {
        assertEquals(
            "try {\n  risky()\n} finally {\n  cleanup()\n}\n",
            renderBlock {
                `try` { +call("risky") }.finally { +call("cleanup") }
            },
        )
    }

    @Test
    fun `tryCatch is an alias`() {
        assertEquals(
            renderBlock { `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("a") } },
            renderBlock { tryCatch { +call("risky") }.`catch`("e", reference<Exception>()) { +call("a") } },
        )
    }

    @Test
    fun `catch parameter name does not leak into the enclosing scope`() {
        // The "e" reserved by the catch clause frees once the try closes: a `val` named "e"
        // declared afterwards in the same enclosing scope does not get renamed to "e2".
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\nval e = 1\n",
            renderBlock {
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
                `val`("e", init = 1.lit)
            },
        )
    }

    // --- a closed chain rejects reattachment ----------------------------------------------------

    @Test
    fun `catch on a chain closed by an unrelated statement throws instead of reattaching`() {
        lateinit var stale: TryChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                stale = `try` { +call("risky") }
                +call("after")
                stale.`catch`("e", reference<Exception>()) { +call("handle") }
            }
        }
        assertEquals(
            "catch: this try/catch/finally chain is already closed and cannot take another clause.",
            failure.message,
        )
    }

    @Test
    fun `finally on a chain closed by an unrelated statement throws instead of reattaching`() {
        lateinit var stale: TryChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                stale = `try` { +call("risky") }
                +call("after")
                stale.finally { +call("cleanup") }
            }
        }
        assertEquals(
            "finally: this try/catch/finally chain is already closed and cannot take another clause.",
            failure.message,
        )
    }

    @Test
    fun `calling close directly on an already-closed chain throws`() {
        val block = attachedBlock()
        val chain = with(block) { `try` { +call("risky") } }
        chain.close()
        val failure = assertFailsWith<IllegalStateException> { chain.close() }
        assertEquals("close: this try/catch/finally chain is already closed.", failure.message)
    }

    // --- two live PendingFlow implementors sharing one `pending` slot ----------------------------

    @Test
    fun `a stale try chain closed by a sibling if opening throws instead of reattaching its catch to the if`() {
        lateinit var stale: TryChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                stale = `try` { +call("risky") }
                // Opening an `if` flushes the pending try, so `if` — not `try` — is now the
                // live PendingFlow on this owner. A stale TryChain must reject on its own,
                // never silently attach `catch` to whatever happens to be open.
                `if`(expression("x")) { +call("guarded") }
                stale.`catch`("e", reference<Exception>()) { +call("handle") }
            }
        }
        assertEquals(
            "catch: this try/catch/finally chain is already closed and cannot take another clause.",
            failure.message,
        )
    }

    @Test
    fun `a stale if chain closed by a sibling try opening throws instead of reattaching`() {
        lateinit var stale: IfChain
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock {
                stale = `if`(expression("x")) { +call("inner") }
                // Opening a `try` flushes the pending if, so `try` is now the live PendingFlow.
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
                stale.elseIf(expression("x")) { +call("unreachable") }
            }
        }
        assertEquals(
            "elseIf: this if/elseIf/else chain is already closed and cannot take another branch.",
            failure.message,
        )
    }

    @Test
    fun `try opened while an if is pending flushes the if cleanly`() {
        assertEquals(
            "if (x) {\n  guarded()\n}\ntry {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\n",
            renderBlock {
                `if`(expression("x")) { +call("guarded") }
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
            },
        )
    }

    @Test
    fun `if opened while a try is pending flushes the try cleanly`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\nif (x) {\n  guarded()\n}\n",
            renderBlock {
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
                `if`(expression("x")) { +call("guarded") }
            },
        )
    }

    // --- the output is real Kotlin ---------------------------------------------------------------

    @Test
    fun `a try,catch compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `try` { ret(n) }.`catch`("e", reference<Exception>()) { ret(0.lit) }
            ret(0.lit)
        }
    }

    @Test
    fun `a try,catch,finally compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `try` { ret(n) }
                .`catch`("e", reference<Exception>()) { ret(0.lit) }
                .finally { +call("println", "done".lit) }
            ret(0.lit)
        }
    }

    @Test
    fun `a try,finally with no catch compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `try` { ret(n) }.finally { +call("println", "done".lit) }
            ret(0.lit)
        }
    }

    @Test
    fun `multiple catch clauses compile`() {
        assertCompilesAsFun("f", INT) { n ->
            `try` { ret(n) }
                .`catch`("e", reference<IllegalArgumentException>()) { ret(1.lit) }
                .`catch`("e", reference<IllegalStateException>()) { ret(2.lit) }
                .`catch`("e", reference<Exception>()) { ret(3.lit) }
            ret(0.lit)
        }
    }

    @Test
    fun `a try nested inside an if compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `if`(n gt 0.lit) {
                `try` { ret(n) }.`catch`("e", reference<Exception>()) { ret(0.lit) }
            }
            ret(0.lit)
        }
    }

    @Test
    fun `an if nested inside a catch body compiles`() {
        assertCompilesAsFun("f", INT) { n ->
            `try` { ret(n) }
                .`catch`("e", reference<Exception>()) { e ->
                    `if`(n gt 0.lit) { ret(1.lit) }
                        .`else` { ret(0.lit) }
                    +e.call("toString")
                    ret(0.lit)
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

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompiles(source: String) {
    val result = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Generated.kt", source))
        inheritClassPath = true
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
}
