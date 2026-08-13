package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallsTest {
    private val x = expression("x")

    @Test
    fun `member call and property access`() {
        assertEquals("x.isNotEmpty()", x.call("isNotEmpty").toString())
        assertEquals("x.length", x.prop("length").toString())
        assertEquals("x?.isNotEmpty()", x.safeCall("isNotEmpty").toString())
        assertEquals("x?.length", x.safeProp("length").toString())
    }

    @Test
    fun `call arguments are comma separated`() {
        assertEquals("x.substring(0, 3)", x.call("substring", 0.lit, 3.lit).toString())
        assertEquals("""x.startsWith("a")""", x.call("startsWith", "a".lit).toString())
    }

    @Test
    fun `receiverless call emits no import`() {
        assertEquals("calculate()", call("calculate").toString())
    }

    @Test
    fun `member call resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", call(member("kotlin.collections", "listOf"), 1.lit).code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import kotlin.collections.listOf

            public fun f() {
              listOf(1)
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `invoke calls the value itself`() {
        assertEquals("x()", x().toString())
        assertEquals("x(1)", x(1.lit).toString())
        assertEquals("(a ?: b)(1)", (expression("a") elvis expression("b"))(1.lit).toString())
    }

    @Test
    fun `calls bind tighter than arithmetic`() {
        assertEquals("x.length + 1", (x.prop("length") + 1.lit).toString())
        assertEquals("(x + 1).toString()", (x + 1.lit).call("toString").toString())
    }

    @Test
    fun `a call has no known return type`() {
        assertNull(x.call("foo").type)
    }
}
