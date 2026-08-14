package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import site.asm0dey.poetdsl.ParamKind.VAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class TypeScopeTest {
    @Test
    fun `data class with constructor parameters`() {
        val out = file("com.example", "User") {
            `class`(DATA.toModifiers(), "User") {
                constructorParam(VAL, "username", STRING)
                constructorParam(VAL, "email", STRING)
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public data class User(
              public val username: String,
              public val email: String,
            )

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `modifier set resolves positionally`() {
        val out = file("com.example", "Repo") {
            `class`(SEALED + INTERNAL, "Repo") { }
        }.toString()
        assertEquals(
            """
            package com.example

            internal sealed class Repo

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `nested class inside a type`() {
        val out = file("com.example", "Outer") {
            `class`("Outer") {
                `class`("Inner") { }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            public class Outer {
              public class Inner
            }

            """.trimIndent(),
            out,
        )
    }

    /**
     * Important 1: a nested (non-`inner`) class cannot see its enclosing type's members, so
     * there is nothing for a same-named constructor parameter to shadow. Before the fix,
     * `declareType` chained `names.child()` from the enclosing scope, so `Inner`'s `id` was
     * silently renamed to `id2` — changing `Inner`'s public constructor signature for no
     * reason. This asserts the nested parameter keeps its own name.
     */
    @Test
    fun `a nested type's constructor parameter keeps its name even when an enclosing type uses it`() {
        val rendered = file("com.example", "Outer") {
            `class`("Outer") {
                constructorParam(VAL, "id", STRING)
                `class`("Inner") {
                    constructorParam(VAL, "id", STRING)
                }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class Outer(
              public val id: String,
            ) {
              public class Inner(
                public val id: String,
              )
            }

            """.trimIndent(),
            rendered,
        )
    }

    /**
     * The nested-type twin of Important 2's companion fix: a nested (non-`inner`) type's
     * `ScopeId` used to chain to its enclosing type's, the same hole that made a companion body
     * wrongly accept an enclosing-instance handle. A nested type cannot see the enclosing type's
     * instance members either, so this is the identical measured shape one level over.
     */
    @Test
    fun `an enclosing-instance handle is rejected inside a nested type's member body`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "N2") {
                `class`("N2", param(VAL, "id", LONG)) { id ->
                    `class`("Inner") { `init` { +call("println", id) } }
                }
            }
        }
        assertTrue("does not enclose the current scope" in failure.message.orEmpty(), "${failure.message}")
    }

    /** kotlinc's word on the shape the guard above refuses to produce. */
    @Test
    fun `kotlinc refuses the enclosing-instance handle a nested type would have smuggled in`() {
        val result = compile("class N2(val id: Long) { class Inner { init { println(id) } } }")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "Outer class 'class N2 : Any' of non-inner class cannot be used as receiver" in result.messages,
            result.messages,
        )
    }

    /**
     * The other half of [declareType]'s conditional root: a *top-level* type still chains to the
     * enclosing file's scope, because that direction is legitimate — a file-level declaration is
     * visible in every type's body in the same file, nested or not, and kotlinc agrees.
     */
    @Test
    fun `a file-level handle is accepted inside a top-level type's member body`() {
        var handle: Expr? = null
        val rendered = file("com.example", "Api") {
            handle = `val`("limit", INT, init = 10.lit)
            `class`("C") { `fun`("f") { +call("println", handle) } }
        }.toString()
        assertTrue("println(limit)" in rendered, rendered)
        assertCompiles(rendered)
    }

    @Test
    fun `constructor parameter handles are visible to sibling members`() {
        val out = file("com.example", "User") {
            `class`("User") {
                val username = constructorParam(VAL, "username", STRING)
                `object`("Companionish") { }
                assertEquals("username", username.toString())
            }
        }.toString()
        assertEquals(true, out.contains("public val username: String"))
    }

    @Test
    fun `typeSpec builds a detached spec`() {
        val spec = typeSpec(name = "Helper") { }
        assertEquals(
            """
            package com.example

            public class Helper

            """.trimIndent(),
            file("com.example", "Api") { +spec }.toString(),
        )
    }

    @Test
    fun `klass is an alias`() {
        assertEquals(
            file("com.example", "A") { `class`("C") { } }.toString(),
            file("com.example", "A") { klass("C") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`(SEALED.toModifiers(), "C") { } }.toString(),
            file("com.example", "A") { klass(SEALED.toModifiers(), "C") { } }.toString(),
        )
    }

    // --- ADR 0001 innermost-wins ------------------------------------------------------------

    /**
     * ADR 0001: one declaration on the sealed `Scope` supertype, dispatching on the runtime
     * scope. `class` is the clearest case — the same call is a top-level class in a file and a
     * nested class in a type. Asserted on rendered output so the *placement* is checked, not
     * merely that some builder was called. The third level, a local class in a block, reaches
     * its own branch — see the two tests below.
     */
    @Test
    fun `the same class call is top-level or nested depending on the innermost scope`() {
        val rendered = file("com.example", "Api") {
            `class`("TopLevel") {
                `class`("Nested") { }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            public class TopLevel {
              public class Nested
            }

            """.trimIndent(),
            rendered,
        )

        val block = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> { with(block) { `class`("Local") { } } }
        assertEquals(
            "A local class cannot be rendered: KotlinPoet 2.3.0 emits an explicit visibility " +
                "modifier on every type, and Kotlin allows none on a local class. Declare it at " +
                "file or type level.",
            thrown.message,
        )
        assertEquals("", block.builder.build().toString(), "nothing partial is left in the block")
    }

    /**
     * Canary for the limitation the block branch guards against, so the guard cannot outlive its
     * reason. A local class is valid Kotlin, but `CodeBlock.of("%L", spec)` renders every
     * `TypeSpec` with an explicit visibility keyword, and Kotlin allows none on a local class.
     *
     * When a KotlinPoet upgrade makes the rendered source compile, this test fails — that is the
     * signal to drop `localClassIsUnrenderable` and emit for real.
     */
    @Test
    fun `local class rendering is still blocked by KotlinPoet`() {
        val spec = typeSpec(name = "Local") { constructorParam(VAL, "name", STRING) }
        val rendered = file("com.example", "Api") {
            +FunSpec.builder("run")
                .addCode("%L", spec)
                .addStatement("println(Local(%S).name)", "x")
                .build()
        }.toString()

        // Checked before the exact-text comparison below: on an upstream KotlinPoet fix, the
        // exact-text comparison would fail first (different rendered text) and this hint would
        // never print, so the actionable message has to sit on the assertion that fires first.
        assertTrue(
            rendered.contains("public class Local"),
            "KotlinPoet can now render a local class — delete the guard in declareType",
        )
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun run() {
              public class Local(
                public val name: String,
              )
              println(Local("x").name)
            }

            """.trimIndent(),
            rendered,
        )
        val result = compile(rendered)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertEquals(
            true,
            result.messages.contains("Modifier 'public' is not applicable to 'local class'"),
            result.messages,
        )
    }

    /**
     * The *runtime* backstop behind Task 20's shadow member.
     *
     * The receiver is deliberately typed `Scope`, not `BlockScope`: written the obvious way this
     * call no longer compiles, because `Shadows.kt` puts an `@Deprecated(ERROR)` extension on
     * `BlockScope` in front of it — `ShadowsTest` asserts exactly that. Widening the static type
     * takes the extension out of the candidate set and reaches the `declareType` branch instead,
     * which is the path a caller holding a `Scope`-typed value still has, and the reason the
     * `check` stays even though no direct caller is expected to hit it.
     */
    @Test
    fun `a local object is rejected, naming the construct`() {
        val block: Scope = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> {
            with(block) { `object`("Nope") { } }
        }
        assertEquals(
            "A local named object is not valid Kotlin. Declare it at file or type level.",
            thrown.message,
        )
    }

    /** The runtime backstop for `interface`; see the note on the `object` test above. */
    @Test
    fun `a local interface is rejected, naming the construct`() {
        val block: Scope = BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "fun f"), mutableListOf())
        val thrown = assertFailsWith<IllegalStateException> {
            with(block) { `interface`(SEALED.toModifiers(), "Nope") { } }
        }
        assertEquals(
            "A local interface is not valid Kotlin. Declare it at file or type level.",
            thrown.message,
        )
    }

    // --- Important 2: duplicate type names ------------------------------------------------

    /**
     * KotlinPoet does not deduplicate types on its own: `TypeSpec.Builder.addType` just
     * appends, and `FileSpec` has no check, so two types named the same in one file would
     * render as two `public class A` — invalid Kotlin, no error. This asserts `declareType`
     * catches it before either type is emitted.
     */
    @Test
    fun `declaring two types with the same name in one file is rejected`() {
        val thrown = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `class`("A") { }
                `class`("A") { }
            }
        }
        assertEquals("A class named \"A\" is already declared in this scope.", thrown.message)
    }

    @Test
    fun `declaring two types with the same name in one type is rejected`() {
        val thrown = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `class`("Outer") {
                    `class`("A") { }
                    `object`("A") { }
                }
            }
        }
        assertEquals("A named object named \"A\" is already declared in this scope.", thrown.message)
    }

    /** The check is per-container: a name reused in an unrelated or nested container is fine. */
    @Test
    fun `the same type name in different containers does not collide`() {
        val rendered = file("com.example", "Api") {
            `class`("A") { }
            `class`("B") {
                `class`("A") { }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            public class A

            public class B {
              public class A
            }

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    @Test
    fun `object and interface render at file and type level`() {
        val rendered = file("com.example", "Api") {
            `object`("Registry") {
                `interface`("Listener") { }
            }
            `interface`(INTERNAL.toModifiers(), "Service") {
                `object`("Default") { }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            public object Registry {
              public interface Listener
            }

            internal interface Service {
              public object Default
            }

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    // --- constructorParam -------------------------------------------------------------------

    @Test
    fun `a null kind is a plain parameter, VAR is a mutable property`() {
        val rendered = file("com.example", "User") {
            `class`("User") {
                constructorParam(name = "seed", type = STRING)
                constructorParam(VAR, "email", STRING)
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class User(
              seed: String,
              public var email: String,
            )

            """.trimIndent(),
            rendered,
        )
    }

    /**
     * D19: the plan types `constructorParam`'s `kind` as `KModifier?` and validates at runtime
     * that it is `VAL`, `VAR` or null. KotlinPoet 2.3.0's `KModifier` has no `VAL`/`VAR`
     * entries, so the validated set is the *whole* of [ParamKind] plus null — every state is
     * legal and the runtime check is unwritable. This asserts the three states behave, which
     * is what the deleted check was protecting.
     */
    @Test
    fun `ParamKind has exactly the two property kinds, and null is the third state`() {
        assertEquals(listOf(VAL, VAR), ParamKind.entries.toList())
        val rendered = file("com.example", "User") {
            `class`("User") {
                constructorParam(null, "plain", STRING)
                constructorParam(VAL, "readOnly", STRING)
                constructorParam(VAR, "mutable", STRING)
            }
        }.toString()
        assertTrue(rendered.contains("  plain: String,"), "null kind should be a plain parameter: $rendered")
        assertTrue(
            rendered.contains("  public val readOnly: String,"),
            "VAL should add a read-only property: $rendered",
        )
        assertTrue(
            rendered.contains("  public var mutable: String,"),
            "VAR should add a mutable property: $rendered",
        )
    }

    @Test
    fun `constructorParam annotates the parameter`() {
        val rendered = file("com.example", "User") {
            `class`("User") {
                constructorParam(VAL, annotation<Email>(), "email", STRING)
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String
            import site.asm0dey.poetdsl.Email

            public class User(
              @Email
              public val email: String,
            )

            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun `ctorParam is an alias`() {
        assertEquals(
            file("com.example", "A") { `class`("C") { constructorParam(VAL, "x", STRING) } }.toString(),
            file("com.example", "A") { `class`("C") { ctorParam(VAL, "x", STRING) } }.toString(),
        )
        assertEquals(
            file("com.example", "A") {
                `class`("C") { constructorParam(VAL, annotation<Email>(), "x", STRING) }
            }.toString(),
            file("com.example", "A") {
                `class`("C") { ctorParam(VAL, annotation<Email>(), "x", STRING) }
            }.toString(),
        )
    }

    /**
     * D21: two constructor parameters named the same is a compile error in Kotlin with no valid
     * output to preserve, so it is rejected outright rather than renamed to `name2` — unlike a
     * parameter colliding with a *different* construct, which still uniquifies (see
     * `a member name colliding with a constructor parameter is uniquified, never shadowed` in
     * BindingsTest).
     */
    @Test
    fun `a duplicate constructor parameter name is rejected, naming the construct`() {
        val thrown = assertFailsWith<IllegalStateException> {
            file("com.example", "User") {
                `class`("User") {
                    constructorParam(VAL, "name", STRING)
                    constructorParam(VAL, "name", STRING)
                }
            }
        }
        assertEquals(
            "A constructor parameter named \"name\" is already declared in this scope.",
            thrown.message,
        )
    }

    /**
     * D21 rejects a duplicate *within* one constructor (above), but the check is per-container:
     * two unrelated sibling classes each get their own `TypeScope` and constructor-parameter
     * registry, so the same parameter name in both is not a collision — both render with the
     * requested name.
     */
    @Test
    fun `the same constructor parameter name in two unrelated sibling classes does not collide`() {
        val rendered = file("com.example", "Api") {
            `class`("A") { constructorParam(VAL, "id", STRING) }
            `class`("B") { constructorParam(VAL, "id", STRING) }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class A(
              public val id: String,
            )

            public class B(
              public val id: String,
            )

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    /** `%T` must reach KotlinPoet unrendered, or the import cannot be emitted. */
    @Test
    fun `a constructor parameter type keeps its placeholder and becomes an import`() {
        val rendered = file("com.example", "User") {
            `class`(DATA.toModifiers(), "User") {
                constructorParam(VAL, "createdAt", ClassName("java.time", "Instant"))
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import java.time.Instant

            public data class User(
              public val createdAt: Instant,
            )

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    // --- D30: what a plain primary-constructor parameter is visible to ------------------------

    /**
     * Deviation D30. A **plain** primary-constructor parameter — no `val`/`var`, so no property —
     * is unresolvable in a member body, so a member parameter of the same name shadows nothing and
     * must not be renamed against it. A `val`/`var` parameter *is* a property, is visible in every
     * member body, and keeps renaming: that is ADR 0009's cross-construct case, and the invariant
     * that nothing is ever qualified with `this.` depends on it.
     *
     * Both halves in one class, so the split is pinned by the same rendered output.
     */
    @Test
    fun `a member parameter renames against a property parameter but not a plain one`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG), param(null, "seed", INT)) { _, _ ->
                `fun`("withId", param("id", LONG)) { p -> +call("println", p) }
                `fun`("withSeed", param("seed", INT)) { p -> +call("println", p) }
            }
        }.toString()
        assertTrue("public fun withId(id2: Long)" in rendered, rendered)
        assertTrue("public fun withSeed(seed: Int)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** The measurement D30 rests on: a plain parameter really is unresolvable in a member body. */
    @Test
    fun `kotlinc cannot resolve a plain constructor parameter in a member body`() {
        val result = compile("class A(x: Int) { fun f() = x }")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("Unresolved reference 'x'" in result.messages, result.messages)
    }

    /**
     * The use side of the same split, answered by ADR 0008 with no change to `checkOwned`: a plain
     * parameter's handle belongs to the initializer level nested inside the type, which encloses an
     * `init { }` block but not a member body. So the DSL refuses to render the unresolved name
     * rather than emitting Kotlin that does not compile (Global Constraint 26).
     */
    @Test
    fun `a plain parameter handle is rejected in a member body`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "User") {
                `class`("User", param(null, "seed", INT)) { seed ->
                    `fun`("f") { +call("println", seed) }
                }
            }
        }
        assertEquals(
            "Handle from scope 'the primary constructor's plain parameters (use param(VAL, …) to " +
                "reach it from a member body)' does not enclose the current scope 'fun(f)'.",
            failure.message,
        )
    }

    /** A `val` parameter is a property, so the same use in the same place is accepted. */
    @Test
    fun `a property parameter handle is accepted in a member body`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG)) { id ->
                `fun`("f") { +call("println", id) }
            }
        }.toString()
        assertTrue("println(id)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /**
     * A property still steps over a plain parameter, in both writing orders: the two names would be
     * distinct declarations, but every mention of the property inside an initializer would resolve
     * to the parameter instead.
     */
    @Test
    fun `a property and a plain parameter still uniquify against each other`() {
        val paramFirst = file("com.example", "A") {
            `class`("A", param(null, "seed", INT)) { _ -> `val`("seed", INT, init = 1.lit) }
        }.toString()
        assertTrue("public val seed2: Int = 1" in paramFirst, paramFirst)

        val propertyFirst = file("com.example", "B") {
            `class`("B") {
                `val`("seed", INT, init = 1.lit)
                constructorParam(null, "seed", INT)
            }
        }.toString()
        assertTrue("seed2: Int," in propertyFirst, propertyFirst)
        assertCompiles(paramFirst)
        assertCompiles(propertyFirst)
    }

    // --- typeSpec ---------------------------------------------------------------------------

    @Test
    fun `typeSpec takes modifiers and a body`() {
        val spec = typeSpec(DATA.toModifiers(), "Point") {
            constructorParam(VAL, "x", STRING)
        }
        assertEquals(
            """
            public data class Point(
              public val x: kotlin.String,
            )
            """.trimIndent() + "\n",
            spec.toString(),
        )
    }
}
