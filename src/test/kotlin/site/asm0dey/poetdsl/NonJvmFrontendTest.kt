package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.EXTERNAL
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2c part 3 — the cross-platform claims this project has always measured by hand.
 *
 * `compileMultiplatform` is JVM-only, so every non-JVM row in D36, D37, D40 and D41 was a sentence in
 * a comment that nothing re-derived. These are the rows where a wrong claim would have cost the most:
 * each one is a place where **the JVM disagrees with the other two**, or where this round's own guard
 * had to know a non-JVM answer to avoid refusing valid output.
 *
 * Not the whole of those tables — pinning 832 matrix cells on three frontends is a task of its own,
 * and the report says so. What is pinned here is the set of claims a future round could break without
 * noticing.
 */
@OptIn(ExperimentalCompilerApi::class)
class NonJvmFrontendTest {

    private fun render(body: FileScope.() -> Unit): String = file("com.example", "P", body = body).toString()

    /**
     * The harness's own control. `-Xwasm` is a flag on `KotlinJsCompilation`, so *that it changes the
     * target at all* is the one thing every Wasm row below depends on and the one thing that could
     * silently stop being true. Fed the **JS** klib, the Wasm compilation must reject the library.
     */
    @Test
    fun `the wasm harness really targets wasm`() {
        val jsKlib = System.getProperty("kotlin.stdlib.js")!!.split(java.io.File.pathSeparator).first()
        val wasmKlib = System.getProperty("kotlin.stdlib.wasm")!!
        assertTrue(jsKlib !in wasmKlib, "the two klibs are the same file: $jsKlib")
        // Sanity: the correct klib compiles.
        val correct = compileWasm("package com.example\n\nclass A\n")
        assertEquals(KotlinCompilation.ExitCode.OK, correct.exitCode, correct.messages)
        // …and the same compilation, handed the *JS* klib, must reject the library. Passed as an
        // argument rather than through `System.setProperty`, which mutates state every other test
        // in this JVM reads.
        val crossed = compileWasmWithKlib(jsKlib, "package com.example\n\nclass A\n")
        assertTrue(
            "failed platform-specific check" in crossed.messages,
            "a Wasm compilation accepted the Kotlin/JS klib, so -Xwasm is not switching the " +
                "target:\n${crossed.messages}",
        )
    }

    /**
     * **D37, and the reason this file exists.** `external val a: Int` at file level is *modifier
     * 'external' is not applicable to 'property'* on the JVM and clean on Kotlin/JS and Kotlin/Wasm.
     * A fix brief once ordered a `check` refusing it outright, off the JVM measurement alone; that
     * guard would have made Kotlin/JS external declarations ungenerable, and nothing but a human
     * re-running kotlinc-js stood between the project and it.
     */
    @Test
    fun `a top-level external property compiles on JS and Wasm and not on the JVM`() {
        val source = render { `val`(EXTERNAL, "a", INT) }
        assertTrue("public external val a: Int" in source, source)
        assertCompilesEverywhereButJvm(source)
        assertTrue(
            "'external' is not applicable" in compile(source).messages,
            compile(source).messages,
        )
    }

    /**
     * **D37 row 6.** KotlinPoet suppresses a member's keyword when the enclosing `TypeSpec` carries
     * it, so this renders `external class C { val a: Int }` — accepted by both non-JVM frontends,
     * and the row that made `externalAllowed` read the immediate builder rather than the argument.
     */
    @Test
    fun `an external class's member property renders without the keyword and compiles`() {
        val source = render { `class`(EXTERNAL, "C") { `val`("a", INT) } }
        assertTrue("public external class C" in source, source)
        assertTrue("public val a: Int\n" in source, source)
        assertCompilesEverywhereButJvm(source)
    }

    /**
     * **This round's own control row.** The empty-body rule had to know that KotlinPoet omits a
     * function's body at *every* depth inside an `external` classifier (`modifiers +
     * implicitModifiers`, `TypeSpec.kt:348`) — otherwise it would have refused these three, which
     * both non-JVM frontends accept. That is the false rejection D37's standing rule exists to
     * prevent, and it is now pinned rather than remembered.
     */
    @Test
    fun `a function inside an external class is a signature at every depth`() {
        val source = render {
            `class`(EXTERNAL, "C") {
                `fun`("f", returns = INT) { }
                `class`("N") { `fun`("g", returns = INT) { } }
                `object`("O") { `fun`("h", returns = INT) { } }
            }
        }
        assertTrue("public fun f(): Int\n" in source, source)
        assertTrue("public fun g(): Int\n" in source, source)
        assertTrue("public fun h(): Int\n" in source, source)
        assertCompilesEverywhereButJvm(source)
    }

    /**
     * **D41's central claim, on the two frontends that never had a test.** Three of the round's nine
     * rules, each rendered by the DSL before this round and refused by every frontend. The DSL now
     * refuses them, so what is compiled here is the *hand-written* Kotlin — which is the only way to
     * pin a diagnostic for a shape the DSL will not produce.
     */
    @Test
    fun `the shapes this round stopped rendering are refused on JS and Wasm too`() {
        val rows = listOf(
            "annotation class N { val p: Int = 1 }" to "members are prohibited in annotation classes",
            "class C { fun f(): Int { } }" to "missing return statement",
            "value class V(val a: Int) { val p: Int = 1 }" to
                "value class cannot have properties with backing fields",
            "data class D(a: Int)" to "primary constructor of data class must only have property",
            "object O { inner class N }" to "modifier 'inner' is not applicable inside",
            "class O { inner class M { class N } }" to "'class' is prohibited here",
            "class C { abstract fun f(): Int }" to "abstract function 'f' in non-abstract class",
            "sealed class S { public constructor(q: Int) }" to
                "constructor must be private or protected in sealed class",
            "fun interface F { fun g(): Int; val p: Int }" to
                "functional interface cannot have abstract properties",
        )
        rows.forEach { (snippet, diagnostic) ->
            val source = "package com.example\n\n$snippet\n"
            val js = compileJs(source)
            // Lowercased: the frontends capitalise the first letter of a diagnostic, and these
            // messages quote it as it reads mid-sentence.
            assertTrue(
                diagnostic in js.messages.lowercase(),
                "Kotlin/JS: $snippet\n${js.messages}",
            )
            val wasm = compileWasm(source)
            assertTrue(
                diagnostic in wasm.messages.lowercase(),
                "Kotlin/Wasm: $snippet\n${wasm.messages}",
            )
        }
    }

    /** …and the control: every remedy those nine messages name compiles on both frontends. */
    @Test
    fun `the remedies this round's messages name compile on JS and Wasm`() {
        val source = """
            package com.example

            annotation class N(val x: Int) { class Inner { val p: Int = 1 } }

            class C1 { fun f(): Int = 1 }

            value class V(val a: Int) { val p: Int get() = 1 }

            data class D(val a: Int)

            class O1 { inner class M { inner class N } }

            abstract class C2 { abstract fun f(): Int }

            sealed class S { protected constructor(q: Int) }

            fun interface F { fun g(): Int; val p: Int get() = 1 }

        """.trimIndent()
        assertCompilesEverywhereButJvm(source)
    }
}
