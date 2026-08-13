package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class LambdasTest {
    private val items = expression("items")

    // --- rendered parameter names (ADR 0005) -----------------------------------------------

    @Test
    fun `omitted param renders implicit it`() {
        assertEquals(
            "items.map {\n  it.name\n}\n",
            renderBlock { +items.call("map") { p -> +p.prop("name") } },
        )
    }

    @Test
    fun `named param renders a parameter list`() {
        assertEquals(
            "items.map { item ->\n  item.name\n}\n",
            renderBlock { +items.call("map", param = "item") { item -> +item.prop("name") } },
        )
    }

    @Test
    fun `the callers own lambda binding name never reaches the output`() {
        val viaP = renderBlock { +items.call("map") { p -> +p.prop("name") } }
        val viaWhatever = renderBlock { +items.call("map") { whatever -> +whatever.prop("name") } }
        assertEquals(viaP, viaWhatever)
        assertEquals(
            renderBlock { +items.call("map", param = "item") { item -> +item.prop("name") } },
            renderBlock { +items.call("map", param = "item") { q -> +q.prop("name") } },
        )
    }

    @Test
    fun `multiple parameters`() {
        assertEquals(
            "items.fold(0) { acc, x ->\n  acc + x\n}\n",
            renderBlock {
                +items.call("fold", 0.lit, params = listOf("acc", "x")) { acc, x -> +(acc + x) }
            },
        )
    }

    @Test
    fun `the list form destructures to the same output as the arity form`() {
        assertEquals(
            renderBlock {
                +items.call("fold", 0.lit, params = listOf("acc", "x")) { acc, x -> +(acc + x) }
            },
            renderBlock {
                +items.call("fold", 0.lit, params = listOf("acc", "x")) { (acc, x) -> +(acc + x) }
            },
        )
    }

    @Test
    fun `arity 8 renders eight parameters`() {
        val names = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        assertEquals(
            "items.combine { a, b, c, d, e, f, g, h ->\n  h\n}\n",
            renderBlock { +items.call("combine", params = names) { _, _, _, _, _, _, _, h -> +h } },
        )
    }

    @Test
    fun `wider than eight uses the list form`() {
        val names = ('a'..'i').map(Char::toString)
        assertEquals(
            "items.combine { a, b, c, d, e, f, g, h, i ->\n  i\n}\n",
            renderBlock { +items.call("combine", params = names) { p -> +p[8] } },
        )
    }

    @Test
    fun `a params list of the wrong size is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { +items.call("fold", params = listOf("acc")) { acc, x -> +(acc + x) } }
        }
        assertEquals("lambda: params names 1 parameter but the body binds 2.", failure.message)
    }

    @Test
    fun `a multi-parameter lambda cannot leave a name out`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { +items.call("fold", params = listOf("acc", null)) { acc, x -> +(acc + x) } }
        }
        assertEquals(
            "lambda: only a single parameter can be left unnamed and render as `it`; " +
                "name every parameter of a multi-parameter lambda.",
            failure.message,
        )
    }

    @Test
    fun `standalone lambda value`() {
        assertEquals("{\n  calculate()\n}", renderValue { lambda { +call("calculate") } })
        assertEquals("{ x ->\n  x\n}", renderValue { lambda("x") { x -> +x } })
        assertEquals("{\n  it\n}", renderValue { lambda(null) { p -> +p } })
        assertEquals("{ a, b ->\n  a + b\n}", renderValue { lambda(listOf("a", "b")) { a, b -> +(a + b) } })
    }

    @Test
    fun `a detached lambda needs no scope at all`() {
        assertEquals("{\n  calculate()\n}", detachedLambda { +call("calculate") }.toString())
    }

    @Test
    fun `a lambda emits nothing on its own`() {
        assertEquals("", renderBlock { lambda { +call("calculate") } })
    }

    @Test
    fun `named lambda params are uniquified against the enclosing scope`() {
        // Task 12's `val`("item", init = 1.lit) is the intended spelling of the collision; until
        // it lands the name is bound directly, which is the same NameScope path.
        assertEquals(
            "items.map { item2 ->\n  item2\n}\n",
            renderBlock {
                names.declare("item")
                +items.call("map", param = "item") { item -> +item }
            },
        )
    }

    @Test
    fun `nested lambdas keep distinct handles`() {
        // Task 15 should restore the `for`(items) { item -> … } spelling of the outer binding;
        // `forEach` exercises the same hazard — a nested lambda must not re-bind the outer handle.
        assertEquals(
            "items.forEach { item ->\n  item.map {\n    it.length\n  }\n}\n",
            renderBlock {
                +items.call("forEach", param = "item") { item ->
                    +item.call("map") { p -> +p.prop("length") }
                }
            },
        )
    }

    @Test
    fun `two sibling lambdas may both use the implicit it`() {
        assertEquals(
            "items.map {\n  it.name\n}\nitems.map {\n  it.size\n}\n",
            renderBlock {
                +items.call("map") { p -> +p.prop("name") }
                +items.call("map") { p -> +p.prop("size") }
            },
        )
    }

    // --- the other lambda-taking constructs -------------------------------------------------

    @Test
    fun `a member call with a lambda resolves its import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addCode(stmts { +call(member("kotlin.io", "use"), items) { p -> +p.prop("name") } }.code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import kotlin.io.use

            public fun f() {
              use(items) {
                it.name
              }
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `invoke takes a lambda too`() {
        assertEquals(
            "f(1) { x ->\n  x\n}\n",
            renderBlock { +expression("f")(1.lit, param = "x") { x -> +x } },
        )
    }

    @Test
    fun `an argumentless call renders no empty parentheses`() {
        assertEquals("items.map {\n  it\n}\n", renderBlock { +items.call("map") { p -> +p } })
        assertEquals("f {\n  it\n}\n", renderBlock { +expression("f")() { p -> +p } })
    }

    // --- D3: a lambda outside a block --------------------------------------------------------

    @Test
    fun `a lambda works at property-delegate position in a type scope`() {
        val spec = typeSpec(name = "Holder") {
            val delegate = call(member("kotlin", "lazy")) { +call("calculate") }
            +PropertySpec.builder("x", INT).delegate(delegate.code).build()
        }
        val file = FileSpec.builder("com.example", "Holder").addType(spec).build()
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.lazy

            public class Holder {
              public val x: Int by lazy {
                    calculate()
                  }
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `a lambda can be built at file scope`() {
        lateinit var lam: Expr
        file("demo", "D") { lam = lambda { +call("calculate") } }
        assertEquals("{\n  calculate()\n}", lam.toString())
    }

    @Test
    fun `a type scope lambda uniquifies its parameters against the type's names`() {
        var rendered = ""
        typeSpec(name = "Holder") {
            constructorParam(VAL, "item", INT)
            rendered = expression("xs").call("map", param = "item") { item -> +item }.toString()
        }
        assertEquals("xs.map { item2 ->\n  item2\n}", rendered)
    }

    // --- ownership through a lambda body (ADR 0008) -----------------------------------------

    @Test
    fun `a foreign handle used inside a lambda body reaches the enclosing fragment`() {
        val foreign = ScopeId(null, "fun(other)")
        val fragment = stmts {
            +items.call("map") { p -> +Expr(CodeBlock.of("leaked"), scope = foreign).call("use") }
        }
        assertEquals(setOf(foreign), fragment.usedScopes, "a lambda body must not swallow the record")
        assertFailsWith<IllegalStateException> { with(attachedBlock()) { +fragment } }
    }

    @Test
    fun `a foreign handle inside a lambda body is rejected in an attached block`() {
        val foreign = ScopeId(null, "fun(other)")
        val block = attachedBlock()
        val failure = assertFailsWith<IllegalStateException> {
            with(block) { +items.call("map") { p -> +Expr(CodeBlock.of("leaked"), scope = foreign) } }
        }
        assertEquals(
            "Handle from scope 'fun(other)' does not enclose the current scope 'lambda'.",
            failure.message,
        )
    }

    @Test
    fun `a handle from the enclosing block is usable inside a lambda body`() {
        val block = attachedBlock()
        val x = Expr(CodeBlock.of("x"), name = "x", scope = block.id)
        with(block) { +items.call("map") { p -> +x.call("inc") } }
        assertEquals("items.map {\n  x.inc()\n}\n", block.builder.build().toString())
    }

    @Test
    fun `a lambda handle used outside its own lambda is rejected`() {
        var escaped: Expr? = null
        val block = attachedBlock()
        with(block) {
            +items.call("map") { p ->
                escaped = p
                +p
            }
        }
        val failure = assertFailsWith<IllegalStateException> { with(block) { +escaped!!.call("use") } }
        assertEquals(
            "Handle from scope 'lambda' does not enclose the current scope 'fun f'.",
            failure.message,
        )
    }

    @Test
    fun `a type scope lambda records a foreign handle on the expression it returns`() {
        val foreign = ScopeId(null, "fun(other)")
        lateinit var delegate: Expr
        typeSpec(name = "Holder") {
            delegate = call(member("kotlin", "lazy")) { +Expr(CodeBlock.of("leaked"), scope = foreign) }
        }
        assertEquals(
            setOf(foreign),
            delegate.usedScopes,
            "a lambda built outside a block carries its foreign scopes to wherever it is spliced",
        )
        assertFailsWith<IllegalStateException> { with(attachedBlock()) { +delegate } }
    }

    @Test
    fun `a type scope's own handles are not foreign to a lambda declared in it`() {
        lateinit var delegate: Expr
        typeSpec(name = "Holder") {
            val seed = constructorParam(VAL, "seed", INT)
            delegate = call(member("kotlin", "lazy")) { +seed.call("inc") }
        }
        assertTrue(delegate.usedScopes.isEmpty(), "the enclosing type already encloses the use site")
    }

    @Test
    fun `a detached lambda reports the foreign scopes it used`() {
        val foreign = ScopeId(null, "fun(other)")
        val lam = detachedLambda { +Expr(CodeBlock.of("leaked"), scope = foreign).call("use") }
        assertEquals(setOf(foreign), lam.usedScopes)
        assertFailsWith<IllegalStateException> { with(attachedBlock()) { +lam } }
    }

    // --- the output is real Kotlin -----------------------------------------------------------

    @Test
    fun `generated lambdas compile`() {
        val file = FileSpec.builder("demo", "Demo")
            .addFunction(
                FunSpec.builder("f")
                    .addParameter("items", LIST.parameterizedBy(INT))
                    .addCode(
                        stmts {
                            +items.call("map") { p -> +p.call("toString") }
                            +items.call("fold", 0.lit, params = listOf("acc", "x")) { acc, x -> +(acc + x) }
                            +items.call("forEach", param = "item") { item ->
                                +item.call("toString").call("map") { p -> +p.prop("code") }
                            }
                        }.code,
                    )
                    .build(),
            )
            .build()
        assertCompiles(file.toString())
    }

    @Test
    fun `a delegate lambda compiles despite the indentation KotlinPoet forces on it`() {
        val holder = typeSpec(name = "Holder") {
            val delegate = call(member("kotlin", "lazy")) { +1.lit }
            +PropertySpec.builder("x", INT).delegate(delegate.code).build()
        }
        assertCompiles(FileSpec.builder("demo", "Holder").addType(holder).build().toString())
    }
}

/** Renders the value a builder returns, without emitting it. */
internal fun renderValue(body: BlockScope.() -> Expr): String =
    BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "block"), mutableListOf())
        .body()
        .toString()

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompiles(source: String) {
    val result = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Generated.kt", source))
        inheritClassPath = true
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
}
