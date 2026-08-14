package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.OPEN
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deviation D25: `` `this`(…) `` and `` `super`(…) ``, the constructor delegation calls, and the two
 * guards they relax.
 *
 * What is legal after D25 and what is still rejected is the whole subject: every rejection here was
 * measured against `kotlinc` first (`Primary constructor call expected.`, `Supertype initialization
 * is impossible without a primary constructor.`), and every acceptance is compiled.
 */
class DelegationTest {
    private val base = ClassName("com.example", "Base")

    /** The shape D25 was written for, verbatim from the deviation. */
    @Test
    fun `a secondary constructor delegates to the primary one with this`() {
        val out = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG), param(VAL, "name", STRING)) { _, _ ->
                `constructor`(param("id", LONG)) { pid ->
                    `this`(pid, "anonymous".lit)
                }
            }
        }.toString()
        // `id2`, not `id`: the secondary constructor's parameter would shadow the primary
        // constructor's property of that name, so ADR 0009's uniquifier renames it — pre-existing
        // behaviour, unchanged by D25, and the delegation call names whatever was rendered.
        assertTrue("""public constructor(id2: Long) : this(id2, "anonymous")""" in out, out)
        assertCompiles(out)
    }

    /**
     * The delegation call is captured, not emitted: it is part of the constructor's *header*, so it
     * lands there even when it is written after the body's statements, and it never appears as a
     * statement inside the braces.
     */
    @Test
    fun `the delegation call renders in the header, not the body`() {
        val out = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG)) { _ ->
                `constructor`(param("raw", STRING)) { raw ->
                    +call("println", raw)
                    `this`(1L.lit)
                }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Long
            import kotlin.String

            public class User(
              public val id: Long,
            ) {
              public constructor(raw: String) : this(1L) {
                println(raw)
              }
            }

            """.trimIndent(),
            out,
        )
        assertCompiles(out)
    }

    /** `` `super` `` is the sibling construct, and completes D26's secondary-constructor half. */
    @Test
    fun `a secondary constructor delegates to the superclass with super`() {
        val out = file("com.example", "Impl") {
            `class`(OPEN, "Base", param(VAL, "n", INT)) { _ -> }
            `class`("Impl") {
                superclass(base)
                `constructor`(param("n", INT)) { n -> `super`(n) }
            }
        }.toString()
        assertTrue("public constructor(n: Int) : super(n)" in out, out)
        assertCompiles(out)
    }

    /**
     * A class with no declared superclass extends `Any`, and `: super()` is still valid Kotlin
     * there (measured), so nothing rejects it: the DSL does not model what the supertype's
     * constructors look like anywhere else either.
     */
    @Test
    fun `super with no declared superclass is left to kotlinc`() {
        val out = file("com.example", "Bare") {
            `class`("Bare") { `constructor`(param("x", INT)) { x -> `super`(); +call("println", x) } }
        }.toString()
        assertTrue("public constructor(x: Int) : super()" in out, out)
        assertCompiles(out)
    }

    /** Delegating to another *secondary* constructor needs no primary one, and is left alone. */
    @Test
    fun `a secondary constructor delegates to another secondary one`() {
        val out = file("com.example", "Chain") {
            `class`("Chain") {
                `constructor`(param("a", INT)) { a -> `this`(a, 1.lit) }
                `constructor`(param("a", INT), param("b", INT)) { a, b -> +call("println", a + b) }
            }
        }.toString()
        assertTrue("public constructor(a: Int) : this(a, 1)" in out, out)
        assertCompiles(out)
    }

    /**
     * Header superclass arguments, a primary constructor and a delegating secondary one: the
     * combination D26 had to reject and D25 makes legal.
     */
    @Test
    fun `header superclass arguments coexist with a delegating secondary constructor`() {
        val out = file("com.example", "Impl") {
            `class`(OPEN, "Base", param(VAL, "n", INT)) { _ -> }
            `class`("Impl", param(VAL, "size", INT)) { size ->
                superclass(base, size)
                `constructor`{ `this`(0.lit) }
            }
        }.toString()
        assertTrue("public class Impl(" in out, out)
        assertTrue("public constructor() : this(0)" in out, out)
        assertCompiles(out)
    }

    /**
     * The header-arguments guard is checked once the type is complete, not at the call that first
     * looks suspicious — a `constructorParam` written *after* both other constructs still makes the
     * combination legal, and an eager check would have rejected this valid Kotlin on writing order
     * alone.
     */
    @Test
    fun `a constructor parameter written last still legalizes header arguments`() {
        val out = file("com.example", "Impl") {
            `class`(OPEN, "Base", param(VAL, "n", INT)) { _ -> }
            `class`("Impl") {
                superclass(base, 1.lit)
                `constructor` { `this`(2.lit) }
                constructorParam(VAL, "size", INT)
            }
        }.toString()
        assertTrue("public constructor() : this(2)" in out, out)
        assertCompiles(out)
    }

    // --- what is still rejected -------------------------------------------------------------

    /**
     * `` `super` `` does not satisfy the requirement: a secondary constructor of a class that has a
     * primary one must delegate to *that*, and `: super(…)` there is the same
     * `e: Primary constructor call expected.` (measured). Both orders again.
     */
    @Test
    fun `a secondary constructor delegating with super under a primary one is rejected`() {
        val paramFirst = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") {
                `class`("Box", param(VAL, "size", INT)) { _ ->
                    `constructor`(param("n", INT)) { n -> `super`(n) }
                }
            }
        }
        val ctorFirst = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") {
                `class`("Box") {
                    `constructor`(param("n", INT)) { n -> `super`(n) }
                    constructorParam(VAL, "size", INT)
                }
            }
        }
        for (failure in listOf(paramFirst, ctorFirst)) {
            assertEquals(SECONDARY_MUST_DELEGATE_TO_PRIMARY, failure.message)
        }
    }

    /**
     * Header arguments with a secondary constructor and *no* primary one stay rejected however the
     * secondary delegates: with nothing to carry the header call, `kotlinc` answers
     * `e: Supertype initialization is impossible without a primary constructor.` (measured).
     */
    @Test
    fun `header arguments with no primary constructor are rejected even when the secondary delegates`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Impl") {
                `class`("Impl") {
                    superclass(base, 1.lit)
                    `constructor`(param("x", INT)) { _ -> `this`() }
                    `constructor` { }
                }
            }
        }
        assertEquals(superclassArgsPlusSecondary("class"), failure.message)
    }

    /** A constructor delegates to exactly one other constructor, whichever pair is written. */
    @Test
    fun `delegating twice is rejected`() {
        for ((second, build) in listOf<Pair<String, () -> Unit>>(
            "this" to {
                file("com.example", "Box") {
                    `class`("Box") { `constructor`(param("x", INT)) { x -> `this`(x); `this`(x) } }
                }
            },
            "super" to {
                file("com.example", "Box") {
                    `class`("Box") { `constructor`(param("x", INT)) { x -> `this`(x); `super`(x) } }
                }
            },
        )) {
            val failure = assertFailsWith<IllegalStateException>(second) { build() }
            assertEquals(
                "`$second`: this constructor already delegates with `this(…)`. A constructor " +
                    "delegates to exactly one other constructor.",
                failure.message,
            )
        }
    }

    /**
     * A delegation call is a constructor header, and Kotlin has one nowhere else — not in a
     * function body, not in a lambda, not inside an `if`, and not in a detached `stmts { }`
     * fragment, which could otherwise be spliced into anything.
     */
    @Test
    fun `a delegation call outside a secondary constructor body is rejected`() {
        val message = "`this`: a constructor delegation call is only valid directly in a secondary " +
            "`constructor`'s body — not in a function body, a lambda, a nested block or a " +
            "`stmts { }` fragment."
        val inFunction = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f") { `this`(1.lit) } }
        }
        val inNestedBlock = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("Box") { `constructor` { `if`(1.lit eq 1.lit) { `this`(1.lit) } } }
            }
        }
        val inLambda = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("Box") { `constructor` { +lambda { `this`(1.lit) } } }
            }
        }
        val inFragment = assertFailsWith<IllegalStateException> { stmts { `this`(1.lit) } }
        for (failure in listOf(inFunction, inNestedBlock, inLambda, inFragment)) {
            assertEquals(message, failure.message)
        }
    }

    /**
     * The compiler's own word on the three shapes the two guards still reject, kept in the repo as
     * the evidence they rest on. The DSL refuses to produce this Kotlin, so it is written by hand
     * here — the same shape `TypeScopeTest` pins its local-class guard with.
     */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `the still-rejected combinations are what kotlinc refuses`() {
        for ((source, diagnostic) in listOf(
            // A secondary constructor under a primary one, not delegating.
            "class Box(val size: Int) { constructor(other: String) { println(other) } }" to
                "Primary constructor call expected",
            // The same, delegating with `super` instead of `this`.
            """
            open class Base(val n: Int)
            class Box(val size: Int) : Base(size) { constructor(n: Int) : super(n) }
            """.trimIndent() to "Primary constructor call expected",
            // Header superclass arguments with secondary constructors and no primary one, however
            // the secondary delegates.
            """
            open class Base(val n: Int)
            class Impl : Base(1) { constructor(x: Int) : this() ; constructor() }
            """.trimIndent() to "Supertype initialization is impossible without a primary constructor",
        )) {
            val result = compile(source)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, source)
            assertTrue(diagnostic in result.messages, result.messages)
        }
    }

    /**
     * The same four wrong locations for `` `super` ``. The message interpolates the keyword, and
     * only the double-delegation test above ever saw it come out as `super`.
     */
    @Test
    fun `a super delegation call outside a secondary constructor body is rejected`() {
        val message = "`super`: a constructor delegation call is only valid directly in a secondary " +
            "`constructor`'s body — not in a function body, a lambda, a nested block or a " +
            "`stmts { }` fragment."
        val inFunction = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f") { `super`(1.lit) } }
        }
        val inNestedBlock = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("Box") { `constructor` { `if`(1.lit eq 1.lit) { `super`(1.lit) } } }
            }
        }
        val inLambda = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("Box") { `constructor` { +lambda { `super`(1.lit) } } }
            }
        }
        val inFragment = assertFailsWith<IllegalStateException> { stmts { `super`(1.lit) } }
        for (failure in listOf(inFunction, inNestedBlock, inLambda, inFragment)) {
            assertEquals(message, failure.message)
        }
    }

    /**
     * The one delegation cycle that is decidable: a class with no primary constructor and exactly
     * one secondary one, delegating with `` `this` ``, delegates to itself.
     */
    @Test
    fun `a lone secondary constructor delegating to itself is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") { `constructor`(param("q", INT)) { q -> `this`(q) } }
            }
        }
        assertEquals(LONE_SECONDARY_DELEGATING_TO_ITSELF, failure.message)
    }

    /** kotlinc's word on the shape that guard refuses to produce. */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `kotlinc refuses a self-delegating lone constructor`() {
        val result = compile("class A { constructor(q: Int) : this(q) }")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("cycle in the delegation calls chain" in result.messages, result.messages)
    }

    /**
     * The guard is deferred to `finish` for the same reason the header-arguments one is: a
     * `constructorParam` written *after* the constructor still gives the class a primary one, and
     * then the very same `` `this`(…) `` is the required call to it rather than a cycle.
     */
    @Test
    fun `a constructor parameter written last turns the self-delegation into a primary call`() {
        val out = file("com.example", "A") {
            `class`("A") {
                `constructor`(param("q", INT), param("r", INT)) { q, _ -> `this`(q) }
                constructorParam(VAL, "n", INT)
            }
        }.toString()
        assertTrue("public constructor(q: Int, r: Int) : this(q)" in out, out)
        assertCompiles(out)
    }

    /**
     * Important 1 (review): a sibling constructor spliced in as a raw `FunSpec` bypasses
     * `addSecondaryConstructor` entirely, via `` +spliced ``'s `unaryPlus`. The guard above used to
     * count secondary constructors off a field that only `addSecondaryConstructor` incremented, so
     * this class's second constructor went uncounted, the count read 1 instead of 2, and the guard
     * fired on a class that plainly delegates to a real sibling rather than to itself — measured
     * `IllegalStateException` on Kotlin kotlinc accepts unchanged. Counting straight off
     * `builder.funSpecs` at `finish` closes it, because the splice still lands there.
     */
    @Test
    fun `a spliced sibling constructor counts toward the self-delegation guard`() {
        val spliced = FunSpec.constructorBuilder()
            .addParameter("n", INT)
            .addParameter("m", INT)
            .build()
        val out = file("com.example", "S") {
            `class`("S") {
                `constructor`(param("q", INT)) { q -> `this`(q, 1.lit) }
                +spliced
            }
        }.toString()
        assertTrue("public constructor(q: Int) : this(q, 1)" in out, out)
        assertTrue("public constructor(n: Int, m: Int)" in out, out)
        assertCompiles(out)
    }

    /** Two secondary constructors are outside the decidable case, and are left to kotlinc. */
    @Test
    fun `two this-delegating secondary constructors are left alone`() {
        val out = file("com.example", "Chain") {
            `class`("Chain") {
                `constructor`(param("a", INT)) { a -> `this`(a, 1.lit) }
                `constructor`(param("a", INT), param("b", INT)) { a, _ -> `super`(a) }
            }
        }.toString()
        assertTrue("public constructor(a: Int) : this(a, 1)" in out, out)
    }

    /** ADR 0008 applies to a delegation call's arguments exactly as it does to a statement's. */
    @Test
    fun `a foreign handle in a delegation call is rejected`() {
        var escaped: Expr? = null
        file("com.example", "A") { `fun`("f", param("x", INT)) { x -> escaped = x; +call("println", x) } }
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "B") {
                `class`("Box") { `constructor` { `this`(escaped!!) } }
            }
        }
        assertTrue("does not enclose the current scope" in failure.message.orEmpty(), "${failure.message}")
    }
}
