@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

class LiteralsTest {
    @Test
    fun `numeric and boolean literals`() {
        assertEquals("1", 1.literal.toString())
        assertEquals("1", 1.lit.toString())
        assertEquals("true", true.literal.toString())
        assertEquals("2.5", 2.5.literal.toString())
    }

    @Test
    fun `string literals are escaped`() {
        assertEquals("\"a\\\"b\"", "a\"b".literal.toString())
        assertEquals("\"tab\\there\"", "tab\there".literal.toString())
    }

    @Test
    fun `char literal - ordinary character is unescaped`() {
        assertEquals("'a'", 'a'.literal.toString())
        assertEquals("'a'", 'a'.lit.toString())
    }

    @Test
    fun `char literal - backslash and quote are escaped`() {
        assertEquals("'\\\\'", '\\'.literal.toString())
        assertEquals("'\\''", '\''.literal.toString())
    }

    @Test
    fun `char literal - standard escapes`() {
        assertEquals("'\\n'", '\n'.literal.toString())
        assertEquals("'\\r'", '\r'.literal.toString())
        assertEquals("'\\t'", '\t'.literal.toString())
        assertEquals("'\\b'", '\b'.literal.toString())
    }

    @Test
    fun `char literal - other non-printable characters use unicode escape`() {
        assertEquals("'\\u0000'", '\u0000'.literal.toString())
        assertEquals("'\\u001f'", '\u001f'.literal.toString())
        assertEquals("'\\u007f'", '\u007f'.literal.toString())
    }

    @Test
    fun `char literal - double quote and dollar sign need no escaping`() {
        assertEquals("'\"'", '"'.literal.toString())
        assertEquals("'$'", '$'.literal.toString())
    }

    @Test
    fun `char literal - escaped output actually compiles`() {
        val chars = listOf('a', '\\', '\'', '\n', '\r', '\t', '\b', '\u0000', '"', '$')
        val fileSpec = FileSpec.builder("com.example", "CharLiterals")
            .addProperties(
                chars.mapIndexed { index, c ->
                    PropertySpec.builder("c$index", com.squareup.kotlinpoet.CHAR)
                        .initializer(c.literal.code)
                        .build()
                },
            )
            .build()

        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("CharLiterals.kt", fileSpec.toString()))
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
    }

    @Test
    fun `null literal`() {
        assertEquals("null", nullLiteral.toString())
        assertEquals("null", nul.toString())
    }

    @Test
    fun `type reference resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", expression("%T()", reference<StringBuilder>()).code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import java.lang.StringBuilder

            public fun f() {
              StringBuilder()
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `member reference resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", member("kotlin.collections", "listOf").expression().code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import kotlin.collections.listOf

            public fun f() {
              listOf
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `escape hatch keeps placeholders and unwraps Expr arguments`() {
        val e = expression(
            "%L.filterIsInstance<%T>()",
            expression("xs"),
            reference<CharSequence>(),
            prec = Prec.POSTFIX,
        )
        assertEquals("xs.filterIsInstance<kotlin.CharSequence>()", e.code.toString())
        assertEquals(Prec.POSTFIX, e.prec)
    }

    @Test
    fun `nullable sugar`() {
        assertEquals("kotlin.String?", STRING.nullable.toString())
    }
}
