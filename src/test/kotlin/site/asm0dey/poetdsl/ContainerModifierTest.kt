package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.EXTERNAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Whether a modifier is legal **in this container** is a different question from whether the
 * container needs the property to have a value, and until this round the DSL only ever asked it on
 * the `needsValue` branch. `needsValue` is false in an interface body and in every `expect` body, so
 * those two containers validated no modifier at all and rendered, among others:
 *
 * ```
 * public interface I    { public external val x: Int }
 * public expect class E { public external val x: Int }
 * public interface I    { public expect   val x: Int }
 * ```
 *
 * — and the same on the value paths, where the branch is skipped for a different reason:
 * `` `class`("C") { `val`(EXTERNAL, "x", INT, init = 1.lit) } `` rendered
 * `public external val x: Int = 1`.
 *
 * Measured on all three frontends that ship with Kotlin 2.4.10, one file each. Every shape this test
 * refuses is refused by **all three**; see the per-test tables and D37.
 *
 * The boundary is drawn at what is **rendered**, not at what was passed. KotlinPoet hands a
 * `TypeSpec`'s own `EXPECT`/`EXTERNAL` down to its direct members as an *implicit* modifier, so the
 * member's copy is never printed and the render is valid — which is why the two containers in
 * `a container that carries the modifier itself suppresses it` are accepted rather than refused.
 */
class ContainerModifierTest {
    internal fun externalMessage(keyword: String = "val") =
        "`$keyword`: 'x' is EXTERNAL, and this container is not one where that renders something " +
            "any target accepts: a member `external` property is \"non-top-level 'external' " +
            "declaration\" on Kotlin/JS and Kotlin/Wasm and \"modifier 'external' is not applicable " +
            "to 'property'\" on the JVM. Drop EXTERNAL — inside an `external` type Kotlin makes it " +
            "implicit, and at file level it is the one position Kotlin/JS and Kotlin/Wasm accept."

    internal fun expectMessage(keyword: String = "val") =
        "`$keyword`: 'x' is EXPECT, and this container is not one where that renders something any " +
            "target accepts: \"modifier 'expect' is not applicable to 'member property without " +
            "backing field or delegate'\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. Drop " +
            "EXPECT — inside an `expect` type Kotlin makes it implicit, and at file level it is legal."

    /**
     * `EXTERNAL` on a member property, in every container that renders the keyword. All three
     * frontends refuse all of them:
     *
     * ```
     * interface I    { external val a: Int }   jvm  modifier 'external' is not applicable to 'property'.
     *                                          js   non-top-level 'external' declaration.
     *                                          wasm non-top-level 'external' declaration.
     * expect class E { external val a: Int }   jvm  expected declaration cannot be external.
     *                                               modifier 'external' is not applicable to 'property'.
     *                                          js   expected declaration cannot be external.
     *                                               non-top-level 'external' declaration.
     *                                          wasm as js
     * expect class E { class N { external val a: Int } }        as above, all three
     * expect class E { companion object { external val a: Int } } as above, all three
     * expect object O { external val a: Int }                   as above, all three
     * expect interface I { external val a: Int }                as above, all three
     * ```
     */
    @Test
    fun `an external property is refused in every container that renders the keyword`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            externalMessage() to { `interface`("I") { `val`(EXTERNAL, "x", INT) } },
            externalMessage(keyword = "var") to { `interface`("I") { `var`(EXTERNAL, "x", INT) } },
            externalMessage() to { `class`(EXPECT, "E") { `val`(EXTERNAL, "x", INT) } },
            externalMessage() to { `class`(EXPECT, "E") { `class`("N") { `val`(EXTERNAL, "x", INT) } } },
            externalMessage() to { `class`(EXPECT, "E") { companionObject { `val`(EXTERNAL, "x", INT) } } },
            externalMessage() to { `object`(EXPECT, "O") { `val`(EXTERNAL, "x", INT) } },
            externalMessage() to { `interface`(EXPECT, "I") { `val`(EXTERNAL, "x", INT) } },
        ).forEachIndexed { index, (message, position) ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
        // …and the detached `expect` builder, whose body validated nothing either.
        assertEquals(
            externalMessage(),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `val`(EXTERNAL, "x", INT) }
            }.message,
        )
    }

    /**
     * The same modifier on the **value** paths, where `needsValue` is true but the branch is skipped
     * because the property already has a value. Same root cause, and the same three frontends:
     *
     * ```
     * class C  { external val a: Int = 1 }      jvm  modifier 'external' is not applicable to 'property'.
     *                                           js   non-top-level 'external' declaration.
     *                                                wrong initializer of external declaration.
     *                                                    Must be ' = definedExternally'.
     *                                           wasm as js
     * class C  { external val a: Int get() = 1 } jvm modifier 'external' is not applicable to 'property'.
     *                                                external declaration cannot have a body.
     *                                           js   non-top-level 'external' declaration.
     *                                                wrong body of external declaration.
     *                                           wasm as js
     * object O { external val a: Int = 1 }       as the first row, all three
     * ```
     */
    @Test
    fun `an external property with a value is refused too`() {
        listOf<FileScope.() -> Unit>(
            { `class`("C") { `val`(EXTERNAL, "x", INT, init = 1.lit) } },
            { `class`("C") { `val`(EXTERNAL, "x", INT) { ret(1.lit) } } },
            { `class`("C") { `val`(EXTERNAL, "x", INT, by = expression("lazy()")) } },
            { `object`("O") { `val`(EXTERNAL, "x", INT, init = 1.lit) } },
        ).forEachIndexed { index, position ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(externalMessage(), e.message, "position $index")
        }
    }

    /**
     * `EXPECT` on a property, in every container that renders the keyword — which is every container
     * inside a type **except** one whose own builder carries `EXPECT`. All three frontends answer
     * with the same sentence, and it is the exact one `expectAllowed` was introduced to prevent:
     *
     * ```
     * interface I    { expect val a: Int }                       all three:
     * class C        { expect val a: Int = 1 }                     modifier 'expect' is not applicable to
     * expect class E { class N { expect val a: Int } }              'member property without backing field
     * expect class E { companion object { expect val a: Int } }     or delegate'.
     * ```
     *
     * The two nested rows are why [PropertyContainer.expectAllowed] reads the immediate builder's own
     * modifiers and not [TypeScope.isExpect]: the classifier *is* implicitly `expect`, and spelling
     * the keyword on its members is still rejected.
     */
    @Test
    fun `an expect property is refused in every container that renders the keyword`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            expectMessage() to { `interface`("I") { `val`(EXPECT, "x", INT) } },
            expectMessage() to { `class`("C") { `val`(EXPECT, "x", INT, init = 1.lit) } },
            expectMessage(keyword = "var") to { `class`("C") { `var`(EXPECT, "x", INT, init = 1.lit) } },
            expectMessage() to { `class`(EXPECT, "E") { `class`("N") { `val`(EXPECT, "x", INT) } } },
            expectMessage() to { `class`(EXPECT, "E") { companionObject { `val`(EXPECT, "x", INT) } } },
        ).forEachIndexed { index, (message, position) ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
    }

    /**
     * The other edge, and the reason neither guard reads `isExpect`/`isExternal` inherited: a
     * container that carries the modifier itself makes it **implicit** for its direct members, so
     * KotlinPoet never prints the member's copy and the render is valid.
     *
     * ```
     * expect class E   { val a: Int }   all three: only "expected E has no actual declaration",
     *                                   which is the single-module artefact, not a rule about the body
     * external class C { val a: Int }   jvm  modifier 'external' is not applicable to 'class'
     *                                   js   OK
     *                                   wasm OK
     * ```
     *
     * The second row is the one that costs something to get wrong: refusing it would refuse output
     * two frontends accept, which is the direction D37's standing rule exists to prevent.
     */
    @Test
    fun `a container that carries the modifier itself suppresses it`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E {
              public val x: Int
            }

            """.trimIndent(),
            file("com.example", "A") { `class`(EXPECT, "E") { `val`(EXPECT, "x", INT) } }.toString(),
        )
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public external class C {
              public val x: Int
            }

            """.trimIndent(),
            file("com.example", "A") { `class`(EXTERNAL, "C") { `val`(EXTERNAL, "x", INT) } }.toString(),
        )
    }

    /**
     * The detached builder still runs neither container rule, because it has no container:
     * `propertySpec` is documented as leaving every one of them to the caller's own compile, and an
     * interface body — where a bare `external val` is as wrong as anywhere else, but where the
     * *splice* is the escape hatch D37 names — is a legitimate destination.
     */
    @Test
    fun `propertySpec still answers to no container`() {
        assertEquals("external val x: kotlin.Int\n", propertySpec(EXTERNAL.toModifiers(), "x", INT).toString())
        assertEquals("expect val x: kotlin.Int\n", propertySpec(EXPECT.toModifiers(), "x", INT).toString())
    }
}
