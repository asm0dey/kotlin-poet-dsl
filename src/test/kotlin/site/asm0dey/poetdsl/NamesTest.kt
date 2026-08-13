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

    /**
     * [NameScope.isTaken] recurses to the root with no depth limit, so a name taken two or more
     * levels up must still be seen. Only the parent→child hop was pinned before.
     */
    @Test
    fun `a name taken by a grandparent is still seen`() {
        val root = NameScope(null)
        root.unique("item")
        val grandchild = root.child().child()
        assertTrue(grandchild.isTaken("item"))
        assertEquals("item2", grandchild.unique("item"))
        // The middle scope declared nothing, and the grandchild's own name does not leak upwards.
        assertFalse(root.isTaken("item2"))
    }

    /**
     * [NameScope.declare] was only ever exercised through [NameScope.unique]. It is also called
     * on its own wherever a name is fixed rather than requested, so its own effect is pinned here:
     * it registers unconditionally, without uniquifying.
     */
    @Test
    fun `declare registers a name without renaming it`() {
        val scope = NameScope(null)
        scope.declare("item")
        assertTrue(scope.isTaken("item"))
        assertEquals("item2", scope.unique("item"))
        // Declaring the same name twice is not an error and does not invent a second entry.
        scope.declare("item")
        assertEquals("item3", scope.unique("item"))
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

    /**
     * The suffix rules have no irregular-plural table, by decision — see [singularize]'s doc.
     * These are the answers that decision produces; they are pinned so that adding a table later
     * is a deliberate change rather than an accident, and so the limit is visible to a reader.
     */
    @Test
    fun `singularization does not handle irregular plurals`() {
        assertEquals("sery", singularize("series"))
        assertEquals("children", singularize("children"))
        assertEquals("person", singularize("persons"))
    }

    /** The fallback is always a legal identifier, however odd the singular reads. */
    @Test
    fun `an unhelpful singular still renders a unique loop variable`() {
        assertEquals(
            "val children = load()\nfor (children2 in children) {\n  println(children2)\n}\n",
            renderBlock {
                val xs = `val`("children", init = call("load"))
                `for`(xs) { child -> +call("println", child) }
            },
        )
    }
}
