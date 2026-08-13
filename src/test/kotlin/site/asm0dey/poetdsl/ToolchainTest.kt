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
}

class Marker(val value: String)

context(m: Marker)
fun readMarker(): String = m.value

fun withMarker(): String = with(Marker("ok")) { readMarker() }
