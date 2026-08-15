package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.VALUE
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's cheapest construct: `addSuperinterface(TypeName, delegate)`, which `applySuperinterface` had
 * never called. A **defaulted slot** on the existing `superinterface`, so the generated surface and
 * the shadow list are both unchanged — the shape every slot addition since E1 has taken.
 *
 * Measured, one file per row, `kotlinc` / `kotlinc-js` / `kotlinc-wasm` 2.4.10, 14 rows:
 *
 *     interface F : Iface by D()                delegation cannot be used in interfaces.
 *     value class C(val a: Int) : Iface by D()  value class cannot implement an interface by
 *                                               delegation.   (JS and Wasm; the JVM masks it — see
 *                                               `a value class cannot delegate`)
 *     class C : Base0 by Base0()                delegation is supported only for interfaces.
 *                                               — unreachable: `superclass` takes no delegate
 *     annotation class A : Iface by D()         annotation class cannot have supertypes.
 *                                               — already refused by `supertypesAllowed`
 *
 * and the controls, clean on all three: a class delegating to a plain parameter, to a `val`
 * parameter, to a property and to a call; an object; a companion object; an `enum class`; a nested
 * class; a delegate alongside a superclass; a delegate alongside a second, undelegated interface; and
 * a delegate whose member is overridden anyway.
 */
@OptIn(ExperimentalCompilerApi::class)
class DelegatedSuperinterfaceTest {
    private val iface = className("com.example", "Iface")
    private val impl = className("com.example", "D")

    /** `D()` — a constructor call, which `call(…)` does not spell for a `ClassName`. */
    private val newImpl = expression("%T()", impl)

    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    /** Compiles the render with the interface and the implementation it names. */
    private fun assertCompilesWithIface(out: String) {
        assertCompiles(
            out.replace("package com.example\n", "") +
                "\ninterface Iface { fun g(): Int }\nclass D : Iface { override fun g(): Int = 1 }\n",
        )
    }

    @Test
    fun `a class delegates an interface to a constructor parameter`() {
        val out = render {
            `class`("C", param("i", iface)) { i -> superinterface(iface, by = i) }
        }
        assertTrue(": Iface by i" in out, out)
        assertCompilesWithIface(out)
    }

    @Test
    fun `a class delegates to a val parameter, a property and a call`() {
        assertCompilesWithIface(
            render { `class`("C", param(ParamKind.VAL, "i", iface)) { i -> superinterface(iface, by = i) } },
        )
        assertCompilesWithIface(render { `class`("C") { superinterface(iface, by = newImpl) } })
        assertCompilesWithIface(
            render {
                `val`("shared", iface, init = newImpl)
                `class`("C") { superinterface(iface, by = expression("shared")) }
            },
        )
    }

    /**
     * The one delegate expression this DSL can build and no frontend accepts, measured rather than
     * guarded — a **property of the class being declared**:
     *
     *     class C : Iface by d { val d: Iface = D() }   cannot access '<this>' before the instance
     *                                                    has been initialized.
     *     class C(i: Iface) : Iface by i                clean   — a constructor parameter
     *     class C(val i: Iface) : Iface by i            clean   — including a property parameter
     *     val shared: Iface = D() ; class C : Iface by shared      clean — a file-level binding
     *     class C : Iface by D()                                  clean — a call
     *     class C : Iface by holder { companion object { val holder: Iface = D() } }   clean
     *
     * A *property* handle and a *property parameter* handle are owned by the same [ScopeId] in this
     * DSL, so the two rows cannot be told apart without a new field, and `checkOwned` does not reach a
     * [TypeScope] at all. Left as a documented hazard with four working spellings above it, rather
     * than as a new ownership mechanism in the last feature round.
     */
    @Test
    fun `a delegate that is a property of this class does not compile, and is not guarded`() {
        val out = render {
            `class`("C") {
                val p = `val`("d", iface, init = newImpl)
                superinterface(iface, by = p)
            }
        }
        assertTrue(": Iface by d" in out, out)
        assertTrue(
            "before the instance has been initialized" in compile(
                out.replace("package com.example\n", "") +
                    "\ninterface Iface { fun g(): Int }\nclass D : Iface { override fun g(): Int = 1 }\n",
            ).messages,
            out,
        )
    }

    @Test
    fun `an object, a companion object, an enum class and a nested class all delegate`() {
        assertCompilesWithIface(render { `object`("O") { superinterface(iface, by = newImpl) } })
        assertCompilesWithIface(
            render { `class`("C") { companionObject { superinterface(iface, by = newImpl) } } },
        )
        assertCompilesWithIface(
            render { `class`("Outer") { `class`("C") { superinterface(iface, by = newImpl) } } },
        )
        assertCompilesWithIface(
            render { `class`(DATA, "C", param(ParamKind.VAL, "a", INT)) { superinterface(iface, by = newImpl) } },
        )
    }

    /**
     * The render gap the control row found: `enum class E : Iface by D() { A }` is **clean on all
     * three frontends** and KotlinPoet refuses it — `IllegalArgumentException: delegation only
     * allowed for classes and objects (found CLASS 'E')`, from a condition that is
     * `kind == CLASS && !isEnum && !isAnnotation`, read off the bytecode. D20's shape, so it is
     * refused with a message that says *KotlinPoet cannot render this* rather than one asserting a
     * language rule that does not exist.
     *
     * The canary is the second half: it fails the day KotlinPoet accepts the delegate, which is when
     * this refusal should go.
     */
    @Test
    fun `an enum class delegating is refused as a render gap`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "E") { superinterface(iface, by = newImpl); enumEntry("A") } }
        }
        assertTrue("KotlinPoet 2.3.0 cannot render it" in e.message!!, e.message!!)
        // The language row, compiled by hand because the DSL has no route to it.
        assertCompiles(
            "interface Iface { fun g(): Int }\nclass D : Iface { override fun g(): Int = 1 }\n" +
                "enum class E : Iface by D() { A }\n",
        )
        // The canary.
        val kp = assertFailsWith<IllegalArgumentException> {
            com.squareup.kotlinpoet.TypeSpec.classBuilder("E")
                .addModifiers(ENUM)
                .addSuperinterface(iface, com.squareup.kotlinpoet.CodeBlock.of("D()"))
        }
        assertTrue("delegation only allowed" in kp.message!!, kp.message!!)
        // The control: the same enum implementing the interface without a delegate.
        assertCompilesWithIface(
            render {
                `class`(ENUM, "E") {
                    superinterface(iface)
                    enumEntry("A")
                    `fun`(com.squareup.kotlinpoet.KModifier.OVERRIDE, "g", returns = INT) { ret(1.lit) }
                }
            },
        )
    }

    /** *delegation cannot be used in interfaces* — with the undelegated row as its control. */
    @Test
    fun `an interface cannot delegate`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `interface`("F") { superinterface(iface, by = newImpl) } }
        }
        assertTrue("delegation cannot be used in interfaces" in e.message!!, e.message!!)
        // The control: the same supertype without the delegate.
        assertCompilesWithIface(render { `interface`("F") { superinterface(iface) } })
    }

    /**
     * *value class cannot implement an interface by delegation* — and this is the round's one row the
     * JVM cannot judge.
     *
     * A `value class` without `@JvmInline` is *value classes without '@JvmInline' annotation are not
     * yet supported* on the JVM whatever else is wrong with it, so the delegation diagnostic never
     * appears there. Kotlin/JS and Kotlin/Wasm compile a plain `value class`: both print the
     * delegation sentence for the delegated form, and both compile the **undelegated** form clean,
     * which is the control that keeps this from being over-broad. D37's family, four rounds on.
     */
    @Test
    fun `a value class cannot delegate`() {
        val e = assertFailsWith<IllegalStateException> {
            render {
                `class`(VALUE, "C", param(ParamKind.VAL, "a", INT)) {
                    superinterface(iface, by = newImpl)
                }
            }
        }
        assertTrue("value class cannot implement an interface by delegation" in e.message!!, e.message!!)
        // The control, on the two frontends that can see it: a `value class` may implement the
        // interface, only not by delegation.
        assertCompilesEverywhereButJvm(
            render {
                `class`(VALUE, "C", param(ParamKind.VAL, "a", INT)) {
                    superinterface(iface)
                    `fun`(com.squareup.kotlinpoet.KModifier.OVERRIDE, "g", returns = INT) { ret(1.lit) }
                }
            }.replace("package com.example\n", "") + "\ninterface Iface { fun g(): Int }\n",
        )
    }

    @Test
    fun `a delegate coexists with a superclass and with an undelegated interface`() {
        val base = className("com.example", "Base")
        val other = className("com.example", "J")
        val out = render {
            `class`("C") {
                superclass(base)
                superinterface(iface, by = newImpl)
                superinterface(other)
                `fun`(com.squareup.kotlinpoet.KModifier.OVERRIDE, "k", returns = INT) { ret(2.lit) }
            }
        }
        assertCompiles(
            out.replace("package com.example\n", "") +
                "\ninterface Iface { fun g(): Int }\ninterface J { fun k(): Int }\n" +
                "open class Base\nclass D : Iface { override fun g(): Int = 1 }\n",
        )
    }

    /** The duplicate check reads the same map the delegate is stored in, so it still fires. */
    @Test
    fun `a second superinterface call for the same type is still refused, delegated or not`() {
        assertFailsWith<IllegalStateException> {
            render { `class`("C") { superinterface(iface); superinterface(iface, by = newImpl) } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`("C") { superinterface(iface, by = newImpl); superinterface(iface) } }
        }
    }

    /**
     * ADR 0008 does **not** reach here, and the brief said it did. `checkOwned` is a [BlockScope]
     * extension and a [TypeScope] has none, so a superclass's constructor arguments have never been
     * ownership-checked either. This test pins the two behaving *identically*, so that a future
     * retrofit covers both or neither rather than one silently diverging.
     */
    @Test
    fun `a delegate and a superclass argument treat a foreign handle the same way`() {
        val foreign = attachedBlock("other").run { `val`("stranger", iface, init = newImpl) }
        val base = className("com.example", "Base")
        // Neither is refused. Both render the foreign name, which does not resolve — the same gap,
        // in the same position, reached by two constructs.
        val delegated = render { `class`("C") { superinterface(iface, by = foreign) } }
        val argumented = render { `class`("C") { superclass(base, foreign) } }
        assertTrue("by stranger" in delegated, delegated)
        assertTrue("Base(stranger)" in argumented, argumented)
    }
}
