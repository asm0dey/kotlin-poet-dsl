package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainTest {
    @Test
    fun `kotlinpoet renders a file`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(FunSpec.builder("noop").build())
            .build()
        assertEquals(
            """
            package com.example

            public fun noop() {
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `use site target ALL exists in the pinned kotlinpoet`() {
        assertTrue(AnnotationSpec.UseSiteTarget.entries.any { it.name == "ALL" })
    }

    @Test
    fun `context parameters compile without a flag`() {
        assertEquals("ok", withMarker())
    }

    /**
     * **The in-suite compiler is the version this project quotes**, and this is what says so.
     *
     * Two Kotlin versions were in play and no round before E3 stated it: kctfork 0.13.0 pins
     * `kotlin-compiler-embeddable:2.4.0`, so every `assertCompiles` ran the *older* frontend, while
     * every diagnostic quoted in D36-D43 was measured at a command line on 2.4.10 — and the JS and
     * Wasm rows fed 2.4.10 klibs to a 2.4.0 frontend. The deviations file's version note recorded the
     * split and left the decision here.
     *
     * E3 aligned them, and the argument is the klib mismatch rather than a preference: nothing was
     * testing 2.4.0 consistently, so "the suite tests the conservative older frontend" was not true
     * of the non-JVM half. The whole suite passed on 2.4.10 with no expectation changed.
     *
     * This test fails if a kctfork bump, a plugin bump or a dropped dependency constraint reopens the
     * split — which is the only outcome the version note called indefensible.
     */
    @Test
    fun `the in-suite compiler is the version this project quotes`() {
        assertEquals(
            KotlinVersion.CURRENT.toString(),
            org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION?.substringBefore('-'),
            "the embedded compiler and the Kotlin the suite is built with have drifted apart",
        )
    }
}

class Marker(val value: String)

context(m: Marker)
fun readMarker(): String = m.value

fun withMarker(): String = with(Marker("ok")) { readMarker() }
