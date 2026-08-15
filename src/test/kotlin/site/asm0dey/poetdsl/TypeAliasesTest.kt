package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PROTECTED
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeAliasSpec
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's `` `typealias` ``, and the answer to the question E1 opened and three batches did not measure:
 * **is a nested `typealias` valid for the target Kotlin version?**
 *
 * It is. Measured, one file per cell, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all three
 * agreeing on every one of 96 + 13 cells (D43). The nested form is offered; the *local* form is not,
 * and that includes an enum entry's and an anonymous object's body, both of which are local classes.
 */
@OptIn(ExperimentalCompilerApi::class)
class TypeAliasesTest {
    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    // --- the shape ------------------------------------------------------------------------------

    @Test
    fun `a typealias renders at file level`() {
        val out = render { `typealias`("Handler", STRING) }
        assertTrue("public typealias Handler = String" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    /** E1's open question, answered: `class C { typealias S = String }` is clean, and `C.S` resolves. */
    @Test
    fun `a nested typealias renders in a class, an object, an interface and a companion object`() {
        val inClass = render { `class`("C") { `typealias`("S", STRING) } }
        assertTrue("public typealias S = String" in inClass, inClass)
        assertCompiles(inClass)
        assertCompilesEverywhereButJvm(inClass)
        assertCompiles(render { `object`("O") { `typealias`("S", STRING) } })
        assertCompiles(render { `interface`("I") { `typealias`("S", STRING) } })
        assertCompiles(render { `class`("C") { companionObject { `typealias`("S", STRING) } } })
        assertCompiles(render { `class`("O") { `class`("N") { `typealias`("S", STRING) } } })
        // …and the reference: the alias is reachable as `C.S`, which is what makes it worth offering.
        assertCompiles(
            render {
                `class`("C") { `typealias`("S", STRING) }
                `val`("v", className("com.example", "C", "S"), init = "a".lit)
            },
        )
    }

    @Test
    fun `a typealias takes type parameters, a visibility and annotations`() {
        val t = typeVariable("T")
        val generic = render { `typealias`("Rows", LIST.of(t), typeVariables = listOf(t)) }
        assertTrue("typealias Rows<T> = List<T>" in generic, generic)
        assertCompiles(generic)
        assertCompiles(render { `typealias`(PRIVATE, "S", STRING); `val`("v", INT, init = 1.lit) })
        assertCompiles(render { `typealias`(INTERNAL, "S", STRING); `val`("v", INT, init = 1.lit) })
        val annotated = render {
            `typealias`(
                annotation(className("kotlin", "Deprecated"), args = arrayOf("why".lit)),
                "S",
                STRING,
            )
        }
        assertTrue("@Deprecated" in annotated, annotated)
    }

    @Test
    fun `a typealias takes kdoc`() {
        val out = render { `typealias`("S", STRING, kdoc = "A short string.") }
        assertTrue("A short string." in out, out)
        assertCompiles(out)
    }

    // --- the local form, refused ------------------------------------------------------------------

    /**
     * *the feature "local type aliases" is experimental … '-Xlocal-type-aliases'* on all three
     * frontends. This DSL emits source for compilers it does not configure and passes no flag, so it
     * refuses — the same call E2f made for `of` as an operator name.
     *
     * **No shadow**, deliberately: an `@Deprecated(ERROR)` overload would freeze a temporary compiler
     * state into a permanently locked surface, which is exactly D20's argument for `` `class` ``
     * having none. The refusal is a run-time [IllegalStateException] instead.
     */
    @Test
    fun `a typealias in a block body is refused, naming the flag`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `fun`("f") { `typealias`("S", STRING) } }
        }
        assertTrue("-Xlocal-type-aliases" in e.message!!, e.message!!)
        // …and the refusal happens **before** the name is registered, which is what makes the early
        // guard load-bearing rather than a duplicate of the `when`'s D17 branch: falsifying it left
        // every assertion above passing, because the `when` still refuses — one scope later, with
        // the name already burned. Task 12's ordering, pinned.
        val block = attachedBlock()
        runCatching { with(block) { `typealias`("S", STRING) } }
        assertTrue(block.declaredTypeNames.isEmpty(), block.declaredTypeNames.toString())
        // The control: the identical alias one scope out.
        assertCompiles(render { `class`("C") { `typealias`("S", STRING) } })
    }

    /** An enum entry's body and an anonymous object's are local class bodies, so the same rule. */
    @Test
    fun `a typealias in an anonymous body is refused for the same reason`() {
        val entry = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "E") { enumEntry("A") { `typealias`("S", STRING) } } }
        }
        assertTrue("-Xlocal-type-aliases" in entry.message!!, entry.message!!)
        assertTrue("enum entry" in entry.message!!, entry.message!!)
        val anon = assertFailsWith<IllegalStateException> {
            render { `val`("v", INT, init = anonymousObject { `typealias`("S", STRING) }) }
        }
        assertTrue("-Xlocal-type-aliases" in anon.message!!, anon.message!!)
        assertTrue("anonymous object" in anon.message!!, anon.message!!)
        // The control: the same alias in the *enum class's* body rather than an entry's — which
        // needs a second member, for the reason `an enum whose only member is a type alias does not
        // render` gives.
        assertCompiles(
            render {
                `class`(ENUM, "E") {
                    enumEntry("A")
                    `val`("p", INT, init = 1.lit)
                    `typealias`("S", STRING)
                }
            },
        )
    }

    /**
     * The canary. It fails the day `-Xlocal-type-aliases` stabilises, which is when the three
     * refusals above should be dropped — the same shape E2f gave `of`, whose subtraction becomes a
     * false rejection when collection literals ship.
     */
    @Test
    fun `local type aliases are still experimental in the in-suite compiler`() {
        val result = compile("fun f() {\n  typealias S = String\n  println(\"\" as S)\n}\n")
        assertTrue("local type aliases" in result.messages, result.messages)
    }

    // --- the modifier family ----------------------------------------------------------------------

    /**
     * E2f's rule, applied where it would next have been broken: **derive the set or pin it against
     * its source**. `TYPE_ALIAS_MODIFIERS` is a hand-written list here and would be a sound-looking
     * argument built on an unverified set; this reads
     * `TypeAliasSpec.Builder.Companion.ALLOWABLE_MODIFIERS` by reflection and fails when KotlinPoet's
     * moves.
     */
    @Test
    fun `the allowed modifier set equals KotlinPoet's own`() {
        val field = TypeAliasSpec.Builder::class.java.getDeclaredField("ALLOWABLE_MODIFIERS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val kotlinPoets = field.get(null) as Set<KModifier>
        assertEquals(kotlinPoets, TYPE_ALIAS_MODIFIERS, "KotlinPoet's allowable set has moved")
    }

    /**
     * `protected typealias S = String` in a class body is **clean on all three frontends** and
     * KotlinPoet cannot render it. D20's shape again, so the message says which, and the language row
     * is compiled by hand because the DSL has no route to it.
     */
    @Test
    fun `PROTECTED is refused as a render gap`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`("C") { `typealias`(PROTECTED, "S", STRING) } }
        }
        assertTrue("KotlinPoet 2.3.0 cannot render it" in e.message!!, e.message!!)
        assertCompiles("class C { protected typealias S = String }\n")
        // The canary, and the control: PRIVATE and INTERNAL are what the remedy names, and both work.
        assertCompiles(render { `class`("C") { `typealias`(PRIVATE, "S", STRING) } })
        assertCompiles(render { `class`("C") { `typealias`(INTERNAL, "S", STRING) } })
    }

    /**
     * Every `KModifier` outside KotlinPoet's set except `PROTECTED` is a **language** rule, and the
     * message quotes it: *modifier 'x' is not applicable to 'typealias'*, on all three frontends,
     * across the 96-cell sweep. `EXPECT` is in that group and is the one worth naming — `expect
     * typealias` is *modifier 'expect' is not applicable to 'typealias'* even under
     * `-Xmulti-platform`, while `actual typealias` is the ordinary multiplatform idiom and is allowed.
     */
    @Test
    fun `every other modifier is refused with the language's own sentence`() {
        KModifier.entries.filterNot { it in TYPE_ALIAS_MODIFIERS || it == PROTECTED }.forEach { m ->
            val e = assertFailsWith<IllegalStateException>("$m was not refused") {
                render { `typealias`(m, "S", STRING) }
            }
            assertTrue("not applicable to 'typealias'" in e.message!!, "$m: ${e.message}")
        }
        // The controls: the four KotlinPoet allows, all rendering.
        TYPE_ALIAS_MODIFIERS.forEach { m -> render { `typealias`(m, "S", STRING) } }
    }

    /** The interface body's own rule, through the predicate `declareType` and `buildFun` share. */
    @Test
    fun `INTERNAL is refused in an interface body and PRIVATE is not`() {
        assertFailsWith<IllegalStateException> {
            render { `interface`("I") { `typealias`(INTERNAL, "S", STRING) } }
        }
        assertCompiles(render { `interface`("I") { `typealias`(PRIVATE, "S", STRING) } })
    }

    /** *members are prohibited in annotation classes* reaches a type alias like every other member. */
    @Test
    fun `a typealias is refused in an annotation class body`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ANNOTATION, "Ann") { `typealias`("S", STRING) } }
        }
        assertTrue("members are prohibited in annotation classes" in e.message!!, e.message!!)
        // The control: the same alias in an ordinary class.
        assertCompiles(render { `class`("C") { `typealias`("S", STRING) } })
    }

    // --- names and type parameters ------------------------------------------------------------------

    /** *redeclaration* — an alias is a type name and shares the classifiers' registry. */
    @Test
    fun `a typealias colliding with a type or another alias is refused`() {
        assertFailsWith<IllegalStateException> {
            render { `typealias`("S", STRING); `typealias`("S", INT) }
        }
        assertFailsWith<IllegalStateException> {
            render { `class`("S") { }; `typealias`("S", STRING) }
        }
        assertFailsWith<IllegalStateException> {
            render { `typealias`("S", STRING); `class`("S") { } }
        }
        // The control: the same alias in two different containers.
        assertCompiles(
            render { `typealias`("S", STRING); `class`("C") { `typealias`("S", INT) } },
        )
    }

    /**
     * The type-parameter rule no other declaration in this DSL has. Measured, all three frontends:
     *
     *     typealias L<T : Number> = List<T>   bounds on type alias parameters are prohibited.
     *     typealias L<out T> = List<T>        variance annotations are only allowed for type
     *     typealias L<reified T> = List<T>     parameters of classes and interfaces. / applying
     *                                          reified modifier … makes no sense.
     *     typealias L<T> = List<T>            clean — the control
     */
    @Test
    fun `a type alias parameter takes no bound, no variance and no reified`() {
        val bounded = typeVariable("T", className("kotlin", "Number"))
        val e = assertFailsWith<IllegalStateException> {
            render { `typealias`("L", LIST.of(bounded), typeVariables = listOf(bounded)) }
        }
        assertTrue("bounds on type alias parameters are prohibited" in e.message!!, e.message!!)
        val variant = typeVariable("T", variance = com.squareup.kotlinpoet.KModifier.OUT)
        assertFailsWith<IllegalStateException> {
            render { `typealias`("L", LIST.of(variant), typeVariables = listOf(variant)) }
        }
        val reified = typeVariable("T", reified = true)
        assertFailsWith<IllegalStateException> {
            render { `typealias`("L", LIST.of(reified), typeVariables = listOf(reified)) }
        }
        // The control, which is also what makes the bound rule's remedy real.
        val plain = typeVariable("T")
        assertCompiles(render { `typealias`("L", LIST.of(plain), typeVariables = listOf(plain)) })
    }

    /** `` `typealias` `` is `context(s: Scope)` and takes no body, so ABSTRACT and the rest never
     * reach a container question — this is the pair that says the container rules still run. */
    @Test
    fun `an abstract typealias is refused before any container is consulted`() {
        assertFailsWith<IllegalStateException> { render { `typealias`(ABSTRACT, "S", STRING) } }
        assertFailsWith<IllegalStateException> {
            render { `class`("C") { `typealias`(ABSTRACT, "S", STRING) } }
        }
    }

    /**
     * The render gap the control row found, and the round's second one.
     *
     * An `enum class` body needs a `;` before any member, and KotlinPoet writes it for a property, a
     * function, a nested type or an `init` block — never for a type alias. So an enum whose only
     * member is an alias renders `enum class E { A, typealias S = String }`, which is a **syntax
     * error** on all three frontends.
     *
     * Refused in `TypeScope.finish` rather than at the call, because a property written *after* the
     * alias fixes it — the last row here is what says so, and an eager check would answer on writing
     * order alone.
     */
    @Test
    fun `an enum whose only member is a type alias does not render`() {
        val e = assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "E") { enumEntry("A"); `typealias`("S", STRING) } }
        }
        assertTrue("Expecting ';' after the last enum entry" in e.message!!, e.message!!)
        // …and with no entries either, which is the row that says this is not an entry rule.
        assertFailsWith<IllegalStateException> {
            render { `class`(ENUM, "E") { `typealias`("S", STRING) } }
        }
        // The four members that supply the `;`, each on its own, and each with the alias written
        // FIRST so that the deferral is what is being tested rather than the writing order.
        assertCompiles(
            render { `class`(ENUM, "E") { `typealias`("S", STRING); `val`("p", INT, init = 1.lit) } },
        )
        assertCompiles(
            render {
                `class`(ENUM, "E") { `typealias`("S", STRING); `fun`("f", returns = INT) { ret(1.lit) } }
            },
        )
        assertCompiles(render { `class`(ENUM, "E") { `typealias`("S", STRING); `class`("N") { } } })
        assertCompiles(
            render {
                `class`(ENUM, "E") { `typealias`("S", STRING); `init` { +call("println", 1.lit) } }
            },
        )
        // The canary: the day KotlinPoet writes the `;` for a type alias, this refusal can go.
        val bare = com.squareup.kotlinpoet.TypeSpec.classBuilder("E")
            .addModifiers(ENUM)
            .addEnumConstant("A")
            .addTypeAlias(TypeAliasSpec.builder("S", STRING).build())
            .build()
        assertTrue(";" !in bare.toString(), bare.toString())
    }
}
