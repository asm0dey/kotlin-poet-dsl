package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class FunctionsTest {
    @Test
    fun `top level function with no parameters`() {
        assertEquals(
            """
            package com.example

            public fun noop() {
              work()
            }

            """.trimIndent(),
            file("com.example", "Api") { `fun`("noop") { +call("work") } }.toString(),
        )
    }

    /**
     * KotlinPoet's expression body, `= 1`, not a block body: `FunSpec.asExpressionBody` matches
     * `formatParts` positionally against the literal `"return·"`, so it only fires when the
     * `return` sits in `formatParts[0]`. `emitCode` splices its `CodeBlock` inline
     * (`add(code).add("\n")`) rather than hiding it behind a `%L` argument, which is what makes
     * the prefix visible to the match.
     */
    @Test
    fun `return type is inferred from a literal`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun one(): Int = 1

            """.trimIndent(),
            file("com.example", "Api") { `fun`("one") { ret(1.lit) } }.toString(),
        )
    }

    @Test
    fun `return type is inferred from a parameter`() {
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun echo(s: String): String = s

            """.trimIndent(),
            file("com.example", "Api") { `fun`("echo", param("s", STRING)) { s -> ret(s) } }.toString(),
        )
    }

    /**
     * The inferred types are proven by the compiler, not by the rendered text: `return 1` under a
     * declared `: String` renders perfectly well and only the compiler catches it.
     */
    @Test
    fun `inferred return types compile`() {
        assertCompiles(
            file("com.example", "Api") {
                `fun`("one") { ret(1.lit) }
                `fun`("echo", param("s", STRING)) { s -> ret(s) }
                `fun`("noop") { +call("println", "hi".lit) }
                `fun`("pick", param("flag", BOOLEAN)) { flag ->
                    `if`(flag) { ret(1.lit) }
                    ret(2.lit)
                }
            }.toString(),
        )
    }

    /** ADR 0007's first bullet: a `return` inside control flow is a real function return. */
    @Test
    fun `returns from control-flow children share the enclosing function's inference`() {
        assertEquals(
            """
            package com.example

            import kotlin.Boolean
            import kotlin.Int

            public fun pick(flag: Boolean): Int {
              if (flag) {
                return 1
              }
              return 2
            }

            """.trimIndent(),
            file("com.example", "Api") {
                `fun`("pick", param("flag", BOOLEAN)) { flag ->
                    `if`(flag) { ret(1.lit) }
                    ret(2.lit)
                }
            }.toString(),
        )
    }

    @Test
    fun `an uninferable return type is a named build error`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("mystery", param("x", STRING)) { x -> ret(x.call("foo")) }
            }
        }
        assertEquals(
            "Cannot infer the return type of 'mystery': the returned expression's type is unknown. " +
                "Pass returns = … explicitly.",
            failure.message,
        )
    }

    @Test
    fun `conflicting return types are a named build error listing them`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("mixed", param("flag", BOOLEAN)) { flag ->
                    `if`(flag) { ret(1.lit) }
                    ret("no".lit)
                }
            }
        }
        assertEquals(
            "Cannot infer the return type of 'mixed': its returns have different types " +
                "(kotlin.Int, kotlin.String). Pass returns = … explicitly.",
            failure.message,
        )
    }

    @Test
    fun `explicit returns wins`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public fun mystery(x: String): Int = x.foo()

            """.trimIndent(),
            file("com.example", "Api") {
                `fun`("mystery", param("x", STRING), returns = INT) { x -> ret(x.call("foo")) }
            }.toString(),
        )
    }

    @Test
    fun `a return inside a lambda does not drive the enclosing inference`() {
        val out = file("com.example", "Api") {
            `fun`("f", param("xs", STRING)) { xs ->
                +xs.call("forEach") { p -> `if`(p eq nul) { ret() } }
            }
        }.toString()
        assertEquals(false, out.contains("): "))
    }

    /**
     * The stronger form of the test above: the lambda returns a *typed* value, so a shared
     * `returns` list would infer `Int` for `f`. ADR 0007 isolates it, and `f` stays `Unit`.
     */
    @Test
    fun `a typed return inside a lambda does not drive the enclosing inference`() {
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun f(xs: String) {
              xs.forEach {
                return 1
              }
            }

            """.trimIndent(),
            file("com.example", "Api") {
                `fun`("f", param("xs", STRING)) { xs -> +xs.call("forEach") { ret(1.lit) } }
            }.toString(),
        )
    }

    @Test
    fun `member function sees constructor parameter handles`() {
        val out = file("com.example", "User") {
            `class`("User") {
                val username = constructorParam(VAL, "username", STRING)
                `fun`(PRIVATE.toModifiers(), "greet", param("greeting", STRING)) { greeting ->
                    +call("println", greeting)
                    +call("println", username)
                }
            }
        }.toString()
        assertEquals(true, out.contains("private fun greet(greeting: String)"))
        assertEquals(true, out.contains("println(username)"))
        assertCompiles(out)
    }

    /**
     * Deferred from Task 12, which had no member-body construct to check against: a property
     * handle's owner is the [TypeScope], and a member body is a [BlockScope] nested in it, so
     * `checkOwned` accepts it.
     */
    @Test
    fun `member function sees a property handle`() {
        val out = file("com.example", "Counter") {
            `class`("Counter") {
                val count = `val`(PRIVATE.toModifiers(), "count", INT, 0.lit)
                `fun`("show") { +call("println", count) }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public class Counter {
              private val count: Int = 0

              public fun show() {
                println(count)
              }
            }

            """.trimIndent(),
            out,
        )
        assertCompiles(out)
    }

    /**
     * ADR 0009: a parameter that would shadow an enclosing binding is renamed at declaration, so
     * the property handle still renders the property and nothing is ever qualified with `this.`.
     * Without this the body would emit `println(username)` twice, once meaning the parameter —
     * output that compiles and does the wrong thing.
     */
    @Test
    fun `a parameter that would shadow an enclosing binding is renamed`() {
        val out = file("com.example", "User") {
            `class`("User") {
                val username = constructorParam(VAL, "username", STRING)
                `fun`("rename", param("username", STRING)) { p ->
                    +call("println", p)
                    +call("println", username)
                }
            }
        }.toString()
        assertTrue(out.contains("public fun rename(username2: String)"), out)
        assertTrue(out.contains("println(username2)"), out)
        assertTrue(out.contains("println(username)\n"), out)
        assertCompiles(out)
    }

    /** A parameter's name in the output comes from the DSL call, never from the Kotlin binding. */
    @Test
    fun `the rendered parameter name comes from the DSL call`() {
        val out = file("com.example", "Api") {
            `fun`("echo", param("value", STRING)) { anythingAtAll -> ret(anythingAtAll) }
        }.toString()
        assertTrue(out.contains("public fun echo(`value`: String): String = `value`"), out)
    }

    /** D22: a Kotlin function parameter is immutable, so its handle is a `val`, not "unknown". */
    @Test
    fun `a parameter handle is immutable`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("f", param("x", INT)) { x -> x assign 2.lit }
            }
        }
        assertEquals("assign: 'x' is a val and cannot be reassigned.", failure.message)
    }

    /**
     * A function body is the first *attached* block the DSL exposes, so ADR 0008's second safety
     * layer finally fires against real generated code rather than a hand-built test scope.
     */
    @Test
    fun `a handle from one function body is rejected in another`() {
        var escaped: Expr? = null
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("a") { escaped = `val`("x", INT, 1.lit) }
                `fun`("b") { +escaped!! }
            }
        }
        assertEquals(
            "Handle from scope 'fun(a)' does not enclose the current scope 'fun(b)'.",
            failure.message,
        )
    }

    /** Kotlin permits overloads, so `fun` names deliberately skip the duplicate-name check. */
    @Test
    fun `two functions may share a name`() {
        val out = file("com.example", "Api") {
            `fun`("show", param("x", INT)) { x -> +call("println", x) }
            `fun`("show", param("s", STRING)) { s -> +call("println", s) }
        }.toString()
        assertTrue(out.contains("public fun show(x: Int)"), out)
        assertTrue(out.contains("public fun show(s: String)"), out)
        assertCompiles(out)
    }

    /**
     * D20: the block branch is reached — proving ADR 0001's dispatch — and rejected, because
     * KotlinPoet 2.3.0 cannot render a local function. Same treatment as Task 10's local class.
     */
    @Test
    fun `a local function is rejected, naming the reason`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("outer") {
                    `fun`("helper") { ret(1.lit) }
                    +call("helper")
                }
            }
        }
        assertEquals(
            "A local function cannot be rendered: KotlinPoet 2.3.0 emits an implicit `public` " +
                "on every function spliced into a code block, and Kotlin allows no visibility " +
                "modifier on a local function. Declare it at file or type level.",
            failure.message,
        )
    }

    /** Nothing partial is left behind in the enclosing block when the guard fires. */
    @Test
    fun `the rejected local function leaves the enclosing block untouched`() {
        val block = attachedBlock("fun outer")
        assertFailsWith<IllegalStateException> {
            with(block) { `fun`("helper") { ret(1.lit) } }
        }
        assertEquals("", block.builder.build().toString())
    }

    /**
     * Canary for the limitation the block branch guards against, so the guard cannot outlive its
     * reason. `CodeWriter.emitLiteral` splices a `FunSpec` with `implicitModifiers = setOf(PUBLIC)`
     * unless `omitImplicitModifiers` is set, and only `FileSpec`'s script body sets it — never a
     * `CodeBlock` nested in a function body.
     *
     * When a KotlinPoet upgrade makes the rendered source compile, this test fails — that is the
     * signal to drop `localFunIsUnrenderable` and emit for real.
     */
    @Test
    fun `local function rendering is still blocked by KotlinPoet`() {
        val spec = funSpec(name = "helper") { ret(1.lit) }
        val rendered = file("com.example", "Api") {
            +FunSpec.builder("run")
                .addCode("%L", spec)
                .addStatement("println(helper())")
                .build()
        }.toString()

        // Checked before the exact-text comparison below: on an upstream KotlinPoet fix, the
        // exact-text comparison would fail first (different rendered text) and this hint would
        // never print, so the actionable message has to sit on the assertion that fires first.
        assertTrue(
            rendered.contains("public fun helper"),
            "KotlinPoet can now render a local function — delete the guard in declareFun",
        )
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun run() {
              public fun helper(): Int = 1
              println(helper())
            }

            """.trimIndent(),
            rendered,
        )
        val result = compile(rendered)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            result.messages.contains("Modifier 'public' is not applicable to 'local function'"),
            result.messages,
        )
    }

    @Test
    fun `list form for computed parameter lists`() {
        assertEquals(
            true,
            file("com.example", "Api") {
                `fun`("wide", params = listOf(param("a", INT), param("b", INT))) { ps -> +(ps[0] + ps[1]) }
            }.toString().contains("public fun wide(a: Int, b: Int)"),
        )
    }

    @Test
    fun `func is an alias of fun`() {
        assertEquals(
            file("com.example", "Api") { `fun`("one") { ret(1.lit) } }.toString(),
            file("com.example", "Api") { func("one") { ret(1.lit) } }.toString(),
        )
    }

    @Test
    fun `a constructor is a member, with no return type`() {
        val out = file("com.example", "Box") {
            `class`("Box") {
                `constructor`(param("size", INT)) { size -> +call("println", size) }
            }
            `class`("Empty") {
                ctor { +call("println", "made".lit) }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public class Box {
              public constructor(size: Int) {
                println(size)
              }
            }

            public class Empty {
              public constructor() {
                println("made")
              }
            }

            """.trimIndent(),
            out,
        )
        assertCompiles(out)
    }

    @Test
    fun `funSpec builds a detached spec`() {
        assertEquals(
            true,
            file("com.example", "Api") { +funSpec(name = "helper") { ret(1.lit) } }
                .toString()
                .contains("public fun helper(): Int = 1"),
        )
    }

    @Test
    fun `funSpec takes modifiers and a parameter`() {
        val spec = funSpec(PRIVATE.toModifiers(), "twice", param("x", INT)) { x -> ret(x + x) }
        assertEquals(
            """
            private fun twice(x: kotlin.Int): kotlin.Int = x + x

            """.trimIndent(),
            spec.toString(),
        )
    }

    @Test
    fun `propertySpec builds a detached spec`() {
        val out = file("com.example", "Api") {
            +propertySpec(PRIVATE.toModifiers(), "limit", INT, init = 10.lit)
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            private val limit: Int = 10

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `propertySpec takes a delegate`() {
        val spec = propertySpec(name = "lazyOne", type = INT, by = call("lazy", oneValueLambda()))
        assertEquals(
            """
            val lazyOne: kotlin.Int by lazy({
                  1
                })

            """.trimIndent(),
            spec.toString(),
        )
    }

    @Test
    fun `propertySpec rejects an initializer and a delegate together`() {
        val failure = assertFailsWith<IllegalStateException> {
            propertySpec(name = "both", type = INT, init = 1.lit, by = oneValueLambda())
        }
        assertEquals(
            "propertySpec: 'both' cannot have both an initializer and a delegate.",
            failure.message,
        )
    }

    /**
     * Deferred from Task 11: `stmts` built its scope with a fresh `returns` that nothing read, so a
     * `return` in a pure fragment could not reach the function it was spliced into. ADR 0007 calls
     * a spliced fragment's `return` a real function return — only a *lambda* body is isolated — so
     * the recorded types now travel with the [Stmt] and are replayed at the splice.
     */
    @Test
    fun `a return inside a spliced fragment drives the enclosing inference`() {
        val guard = stmts { ret(1.lit) }
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun f(): Int = 1

            """.trimIndent(),
            file("com.example", "Api") { `fun`("f") { +guard } }.toString(),
        )
    }

    /** The other half of ADR 0007 still holds inside a fragment: a lambda body is isolated. */
    @Test
    fun `a return inside a fragment's lambda does not travel with the fragment`() {
        val frag = stmts { +call("run", lambda { ret(1.lit) }) }
        val out = file("com.example", "Api") { `fun`("g") { +frag } }.toString()
        assertEquals(false, out.contains("public fun g():"), out)
        assertTrue(out.contains("public fun g() {"), out)
    }

    /**
     * `buildFun`'s closing `scope.flushPending()` is the only thing that closes a control-flow
     * block left open at the very *end* of a function body: every other flush is triggered by
     * whatever statement comes next, and here nothing does. Without it this renders `if (flag) {`
     * with no closing brace — `e: Syntax error: Expecting '}'` — so the golden text and the
     * compile below are what pin the call. No other test's body shape ends in an open block.
     */
    @Test
    fun `a function body ending in an open control-flow block is closed`() {
        val out = file("com.example", "Api") {
            `fun`("f", param("flag", BOOLEAN)) { flag ->
                `if`(flag) { +call("println", "yes".lit) }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Boolean

            public fun f(flag: Boolean) {
              if (flag) {
                println("yes")
              }
            }

            """.trimIndent(),
            out,
        )
        assertCompiles(out)
    }

    /**
     * Parameters are declared in a *child* [NameScope] that is discarded when the body ends, so two
     * sibling functions each get their own `x`. Declaring into the parent scope instead would leave
     * `x` taken after the first function and rename the second's parameter to `x2` — a public API
     * name nobody asked for, in a signature with nothing to shadow. The brief names this behaviour
     * explicitly, and `a parameter that would shadow an enclosing binding is renamed` above is the
     * other half: chaining to the parent is what makes a genuine *enclosing* binding still win.
     */
    @Test
    fun `sibling functions each get their own parameter names`() {
        val out = file("com.example", "Api") {
            `fun`("first", param("x", INT)) { x -> +call("println", x) }
            `fun`("second", param("x", INT)) { x -> +call("println", x) }
        }.toString()
        assertTrue(out.contains("public fun first(x: Int)"), out)
        assertTrue(out.contains("public fun second(x: Int)"), out)
        assertEquals(false, out.contains("x2"), out)
        assertCompiles(out)
    }

    /**
     * A detached [funSpec] has no parent scope, so `buildFun` builds its body with
     * `detachedRoot = true` and an outer handle is *recorded* rather than rejected — the same call
     * `stmts { }` and `typeSpec { }` make. Building it with `detachedRoot = false` instead would
     * reject every handle the caller legitimately brought in from outside, which is the whole point
     * of a detached builder. Nothing re-judges the record afterwards, because a `FunSpec` has no
     * channel to carry it to a splice; that is inherent to returning a KotlinPoet spec.
     */
    @Test
    fun `a detached funSpec accepts a foreign handle instead of rejecting it`() {
        var escaped: Expr? = null
        file("com.example", "Api") { `fun`("a") { escaped = `val`("x", INT, 1.lit) } }
        assertEquals(
            """
            public fun b() {
              x
            }

            """.trimIndent(),
            funSpec(name = "b") { +escaped!! }.toString(),
        )
    }

    /**
     * D21's rationale, applied to function parameters: two declarations of one construct with the
     * same name in the same container is not valid Kotlin, and there is no valid output for the
     * uniquifier to rescue — renaming would only invent a public API name nobody asked for. The
     * *shadowing* case is different and still renames, because there the output is valid.
     */
    @Test
    fun `two parameters of one function may not share a name`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`("f", params = listOf(param("x", INT), param("x", STRING))) { }
            }
        }
        assertEquals(
            "param: a parameter named \"x\" is already declared in this function.",
            failure.message,
        )
    }

    /**
     * Kotlin requires a secondary constructor of a class that has a primary one to delegate with
     * `: this(…)`, and the DSL cannot express that call, so the pair renders
     * `public class Box(public val size: Int) { public constructor(other: String) { … } }` —
     * `e: Primary constructor call expected.` Rejected with a diagnostic instead.
     */
    @Test
    fun `a primary and a secondary constructor together are rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") {
                `class`("Box") {
                    constructorParam(VAL, "size", INT)
                    `constructor`(param("other", STRING)) { other -> +call("println", other) }
                }
            }
        }
        assertEquals(PRIMARY_PLUS_SECONDARY, failure.message)
    }

    /** The same guard in the other writing order — the broken output is identical. */
    @Test
    fun `a secondary constructor followed by a constructor parameter is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") {
                `class`("Box") {
                    ctor { +call("println", "made".lit) }
                    constructorParam(VAL, "size", INT)
                }
            }
        }
        assertEquals(PRIMARY_PLUS_SECONDARY, failure.message)
    }

    /**
     * Inference is skipped for a constructor, so nothing else would ever look at what its body
     * recorded and `ctor { ret(1.lit) }` would render `public constructor() { return 1 }` —
     * `e: Return type mismatch: expected 'Unit', actual 'Int'.`
     */
    @Test
    fun `a value return inside a constructor is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") { `class`("Box") { ctor { ret(1.lit) } } }
        }
        assertEquals(
            "constructor: a constructor cannot return a value. Remove the returned expression, " +
                "or move the code to a function.",
            failure.message,
        )
    }

    /** The same, reached through a spliced fragment, which replays its recorded types at the splice. */
    @Test
    fun `a value return spliced into a constructor is rejected`() {
        val guard = stmts { ret(1.lit) }
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Box") { `class`("Box") { ctor { +guard } } }
        }
        assertEquals(
            "constructor: a constructor cannot return a value. Remove the returned expression, " +
                "or move the code to a function.",
            failure.message,
        )
    }

    /** A valueless `return` records no type, so it stays legal in a constructor — and it is. */
    @Test
    fun `a valueless return inside a constructor is allowed`() {
        val out = file("com.example", "Box") {
            `class`("Box") {
                ctor { `if`(1.lit eq 1.lit) { ret() } }
            }
        }.toString()
        assertTrue(out.contains("return"), out)
        assertCompiles(out)
    }
}

/** The message both halves of the primary/secondary constructor guard raise. */
private const val PRIMARY_PLUS_SECONDARY: String =
    "constructor: a class cannot have both a primary constructor (from constructorParam) and a " +
        "secondary `constructor`, because the DSL cannot express the required `: this(…)` " +
        "delegation call. Fold the parameters into one constructor."

/** A one-statement lambda value, built outside every scope, for the delegate tests above. */
private fun oneValueLambda(): Expr = detachedLambda { +1.lit }

@OptIn(ExperimentalCompilerApi::class)
private fun compile(source: String): JvmCompilationResult = KotlinCompilation().apply {
    sources = listOf(SourceFile.kotlin("Generated.kt", source))
    inheritClassPath = true
    messageOutputStream = OutputStream.nullOutputStream()
}.compile()

@OptIn(ExperimentalCompilerApi::class)
private fun assertCompiles(source: String) {
    val result = compile(source)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
}
