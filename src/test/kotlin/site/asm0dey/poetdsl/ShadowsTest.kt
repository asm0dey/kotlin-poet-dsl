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
            "'fun BlockScope.object(name: String, body: TypeScope.() -> Unit): Nothing' is deprecated" in
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
            "'fun BlockScope.object(name: String, body: TypeScope.() -> Unit): Nothing' is deprecated" in
                result.messages,
            result.messages,
        )
        assertTrue(
            "constructorParam is only valid inside a class or object body" in result.messages,
            result.messages,
        )
    }
}
