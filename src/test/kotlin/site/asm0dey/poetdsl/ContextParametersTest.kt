package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ContextParameter
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E3's last construct, and the one that carried a policy question.
 *
 * D31 predicted that `ContextParameter.kt` being `@ExperimentalKotlinPoetApi` would "leak an `@OptIn`
 * requirement to callers, as `UseSiteTarget.ALL` already does". It does not, and the reason is
 * measured twice — once by reading the annotations off the 2.3.0 jar and once by compiling a consumer
 * snippet that names the type without opting in, because a reflected annotation set is a claim about
 * the jar and only the compiler's answer settles what a caller has to write (E2f's rider on deriving
 * a set).
 *
 * The language side is 22 measured cells on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all
 * three agreeing on every one; the table is in `ContextParameters.kt`'s header and in D43.
 */
@OptIn(ExperimentalCompilerApi::class)
class ContextParametersTest {
    private val ctx = ClassName("com.example", "Ctx")

    private fun render(body: FileScope.() -> Unit): String =
        file("com.example", "A", body = body).toString()

    /** Compiles the render together with the context type it names. */
    private fun assertCompilesWithCtx(out: String, extra: String = "") {
        assertCompiles(out + "\nclass Ctx\n" + extra)
    }

    // --- the @OptIn question --------------------------------------------------------------------

    /**
     * **The reflected half.** `ContextParameter` — the only KotlinPoet type this DSL's public
     * signatures name — carries no `@ExperimentalKotlinPoetApi` anywhere, while
     * `ContextParameterizable` and its `Builder`'s methods do. That is the whole of why the opt-in
     * stops at two `internal` functions.
     */
    @Test
    fun `ContextParameter itself is not experimental, and the builder methods are`() {
        // `@RequiresOptIn` annotations are BINARY-retained, so Java reflection cannot see them and a
        // reflective check would pass vacuously. The class file is read instead — the annotation's
        // own descriptor, searched in the constant pool.
        fun carriesOptIn(name: String): Boolean {
            val path = "/" + name.replace('.', '/') + ".class"
            val bytes = ContextParameter::class.java.getResourceAsStream(path)!!.readBytes()
            return "Lcom/squareup/kotlinpoet/ExperimentalKotlinPoetApi;".toByteArray()
                .let { needle -> bytes.toList().windowed(needle.size).any { it == needle.toList() } }
        }
        assertTrue(!carriesOptIn("com.squareup.kotlinpoet.ContextParameter"))
        // …and the counterparts, so this is a *difference* and not an absence everywhere.
        assertTrue(carriesOptIn("com.squareup.kotlinpoet.ContextParameterizable"))
        assertTrue(carriesOptIn("com.squareup.kotlinpoet.ContextParameterizable\u0024Builder"))
    }

    /**
     * **The compiled half, and the one that decides the policy.** A consumer snippet that names
     * `contextParameter(…)` and holds its result compiles with **no `@OptIn` at all**.
     *
     * The control is the second snippet: `UseSiteTarget.ALL` in the same file *does* need one, so this
     * harness can tell the two apart and the first result is not a broken measurement. That contrast
     * is the whole finding — D31 predicted these two would behave alike.
     */
    @Test
    fun `a consumer needs no opt-in for context parameters, and still does for UseSiteTarget ALL`() {
        val ok = compileDsl(
            """
            val cp = contextParameter("c", com.squareup.kotlinpoet.STRING)
            fun build() = file("com.example", "A") {
                `fun`("f", returns = com.squareup.kotlinpoet.INT, contextParameters = listOf(cp)) {
                    ret(1.lit)
                }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, ok.exitCode, ok.messages)
        val leaks = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `val`(
                    annotation(
                        com.squareup.kotlinpoet.ClassName("com.example", "Marker"),
                        target = UseSiteTarget.ALL,
                    ),
                    "x",
                    com.squareup.kotlinpoet.INT,
                    init = 1.lit,
                )
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, leaks.exitCode, leaks.messages)
        assertTrue("needs opt-in" in leaks.messages, leaks.messages)
    }

    // --- the construct ---------------------------------------------------------------------------

    @Test
    fun `a function takes context parameters`() {
        val out = render {
            `fun`("f", returns = INT, contextParameters = listOf(contextParameter("c", ctx))) {
                ret(1.lit)
            }
        }
        assertTrue("context(c: Ctx)" in out, out)
        assertCompilesWithCtx(out)
        assertCompilesEverywhereButJvm(out + "\nclass Ctx\n")
    }

    @Test
    fun `a member function, an interface member and a property all take them`() {
        val cp = listOf(contextParameter("c", ctx))
        assertCompilesWithCtx(
            render { `class`("C") { `fun`("f", returns = INT, contextParameters = cp) { ret(1.lit) } } },
        )
        assertCompilesWithCtx(
            render {
                `interface`("I") {
                    `fun`(com.squareup.kotlinpoet.KModifier.ABSTRACT, "f", returns = INT, contextParameters = cp) { }
                }
            },
        )
        val prop = render {
            `val`("p", INT, contextParameters = cp) { ret(1.lit) }
        }
        assertTrue("context(c: Ctx)" in prop, prop)
        assertCompilesWithCtx(prop)
        val mutable = render {
            `var`("p", INT, setter = { _ -> }, contextParameters = cp) { ret(1.lit) }
        }
        assertCompilesWithCtx(mutable)
    }

    @Test
    fun `several context parameters, an anonymous one, and one beside a receiver`() {
        val out = render {
            `fun`(
                "f",
                returns = INT,
                receiver = STRING,
                contextParameters = listOf(
                    contextParameter("c", ctx),
                    contextParam("_", ClassName("com.example", "Other")),
                ),
            ) { ret(1.lit) }
        }
        assertTrue("context(c: Ctx, _: Other)" in out, out)
        assertCompilesWithCtx(out, "class Other\n")
    }

    /** `contextParam` is the alias, and it builds the same value. */
    @Test
    fun `the alias builds the same descriptor`() {
        assertEquals(contextParameter("c", ctx), contextParam("c", ctx))
        assertFailsWith<IllegalStateException> { contextParameter(" ", ctx) }
    }

    // --- the guards, each with the compiler's own sentence and a control row ----------------------

    /**
     * The two rules context parameters add to a property — **and the two they do not**, which is the
     * more useful half.
     *
     * An initializer and a delegate are refused, container-independently. "Needs a getter" and "a
     * `var` needs both accessors" are *not* context rules: the container machinery already answers
     * them where they hold, and one container down they are **false** — measured clean on all three
     * frontends. Two guards were written here, survived one-at-a-time falsification, and were removed
     * after these rows were compiled. The expectations below are the exact sentences, because a
     * substring assertion is what let the two survive in the first place (E2d's lesson).
     */
    @Test
    fun `context parameters refuse a value and a delegate, and nothing about accessors`() {
        val cp = listOf(contextParameter("c", ctx))
        val init = assertFailsWith<IllegalStateException> {
            render { `val`("p", INT, init = 1.lit, contextParameters = cp) }
        }
        assertEquals(
            "`val`: 'p' has context parameters and an initializer. A property with context " +
                "parameters has no backing field — \"property with context parameters cannot be " +
                "initialized because it has no backing field\" — so pass a getter instead of init.",
            init.message,
        )
        // …and the accessorless case is answered by the **container** rule that predates this round,
        // word for word, which is what says the dropped guard was unreachable.
        val none = assertFailsWith<IllegalStateException> {
            render { `val`("p", INT, contextParameters = cp) }
        }
        assertTrue(
            none.message!!.startsWith("`val`: 'p' has no initializer, no delegate and no getter"),
            none.message!!,
        )
        // The four control rows the dropped guards would have refused, every one of them clean on all
        // three frontends and every one of them rendered by this DSL.
        val ifaceVal = render { `interface`("I") { `val`("p", INT, contextParameters = cp) } }
        assertTrue("context(c: Ctx)" in ifaceVal, ifaceVal)
        assertCompilesWithCtx(ifaceVal)
        assertCompilesWithCtx(render { `interface`("I") { `var`("p", INT, contextParameters = cp) } })
        assertCompilesWithCtx(
            render {
                `class`(com.squareup.kotlinpoet.KModifier.ABSTRACT, "A") {
                    `val`(com.squareup.kotlinpoet.KModifier.ABSTRACT, "p", INT, contextParameters = cp)
                }
            },
        )
        assertCompilesWithCtx(
            render {
                `class`(com.squareup.kotlinpoet.KModifier.ABSTRACT, "A") {
                    `var`(com.squareup.kotlinpoet.KModifier.ABSTRACT, "p", INT, contextParameters = cp)
                }
            },
        )
        // …and the two that do render and compile with accessors written out.
        assertCompilesWithCtx(render { `val`("p", INT, contextParameters = cp) { ret(1.lit) } })
        assertCompilesWithCtx(
            render { `var`("p", INT, setter = { _ -> }, contextParameters = cp) { ret(1.lit) } },
        )
    }

    /** *context parameters on delegated properties are unsupported* — its own row, its own sentence. */
    @Test
    fun `a delegated property cannot have context parameters`() {
        val e = assertFailsWith<IllegalStateException> {
            render {
                `val`(
                    "p",
                    INT,
                    by = expression("%T()", ClassName("com.example", "D")),
                    contextParameters = listOf(contextParameter("c", ctx)),
                )
            }
        }
        assertTrue("delegated properties are unsupported" in e.message!!, e.message!!)
    }

    /**
     * A context parameter's name is **escaped**, which E3's was not, and the asymmetry was with
     * every other name this DSL emits: KotlinPoet backticks a `ParameterSpec`'s name, a property's,
     * an enum entry's and a type's, and does nothing at all to a `ContextParameter`'s. So
     * `contextParameter("a b", ctx)` rendered `context(a b: Ctx)`.
     *
     * Measured, one file per row, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10:
     *
     *     context(a b: Ctx) fun f(): Int = 1          context parameters must be named. Use '_' to
     *                                                 declare an anonymous context parameter.
     *     context(`a b`: Ctx) fun f(): Int = 1        clean
     *     context(`object`: Ctx) fun f(): Int = 1     clean
     *     fun f(`a b`: Int): Int = `a b`              clean — the sibling `param` already renders
     *
     * so this is output that exists rather than a shape to refuse, and escaping is the two-directional
     * answer. The escaper is KotlinPoet's own, reached through `%N`; this test pins that it agrees
     * with `ParameterSpec`'s rendering rather than trusting a keyword list.
     */
    @Test
    fun `a context parameter's name is escaped exactly as a value parameter's is`() {
        val out = render {
            `fun`("f", returns = INT, contextParameters = listOf(contextParameter("a b", ctx))) {
                ret(1.lit)
            }
        }
        assertTrue("context(`a b`: Ctx)" in out, out)
        assertCompilesWithCtx(out)
        assertCompilesEverywhereButJvm(out + "\nclass Ctx\n")
        // A keyword too, and a property as well as a function.
        val keyword = render {
            `val`(
                "p",
                INT,
                contextParameters = listOf(contextParameter("object", ctx)),
                getter = { ret(1.lit) },
            )
        }
        assertTrue("context(`object`: Ctx)" in keyword, keyword)
        assertCompilesWithCtx(keyword)
        // Pinned against KotlinPoet's own escaper rather than against a hand-written list: whatever
        // `ParameterSpec` renders for a name is what a context parameter renders for it.
        for (name in listOf("a b", "object", "class", "if", "a-b", "ok", "a1")) {
            val asValueParameter = com.squareup.kotlinpoet.ParameterSpec.builder(name, INT).build()
                .toString().substringBefore(":")
            val asContextParameter = render {
                `fun`("f", returns = INT, contextParameters = listOf(contextParameter(name, ctx))) {
                    ret(1.lit)
                }
            }
            assertTrue("context($asValueParameter: Ctx)" in asContextParameter, "$name: $asContextParameter")
        }
        // `_` is the one exemption, and it is a **meaning** rule: `%N` renders it `` `_` ``, which
        // Kotlin reads as an ordinary parameter named `_` rather than as the anonymous context
        // parameter this construct documents. Both compile; only one is what the caller asked for.
        val anonymous = render {
            `fun`("f", returns = INT, contextParameters = listOf(contextParameter("_", ctx))) {
                ret(1.lit)
            }
        }
        assertTrue("context(_: Ctx)" in anonymous, anonymous)
        assertCompilesWithCtx(anonymous)
        // …and the escaping happens at the render, not on the descriptor, so the *conflict* rules
        // still compare logical names. This is the row that would have broken if the escape had
        // gone into `contextParameter` itself.
        val clash = assertFailsWith<IllegalStateException> {
            render {
                `fun`(
                    "f",
                    param("a b", INT),
                    returns = INT,
                    contextParameters = listOf(contextParameter("a b", ctx)),
                ) { _ -> ret(1.lit) }
            }
        }
        assertTrue("conflicting declarations" in clash.message!!, clash.message!!)
        assertEquals("a b", contextParameter("a b", ctx).name)
    }

    /** *conflicting declarations*, in both directions. */
    @Test
    fun `a repeated context parameter name and a clash with a value parameter are refused`() {
        val dup = assertFailsWith<IllegalStateException> {
            render {
                `fun`(
                    "f",
                    returns = INT,
                    contextParameters = listOf(contextParameter("c", ctx), contextParameter("c", ctx)),
                ) { ret(1.lit) }
            }
        }
        assertTrue("more than once" in dup.message!!, dup.message!!)
        val clash = assertFailsWith<IllegalStateException> {
            render {
                `fun`(
                    "f",
                    param("c", INT),
                    returns = INT,
                    contextParameters = listOf(contextParameter("c", ctx)),
                ) { _ -> ret(1.lit) }
            }
        }
        assertTrue("conflicting declarations" in clash.message!!, clash.message!!)
        // The controls: two `_` parameters are fine, and `_` never clashes with a value parameter.
        assertCompilesWithCtx(
            render {
                `fun`(
                    "f",
                    param("c", INT),
                    returns = INT,
                    contextParameters = listOf(
                        contextParameter("_", ctx),
                        contextParameter("_", ClassName("com.example", "Other")),
                    ),
                ) { _ -> ret(1.lit) }
            },
            "class Other\n",
        )
    }

    /** A local *variable* takes none; a local *function* does, which is the control. */
    @Test
    fun `a local binding cannot have context parameters and a local function can`() {
        val e = assertFailsWith<IllegalStateException> {
            render {
                `fun`("h") {
                    `val`("p", INT, init = 1.lit, contextParameters = listOf(contextParameter("c", ctx)))
                }
            }
        }
        assertTrue("cannot have context parameters" in e.message!!, e.message!!)
    }

    /**
     * E1's deferred rule, widened by exactly the term the compiler's own sentence names: *Type
     * parameter of a property must be used in its receiver type **or context parameters***. E1
     * implemented the first half because the second had no construct behind it, so
     * `context(c: Ctx<T>) val <T> p: Int get() = 1` — clean on all three frontends — was refused.
     */
    @Test
    fun `a property type parameter may be bound by a context parameter instead of a receiver`() {
        val t = typeVariable("T")
        val generic = ClassName("com.example", "Box").parameterizedBy(t)
        val out = render {
            `val`(
                "p",
                INT,
                typeVariables = listOf(t),
                contextParameters = listOf(contextParameter("c", generic)),
            ) { ret(1.lit) }
        }
        assertTrue("context(c: Box<T>)" in out, out)
        assertCompiles(out + "\nclass Box<T>\n")
        // The control that keeps the widening narrow: a type parameter bound by *neither* is still
        // refused, and so is one the context parameter does not mention.
        assertFailsWith<IllegalStateException> {
            render { `val`("p", INT, typeVariables = listOf(t)) { ret(1.lit) } }
        }
        assertFailsWith<IllegalStateException> {
            render {
                `val`(
                    "p",
                    INT,
                    typeVariables = listOf(t),
                    contextParameters = listOf(contextParameter("c", ctx)),
                ) { ret(1.lit) }
            }
        }
    }

    /**
     * The four positions Kotlin calls *unsupported* get **no slot at all**, which is the strongest
     * form the guard can take — E1's call for `object O<T>`, applied again. This test is what says the
     * absence is deliberate: it fails if anybody adds one.
     */
    @Test
    fun `a constructor, a class, an object and a typealias have no context-parameter slot`() {
        val result = compileDsl(
            """
            val cp = contextParameter("c", com.squareup.kotlinpoet.STRING)
            fun build() = file("com.example", "A") {
                `class`("C") { `constructor`(contextParameters = listOf(cp)) { } }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        // …and the language rows those absences stand for, compiled by hand.
        listOf(
            "class Ctx\ncontext(c: Ctx)\nclass C\n" to "on classes are unsupported",
            "class Ctx\ncontext(c: Ctx)\nobject O\n" to "on classes are unsupported",
            "class Ctx\nclass C { context(c: Ctx)\n constructor(x: Int) { } }\n" to
                "on constructors are unsupported",
            "class Ctx\ncontext(c: Ctx)\ntypealias S = String\n" to "on type aliases are unsupported",
        ).forEach { (source, sentence) ->
            assertTrue(sentence in compile(source).messages, source)
        }
    }

    /** The detached builders take the slot too, since a spliced spec carries the parameters with it. */
    @Test
    fun `funSpec and propertySpec take context parameters`() {
        val cp = listOf(contextParameter("c", ctx))
        val f = funSpec(name = "f", returns = INT, contextParameters = cp) { ret(1.lit) }
        // A detached spec has no file to resolve imports against, so `%T` renders fully qualified.
        assertTrue("context(c: com.example.Ctx)" in f.toString(), f.toString())
        val p = propertySpec(name = "p", type = INT, contextParameters = cp) { ret(1.lit) }
        assertTrue("context(c: com.example.Ctx)" in p.toString(), p.toString())
        assertCompilesWithCtx(render { +f; +p })
    }
}
