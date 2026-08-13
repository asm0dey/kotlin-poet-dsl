package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileScopeTest {
    @Test
    fun `empty file renders its package`() {
        assertEquals("package com.example\n\n", file("com.example", "Api") { }.toString())
    }

    @Test
    fun `an empty file is exactly kotlinpoet's empty file`() {
        assertEquals(
            FileSpec.builder("com.example", "Api").build().toString(),
            file("com.example", "Api") { }.toString(),
        )
    }

    @Test
    fun `a prebuilt FunSpec can be emitted four ways`() {
        val f = FunSpec.builder("helper").build()
        val plus = file("com.example", "Api") { +f }.toString()
        assertEquals(plus, file("com.example", "Api") { f() }.toString())
        assertEquals(plus, file("com.example", "Api") { emit(f) }.toString())
        assertEquals(plus, file("com.example", "Api") { add(f) }.toString())
        assertEquals(
            """
            package com.example

            public fun helper() {
            }

            """.trimIndent(),
            plus,
        )
    }

    @Test
    fun `a prebuilt TypeSpec can be emitted four ways`() {
        val t = TypeSpec.classBuilder("User").build()
        val plus = file("com.example", "Api") { +t }.toString()
        assertEquals(plus, file("com.example", "Api") { t() }.toString())
        assertEquals(plus, file("com.example", "Api") { emit(t) }.toString())
        assertEquals(plus, file("com.example", "Api") { add(t) }.toString())
        assertEquals(
            """
            package com.example

            public class User
            """.trimIndent() + "\n",
            plus,
        )
    }

    @Test
    fun `a prebuilt PropertySpec can be emitted four ways`() {
        val p = PropertySpec.builder("greeting", STRING).initializer("%S", "hi").build()
        val plus = file("com.example", "Api") { +p }.toString()
        assertEquals(plus, file("com.example", "Api") { p() }.toString())
        assertEquals(plus, file("com.example", "Api") { emit(p) }.toString())
        assertEquals(plus, file("com.example", "Api") { add(p) }.toString())
        assertEquals(
            """
            package com.example

            import kotlin.String

            public val greeting: String = "hi"
            """.trimIndent() + "\n",
            plus,
        )
    }

    /**
     * ADR 0001 fact 4: one declaration parameterized on the sealed supertype resolves to the
     * innermost scope value in context, with no ambiguity. Every construct valid at more than
     * one level rides on this, so it is asserted directly rather than assumed.
     */
    @Test
    fun `one declaration on Scope resolves to the innermost scope`() {
        val seen = mutableListOf<String>()
        file("com.example", "Api") {
            seen += level()
            val type = TypeScope(TypeSpec.classBuilder("User"), names.child(), id.child("class User"))
            with(type) {
                seen += level()
                val block = BlockScope(CodeBlock.builder(), names.child(), id.child("fun f"), mutableListOf())
                with(block) {
                    seen += level()
                }
                seen += level()
            }
            seen += level()
        }
        assertEquals(listOf("file", "type", "block", "type", "file"), seen)
    }

    /**
     * D17: the twelve spec-emission declarations dispatch on the runtime `Scope`, so a spec
     * spliced inside a `class` body lands on the class builder, not the enclosing file's.
     * Before D17 all twelve were `context(f: FileScope)`, and `FileScope` stayed in context
     * inside a nested `TypeScope`, so `+someFunSpec` inside a class silently added to the
     * file instead — Global Constraint 26 forbids exactly this. Each spec type is checked
     * against a rendered file so the class/file distinction is visible in the output, not
     * just in which builder was called.
     */
    @Test
    fun `a FunSpec emitted inside a class body lands on the class, not the file, via all four forms`() {
        val expected = """
            package com.example

            public class User {
              public fun greet() {
              }
            }

            """.trimIndent()

        fun renderWith(emitInto: TypeScope.(FunSpec) -> Unit): String =
            file("com.example", "Api") {
                val type = TypeScope(TypeSpec.classBuilder("User"), names.child(), id.child("class User"))
                val f = FunSpec.builder("greet").build()
                with(type) { emitInto(f) }
                +type.finish()
            }.toString()

        val plus = renderWith { f -> +f }
        assertEquals(expected, plus)
        assertEquals(plus, renderWith { f -> f() })
        assertEquals(plus, renderWith { f -> emit(f) })
        assertEquals(plus, renderWith { f -> add(f) })
    }

    @Test
    fun `a TypeSpec emitted inside a class body lands on the class, not the file`() {
        val rendered = file("com.example", "Api") {
            val type = TypeScope(TypeSpec.classBuilder("User"), names.child(), id.child("class User"))
            with(type) {
                +TypeSpec.classBuilder("Nested").build()
            }
            +type.finish()
        }.toString()

        assertEquals(
            """
            package com.example

            public class User {
              public class Nested
            }
            """.trimIndent() + "\n",
            rendered,
        )
    }

    @Test
    fun `a PropertySpec emitted inside a class body lands on the class, not the file`() {
        val rendered = file("com.example", "Api") {
            val type = TypeScope(TypeSpec.classBuilder("User"), names.child(), id.child("class User"))
            with(type) {
                +PropertySpec.builder("name", STRING).initializer("%S", "x").build()
            }
            +type.finish()
        }.toString()

        assertEquals(
            """
            package com.example

            import kotlin.String

            public class User {
              public val name: String = "x"
            }
            """.trimIndent() + "\n",
            rendered,
        )
    }

    /**
     * Regression: emission at file level must still land on the file after D17's rewrite from
     * `context(f: FileScope)` to `context(s: Scope)` with a runtime dispatch. The four-way
     * tests above already cover this per spec type; this test additionally confirms a
     * `FunSpec`, `TypeSpec` and `PropertySpec` emitted side by side in the same file body all
     * land at file level together, not nested into one another.
     */
    @Test
    fun `specs emitted at file level still land on the file, side by side`() {
        val rendered = file("com.example", "Api") {
            +FunSpec.builder("helper").build()
            +TypeSpec.classBuilder("User").build()
            +PropertySpec.builder("greeting", STRING).initializer("%S", "hi").build()
        }.toString()

        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun helper() {
            }

            public class User

            public val greeting: String = "hi"
            """.trimIndent() + "\n",
            rendered,
        )
    }

    @Test
    fun `splicing a FunSpec into a block body throws and names the construct`() {
        val block = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> {
            with(block) { +FunSpec.builder("x").build() }
        }
        assertEquals("FunSpec: a function spec cannot be emitted into a block body.", thrown.message)
    }

    @Test
    fun `splicing a TypeSpec into a block body throws and names the construct`() {
        val block = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> {
            with(block) { +TypeSpec.classBuilder("x").build() }
        }
        assertEquals("TypeSpec: a type spec cannot be emitted into a block body.", thrown.message)
    }

    @Test
    fun `splicing a PropertySpec into a block body throws and names the construct`() {
        val block = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> {
            with(block) { +PropertySpec.builder("x", STRING).initializer("%S", "x").build() }
        }
        assertEquals("PropertySpec: a property spec cannot be emitted into a block body.", thrown.message)
    }

    @Test
    fun `a child block nests names and id, and isolates returns only for lambdas`() {
        val root = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "file").child("fun f"), mutableListOf())
        root.names.declare("user")

        val flow = root.child("if")
        assertSame(root.returns, flow.returns, "control flow shares the enclosing returns list")
        assertTrue(flow.names.isTaken("user"), "a nested block sees the enclosing block's names")
        assertEquals("if", flow.id.toString())
        assertSame(root.id, flow.id.parent)

        val lambda = root.child("lambda", isolateReturns = true)
        assertNotSame(root.returns, lambda.returns, "a lambda body's return is non-local (ADR 0007)")

        assertEquals(false, root.detachedRoot)
        assertEquals(false, flow.detachedRoot)
        val detached = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "block"), mutableListOf(), true)
        assertEquals(true, detached.child("if").detachedRoot, "detachedness is inherited by children")
    }

    @Test
    fun `TypeScope adds a primary constructor only when one was declared`() {
        val without = TypeScope(TypeSpec.classBuilder("User"), NameScope(null), ScopeId(null, "type"))
        assertEquals("public class User\n", without.finish().toString())

        val withCtor = TypeScope(TypeSpec.classBuilder("User"), NameScope(null), ScopeId(null, "type"))
        withCtor.ctor.addParameter("name", STRING)
        withCtor.hasCtor = true
        assertEquals(
            """
            public class User(
              name: kotlin.String,
            )
            """.trimIndent() + "\n",
            withCtor.finish().toString(),
        )
    }

    /**
     * `%T` and `%M` must reach KotlinPoet unrendered, or it cannot emit the import. Asserting
     * on the import line and the short name proves the placeholder survived emission.
     */
    @Test
    fun `type and member placeholders survive emission and become imports`() {
        val instant = ClassName("java.time", "Instant")
        val makeInstant = MemberName("com.example.util", "makeInstant")
        val f = FunSpec.builder("call")
            .returns(instant)
            .addStatement("return %M()", makeInstant)
            .build()

        assertEquals(
            """
            package com.example

            import com.example.util.makeInstant
            import java.time.Instant

            public fun call(): Instant = makeInstant()
            """.trimIndent() + "\n",
            file("com.example", "Api") { +f }.toString(),
        )
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `a rendered file is valid Kotlin that resolves its imports`() {
        val instant = ClassName("java.time", "Instant")
        val makeInstant = MemberName("com.example.util", "makeInstant")

        val util = file("com.example.util", "Util") {
            +FunSpec.builder("makeInstant")
                .returns(instant)
                .addStatement("return %T.now()", instant)
                .build()
        }
        val api = file("com.example", "Api") {
            +TypeSpec.classBuilder("Clock")
                .addFunction(
                    FunSpec.builder("call")
                        .returns(instant)
                        .addStatement("return %M()", makeInstant)
                        .build(),
                )
                .build()
        }

        val result = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin("Util.kt", util.toString()),
                SourceFile.kotlin("Api.kt", api.toString()),
            )
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}

/**
 * A stand-in for the multi-level constructs later tasks declare: one declaration on the
 * sealed supertype, dispatching on the runtime scope.
 */
context(s: Scope)
private fun level(): String = when (s) {
    is FileScope -> "file"
    is TypeScope -> "type"
    is BlockScope -> "block"
}
