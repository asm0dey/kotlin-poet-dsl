package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's imports. `%T` and `%M` already resolve everything a file *names*, so the interesting half is
 * the aliased import — D31's stated motivation, and the only fix when two generated types share a
 * simple name.
 *
 * Measured, all three frontends, one file per row:
 *
 *     import kotlin.math.PI                              clean
 *     import kotlin.math.max as mx                       clean
 *     import kotlin.collections.List as A
 *       + import kotlin.sequences.Sequence as A          conflicting import: imported name 'A' is
 *                                                         ambiguous.
 *     import kotlin.collections.List
 *       + import kotlin.collections.List as L2           clean — the control for the row above
 *     import kotlin.math.`*`                             unresolved reference '*'.
 */
@OptIn(ExperimentalCompilerApi::class)
class ImportsTest {
    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    @Test
    fun `an explicit import of package members renders`() {
        val out = render {
            `import`("kotlin.math", "PI")
            `val`("v", com.squareup.kotlinpoet.DOUBLE, init = expression("PI"))
        }
        assertTrue("import kotlin.math.PI" in out, out)
        assertCompiles(out)
        assertCompilesEverywhereButJvm(out)
    }

    @Test
    fun `an explicit import of a member name renders`() {
        val out = render {
            `import`(member("kotlin.math", "min"))
            `val`("v", INT, init = call(member("kotlin.math", "min"), 1.lit, 2.lit))
        }
        assertTrue("import kotlin.math.min" in out, out)
        assertCompiles(out)
    }

    @Test
    fun `an explicit import of a type's members renders`() {
        val out = render {
            `import`(className("kotlin", "Int", "Companion"), "MAX_VALUE")
            `val`("v", INT, init = expression("MAX_VALUE"))
        }
        assertTrue("import kotlin.Int.Companion.MAX_VALUE" in out, out)
        assertCompiles(out)
    }

    /**
     * **D31's stated motivation for this construct is false as of KotlinPoet 2.3.0, and the real one
     * is a different collision.** Both halves are built rather than reasoned about.
     *
     * D31 says an aliased import is "the only fix when two generated types share a simple name, where
     * KotlinPoet otherwise falls back to fully-qualified names". It does not fall back: it **invents
     * aliases of its own**, so `com.a.User` and `com.b.User` in one file render as `import com.a.User
     * as AUser` and `import com.b.User as BUser` with no help from anybody.
     *
     * What KotlinPoet does *not* resolve is a collision between an imported type and a type the file
     * **declares**. It emits `import com.a.User` beside `public class User`, and on the JVM the
     * explicit import wins: measured, `import java.sql.Date` + `class Date` + `fun f(x: Date) {
     * x.toLocalDate() }` is clean, and the same file calling a member of the *local* `Date` is
     * *unresolved reference 'mine'*. So the file's own type becomes unnameable by its simple name, and
     * an aliased import is the fix — alias the import and leave the declaration alone.
     */
    @Test
    fun `KotlinPoet aliases colliding imports itself, and does not resolve a local collision`() {
        val a = ClassName("com.a", "User")
        val b = ClassName("com.b", "User")
        // D31's case: KotlinPoet needs no help.
        val two = render { `fun`("f", param("x", a), param("y", b)) { _, _ -> } }
        assertTrue("import com.a.User as AUser" in two, two)
        assertTrue("import com.b.User as BUser" in two, two)
        // …and neither type is written fully qualified in the body, which is the claim D31 makes
        // about KotlinPoet's fallback and which is what fails here.
        assertTrue("f(x: AUser, y: BUser)" in two, two)
        // The collision it does not resolve: the file declares `User` and imports another.
        val clash = render {
            `class`("User") { }
            `fun`("f", param("x", a)) { _ -> }
        }
        assertTrue("import com.a.User\n" in clash, clash)
        assertTrue("public class User" in clash, clash)
        // …and on the JVM the import wins over the declaration, which is what makes it a hazard.
        assertCompiles("import java.sql.Date\nclass Date\nfun f(x: Date) { println(x.toLocalDate()) }\n")
        assertTrue(
            "nresolved reference 'mine'" in compile(
                "import java.sql.Date\nclass Date { fun mine(): Int = 1 }\n" +
                    "fun f(x: Date) { println(x.mine()) }\n",
            ).messages,
        )
        // The fix, and the reason this construct exists: alias the import, and the file's own `Date`
        // keeps its name.
        assertCompiles(
            "import java.sql.Date as SqlDate\nclass Date { fun mine(): Int = 1 }\n" +
                "fun f(x: Date, y: SqlDate) { println(x.mine()); println(y.toLocalDate()) }\n",
        )
        val fixed = render {
            aliasedImport(a, "AUser")
            `class`("User") { }
            `fun`("f", param("x", a)) { _ -> }
        }
        assertTrue("import com.a.User as AUser" in fixed, fixed)
        assertTrue("x: AUser" in fixed, fixed)
    }

    /** A real pair, so both halves compile: `java.util.Date` and `java.sql.Date`. */
    @Test
    fun `an aliased import names a type the file also declares under another package`() {
        val util = ClassName("java.util", "Date")
        val sql = ClassName("java.sql", "Date")
        val out = render { `fun`("f", param("x", util), param("y", sql)) { _, _ -> } }
        assertCompiles(out)
        val chosen = render {
            aliasedImport(sql, "SqlDate")
            `fun`("f", param("x", util), param("y", sql)) { _, _ -> }
        }
        assertTrue("import java.sql.Date as SqlDate" in chosen, chosen)
        assertTrue("y: SqlDate" in chosen, chosen)
        assertCompiles(chosen)
    }

    @Test
    fun `an aliased import of a member renders`() {
        val out = render {
            aliasedImport(member("kotlin.math", "abs"), "absolute")
            `val`("v", INT, init = expression("absolute(-1)"))
        }
        assertTrue("import kotlin.math.abs as absolute" in out, out)
        assertCompiles(out)
    }

    /** *conflicting import: imported name 'A' is ambiguous*, with the non-colliding row as control. */
    @Test
    fun `two aliased imports cannot share one alias`() {
        val e = assertFailsWith<IllegalStateException> {
            render {
                aliasedImport(ClassName("com.a", "User"), "U")
                aliasedImport(ClassName("com.b", "User"), "U")
            }
        }
        assertTrue("ambiguous" in e.message!!, e.message!!)
        // The control: two aliases, two names — and the same type imported plainly and aliased.
        assertCompiles(
            render {
                aliasedImport(ClassName("kotlin.collections", "List"), "KList")
                aliasedImport(ClassName("kotlin.sequences", "Sequence"), "KSeq")
                `val`("v", INT, init = 1.lit)
            },
        )
        assertFailsWith<IllegalStateException> { render { aliasedImport(ClassName("a", "B"), " ") } }
    }

    /**
     * **Star imports are blocked, not omitted.** Refused with an [IllegalStateException] naming the
     * construct rather than by letting KotlinPoet's `IllegalArgumentException` surface (Global
     * Constraint 26), and the canary is the second half — it fails the day KotlinPoet drops its own
     * `require`, which is when this refusal becomes a decision to revisit rather than a fact.
     */
    @Test
    fun `a star import is refused, and KotlinPoet still refuses one too`() {
        val e = assertFailsWith<IllegalStateException> { render { `import`("kotlin.math", "*") } }
        assertTrue("star import is not available" in e.message!!, e.message!!)
        assertFailsWith<IllegalStateException> {
            render { `import`(ClassName("kotlin", "Int"), "*") }
        }
        // The canary.
        val kp = assertFailsWith<IllegalArgumentException> {
            FileSpec.builder("com.example", "A").addImport("kotlin.math", "*")
        }
        assertTrue("Wildcard imports are not allowed" in kp.message!!, kp.message!!)
        // …and the language row it protects, compiled by hand because the DSL has no route to it.
        assertTrue(
            "nresolved reference '*'" in compile("import kotlin.math.`*`\nval v: Int = 1\n").messages,
        )
        // The control: the same package imported by name.
        assertCompiles(render { `import`("kotlin.math", "PI"); `val`("v", INT, init = 1.lit) })
    }

    /**
     * `context(f: FileScope)` reaching into a nested type and a block body is the **right** answer
     * here, unlike every construct that has a shadow: an import has one container and a call from
     * anywhere in the file means the same thing. Pinned, because it is a deliberate departure from
     * the rule that produced 142 shadows.
     */
    @Test
    fun `an import written inside a type or a function body attaches to the file`() {
        val out = render {
            `class`("C") {
                `import`("kotlin.math", "PI")
                `fun`("f") {
                    `import`(member("kotlin.math", "min"))
                    +call("println", expression("PI"))
                }
            }
        }
        assertTrue("import kotlin.math.PI" in out, out)
        assertTrue("import kotlin.math.min" in out, out)
        assertCompiles(out)
    }

    /** The detached builders have no [FileScope], so the call does not resolve there at all. */
    @Test
    fun `an import in a detached typeSpec does not compile`() {
        val result = compileDsl(
            """
            fun build() = typeSpec(name = "C") {
                `import`("kotlin.math", "PI")
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("FileScope" in result.messages, result.messages)
    }

    @Test
    fun `an import coexists with the imports %T resolves for itself`() {
        val out = render {
            `import`("kotlin.math", "PI")
            `val`("s", STRING, init = "a".lit)
            `fun`("f", param("x", ClassName("com.other", "Thing"))) { _ -> }
        }
        assertTrue("import com.other.Thing" in out, out)
        assertTrue("import kotlin.math.PI" in out, out)
    }
}
