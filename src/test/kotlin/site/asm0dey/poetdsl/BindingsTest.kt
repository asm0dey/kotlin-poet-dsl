package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.CONST
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCompilerApi::class)
class BindingsTest {
    // --- locals -------------------------------------------------------------------------------

    @Test
    fun `local val with and without an explicit type`() {
        assertEquals("val n: kotlin.Int = 1\n", renderBlock { `val`("n", INT, 1.lit) })
        assertEquals("val n = 1\n", renderBlock { `val`("n", init = 1.lit) })
    }

    @Test
    fun `var and reassignment`() {
        val out = renderBlock {
            val total = `var`("total", INT, 0.lit)
            total assign (total + 1.lit)
        }
        assertEquals("var total: kotlin.Int = 0\ntotal = total + 1\n", out)
    }

    @Test
    fun `compound assignment operators`() {
        val out = renderBlock {
            val t = `var`("t", INT, 0.lit)
            t += 1.lit
            t -= 2.lit
            t *= 3.lit
            t /= 4.lit
            t %= 5.lit
        }
        assertEquals("var t: kotlin.Int = 0\nt += 1\nt -= 2\nt *= 3\nt /= 4\nt %= 5\n", out)
    }

    @Test
    fun `the pure twin of compound assignment is stmts`() {
        val emitting = renderBlock {
            val t = `var`("t", INT, 0.lit)
            t += 1.lit
        }
        val viaPure = renderBlock {
            val t = `var`("t", INT, 0.lit)
            +stmts { t += 1.lit }
        }
        assertEquals(emitting, viaPure)
    }

    @Test
    fun `colliding local names are uniquified`() {
        val out = renderBlock {
            `val`("item", init = 1.lit)
            val second = `val`("item", init = 2.lit)
            +second
        }
        assertEquals("val item = 1\nval item2 = 2\nitem2\n", out)
    }

    @Test
    fun `a local delegate needs no type`() {
        // ADR 0003: `by` without a type is the one inference Kotlin can still do for a local.
        // No statement wraps this one — `emitCode` uses `add`, so the body keeps `lambdaCode`'s
        // own single indent, unlike the property case below.
        // `kotlin.lazy`, not `lazy`: a detached CodeBlock has no FileSpec to register the import
        // against, so `%M` renders canonically — the same reason `%T` renders `kotlin.Int` above.
        assertEquals(
            "val cache by kotlin.lazy {\n  compute()\n}\n",
            renderBlock { `val`("cache", by = call(member("kotlin", "lazy")) { +call("compute") }) },
        )
    }

    @Test
    fun `a typed local may defer its initializer`() {
        val out = renderBlock {
            val t = `var`("t", INT)
            t assign 1.lit
        }
        assertEquals("var t: kotlin.Int\nt = 1\n", out)
    }

    @Test
    fun `the handle a val returns carries the declared name and type`() {
        lateinit var handle: Expr
        renderBlock { handle = `val`("item", init = 1.lit) }
        assertEquals("item", handle.name)
        assertEquals(INT, handle.type, "an untyped local takes its type from the initializer")
        renderBlock { handle = `val`("x", STRING, init = "a".lit) }
        assertEquals(STRING, handle.type)
    }

    // --- properties ---------------------------------------------------------------------------

    @Test
    fun `a member name colliding with a constructor parameter is uniquified, never shadowed`() {
        // ADR 0009's worked example: the second binding is renamed at declaration, and the
        // handle carries the new name, so nothing is ever qualified with `this.`.
        lateinit var second: Expr
        val out = file("com.example", "User") {
            `class`("User") {
                constructorParam(VAL, "username", STRING)
                second = `val`("username", STRING, init = "a".lit)
            }
        }.toString()
        assertEquals("username2", second.name)
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class User(
              public val username: String,
            ) {
              public val username2: String = "a"
            }

            """.trimIndent(),
            out,
        )
    }

    /**
     * D21: two properties named the same in one container is a compile error in Kotlin with no
     * valid output to preserve, so the second is rejected rather than renamed to `username2` —
     * unlike a property colliding with a *different* construct, which still uniquifies (see the
     * test above).
     */
    @Test
    fun `a duplicate property name in one type is rejected, naming the construct`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "User") {
                `class`("User") {
                    `val`("username", STRING, init = "a".lit)
                    `val`("username", STRING, init = "b".lit)
                }
            }
        }
        assertEquals(
            "A property named \"username\" is already declared in this scope.",
            failure.message,
        )
    }

    /** The same rejection applies at file level, where `propertyOf` is also used. */
    @Test
    fun `a duplicate property name at file level is rejected, naming the construct`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Config") {
                `val`("limit", INT, init = 10.lit)
                `val`("limit", INT, init = 20.lit)
            }
        }
        assertEquals(
            "A property named \"limit\" is already declared in this scope.",
            failure.message,
        )
    }

    @Test
    fun `a var property is mutable and a file-level binding takes modifiers`() {
        val out = file("com.example", "Config") {
            `val`(CONST + PRIVATE, "limit", INT, init = 10.lit)
            `class`("Config") { `var`("count", INT, init = 0.lit) }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            private const val limit: Int = 10

            public class Config {
              public var count: Int = 0
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `property with a delegate`() {
        // The delegate's body renders at +6 and its closing brace at +4, two levels deeper than
        // the DSL asks for: `PropertySpec.emit` opens a KotlinPoet statement around a delegate,
        // and KotlinPoet indents a statement's continuation lines twice. Out of this DSL's hands
        // — removing it would need string surgery on rendered output — and valid Kotlin, which
        // `the rendered delegate compiles` below proves by compiling this exact source.
        val out = renderDelegateFile()
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.lazy

            public class User {
              public val x: Int by lazy {
                    calculate()
                  }
            }

            public fun calculate(): Int = 1

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `the rendered delegate compiles`() {
        assertCompiles(renderDelegateFile())
    }

    @Test
    fun `property is an alias of the declaration-level val`() {
        assertEquals(renderBlock { `val`("x", INT, 1.lit) }, renderBlock { property("x", INT, 1.lit) })
        assertEquals(
            file("com.example", "A") { `class`("A") { `val`("x", INT, init = 1.lit) } }.toString(),
            file("com.example", "A") { `class`("A") { property("x", INT, init = 1.lit) } }.toString(),
        )
    }

    // --- ownership (ADR 0008) -----------------------------------------------------------------

    @Test
    fun `a handle escaping the block that declared it is rejected`() {
        lateinit var escaped: Expr
        stmts { escaped = `val`("x", INT, init = 1.lit) }
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> { with(block) { +escaped } }
        assertEquals(
            "Handle from scope 'block' does not enclose the current scope 'fun f'.",
            failure.message,
        )
        assertEquals("", block.builder.build().toString(), "a rejected statement emits nothing")
    }

    @Test
    fun `assignment validates both sides`() {
        val foreign = Expr(CodeBlock.of("leaked"), name = "leaked", scope = ScopeId(null, "fun(other)"))
        val block = attachedBlock()
        assertFailsWith<IllegalStateException> { with(block) { foreign assign 1.lit } }
        assertFailsWith<IllegalStateException> {
            with(block) { expression("t") assign foreign }
        }
        assertFailsWith<IllegalStateException> { with(block) { foreign += 1.lit } }
        assertEquals("", block.builder.build().toString(), "a rejected assignment emits nothing")
    }

    // --- validation ---------------------------------------------------------------------------

    @Test
    fun `a property without a type is a named error`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "User") { `class`("User") { `val`("x", init = 1.lit) } }
        }
        assertEquals(
            "Property 'x' requires an explicit type; KotlinPoet cannot infer it.",
            failure.message,
        )
    }

    @Test
    fun `initializer and delegate together is an error`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { `val`("x", INT, init = 1.lit, by = call("lazy")) }
        }
        assertEquals("Binding 'x' cannot have both an initializer and a delegate.", failure.message)
    }

    @Test
    fun `modifiers on a local are an error`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { `val`(PRIVATE.toModifiers(), "x", INT, init = 1.lit) }
        }
        assertEquals(
            "A local binding ('x') cannot carry annotations or modifiers.",
            failure.message,
        )
    }

    @Test
    fun `a local with nothing to declare it from is a named error`() {
        val failure = assertFailsWith<IllegalStateException> { renderBlock { `val`("x") } }
        assertEquals(
            "Binding 'x' needs a type, an initializer or a delegate.",
            failure.message,
        )
    }

    // --- the rendered locals are valid Kotlin --------------------------------------------------

    @Test
    fun `the rendered locals compile`() {
        val body = stmts {
            val total = `var`("total", INT, 0.lit)
            val step = `val`("step", init = 2.lit)
            total assign (total + step)
            total += step
            total -= 1.lit
            total *= 3.lit
            total /= 2.lit
            total %= 7.lit
            val deferred = `var`("deferred", INT)
            deferred assign total
            val cache = `val`("cache", by = call(member("kotlin", "lazy")) { +deferred })
            +call("println", cache)
        }
        val rendered = file("com.example", "Locals") {
            +FunSpec.builder("run").addCode(body.code).build()
        }.toString()
        assertCompiles(rendered)
    }
}

private fun renderDelegateFile(): String = file("com.example", "User") {
    `class`("User") {
        `val`("x", INT, by = call(member("kotlin", "lazy")) { +call("calculate") })
    }
    +FunSpec.builder("calculate").returns(INT).addStatement("return 1").build()
}.toString()

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompiles(source: String) {
    val result = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Generated.kt", source))
        inheritClassPath = true
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
}
