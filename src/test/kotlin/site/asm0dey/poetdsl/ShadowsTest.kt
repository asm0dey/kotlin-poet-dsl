package site.asm0dey.poetdsl

import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0002's guards, checked the only way they can be: by compiling DSL source and reading the
 * compiler's verdict.
 *
 * Every case is a *pair* — the same construct where it is invalid and where it is valid. The
 * negative half alone would pass just as happily against a typo in the snippet, a missing import,
 * or a broken compile harness; the positive half is what pins the failure to the shadow member.
 */
@OptIn(ExperimentalCompilerApi::class)
class ShadowsTest {
    /**
     * The **whole** shadow set, read off the compiled declarations rather than off the generator's
     * source — which is the difference that matters, because the E3 fix round corrected the capture
     * sentence in one shared constant, said so, and left the two overloads that spell their own
     * message untouched. It reached 124 of 126, and nothing failed.
     *
     * `kotlin.Deprecated` keeps RUNTIME retention, so the messages a consumer will see are readable
     * from `ShadowsKt`'s methods. This asserts the count of shadows, the count carrying the capture
     * clause, and that the sentence the fix round declared false — "Written in a block it would
     * silently attach *x* to the enclosing type", for a call that in fact lands in a genuine
     * `TypeScope` — appears in none of them. The corrected wording opens "Written **directly** in a
     * block", so the stale form is a substring test that cannot pass by accident.
     */
    @Test
    fun `every shadow message is the corrected one, all 126 of them`() {
        val messages = Class.forName("site.asm0dey.poetdsl.ShadowsKt")
            .declaredMethods
            .mapNotNull { it.getAnnotation(Deprecated::class.java)?.message }
        assertEquals(142, messages.size, "the shadow count moved; the surface Task 22 locks is 142")
        assertEquals(
            126,
            messages.count { "an extension receiver beats a context parameter (ADR 0002)" in it },
            messages.toSet().joinToString("\n"),
        )
        val stale = messages.filter { "Written in a block it would silently attach" in it }
        assertTrue(stale.isEmpty(), "these still claim the call would have attached:\n$stale")
    }

    /**
     * The **validity** half of the same messages, which the shared clause got wrong in the other
     * direction: it told all 124 callers the construct "would have been valid there" for a body it
     * names two of — and a `typeSpec` body and an `anonymousObject`'s are not the same container.
     * Measured in-suite below, and at a command line on `kotlinc`, `kotlinc-js` and `kotlinc-wasm`
     * 2.4.10, all three identical:
     *
     *     val v = object { constructor(q: Int) }   objects cannot have constructors.
     *     val v = object { companion object }      modifier 'companion' is not applicable inside
     *                                              'local class'.
     *     val v = object { init { println(1) } }   clean   ← the one construct valid in both
     *     class C { constructor(q: Int) { … }      clean   ← the `typeSpec` half of the split
     *               companion object }
     *
     * So 125 of the 126 carry "In a `typeSpec` body the construct would have been valid" plus what
     * the anonymous body does instead, and `` `init` `` — the one that *is* valid in both — is the
     * one that still says so.
     */
    @Test
    fun `the validity clause is per construct, and each construct's is measured`() {
        val messages = Class.forName("site.asm0dey.poetdsl.ShadowsKt")
            .declaredMethods
            .mapNotNull { it.getAnnotation(Deprecated::class.java)?.message }
        val captured = messages.filter { "an extension receiver beats a context parameter (ADR 0002)" in it }
        assertEquals(126, captured.size)
        assertTrue(
            captured.none { "lands here too, though the construct would have been valid there" in it },
            "the unqualified claim is back",
        )
        // 120 `constructor`/`ctor`, both `companionObject`s, `enumEntry`, and the two supertypes.
        assertEquals(
            125,
            captured.count { "In a `typeSpec` body the construct would have been valid" in it },
        )
        assertEquals(120, captured.count { "\"objects cannot have constructors\"" in it })
        assertEquals(
            2,
            captured.count { "\"modifier 'companion' is not applicable inside 'local class'\"" in it },
        )
        assertEquals(1, captured.count { "an anonymous object has no entries" in it })
        assertEquals(2, captured.count { "takes its supertypes as parameters" in it })
        // …and `init`, the exception, which keeps the claim because the language keeps it.
        assertEquals(1, captured.count { "In either body the construct would have been valid" in it })

        // The rows the two clauses quote, compiled here rather than trusted. The leading character
        // is dropped because kctfork's renderer capitalises the first letter of a diagnostic where
        // command-line kotlinc does not — the suite's existing `"nresolved reference '*'"` idiom.
        assertTrue(
            "bjects cannot have constructors" in
                compile("fun f() {\n  val v = object { constructor(q: Int) }\n  println(v)\n}\n").messages,
        )
        assertTrue(
            "companion' is not applicable inside 'local class'" in
                compile("fun f() {\n  val v = object { companion object }\n  println(v)\n}\n").messages,
        )
        // The controls, and they are what make the clause a split rather than a blanket denial.
        assertCompiles("fun f() {\n  val v = object { init { println(1) } }\n  println(v)\n}\n")
        assertCompiles("class C {\n  constructor(q: Int) { println(q) }\n\n  companion object\n}\n")
    }

    /**
     * The two the sweep above missed, through the route a consumer actually takes: a call in a block
     * body, compiled, with the deprecation message read out of the compiler's own output. An
     * out-of-project consumer compile printed the stale `enumEntry` text verbatim, which is what says
     * a generator-source assertion would not have been enough.
     */
    @Test
    fun `enumEntry and the named companionObject carry the corrected message too`() {
        for ((call, expected) in listOf(
            """enumEntry("A")""" to "enumEntry is only valid inside an enum class body",
            """companionObject("Factory") { }""" to "companionObject is only valid inside a class or interface body",
        )) {
            val result = compileDsl(
                """
                fun build() = file("com.example", "A") {
                    `class`("C") {
                        `fun`("f") {
                            $call
                        }
                    }
                }
                """.trimIndent(),
            )
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(expected in result.messages, result.messages)
            assertTrue("lexically in a block" in result.messages, result.messages)
            assertTrue(
                "Written in a block it would silently attach" !in result.messages,
                result.messages,
            )
        }
    }

    @Test
    fun `a named object in a function body is a compile error naming the shadow`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `fun`("f") {
                    `object`("Local") { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "'fun BlockScope.object(name: String, kdoc: String? = ..., body: TypeScope.() -> Unit): Nothing' is deprecated" in
                result.messages,
            result.messages,
        )
        assertTrue("A named object cannot be local in Kotlin" in result.messages, result.messages)
    }

    /** The control: the identical call one scope out is the intended use and must still compile. */
    @Test
    fun `a named object at file level compiles`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `object`("Registry") { }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * A variant the shadow set only covers because D10 gave `interface` all six shapes: before
     * that, `interface` had two overloads and the plan's generator emitted four shadows, so the
     * annotated ones guarded nothing that existed and the `KModifier` one existed unguarded.
     */
    @Test
    fun `an annotated interface in a function body is a compile error`() {
        val result = compileDsl(
            """
            annotation class Marker

            fun build() = file("com.example", "A") {
                `fun`("f") {
                    `interface`(annotation<Marker>(), INTERNAL, "Local") { }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.KModifier.INTERNAL"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("An interface cannot be local in Kotlin" in result.messages, result.messages)
    }

    /**
     * D19: the shadow takes `ParamKind?`, the type the real overload takes. Written with the
     * plan's `KModifier?` it would not match, the member would not be applicable, and resolution
     * would fall straight through to the context function — silently adding a parameter to the
     * enclosing class from inside a method body.
     */
    @Test
    fun `constructorParam in a function body is a compile error naming the shadow`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        constructorParam(VAL, "x", STRING)
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.STRING", "site.asm0dey.poetdsl.ParamKind.VAL"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("constructorParam is only valid inside a class or object body" in result.messages, result.messages)
    }

    /** The control for `constructorParam`: in a type body it is the intended use. */
    @Test
    fun `constructorParam in a type body compiles`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    constructorParam(VAL, "x", STRING)
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.STRING", "site.asm0dey.poetdsl.ParamKind.VAL"),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * The alias is a separate declaration, so it needs its own shadow — and gets one, because the
     * shadows are filtered out of the same list that generated the real overloads rather than
     * spelled out by hand.
     */
    @Test
    fun `the ctorParam alias is shadowed too`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        ctorParam(VAL, "x", STRING)
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.STRING", "site.asm0dey.poetdsl.ParamKind.VAL"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("ctorParam is only valid inside a class or object body" in result.messages, result.messages)
    }

    /**
     * The three `TypeScope`-only constructs that shipped without shadows, on the false premise that
     * `context(t: TypeScope)` alone stops a call in a block body. It does not: the enclosing type's
     * context parameter is still in scope inside a `` `fun` `` body, so the call resolved and the
     * construct silently attached to the enclosing type — `` `class`("C") { `fun`("f") {
     * superclass(b) } } `` rendered `public class C : Base() { public fun f() { } }`. Only the
     * *file-level* direction fails on its own, with "no context argument for 't: TypeScope' found".
     */
    @Test
    fun `superclass in a function body is a compile error naming the shadow`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        superclass(ClassName("com.example", "Base"))
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.ClassName"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "'fun BlockScope.superclass(type: TypeName, vararg args: Expr): Nothing' is deprecated" in
                result.messages,
            result.messages,
        )
        assertTrue("superclass is only valid inside a class or object body" in result.messages, result.messages)
    }

    @Test
    fun `superinterface in a function body is a compile error naming the shadow`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        superinterface(ClassName("com.example", "Greeter"))
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.ClassName"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "'fun BlockScope.superinterface(type: TypeName, by: Expr? = ...): Nothing' is deprecated" in
                result.messages,
            result.messages,
        )
        assertTrue(
            "superinterface is only valid inside a class, object or interface body" in result.messages,
            result.messages,
        )
    }

    /**
     * D29's two constructs have the identical gap, and it is the worse one to leave open: an
     * `init { }` written inside a member body would attach an initializer block to the enclosing
     * type, running code the author meant to run inside the function.
     */
    @Test
    fun `init in a function body is a compile error naming the shadow`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        `init` { +call("println") }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "'fun BlockScope.init(body: BlockScope.() -> Unit): Nothing' is deprecated" in result.messages,
            result.messages,
        )
        assertTrue(
            "`init` is only valid inside a class, object or companion object body" in result.messages,
            result.messages,
        )
    }

    /** Both companion overloads are shadowed, so neither form falls through to the context function. */
    @Test
    fun `companionObject in a function body is a compile error naming the shadow`() {
        for (call in listOf("companionObject { }", """companionObject("Factory") { }""")) {
            val result = compileDsl(
                """
                fun build() = file("com.example", "A") {
                    `class`("C") {
                        `fun`("f") {
                            $call
                        }
                    }
                }
                """.trimIndent(),
            )
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(
                "companionObject is only valid inside a class or interface body" in result.messages,
                result.messages,
            )
        }
    }

    /** The control for D29's pair: the same calls one scope out are the intended use. */
    @Test
    fun `init and companionObject in a type body compile`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `init` { +call("println") }
                    companionObject("Factory") { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * `init` is a *soft* keyword: it is a keyword only in a class body's declaration position, so
     * the construct needs no backticks and both spellings resolve to the same declaration. Written
     * without them it is the one construct in this DSL whose name is a Kotlin keyword and yet is
     * spelled plainly, so both forms are pinned.
     */
    @Test
    fun `init compiles with and without backticks`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    init { +call("println") }
                    `init` { +call("println") }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /** The control for both: the same two calls one scope out are the intended use. */
    @Test
    fun `superclass and superinterface in a type body compile`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    superclass(ClassName("com.example", "Base"))
                    superinterface(ClassName("com.example", "Greeter"))
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.ClassName"),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * `` `constructor` `` had the identical gap. Three shapes, because its shadow set is the one
     * that had to grow to 120 to cover ADR 0004's variants against D23/D24's arities: the bare
     * form, a parameter-taking one, and a variant carrying both annotations and a modifier — the
     * corners where a partial shadow set would fall through to the context function.
     */
    @Test
    fun `constructor in a function body is a compile error, in every shape`() {
        val bare = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        `constructor` { }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, bare.exitCode, bare.messages)
        assertTrue(
            "'fun BlockScope.constructor(kdoc: String? = ..., body: BlockScope.() -> Unit): Nothing' is deprecated" in bare.messages,
            bare.messages,
        )
        assertTrue("`constructor` is only valid inside a class body" in bare.messages, bare.messages)

        val withParam = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        `constructor`(param("x", INT)) { _ -> }
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, withParam.exitCode, withParam.messages)
        assertTrue("`constructor` is only valid inside a class body" in withParam.messages, withParam.messages)

        val variant = compileDsl(
            """
            annotation class Marker

            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        `constructor`(annotation<Marker>(), PRIVATE, listOf(param("x", INT))) { _ -> }
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT", "com.squareup.kotlinpoet.KModifier.PRIVATE"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, variant.exitCode, variant.messages)
        assertTrue("`constructor` is only valid inside a class body" in variant.messages, variant.messages)
    }

    /** The alias is a separate declaration and gets its own 60 shadows, off the same list. */
    @Test
    fun `the ctor alias is shadowed too`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `fun`("f") {
                        ctor(param("x", INT)) { _ -> }
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("ctor is only valid inside a class body" in result.messages, result.messages)
    }

    /** The control for `` `constructor` ``: in a class body it is the intended use. */
    @Test
    fun `constructor in a type body compiles`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `class`("C") {
                    `constructor`(param("x", INT)) { _ -> }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT"),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * The counterpart to the shadows: `class` deliberately has none, because a local class is
     * valid Kotlin and only KotlinPoet 2.3.0's renderer stands in the way (deviation D20). It
     * therefore has to keep *compiling* — the runtime guard in `declareType` is what rejects it,
     * with a message that names the backend, and `TypeScopeTest`'s canary is what says when the
     * backend is fixed. If a shadow were ever added, this test fails and points at that decision.
     */
    @Test
    fun `a local class still compiles, because its block is a backend limit and not a Kotlin one`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `fun`("f") {
                    `class`("Local") { }
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * KNOWN LIMITATION, not a desired property: a `typeSpec { }` opened lexically inside a `fun`
     * body still sees the enclosing `BlockScope` as a visible implicit receiver, and its shadow
     * extensions outrank the context function even though the innermost receiver is the detached
     * `TypeScope` where `object`/`constructorParam` are legal. `TypeScopeTest`'s
     * `` `local class rendering is still blocked by KotlinPoet` `` -style detached `typeSpec` use
     * only passes because it is *not* nested inside a block.
     *
     * This is inherent to shadowing extensions on `BlockScope` — ADR 0002's member-fallback
     * alternative would have the identical receiver behaviour, since the leak is about what
     * receivers are in scope, not about how the shadow is declared — so the fix is documentation
     * and this pin, not a redesign. See ADR 0002's Verified section for the workaround: build the
     * detached spec outside the block and splice it in with `+spec`.
     */
    @Test
    fun `KNOWN LIMITATION - a detached typeSpec nested inside a block still trips the block's shadows`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `fun`("f") {
                    val t = typeSpec(name = "Inner") {
                        `object`("Nested") { }
                        constructorParam(VAL, "x", STRING)
                    }
                }
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.STRING", "site.asm0dey.poetdsl.ParamKind.VAL"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "'fun BlockScope.object(name: String, kdoc: String? = ..., body: TypeScope.() -> Unit): Nothing' is deprecated" in
                result.messages,
            result.messages,
        )
        assertTrue(
            "constructorParam is only valid inside a class or object body" in result.messages,
            result.messages,
        )
    }
}
