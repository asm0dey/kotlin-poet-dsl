package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.SUSPEND
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import site.asm0dey.poetdsl.ParamKind.VAL
import site.asm0dey.poetdsl.ParamKind.VAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stand-in for `kotlinx.serialization.Serializable`, which is not a dependency of this module. */
annotation class Serializable

/**
 * The generated surface: ADR 0004's arities and six variants, for every declaration construct.
 *
 * Nothing here asserts that a file was *written* — every test calls the generated overload and
 * reads the rendered Kotlin, so a generator that emits a signature it never wires up correctly
 * fails here rather than passing on a file count.
 */
class ArityTest {
    private fun params(n: Int) = (1..n).map { param("p$it", INT) }

    // --- arities ---------------------------------------------------------------------------

    /**
     * Deviation D1. The plan's generator destructured the `List<Expr>` the implementation hands
     * the body — `{ (a1, a2, …, a8) -> … }` — which does not compile past arity 5, because the
     * stdlib gives `List` only `component1()`-`component5()`. The generated overloads bind by
     * index instead, so this test uses the *last* handle first: an off-by-one or a
     * copy-and-paste `args[0]` everywhere still renders eight parameters and would pass a
     * signature-only check.
     */
    @Test
    fun `arity 8 binds every handle in order`() {
        val ps = params(8)
        val out = file("com.example", "Api") {
            `fun`(
                "wide8",
                ps[0], ps[1], ps[2], ps[3], ps[4], ps[5], ps[6], ps[7],
            ) { a1, a2, a3, a4, a5, a6, a7, a8 ->
                +call("use", a8, a7, a6, a5, a4, a3, a2, a1)
            }
        }.toString()
        assertTrue("public fun wide8(" in out, out)
        assertTrue("p8: Int," in out, out)
        assertTrue("use(p8, p7, p6, p5, p4, p3, p2, p1)" in out, out)
    }

    /** Arities 6 and 7 are the other two the destructuring form could not have reached. */
    @Test
    fun `arities 6 and 7 bind every handle in order`() {
        val six = params(6)
        val seven = params(7)
        val out = file("com.example", "Api") {
            `fun`("wide6", six[0], six[1], six[2], six[3], six[4], six[5]) { a1, a2, a3, a4, a5, a6 ->
                +call("use", a6, a5, a4, a3, a2, a1)
            }
            `fun`(
                "wide7",
                seven[0], seven[1], seven[2], seven[3], seven[4], seven[5], seven[6],
            ) { a1, a2, a3, a4, a5, a6, a7 ->
                +call("use", a7, a6, a5, a4, a3, a2, a1)
            }
        }.toString()
        assertTrue("use(p6, p5, p4, p3, p2, p1)" in out, out)
        assertTrue("use(p7, p6, p5, p4, p3, p2, p1)" in out, out)
    }

    /** Arities 0-5, the ones the old form did reach, still bind correctly. */
    @Test
    fun `arities 0 through 5 bind every handle in order`() {
        val p = params(5)
        val out = file("com.example", "Api") {
            `fun`("a0") { +call("use") }
            `fun`("a1", p[0]) { a1 -> +call("use", a1) }
            `fun`("a2", p[0], p[1]) { a1, a2 -> +call("use", a2, a1) }
            `fun`("a3", p[0], p[1], p[2]) { a1, a2, a3 -> +call("use", a3, a2, a1) }
            `fun`("a4", p[0], p[1], p[2], p[3]) { a1, a2, a3, a4 -> +call("use", a4, a3, a2, a1) }
            `fun`("a5", p[0], p[1], p[2], p[3], p[4]) { a1, a2, a3, a4, a5 ->
                +call("use", a5, a4, a3, a2, a1)
            }
        }.toString()
        assertTrue("use()" in out, out)
        assertTrue("use(p1)" in out, out)
        assertTrue("use(p2, p1)" in out, out)
        assertTrue("use(p3, p2, p1)" in out, out)
        assertTrue("use(p4, p3, p2, p1)" in out, out)
        assertTrue("use(p5, p4, p3, p2, p1)" in out, out)
    }

    /** ADR 0004's cap: past eight, the list form takes over. */
    @Test
    fun `beyond eight uses the list form`() {
        val out = file("com.example", "Api") {
            `fun`("wide12", params = params(12)) { ps -> +ps[11] }
        }.toString()
        assertTrue("p12: Int" in out, out)
    }

    // --- D24: the list form, on every construct that takes parameters -----------------------

    /**
     * Deviation D24. Before it, `` `constructor` `` capped at arity 8 with no way past, so a
     * secondary constructor with more than eight parameters was inexpressible.
     */
    @Test
    fun `a secondary constructor beyond eight uses the list form`() {
        val out = file("com.example", "Wide") {
            `class`("Wide") {
                `constructor`(params(12)) { ps -> +call("use", ps[0], ps[11]) }
            }
        }.toString()
        assertTrue("p12: Int," in out, out)
        assertTrue("use(p1, p12)" in out, out)
    }

    /** D24's list forms carry ADR 0004's six variants, or a wide `private` constructor would be lost. */
    @Test
    fun `the list forms take annotations and modifiers`() {
        val out = file("com.example", "Wide") {
            `class`(DATA, "Wide", params(9).map { param(VAL, it.name, INT) }) { _ -> }
            `class`("Holder") {
                ctor(annotation<Serializable>(), PRIVATE, params(9)) { _ -> }
                func(INTERNAL, "wide", params(9)) { _ -> }
            }
        }.toString()
        assertTrue("data class Wide(" in out, out)
        assertTrue("public val p9: Int," in out, out)
        assertTrue("  @Serializable\n  private constructor(" in out, out)
        assertTrue("internal fun wide(" in out, out)
    }

    /** `klass`, `func` and `ctor` reach the list form too — the aliases come off the same table. */
    @Test
    fun `aliases carry the list form`() {
        assertEquals(
            file("com.example", "A") {
                `class`("C", params(9)) { ps -> `fun`("f") { +ps[8] } }
                `class`("D") { `constructor`(params(9)) { ps -> +ps[8] } }
                `fun`("g", params(9)) { ps -> +ps[8] }
            }.toString(),
            file("com.example", "A") {
                klass("C", params(9)) { ps -> `fun`("f") { +ps[8] } }
                klass("D") { ctor(params(9)) { ps -> +ps[8] } }
                func("g", params(9)) { ps -> +ps[8] }
            }.toString(),
        )
    }

    // --- D23: primary-constructor parameters in the `class` signature ------------------------

    /**
     * Deviation D23's headline shape: the parameters in the declaration, their handles named in
     * the body.
     */
    @Test
    fun `a class declares its primary constructor in its signature`() {
        assertEquals(
            """
            package com.example

            import kotlin.Long
            import kotlin.String

            public data class User(
              public val id: Long,
              public var name: String,
            ) {
              public fun greet() {
                println(name)
              }
            }

            """.trimIndent(),
            file("com.example", "User") {
                `class`(DATA, "User", param(VAL, "id", LONG), param(VAR, "name", STRING)) { _, name ->
                    `fun`("greet") { +call("println", name) }
                }
            }.toString(),
        )
    }

    /**
     * The same index-based binding D1 forced on `` `fun` ``, on the construct that got it last:
     * the last handle is used first, so an `args[0]`-everywhere generator fails here.
     */
    @Test
    fun `class arities 1 through 8 bind every handle in order`() {
        val p = params(8)
        val out = file("com.example", "Api") {
            `class`("C0") { }
            `class`("C1", p[0]) { a1 -> `fun`("f") { +call("use", a1) } }
            `class`("C2", p[0], p[1]) { a1, a2 -> `fun`("f") { +call("use", a2, a1) } }
            `class`("C3", p[0], p[1], p[2]) { a1, a2, a3 -> `fun`("f") { +call("use", a3, a2, a1) } }
            `class`("C4", p[0], p[1], p[2], p[3]) { a1, a2, a3, a4 ->
                `fun`("f") { +call("use", a4, a3, a2, a1) }
            }
            `class`("C5", p[0], p[1], p[2], p[3], p[4]) { a1, a2, a3, a4, a5 ->
                `fun`("f") { +call("use", a5, a4, a3, a2, a1) }
            }
            `class`("C6", p[0], p[1], p[2], p[3], p[4], p[5]) { a1, a2, a3, a4, a5, a6 ->
                `fun`("f") { +call("use", a6, a5, a4, a3, a2, a1) }
            }
            `class`("C7", p[0], p[1], p[2], p[3], p[4], p[5], p[6]) { a1, a2, a3, a4, a5, a6, a7 ->
                `fun`("f") { +call("use", a7, a6, a5, a4, a3, a2, a1) }
            }
            `class`("C8", p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]) { a1, a2, a3, a4, a5, a6, a7, a8 ->
                `fun`("f") { +call("use", a8, a7, a6, a5, a4, a3, a2, a1) }
            }
        }.toString()
        assertTrue("public class C0\n" in out, out)
        assertTrue("use(p1)" in out, out)
        assertTrue("use(p2, p1)" in out, out)
        assertTrue("use(p8, p7, p6, p5, p4, p3, p2, p1)" in out, out)
        // The signature form declares real primary-constructor parameters, not properties on the side.
        assertTrue("public class C8(\n  p1: Int,\n" in out, out)
    }

    @Test
    fun `all six class variants take primary-constructor parameters`() {
        val out = file("com.example", "Api") {
            `class`("C1", param(VAL, "x", INT)) { _ -> }
            `class`(SEALED, "C2", param(VAL, "x", INT)) { _ -> }
            `class`(DATA + INTERNAL, "C3", param(VAL, "x", INT)) { _ -> }
            `class`(annotation<Serializable>(), "C4", param(VAL, "x", INT)) { _ -> }
            `class`(annotation<Serializable>(), SEALED, "C5", param(VAL, "x", INT)) { _ -> }
            `class`(annotation<Serializable>(), DATA + INTERNAL, "C6", param(VAL, "x", INT)) { _ -> }
        }.toString()
        assertTrue("public class C1(\n" in out, out)
        assertTrue("public sealed class C2(\n" in out, out)
        assertTrue("internal data class C3(\n" in out, out)
        assertTrue("@Serializable\npublic class C4(\n" in out, out)
        assertTrue("@Serializable\npublic sealed class C5(\n" in out, out)
        assertTrue("@Serializable\ninternal data class C6(\n" in out, out)
    }

    /**
     * The signature form and the in-body form are one construct: same duplicate check, same
     * uniquifying, same handle. A parameter written both ways is the collision that proves it.
     */
    @Test
    fun `a signature parameter and a body parameter are the same construct`() {
        assertEquals(
            file("com.example", "A") { `class`("C", param(VAL, "id", INT)) { _ -> } }.toString(),
            file("com.example", "A") { `class`("C") { constructorParam(VAL, "id", INT) } }.toString(),
        )
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C", param(VAL, "id", INT)) { _ -> constructorParam(VAL, "id", INT) }
            }
        }.exceptionOrNull()
        assertEquals(
            "A constructor parameter named \"id\" is already declared in this scope.",
            (thrown as IllegalStateException).message,
        )
    }

    /** No kind means a plain parameter with no property — the same thing `param(name, type)` builds. */
    @Test
    fun `a null kind is a plain parameter`() {
        assertEquals(
            file("com.example", "A") { `class`("C", param("x", INT)) { _ -> } }.toString(),
            file("com.example", "A") { `class`("C", param(null, "x", INT)) { _ -> } }.toString(),
        )
        assertTrue(
            "public class C(\n  x: Int,\n)" in
                file("com.example", "A") { `class`("C", param("x", INT)) { _ -> } }.toString(),
        )
    }

    /**
     * `val`/`var` is legal only on a primary constructor's parameters. Passing such a descriptor to
     * a function — or to a *secondary* constructor — would otherwise render a plain parameter and
     * silently lose the property the caller asked for.
     */
    @Test
    fun `a val parameter is rejected outside a primary constructor`() {
        val onFun = kotlin.runCatching {
            file("com.example", "A") { `fun`("f", param(VAL, "x", INT)) { _ -> } }
        }.exceptionOrNull()
        assertTrue(
            onFun is IllegalStateException && onFun.message.orEmpty().startsWith("param: \"x\" is a `val`/`var`"),
            "$onFun",
        )
        val onCtor = kotlin.runCatching {
            file("com.example", "A") { `class`("C") { `constructor`(param(VAL, "x", INT)) { _ -> } } }
        }.exceptionOrNull()
        assertTrue(onCtor is IllegalStateException, "$onCtor")
    }

    /** The descriptor is a `ParameterSpec`, so a hand-built annotated one goes straight through. */
    @Test
    fun `a hand built parameter spec keeps its annotations in the signature form`() {
        val annotated = param(VAL, "id", INT).toBuilder()
            .addAnnotation(annotation<Serializable>().list.single())
            .build()
        val out = file("com.example", "A") { `class`("C", annotated) { _ -> } }.toString()
        assertTrue("@Serializable\n  public val id: Int," in out, out)
    }

    // --- the six variants, on `fun` ---------------------------------------------------------

    @Test
    fun `spec style positional call resolves`() {
        val out = file("com.example", "Api") {
            `fun`(PRIVATE + SUSPEND, "greet", param("greeting", STRING)) { greeting ->
                +call("println", greeting)
            }
        }.toString()
        assertTrue("private suspend fun greet(greeting: String)" in out, out)
    }

    @Test
    fun `single modifier variant resolves`() {
        val out = file("com.example", "Api") {
            `fun`(PRIVATE, "hidden") { +call("work") }
        }.toString()
        assertTrue("private fun hidden()" in out, out)
    }

    /**
     * All six shapes on one construct, in one file. ADR 0004's claim is that presence and type
     * alone disambiguate them; if any pair collapsed, this would not compile.
     */
    @Test
    fun `all six fun variants resolve and render`() {
        val out = file("com.example", "Api") {
            `fun`("v1") { +call("work") }
            `fun`(PRIVATE, "v2") { +call("work") }
            `fun`(PRIVATE + SUSPEND, "v3") { +call("work") }
            `fun`(annotation<Serializable>(), "v4") { +call("work") }
            `fun`(annotation<Serializable>(), PRIVATE, "v5") { +call("work") }
            `fun`(annotation<Serializable>(), PRIVATE + SUSPEND, "v6") { +call("work") }
        }.toString()
        assertTrue("public fun v1()" in out, out)
        assertTrue("private fun v2()" in out, out)
        assertTrue("private suspend fun v3()" in out, out)
        assertTrue("@Serializable\npublic fun v4()" in out, out)
        assertTrue("@Serializable\nprivate fun v5()" in out, out)
        assertTrue("@Serializable\nprivate suspend fun v6()" in out, out)
    }

    /** The alias carries the whole set, not just the shape it was hand-written with. */
    @Test
    fun `func is the same construct as fun across variants`() {
        assertEquals(
            file("com.example", "A") { `fun`(annotation<Serializable>(), PRIVATE, "f", param("x", INT)) { } }
                .toString(),
            file("com.example", "A") { func(annotation<Serializable>(), PRIVATE, "f", param("x", INT)) { } }
                .toString(),
        )
    }

    // --- constructors -----------------------------------------------------------------------

    @Test
    fun `constructor family is generated`() {
        val out = file("com.example", "User") {
            `class`("User") {
                `constructor`(param("a", INT), param("b", INT)) { a, b -> +call("init", a, b) }
            }
        }.toString()
        assertTrue("public constructor(" in out, out)
        assertTrue("init(a, b)" in out, out)
    }

    @Test
    fun `constructor takes annotations and modifiers`() {
        val out = file("com.example", "User") {
            `class`("User") {
                ctor(annotation<Serializable>(), INTERNAL, param("a", INT)) { a -> +call("init", a) }
            }
        }.toString()
        assertTrue("  @Serializable\n  internal constructor(a: Int)" in out, out)
    }

    /**
     * The primary/secondary guard is per-overload, not inside `buildFun`, so a generated
     * overload that forgot it would render `constructor` under a primary constructor — Kotlin's
     * `Primary constructor call expected`. Checked on a generated arity/variant the hand-written
     * pair never had. D25 moved the check to the second of the two calls the generated body makes
     * (`addSecondaryConstructor`, which sees whether the body delegated); a generated overload that
     * dropped *either* call would land here.
     */
    @Test
    fun `a generated constructor still rejects an undelegated one alongside a primary`() {
        val thrown = kotlin.runCatching {
            file("com.example", "User") {
                `class`("User") {
                    constructorParam(VAL, "id", STRING)
                    `constructor`(PRIVATE, param("a", INT), param("b", INT)) { _, _ -> }
                }
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "$thrown")
        assertEquals(SECONDARY_MUST_DELEGATE_TO_PRIMARY, thrown.message)
    }

    /** And the same generated overload accepts the pair once the secondary delegates (D25). */
    @Test
    fun `a generated constructor accepts a primary alongside it once it delegates`() {
        val out = file("com.example", "User") {
            `class`("User") {
                constructorParam(VAL, "id", STRING)
                `constructor`(PRIVATE, param("a", INT), param("b", INT)) { a, b ->
                    `this`(call("buildId", a, b))
                }
            }
        }.toString()
        assertTrue("private constructor(a: Int, b: Int) : this(buildId(a, b))" in out, out)
    }

    // --- the six variants on the other declaration constructs (deviation D10) ----------------

    /**
     * D10's headline case. Before Task 20, `declareType`'s `annotations` slot had no public call
     * site that passed anything but `null`, so an annotation on a class was simply not
     * expressible. This is the proof that it is.
     */
    @Test
    fun `an annotated class is expressible`() {
        assertEquals(
            """
            package com.example

            import site.asm0dey.poetdsl.Serializable

            @Serializable
            public class User

            """.trimIndent(),
            file("com.example", "User") {
                `class`(annotation<Serializable>(), "User") { }
            }.toString(),
        )
    }

    @Test
    fun `all six class variants resolve and render`() {
        val out = file("com.example", "Api") {
            `class`("C1") { }
            `class`(SEALED, "C2") { }
            `class`(DATA + INTERNAL, "C3") { constructorParam(VAL, "x", INT) }
            `class`(annotation<Serializable>(), "C4") { }
            `class`(annotation<Serializable>(), SEALED, "C5") { }
            `class`(annotation<Serializable>(), DATA + INTERNAL, "C6") { constructorParam(VAL, "x", INT) }
        }.toString()
        assertTrue("public class C1" in out, out)
        assertTrue("public sealed class C2" in out, out)
        assertTrue("internal data class C3" in out, out)
        assertTrue("@Serializable\npublic class C4" in out, out)
        assertTrue("@Serializable\npublic sealed class C5" in out, out)
        assertTrue("@Serializable\ninternal data class C6" in out, out)
    }

    @Test
    fun `object and interface take annotations`() {
        val out = file("com.example", "Api") {
            `object`(annotation<Serializable>(), "Registry") { }
            `interface`(annotation<Serializable>(), INTERNAL, "Service") { }
        }.toString()
        assertTrue("@Serializable\npublic object Registry" in out, out)
        assertTrue("@Serializable\ninternal interface Service" in out, out)
    }

    @Test
    fun `properties take annotations`() {
        val out = file("com.example", "Api") {
            `class`("User") {
                `val`(annotation<Serializable>(), PRIVATE, "name", STRING, init = "x".lit)
                `var`(annotation<Serializable>(), "count", INT, init = 0.lit)
                property(annotation<Serializable>(), INTERNAL, "id", STRING, init = "i".lit)
            }
        }.toString()
        assertTrue("  @Serializable\n  private val name: String" in out, out)
        assertTrue("  @Serializable\n  public var count: Int" in out, out)
        assertTrue("  @Serializable\n  internal val id: String" in out, out)
    }

    /** `klass` and `property` carry the full variant set, not the two they were written with. */
    @Test
    fun `aliases carry the whole variant set`() {
        assertEquals(
            file("com.example", "A") { `class`(annotation<Serializable>(), SEALED, "C") { } }.toString(),
            file("com.example", "A") { klass(annotation<Serializable>(), SEALED, "C") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `val`(annotation<Serializable>(), PRIVATE, "x", INT, 1.lit) }.toString(),
            file("com.example", "A") { property(annotation<Serializable>(), PRIVATE, "x", INT, 1.lit) }.toString(),
        )
    }

    /**
     * A single `KModifier` and a `Modifiers` occupy the same slot in two different variants; the
     * one-modifier spelling has to mean the same thing either way.
     */
    @Test
    fun `the bare KModifier variant equals the one element Modifiers variant`() {
        assertEquals(
            file("com.example", "A") { `fun`(PRIVATE.toModifiers(), "f") { } }.toString(),
            file("com.example", "A") { `fun`(PRIVATE, "f") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`(SEALED.toModifiers(), "C") { } }.toString(),
            file("com.example", "A") { `class`(SEALED, "C") { } }.toString(),
        )
    }

    /**
     * The generated `val`/`var` variants reach `bind`'s block branch too, and a local binding
     * cannot carry either — the error names the binding rather than rendering invalid Kotlin.
     */
    @Test
    fun `an annotated local binding is rejected`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `fun`("f") { `val`(annotation<Serializable>(), "x", INT, 1.lit) }
            }
        }.exceptionOrNull()
        assertEquals(
            "A local binding ('x') cannot carry annotations or modifiers.",
            (thrown as IllegalStateException).message,
        )
    }
}
