package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * E2b item 4: the detached [propertySpec] can build a `var`.
 *
 * It could not before, and the shape of the gap is why this had to land now rather than after the
 * API lock: with no `mutable` slot the builder only ever produced a `val`, so E2a gave it a `getter`
 * and deliberately no `setter` — `PropertySpec.build` is `require(mutable || setter == null)`, so a
 * setter could only have been an error. That argued from the gap instead of closing it.
 */
class PropertySpecTest {
    @Test
    fun `propertySpec builds a var`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var counter: Int = 0

            """.trimIndent(),
            file("com.example", "A") {
                +propertySpec(name = "counter", type = INT, init = 0.lit, mutable = true)
            }.toString(),
        )
    }

    @Test
    fun `propertySpec builds a var with both accessors`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var counter: Int
              get() = 1
              set(next) {
                println(next)
              }

            """.trimIndent(),
            // The setter deliberately does *not* write `field`: with a custom getter that ignores
            // it, a `field` write would give the property a backing field and therefore demand an
            // initializer — the undecidable half of the rule D34 stops short of.
            file("com.example", "A") {
                +propertySpec(
                    name = "counter",
                    type = INT,
                    mutable = true,
                    setterParam = "next",
                    setter = { next -> +call("println", next) },
                ) { ret(1.lit) }
            }.toString(),
        )
    }

    @Test
    fun `propertySpec rejects a setter on a val, naming itself`() {
        val e = assertFailsWith<IllegalStateException> {
            propertySpec(name = "x", type = INT, init = 1.lit, setter = { })
        }
        assertEquals(
            "propertySpec: 'x' is a `val` and has no setter. Declare it with `var`, or drop the setter.",
            e.message,
        )
    }

    @Test
    fun `propertySpec rejects a var with exactly one custom accessor`() {
        val e = assertFailsWith<IllegalStateException> {
            propertySpec(name = "x", type = INT, mutable = true) { ret(1.lit) }
        }
        assertEquals(
            "propertySpec: 'x' has a getter but no setter, so Kotlin generates the setter, which " +
                "needs a backing field, which needs an initializer. Add an initializer, or write " +
                "the setter as well.",
            e.message,
        )
    }

    /**
     * The render assertions above are not enough on their own: a `var` with two custom accessors and
     * no initializer, and a mutable extension property, both render cleanly and are exactly the pair
     * whose validity depends on whether an accessor touches the backing field.
     */
    @Test
    fun `what propertySpec builds as a var compiles`() {
        val spec = file("com.example", "Detached") {
            +propertySpec(name = "counter", type = INT, init = 0.lit, mutable = true)
            +propertySpec(
                name = "computed",
                type = INT,
                mutable = true,
                setterParam = "next",
                setter = { next -> +call("println", next) },
            ) { ret(1.lit) }
            +propertySpec(
                name = "head",
                type = INT,
                receiver = STRING,
                mutable = true,
                setter = { },
            ) { ret(0.lit) }
            +propertySpec(name = "stored", type = INT, init = 3.lit, mutable = true, setter = { })
        }
        assertCompiles(spec.toString())
    }

    @Test
    fun `propertySpec builds a mutable extension property`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public var String.head: Int
              get() = 0
              set(`value`) {
              }

            """.trimIndent(),
            file("com.example", "A") {
                +propertySpec(
                    name = "head",
                    type = INT,
                    receiver = STRING,
                    mutable = true,
                    setter = { },
                ) { ret(0.lit) }
            }.toString(),
        )
    }
}
