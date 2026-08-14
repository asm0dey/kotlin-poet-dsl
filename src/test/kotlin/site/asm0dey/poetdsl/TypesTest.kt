package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.COMPARABLE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.NUMBER
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.UNIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import site.asm0dey.poetdsl.ParamKind.VAL

/**
 * D31/E1's type vocabulary: naming a type, composing one, and declaring the type parameters a
 * declaration binds.
 *
 * The rendering assertions are the fast half. They are not the proof — a generic signature that
 * renders plausibly and does not compile is the characteristic failure here — so every shape the
 * audit named is also handed to `kotlinc` in [TypesCompileTest].
 */
class TypesTest {
    // --- naming ---------------------------------------------------------------------------------

    @Test
    fun `className names a type the generator does not have on its classpath`() {
        assertEquals("com.example.User", className("com.example", "User").toString())
        assertEquals("com.example.Outer.Inner", className("com.example", "Outer", "Inner").toString())
    }

    @Test
    fun `typeReference keeps the type arguments reference erases`() {
        assertEquals("kotlin.collections.List<kotlin.String>", typeReference<List<String>>().toString())
        assertEquals(
            "kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.Int?>>",
            typeRef<Map<String, List<Int?>>>().toString(),
        )
        assertEquals("kotlin.collections.List<*>", typeReference<List<*>>().toString())
        assertEquals("kotlin.Array<out kotlin.Number>", typeReference<Array<out Number>>().toString())
        assertEquals("kotlin.String?", typeReference<String?>().toString())
    }

    /**
     * The gap the audit named as `reference<List<String>>()` "erases to bare
     * `kotlin.collections.List`" — closed by refusing rather than by guessing, since a silently
     * dropped type argument changes the *generated* public API.
     */
    @Test
    fun `reference refuses a type it would have to erase`() {
        val e = assertFailsWith<IllegalStateException> { reference<List<String>>() }
        assertTrue("reference:" in e.message!!, e.message!!)
        assertTrue("typeReference<T>()" in e.message!!, e.message!!)
        // The two routes the message may name, and the one it must not: recommending
        // `reference<List<*>>()` steered the caller straight back into the erasure this rejects.
        assertTrue("className(" in e.message!!, e.message!!)
        assertFalse("<*>>()" in e.message!!, e.message!!)
    }

    /**
     * A star projection erases exactly as badly: `reference<List<*>>()` used to hand back bare
     * `kotlin.collections.List`, which is not a Kotlin type in either position —
     * `fun f(xs: List)` and `class C : List` are both compile errors. So the guard covers any type
     * argument at all, star or concrete.
     */
    @Test
    fun `reference refuses a star projection too`() {
        val onList = assertFailsWith<IllegalStateException> { reference<List<*>>() }
        assertTrue("reference:" in onList.message!!, onList.message!!)
        assertTrue("typeReference<T>()" in onList.message!!, onList.message!!)
        val onMap = assertFailsWith<IllegalStateException> { ref<Map<*, *>>() }
        assertTrue("reference:" in onMap.message!!, onMap.message!!)
        // A type with no arguments at all is untouched — that is what `reference` is for.
        assertEquals("kotlin.String", reference<String>().toString())
    }

    /**
     * The same silent drop by a different route, found by the fix round's review: nullability is
     * `isMarkedNullable`, not an argument, so `reference<String?>()` sailed past the argument guard
     * and returned non-null `kotlin.String`. A `ClassName` cannot carry a `?` at all, so there is no
     * honest answer to give — only a refusal.
     */
    @Test
    fun `reference refuses a nullable type`() {
        val e = assertFailsWith<IllegalStateException> { reference<String?>() }
        assertTrue("reference:" in e.message!!, e.message!!)
        assertTrue("nullable" in e.message!!, e.message!!)
        assertTrue("typeReference<T>()" in e.message!!, e.message!!)
        // The non-null spelling is what `reference` is for, and typeReference keeps the `?`.
        assertEquals("kotlin.String", reference<String>().toString())
        assertEquals("kotlin.String?", typeReference<String?>().toString())
    }

    /** The escape hatch the message offers instead: the raw class, named out loud. */
    @Test
    fun `className still names a raw generic class explicitly`() {
        assertEquals("kotlin.collections.List", className("kotlin.collections", "List").toString())
    }

    // --- composing ------------------------------------------------------------------------------

    @Test
    fun `parameterizedBy applies type arguments and nests`() {
        assertEquals("kotlin.collections.List<kotlin.String>", LIST.parameterizedBy(STRING).toString())
        assertEquals(
            "kotlin.collections.Map<kotlin.String, kotlin.collections.List<com.example.User?>>",
            MAP.of(STRING, LIST.of(className("com.example", "User").nullable)).toString(),
        )
    }

    @Test
    fun `parameterizedBy rejects an empty argument list`() {
        val e = assertFailsWith<IllegalStateException> { LIST.parameterizedBy() }
        assertTrue("parameterizedBy:" in e.message!!, e.message!!)
    }

    @Test
    fun `use site variance and star projections render`() {
        assertEquals("kotlin.collections.List<out kotlin.Number>", LIST.of(out(NUMBER)).toString())
        assertEquals("kotlin.collections.List<in kotlin.String>", LIST.of(`in`(STRING)).toString())
        assertEquals("kotlin.collections.List<*>", LIST.of(STAR).toString())
    }

    @Test
    fun `function types render in every shape`() {
        assertEquals("(kotlin.String) -> kotlin.Int", functionType(STRING, returns = INT).toString())
        assertEquals("() -> kotlin.Unit", functionType(returns = UNIT).toString())
        assertEquals(
            "kotlin.String.() -> kotlin.Unit",
            funType(receiver = STRING, returns = UNIT).toString(),
        )
        assertEquals(
            "suspend (kotlin.String) -> kotlin.Unit",
            functionType(STRING, returns = UNIT, suspending = true).toString(),
        )
        // A function type is a type like any other, so it composes.
        assertEquals(
            "kotlin.collections.List<(kotlin.String) -> kotlin.Int>",
            LIST.of(functionType(STRING, returns = INT)).toString(),
        )
    }

    // --- declaring type parameters ---------------------------------------------------------------

    @Test
    fun `typeVariable carries bounds variance and reified`() {
        val t = typeVariable("T")
        assertEquals("T", t.toString())
        assertEquals(listOf(ANY.nullable), typeVariable("T").bounds)
        assertEquals(listOf(ANY), typeVariable("T", ANY).bounds)
        assertEquals(KModifier.IN, typeVar("K", variance = KModifier.IN).variance)
        assertTrue(typeVariable("T", reified = true).isReified)
    }

    @Test
    fun `typeVariable rejects a variance Kotlin has no word for`() {
        val e = assertFailsWith<IllegalStateException> { typeVariable("T", variance = KModifier.PRIVATE) }
        assertTrue("typeVariable:" in e.message!!, e.message!!)
    }

    @Test
    fun `a generic class renders its type parameters and uses them in the signature`() {
        val t = typeVariable("T")
        assertEquals(
            """
            |public class Box<T>(
            |  public val item: T,
            |)
            |
            """.trimMargin(),
            file("com.example", "Box") {
                `class`("Box", param(VAL, "item", t), typeVariables = listOf(t)) { }
            }.let { renderTypes(it.toString()) },
        )
    }

    @Test
    fun `declaration site variance renders on a class`() {
        val k = typeVariable("K", variance = KModifier.IN)
        val v = typeVariable("V", variance = KModifier.OUT)
        val rendered = file("com.example", "Cache") {
            `class`("Cache", typeVariables = listOf(k, v)) {
                `fun`("get", param("key", k), returns = v) { +expression("TODO()") }
            }
        }.toString()
        assertTrue("public class Cache<in K, out V>" in rendered, rendered)
    }

    @Test
    fun `a generic function renders a self referential bound`() {
        val t = typeVariable("T", COMPARABLE.of(typeVariable("T")))
        val rendered = file("com.example", "Max") {
            `fun`("max", param("a", t), param("b", t), typeVariables = listOf(t), returns = t) { a, _ -> ret(a) }
        }.toString()
        assertTrue("public fun <T : Comparable<T>> max(a: T, b: T): T" in rendered, rendered)
    }

    @Test
    fun `an interface takes type parameters`() {
        val t = typeVariable("T")
        val rendered = file("com.example", "Repo") {
            `interface`("Repo", typeVariables = listOf(t)) {
                `fun`(KModifier.ABSTRACT, "find", param("id", INT), returns = t) { }
            }
        }.toString()
        assertTrue("public interface Repo<T>" in rendered, rendered)
    }

    /** The detached builders take the same slot, so interop with hand-written KotlinPoet is unchanged. */
    @Test
    fun `the detached builders take type parameters`() {
        val t = typeVariable("T")
        assertTrue("class Box<T>" in typeSpec(name = "Box", typeVariables = listOf(t)) { }.toString())
        assertTrue(
            "fun <T> id(x: T): T" in
                funSpec(name = "id", p1 = param("x", t), typeVariables = listOf(t), returns = t) { x -> ret(x) }
                    .toString(),
        )
    }

    // --- the guards -------------------------------------------------------------------------------

    @Test
    fun `a duplicate type parameter name is rejected`() {
        val t = typeVariable("T")
        val onFun = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f", typeVariables = listOf(t, typeVariable("T"))) { } }
        }
        assertTrue("more than once" in onFun.message!!, onFun.message!!)
        val onClass = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("C", typeVariables = listOf(t, t)) { } }
        }
        assertTrue("`class`:" in onClass.message!!, onClass.message!!)
    }

    @Test
    fun `declaration site variance on a function is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `fun`("f", typeVariables = listOf(typeVariable("T", variance = KModifier.OUT))) { }
            }
        }
        assertTrue("`fun`:" in e.message!!, e.message!!)
        assertTrue("only on a class or interface" in e.message!!, e.message!!)
    }

    @Test
    fun `reified is rejected off an inline function and accepted on one`() {
        val t = typeVariable("T", reified = true)
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f", typeVariables = listOf(t)) { } }
        }
        assertTrue("only on an `inline` function" in e.message!!, e.message!!)
        val rendered = file("com.example", "A") {
            `fun`(KModifier.INLINE, "f", typeVariables = listOf(t)) { }
        }.toString()
        assertTrue("public inline fun <reified T> f()" in rendered, rendered)
    }

    /** A class has no `reified`, whatever modifiers it carries — that is an inline function's word. */
    @Test
    fun `reified on a class is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("C", typeVariables = listOf(typeVariable("T", reified = true))) { } }
        }
        assertTrue("`class`:" in e.message!!, e.message!!)
    }

    /**
     * `enum class E<T>` renders perfectly and does not compile — Kotlin gives an enum class no type
     * parameters of its own, since its entries are singletons of the class itself. The same class of
     * user error as declaration-site variance on a function, caught in the same place: `declareType`
     * already holds both the modifiers and the type parameters.
     */
    @Test
    fun `an enum class rejects type parameters`() {
        val t = typeVariable("T")
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`(KModifier.ENUM, "E", typeVariables = listOf(t)) { } }
        }
        assertTrue("`class`:" in e.message!!, e.message!!)
        assertTrue("enum class" in e.message!!, e.message!!)

        // The detached builder renders the same invalid Kotlin, so it takes the same guard.
        val detached = assertFailsWith<IllegalStateException> {
            typeSpec(KModifier.ENUM.toModifiers(), "E", typeVariables = listOf(t)) { }
        }
        assertTrue("typeSpec:" in detached.message!!, detached.message!!)

        // The positive half: an enum class with no type parameters is untouched.
        val rendered = file("com.example", "A") { `class`(KModifier.ENUM, "E") { } }.toString()
        assertTrue("public enum class E" in rendered, rendered)
    }

    /**
     * `annotation class Ann<T>` **is** valid Kotlin (compiled in [TypesCompileTest]), unlike the enum
     * case, so it is deliberately not guarded — the guard is about what Kotlin rejects, not about
     * which declarations look unusual.
     */
    @Test
    fun `an annotation class keeps its type parameters`() {
        val rendered = file("com.example", "A") {
            `class`(KModifier.ANNOTATION, "Ann", typeVariables = listOf(typeVariable("T"))) { }
        }.toString()
        assertTrue("public annotation class Ann<T>" in rendered, rendered)
    }

    /** Everything above is a plain function of its arguments, so it composes without a scope. */
    @Test
    fun `the type vocabulary needs no scope`() {
        assertEquals(
            "kotlin.collections.Map<kotlin.String, (T) -> kotlin.Boolean>",
            MAP.of(STRING, functionType(typeVariable("T"), returns = BOOLEAN)).toString(),
        )
    }

    /** Strips a `FileSpec`'s package header, so a golden string is about the declaration alone. */
    private fun renderTypes(source: String): String = source.substringAfter("\n\n")
}
