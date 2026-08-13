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

        val with = TypeScope(TypeSpec.classBuilder("User"), NameScope(null), ScopeId(null, "type"))
        with.ctor.addParameter("name", STRING)
        with.hasCtor = true
        assertEquals(
            """
            public class User(
              name: kotlin.String,
            )
            """.trimIndent() + "\n",
            with.finish().toString(),
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
