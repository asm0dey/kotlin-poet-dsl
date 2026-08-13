package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.SUSPEND
import kotlin.test.Test
import kotlin.test.assertEquals

class ModifiersTest {
    @Test
    fun `two modifiers combine`() {
        assertEquals(listOf(SEALED, INTERNAL), (SEALED + INTERNAL).toList())
    }

    @Test
    fun `three modifiers combine`() {
        assertEquals(listOf(PRIVATE, SUSPEND, INTERNAL), (PRIVATE + SUSPEND + INTERNAL).toList())
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(listOf(PRIVATE, SUSPEND), (PRIVATE + SUSPEND + PRIVATE).toList())
    }

    @Test
    fun `null modifiers is an empty list`() {
        assertEquals(emptyList(), (null as Modifiers?).toList())
        assertEquals(listOf(PRIVATE), PRIVATE.toModifiers().toList())
    }
}
