package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.TypeSpec
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

annotation class Email
annotation class SerialName(val value: String)

class AnnotationsTest {
    @Test
    fun `marker annotation`() {
        assertEquals("@site.asm0dey.poetdsl.Email", annotation<Email>().list.single().toString())
    }

    @Test
    fun `named arguments keep Expr placeholders`() {
        assertEquals(
            """@site.asm0dey.poetdsl.SerialName(value = "user_name")""",
            annotation<SerialName>("value" to "user_name".lit).list.single().toString(),
        )
    }

    @Test
    fun `use site target renders`() {
        assertEquals(
            "@set:site.asm0dey.poetdsl.Email",
            annotation<Email>(UseSiteTarget.SET).list.single().toString(),
        )
    }

    /**
     * Closes spec open question 1: `AnnotationSpec.UseSiteTarget.ALL` (the `@all:` meta-target
     * added in Kotlin 2.2) exists natively in KotlinPoet 2.3.0. It is marked
     * `@ExperimentalKotlinPoetApi` by KotlinPoet itself, so the real, documented opt-in is used
     * here rather than a shim — no workaround needed to prove it exists and renders correctly.
     */
    @OptIn(ExperimentalKotlinPoetApi::class)
    @Test
    fun `the all meta target is available without a shim`() {
        assertEquals(
            "@all:site.asm0dey.poetdsl.Email",
            annotation<Email>(UseSiteTarget.ALL).list.single().toString(),
        )
    }

    @Test
    fun `annotations combine with plus`() {
        val combined = annotation<Email>(UseSiteTarget.SET) + annotation<SerialName>("value" to "x".lit)
        assertEquals(2, combined.list.size)
    }

    @Test
    fun `runtime known annotation type`() {
        assertEquals(
            """@com.example.Generated("gen")""",
            annotation(ClassName("com.example", "Generated"), args = arrayOf("gen".lit)).list.single().toString(),
        )
    }

    @Test
    fun `file level annotation defaults to the FILE target`() {
        val out = file("com.example", "Api") {
            annotate<SerialName>("value" to "ApiKt".lit)
        }.toString()
        assertEquals(true, out.startsWith("@file:"))
    }

    /**
     * A zero-argument call to each pair of overloads must resolve unambiguously (D2). If
     * either pair were still ambiguous for a zero-argument call, this file would fail to
     * compile — the calls below only need to compile and run, no assertion recreates that.
     */
    @Test
    fun `zero argument calls resolve unambiguously per D2`() {
        assertEquals(1, annotation<Email>().list.size)
        assertEquals(1, annotation<Email>(UseSiteTarget.SET).list.size)
        assertEquals(1, ann<Email>().list.size)
        assertEquals(1, ann<Email>(UseSiteTarget.SET).list.size)
    }

    /**
     * `%T` must reach KotlinPoet unrendered from inside an annotation *argument*, not just
     * from the annotation's own type, or the import for a type referenced only inside a
     * member value would never resolve. A string match on `toString()` cannot tell "the
     * import is missing" apart from "the import happens not to be needed here" — only an
     * end-to-end compile with kctfork proves the import resolved and the code is valid Kotlin.
     */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `a type placeholder inside an annotation argument survives and its import resolves`() {
        val labels = ClassName("com.example.util", "Labels")
        val described = annotation(
            ClassName("com.example.util", "Described"),
            args = arrayOf(expression("%T.NAME", labels)),
        )

        val rendered = file("com.example.app", "Api") {
            val type = TypeScope(TypeSpec.classBuilder("Foo"), names.child(), id.child("class Foo"))
            with(type) { addAnnotation(described.list.single()) }
            +type.finish()
        }.toString()

        assertTrue(rendered.contains("import com.example.util.Described"), rendered)
        assertTrue(rendered.contains("import com.example.util.Labels"), rendered)
        assertTrue(rendered.contains("@Described(Labels.NAME)"), rendered)

        val util = SourceFile.kotlin(
            "Util.kt",
            """
            package com.example.util

            annotation class Described(val value: String)

            object Labels {
                const val NAME: String = "tag"
            }
            """.trimIndent(),
        )

        val result = KotlinCompilation().apply {
            sources = listOf(util, SourceFile.kotlin("Api.kt", rendered))
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
