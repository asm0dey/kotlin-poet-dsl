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
 * compiler chatter discarded. The one kctfork invocation shared by [compile] and [compileDsl] —
 * they differed only in how the source text was assembled, not in how it was compiled.
 */
@OptIn(ExperimentalCompilerApi::class)
private fun compileKotlin(fileName: String, source: String): JvmCompilationResult = KotlinCompilation().apply {
    sources = listOf(SourceFile.kotlin(fileName, source))
    inheritClassPath = true
    messageOutputStream = OutputStream.nullOutputStream()
}.compile()

/** Compiles already-rendered Kotlin source (e.g. a KotlinPoet `FileSpec`'s output) as-is. */
@OptIn(ExperimentalCompilerApi::class)
internal fun compile(source: String): JvmCompilationResult = compileKotlin("Generated.kt", source)

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
