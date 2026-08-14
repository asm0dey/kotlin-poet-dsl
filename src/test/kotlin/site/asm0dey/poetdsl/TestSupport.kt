package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.OutputStream
import kotlin.test.assertEquals

/**
 * Compiles [source] as a standalone Kotlin file, with the DSL itself on the classpath and
 * compiler chatter discarded. **The only kctfork invocation in this file** — every other entry
 * point below differs from its neighbours in how the source text is assembled or which flag is
 * set, never in how the compiler is configured, so the [jvmTarget] rationale below has exactly one
 * place to live and the next harness change has exactly one place to land.
 *
 * @param multiplatform passes `-Xmulti-platform`; see [compileMultiplatform], the only caller that
 *   sets it, for what that buys and what it does not.
 */
@OptIn(ExperimentalCompilerApi::class)
private fun compileKotlin(
    fileName: String,
    source: String,
    multiplatform: Boolean = false,
): JvmCompilationResult = KotlinCompilation().apply {
    sources = listOf(SourceFile.kotlin(fileName, source))
    inheritClassPath = true
    // kctfork defaults to JVM target 1.8, while this module is built against 17. That only shows
    // up when a snippet *inlines* one of the DSL's `inline` functions — `ann<T>()` and the rest —
    // which fails with "Cannot inline bytecode built with JVM target 17 into bytecode that is
    // being built with JVM target 1.8". A consumer compiles against the same toolchain, so this is
    // what a snippet should have been compiled with all along.
    jvmTarget = "17"
    this.multiplatform = multiplatform
    messageOutputStream = OutputStream.nullOutputStream()
}.compile()

/** Compiles already-rendered Kotlin source (e.g. a KotlinPoet `FileSpec`'s output) as-is. */
@OptIn(ExperimentalCompilerApi::class)
internal fun compile(source: String): JvmCompilationResult = compileKotlin("Generated.kt", source)

/**
 * Compiles rendered Kotlin with `-Xmulti-platform`, so that a single-file `expect` declaration is
 * judged as one rather than as a project-layout mistake.
 *
 * **The exit code is never OK here and is not what to assert on.** A single compilation unit has no
 * platform source set to put the `actual` in, so the result always carries *expected E has no actual
 * declaration in module &lt;main&gt; for JVM*. What *is* decidable, and what the callers of this
 * function assert, is whether a **different** diagnostic appears in [JvmCompilationResult.messages]
 * — the compiler runs the expect-specific rules (*expected property cannot have an initializer*) and
 * the ordinary ones (*property must be initialized or be abstract*) before it gets that far, so their
 * presence and absence is a measurement rather than an inference.
 *
 * **The flag is not what makes that measurable, and this KDoc used to claim it was.** It said that
 * without `-Xmulti-platform` "no later rule is ever reached", which is false — measured on kotlinc
 * 2.4.10, `expect class E { class N { val x: Int = 1 } }` reports *expected property cannot have an
 * initializer* with the flag and **also** without it, where that error simply arrives alongside
 * *'expect' and 'actual' declarations can be used only in multiplatform projects* instead of
 * alongside the missing-`actual` one. All the flag does is swap which unavoidable error accompanies
 * the interesting ones. Pinned by `the expect diagnostics do not depend on the multiplatform flag`,
 * which runs the same source through [compile]; the D36 measurement is if anything stronger for
 * holding under both settings. Kept because it states the intent and drops a misleading error line.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun compileMultiplatform(source: String): JvmCompilationResult =
    compileKotlin("Generated.kt", source, multiplatform = true)

/** Compiles [source] and asserts it succeeds, printing the compiler's messages if it does not. */
@OptIn(ExperimentalCompilerApi::class)
internal fun assertCompiles(source: String) {
    val result = compile(source)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "$source\n${result.messages}")
}

/** Compiles a snippet written against the DSL, importing the DSL's package plus [extraImports]. */
@OptIn(ExperimentalCompilerApi::class)
internal fun compileDsl(body: String, extraImports: List<String> = emptyList()): JvmCompilationResult {
    val imports = (listOf("site.asm0dey.poetdsl.*") + extraImports).joinToString("\n") { "import $it" }
    return compileKotlin("Snippet.kt", "$imports\n\n$body\n")
}

/** Renders a detached block, for golden assertions on statement output. */
internal fun renderBlock(body: BlockScope.() -> Unit): String = stmts(body).code.toString()

/**
 * An *attached* block — the shape a `fun` body has. Ownership checks only fire here: a detached
 * root legitimately accepts foreign handles, which is what makes `stmts { }` usable for building
 * fragments up front (deviation D6).
 */
internal fun attachedBlock(label: String = "fun f"): BlockScope =
    BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, label), mutableListOf())

/** Renders the value a builder returns, without emitting it. */
internal fun renderValue(body: BlockScope.() -> Expr): String =
    BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "block"), mutableListOf())
        .body()
        .toString()

/**
 * Stands in for the control-flow builders of Tasks 15-18: closing it ends the block that
 * [openFlow] opened. Counts its closes, so a test can prove the flush happens exactly once.
 */
internal class CountingFlow(private val scope: BlockScope) : PendingFlow {
    var closes: Int = 0
        private set

    override fun close() {
        closes++
        scope.builder.endControlFlow()
    }
}

/**
 * Opens a control-flow block, fills it with [body], and leaves it open — the exact shape
 * Tasks 15-18's `if`/`while`/`for` builders will have, so that a following `else` can attach
 * and anything else forces the flush.
 *
 * Flushes any flow already pending *before* opening this one: two of these in a row must
 * render balanced, not overwrite `pending` and drop the first flow's `close()`.
 */
internal fun BlockScope.openFlow(control: String, body: BlockScope.() -> Unit): CountingFlow {
    flushPending()
    builder.beginControlFlow(control)
    runNested(control, body = body)
    return CountingFlow(this).also { pending = it }
}
