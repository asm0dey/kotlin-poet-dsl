package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The README, executed.**
 *
 * A README is a claim about an API, and it is the claim that rots first: it is written once, read by
 * everyone, and checked by nothing. This project's whole method is that a claim is checked against a
 * compiler rather than against the rule it describes, and the README is not exempt from it.
 *
 * So every code block in `README.md` is extracted from the file by marker and put through the real
 * thing. The worked example is compiled **and run**, and the output it produces is compared with the
 * block the README says it produces, character for character; that output is then compiled itself,
 * with a stub supplying the three annotation types it references. The shapes block is compiled. The
 * two refusal messages are produced by running the calls they describe and compared with what the
 * README prints, whitespace-normalised so the file may wrap them.
 *
 * The blocks are read from the file rather than duplicated here on purpose. A copy in this file that
 * agreed with the compiler would say nothing about the copy a reader sees.
 */
@OptIn(ExperimentalCompilerApi::class)
class ReadmeTest {
    private val readme: String = File("README.md").also {
        assertTrue(it.isFile, "README.md not found; the working directory is ${File(".").absolutePath}")
    }.readText()

    /**
     * The fenced block that follows an HTML-comment marker. HTML comments are invisible in rendered
     * Markdown, so the file reads as prose and this test still has an anchor that cannot be moved by
     * editing a heading.
     */
    private fun block(marker: String): String {
        val at = readme.indexOf("<!-- $marker -->")
        assertTrue(at >= 0, "no <!-- $marker --> in README.md")
        val open = readme.indexOf("```", at)
        val bodyStart = readme.indexOf('\n', open) + 1
        val close = readme.indexOf("\n```", bodyStart)
        assertTrue(close > bodyStart, "the block after <!-- $marker --> is unterminated")
        return readme.substring(bodyStart, close + 1)
    }

    private fun normalised(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * The worked example: compiled, loaded, invoked, and its result compared with the output block.
     *
     * `compile` puts the DSL on the snippet's classpath and names the file `Generated.kt`, so the
     * top-level function lands on `GeneratedKt` in the default package.
     */
    @Test
    fun `the README's example compiles, runs, and renders what the README says it renders`() {
        val snippet = block("readme-example")
        val compiled = compile(snippet)
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.exitCode, "$snippet\n${compiled.messages}")

        val rendered = compiled.classLoader
            .loadClass("GeneratedKt")
            .getMethod("user")
            .invoke(null) as String
        assertEquals(block("readme-output"), rendered)
    }

    /**
     * …and the rendered output is itself Kotlin, which is the claim the example exists to make. The
     * three annotation types are declared in a second source file rather than stubbed away, so that
     * `@NamedEntityGraph(attributeNodes = [...])` is checked against a real `Array<NamedAttributeNode>`
     * parameter and not merely parsed.
     */
    @Test
    fun `the README's rendered output compiles`() {
        val jpa = SourceFile.kotlin(
            "Jpa.kt",
            """
            package com.example.jpa

            annotation class Entity

            annotation class NamedAttributeNode(val value: String)

            @Target(AnnotationTarget.CLASS)
            annotation class NamedEntityGraph(
                val name: String,
                val attributeNodes: Array<NamedAttributeNode> = [],
            )
            """.trimIndent(),
        )
        val output = block("readme-output")
        val result = KotlinCompilation().apply {
            sources = listOf(jpa, SourceFile.kotlin("User.kt", output))
            inheritClassPath = true
            jvmTarget = "17"
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "$output\n${result.messages}")
    }

    /**
     * The tour of the three scopes is real API, so it is compiled — and then run, and what it
     * renders is compiled too. A snippet that type-checks against this DSL and emits Kotlin that
     * does not compile would be the exact failure the README claims does not happen.
     */
    @Test
    fun `the README's shapes block compiles, and so does what it renders`() {
        val snippet = block("readme-shapes")
        val compiled = compile(snippet)
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.exitCode, "$snippet\n${compiled.messages}")
        val rendered = compiled.classLoader.loadClass("GeneratedKt").getMethod("shapes").invoke(null) as String
        assertCompiles(rendered)
    }

    /**
     * The two refusals the README quotes, produced rather than transcribed. A README that prints a
     * message the library does not print is exactly the defect this project has shipped three times
     * — a right verdict with an invented citation — and it is worse in a README, where nobody can
     * check it against anything.
     */
    @Test
    fun `the README quotes refusal messages this DSL actually produces`() {
        val inner = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`(KModifier.INNER, "N") { } }
        }
        assertEquals(normalised(block("readme-refusal-inner")), normalised(inner.message!!))

        val annotationMember = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`(KModifier.ANNOTATION, "A") {
                    `fun`("f", returns = com.squareup.kotlinpoet.INT) { ret(1.lit) }
                }
            }
        }
        assertEquals(normalised(block("readme-refusal-annotation")), normalised(annotationMember.message!!))
    }

    /**
     * The three counts the README states as facts about the artifact rather than about the language:
     * the public surface, the shadow set, and the size of the pair census. Each is read from the
     * thing it describes.
     */
    @Test
    fun `the README's counts match the artifact`() {
        val dump = File("api/kotlin-poet-dsl.api")
        assertTrue(dump.isFile, "the API dump is missing; `./gradlew apiDump`")
        val members = dump.readLines().filter { it.startsWith("\t") }
            .filterNot { " synthetic " in " ${it.trim()} " || "\$default" in it }
        assertEquals(774, members.size, "the README says 774 public declarations")
        assertTrue("774 public declarations" in readme, "the README's surface count moved")

        val shadows = Class.forName("site.asm0dey.poetdsl.ShadowsKt")
            .declaredMethods
            .count { it.getAnnotation(Deprecated::class.java) != null }
        assertEquals(142, shadows)
        // The README says "eleven constructs", which is the number of distinct names behind those
        // 142 overloads. A value-class parameter mangles the JVM name with a `-hash` suffix, so the
        // name is taken up to the first dash — no construct in this DSL has one.
        val names = Class.forName("site.asm0dey.poetdsl.ShadowsKt")
            .declaredMethods
            .filter { it.getAnnotation(Deprecated::class.java) != null }
            .map { it.name.substringBefore('-').substringBefore('$') }
            .toSet()
        assertEquals(11, names.size, names.sorted().toString())
        assertTrue("Eleven constructs" in readme, "the README's shadow-construct count moved")
    }
}
