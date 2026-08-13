package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import kotlin.reflect.KFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A member function and a member property, for the reference kinds reflection cannot place. */
class RefsSample(val size: Int) {
    fun grow(): Int = size + 1

    companion object {
        fun of(n: Int): RefsSample = RefsSample(n)
    }
}

object RefsHolder {
    fun make(): Int = 1
}

class RefsTest {
    // --- name-source forms: the reference contributes a name, the receiver qualifies it --------

    @Test
    fun `member function reference contributes a bare name`() {
        assertEquals("x.isNotEmpty()", expression("x").call(String::isNotEmpty).toString())
    }

    @Test
    fun `a true member function reference contributes a bare name`() {
        assertEquals("x.grow()", expression("x").call(RefsSample::grow).toString())
    }

    @Test
    fun `property reference contributes a bare name`() {
        assertEquals("s.length", expression("s").prop(String::length).toString())
    }

    @Test
    fun `a declared member property reference contributes a bare name`() {
        assertEquals("s.size", expression("s").prop(RefsSample::size).toString())
    }

    // --- top-level functions: %M, so the import resolves ---------------------------------------

    @Test
    fun `top level function reference resolves package and import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f").addStatement("%L", call(::topLevelHelper, 1.lit).code).build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import site.asm0dey.poetdsl.topLevelHelper

            public fun f() {
              topLevelHelper(1)
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `asMemberName resolves the package, not the declaring class name`() {
        // A stdlib extension is declared in a multifile *part* (kotlin.text.StringsKt__StringsKt);
        // only its package is the import, which is why the package is read and the name is not.
        assertEquals(MemberName("kotlin.text", "isNotEmpty"), String::isNotEmpty.asMemberName())
        assertEquals(MemberName("site.asm0dey.poetdsl", "topLevelHelper"), ::topLevelHelper.asMemberName())
    }

    @Test
    fun `the lambda form keeps the reference as a resolved import`() {
        val block = attachedBlock()
        val value = with(block) { call(::topLevelHelper, 1.lit) { +2.lit } }
        // `addCode`, not `addStatement`: a multi-line value's continuation lines pick up two extra
        // statement indents otherwise — the reason `emitCode` adds rather than addStatements.
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(FunSpec.builder("f").addCode("%L\n", value.code).build())
            .build()
        assertEquals(
            """
            package com.example

            import site.asm0dey.poetdsl.topLevelHelper

            public fun f() {
              topLevelHelper(1) {
                2
              }
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `the lambda form carries what its body captured`() {
        // An *attached* block accepts the handle and records nothing in `referenced`, so the only
        // trace of the capture is the lambda value's own `usedScopes` — which the call must
        // propagate, or a smuggled value is spliced anywhere unchecked (ADR 0008, layer 2).
        val a = attachedBlock("fun a")
        val x = Expr(CodeBlock.of("x"), name = "x", scope = a.id)
        val smuggled = with(a) { call(::topLevelHelper) { +x.call("inc") } }
        assertEquals(setOf(a.id), smuggled.usedScopes, "the lambda body's captures must travel")

        val failure = assertFailsWith<IllegalStateException> { with(attachedBlock("fun b")) { +smuggled } }
        assertEquals(
            "Handle from scope 'fun a' does not enclose the current scope 'fun b'.",
            failure.message,
        )
    }

    // --- everything reflection cannot place, failing loudly ------------------------------------

    @Test
    fun `an unresolvable reference fails with a named error`() {
        // A local function: `javaMethod` throws KotlinReflectionInternalError rather than
        // returning null, so this is the guarded path, not the null path.
        fun localHelper(): Int = 1
        val failure = assertFailsWith<IllegalStateException> { call(::localHelper) }
        assertTrue(failure.message!!.contains("'localHelper'"), failure.message!!)
        assertEquals(true, failure.message!!.endsWith("Use member(\"pkg\", \"name\") instead."))
    }

    @Test
    fun `a constructor reference fails with a named error`() {
        val failure = assertFailsWith<IllegalStateException> { call(::RefsSample) }
        assertTrue(failure.message!!.contains("'<init>'"), failure.message!!)
        assertEquals(true, failure.message!!.endsWith("Use member(\"pkg\", \"name\") instead."))
    }

    @Test
    fun `a member function is rejected instead of resolving to its package`() {
        val failure = assertFailsWith<IllegalStateException> { call(RefsSample::grow) }
        assertEquals(
            "Cannot resolve a MemberName for 'grow': it is declared in " +
                "'site.asm0dey.poetdsl.RefsSample', not at file level, so its package is not its " +
                "import. Use member(reference<Owner>(), \"grow\") with the owning class instead.",
            failure.message,
        )
    }

    @Test
    fun `companion, object and Java members are rejected too`() {
        // None of these has an instance parameter, so only the declaring class's Kotlin metadata
        // kind separates them from a top-level function.
        assertTrue(
            assertFailsWith<IllegalStateException> { call(RefsSample.Companion::of) }
                .message!!.contains("RefsSample\$Companion"),
        )
        assertTrue(
            assertFailsWith<IllegalStateException> { call(RefsHolder::make) }
                .message!!.contains("site.asm0dey.poetdsl.RefsHolder"),
        )
        assertTrue(
            assertFailsWith<IllegalStateException> { call(java.io.File::mkdir) }
                .message!!.contains("java.io.File"),
        )
    }

    @Test
    fun `an inline reified function resolves only through an explicitly typed reference`() {
        // `call(::emptyArray)` does not compile at all — `T` has nothing to be inferred from in
        // `KFunction<*>` ("Cannot infer type for type parameter 'T'"). Bound to a typed reference
        // it resolves, but the type argument is erased and the generated call carries none.
        val ref: KFunction<Array<Int>> = ::emptyArray
        assertEquals(MemberName("kotlin", "emptyArray"), ref.asMemberName())
        // Fully qualified because this CodeBlock is detached: there is no FileSpec to own the
        // import yet. In a file it renders as `emptyArray()` plus `import kotlin.emptyArray`.
        assertEquals("kotlin.emptyArray()", call(ref).toString())
    }

    @Test
    fun `an extension function resolves but cannot be called bare`() {
        assertEquals(MemberName("kotlin.text", "isNotEmpty"), String::isNotEmpty.asMemberName())
        val failure = assertFailsWith<IllegalStateException> { call(String::isNotEmpty) }
        assertEquals(
            "'isNotEmpty' is an extension function: a bare call cannot render its receiver. " +
                "Use receiver.call(\"isNotEmpty\") instead.",
            failure.message,
        )
    }
}

fun topLevelHelper(n: Int): Int = n
