package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import site.asm0dey.poetdsl.ParamKind.VAL

/**
 * The cases where "the golden string looks right" is not proof: `kotlinc` gets the last word.
 *
 * Compile tests are slow — seconds each — so there are only a handful, and each covers a place
 * where the DSL invents something a human did not write: parentheses, imports, uniquified names,
 * lambda parameter lists, delegates and annotation placement.
 */
class CompileTest {
    private fun assertCompiles(spec: FileSpec) = assertCompiles(spec.toString())

    @Test
    fun `precedence output is valid kotlin`() {
        assertCompiles(
            file("com.example", "Prec") {
                `fun`("f", param("a", INT), param("b", INT), param("c", INT), returns = INT) { a, b, c ->
                    ret((a + b) * c)
                }
                `fun`("g", param("a", INT.nullable), param("b", INT.nullable), param("c", INT), returns = INT) { a, b, c ->
                    ret((a elvis b) elvis c)
                }
                `fun`("h", param("a", INT), returns = INT) { a -> ret(-(a + a)) }
                // The matrix asserts that `(a ?: b) == c` emits *without* parentheses. That is a
                // claim about Kotlin's grammar, so `kotlinc` decides it: under the other reading,
                // `a ?: (b == c)` is `Any`, and a `Boolean` return type would not compile.
                `fun`("k", param("a", INT.nullable), param("b", INT), param("c", INT), returns = BOOLEAN) { a, b, c ->
                    ret((a elvis b) eq c)
                }
            },
        )
    }

    @Test
    fun `imports resolve for type and member references`() {
        assertCompiles(
            file("com.example", "Imports") {
                `fun`("f") { `val`("xs", init = call(member("kotlin.collections", "listOf"), 1.lit)) }
            },
        )
    }

    @Test
    fun `uniquified locals do not collide with members`() {
        assertCompiles(
            file("com.example", "Shadow") {
                `class`("Holder") {
                    constructorParam(VAL, "item", STRING)
                    `fun`("use", param("items", LIST.parameterizedBy(STRING))) { items ->
                        `for`(items) { local -> +call("println", local) }
                    }
                }
            },
        )
    }

    @Test
    fun `lambdas compile with implicit and named parameters`() {
        assertCompiles(
            file("com.example", "Lambdas") {
                `fun`("f", param("xs", LIST.parameterizedBy(STRING))) { xs ->
                    `val`("a", init = xs.call("map") { p -> +p.prop("length") })
                    `val`("b", init = xs.call("map", param = "s") { s -> +s.prop("length") })
                }
            },
        )
    }

    @Test
    fun `delegated properties compile`() {
        assertCompiles(
            file("com.example", "Delegates") {
                `class`("Holder") {
                    `val`("x", INT, by = call(member("kotlin", "lazy")) { +1.lit })
                }
            },
        )
    }

    /**
     * Annotation placement is the other "looks right is not proof" case: a use-site target in the
     * wrong position, or a file annotation emitted after the package declaration, is invisible in a
     * golden string and fatal to `kotlinc`.
     */
    @Test
    fun `annotations and use site targets compile`() {
        assertCompiles(
            file("com.example", "Annotated") {
                // The explicit `null` target is not noise: `target` is the first parameter of the
                // positional overload, so a lone `Expr` would bind to it instead (deviation D2).
                addAll(annotation<Suppress>(null, "unused".lit))
                `class`(annotation<Suppress>(null, "UNUSED_PARAMETER".lit), "Holder") {
                    `val`(annotation<Suppress>(UseSiteTarget.GET, "unused".lit), "x", INT, init = 1.lit)
                    `fun`("f", param("s", STRING)) { }
                }
            },
        )
    }
}
