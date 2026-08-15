package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.EXTERNAL
import com.squareup.kotlinpoet.KModifier.FUN
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.INLINE
import com.squareup.kotlinpoet.KModifier.INNER
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.OPEN
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PROTECTED
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.VALUE
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.UNIT
import site.asm0dey.poetdsl.ParamKind.VAL
import site.asm0dey.poetdsl.ParamKind.VAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2c — the classifier-kind × body-member matrix (**D41**).
 *
 * One question: *what does a classifier's own kind forbid in its own body?* Every refusal below has
 * its **nearest valid neighbour** in the same test, at every depth the guard can fire, because a
 * guard keyed on a kind inverts one level down exactly as a guard keyed on a container does — that
 * is the standing method, and the matrix that produced this file measured every control on
 * `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10.
 */
class KindBodyTest {

    private val base = ClassName("com.example", "Base")
    private val iface = ClassName("com.example", "Iface")

    private fun render(body: FileScope.() -> Unit): String = file("com.example", "P", body = body).toString()

    private fun message(body: FileScope.() -> Unit): String =
        assertFailsWith<IllegalStateException> { render(body) }.message!!

    // --- `inner` needs a class around it -------------------------------------------------------

    @Test
    fun `an inner class at file level is refused`() {
        val m = message { `class`(INNER, "I") { } }
        assertTrue("modifier 'inner' is not applicable inside 'file'" in m, m)
    }

    @Test
    fun `an inner class is refused in an object, an interface and a companion object`() {
        assertTrue(
            "'standalone object'" in message { `object`("O") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "'interface'" in message { `interface`("O") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "'companion object'" in message {
                `class`("O") { companionObject { `class`(INNER, "I") { } } }
            },
        )
    }

    @Test
    fun `an inner class is refused in an annotation class and in a value class`() {
        assertTrue(
            "'annotation class'" in message { `class`(ANNOTATION, "A") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "value class cannot have inner classes" in message {
                `class`(VALUE, "V", param(VAL, "a", INT)) { `class`(INNER, "I") { } }
            },
        )
    }

    /**
     * The control rows: every container that *does* accept an `inner` class, measured clean on all
     * three frontends, at the depth each of them occurs at.
     */
    @Test
    fun `an inner class still renders in every container that accepts one`() {
        assertTrue("inner class N" in render { `class`("O") { `class`(INNER, "N") { } } })
        assertTrue(
            "inner class N" in render { `class`(DATA, "O", param(VAL, "a", INT)) { `class`(INNER, "N") { } } },
        )
        assertTrue("inner class N" in render { `class`(SEALED, "O") { `class`(INNER, "N") { } } })
        assertTrue("inner class N" in render { `class`(ENUM, "O") { `class`(INNER, "N") { } } })
        // Depth: the immediate container is what decides, so a class nested in an object is fine.
        assertTrue(
            "inner class N" in render { `object`("O") { `class`("M") { `class`(INNER, "N") { } } } },
        )
        assertTrue(
            "inner class N" in render { `interface`("O") { `class`("M") { `class`(INNER, "N") { } } } },
        )
    }

    // --- …and the pair half: which classifier kinds may be `inner` at all ----------------------

    /**
     * `inner` is refused **on** six classifier kinds, in all three containers that take an `inner`
     * class: a class body, an anonymous object's body and an enum entry's. Five of the six draw one
     * sentence in every container and are the rows below; the sixth, `enum`, draws a different noun
     * per container and has the test after this one. That the set is exactly six is neither written
     * here nor read off the guard's own list — `the set of kinds inner is refused on is exactly six`
     * derives it by sweeping every `KModifier`.
     *
     * Every row is a render this DSL emitted until the pre-lock round — the E3 fix round widened
     * [Scope.innerAllowed] to the two anonymous bodies, and every `inner` pair in those bodies went
     * from refused to rendered with it, because its sweep was 32 *single* modifiers and a pair is
     * not a coordinate of that product.
     *
     * The quoted sentences are the compiler's, transcribed from a run over the **renders**, and the
     * render is not the input: KotlinPoet emits `public sealed inner class N` for
     * `` `class`(INNER + SEALED, …) ``, and the frontends name the first-written modifier, so
     * `inner sealed class N` and `sealed inner class N` draw the two halves of one sentence.
     */
    @Test
    fun `inner is refused on five classifier kinds with one sentence in every container`() {
        data class Row(val modifier: KModifier, val sentence: String, val param: Boolean)
        val rows = listOf(
            Row(ANNOTATION, "modifier 'inner' is not applicable to 'annotation class'", false),
            Row(SEALED, "modifier 'sealed' is incompatible with 'inner'", false),
            Row(DATA, "modifier 'inner' is incompatible with 'data'", true),
            Row(VALUE, "value class cannot be local or inner", true),
            Row(INLINE, "value class cannot be local or inner", true),
        )
        for (row in rows) {
            val declare: TypeScope.() -> Unit = {
                if (row.param) {
                    `class`(Modifiers(setOf(INNER, row.modifier)), "N", param(VAL, "a", INT)) { }
                } else {
                    `class`(Modifiers(setOf(INNER, row.modifier)), "N") { }
                }
            }
            val inClass = message { `class`("C") { declare() } }
            assertTrue(row.sentence in inClass, inClass)
            val inAnonymous = message { `fun`("f") { `val`("v", init = anonymousObject(body = declare)) } }
            assertTrue(row.sentence in inAnonymous, inAnonymous)
            val inEntry = message { `class`(ENUM, "E") { enumEntry("A", body = declare) } }
            assertTrue(row.sentence in inEntry, inEntry)
        }
    }

    /**
     * `enum` is the sixth kind, and the one whose **noun** moves: the frontends call the declaration an
     * *enum class* in a class body and a *local class* in either anonymous body, because `enum`
     * makes it local there and `inner` cannot make it a member. Measured, one file per cell, all
     * three frontends:
     *
     *     class C { public inner enum class N }              modifier 'inner' is not applicable to
     *                                                        'enum class'.
     *     val v = object { public inner enum class N }       …to 'local class'.
     *     enum class E { A { public inner enum class N } }   …to 'local class'.
     */
    @Test
    fun `the inner enum sentence names the container's own noun`() {
        val declare: TypeScope.() -> Unit = { `class`(Modifiers(setOf(INNER, ENUM)), "N") { } }
        val inClass = message { `class`("C") { declare() } }
        assertTrue("modifier 'inner' is not applicable to 'enum class'" in inClass, inClass)
        val inAnonymous = message { `fun`("f") { `val`("v", init = anonymousObject(body = declare)) } }
        assertTrue("modifier 'inner' is not applicable to 'local class'" in inAnonymous, inAnonymous)
        val inEntry = message { `class`(ENUM, "E") { enumEntry("A", body = declare) } }
        assertTrue("modifier 'inner' is not applicable to 'local class'" in inEntry, inEntry)
    }

    /**
     * **The set, derived rather than transcribed.** Three places in this project have carried a
     * count of this one fact and no two agreed: the guard's own list held six kinds, the test above
     * said five in its name, and `Kinds.kt` said the widening released "ten refusals". Every number
     * was defensible in isolation and none of them said what it was counting, which is E2f's lesson
     * — *when a guard's correctness depends on a set, derive the set or pin it against its source* —
     * applied to the arithmetic instead of to the membership.
     *
     * So the membership is swept: every `KModifier` value, paired with `INNER` on a nested class in
     * a class body, in both base forms. Exactly six draw the pair refusal, and this test names them.
     * A seventh kind added to the guard's list fails here, and so does one dropped.
     */
    @Test
    fun `the set of kinds inner is refused on is exactly six`() {
        val refused = KModifier.entries.filter { it != INNER }.filter { other ->
            listOf(false, true).any { withParam ->
                val e = runCatching {
                    render {
                        `class`("C") {
                            if (withParam) {
                                `class`(Modifiers(setOf(INNER, other)), "N", param(VAL, "a", INT)) { }
                            } else {
                                `class`(Modifiers(setOf(INNER, other)), "N") { }
                            }
                        }
                    }
                }.exceptionOrNull()
                e is IllegalStateException && "carries INNER and" in e.message.orEmpty()
            }
        }
        assertEquals(listOf(SEALED, ANNOTATION, DATA, VALUE, INLINE, ENUM).sortedBy { it.name }, refused.sortedBy { it.name })
    }

    /**
     * The control rows, and they are what say this refuses the **pair** and not either half: an
     * `inner` class still renders in all three containers, and each of the six kinds still renders
     * without `inner` — five declarations, `inline` and `value` being one shape. Compiled, so
     * "still renders" is not the whole claim.
     */
    @Test
    fun `an inner class and each classifier kind still render on their own`() {
        val anonymous = render {
            `fun`("f") {
                `val`("v", init = anonymousObject { `class`(INNER, "N") { `val`("p", INT, init = 1.lit) } })
            }
        }
        assertTrue("inner class N" in anonymous, anonymous)
        assertCompiles(anonymous)
        val entry = render {
            `class`(ENUM, "E") { enumEntry("A") { `class`(INNER, "N") { } } }
        }
        assertTrue("inner class N" in entry, entry)
        assertCompiles(entry)
        val kinds = render {
            `class`("C") {
                `class`(ANNOTATION, "A") { }
                `class`(ENUM, "N") { }
                `class`(SEALED, "S") { }
                `class`(DATA, "D", param(VAL, "a", INT)) { }
            }
        }
        assertTrue("annotation class A" in kinds, kinds)
        assertCompiles(kinds)
        // A `value class` is the one whose control the JVM refuses for a reason of its own —
        // *value classes without '@JvmInline' annotation are not yet supported*, D37's platform
        // rule and the caller's annotation to add — so it is rendered here and compiled on the two
        // frontends that can see past that.
        val valueClass = render { `class`("C") { `class`(VALUE, "V", param(VAL, "a", INT)) { } } }
        assertTrue("value class V" in valueClass, valueClass)
        assertCompilesEverywhereButJvm(valueClass)
    }

    // --- an `inner class` holds no nested classifier ------------------------------------------

    @Test
    fun `a nested classifier inside an inner class is refused`() {
        assertTrue(
            "'Class' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `class`("N") { } } }
            },
        )
        assertTrue(
            "'Object' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `object`("N") { } } }
            },
        )
        assertTrue(
            "'Interface' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `interface`("N") { } } }
            },
        )
        assertTrue(
            "'Companion object' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { companionObject { } } }
            },
        )
    }

    /**
     * …and which noun *'X' is prohibited here* carries, which is the declared **kind** and not this
     * DSL's `kindName`. Two of the six class-shaped kinds get a noun of their own, and the split is
     * not the one the anonymous body makes. Measured, one file per row, all three frontends 2.4.10:
     *
     *     class O { inner class M { public annotation class N } }  'Annotation class' is prohibited
     *                                                              here.
     *     class O { inner class M { public enum class N } }        'Enum class' is prohibited here.
     *     class O { inner class M { public sealed class N } }      'Class' is prohibited here.
     *     class O { inner class M { public data class N(val a: Int) } }   — the same
     *     class O { inner class M { public value class N(val a: Int) } }  — the same
     */
    @Test
    fun `the prohibited-here noun inside an inner class is the declared kind's`() {
        val rows = listOf(
            Triple(ANNOTATION, "'Annotation class' is prohibited here", false),
            Triple(ENUM, "'Enum class' is prohibited here", false),
            Triple(SEALED, "'Class' is prohibited here", false),
            Triple(DATA, "'Class' is prohibited here", true),
            Triple(VALUE, "'Class' is prohibited here", true),
        )
        for ((modifier, sentence, withParam) in rows) {
            val m = message {
                `class`("O") {
                    `class`(INNER, "M") {
                        if (withParam) {
                            `class`(modifier.toModifiers(), "N", param(VAL, "a", INT)) { }
                        } else {
                            `class`(modifier.toModifiers(), "N") { }
                        }
                    }
                }
            }
            assertTrue(sentence in m, m)
        }
    }

    /** The control rows: what an `inner class` *does* still hold. */
    @Test
    fun `an inner class still holds members and another inner class`() {
        val nested = render { `class`("O") { `class`(INNER, "M") { `class`(INNER, "N") { } } } }
        assertTrue("inner class N" in nested, nested)
        assertTrue("public val p: Int = 1" in render {
            `class`("O") { `class`(INNER, "M") { `val`("p", INT, init = 1.lit) } }
        })
        assertTrue("public fun f(): Int = 1" in render {
            `class`("O") { `class`(INNER, "M") { `fun`("f", returns = INT) { ret(1.lit) } } }
        })
        // …and a plain nested class one level further out is untouched: the rule reads the
        // immediate container only.
        assertTrue("public class N" in render {
            `class`("O") { `class`("M") { `class`("N") { } } }
        })
    }

    @Test
    fun `the inner rules go through the frontend`() {
        assertCompiles(
            render {
                `class`("Outer") {
                    `class`(INNER, "M") { `class`(INNER, "N") { `val`("p", INT, init = 1.lit) } }
                }
                `class`(ENUM, "E") { `class`(INNER, "N") { } }
                `class`(SEALED, "S") { `class`(INNER, "N") { } }
                `object`("O") { `class`("M") { `class`(INNER, "N") { } } }
            },
        )
    }

    // --- an `annotation class` has no members and no supertypes --------------------------------

    @Test
    fun `every member of an annotation class is refused`() {
        val shapes: List<Pair<String, TypeScope.() -> Unit>> = listOf(
            "val" to { `val`("p", INT, init = 1.lit) },
            "var" to { `var`("p", INT, init = 1.lit) },
            "val with a getter" to { `val`("p", INT, getter = { ret(1.lit) }) },
            "fun" to { `fun`("f", returns = INT) { ret(1.lit) } },
            "constructor" to { `constructor`(param("q", INT)) { } },
            "init" to { `init` { } },
        )
        shapes.forEach { (label, member) ->
            val m = message { `class`(ANNOTATION, "A") { member() } }
            assertTrue("members are prohibited in annotation classes" in m, "$label: $m")
        }
    }

    @Test
    fun `an annotation class takes neither a superclass nor a superinterface`() {
        assertTrue(
            "annotation class cannot have supertypes" in
                message { `class`(ANNOTATION, "A") { superinterface(iface) } },
        )
        assertTrue(
            "annotation class cannot have supertypes" in
                message { `class`(ANNOTATION, "A") { superclass(base) } },
        )
    }

    /** The control rows: an annotation class keeps its parameters, its nested types, its companion. */
    @Test
    fun `an annotation class still holds parameters, nested types and a companion object`() {
        val rendered = render {
            `class`(ANNOTATION, "A", param(VAL, "x", INT)) {
                `class`("Inner") { `val`("p", INT, init = 1.lit) }
                companionObject { `val`("q", INT, init = 2.lit) }
            }
        }
        assertTrue("public annotation class A(" in rendered, rendered)
        assertTrue("public val x: Int" in rendered, rendered)
        assertTrue("public class Inner" in rendered, rendered)
        assertTrue("public companion object" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** …and the rule is the *kind's*, so it fires at every depth and nowhere else. */
    @Test
    fun `the annotation rule fires at every depth and leaves neighbouring kinds alone`() {
        assertTrue(
            "members are prohibited" in message {
                `class`("O") { `class`(ANNOTATION, "A") { `val`("p", INT, init = 1.lit) } }
            },
        )
        assertTrue(
            "members are prohibited" in message {
                `object`("O") { `class`(ANNOTATION, "A") { `val`("p", INT, init = 1.lit) } }
            },
        )
        // The neighbour one level in: a class nested inside an annotation class is an ordinary
        // class and holds whatever a class holds.
        assertCompiles(
            render {
                `class`(ANNOTATION, "A") {
                    `class`("M") { `fun`("f", returns = INT) { ret(1.lit) } }
                }
            },
        )
    }

    // --- an abstract function needs a container that can hold one -----------------------------

    @Test
    fun `an abstract function is refused where Kotlin has nothing to override it`() {
        val containers: List<Pair<String, FileScope.() -> Unit>> = listOf(
            "class" to { `class`("C") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "data class" to {
                `class`(DATA, "C", param(VAL, "a", INT)) { `fun`(ABSTRACT, "f", returns = INT) { } }
            },
            "open class" to { `class`(OPEN, "C") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "object" to { `object`("O") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "companion object" to {
                `class`("C") { companionObject { `fun`(ABSTRACT, "f", returns = INT) { } } }
            },
            "expect class" to {
                `class`(EXPECT, "E") { `fun`(ABSTRACT, "f", returns = INT) { } }
            },
        )
        containers.forEach { (label, body) ->
            val m = message(body)
            assertTrue("abstract function 'f' in non-abstract class" in m, "$label: $m")
        }
    }

    /**
     * **A file is not a non-abstract class, and the frontends do not call it one.** Measured on
     * kotlinc 2.4.10: `abstract fun f(): Int` at file level is *modifier 'abstract' is not
     * applicable to 'top level function'*, and a local one is *…to 'local function'*. In this
     * project the quoted sentence is the currency, so the message quotes the one that is printed.
     */
    @Test
    fun `an abstract function at file level quotes the sentence a file gets`() {
        val m = message { `fun`(ABSTRACT, "f", returns = INT) { } }
        assertTrue("modifier 'abstract' is not applicable to 'top level function'" in m, m)
        assertFalse("non-abstract class" in m, m)
    }

    /** The control rows: the four containers that do hold an abstract member. */
    @Test
    fun `an abstract function still renders in an interface, an abstract, a sealed and an enum class`() {
        assertCompiles(
            render {
                `interface`("I") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ABSTRACT, "A") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(SEALED, "S") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ENUM, "E") { `fun`(ABSTRACT, "f", returns = INT) { } }
                // Depth: the immediate container decides, so an abstract class nested in an
                // object still holds one.
                `object`("O") { `class`(ABSTRACT, "N") { `fun`(ABSTRACT, "f", returns = INT) { } } }
            },
        )
        // `expect abstract class X { abstract fun f(): Int }` is clean on all three frontends and
        // is the control for the `expect class` row above — asserted on the render rather than
        // compiled here, since a single-file `expect` has no `actual` to pair with.
        val expected = render { `class`(EXPECT + ABSTRACT, "X") { `fun`(ABSTRACT, "f", returns = INT) { } } }
        assertTrue("public expect abstract class X" in expected, expected)
        assertTrue("public abstract fun f(): Int" in expected, expected)
    }

    // --- a function with a declared return type and an empty body -----------------------------

    @Test
    fun `a function with a return type and no body is refused in every container`() {
        val containers: List<Pair<String, FileScope.() -> Unit>> = listOf(
            "file" to { `fun`("f", returns = INT) { } },
            "class" to { `class`("C") { `fun`("f", returns = INT) { } } },
            "interface" to { `interface`("I") { `fun`("f", returns = INT) { } } },
            "object" to { `object`("O") { `fun`("f", returns = INT) { } } },
            "enum class" to { `class`(ENUM, "E") { `fun`("f", returns = INT) { } } },
            "abstract class" to { `class`(ABSTRACT, "A") { `fun`("f", returns = INT) { } } },
            "companion object" to { `class`("C") { companionObject { `fun`("f", returns = INT) { } } } },
            "nested" to { `class`("C") { `class`("N") { `fun`("f", returns = INT) { } } } },
        )
        containers.forEach { (label, body) ->
            val m = message(body)
            assertTrue("missing return statement" in m, "$label: $m")
        }
    }

    /**
     * The control rows: every shape whose body KotlinPoet **omits**, so that what renders is a
     * signature and not an empty block. Each measured clean on all three frontends (the two
     * `external` rows on Kotlin/JS and Kotlin/Wasm — D37's platform rule).
     */
    @Test
    fun `a signature still renders wherever KotlinPoet omits the body`() {
        assertCompiles(
            render {
                `fun`("unitExplicit", returns = UNIT) { }
                `fun`("unitInferred") { }
                `interface`("I") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ABSTRACT, "A") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`("C") { `constructor`(param("q", INT)) { } }
            },
        )
        // `expect`, at the depth KotlinPoet suppresses it: directly, and one level down, where the
        // *container* is the only thing that says so.
        val expect = render {
            `class`(EXPECT, "E") {
                `fun`("f", returns = INT) { }
                `class`("N") { `fun`("g", returns = INT) { } }
            }
        }
        assertTrue("public fun f(): Int\n" in expect, expect)
        assertTrue("public fun g(): Int\n" in expect, expect)
        assertTrue("public expect fun h(): Int" in render { `fun`(EXPECT, "h", returns = INT) { } })
        // …and `external`, which is the same fact on the other keyword and which KotlinPoet threads
        // down to every depth (`modifiers + implicitModifiers`, TypeSpec.kt:348). All four are
        // clean on Kotlin/JS and Kotlin/Wasm; the JVM refuses `external` on a class at all.
        val external = render {
            `fun`(EXTERNAL, "top", returns = INT) { }
            `class`(EXTERNAL, "X") {
                `fun`("f", returns = INT) { }
                `class`("N") { `fun`("g", returns = INT) { } }
                `object`("O") { `fun`("h", returns = INT) { } }
            }
        }
        assertTrue("public external fun top(): Int" in external, external)
        assertTrue("public fun f(): Int\n" in external, external)
        assertTrue("public fun g(): Int\n" in external, external)
        assertTrue("public fun h(): Int\n" in external, external)
    }

    /**
     * **The detached boundary.** `funSpec` passes `parent = null`, so the two container-dependent
     * halves of this rule's exemption set — the container's `expect`-ness and its `external`-ness —
     * have no answer here, and the same spec is a valid signature the moment it is spliced into an
     * `expect` or `external` type. [PropertyContainer.UNKNOWN] is this project's settled answer for
     * exactly that shape, so a null parent takes the permissive branch.
     */
    @Test
    @OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
    fun `a detached funSpec with a return type and no body renders a signature`() {
        val spec = funSpec(name = "f", returns = INT) { }
        // Detached, the empty body is still there; it is the *splice* that decides whether
        // KotlinPoet omits it, which is exactly what this builder cannot know.
        assertEquals("public fun f(): kotlin.Int {\n}\n", spec.toString())
        // …and both destinations that make it valid, at the top level and one level down.
        val expect = render { `class`(EXPECT, "E") { +spec; `class`("N") { +spec } } }
        assertTrue("public fun f(): Int\n" in expect, expect)
        assertFalse("missing return statement" in compileMultiplatform(expect).messages, expect)
        val external = render { `class`(EXTERNAL, "X") { +spec; `class`("N") { +spec } } }
        assertCompilesEverywhereButJvm(external)
    }

    // --- a `data class` and its primary constructor --------------------------------------------

    @Test
    fun `a data class refuses a plain primary-constructor parameter`() {
        val m = message { `class`(DATA, "D") { constructorParam(null, "a", INT) } }
        assertTrue("must only have property ('val' / 'var') parameters" in m, m)
        // …at every depth, and through the signature form as well as the in-body one.
        assertTrue(
            "must only have property" in message {
                `class`("O") { `class`(DATA, "D", param(null, "a", INT)) { _ -> } }
            },
        )
        assertTrue(
            "must only have property" in message {
                `object`("O") {
                    `class`(DATA, "D", param(VAL, "a", INT), param(null, "b", INT)) { _, _ -> }
                }
            },
        )
    }

    @Test
    fun `a data class with no primary-constructor parameter is refused`() {
        val m = message { `class`(DATA, "D") { } }
        assertTrue("data class must have at least one primary constructor parameter" in m, m)
        // The detached builder answers for itself, exactly as it does for every other kind rule.
        val detached = assertFailsWith<IllegalStateException> {
            typeSpec(DATA.toModifiers(), name = "D") { }
        }
        assertTrue("at least one primary constructor parameter" in detached.message!!, detached.message!!)
    }

    /** The control rows: what a data class still is. */
    @Test
    fun `a data class still takes val and var parameters and ordinary members`() {
        assertCompiles(
            render {
                `class`(DATA, "D", param(VAL, "a", INT), param(VAR, "b", INT)) { _, _ ->
                    `fun`("f", returns = INT) { ret(1.lit) }
                    `class`("N") { }
                    companionObject { }
                }
                // A *plain* parameter is exactly what an ordinary class still takes.
                `class`("C", param(null, "a", INT)) { _ -> }
                `class`("K") { constructorParam(null, "a", INT) }
            },
        )
    }

    /**
     * **`data object` is valid Kotlin and this DSL refused it.** The no-parameter half of the data
     * rule fired on the modifier alone, and a `data object` has no primary constructor to give it —
     * `data object O` is clean on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10 (measured, one
     * file per frontend). D42's one false rejection.
     */
    @Test
    fun `a data object renders and compiles`() {
        val source = render {
            `object`(DATA, "O") { }
            `class`("C") { `object`(DATA, "N") { } }
            `class`("D") { `class`("Inner") { `object`(DATA, "M") { } } }
        }
        assertTrue("public data object O" in source, source)
        assertCompiles(source)
        assertCompilesEverywhereButJvm(source)
        // …and the rule it was caught by still fires for a class, which is what it was written for.
        assertTrue(
            "at least one primary constructor parameter" in message { `class`(DATA, "D") { } },
        )
    }

    // --- a `value class` holds no property with a backing field --------------------------------

    @Test
    fun `a value class property with a backing field is refused`() {
        val v = { member: TypeScope.() -> Unit ->
            message { `class`(VALUE, "V", param(VAL, "a", INT)) { _ -> member() } }
        }
        assertTrue(
            "value class cannot have properties with backing fields" in
                v { `val`("p", INT, init = 1.lit) },
        )
        assertTrue(
            "value class cannot have properties with backing fields" in
                v { `var`("p", INT, init = 1.lit) },
        )
        assertTrue(
            "value class cannot have delegated properties" in
                v { `val`("p", INT, by = call("lazy", lambda { ret(1.lit) })) },
        )
        assertTrue(
            "value class cannot have properties with backing fields" in
                v { `var`(LATEINIT, "p", STRING) },
        )
    }

    /** The control rows: what a value class still holds, and what an interface still says. */
    @Test
    fun `a value class still holds accessors, functions and a companion object`() {
        val rendered = render {
            `class`(VALUE, "V", param(VAL, "a", INT)) { _ ->
                `val`("p", INT, getter = { ret(1.lit) })
                `fun`("f", returns = INT) { ret(1.lit) }
                `class`("N") { }
                companionObject { `val`("q", INT, init = 2.lit) }
            }
        }
        assertTrue("public val p: Int" in rendered, rendered)
        assertTrue("get() = 1" in rendered, rendered)
        // The interface's own three messages are untouched by the shared reason.
        val i = message { `interface`("I") { `val`("p", INT, init = 1.lit) } }
        assertTrue("property initializers in interfaces are prohibited" in i, i)
    }

    // --- a `fun interface` holds no abstract property -------------------------------------------

    @Test
    fun `an abstract property in a fun interface is refused`() {
        val m = message {
            `interface`(FUN, "F") {
                `fun`(ABSTRACT, "g", returns = INT) { }
                `val`("p", INT)
            }
        }
        assertTrue("functional interface cannot have abstract properties" in m, m)
        assertTrue(
            "functional interface cannot have abstract properties" in message {
                `interface`(FUN, "F") {
                    `fun`(ABSTRACT, "g", returns = INT) { }
                    `val`(ABSTRACT, "p", INT)
                }
            },
        )
    }

    /** The control rows: an ordinary interface, and a fun interface's non-abstract property. */
    @Test
    fun `a fun interface still holds a property with accessors, and an interface an abstract one`() {
        assertCompiles(
            render {
                `interface`(FUN, "F") {
                    `fun`(ABSTRACT, "g", returns = INT) { }
                    `val`("p", INT, getter = { ret(1.lit) })
                }
                `interface`("H") { `val`("p", INT) }
            },
        )
    }

    // --- a secondary constructor of an `enum` or a `sealed` class ------------------------------

    @Test
    fun `a secondary constructor that renders public is refused in an enum and a sealed class`() {
        val e = message { `class`(ENUM, "E") { `constructor`(param("q", INT)) { } } }
        assertTrue("constructor must be private in enum class" in e, e)
        val s = message { `class`(SEALED, "S") { `constructor`(param("q", INT)) { } } }
        assertTrue("constructor must be private or protected in sealed class" in s, s)
        // INTERNAL and PROTECTED are refused in an enum, INTERNAL in a sealed class — measured, all
        // three frontends, so the accepted set is exactly what the message names.
        assertTrue(
            "must be private in enum class" in message {
                `class`(ENUM, "E") { `constructor`(PROTECTED, param("q", INT)) { } }
            },
        )
        assertTrue(
            "must be private in enum class" in message {
                `class`(ENUM, "E") { `constructor`(INTERNAL, param("q", INT)) { } }
            },
        )
        assertTrue(
            "must be private or protected in sealed class" in message {
                `class`(SEALED, "S") { `constructor`(INTERNAL, param("q", INT)) { } }
            },
        )
    }

    /** The control rows: the visibilities that do render, and the kinds the rule leaves alone. */
    @Test
    fun `a private or protected secondary constructor still renders`() {
        assertCompiles(
            render {
                `class`(ENUM, "E") { `constructor`(PRIVATE, param("q", INT)) { } }
                `class`(SEALED, "S") { `constructor`(PRIVATE, param("q", INT)) { } }
                `class`(SEALED, "T") { `constructor`(PROTECTED, param("q", INT)) { } }
                // Every other kind keeps the public secondary constructor it always had.
                `class`("C") { `constructor`(param("q", INT)) { } }
                `class`(OPEN, "O") { `constructor`(param("q", INT)) { } }
                `class`(ABSTRACT, "A") { `constructor`(param("q", INT)) { } }
            },
        )
    }
}
