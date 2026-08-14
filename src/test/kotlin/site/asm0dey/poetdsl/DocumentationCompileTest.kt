package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test

/**
 * `kotlinc`'s verdict on E2b's documentation output.
 *
 * A KDoc block is a comment, so it is easy to assume it cannot break a build. It can: KotlinPoet
 * emits a primary-constructor parameter's own `kdoc` *inline*, inside the parameter list, as well as
 * folding it into the class's `@param` tag — so a documented `class C(val id: Int)` renders a
 * comment in a position no hand-written example in this suite covers. And `%` prose has to survive
 * both the format layer and the renderer's own line wrapping.
 */
@OptIn(ExperimentalCompilerApi::class)
class DocumentationCompileTest {
    @Test
    fun `documented output compiles, percent signs and all`() {
        val text = "100% done. A %L is a literal, %T a type, %S a string, and %% is an escape."
        val spec = file("com.example", "Documented", fileComment = text) {
            `val`("top", INT, init = 1.lit, kdoc = text)
            `fun`(
                "repeated",
                param("n", INT, kdoc = text),
                returns = STRING,
                receiver = STRING,
                kdoc = text,
                receiverKdoc = text,
                returnsKdoc = text,
            ) { n -> ret(expression("this").call("repeat", n)) }
            `class`("C", param(VAL, "id", INT, kdoc = text), kdoc = text) { _ ->
                `var`("m", INT, init = 0.lit, kdoc = text)
                // A `String` parameter, not an `Int`: a second `(Int)` constructor would clash with the
                // primary one, which is a fact about the example rather than about KDoc.
                `constructor`(param("other", STRING), kdoc = text) { _ -> `this`(0.lit) }
                `fun`("f", kdoc = text) { }
            }
            `interface`("I", kdoc = text) { }
            `object`("O", kdoc = text) { }
            +typeSpec(name = "T", kdoc = text) { }
            +funSpec(name = "g", returns = INT, kdoc = text, returnsKdoc = text) { ret(1.lit) }
            +propertySpec(name = "p", type = INT, init = 1.lit, kdoc = text)
        }
        assertCompiles(spec.toString())
    }

    /**
     * A multi-line and a blank KDoc, both of which reach the `*`-prefixing renderer differently from
     * the one-liners above.
     */
    @Test
    fun `multi-line and empty documentation compiles`() {
        val spec = file("com.example", "Edges", fileComment = "line one\nline two") {
            `fun`("f", kdoc = "First paragraph.\n\nSecond paragraph, with a * star and a / slash.") { }
            `fun`("g", kdoc = "") { }
        }
        assertCompiles(spec.toString())
    }
}
