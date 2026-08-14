package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

annotation class Email
annotation class SerialName(val value: String)

class AnnotationsTest {
    @Test
    fun `marker annotation`() {
        assertEquals("@site.asm0dey.poetdsl.Email", annotation<Email>().list.single().toString())
    }

    @Test
    fun `named arguments keep Expr placeholders`() {
        assertEquals(
            """@site.asm0dey.poetdsl.SerialName(value = "user_name")""",
            annotation<SerialName>("value" to "user_name".lit).list.single().toString(),
        )
    }

    @Test
    fun `use site target renders`() {
        assertEquals(
            "@set:site.asm0dey.poetdsl.Email",
            annotation<Email>(UseSiteTarget.SET).list.single().toString(),
        )
    }

    /**
     * Closes spec open question 1: `AnnotationSpec.UseSiteTarget.ALL` (the `@all:` meta-target
     * added in Kotlin 2.2) exists natively in KotlinPoet 2.3.0. It is marked
     * `@ExperimentalKotlinPoetApi` by KotlinPoet itself, so the real, documented opt-in is used
     * here rather than a shim — no workaround needed to prove it exists and renders correctly.
     */
    @OptIn(ExperimentalKotlinPoetApi::class)
    @Test
    fun `the all meta target is available without a shim`() {
        assertEquals(
            "@all:site.asm0dey.poetdsl.Email",
            annotation<Email>(UseSiteTarget.ALL).list.single().toString(),
        )
    }

    @Test
    fun `annotations combine with plus`() {
        val combined = annotation<Email>(UseSiteTarget.SET) + annotation<SerialName>("value" to "x".lit)
        assertEquals(2, combined.list.size)
    }

    @Test
    fun `runtime known annotation type`() {
        assertEquals(
            """@com.example.Generated("gen")""",
            annotation(ClassName("com.example", "Generated"), args = arrayOf("gen".lit)).list.single().toString(),
        )
    }

    @Test
    fun `file level annotation defaults to the FILE target`() {
        val out = file("com.example", "Api") {
            annotate<SerialName>("value" to "ApiKt".lit)
        }.toString()
        assertEquals(true, out.startsWith("@file:"))
    }

    /**
     * A zero-argument call to each pair of overloads must resolve unambiguously (D2). If
     * either pair were still ambiguous for a zero-argument call, this file would fail to
     * compile — the calls below only need to compile and run, no assertion recreates that.
     */
    @Test
    fun `zero argument calls resolve unambiguously per D2`() {
        assertEquals(1, annotation<Email>().list.size)
        assertEquals(1, annotation<Email>(UseSiteTarget.SET).list.size)
        assertEquals(1, ann<Email>().list.size)
        assertEquals(1, ann<Email>(UseSiteTarget.SET).list.size)
    }

    /**
     * `%T` must reach KotlinPoet unrendered from inside an annotation *argument*, not just
     * from the annotation's own type, or the import for a type referenced only inside a
     * member value would never resolve. A string match on `toString()` cannot tell "the
     * import is missing" apart from "the import happens not to be needed here" — only an
     * end-to-end compile with kctfork proves the import resolved and the code is valid Kotlin.
     */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `a type placeholder inside an annotation argument survives and its import resolves`() {
        val labels = ClassName("com.example.util", "Labels")
        val described = annotation(
            ClassName("com.example.util", "Described"),
            args = arrayOf(expression("%T.NAME", labels)),
        )

        val rendered = file("com.example.app", "Api") {
            val type = TypeScope(TypeSpec.classBuilder("Foo"), names.child(), id.child("class Foo"))
            with(type) { addAnnotation(described.list.single()) }
            +type.finish()
        }.toString()

        assertTrue(rendered.contains("import com.example.util.Described"), rendered)
        assertTrue(rendered.contains("import com.example.util.Labels"), rendered)
        assertTrue(rendered.contains("@Described(Labels.NAME)"), rendered)

        val util = SourceFile.kotlin(
            "Util.kt",
            """
            package com.example.util

            annotation class Described(val value: String)

            object Labels {
                const val NAME: String = "tag"
            }
            """.trimIndent(),
        )

        val result = KotlinCompilation().apply {
            sources = listOf(util, SourceFile.kotlin("Api.kt", rendered))
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    /**
     * A Kotlin annotation argument must be a compile-time constant, so a handle — which names a
     * runtime binding — can never legitimately appear in one. Before this guard, `@Ann(x)` rendered
     * happily and only `kotlinc` complained, at whatever point the caller got round to compiling
     * the output.
     */
    @Test
    fun `a handle is rejected as an annotation argument`() {
        lateinit var handle: Expr
        stmts { handle = `val`("userName", init = "u".lit) }

        val failure = assertFailsWith<IllegalStateException> {
            annotation<SerialName>(null, handle)
        }
        assertEquals(
            "annotation: 'userName' is a handle, but a Kotlin annotation argument must be a " +
                "compile-time constant. Use a literal, or name the constant with " +
                "member(\"pkg\", \"NAME\").expression() — a handle is rejected even for a " +
                "`const val`, because an Expr cannot tell one from a local.",
            failure.message,
        )
    }

    /** The same guard on the named-argument overload, the trailing-lambda form and the alias. */
    @Test
    fun `every annotation entry point rejects a handle`() {
        lateinit var handle: Expr
        stmts { handle = `val`("x", init = 1.lit) }
        val type = TypeScope(TypeSpec.classBuilder("Holder"), NameScope(null), ScopeId(null, "type"))

        assertFailsWith<IllegalStateException> { annotation<SerialName>("value" to handle) }
        assertFailsWith<IllegalStateException> { ann<SerialName>(null, handle) }
        assertFailsWith<IllegalStateException> { ann<SerialName>("value" to handle) }
        assertFailsWith<IllegalStateException> { annotation(ClassName("p", "A"), null, handle) }
        assertFailsWith<IllegalStateException> { with(type) { annotate<SerialName>(null, handle) } }
        assertFailsWith<IllegalStateException> { with(type) { annotate<SerialName>("value" to handle) } }
        // D27's two new entry points inherit the guard through `buildAnnotation`, like every other.
        assertFailsWith<IllegalStateException> { annotation(ClassName("p", "A"), "value" to handle) }
        assertFailsWith<IllegalStateException> { ann(ClassName("p", "A"), "value" to handle) }
        // And so does an argument that merely *contains* a handle, joined by D28's helper.
        assertFailsWith<IllegalStateException> {
            annotation(ClassName("p", "A"), "value" to arrayLiteral(listOf(handle)))
        }
    }

    /**
     * Deviation D27: the [ClassName] form takes named arguments too. In real codegen the annotation
     * type is usually not on the generator's classpath, which is exactly when the reified form —
     * the only one that had them — is unavailable.
     */
    @Test
    fun `the ClassName form takes named arguments`() {
        val serialName = ClassName("kotlinx.serialization", "SerialName")
        assertEquals(
            """@kotlinx.serialization.SerialName(value = "user_name")""",
            annotation(serialName, "value" to "user_name".lit).list.single().toString(),
        )
        assertEquals(
            """@kotlinx.serialization.SerialName(value = "user_name")""",
            ann(serialName, "value" to "user_name".lit).list.single().toString(),
        )
    }

    /**
     * `target` is named-only and comes last, the ordering D2 corrected: Kotlin binds positional
     * arguments by declared order regardless of defaults, so a leading `target` would swallow the
     * first pair. Several pairs render in the order written.
     */
    @Test
    fun `the ClassName named form takes a use-site target and keeps argument order`() {
        val column = ClassName("jakarta.persistence", "Column")
        assertEquals(
            "@get:jakarta.persistence.Column(name = \"user_name\", nullable = false)",
            annotation(
                column,
                "name" to "user_name".lit,
                "nullable" to false.lit,
                target = UseSiteTarget.GET,
            ).list.single().toString(),
        )
    }

    /**
     * The zero-argument call still resolves to the positional overload rather than failing overload
     * resolution — the reason `first` is a required parameter and not part of the vararg (D2).
     */
    @Test
    fun `a zero-argument ClassName annotation still resolves`() {
        assertEquals("@p.A", annotation(ClassName("p", "A")).list.single().toString())
    }

    /**
     * The shape D27 and D28 were both written for, compiled end to end: a JPA-style nested
     * annotation whose type is only a [ClassName], with named arguments and an array of nested
     * annotations built from a computed list.
     */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `a nested annotation built from ClassNames with named arguments and an array compiles`() {
        val neg = ClassName("com.example.jpa", "NamedEntityGraph")
        val nan = ClassName("com.example.jpa", "NamedAttributeNode")
        val nodes = listOf("orders", "address").map { expression("%T(%L)", nan, it.lit) }
        val rendered = file("com.example.app", "Api") {
            `class`(
                annotation(neg, "name" to "User.detail".lit, "attributeNodes" to arrayLiteral(nodes)),
                "User",
            ) { }
        }.toString()

        assertTrue(
            """@NamedEntityGraph(
    |  name = "User.detail",
    |  attributeNodes = [NamedAttributeNode("orders"), NamedAttributeNode("address")],
    |)""".trimMargin() in rendered,
            rendered,
        )
        assertTrue("import com.example.jpa.NamedAttributeNode" in rendered, rendered)

        val jpa = SourceFile.kotlin(
            "Jpa.kt",
            """
            package com.example.jpa

            annotation class NamedAttributeNode(val value: String)

            @Target(AnnotationTarget.CLASS)
            annotation class NamedEntityGraph(
                val name: String,
                val attributeNodes: Array<NamedAttributeNode> = [],
            )
            """.trimIndent(),
        )
        val result = KotlinCompilation().apply {
            sources = listOf(jpa, SourceFile.kotlin("Api.kt", rendered))
            inheritClassPath = true
            messageOutputStream = OutputStream.nullOutputStream()
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "$rendered\n${result.messages}")
    }

    /**
     * The guard is [Expr.usedScopes], so it also catches an argument merely *derived* from a
     * handle — and leaves the escape hatch alone, since a raw string carries no scope. That
     * asymmetry is the documented trade-off, not an accident: the check is sound (it never rejects
     * a constant), not complete.
     */
    @Test
    fun `the guard follows derived expressions but not raw strings`() {
        lateinit var handle: Expr
        stmts { handle = `val`("x", init = 1.lit) }

        assertFailsWith<IllegalStateException> { annotation<SerialName>(null, handle.prop("name")) }
        assertFailsWith<IllegalStateException> { annotation<SerialName>(null, expression("%L", handle)) }
        assertEquals(
            "@site.asm0dey.poetdsl.SerialName(NAME)",
            annotation<SerialName>(null, expression("NAME")).list.single().toString(),
        )
    }

    /**
     * The one false rejection the guard buys, pinned so it is a known cost rather than a surprise:
     * a `` const `val` `` declared through the DSL hands back a handle like any other binding, and
     * `@Ann(MAX)` referring to it would be perfectly legal Kotlin. Nothing in an [Expr] separates a
     * file-level `const` from a local, and [annotation] takes no scope in which to ask, so it is
     * rejected too. The workaround is one line and the message names it.
     */
    @Test
    fun `a const val handle is rejected too and member is the way round it`() {
        lateinit var max: Expr
        file("com.example", "Api") { max = `val`(KModifier.CONST, "MAX", INT, init = 5.lit) }

        assertFailsWith<IllegalStateException> { annotation<SerialName>(null, max) }
        assertEquals(
            "@site.asm0dey.poetdsl.SerialName(com.example.MAX)",
            annotation<SerialName>(null, member("com.example", "MAX").expression())
                .list.single().toString(),
        )
    }

    /**
     * A type-position annotation *with arguments* had no public route: [Annotations.list] is
     * `@PublishedApi internal`, so the spread the `param` KDoc showed
     * (`INT.annotated(*ann<SerialName>(…).list.toTypedArray())`) is unreachable from outside the
     * module, and KotlinPoet's own `TypeName.annotated` overloads take `AnnotationSpec`s or bare
     * `KClass`es — the latter covering the zero-argument case only. This extension is that route.
     */
    @Test
    fun `annotated takes an Annotations with arguments`() {
        val rendered = file("com.example", "Api") {
            `fun`("f", param("x", INT.annotated(ann<SerialName>("value" to "user_name".lit)))) { }
        }.toString()
        assertTrue("""x: @SerialName(value = "user_name") Int""" in rendered, rendered)
        // Several annotations at once, and the marker case the KClass overload already covered.
        assertEquals(
            "@site.asm0dey.poetdsl.Email @site.asm0dey.poetdsl.SerialName(value = \"n\") kotlin.Int",
            INT.annotated(annotation<Email>() + annotation<SerialName>("value" to "n".lit)).toString(),
        )
    }

    /**
     * The gap this closes, measured from where it matters: a *consumer* of the published artifact.
     * The KDoc's old spelling cannot compile there at all, and it must stay that way — the ruling is
     * that [Annotations.list] remains internal and the extension is the supported route.
     */
    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun `the internal list is unreachable from a consumer and the extension is not`() {
        val viaList = compileDsl(
            """
            annotation class Positive(val min: Int)

            fun p() = param("x", INT.annotated(*ann<Positive>("min" to 0.lit).list.toTypedArray()))
            """.trimIndent(),
            // KotlinPoet's own `annotated` is a top-level extension in `TypeNames.kt`, so the old
            // spelling needs that import as well — given here, so the only thing left to fail is
            // the access to `list`.
            extraImports = listOf("com.squareup.kotlinpoet.INT", "com.squareup.kotlinpoet.annotated"),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, viaList.exitCode, viaList.messages)
        assertTrue("it is internal in" in viaList.messages, viaList.messages)

        val viaExtension = compileDsl(
            """
            annotation class Positive(val min: Int)

            fun p() = param("x", INT.annotated(ann<Positive>("min" to 0.lit)))
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT"),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, viaExtension.exitCode, viaExtension.messages)
    }
}
