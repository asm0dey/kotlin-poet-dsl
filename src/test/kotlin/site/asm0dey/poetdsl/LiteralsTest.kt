@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiteralsTest {
    @Test
    fun `numeric and boolean literals`() {
        assertEquals("1", 1.literal.toString())
        assertEquals("1", 1.lit.toString())
        assertEquals("true", true.literal.toString())
        assertEquals("2.5", 2.5.literal.toString())
    }

    @Test
    fun `finite double and float values still render as before`() {
        assertEquals("2.5", 2.5.literal.toString())
        assertEquals("-1.0", (-1.0).literal.toString())
        assertEquals("2.5F", 2.5f.literal.toString())
        assertEquals("-1.0F", (-1.0f).literal.toString())
    }

    @Test
    fun `Double literal rejects NaN and Infinity naming the construct`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalStateException> { value.literal }
            assertTrue(ex.message!!.contains("Double.literal"), ex.message)
        }
    }

    @Test
    fun `Float literal rejects NaN and Infinity naming the construct`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalStateException> { value.literal }
            assertTrue(ex.message!!.contains("Float.literal"), ex.message)
        }
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
    fun `char literal - lone surrogate uses unicode escape`() {
        assertEquals("'\\ud800'", '\uD800'.literal.toString())
        assertEquals("'\\udfff'", '\uDFFF'.literal.toString())
    }

    @Test
    fun `char literal - escaped output actually compiles`() {
        val chars = listOf('a', '\\', '\'', '\n', '\r', '\t', '\b', '\u0000', '"', '$', '\uD800')
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

    /**
     * Deviation D28: a computed `List<Expr>` becomes one bracketed expression, with the
     * non-breaking separator every other joined list in this DSL uses.
     */
    @Test
    fun `array literal joins a computed list`() {
        assertEquals("[1, 2, 3]", arrayLiteral(listOf(1.lit, 2.lit, 3.lit)).code.toString())
        assertEquals("[1]", arrayLit(listOf(1.lit)).code.toString())
        assertEquals("[]", arrayLiteral(emptyList()).code.toString())
    }

    /**
     * The separator is KotlinPoet's non-breaking `·`, not a plain space: a joined list far past the
     * 100-column wrap limit still renders on one line, where a breakable space would have been torn
     * apart mid-literal.
     *
     * Rendered into an annotation argument, which is the only position Kotlin has a collection
     * literal in — `val xs = [1, 2, 3]` is `e: Array literals outside of annotations are
     * unsupported`, pinned below. `@Suppress(names = […])` is a real vararg-of-`String` annotation,
     * so the output compiles as well as renders.
     */
    @Test
    fun `the array literal separator does not wrap`() {
        val long = arrayLiteral((1..20).map { "element$it".lit })
        val out = file("com.example", "Api") {
            `fun`(annotation<Suppress>("names" to long), "f") { }
        }.toString()
        assertTrue(out.lines().any { """["element1",""" in it && """"element20"]""" in it }, out)
        assertCompiles(out)
    }

    /**
     * Why the KDoc's first line says "valid only as an annotation argument": the DSL will build the
     * literal anywhere, and anywhere else is not Kotlin. Guarding it would need a marker on every
     * [Expr] for one narrow case, so the restriction is documented rather than enforced — and this
     * is kotlinc's word on what is being documented.
     */
    @Test
    fun `kotlinc rejects a collection literal outside an annotation`() {
        val result = compile("val xs = [1, 2, 3]")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("rray literals outside of annotations are unsupported" in result.messages, result.messages)
    }

    /**
     * The spread route the D28 KDoc names, exactly as it has to be written: `target` sits between
     * the annotation type and the vararg, and Kotlin binds positional arguments by declared order
     * regardless of defaults, so `annotation(cls, *args)` does not compile and the `null` target is
     * not optional. That route is the stated reason for shipping one bracketed helper instead of a
     * comma-joined sibling, so it is pinned here.
     */
    @Test
    fun `a computed list reaches a positional annotation argument by spread`() {
        val cls = ClassName("com.example", "Tags")
        val args = listOf("a".lit, "b".lit)
        assertEquals(
            """@com.example.Tags("a", "b")""",
            annotation(cls, null, *args.toTypedArray()).list.single().toString(),
        )
    }

    /**
     * The negative half of the KDoc claim above: with no explicit `null` target, the spread's first
     * element binds positionally to `target: UseSiteTarget?` instead of the vararg, and fails to
     * type-check. Only the positive form (`annotation(cls, null, *args)`) had a test before this.
     */
    @Test
    fun `annotation with a spread list and no explicit target does not compile`() {
        val result = compileDsl(
            """
            val cls = ClassName("com.example", "Tags")
            val args = listOf("a".lit, "b".lit).toTypedArray()
            val ann = annotation(cls, *args)
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.ClassName"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    /** `%T`/`%M` in the elements survive the join, so imports still resolve at render time. */
    @Test
    fun `array literal keeps placeholders`() {
        val nan = ClassName("com.example", "Node")
        val joined = arrayLiteral(listOf("a", "b").map { expression("%T(%L)", nan, it.lit) })
        assertEquals("""[com.example.Node("a"), com.example.Node("b")]""", joined.code.toString())
    }

    /**
     * The elements' scopes are carried through, exactly as `call` carries its arguments' — so a
     * joined list is judged where it is emitted, and a list of literals carries none at all, which
     * is what keeps it usable as an annotation argument (Task 21's B1 guard).
     */
    @Test
    fun `array literal carries its elements' scopes and nothing more`() {
        lateinit var handle: Expr
        stmts { handle = `val`("x", init = 1.lit) }
        assertEquals(handle.usedScopes, arrayLiteral(listOf(1.lit, handle)).usedScopes)
        assertEquals(emptySet(), arrayLiteral(listOf(1.lit, "a".lit)).usedScopes)
    }
}
