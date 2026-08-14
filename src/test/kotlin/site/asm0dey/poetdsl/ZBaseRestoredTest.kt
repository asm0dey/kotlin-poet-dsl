package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.EXPECT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Base-restored evidence for the two Important findings of this round. Byte-identical at `68df28f`,
 * at `0a9f6d1` and here: each assertion **passed** at `68df28f`, **failed** at `0a9f6d1`, and passes
 * again after the fix.
 *
 * FIX1 is deliberately type-only. The refusal it restores had a different message at `68df28f` (the
 * generic `expect` one), and the claim being restored is the refusal, not the wording.
 */
class ZBaseRestoredTest {
    @Test
    fun `FIX1 a var parameter of a nested annotation class is refused`() {
        assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`(EXPECT, "E") { `class`(ANNOTATION, "N") { constructorParam(ParamKind.VAR, "x", INT) } }
            }
        }
    }

    @Test
    fun `FIX2 a nested supertype of kotlin Any inside an expect type still renders`() {
        assertEquals(
            """
            package com.example

            public expect class E {
              public class N
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") { `class`("N") { superclass(ANY) } }
            }.toString(),
        )
    }
}
