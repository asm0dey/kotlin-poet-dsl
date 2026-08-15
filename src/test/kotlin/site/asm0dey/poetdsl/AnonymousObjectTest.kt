package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PROTECTED
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's expression construct. Every other construct in this DSL declares something; this one produces
 * a **value**, which is what makes ADR 0008's "what escapes it?" a new question rather than a
 * repeated one.
 *
 * Its acceptance spec is the same language-side measurement the enum entry's is (D43): the anonymous
 * body is one family, measured across 32 `KModifier` values × a `val`, a `var` and a `fun` × the two
 * positions, all three frontends agreeing on all 192 cells. The two positions differ on **three**
 * cells and they are one row — `protected`, clean here and *modifier 'protected' is not applicable
 * inside 'enum entry'* there.
 */
@OptIn(ExperimentalCompilerApi::class)
class AnonymousObjectTest {
    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    private val iface = className("com.example", "Iface")
    private val base = className("com.example", "Base")

    /** Compiles the render together with the supertypes it names, which the DSL does not emit. */
    private fun assertCompilesWithSupertypes(out: String, vararg extra: String) {
        assertCompiles(out.replace("package com.example\n", "") + "\n" + extra.joinToString("\n"))
    }

    @Test
    fun `an anonymous object is a value, usable as a property initializer`() {
        val out = render {
            `val`("handler", iface, init = anonymousObject(superinterfaces = listOf(iface)) {
                `fun`(OVERRIDE, "g", returns = INT) { ret(1.lit) }
            })
        }
        assertTrue("object : Iface" in out, out)
        assertCompilesWithSupertypes(out, "interface Iface { fun g(): Int }")
    }

    @Test
    fun `an anonymous object extends a class with constructor arguments`() {
        val out = render {
            `val`("v", base, init = anonymousObject(base, 1.lit))
        }
        assertTrue("object : Base(1)" in out, out)
        assertCompilesWithSupertypes(out, "open class Base(val n: Int)")
    }

    @Test
    fun `an anonymous object with no supertype is a bare object expression`() {
        val out = render {
            `fun`("f") { `val`("v", init = anonymousObject { `val`("p", INT, init = 1.lit) }) }
        }
        assertTrue("object {" in out, out)
        assertCompiles(out)
    }

    /** It is an expression, so a block body is its home rather than a mistake — no shadow. */
    @Test
    fun `an anonymous object is usable in a block body, as an argument and as a return value`() {
        val out = render {
            `fun`("make", returns = iface) {
                ret(
                    anonymousObject(superinterfaces = listOf(iface)) {
                        `fun`(OVERRIDE, "g", returns = INT) { ret(2.lit) }
                    },
                )
            }
        }
        assertCompilesWithSupertypes(out, "interface Iface { fun g(): Int }")
    }

    // --- the body, and the one cell where it differs from an enum entry's ------------------------

    /** Clean on all three frontends here, refused in an enum entry body. The pair is the point. */
    @Test
    fun `protected is allowed in an anonymous object body and refused in an entry body`() {
        val out = render {
            `fun`("f") {
                `val`("v", init = anonymousObject { `val`(PROTECTED, "p", INT, init = 1.lit) })
            }
        }
        assertCompiles(out)
        assertFailsWith<IllegalStateException> {
            render {
                `class`(com.squareup.kotlinpoet.KModifier.ENUM, "E") {
                    enumEntry("A") { `val`(PROTECTED, "p", INT, init = 1.lit) }
                }
            }
        }
    }

    /**
     * Written at **file level**, not in a block, and that is deliberate: `` `object` ``,
     * `` `interface` ``, `` `constructor` `` and `companionObject` all carry ADR 0002 `BlockScope`
     * shadows, so inside a block their calls resolve to the shadow and fail at *this suite's* compile
     * rather than at the guard under test. The guards are container rules and hold in every scope;
     * see `an anonymous object in a block cannot reach the shadowed constructs` for the pinned
     * limitation itself.
     */
    @Test
    fun `an anonymous object body holds no nested classifier, constructor or companion object`() {
        assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject { `class`("N") { } }) }
        }
        assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject { `object`("O") { } }) }
        }
        assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject { `interface`("I") { } }) }
        }
        assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject { companionObject { } }) }
        }
        assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject { `constructor`(param("q", INT)) { } }) }
        }
    }

    /** The same render gap the enum entry hits, with the same canary — see [EnumEntriesTest]. */
    @Test
    fun `an abstract member is refused as a render gap`() {
        val e = assertFailsWith<IllegalStateException> {
            render {
                `fun`("f") {
                    `val`("v", init = anonymousObject { `fun`(ABSTRACT, "g", returns = INT) { } })
                }
            }
        }
        assertTrue("KotlinPoet 2.3.0 cannot render it" in e.message!!, e.message!!)
        assertTrue("anonymous object" in e.message!!, e.message!!)
    }

    @Test
    fun `an anonymous object body holds an init block, properties and functions`() {
        val out = render {
            `fun`("f") {
                `val`("v", init = anonymousObject {
                    `val`("p", INT, init = 1.lit)
                    `var`("q", INT, init = 2.lit)
                    `fun`("h", returns = INT) { ret(3.lit) }
                })
            }
        }
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    /**
     * The supertypes are parameters, so the guards `superclass`/`superinterface` carry have to be
     * reached through them rather than re-implemented — this is the test that says they are. The
     * duplicate-interface refusal is `applySuperinterface`'s own, unchanged since D26.
     */
    @Test
    fun `the supertype parameters run the same guards the body constructs do`() {
        val dup = assertFailsWith<IllegalStateException> {
            render {
                `val`("v", iface, init = anonymousObject(superinterfaces = listOf(iface, iface)))
            }
        }
        assertTrue("already implements" in dup.message!!, dup.message!!)
        // `kotlin.Any` with arguments reaches no output at all, so `superclass`'s render-gap check
        // has to fire here too.
        val any = assertFailsWith<IllegalStateException> {
            render { `val`("v", iface, init = anonymousObject(className("kotlin", "Any"), 1.lit)) }
        }
        assertTrue("kotlin.Any" in any.message!!, any.message!!)
        // The control: one interface, and `Any` with no arguments, both render.
        assertCompilesWithSupertypes(
            render { `val`("v", iface, init = anonymousObject(superinterfaces = listOf(iface)) {
                `fun`(OVERRIDE, "g", returns = INT) { ret(1.lit) }
            }) },
            "interface Iface { fun g(): Int }",
        )
    }

    /** Arguments with nothing to pass them to would reach no output — the partial render Global
     * Constraint 26 forbids as loudly as an invalid one. */
    @Test
    fun `constructor arguments with no superclass are refused`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `val`("v", iface, init = anonymousObject(null, 1.lit)) }
        }
        assertTrue("no superclass" in e.message!!, e.message!!)
        // The control: the same arguments with a superclass to carry them.
        assertCompilesWithSupertypes(
            render { `val`("v", base, init = anonymousObject(base, 1.lit)) },
            "open class Base(val n: Int)",
        )
    }

    // --- ADR 0008 --------------------------------------------------------------------------------

    /**
     * An anonymous object **captures**, where a nested class does not: measured clean on all three
     * frontends for a local, an enclosing instance's property and a file-level binding alike. So the
     * body's [ScopeId] chains to the enclosing scope rather than being re-parented at the file, and
     * [checkOwned] accepts the handle with no change of its own.
     */
    @Test
    fun `an anonymous object body captures a local, a member and a file-level handle`() {
        val out = render {
            `val`("limit", INT, init = 10.lit)
            `class`("Holder", param(ParamKind.VAL, "id", INT)) {
                `fun`("f") {
                    val local = `val`("seed", INT, init = 1.lit)
                    `val`("v", init = anonymousObject {
                        `fun`("g", returns = INT) { ret(local) }
                    })
                }
            }
        }
        assertCompiles(out)
    }

    /** …and a genuinely foreign handle is still refused, which is the half that proves the above. */
    @Test
    fun `a foreign handle in an anonymous object body is still refused`() {
        assertFailsWith<IllegalStateException> {
            val foreign = stmts { `val`("stranger", INT, init = 1.lit) }
            val handle = attachedBlock().run { `val`("other", INT, init = 1.lit) }
            render {
                `fun`("f") {
                    `val`("v", init = anonymousObject { `fun`("g", returns = INT) { ret(handle) } })
                }
            }
            foreign.toString()
        }
    }

    /**
     * The one language rule about anonymous objects this DSL cannot express — and the reason it does
     * not need a guard.
     *
     * A **non-private** declaration whose inferred type is an anonymous object with two or more
     * supertypes is *right-hand side has an anonymous type. Specify the type explicitly.* (measured,
     * all three frontends). The DSL never renders that shape, because a file-level or member
     * property already *requires* an explicit type — KotlinPoet's `PropertySpec` has nowhere to put
     * an inferred one — and a **local** infers freely, which the last control row compiles.
     *
     * Both halves are built. Assuming the shape unreachable without constructing it is how E2d
     * shipped a false rejection.
     */
    @Test
    fun `the anonymous-type inference rule is unreachable through this DSL`() {
        val two: TypeScope.() -> Unit = {
            `fun`(OVERRIDE, "g", returns = INT) { ret(1.lit) }
            `fun`(OVERRIDE, "k", returns = INT) { ret(2.lit) }
        }
        val both = listOf(iface, className("com.example", "J"))
        val supers = "interface Iface { fun g(): Int }\ninterface J { fun k(): Int }"
        // The half that would render the invalid Kotlin: refused, and not by anything E3 added.
        val e = assertFailsWith<IllegalStateException> {
            render { `val`("v", init = anonymousObject(superinterfaces = both, body = two)) }
        }
        assertTrue("requires an explicit type" in e.message!!, e.message!!)
        // …and the two halves that do render, both clean on the JVM frontend.
        assertCompilesWithSupertypes(
            render { `val`("v", iface, init = anonymousObject(superinterfaces = both, body = two)) },
            supers,
        )
        assertCompilesWithSupertypes(
            render {
                `fun`("h") {
                    `val`("v", init = anonymousObject(superinterfaces = both, body = two))
                    +call("println", expression("v"))
                }
            },
            supers,
        )
        // The language row itself, compiled by hand because the DSL has no route to it.
        assertTrue(
            "anonymous type" in compile(
                "$supers\nval v = object : Iface, J { override fun g(): Int = 1\n" +
                    "  override fun k(): Int = 2 }\n",
            ).messages,
        )
    }

    /**
     * The limitation that made supertypes parameters rather than body constructs, pinned so that it
     * is a recorded fact and not a surprise.
     *
     * ADR 0002's shadows are `BlockScope` **extensions**, and an extension receiver beats a context
     * parameter in Kotlin's resolution, so inside an `anonymousObject { }` written lexically in a
     * block the enclosing body's `BlockScope` still wins for every shadowed construct. `superclass`
     * and `superinterface` were the two that mattered and they are parameters now; `` `init` `` is
     * the one that remains, and this test is what fails the day ADR 0002's shadowing changes.
     */
    @Test
    fun `an anonymous object in a block cannot reach the shadowed constructs`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `fun`("f") {
                    `val`("v", INT, init = anonymousObject { `init` { } })
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("`init` is only valid inside a class" in result.messages, result.messages)
        // The control: the identical construct at file level, where no `BlockScope` is in scope,
        // reaches the real `init` and renders.
        assertCompiles(
            render {
                `val`("v", className("kotlin", "Any"), init = anonymousObject { `init` { +call("println", 1.lit) } })
            },
        )
    }
}
