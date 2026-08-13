package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `collisions get a numeric suffix`() {
        val scope = NameScope(null)
        assertEquals("item", scope.unique("item"))
        assertEquals("item2", scope.unique("item"))
        assertEquals("item3", scope.unique("item"))
    }

    @Test
    fun `a child scope sees names taken by its parent`() {
        val parent = NameScope(null)
        parent.unique("item")
        val child = parent.child()
        assertEquals("item2", child.unique("item"))
        assertTrue(child.isTaken("item"))
        assertFalse(parent.isTaken("item2"))
    }

    @Test
    fun `scope ancestry`() {
        val root = ScopeId(null, "file")
        val type = root.child("type")
        val block = type.child("block")
        val sibling = root.child("other")
        assertTrue(root.isAncestorOf(block))
        assertTrue(block.isAncestorOf(block))
        assertFalse(block.isAncestorOf(root))
        assertFalse(sibling.isAncestorOf(block))
        assertFalse(root.isAncestorOf(null))
    }

    @Test
    fun `singularization`() {
        assertEquals("item", singularize("items"))
        assertEquals("user", singularize("users"))
        assertEquals("box", singularize("boxes"))
        assertEquals("entry", singularize("entries"))
        assertEquals("data", singularize("data"))
        assertEquals("item", singularize(""))
    }
}
