package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.LONG
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deviation D26: `superclass` and `superinterface`.
 *
 * The two places superclass constructor arguments can legally go — the class header when the class
 * has a primary constructor, a secondary constructor's `: super(…)` when it does not — are the whole
 * subject here. The second is D25's `` `super` ``; what this file pins down is the first, and the
 * rejection of the combination that would render Kotlin the compiler refuses.
 */
class SupertypesTest {
    private val base = ClassName("com.example", "Base")
    private val runnable = ClassName("java.lang", "Runnable")
    private val closeable = ClassName("java.io", "Closeable")

    @Test
    fun `a class extends a superclass and implements interfaces`() {
        assertEquals(
            """
            package com.example

            import java.io.Closeable
            import java.lang.Runnable

            public class Impl : Base(), Runnable, Closeable

            """.trimIndent(),
            file("com.example", "Impl") {
                `class`("Impl") {
                    superclass(base)
                    superinterface(runnable)
                    superinterface(closeable)
                }
            }.toString(),
        )
    }

    /** The header-argument case: the arguments name the class's own primary-constructor parameters. */
    @Test
    fun `superclass arguments may name the primary constructor parameters`() {
        assertEquals(
            """
            package com.example

            import kotlin.Long

            public class User(
              public val id: Long,
            ) : Base(id, 1)

            """.trimIndent(),
            file("com.example", "User") {
                `class`("User", param(VAL, "id", LONG)) { id -> superclass(base, id, 1.lit) }
            }.toString(),
        )
    }

    /** The in-body `constructorParam` reaches the same place, written in either order. */
    @Test
    fun `superclass arguments work with an in body primary constructor in both orders`() {
        val paramFirst = file("com.example", "A") {
            `class`("User") {
                val id = constructorParam(VAL, "id", LONG)
                superclass(base, id)
            }
        }.toString()
        assertTrue("public class User(\n  public val id: Long,\n) : Base(id)" in paramFirst, paramFirst)
    }

    /** An `object` has no primary constructor of its own, and its header call is still legal Kotlin. */
    @Test
    fun `an object can extend a class with arguments`() {
        val out = file("com.example", "A") { `object`("Registry") { superclass(base, 1.lit) } }.toString()
        assertTrue("public object Registry : Base(1)" in out, out)
    }

    /** An interface extends interfaces — `superinterface`, in an interface body. */
    @Test
    fun `an interface extends another interface`() {
        val out = file("com.example", "A") {
            `interface`("Service") { superinterface(runnable) }
        }.toString()
        assertTrue("public interface Service : Runnable" in out, out)
    }

    @Test
    fun `an interface has no superclass`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") { `interface`("Service") { superclass(base) } }
        }.exceptionOrNull()
        assertEquals(
            "superclass: an interface has no superclass. Use superinterface to extend another interface.",
            (thrown as IllegalStateException).message,
        )
    }

    /**
     * `TypeSpec.emit` filters `ANY` out of the supertype list at `TypeSpec.kt:235`, *before* it
     * decides anything about parentheses — so `superclass(ANY, 1.lit)` rendered `public class N` and
     * the argument reached no output at all, in every container. Silent partial output, which Global
     * Constraint 26 forbids as loudly as invalid output, and the same defect D40 row 9 recorded for
     * an `expect` type's superclass arguments, one filter earlier and with no `expect` involved.
     *
     * `class N : Any()` is clean on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` alike (measured), so
     * this is a render gap. It is also the cheapest one in the project: the remedy costs nothing,
     * because `class N : Any()` and `class N` are the same class.
     */
    @Test
    fun `kotlin Any takes no superclass arguments`() {
        val message =
            "superclass: kotlin.Any is given constructor arguments, and KotlinPoet 2.3.0 renders " +
                "neither — `TypeSpec.emit` filters `ANY` out of the supertype list at " +
                "`TypeSpec.kt:235`, before it decides anything about parentheses, so the arguments " +
                "reach no output at all. `class N : Any()` is valid on the JVM, on Kotlin/JS and on " +
                "Kotlin/Wasm alike, which makes this a backend gap and not a language rule. Drop " +
                "the arguments: a class with no declared supertype already extends `Any`, so " +
                "`: Any()` and nothing at all are the same class."
        for (build in listOf<() -> Unit>(
            { file("com.example", "A") { `class`("N") { superclass(ANY, 1.lit) } } },
            { file("com.example", "A") { `object`("O") { superclass(ANY, 1.lit) } } },
            { file("com.example", "A") { `class`(EXPECT, "E") { `class`("N") { superclass(ANY, 1.lit) } } } },
        )) {
            assertEquals(message, (kotlin.runCatching { build() }.exceptionOrNull() as IllegalStateException).message)
        }
        // The control: the bare `superclass(ANY)` drops nothing — `Any` is every class's supertype
        // already — and still renders, inside an `expect` container and outside it.
        assertEquals(
            """
            package com.example

            public class N

            public expect class E {
              public class M
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`("N") { superclass(ANY) }
                `class`(EXPECT, "E") { `class`("M") { superclass(ANY) } }
            }.toString(),
        )
    }

    @Test
    fun `a class extends only one class`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superclass(base)
                    superclass(runnable)
                }
            }
        }.exceptionOrNull()
        assertEquals(
            "superclass: a class can only extend one class, and this one already does.",
            (thrown as IllegalStateException).message,
        )
    }

    /** KotlinPoet's map of superinterfaces would swallow the second one; this says so instead. */
    @Test
    fun `the same interface cannot be implemented twice`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superinterface(runnable)
                    superinterface(runnable)
                }
            }
        }.exceptionOrNull()
        assertEquals(
            "superinterface: this class already implements java.lang.Runnable.",
            (thrown as IllegalStateException).message,
        )
    }

    /**
     * The invalid combination, in both writing orders: header arguments make the header a primary
     * constructor, and a secondary constructor would then have to delegate to it with `: this(…)`.
     * KotlinPoet rejects the same pair, but only at `build()` and without naming a construct.
     */
    @Test
    fun `superclass arguments and a secondary constructor are rejected in both orders`() {
        val argsFirst = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superclass(base, 1.lit)
                    `constructor`(param("x", INT)) { _ -> }
                }
            }
        }.exceptionOrNull()
        val ctorFirst = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    `constructor`(param("x", INT)) { _ -> }
                    superclass(base, 1.lit)
                }
            }
        }.exceptionOrNull()
        for (thrown in listOf(argsFirst, ctorFirst)) {
            assertTrue(thrown is IllegalStateException, "$thrown")
            assertEquals(superclassArgsPlusSecondary("class"), thrown.message)
        }
    }

    /**
     * The same message on an `object` says "a named object", not "a class": it interpolates
     * `kindName` like its two neighbouring `superclass` messages, which is the whole reason it is a
     * function and not a constant. Unreachable through `` `constructor` ``'s half of the guard now
     * that an object is refused a constructor outright, so `superclass`'s half is where it shows.
     */
    @Test
    fun `the header-arguments message names the kind it fired on`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `object`("O") {
                    superclass(base, 1.lit)
                    superclass(runnable)
                }
            }
        }.exceptionOrNull()
        assertEquals(
            "superclass: a named object can only extend one class, and this one already does.",
            (thrown as IllegalStateException).message,
        )
        assertTrue("a named object cannot pass superclass" in superclassArgsPlusSecondary("named object"))
    }

    /**
     * Global Constraint 26: neither an object nor an interface has a constructor, so
     * `` `constructor` `` in one is rejected instead of rendering `public object O { public
     * constructor(x: Int) }`, which `kotlinc` refuses. Pre-existing, and cheap to close now that
     * `beginSecondaryConstructor` is the single place every overload passes through.
     */
    @Test
    fun `only a class can declare a constructor`() {
        for ((kind, build) in listOf<Pair<String, () -> Unit>>(
            "named object" to { file("com.example", "A") { `object`("O") { `constructor`(param("x", INT)) { _ -> } } } },
            "interface" to { file("com.example", "A") { `interface`("I") { `constructor`(param("x", INT)) { _ -> } } } },
        )) {
            val thrown = kotlin.runCatching { build() }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException, "$kind: $thrown")
            assertEquals("constructor: a $kind cannot declare a constructor; only a class can.", thrown.message)
        }
    }

    /**
     * Without arguments there is no header call to delegate to, so a secondary constructor is fine
     * and KotlinPoet writes the supertype bare — which is exactly the shape D25's `` `super` `` will
     * fill in.
     */
    @Test
    fun `a superclass with no arguments coexists with a secondary constructor`() {
        val out = file("com.example", "A") {
            `class`("C") {
                superclass(base)
                `constructor`(param("x", INT)) { _ -> }
            }
        }.toString()
        assertTrue("public class C : Base {" in out, out)
        assertTrue("public constructor(x: Int)" in out, out)
    }

    /** The rendered Kotlin is what matters, so `kotlinc` gets the last word on the whole shape. */
    @Test
    fun `a generated class with supertypes compiles`() {
        assertCompiles(
            file("com.example", "Supertypes") {
                `class`(ABSTRACT, "Base", param(VAL, "id", LONG)) { _ -> }
                `interface`("Greeter") { `fun`(ABSTRACT, "greet") { } }
                `class`("Impl", param(VAL, "tag", LONG)) { tag ->
                    superclass(ClassName("com.example", "Base"), tag)
                    superinterface(ClassName("com.example", "Greeter"))
                    `fun`(OVERRIDE, "greet") { +call("println", tag) }
                }
            }.toString(),
        )
    }
}
