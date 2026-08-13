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

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompiles(source: String) {
    val result = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Generated.kt", source))
        inheritClassPath = true
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
}
