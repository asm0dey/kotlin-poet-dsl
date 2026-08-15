package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.INNER
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PROTECTED
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's first construct, and D31's one **silent** failure: `` `class`(ENUM, "Color") { } `` produced a
 * valid enum builder and there was no way to put an entry in it.
 *
 * The acceptance spec is a **language-side measurement**, run at a command line on `kotlinc`,
 * `kotlinc-js` and `kotlinc-wasm` 2.4.10, one file per cell, 49 + 192 + 27 cells, **all three
 * frontends agreeing on every one**. Two axes, both recorded in D43:
 *
 * **What an entry may carry, against the enum's primary constructor** — ordinary constructor-call
 * resolution, nothing enum-specific:
 *
 *     enum class E              { A }        clean     enum class E(val x: Int) { A }      no value passed
 *     enum class E              { A() }      clean     enum class E(val x: Int) { A() }     for parameter 'x'.
 *     enum class E              { A(1) }     too many  enum class E(val x: Int) { A(1) }   clean
 *                                            arguments enum class E(val x: Int) { A(1) { } }  clean
 *     enum class E              { A { } }    clean     enum class E(val x: Int = 1) { A }  clean
 *     enum class E(val x: Int, val y: Int = 2) { A(1) }      clean
 *     enum class E(val x: Int = 1, val y: Int)  { A(1) }     no value passed for parameter 'y'.
 *     enum class E(vararg val x: Int)           { A }        clean   — and `A(1, 2, 3)` too
 *
 * **What may appear in an entry body** — a strict subset of a class body, and *identical* to an
 * anonymous object's body on every question but one:
 *
 *     A { val p: Int = 1 }          clean     A { constructor(q: Int) }   objects cannot have
 *     A { var p: Int = 1 }          clean                                  constructors.
 *     A { abstract val p: Int }     clean     A { class N }               'Class' is prohibited here.
 *     A { fun f(): Int = 1 }        clean     A { object O }              named object 'O' cannot be
 *     A { abstract fun f(): Int }   clean                                  local.
 *     A { fun g() { } }             clean     A { interface I }           'Interface' is prohibited
 *     A { init { } }                clean                                  here.
 *     A { override fun … }          clean     A { companion object }      modifier 'companion' is not
 *     A { private val p: Int = 1 }  clean                                  applicable inside 'enum entry'.
 *     A : Iface { }                 syntax error — an entry has no supertype of its own
 *
 * and the **one** question the two positions answer differently, which is why they are two
 * `kindName`s and not one:
 *
 *     enum class E { A { protected val p: Int = 1 } }   modifier 'protected' is not applicable
 *                                                       inside 'enum entry'.
 *     val v = object { protected val p: Int = 1 }       clean
 *
 * The `abstract` rows are the surprise and they are what a control row is for: `class C { abstract
 * fun f(): Int }` is *abstract function 'f' in non-abstract class 'C'* and the identical member in an
 * entry body is clean on all three frontends, so [Scope.abstractMemberAllowed] had to be widened
 * rather than left to refuse it.
 */
@OptIn(ExperimentalCompilerApi::class)
class EnumEntriesTest {
    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    // --- the gap D31 filed ---------------------------------------------------------------------

    /**
     * The base fact, pinned rather than described: an enum with no entries renders, and **the render
     * is valid Kotlin** on all three frontends. So the audit's "silent failure" is a *capability*
     * gap and not a Global Constraint 26 violation, and the fix is a construct, not a guard — an
     * empty `enum class` refused here would be a false rejection of code every frontend compiles.
     */
    @Test
    fun `an enum class with no entries renders and is valid Kotlin`() {
        val out = render { `class`(ENUM, "Color") { } }
        assertTrue("public enum class Color" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    // --- the construct -------------------------------------------------------------------------

    @Test
    fun `entries render in declaration order`() {
        val out = render {
            `class`(ENUM, "Color") {
                enumEntry("RED")
                enumEntry("GREEN")
                enumEntry("BLUE")
            }
        }
        assertTrue("RED,\n  GREEN,\n  BLUE," in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    @Test
    fun `an entry carries constructor arguments`() {
        val out = render {
            `class`(ENUM, "Color", param(ParamKind.VAL, "rgb", INT)) {
                enumEntry("RED", 0xFF0000.lit)
                enumEntry("BLACK", 0.lit)
            }
        }
        assertTrue("RED(16_711_680)" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    @Test
    fun `an entry carries a body`() {
        val out = render {
            `class`(ENUM, "Op") {
                enumEntry("PLUS") {
                    `fun`(OVERRIDE, "apply", param("a", INT), param("b", INT), returns = INT) { a, b ->
                        ret(a + b)
                    }
                }
                `fun`(ABSTRACT, "apply", param("a", INT), param("b", INT), returns = INT) { _, _ -> }
            }
        }
        assertTrue("PLUS {" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    @Test
    fun `an entry carries both arguments and a body`() {
        val out = render {
            `class`(ENUM, "Color", param(ParamKind.VAL, "rgb", INT)) {
                enumEntry("RED", 1.lit) { `fun`("hex", returns = STRING) { ret("f".lit) } }
            }
        }
        assertTrue("RED(1) {" in out, out)
        assertCompiles(out)
    }

    @Test
    fun `an entry carries kdoc`() {
        val out = render { `class`(ENUM, "Color") { enumEntry("RED", kdoc = "The warm one.") } }
        assertTrue("The warm one." in out, out)
        assertCompiles(out)
    }

    // --- the guards, each with the compiler's own sentence and a control row --------------------

    /** The container must be an enum: without this, `class C { A }` renders and nothing accepts it. */
    @Test
    fun `an entry outside an enum class is refused`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`("C") { enumEntry("A") } }
        }
        assertTrue("enumEntry" in e.message!!, e.message!!)
        assertTrue("enum class" in e.message!!, e.message!!)
        // The control: the identical entry in an enum class renders and compiles.
        assertCompiles(render { `class`(ENUM, "C") { enumEntry("A") } })
    }

    /** KotlinPoet keeps entries in a `Map`, so a duplicate silently overwrites the first. */
    @Test
    fun `a duplicate entry name is refused`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A"); enumEntry("A") } }
        }
        // The entry-specific sentence, not merely "already declared": the property check registers
        // the same name and would otherwise answer first with a message about properties.
        assertTrue("entries in a map" in e.message!!, e.message!!)
        // The control: two differently-named entries.
        assertCompiles(render { `class`(ENUM, "C") { enumEntry("A"); enumEntry("B") } })
    }

    /** *conflicting declarations* — an entry and a property in one enum share a namespace. */
    @Test
    fun `an entry colliding with a property is refused, in both writing orders`() {
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { `val`("A", INT, init = 1.lit); enumEntry("A") } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A"); `val`("A", INT, init = 1.lit) } }
        }
        // The control, and it is the reason this is not simply "an entry name is taken": an entry
        // and a **function** of the same name are clean on all three frontends.
        assertCompiles(
            render {
                `class`(ENUM, "C") { enumEntry("A"); `fun`("A", returns = INT) { ret(1.lit) } }
            },
        )
    }

    /** An entry name and a nested type's name collide too — *conflicting declarations*. */
    @Test
    fun `an entry colliding with a nested type is refused`() {
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { `class`("A") { }; enumEntry("A") } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A"); `class`("A") { } } }
        }
    }

    // --- the argument rule, answered in `finish` so writing order cannot decide it ---------------

    @Test
    fun `an entry with arguments in an enum with no primary constructor is refused`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A", 1.lit) } }
        }
        assertTrue("too many arguments" in e.message!!, e.message!!)
    }

    @Test
    fun `an entry with no arguments in an enum whose parameter has no default is refused`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C", param(ParamKind.VAL, "x", INT)) { enumEntry("A") } }
        }
        assertTrue("no value passed for parameter 'x'" in e.message!!, e.message!!)
    }

    /**
     * The reason the check lives in [TypeScope.finish]: a `constructorParam` written *after* the
     * entry still supplies the parameter, so an eager check would answer on writing order alone.
     * This is the same deferral D25's three checks take.
     */
    @Test
    fun `a constructorParam written after the entry still supplies it`() {
        val out = render {
            `class`(ENUM, "C") {
                enumEntry("A", 1.lit)
                constructorParam(ParamKind.VAL, "x", INT)
            }
        }
        assertCompiles(out)
    }

    /** A defaulted parameter is what makes a bare entry legal again — the control for both rows. */
    @Test
    fun `a defaulted parameter lets an entry carry no arguments`() {
        val out = render {
            `class`(ENUM, "C", param(ParamKind.VAL, "x", INT, default = 1.lit)) {
                enumEntry("A")
                enumEntry("B", 2.lit)
            }
        }
        assertCompiles(out)
    }

    /** `enum class E(vararg val x: Int) { A }` and `{ A(1, 2, 3) }` are both clean, so the count
     * rule is switched off entirely rather than approximated when a `vararg` is present. */
    @Test
    fun `a vararg parameter switches the count rule off`() {
        assertCompiles(
            render {
                `class`(ENUM, "C", param(ParamKind.VAL, "x", INT, modifiers = VARARG)) {
                    enumEntry("A")
                    enumEntry("B", 1.lit, 2.lit)
                }
            },
        )
    }

    // --- the entry body ------------------------------------------------------------------------

    @Test
    fun `an entry body holds no nested classifier, constructor or companion object`() {
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `class`("N") { } } } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `object`("O") { } } } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `interface`("I") { } } } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { companionObject { } } } }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `constructor`(param("q", INT)) { } } } }
        }
    }

    /**
     * …and the one nested classifier it **does** hold — the row the 192-cell sweep could not reach,
     * because every one of its cells was a `val`, a `var` or a `fun` and no nested-class declaration
     * form was ever built. See [AnonymousObjectTest] for the other half of the family and for the
     * mechanism; the two positions agree here as they do everywhere else.
     *
     * Measured, one file per cell, all three frontends 2.4.10:
     *
     *     enum class E { A { inner class N { fun f(): Int = 1 }
     *                        override fun g(): Int = N().f() }; abstract fun g(): Int }   clean
     *     enum class E { A { public inner class N } }                                     clean
     *     enum class E { A { class N { fun f(): Int = 1 } } }   'Class' is prohibited here.
     */
    @Test
    fun `an entry body holds an inner class`() {
        val out = render {
            `class`(ENUM, "C") {
                enumEntry("A") {
                    `class`(INNER, "N") { `fun`("f", returns = INT) { ret(1.lit) } }
                }
            }
        }
        assertTrue("inner class N" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    @Test
    fun `an entry body holds no supertype of its own`() {
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { superclass(className("com.example", "B")) } } }
        }
        assertFailsWith<IllegalStateException> {
            render {
                `class`(ENUM, "C") { enumEntry("A") { superinterface(className("com.example", "I")) } }
            }
        }
    }

    /** `modifier 'protected' is not applicable inside 'enum entry'` — and the control is the
     * *anonymous object* body, where the identical member is clean on all three frontends. */
    @Test
    fun `protected is refused in an entry body`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `val`(PROTECTED, "p", INT, init = 1.lit) } } }
        }
        assertTrue("protected" in e.message!!.lowercase(), e.message!!)
        // The controls, all clean on all three frontends.
        assertCompiles(
            render { `class`(ENUM, "C") { enumEntry("A") { `val`(PRIVATE, "p", INT, init = 1.lit) } } },
        )
        assertCompiles(render { `class`(ENUM, "C") { enumEntry("A") { `val`("p", INT, init = 1.lit) } } })
    }

    /**
     * The `abstract` family, and the one place this round's language measurement and KotlinPoet
     * disagree.
     *
     * `enum class E { A { abstract fun f(): Int } }` and the `val` twin are **clean on all three
     * frontends**, where `class C { abstract fun f(): Int }` is *abstract function 'f' in
     * non-abstract class 'C'*. So the language does not refuse this. KotlinPoet does:
     * `TypeSpec.Builder.build` raises `IllegalArgumentException: non-abstract type null cannot
     * declare abstract function f`, and an anonymous builder can never carry ABSTRACT because
     * `addModifiers` is `check(!isAnonymousClass)`.
     *
     * That is D20's shape, so it gets D20's treatment: refused with a message that says *KotlinPoet
     * cannot render it*, not one quoting a language rule that does not exist here. This test is also
     * the **canary** — it fails the day KotlinPoet renders the shape, which is when the refusal
     * should be dropped.
     */
    @Test
    fun `an abstract member in an entry body is refused as a render gap, not as a language rule`() {
        val fn = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `fun`(ABSTRACT, "f", returns = INT) { } } } }
        }
        assertTrue("KotlinPoet 2.3.0 cannot render it" in fn.message!!, fn.message!!)
        val prop = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "C") { enumEntry("A") { `val`(ABSTRACT, "p", INT) } } }
        }
        assertTrue("KotlinPoet 2.3.0 cannot render it" in prop.message!!, prop.message!!)
        // The canary: the moment `addModifiers` stops refusing an anonymous builder, or `build`
        // stops requiring ABSTRACT on it, this assertion fails and the refusal above can go.
        val e = assertFailsWith<IllegalStateException> {
            com.squareup.kotlinpoet.TypeSpec.anonymousClassBuilder().addModifiers(emptyList())
        }
        assertTrue("forbidden on anonymous types" in e.message!!, e.message!!)
        // The control, and it is what makes the refusal narrow: the same members with bodies and
        // values are clean here, and the *language* row the refusal does not deny is compiled by
        // hand rather than through the DSL, because the DSL has no route to it.
        assertCompiles("enum class E {\n  A { abstract fun f(): Int }\n}\n")
        assertCompilesEverywhereButJvm("enum class E {\n  A { abstract val p: Int }\n}\n")
    }

    @Test
    fun `an entry body holds an init block, a property and a function`() {
        val out = render {
            `class`(ENUM, "C") {
                enumEntry("A") {
                    `val`("p", INT, init = 1.lit)
                    `var`("q", INT, init = 2.lit)
                    `init` { +call("println", 1.lit) }
                    `fun`("f", returns = INT) { ret(1.lit) }
                }
            }
        }
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    /** An entry body sees the enum's own properties — measured clean, so ADR 0008 must accept the
     * handle rather than treat the entry as a re-parented nested type. */
    @Test
    fun `an entry body sees the enum's own property`() {
        val out = render {
            `class`(ENUM, "C", param(ParamKind.VAL, "x", INT)) {
                enumEntry("A", 1.lit) { `fun`("f", returns = INT) { ret(expression("x")) } }
            }
        }
        assertCompiles(out)
    }

    // --- the shadow ----------------------------------------------------------------------------

    /**
     * `context(t: TypeScope)` does not stop a call inside a member body — the enclosing type's
     * context parameter is still in scope there — so an `enumEntry` written in a function would
     * silently attach an entry to the enclosing enum. ADR 0002's shadow, generated from the same
     * row that generated the real overload (D7).
     */
    @Test
    fun `enumEntry in a block body is a compile error`() {
        val result = compileDsl(
            """
            import com.squareup.kotlinpoet.KModifier.ENUM
            fun build() = file("com.example", "A") {
                `class`(ENUM, "C") {
                    `fun`("f") { enumEntry("A") }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("enumEntry" in result.messages, result.messages)
    }
}
