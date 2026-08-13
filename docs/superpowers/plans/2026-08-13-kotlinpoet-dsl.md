# KotlinPoet DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a publishable Kotlin library that wraps KotlinPoet in a DSL where generator code reads like the Kotlin it generates — files, types, members, and function bodies, with typed handles and `%T`/`%M` placeholders preserved.

**Architecture:** Three scope types (`FileScope` → `TypeScope` → `BlockScope`), each with its own `@DslMarker`. Scopes enter user code as **receiver lambdas** (`BlockScope.() -> Unit`), and every builder function declares the scope it needs as a **context parameter** (`context(b: BlockScope)`). The receiver satisfies the context argument. That split is what allows one declaration per construct (emitting form and pure `stmts { }` form are the same function) and user-written extensions like `context(b: BlockScope) fun Expr.orThrow(...)`. Scopes write directly into KotlinPoet builders; indentation and imports stay KotlinPoet's job.

**Tech Stack:** Kotlin 2.4.10 (JVM), Gradle 9.7.0, KotlinPoet 2.3.0, kotlin-reflect 2.4.10, kotlin-compile-testing fork `dev.zacsweers.kctfork:core:0.13.0`, binary-compatibility-validator 0.18.1, JUnit 5 via `kotlin("test")`.

## Global Constraints

- **Kotlin baseline 2.4.10.** Context parameters are Stable in 2.4.0 — no compiler flag needed. Do **not** add `-Xcontext-parameters` (that was the 2.2-era preview flag) and do **not** add `-Xexplicit-context-arguments` (that opts into a *different*, still-experimental feature: explicit context arguments at call sites).
- **KotlinPoet 2.3.0 is the only backend.** No own IR, no post-processing passes. Verified: `AnnotationSpec.UseSiteTarget.ALL` exists in 2.3.0, so no shim is needed (this closes spec open task 1).
- **Base package: `dev.asm0dey.poetdsl`.** Single Gradle module named `kotlin-poet-dsl`.
- `explicitApi()` is on. Every public declaration needs an explicit visibility modifier and explicit return type.
- **Dependencies:** `api("com.squareup:kotlinpoet:2.3.0")`, `implementation(kotlin("reflect"))`. No other runtime dependencies.
- **Naming convention, no exceptions:** full word is canonical, short form is the alias — `annotation`/`ann`, `member`/`mem`, `expression`/`expr`, `reference`/`ref`, `literal`/`lit`, `statement`/`stmt`, `constructorParam`/`ctorParam`. Where the natural full name is a Kotlin keyword it is backticked and canonical: `` `return` ``/`ret`, `` `break` ``/`brk`, `` `continue` ``/`cont`, `` `constructor` ``/`ctor`. Aliases are permanent public API, never deprecated.
- **Emission rule:** `Unit`-returning API emits; `Expr`-returning API does not. Single exception: `` `val` ``/`` `var` `` emit *and* return a handle.
- **Errors are build-time `IllegalStateException`s** naming the offending construct. Never emit partial or silently wrong output.
- **`prec` drives parenthesization.** `(a + b) * c` gets parens; `a + b * c` does not.
- Every task ends with a passing test run and a commit. Commit messages use Conventional Commits.

## File Structure

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
buildSrc/build.gradle.kts
buildSrc/src/main/kotlin/ArityGenerator.kt          — emits FunArity.kt, CtorArity.kt
api/kotlin-poet-dsl.api                             — binary-compatibility-validator dump
src/main/kotlin/dev/asm0dey/poetdsl/
  Markers.kt        — @FileDsl, @TypeDsl, @BlockDsl
  Prec.kt           — precedence constants + parenthesization helper
  Expr.kt           — Expr, Stmt
  Literals.kt       — .literal/.lit, nullLiteral/nul, reference/ref, member/mem, expression/expr
  Operators.kt      — arithmetic, comparison, logical, elvis
  Names.kt          — ScopeId, NameScope, singularize
  BlockScope.kt     — BlockScope, emission, stmts { }, ownership check
  Bindings.kt       — `val`, `var`, assign, compound assignment
  Calls.kt          — call, prop, safeCall, safeProp
  Lambdas.kt        — lambda arities 0–8, implicit `it`
  Refs.kt           — KFunction/KProperty → MemberName / bare name
  ControlFlow.kt    — for, while, doWhile, if-chain, when, try
  Modifiers.kt      — Modifiers value class, KModifier.plus
  Annotations.kt    — Annotations value class, Annotatable, annotation/ann
  FileScope.kt      — file { }, top-level emission
  TypeScope.kt      — class/object/interface, properties, constructorParam
  Declarations.kt   — funSpec/typeSpec/propertySpec detached builders, param(), return inference
  FunArity.kt       — GENERATED (build/generated/source/dsl)
  CtorArity.kt      — GENERATED
src/test/kotlin/dev/asm0dey/poetdsl/
  TestSupport.kt    — render helpers for golden tests
  <one golden test file per task>
docs/spikes/                                        — spike findings (Tasks 2 and 10)
README.md
```

Each source file owns one responsibility and stays small enough to read whole. Generated arity files are the only large ones, and nobody reads those.

---

### Task 1: Project skeleton and toolchain smoke test

**Files:**
- Create: `settings.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/ToolchainTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a Gradle build where `./gradlew test` runs Kotlin 2.4.10 JVM tests with KotlinPoet 2.3.0 on the compile classpath and `explicitApi()` enforced.

- [ ] **Step 1: Create the Gradle wrapper**

Run: `gradle wrapper --gradle-version 9.7.0`

If no `gradle` is on PATH, download the wrapper jar from an existing project or run
`curl -L https://services.gradle.org/distributions/gradle-9.7.0-bin.zip -o /tmp/g.zip && unzip -q /tmp/g.zip -d /tmp/g && /tmp/g/gradle-9.7.0/bin/gradle wrapper --gradle-version 9.7.0`.

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar` exist.

- [ ] **Step 2: Write the version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.10"
kotlinpoet = "2.3.0"
kctfork = "0.13.0"
bcv = "0.18.1"

[libraries]
kotlinpoet = { module = "com.squareup:kotlinpoet", version.ref = "kotlinpoet" }
kctfork-core = { module = "dev.zacsweers.kctfork:core", version.ref = "kctfork" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
bcv = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "bcv" }
```

- [ ] **Step 3: Write the settings and build scripts**

`settings.gradle.kts`:

```kotlin
rootProject.name = "kotlin-poet-dsl"
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bcv)
}

group = "dev.asm0dey"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlinpoet)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
```

Note: no `freeCompilerArgs` entry. Context parameters are Stable in Kotlin 2.4.0; adding `-Xcontext-parameters` would fail the build with an unknown-option error.

`.gitignore`:

```
build/
.gradle/
buildSrc/build/
.kotlin/
```

- [ ] **Step 4: Write the failing smoke test**

`src/test/kotlin/dev/asm0dey/poetdsl/ToolchainTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainTest {
    @Test
    fun `kotlinpoet renders a file`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(FunSpec.builder("noop").build())
            .build()
        assertEquals(
            """
            package com.example

            public fun noop() {
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `use site target ALL exists in the pinned kotlinpoet`() {
        assertTrue(AnnotationSpec.UseSiteTarget.entries.any { it.name == "ALL" })
    }

    @Test
    fun `context parameters compile without a flag`() {
        assertEquals("ok", withContext())
    }
}

class Marker(val value: String)

context(m: Marker)
fun readMarker(): String = m.value

fun withContext(): String = with(Marker("ok")) { readMarker() }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS, 3 tests. If `context parameters compile without a flag` fails to compile, the Kotlin version is wrong — check `libs.versions.toml` says `2.4.10`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle .gitignore gradlew gradlew.bat src/test
git commit -m "build: gradle skeleton with kotlin 2.4.10 and kotlinpoet 2.3.0"
```

---

### Task 2: Spike — does `@DslMarker` restrict context-argument resolution?

The spec's first safety layer assumes a `@BlockDsl`-marked `BlockScope` receiver hides an outer `@TypeDsl`-marked `TypeScope` receiver, so a `context(t: TypeScope)`-declared function cannot resolve inside a function body. Kotlin documents `@DslMarker` only in terms of implicit *receivers*; whether the filter also applies when an implicit receiver is used to satisfy a *context argument* is undocumented. This task answers it empirically and records the answer, because every later task's signature shape depends on it.

**Files:**
- Create: `docs/spikes/2026-08-13-dslmarker-context-parameters.md`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/SpikeDslMarkerTest.kt`

**Interfaces:**
- Consumes: Task 1's build.
- Produces: a written decision — either "DslMarker has teeth over context arguments" (spec design stands as written) or "it does not" (the runtime ownership check from Task 6 is the only cross-level guard, and the spike file says so).

- [ ] **Step 1: Write the probe**

`src/test/kotlin/dev/asm0dey/poetdsl/SpikeDslMarkerTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

@DslMarker
annotation class OuterDsl

@DslMarker
annotation class InnerDsl

@OuterDsl
class Outer {
    val log = mutableListOf<String>()
}

@InnerDsl
class Inner

context(o: Outer)
fun outerOnly(): String {
    o.log += "called"
    return "outer"
}

fun outer(body: Outer.() -> Unit): Outer = Outer().apply(body)

fun Outer.inner(body: Inner.() -> Unit) {
    Inner().body()
}

class SpikeDslMarkerTest {
    @Test
    fun `probe`() {
        val result = outer {
            inner {
                // UNCOMMENT the next line and run the build.
                // outerOnly()
            }
            outerOnly()
        }
        assertEquals(listOf("called"), result.log)
    }
}
```

- [ ] **Step 2: Run with the probe line commented out**

Run: `./gradlew test --tests '*SpikeDslMarkerTest*'`
Expected: PASS. This proves the harness itself is sound.

- [ ] **Step 3: Uncomment the probe line and run again**

Run: `./gradlew test --tests '*SpikeDslMarkerTest*'`
Record which happens:
- **Compile error** mentioning the DSL scope violation → `@DslMarker` *does* filter context-argument resolution. Spec design stands.
- **Compiles and passes** (`log` now has two entries, so the assertion fails at runtime) → `@DslMarker` does **not** filter context arguments.

- [ ] **Step 4: Restore the probe line to commented, write down the finding**

`docs/spikes/2026-08-13-dslmarker-context-parameters.md`:

```markdown
# Spike: @DslMarker vs context arguments (Kotlin 2.4.10)

**Question:** inside a `@InnerDsl`-marked receiver lambda nested in an `@OuterDsl`-marked
receiver lambda, does a call to a `context(o: Outer)` function still resolve?

**Method:** `src/test/kotlin/dev/asm0dey/poetdsl/SpikeDslMarkerTest.kt`, probe line
commented out in the committed state. Uncomment to reproduce.

**Result:** <FILL IN: "compile error: <exact message>" or "compiles — DslMarker does not
filter context arguments">

**Consequence:**
- If filtered: the three markers (`@FileDsl`, `@TypeDsl`, `@BlockDsl`) are a real
  compile-time guard. `` `fun` `` inside a function body is a compile error.
- If not filtered: markers still block outer *members* and marked-receiver extensions,
  but a cross-level context-parameter call compiles. The runtime ownership check
  (Task 6) is then the only guard, and the README must say so instead of promising a
  compile error.
```

Fill in `<FILL IN>` with the actual observed behaviour and the exact compiler message if there was one. Do not guess.

- [ ] **Step 5: Commit**

```bash
git add docs/spikes src/test/kotlin/dev/asm0dey/poetdsl/SpikeDslMarkerTest.kt
git commit -m "docs: record DslMarker vs context-argument resolution spike"
```

---

### Task 3: Precedence model, `Expr`, `Stmt`, and the DSL markers

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Markers.kt`
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Prec.kt`
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Expr.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/PrecTest.kt`

**Interfaces:**
- Consumes: Task 1's build.
- Produces:
  - `Expr(code: CodeBlock, type: TypeName?, prec: Int, name: String?, scope: ScopeId?)` — internal constructor, public class.
  - `Stmt(code: CodeBlock)` — internal constructor, public class.
  - `Prec` object with `ATOM POSTFIX PREFIX MULTIPLICATIVE ADDITIVE ELVIS COMPARISON EQUALITY CONJUNCTION DISJUNCTION` constants.
  - `internal fun Expr.paren(min: Int): CodeBlock`
  - `internal fun binaryExpr(left: Expr, op: String, right: Expr, prec: Int, type: TypeName?, rightAssoc: Boolean = false): Expr`
  - `@FileDsl`, `@TypeDsl`, `@BlockDsl` annotations.
  - `ScopeId` is referenced here but defined in Task 5. For this task, declare it in `Names.kt` as a stub: `public class ScopeId internal constructor(internal val parent: ScopeId?, internal val label: String)`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/PrecTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class PrecTest {
    private fun atom(name: String) = Expr(CodeBlock.of("%L", name), prec = Prec.ATOM)

    private val a = atom("a")
    private val b = atom("b")
    private val c = atom("c")

    @Test
    fun `higher precedence on the right needs no parens`() {
        val expr = binaryExpr(a, "+", binaryExpr(b, "*", c, Prec.MULTIPLICATIVE), Prec.ADDITIVE)
        assertEquals("a + b * c", expr.code.toString())
    }

    @Test
    fun `lower precedence operand is parenthesized`() {
        val expr = binaryExpr(binaryExpr(a, "+", b, Prec.ADDITIVE), "*", c, Prec.MULTIPLICATIVE)
        assertEquals("(a + b) * c", expr.code.toString())
    }

    @Test
    fun `same precedence on the right is parenthesized for left associative operators`() {
        val expr = binaryExpr(a, "-", binaryExpr(b, "-", c, Prec.ADDITIVE), Prec.ADDITIVE)
        assertEquals("a - (b - c)", expr.code.toString())
    }

    @Test
    fun `right associative operators do not parenthesize the right operand`() {
        val expr = binaryExpr(a, "?:", binaryExpr(b, "?:", c, Prec.ELVIS, rightAssoc = true), Prec.ELVIS, rightAssoc = true)
        assertEquals("a ?: b ?: c", expr.code.toString())
    }

    @Test
    fun `elvis binds tighter than comparison`() {
        val elvis = binaryExpr(a, "?:", b, Prec.ELVIS, rightAssoc = true)
        val expr = binaryExpr(elvis, "<", c, Prec.COMPARISON)
        assertEquals("a ?: b < c", expr.code.toString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PrecTest*'`
Expected: FAIL — `Unresolved reference: Expr`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Markers.kt`:

```kotlin
package dev.asm0dey.poetdsl

/** Marks the file-level DSL scope. Blocks implicit access to outer scopes. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class FileDsl

/** Marks the type-level DSL scope. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class TypeDsl

/** Marks the statement-level DSL scope. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class BlockDsl
```

`src/main/kotlin/dev/asm0dey/poetdsl/Prec.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * Kotlin operator precedence levels, high binds tighter.
 * Mirrors the grammar's order: postfix > prefix > multiplicative > additive >
 * elvis > comparison > equality > conjunction > disjunction.
 */
public object Prec {
    public const val ATOM: Int = 100
    public const val POSTFIX: Int = 90
    public const val PREFIX: Int = 80
    public const val MULTIPLICATIVE: Int = 70
    public const val ADDITIVE: Int = 60
    public const val ELVIS: Int = 50
    public const val COMPARISON: Int = 40
    public const val EQUALITY: Int = 30
    public const val CONJUNCTION: Int = 20
    public const val DISJUNCTION: Int = 10
}

/** Renders this expression, wrapping it in parentheses when it binds looser than [min]. */
internal fun Expr.paren(min: Int): CodeBlock =
    if (prec < min) CodeBlock.of("(%L)", code) else code

/**
 * Builds `left op right` with the minimum parentheses Kotlin needs.
 * Left-associative operators parenthesize an equal-precedence right operand;
 * right-associative ones parenthesize an equal-precedence left operand.
 */
internal fun binaryExpr(
    left: Expr,
    op: String,
    right: Expr,
    prec: Int,
    type: TypeName? = null,
    rightAssoc: Boolean = false,
): Expr = Expr(
    code = CodeBlock.of(
        "%L·%L·%L",
        left.paren(if (rightAssoc) prec + 1 else prec),
        op,
        right.paren(if (rightAssoc) prec else prec + 1),
    ),
    type = type,
    prec = prec,
)
```

The `·` characters are KotlinPoet's non-breaking spaces: they render as ordinary spaces but stop the line-wrapper from breaking an expression across lines mid-operator.

`src/main/kotlin/dev/asm0dey/poetdsl/Expr.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * A generated Kotlin expression. Pure — building one never emits anything.
 *
 * @property type the expression's type when known, used for return-type inference; null when unknowable.
 * @property prec the expression's binding strength, used to parenthesize automatically.
 * @property name the source-level name when this handle refers to a binding.
 * @property scope the scope that declared this handle, used for the runtime ownership check.
 */
public class Expr internal constructor(
    internal val code: CodeBlock,
    internal val type: TypeName? = null,
    internal val prec: Int = Prec.ATOM,
    internal val name: String? = null,
    internal val scope: ScopeId? = null,
) {
    override fun toString(): String = code.toString()
}

/** A generated Kotlin statement, produced by the pure `stmts { }` form. */
public class Stmt internal constructor(internal val code: CodeBlock) {
    override fun toString(): String = code.toString()
}
```

`src/main/kotlin/dev/asm0dey/poetdsl/Names.kt` (stub, completed in Task 5):

```kotlin
package dev.asm0dey.poetdsl

/** Identity of a DSL scope, used to detect handles smuggled out of their declaring scope. */
public class ScopeId internal constructor(
    internal val parent: ScopeId?,
    internal val label: String,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PrecTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl src/test/kotlin/dev/asm0dey/poetdsl/PrecTest.kt
git commit -m "feat: expression model with precedence-driven parenthesization"
```

---

### Task 4: Literals, type references, member references, escape hatch

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Literals.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/LiteralsTest.kt`

**Interfaces:**
- Consumes: `Expr`, `Prec` (Task 3).
- Produces:
  - `val Int.literal: Expr`, and the same for `Long`, `Double`, `Float`, `Boolean`, `Char`, `String`; each with a `lit` alias.
  - `val nullLiteral: Expr`, alias `val nul: Expr`.
  - `inline fun <reified T> reference(): ClassName`, alias `ref`. Returns a `ClassName` so it works in **type position** (`` `var`(..., ref<Collaborator>()) ``) and as a `%T` argument alike.
  - `fun ClassName.expression(): Expr` and `fun MemberName.expression(): Expr` for using a reference in expression position; aliases `expr`.
  - `fun member(packageName: String, simpleName: String): MemberName` and `fun member(enclosing: ClassName, simpleName: String): MemberName`, alias `mem`.
  - `fun expression(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr`, alias `expr`.
  - `internal fun Any?.asFormatArg(): Any?` — unwraps `Expr` to its `CodeBlock` so `%L` renders it.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/LiteralsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class LiteralsTest {
    @Test
    fun `numeric and boolean literals`() {
        assertEquals("1", 1.literal.toString())
        assertEquals("1", 1.lit.toString())
        assertEquals("true", true.literal.toString())
        assertEquals("2.5", 2.5.literal.toString())
    }

    @Test
    fun `string literals are escaped`() {
        assertEquals("\"a\\\"b\"", "a\"b".literal.toString())
        assertEquals("\"tab\\there\"", "tab\there".literal.toString())
    }

    @Test
    fun `null literal`() {
        assertEquals("null", nullLiteral.toString())
        assertEquals("null", nul.toString())
    }

    @Test
    fun `type reference resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", expression("%T()", reference<StringBuilder>()).code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import java.lang.StringBuilder

            public fun f() {
              StringBuilder()
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `member reference resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", member("kotlin.collections", "listOf").expression().code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import kotlin.collections.listOf

            public fun f() {
              listOf
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `escape hatch keeps placeholders and unwraps Expr arguments`() {
        val xs = expression("xs")
        val e = expression("%L.filterIsInstance<%T>()", xs, reference<CharSequence>(), prec = Prec.POSTFIX)
        assertEquals("xs.filterIsInstance<kotlin.CharSequence>()", e.code.toString())
        assertEquals(Prec.POSTFIX, e.prec)
    }
}
```

Note: `CodeBlock.toString()` renders `%T` fully qualified, since import resolution happens only when a `FileSpec` is built — hence the two `FileSpec`-based tests above.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LiteralsTest*'`
Expected: FAIL — `Unresolved reference: literal`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Literals.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.asClassName

public val Int.literal: Expr get() = Expr(CodeBlock.of("%L", this), INT)
public val Int.lit: Expr get() = literal

public val Long.literal: Expr get() = Expr(CodeBlock.of("%LL", this), LONG)
public val Long.lit: Expr get() = literal

public val Double.literal: Expr get() = Expr(CodeBlock.of("%L", this), DOUBLE)
public val Double.lit: Expr get() = literal

public val Float.literal: Expr get() = Expr(CodeBlock.of("%LF", this), FLOAT)
public val Float.lit: Expr get() = literal

public val Boolean.literal: Expr get() = Expr(CodeBlock.of("%L", this), BOOLEAN)
public val Boolean.lit: Expr get() = literal

public val Char.literal: Expr get() = Expr(CodeBlock.of("%L", "'$this'"), CHAR)
public val Char.lit: Expr get() = literal

/** A string literal. Escaping is KotlinPoet's `%S`. */
public val String.literal: Expr get() = Expr(CodeBlock.of("%S", this), STRING)
public val String.lit: Expr get() = literal

/** The `null` literal. */
public val nullLiteral: Expr get() = Expr(CodeBlock.of("null"))
public val nul: Expr get() = nullLiteral

/** A type reference. Usable in type position and as a `%T` argument; the import resolves automatically. */
public inline fun <reified T> reference(): ClassName = T::class.asClassName()
public inline fun <reified T> ref(): ClassName = reference<T>()

/** A top-level or enclosed member reference; `%M` resolves the import. */
public fun member(packageName: String, simpleName: String): MemberName = MemberName(packageName, simpleName)
public fun member(enclosing: ClassName, simpleName: String): MemberName = MemberName(enclosing, simpleName)
public fun mem(packageName: String, simpleName: String): MemberName = member(packageName, simpleName)
public fun mem(enclosing: ClassName, simpleName: String): MemberName = member(enclosing, simpleName)

/** Uses this type in expression position, e.g. as the receiver of a companion call. */
public fun ClassName.expression(): Expr = Expr(CodeBlock.of("%T", this), this)
public fun ClassName.expr(): Expr = expression()

/** Uses this member in expression position. */
public fun MemberName.expression(): Expr = Expr(CodeBlock.of("%M", this))
public fun MemberName.expr(): Expr = expression()

/**
 * Escape hatch for constructs the DSL does not model. `%T`/`%M` placeholders survive, so
 * imports still resolve; [Expr] arguments are unwrapped so `%L` renders their code.
 *
 * Strings passed here bypass scope checking — that is the documented trade-off.
 *
 * @param prec the result's binding strength. Leave at [Prec.ATOM] for a self-contained
 *   expression; pass the real level (e.g. [Prec.ADDITIVE] for `"a + b"`) so surrounding
 *   operators parenthesize correctly.
 */
public fun expression(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr =
    Expr(CodeBlock.of(format, *args.map { it.asFormatArg() }.toTypedArray()), prec = prec)

public fun expr(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr =
    expression(format, *args, prec = prec)

internal fun Any?.asFormatArg(): Any? = if (this is Expr) code else this
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LiteralsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Literals.kt src/test/kotlin/dev/asm0dey/poetdsl/LiteralsTest.kt
git commit -m "feat: literals, type and member references, expression escape hatch"
```

---

### Task 5: Operators

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Operators.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/OperatorsTest.kt`

**Interfaces:**
- Consumes: `Expr`, `Prec`, `binaryExpr` (Task 3), `literal` (Task 4).
- Produces, all pure (`Expr` in, `Expr` out, nothing emitted):
  - `operator fun Expr.plus/minus/times/div/rem(other: Expr): Expr`
  - `operator fun Expr.unaryMinus(): Expr`
  - `infix fun Expr.eq/neq(other: Expr): Expr` → `==`, `!=`, type `BOOLEAN`
  - `infix fun Expr.lt/le/gt/ge(other: Expr): Expr` → `<`, `<=`, `>`, `>=`, type `BOOLEAN`
  - `infix fun Expr.and/or(other: Expr): Expr` → `&&`, `||`, type `BOOLEAN`
  - `fun Expr.not(): Expr` → `!a`, type `BOOLEAN`
  - `infix fun Expr.elvis(other: Expr): Expr` → `?:`, right-associative

Arithmetic result types are inferred as `this.type` when both operands agree, otherwise null.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/OperatorsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperatorsTest {
    private val a = expression("a")
    private val b = expression("b")
    private val c = expression("c")

    @Test
    fun `arithmetic`() {
        assertEquals("a + b", (a + b).toString())
        assertEquals("a - b", (a - b).toString())
        assertEquals("a * b", (a * b).toString())
        assertEquals("a / b", (a / b).toString())
        assertEquals("a % b", (a % b).toString())
        assertEquals("-a", (-a).toString())
    }

    @Test
    fun `comparison and equality use infix names`() {
        assertEquals("a == b", (a eq b).toString())
        assertEquals("a != b", (a neq b).toString())
        assertEquals("a < b", (a lt b).toString())
        assertEquals("a <= b", (a le b).toString())
        assertEquals("a > b", (a gt b).toString())
        assertEquals("a >= b", (a ge b).toString())
        assertEquals(BOOLEAN, (a lt b).type)
    }

    @Test
    fun `logical operators and negation`() {
        assertEquals("a && b", (a and b).toString())
        assertEquals("a || b", (a or b).toString())
        assertEquals("!a", a.not().toString())
        assertEquals("!(a && b)", (a and b).not().toString())
    }

    @Test
    fun `elvis is right associative and binds tighter than comparison`() {
        assertEquals("a ?: b", (a elvis b).toString())
        assertEquals("a ?: b ?: c", (a elvis (b elvis c)).toString())
        assertEquals("(a ?: b) < c", ((a elvis b) lt c).toString())
    }

    @Test
    fun `conjunction binds tighter than disjunction`() {
        assertEquals("a && b || c", ((a and b) or c).toString())
        assertEquals("a && (b || c)", (a and (b or c)).toString())
    }

    @Test
    fun `arithmetic keeps the operand type when both agree`() {
        assertEquals(INT, (1.lit + 2.lit).type)
        assertNull((1.lit + expression("x")).type)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*OperatorsTest*'`
Expected: FAIL — `Unresolved reference: eq`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Operators.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

private fun Expr.sharedType(other: Expr): TypeName? = if (type != null && type == other.type) type else null

public operator fun Expr.plus(other: Expr): Expr =
    binaryExpr(this, "+", other, Prec.ADDITIVE, sharedType(other))

public operator fun Expr.minus(other: Expr): Expr =
    binaryExpr(this, "-", other, Prec.ADDITIVE, sharedType(other))

public operator fun Expr.times(other: Expr): Expr =
    binaryExpr(this, "*", other, Prec.MULTIPLICATIVE, sharedType(other))

public operator fun Expr.div(other: Expr): Expr =
    binaryExpr(this, "/", other, Prec.MULTIPLICATIVE, sharedType(other))

public operator fun Expr.rem(other: Expr): Expr =
    binaryExpr(this, "%%", other, Prec.MULTIPLICATIVE, sharedType(other))

public operator fun Expr.unaryMinus(): Expr =
    Expr(CodeBlock.of("-%L", paren(Prec.PREFIX)), type, Prec.PREFIX)

/** `==`. Named because Kotlin's `equals` must return `Boolean`, not an [Expr]. */
public infix fun Expr.eq(other: Expr): Expr = binaryExpr(this, "==", other, Prec.EQUALITY, BOOLEAN)

/** `!=`. */
public infix fun Expr.neq(other: Expr): Expr = binaryExpr(this, "!=", other, Prec.EQUALITY, BOOLEAN)

/** `<`. Named because Kotlin's `compareTo` must return `Int`. */
public infix fun Expr.lt(other: Expr): Expr = binaryExpr(this, "<", other, Prec.COMPARISON, BOOLEAN)

/** `<=`. */
public infix fun Expr.le(other: Expr): Expr = binaryExpr(this, "<=", other, Prec.COMPARISON, BOOLEAN)

/** `>`. */
public infix fun Expr.gt(other: Expr): Expr = binaryExpr(this, ">", other, Prec.COMPARISON, BOOLEAN)

/** `>=`. */
public infix fun Expr.ge(other: Expr): Expr = binaryExpr(this, ">=", other, Prec.COMPARISON, BOOLEAN)

/** `&&`. Named because `&&` is not overloadable. */
public infix fun Expr.and(other: Expr): Expr = binaryExpr(this, "&&", other, Prec.CONJUNCTION, BOOLEAN)

/** `||`. */
public infix fun Expr.or(other: Expr): Expr = binaryExpr(this, "||", other, Prec.DISJUNCTION, BOOLEAN)

/** `!a`. */
public fun Expr.not(): Expr = Expr(CodeBlock.of("!%L", paren(Prec.PREFIX)), BOOLEAN, Prec.PREFIX)

/** `?:`. Right-associative, binds tighter than comparison. */
public infix fun Expr.elvis(other: Expr): Expr =
    binaryExpr(this, "?:", other, Prec.ELVIS, other.type ?: type, rightAssoc = true)
```

Note the `"%%"` for the remainder operator: `binaryExpr` passes the operator through `CodeBlock.of` as a `%L` argument, so a literal `%` must be escaped there. If a test shows `%%` leaking into the output, change `binaryExpr` to take the operator as a plain string concatenation instead of a format argument.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OperatorsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Operators.kt src/test/kotlin/dev/asm0dey/poetdsl/OperatorsTest.kt
git commit -m "feat: arithmetic, comparison, logical and elvis operators"
```

---

### Task 6: Names — scope identity, uniquification, singularization

**Files:**
- Modify: `src/main/kotlin/dev/asm0dey/poetdsl/Names.kt` (replaces the Task 3 stub)
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/NamesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ScopeId` with `internal fun child(label: String): ScopeId` and `internal fun isAncestorOf(other: ScopeId?): Boolean` (a scope is its own ancestor).
  - `internal class NameScope(parent: NameScope?)` with `fun unique(base: String): String`, `fun isTaken(name: String): Boolean`, `fun child(): NameScope`, `fun declare(name: String)`.
  - `internal fun singularize(name: String): String` — `items` → `item`, `users` → `user`, `boxes` → `box`, `entries` → `entry`, `data` → `data`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/NamesTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `collisions get a numeric suffix`() {
        val scope = NameScope(null)
        assertEquals("item", scope.unique("item"))
        assertEquals("item2", scope.unique("item"))
        assertEquals("item3", scope.unique("item"))
    }

    @Test
    fun `a child scope sees names taken by its parent`() {
        val parent = NameScope(null)
        parent.unique("item")
        val child = parent.child()
        assertEquals("item2", child.unique("item"))
        assertTrue(child.isTaken("item"))
        assertFalse(parent.isTaken("item2"))
    }

    @Test
    fun `scope ancestry`() {
        val root = ScopeId(null, "file")
        val type = root.child("type")
        val block = type.child("block")
        val sibling = root.child("other")
        assertTrue(root.isAncestorOf(block))
        assertTrue(block.isAncestorOf(block))
        assertFalse(block.isAncestorOf(root))
        assertFalse(sibling.isAncestorOf(block))
        assertFalse(root.isAncestorOf(null))
    }

    @Test
    fun `singularization`() {
        assertEquals("item", singularize("items"))
        assertEquals("user", singularize("users"))
        assertEquals("box", singularize("boxes"))
        assertEquals("entry", singularize("entries"))
        assertEquals("data", singularize("data"))
        assertEquals("item", singularize(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*NamesTest*'`
Expected: FAIL — `Unresolved reference: NameScope`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Names.kt` (full replacement):

```kotlin
package dev.asm0dey.poetdsl

/**
 * Identity of a DSL scope. Handles carry the [ScopeId] that declared them, so emitting a
 * handle in an unrelated scope can be rejected at build time.
 */
public class ScopeId internal constructor(
    internal val parent: ScopeId?,
    internal val label: String,
) {
    internal fun child(label: String): ScopeId = ScopeId(this, label)

    /** True when [other] is this scope or nested inside it. */
    internal fun isAncestorOf(other: ScopeId?): Boolean {
        var current = other
        while (current != null) {
            if (current === this) return true
            current = current.parent
        }
        return false
    }

    override fun toString(): String = label
}

/** Tracks the names bound in a scope so generated names never collide. */
internal class NameScope(private val parent: NameScope?) {
    private val taken = mutableSetOf<String>()

    fun isTaken(name: String): Boolean = name in taken || parent?.isTaken(name) == true

    fun declare(name: String) {
        taken += name
    }

    /** Returns [base] if free, otherwise `base2`, `base3`, … Registers the result. */
    fun unique(base: String): String {
        if (!isTaken(base)) {
            declare(base)
            return base
        }
        var suffix = 2
        while (isTaken("$base$suffix")) suffix++
        val name = "$base$suffix"
        declare(name)
        return name
    }

    fun child(): NameScope = NameScope(this)
}

/** Best-effort English singular, used for loop variable defaults. Falls back to `item`. */
internal fun singularize(name: String): String = when {
    name.isEmpty() -> "item"
    name.endsWith("ies") && name.length > 3 -> name.dropLast(3) + "y"
    name.endsWith("sses") || name.endsWith("xes") || name.endsWith("ches") || name.endsWith("shes") ->
        name.dropLast(2)
    name.endsWith("ss") -> name
    name.endsWith("s") && name.length > 1 -> name.dropLast(1)
    else -> name
}
```

`singularize("data")` returns `data` because it does not end in `s`; `singularize("boxes")` drops `es`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*NamesTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Names.kt src/test/kotlin/dev/asm0dey/poetdsl/NamesTest.kt
git commit -m "feat: scope identity, name uniquification, singularization"
```

---

### Task 7: `BlockScope`, emission, the pure `stmts { }` form, ownership checking

This is the load-bearing task: it fixes the shape every later statement builder copies.

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/BlockScope.kt`
- Create: `src/test/kotlin/dev/asm0dey/poetdsl/TestSupport.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/BlockScopeTest.kt`

**Interfaces:**
- Consumes: `Expr`, `Stmt` (Task 3), `NameScope`, `ScopeId` (Task 6).
- Produces:
  - `@BlockDsl class BlockScope internal constructor(builder: CodeBlock.Builder, names: NameScope, id: ScopeId, returns: MutableList<TypeName?>)` with an internal `pending: PendingFlow?` slot used by Task 12's if-chain.
  - `internal fun BlockScope.child(label: String): BlockScope` — nested scope sharing the `returns` list, with a child `NameScope` and child `ScopeId`, writing into a fresh `CodeBlock.Builder`.
  - `internal fun BlockScope.emitCode(code: CodeBlock)` — flushes any pending control flow, then `addStatement`.
  - `internal fun BlockScope.checkOwned(expr: Expr)` — throws `IllegalStateException` when `expr.scope` is set and is not an ancestor of this scope.
  - `internal fun BlockScope.flushPending()` and `internal interface PendingFlow { fun close() }`.
  - `context(b: BlockScope) fun statement(expr: Expr)`, alias `stmt`, and `context(b: BlockScope) operator fun Expr.unaryPlus()`.
  - `context(b: BlockScope) operator fun Stmt.unaryPlus()`, `context(b: BlockScope) operator fun Stmt.invoke()`, `context(b: BlockScope) fun emit(stmt: Stmt)`, `context(b: BlockScope) fun add(stmt: Stmt)`.
  - `fun stmts(body: BlockScope.() -> Unit): Stmt` — detached scope, pure form.
  - `internal fun buildBlock(parent: BlockScope?, label: String, body: BlockScope.() -> Unit): CodeBlock`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/TestSupport.kt`:

```kotlin
package dev.asm0dey.poetdsl

/** Renders a detached block, for golden assertions on statement output. */
internal fun renderBlock(body: BlockScope.() -> Unit): String = stmts(body).code.toString()
```

`src/test/kotlin/dev/asm0dey/poetdsl/BlockScopeTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlockScopeTest {
    @Test
    fun `unary plus emits a statement`() {
        val out = renderBlock {
            +expression("compute()")
        }
        assertEquals("compute()\n", out)
    }

    @Test
    fun `statement and stmt are the same construct`() {
        assertEquals(
            renderBlock { statement(expression("a")) },
            renderBlock { stmt(expression("a")) },
        )
    }

    @Test
    fun `statements emit in order`() {
        val out = renderBlock {
            +expression("first()")
            +expression("second()")
        }
        assertEquals("first()\nsecond()\n", out)
    }

    @Test
    fun `a pure Stmt can be emitted into another block`() {
        val guard: Stmt = stmts { +expression("check()") }
        val out = renderBlock { +guard }
        assertEquals("check()\n", out)
    }

    @Test
    fun `emit add and invoke are equivalent spellings`() {
        val s = stmts { +expression("x()") }
        assertEquals(renderBlock { +s }, renderBlock { emit(s) })
        assertEquals(renderBlock { +s }, renderBlock { add(s) })
        assertEquals(renderBlock { +s }, renderBlock { s() })
    }

    @Test
    fun `a handle from an unrelated scope is rejected`() {
        var smuggled: Expr? = null
        stmts {
            smuggled = Expr(
                code = com.squareup.kotlinpoet.CodeBlock.of("leaked"),
                name = "leaked",
                scope = this.id.child("inner"),
            )
        }
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { +smuggled!! }
        }
        assertEquals(
            "Handle 'leaked' was declared in scope 'inner', which does not enclose the current scope 'block'.",
            failure.message,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BlockScopeTest*'`
Expected: FAIL — `Unresolved reference: stmts`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/BlockScope.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * The statement-level scope. Holds a KotlinPoet [CodeBlock.Builder] plus the names bound
 * so far; every statement builder in this library takes one as a context parameter.
 */
@BlockDsl
public class BlockScope internal constructor(
    internal val builder: CodeBlock.Builder,
    internal val names: NameScope,
    internal val id: ScopeId,
    internal val returns: MutableList<TypeName?>,
) {
    /** An open control-flow block waiting for its closing brace. See ControlFlow.kt. */
    internal var pending: PendingFlow? = null
}

/** A control-flow block left open by the builder that started it. */
internal interface PendingFlow {
    fun close()
}

internal fun BlockScope.child(label: String): BlockScope =
    BlockScope(CodeBlock.builder(), names.child(), id.child(label), returns)

/** Closes any control-flow block left open by a previous builder. */
internal fun BlockScope.flushPending() {
    val open = pending ?: return
    pending = null
    open.close()
}

/**
 * Rejects a handle that escaped its declaring scope. Handles without a scope (literals,
 * escape-hatch expressions) are always accepted.
 */
internal fun BlockScope.checkOwned(expr: Expr) {
    val owner = expr.scope ?: return
    check(owner.isAncestorOf(id)) {
        "Handle '${expr.name ?: expr.code}' was declared in scope '${owner.label}', " +
            "which does not enclose the current scope '${id.label}'."
    }
}

internal fun BlockScope.emitCode(code: CodeBlock) {
    flushPending()
    builder.addStatement("%L", code)
}

/** Runs [body] against a fresh detached scope and returns the code it produced. */
internal fun buildBlock(parent: BlockScope?, label: String, body: BlockScope.() -> Unit): CodeBlock {
    val scope = parent?.child(label)
        ?: BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, label), mutableListOf())
    scope.body()
    scope.flushPending()
    return scope.builder.build()
}

/** Emits [expr] as a statement. */
context(b: BlockScope)
public fun statement(expr: Expr) {
    b.checkOwned(expr)
    b.emitCode(expr.code)
}

/** Alias of [statement]. */
context(b: BlockScope)
public fun stmt(expr: Expr) {
    statement(expr)
}

context(b: BlockScope)
public operator fun Expr.unaryPlus() {
    statement(this)
}

context(b: BlockScope)
public operator fun Stmt.unaryPlus() {
    b.flushPending()
    b.builder.add(code)
}

context(b: BlockScope)
public operator fun Stmt.invoke() {
    +this
}

context(b: BlockScope)
public fun emit(stmt: Stmt) {
    +stmt
}

context(b: BlockScope)
public fun add(stmt: Stmt) {
    +stmt
}

/**
 * The pure form: runs the same statement builders against a detached scope and returns
 * the result instead of emitting it.
 */
public fun stmts(body: BlockScope.() -> Unit): Stmt = Stmt(buildBlock(null, "block", body))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BlockScopeTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/BlockScope.kt src/test/kotlin/dev/asm0dey/poetdsl
git commit -m "feat: block scope, statement emission, pure stmts form, ownership check"
```

---

### Task 8: Bindings — `val`, `var`, assignment, compound assignment

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Bindings.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/BindingsTest.kt`

**Interfaces:**
- Consumes: `BlockScope`, `emitCode`, `checkOwned` (Task 7); `Expr` (Task 3).
- Produces:
  - `context(b: BlockScope) fun `val`(name: String, type: TypeName? = null, init: Expr): Expr` — emits and returns the handle. Alias: none at statement level (the declaration-level alias `property` belongs to `TypeScope`, Task 17).
  - `context(b: BlockScope) fun `var`(name: String, type: TypeName? = null, init: Expr): Expr`
  - `context(b: BlockScope) infix fun Expr.assign(value: Expr)`
  - `context(b: BlockScope) operator fun Expr.plusAssign/minusAssign/timesAssign/divAssign/remAssign(value: Expr)`
  - `context(b: BlockScope) fun Expr.plusAssignPure(...)` — **not** produced; the pure twins are written as `stmts { total += x }`, exactly as the spec states.

The returned handle carries `name` (the uniquified name), `type` (declared type, or the initializer's inferred type), and `scope` = the declaring `BlockScope`'s `id`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/BindingsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals

class BindingsTest {
    @Test
    fun `val with an explicit type`() {
        assertEquals("val n: kotlin.Int = 1\n", renderBlock { `val`("n", INT, 1.lit) })
    }

    @Test
    fun `val without a type omits it`() {
        assertEquals("val n = 1\n", renderBlock { `val`("n", init = 1.lit) })
    }

    @Test
    fun `var and reassignment`() {
        val out = renderBlock {
            val total = `var`("total", INT, 0.lit)
            total assign (total + 1.lit)
        }
        assertEquals("var total: kotlin.Int = 0\ntotal = total + 1\n", out)
    }

    @Test
    fun `compound assignment operators`() {
        val out = renderBlock {
            val t = `var`("t", INT, 0.lit)
            t += 1.lit
            t -= 2.lit
            t *= 3.lit
            t /= 4.lit
            t %= 5.lit
        }
        assertEquals(
            "var t: kotlin.Int = 0\nt += 1\nt -= 2\nt *= 3\nt /= 4\nt %= 5\n",
            out,
        )
    }

    @Test
    fun `the pure twin of compound assignment is stmts`() {
        val emitting = renderBlock {
            val t = `var`("t", INT, 0.lit)
            t += 1.lit
        }
        val viaPure = renderBlock {
            val t = `var`("t", INT, 0.lit)
            +stmts { t += 1.lit }
        }
        assertEquals(emitting, viaPure)
    }

    @Test
    fun `colliding names are uniquified and the handle uses the new name`() {
        val out = renderBlock {
            `val`("item", init = 1.lit)
            val second = `val`("item", init = 2.lit)
            +second
        }
        assertEquals("val item = 1\nval item2 = 2\nitem2\n", out)
    }

    @Test
    fun `the handle carries the initializer type when no type is declared`() {
        var captured: Expr? = null
        renderBlock { captured = `val`("n", init = 1.lit) }
        assertEquals(INT, captured?.type)
    }
}
```

Note test 5: the handle `t` is declared in the outer scope and used inside `stmts { }`, whose scope has no parent, so the ownership check must **not** fire for it. Because `stmts { }` creates a detached root scope, `t.scope` is not an ancestor — this test will fail unless `stmts` is allowed to skip the check. Resolve it by having `stmts(body)` build its scope with `parent = null` but marking the scope as **detached**: add `internal val detached: Boolean = false` to `BlockScope`, set it in `buildBlock` when `parent == null`, and make `checkOwned` return early when `detached` is true. Apply that change in this task, in `BlockScope.kt`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BindingsTest*'`
Expected: FAIL — `Unresolved reference: val`.

- [ ] **Step 3: Relax the ownership check for detached scopes**

In `src/main/kotlin/dev/asm0dey/poetdsl/BlockScope.kt`, add the flag and use it:

```kotlin
@BlockDsl
public class BlockScope internal constructor(
    internal val builder: CodeBlock.Builder,
    internal val names: NameScope,
    internal val id: ScopeId,
    internal val returns: MutableList<TypeName?>,
    internal val detached: Boolean = false,
) {
    internal var pending: PendingFlow? = null
}
```

```kotlin
internal fun BlockScope.checkOwned(expr: Expr) {
    if (detached) return
    val owner = expr.scope ?: return
    check(owner.isAncestorOf(id)) {
        "Handle '${expr.name ?: expr.code}' was declared in scope '${owner.label}', " +
            "which does not enclose the current scope '${id.label}'."
    }
}
```

```kotlin
internal fun buildBlock(parent: BlockScope?, label: String, body: BlockScope.() -> Unit): CodeBlock {
    val scope = parent?.child(label)
        ?: BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, label), mutableListOf(), detached = true)
    scope.body()
    scope.flushPending()
    return scope.builder.build()
}
```

`BlockScopeTest.a handle from an unrelated scope is rejected` uses `renderBlock`, which goes through `stmts` and is now detached — so update that test to build a non-detached scope instead:

```kotlin
    @Test
    fun `a handle from an unrelated scope is rejected`() {
        val root = BlockScope(
            com.squareup.kotlinpoet.CodeBlock.builder(),
            NameScope(null),
            ScopeId(null, "block"),
            mutableListOf(),
        )
        val smuggled = Expr(
            code = com.squareup.kotlinpoet.CodeBlock.of("leaked"),
            name = "leaked",
            scope = ScopeId(null, "inner"),
        )
        val failure = assertFailsWith<IllegalStateException> { with(root) { +smuggled } }
        assertEquals(
            "Handle 'leaked' was declared in scope 'inner', which does not enclose the current scope 'block'.",
            failure.message,
        )
    }
```

- [ ] **Step 4: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Bindings.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

private fun BlockScope.bind(keyword: String, name: String, type: TypeName?, init: Expr): Expr {
    checkOwned(init)
    val unique = names.unique(name)
    val code = if (type == null) {
        CodeBlock.of("%L·%L·=·%L", keyword, unique, init.code)
    } else {
        CodeBlock.of("%L·%L:·%T·=·%L", keyword, unique, type, init.code)
    }
    emitCode(code)
    return Expr(CodeBlock.of("%L", unique), type ?: init.type, Prec.ATOM, unique, id)
}

/**
 * Declares a local `val`. Emits the declaration **and** returns a handle — the single
 * exception to "Unit emits, Expr does not".
 *
 * @param type pass null to let Kotlin infer it in the generated code.
 */
context(b: BlockScope)
public fun `val`(name: String, type: TypeName? = null, init: Expr): Expr =
    b.bind("val", name, type, init)

/** Declares a local `var`. Emits and returns a handle. */
context(b: BlockScope)
public fun `var`(name: String, type: TypeName? = null, init: Expr): Expr =
    b.bind("var", name, type, init)

/** `a = b`. Named because `=` is not overloadable. */
context(b: BlockScope)
public infix fun Expr.assign(value: Expr) {
    b.checkOwned(this)
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("%L·=·%L", code, value.code))
}

private fun BlockScope.compound(target: Expr, op: String, value: Expr) {
    checkOwned(target)
    checkOwned(value)
    emitCode(CodeBlock.of("%L·%L·%L", target.code, op, value.code))
}

context(b: BlockScope)
public operator fun Expr.plusAssign(value: Expr) {
    b.compound(this, "+=", value)
}

context(b: BlockScope)
public operator fun Expr.minusAssign(value: Expr) {
    b.compound(this, "-=", value)
}

context(b: BlockScope)
public operator fun Expr.timesAssign(value: Expr) {
    b.compound(this, "*=", value)
}

context(b: BlockScope)
public operator fun Expr.divAssign(value: Expr) {
    b.compound(this, "/=", value)
}

context(b: BlockScope)
public operator fun Expr.remAssign(value: Expr) {
    b.compound(this, "%%=", value)
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests '*BindingsTest*' --tests '*BlockScopeTest*'`
Expected: PASS. If `%%=` renders as `%%=`, drop one `%` — `CodeBlock.of` escapes `%` only in the format string, and here the operator arrives via `%L`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl src/test/kotlin/dev/asm0dey/poetdsl
git commit -m "feat: val, var, assignment and compound assignment"
```

---

### Task 9: Calls and property access

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Calls.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/CallsTest.kt`

**Interfaces:**
- Consumes: `Expr`, `Prec` (Task 3); `member` (Task 4).
- Produces, all pure:
  - `fun Expr.call(name: String, vararg args: Expr): Expr` → `recv.name(args)`
  - `fun Expr.safeCall(name: String, vararg args: Expr): Expr` → `recv?.name(args)`
  - `fun Expr.prop(name: String): Expr` → `recv.name`
  - `fun Expr.safeProp(name: String): Expr` → `recv?.name`
  - `fun call(name: String, vararg args: Expr): Expr` → `name(args)` — receiverless, no import
  - `fun call(member: MemberName, vararg args: Expr): Expr` → `%M(args)` — import resolved
  - `internal fun argList(args: Array<out Expr>): CodeBlock` — comma-joined arguments
  - All results have `prec = Prec.POSTFIX` and `type = null` (a callee's return type is unknowable).

Lambda-taking overloads of `call` arrive in Task 10; callable-reference overloads in Task 11.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/CallsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallsTest {
    private val x = expression("x")

    @Test
    fun `member call and property access`() {
        assertEquals("x.isNotEmpty()", x.call("isNotEmpty").toString())
        assertEquals("x.length", x.prop("length").toString())
        assertEquals("x?.isNotEmpty()", x.safeCall("isNotEmpty").toString())
        assertEquals("x?.length", x.safeProp("length").toString())
    }

    @Test
    fun `call arguments are comma separated`() {
        assertEquals("""x.substring(0, 3)""", x.call("substring", 0.lit, 3.lit).toString())
        assertEquals("""x.startsWith("a")""", x.call("startsWith", "a".lit).toString())
    }

    @Test
    fun `receiverless call emits no import`() {
        assertEquals("calculate()", call("calculate").toString())
    }

    @Test
    fun `member call resolves an import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", call(member("kotlin.collections", "listOf"), 1.lit).code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import kotlin.collections.listOf

            public fun f() {
              listOf(1)
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `calls bind tighter than arithmetic`() {
        assertEquals("x.length + 1", (x.prop("length") + 1.lit).toString())
        assertEquals("(x + 1).toString()", (x + 1.lit).call("toString").toString())
    }

    @Test
    fun `a call has no known return type`() {
        assertNull(x.call("foo").type)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*CallsTest*'`
Expected: FAIL — `Unresolved reference: call`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Calls.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

internal fun argList(args: Array<out Expr>): CodeBlock =
    args.map { it.code }.fold(CodeBlock.builder()) { acc, code ->
        if (acc.isNotEmpty()) acc.add(",·") else acc
        acc.add("%L", code)
    }.build()

/** `receiver.name(args)`. The member name is a string because it is unknown when the generator compiles. */
public fun Expr.call(name: String, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%L.%L(%L)", paren(Prec.POSTFIX), name, argList(args)), prec = Prec.POSTFIX)

/** `receiver?.name(args)`. */
public fun Expr.safeCall(name: String, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%L?.%L(%L)", paren(Prec.POSTFIX), name, argList(args)), prec = Prec.POSTFIX)

/** `receiver.name`. */
public fun Expr.prop(name: String): Expr =
    Expr(CodeBlock.of("%L.%L", paren(Prec.POSTFIX), name), prec = Prec.POSTFIX)

/** `receiver?.name`. */
public fun Expr.safeProp(name: String): Expr =
    Expr(CodeBlock.of("%L?.%L", paren(Prec.POSTFIX), name), prec = Prec.POSTFIX)

/** `name(args)` — a bare call, no import registered. */
public fun call(name: String, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%L(%L)", name, argList(args)), prec = Prec.POSTFIX)

/** `name(args)` where `name` is a [MemberName], so `%M` resolves the import. */
public fun call(member: MemberName, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%M(%L)", member, argList(args)), prec = Prec.POSTFIX)
```

`CodeBlock.Builder.isNotEmpty()` exists in KotlinPoet 2.x. If it does not resolve, replace the fold with an index-based loop that adds `",·"` before every argument after the first.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*CallsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Calls.kt src/test/kotlin/dev/asm0dey/poetdsl/CallsTest.kt
git commit -m "feat: calls, safe calls and property access"
```

---

### Task 10: Lambdas

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Lambdas.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/LambdasTest.kt`

**Interfaces:**
- Consumes: `BlockScope`, `buildBlock`, `child` (Task 7); `Calls.argList` (Task 9).
- Produces:
  - `internal fun lambdaCode(parent: BlockScope?, params: List<String>, body: CodeBlock): CodeBlock`
  - `internal fun lambdaExpr(parent: BlockScope?, names: List<String>, arity: Int, build: (List<Expr>) -> Unit)` helper used by the generated overloads.
  - `fun lambda(body: BlockScope.() -> Unit): Expr` — standalone `{ … }` value.
  - `fun Expr.call(name: String, vararg args: Expr, body: BlockScope.(Expr) -> Unit): Expr` — single named parameter, arity 1.
  - `fun Expr.call(name: String, vararg args: Expr, body: BlockScope.() -> Unit): Expr` — arity 0, emits implicit `it` when the body references it via `it()`. See the `it` note below.
  - Explicit-arity overloads for 2…8 parameters: `fun Expr.call(name: String, vararg args: Expr, body: BlockScope.(Expr, Expr) -> Unit): Expr` and so on, plus the same set for `call(member: MemberName, …)`.
  - `context(b: BlockScope) val it: Expr` — no. **Decision:** implicit `it` is expressed by the arity-0 lambda overload plus a `BlockScope`-scoped property `implicitIt`, exposed to users as `it`: `public val BlockScope.it: Expr`. Because the arity-0 lambda emits no parameter list, `it` renders as the literal `it`.

Parameter names are uniquified against the enclosing `NameScope`, so a lambda parameter never shadows an outer binding silently.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/LambdasTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class LambdasTest {
    private val items = expression("items")

    @Test
    fun `named single parameter`() {
        val out = renderBlock {
            +items.call("map") { item -> +item.prop("name") }
        }
        assertEquals("items.map { item ->\n  item.name\n}\n", out)
    }

    @Test
    fun `implicit it`() {
        val out = renderBlock {
            +items.call("map") { +it.prop("name") }
        }
        assertEquals("items.map {\n  it.name\n}\n", out)
    }

    @Test
    fun `two parameters with a leading value argument`() {
        val out = renderBlock {
            +items.call("fold", 0.lit) { acc, x -> +(acc + x) }
        }
        assertEquals("items.fold(0) { acc, x ->\n  acc + x\n}\n", out)
    }

    @Test
    fun `standalone lambda value`() {
        val out = renderBlock {
            `val`("f", init = lambda { +call("calculate") })
        }
        assertEquals("val f = {\n  calculate()\n}\n", out)
    }

    @Test
    fun `lambda parameter names are uniquified against the enclosing scope`() {
        val out = renderBlock {
            `val`("item", init = 1.lit)
            +items.call("map") { item -> +item }
        }
        assertEquals("val item = 1\nitems.map { item2 ->\n  item2\n}\n", out)
    }
}
```

The exact whitespace above is what KotlinPoet produces for `add("{ %L ->\n").indent()…unindent().add("}")` — two-space indent, closing brace on its own line. Run the test and, if the rendering differs in trailing whitespace only, adjust the expected strings to the actual output *after* confirming the generated code is valid Kotlin.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LambdasTest*'`
Expected: FAIL — no `call` overload taking a body.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Lambdas.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

/** The implicit lambda parameter, valid inside a zero-parameter lambda body. */
public val BlockScope.it: Expr
    get() = Expr(CodeBlock.of("it"), scope = id)

internal fun lambdaCode(params: List<String>, body: CodeBlock): CodeBlock =
    CodeBlock.builder()
        .add("{")
        .apply { if (params.isNotEmpty()) add("·%L·->", params.joinToString(",·")) }
        .add("\n")
        .indent()
        .add(body)
        .unindent()
        .add("}")
        .build()

/** Builds a lambda whose body sees [arity] uniquified parameter handles. */
internal fun lambdaOf(
    parent: BlockScope?,
    requested: List<String>,
    body: BlockScope.(List<Expr>) -> Unit,
): CodeBlock {
    val scope = parent?.child("lambda")
        ?: BlockScope(CodeBlock.builder(), NameScope(null), ScopeId(null, "lambda"), mutableListOf(), detached = true)
    val names = requested.map { scope.names.unique(it) }
    val handles = names.map { Expr(CodeBlock.of("%L", it), name = it, scope = scope.id) }
    scope.body(handles)
    scope.flushPending()
    return lambdaCode(names, scope.builder.build())
}

/** A standalone `{ … }` value. */
public fun lambda(body: BlockScope.() -> Unit): Expr =
    Expr(lambdaOf(null, emptyList()) { body() }, prec = Prec.ATOM)

context(b: BlockScope)
public fun lambda(body: BlockScope.() -> Unit): Expr =
    Expr(lambdaOf(b, emptyList()) { body() }, prec = Prec.ATOM)

// --- trailing-lambda call overloads, arities 0..8 ---

public fun Expr.call(name: String, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    Expr(
        CodeBlock.of("%L.%L(%L)·%L", paren(Prec.POSTFIX), name, argList(args), lambdaOf(null, emptyList()) { body() }),
        prec = Prec.POSTFIX,
    )

public fun Expr.call(name: String, vararg args: Expr, body: BlockScope.(Expr) -> Unit): Expr =
    Expr(
        CodeBlock.of(
            "%L.%L(%L)·%L",
            paren(Prec.POSTFIX),
            name,
            argList(args),
            lambdaOf(null, listOf(defaultParamName(this))) { (p) -> body(p) },
        ),
        prec = Prec.POSTFIX,
    )

public fun Expr.call(name: String, vararg args: Expr, body: BlockScope.(Expr, Expr) -> Unit): Expr =
    Expr(
        CodeBlock.of(
            "%L.%L(%L)·%L",
            paren(Prec.POSTFIX),
            name,
            argList(args),
            lambdaOf(null, listOf("acc", "x")) { (a, b2) -> body(a, b2) },
        ),
        prec = Prec.POSTFIX,
    )

// … arities 3..8 follow the same shape, with default names p1..p8 …

/** Default lambda parameter name: singular of the receiver's name, else `item`. */
internal fun defaultParamName(receiver: Expr): String =
    receiver.name?.let(::singularize) ?: singularize(receiver.code.toString().substringAfterLast('.'))
```

Two implementation notes the tests will force you to get right:

1. The overloads above build their lambda with `parent = null`, which loses the enclosing `NameScope` — test 5 (`lambda parameter names are uniquified`) fails. Fix it by making every trailing-lambda `call` overload a `context(b: BlockScope)` function and passing `b` into `lambdaOf`. Do that: it also matches the library-wide rule that anything needing a scope declares it as a context parameter. Keep one non-context copy only if a test needs a lambda outside any block; the `lambda(body)` pair above already covers that.
2. Arity 2's parameter names are hardcoded to `acc`, `x` to match the spec's `fold` example. For arities 3+, use `p1`…`p8`. All are uniquified, and users can rename by binding differently in their own code.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LambdasTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Lambdas.kt src/test/kotlin/dev/asm0dey/poetdsl/LambdasTest.kt
git commit -m "feat: lambdas with arities 0-8 and implicit it"
```

---

### Task 11: Callable references (spec open task 2)

**Files:**
- Create: `docs/spikes/2026-08-13-callable-references.md`
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Refs.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/RefsTest.kt`

**Interfaces:**
- Consumes: `member` (Task 4), `call`/`prop` (Task 9).
- Produces:
  - `fun KFunction<*>.asMemberName(): MemberName` — top-level functions only; throws `IllegalStateException` naming the function when the owner cannot be resolved.
  - `fun Expr.call(ref: KFunction<*>, vararg args: Expr): Expr` — uses only the simple name, qualified by the receiver.
  - `fun Expr.prop(ref: KProperty<*>): Expr` — same.
  - `fun call(ref: KFunction<*>, vararg args: Expr): Expr` — top-level: resolves to `%M`.
  - `fun call(ref: KFunction<*>, vararg args: Expr, body: BlockScope.() -> Unit): Expr` and the 1-parameter body variant, for `call(::lazy) { … }`.

- [ ] **Step 1: Write the spike test that documents what resolves**

`src/test/kotlin/dev/asm0dey/poetdsl/RefsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefsTest {
    @Test
    fun `member function reference contributes a bare name`() {
        val x = expression("x")
        assertEquals("x.isNotEmpty()", x.call(String::isNotEmpty).toString())
    }

    @Test
    fun `property reference contributes a bare name`() {
        val s = expression("s")
        assertEquals("s.length", s.prop(String::length).toString())
    }

    @Test
    fun `top level function reference resolves package and import`() {
        val file = FileSpec.builder("com.example", "Api")
            .addFunction(
                FunSpec.builder("f")
                    .addStatement("%L", call(::topLevelHelper, 1.lit).code)
                    .build(),
            )
            .build()
        assertEquals(
            """
            package com.example

            import dev.asm0dey.poetdsl.topLevelHelper

            public fun f() {
              topLevelHelper(1)
            }

            """.trimIndent(),
            file.toString(),
        )
    }

    @Test
    fun `an unresolvable reference fails with a named error`() {
        val local = { 1 }
        val failure = assertFailsWith<IllegalStateException> { call(local as kotlin.reflect.KFunction<*>) }
        assertEquals(
            "Cannot resolve a MemberName for '<anonymous>': no declaring class. " +
                "Use member(\"pkg\", \"name\") instead.",
            failure.message,
        )
    }
}

fun topLevelHelper(n: Int): Int = n
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*RefsTest*'`
Expected: FAIL — no `call` overload taking a `KFunction`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Refs.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Resolves a top-level function reference to a [MemberName], so `%M` registers the import.
 *
 * Limitations, both documented and both unfixable:
 * - a reference is a **name source only** — `Expr` is untyped, so `someInt.call(String::isNotEmpty)`
 *   compiles and generates invalid Kotlin, exactly like `call("isNotEmpty")` would;
 * - inline functions with reified type parameters (`arrayOf`, `emptyArray`, `typeOf`) cannot be
 *   referenced at all — Kotlin rejects `::arrayOf`. Use `member("kotlin", "arrayOf")`.
 */
public fun KFunction<*>.asMemberName(): MemberName {
    val owner = javaMethod?.declaringClass
        ?: error(
            "Cannot resolve a MemberName for '$name': no declaring class. " +
                "Use member(\"pkg\", \"name\") instead.",
        )
    val packageName = owner.`package`?.name.orEmpty()
    return MemberName(packageName, name)
}

/** `receiver.name(args)` with the name taken from a reference — typo-safe when the API is on the classpath. */
public fun Expr.call(ref: KFunction<*>, vararg args: Expr): Expr = call(ref.name, *args)

/** `receiver.name` with the name taken from a property reference. */
public fun Expr.prop(ref: KProperty<*>): Expr = prop(ref.name)

/** `name(args)` for a top-level function; the import resolves through `%M`. */
public fun call(ref: KFunction<*>, vararg args: Expr): Expr = call(ref.asMemberName(), *args)

context(b: BlockScope)
public fun call(ref: KFunction<*>, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    Expr(
        CodeBlock.of("%M(%L)·%L", ref.asMemberName(), argList(args), lambdaOf(b, emptyList()) { body() }),
        prec = Prec.POSTFIX,
    )
```

- [ ] **Step 4: Run the test, then record the findings**

Run: `./gradlew test --tests '*RefsTest*'`
Expected: PASS, 4 tests. If the error message text differs (a lambda's `KFunction.name` may not be `<anonymous>`), adjust the test to the observed value and keep the "Use member(...) instead." suffix.

`docs/spikes/2026-08-13-callable-references.md`:

```markdown
# Spike: KFunction/KProperty → MemberName (kotlin-reflect 2.4.10)

| Reference kind | Resolves? | Notes |
|---|---|---|
| top-level function (`::topLevelHelper`) | <FILL IN> | package from `javaMethod.declaringClass.package` |
| member function (`String::isNotEmpty`) | <FILL IN> | name only; qualified by the receiver Expr |
| extension function | <FILL IN> | |
| member property (`String::length`) | <FILL IN> | name only |
| top-level property | <FILL IN> | |
| inline + reified (`::arrayOf`) | no | Kotlin rejects the reference at the call site; use `member(...)` |
| local function / lambda | no | throws IllegalStateException naming the function |

Filled in from the observed behaviour of `RefsTest`. Anything marked "no" is documented
in the README as an escape-hatch case.
```

Fill every `<FILL IN>` from real test runs — add a test per row before writing "yes".

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Refs.kt src/test/kotlin/dev/asm0dey/poetdsl/RefsTest.kt docs/spikes
git commit -m "feat: callable reference support with documented limitations"
```

---

### Task 12: Loops — `for`, `while`, `doWhile`

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/LoopsTest.kt`

**Interfaces:**
- Consumes: `BlockScope`, `child`, `flushPending`, `emitCode` (Task 7); `singularize` (Task 6).
- Produces:
  - `context(b: BlockScope) fun `for`(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit)`, alias `forIn`
  - `context(b: BlockScope) fun `while`(condition: Expr, body: BlockScope.() -> Unit)`
  - `context(b: BlockScope) fun doWhile(condition: Expr, body: BlockScope.() -> Unit)`
  - `context(b: BlockScope) fun `break`()`, alias `brk`; `context(b: BlockScope) fun `continue`()`, alias `cont`; `context(b: BlockScope) fun `throw`(value: Expr)`, alias `throwIt`
  - `internal fun BlockScope.controlFlow(head: CodeBlock, body: BlockScope.() -> Unit)` — `beginControlFlow` + nested body + `endControlFlow`, used by every construct here.

Loop variable naming: explicit `name =` wins; otherwise `singularize(items.name)`; otherwise `item`. The result is uniquified against the enclosing scope.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/LoopsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals

class LoopsTest {
    @Test
    fun `for over a named handle singularizes the loop variable`() {
        val out = renderBlock {
            val items = `val`("items", init = call("load"))
            `for`(items) { item -> +item.call("run") }
        }
        assertEquals(
            "val items = load()\nfor (item in items) {\n  item.run()\n}\n",
            out,
        )
    }

    @Test
    fun `explicit name wins`() {
        val out = renderBlock {
            val items = `val`("items", init = call("load"))
            `for`(items, name = "user") { user -> +user }
        }
        assertEquals("val items = load()\nfor (user in items) {\n  user\n}\n", out)
    }

    @Test
    fun `loop variable is uniquified against the enclosing scope`() {
        val out = renderBlock {
            `val`("item", init = 1.lit)
            `for`(expression("items")) { item -> +item }
        }
        assertEquals("val item = 1\nfor (item2 in items) {\n  item2\n}\n", out)
    }

    @Test
    fun `while and doWhile`() {
        val out = renderBlock {
            val n = `var`("n", INT, 0.lit)
            `while`(n lt 10.lit) { n += 1.lit }
            doWhile(n gt 0.lit) { n -= 1.lit }
        }
        assertEquals(
            "var n: kotlin.Int = 0\n" +
                "while (n < 10) {\n  n += 1\n}\n" +
                "do {\n  n -= 1\n} while (n > 0)\n",
            out,
        )
    }

    @Test
    fun `break continue and throw`() {
        val out = renderBlock {
            `for`(expression("items")) {
                `break`()
                `continue`()
                `throw`(call("IllegalStateException", "bad".lit))
            }
        }
        assertEquals(
            "for (item in items) {\n" +
                "  break\n  continue\n  throw IllegalStateException(\"bad\")\n" +
                "}\n",
            out,
        )
    }

    @Test
    fun `aliases match the backticked forms`() {
        assertEquals(
            renderBlock { `for`(expression("xs")) { +it } },
            renderBlock { forIn(expression("xs")) { +it } },
        )
    }
}
```

Note the last test uses `it` — the `for` body is `BlockScope.(Expr) -> Unit`, so the parameter is named `it` by Kotlin when the lambda declares no parameter name. That is Kotlin's own `it`, not the DSL's, and it refers to the loop handle. Both spellings render `item`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LoopsTest*'`
Expected: FAIL — `Unresolved reference: for`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock

/** Emits `head { body }` using KotlinPoet's control-flow API, which owns the indentation. */
internal fun BlockScope.controlFlow(head: CodeBlock, label: String, body: BlockScope.() -> Unit) {
    flushPending()
    builder.beginControlFlow("%L", head)
    val inner = child(label)
    inner.body()
    inner.flushPending()
    builder.add(inner.builder.build())
    builder.endControlFlow()
}

/**
 * `for (name in items) { … }`.
 *
 * @param name the loop variable. Defaults to the singular of the iterable handle's name
 *   (`items` → `item`, `users` → `user`), falling back to `item`. Always uniquified.
 */
context(b: BlockScope)
public fun `for`(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) {
    b.checkOwned(items)
    val chosen = b.names.unique(name ?: items.name?.let(::singularize) ?: "item")
    b.flushPending()
    b.builder.beginControlFlow("for·(%L·in·%L)", chosen, items.code)
    val inner = b.child("for")
    inner.body(Expr(CodeBlock.of("%L", chosen), name = chosen, scope = inner.id))
    inner.flushPending()
    b.builder.add(inner.builder.build())
    b.builder.endControlFlow()
}

/** Alias of [`for`]. */
context(b: BlockScope)
public fun forIn(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) {
    `for`(items, name, body)
}

/** `while (condition) { … }`. */
context(b: BlockScope)
public fun `while`(condition: Expr, body: BlockScope.() -> Unit) {
    b.checkOwned(condition)
    b.controlFlow(CodeBlock.of("while·(%L)", condition.code), "while", body)
}

/** `do { … } while (condition)`. */
context(b: BlockScope)
public fun doWhile(condition: Expr, body: BlockScope.() -> Unit) {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("do")
    val inner = b.child("doWhile")
    inner.body()
    inner.flushPending()
    b.builder.add(inner.builder.build())
    b.builder.endControlFlow("while·(%L)", condition.code)
}

/** `break`. */
context(b: BlockScope)
public fun `break`() {
    b.emitCode(CodeBlock.of("break"))
}

/** Alias of [`break`]. */
context(b: BlockScope)
public fun brk() {
    `break`()
}

/** `continue`. */
context(b: BlockScope)
public fun `continue`() {
    b.emitCode(CodeBlock.of("continue"))
}

/** Alias of [`continue`]. */
context(b: BlockScope)
public fun cont() {
    `continue`()
}

/** `throw value`. */
context(b: BlockScope)
public fun `throw`(value: Expr) {
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("throw·%L", value.code))
}

/** Alias of [`throw`]. */
context(b: BlockScope)
public fun throwIt(value: Expr) {
    `throw`(value)
}
```

`CodeBlock.Builder.endControlFlow(format, vararg args)` may not exist in KotlinPoet 2.3.0 — it has a no-argument `endControlFlow()`. If so, implement `doWhile` by ending with `unindent()` and `add("}·while·(%L)\n", condition.code)` instead of `endControlFlow`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LoopsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/dev/asm0dey/poetdsl/LoopsTest.kt
git commit -m "feat: for, while, doWhile, break, continue, throw"
```

---

### Task 13: The `if` chain

KotlinPoet's control-flow API is linear, so `` `if` `` emits `beginControlFlow` plus its body and **leaves the block open**, returning an `IfChain`. `BlockScope.pending` holds the open chain; the next emission flushes it, and so does closing the block. `elseIf` / `else` call `nextControlFlow` instead of closing.

**Files:**
- Modify: `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/IfChainTest.kt`

**Interfaces:**
- Consumes: `PendingFlow`, `flushPending`, `controlFlow` (Tasks 7, 12).
- Produces:
  - `class IfChain internal constructor(private val owner: BlockScope) : PendingFlow` with `fun elseIf(condition: Expr, body: BlockScope.() -> Unit): IfChain`, `fun `else`(body: BlockScope.() -> Unit)`, and `override fun close()`.
  - `context(b: BlockScope) fun `if`(condition: Expr, body: BlockScope.() -> Unit): IfChain`, alias `ifThen`.
  - `context(b: BlockScope) fun `return`(value: Expr)`, `context(b: BlockScope) fun `return`()`, aliases `ret` — needed by the tests here and reused by Task 20's return-type inference, which reads `b.returns`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/IfChainTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class IfChainTest {
    private val x = expression("x")

    @Test
    fun `bare if is flushed at block close`() {
        val out = renderBlock {
            `if`(x lt 0.lit) { ret(false.lit) }
        }
        assertEquals("if (x < 0) {\n  return false\n}\n", out)
    }

    @Test
    fun `if is flushed before the next statement`() {
        val out = renderBlock {
            `if`(x lt 0.lit) { ret(false.lit) }
            +call("after")
        }
        assertEquals("if (x < 0) {\n  return false\n}\nafter()\n", out)
    }

    @Test
    fun `full chain`() {
        val out = renderBlock {
            `if`(x lt 0.lit) {
                ret(false.lit)
            }.elseIf(x gt 100.lit) {
                ret(false.lit)
            }.`else` {
                ret(true.lit)
            }
        }
        assertEquals(
            "if (x < 0) {\n  return false\n} else if (x > 100) {\n  return false\n} else {\n  return true\n}\n",
            out,
        )
    }

    @Test
    fun `chain inside a loop`() {
        val out = renderBlock {
            `for`(expression("items")) { item ->
                `if`(item eq nul) { `continue`() }
                +item.call("run")
            }
        }
        assertEquals(
            "for (item in items) {\n  if (item == null) {\n    continue\n  }\n  item.run()\n}\n",
            out,
        )
    }

    @Test
    fun `ifThen is an alias`() {
        assertEquals(
            renderBlock { `if`(x) { +call("a") } },
            renderBlock { ifThen(x) { +call("a") } },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*IfChainTest*'`
Expected: FAIL — `Unresolved reference: if`.

- [ ] **Step 3: Write the implementation**

Append to `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`:

```kotlin
/**
 * An `if` whose block is still open. Emitting anything else in the enclosing scope, or
 * closing that scope, flushes the chain. An unbalanced chain cannot be expressed.
 */
public class IfChain internal constructor(private val owner: BlockScope) : PendingFlow {
    /** `else if (condition) { … }`. */
    public fun elseIf(condition: Expr, body: BlockScope.() -> Unit): IfChain {
        owner.checkOwned(condition)
        owner.builder.nextControlFlow("else·if·(%L)", condition.code)
        owner.runNested("elseIf", body)
        return this
    }

    /** `else { … }`. Closes the chain's last branch but leaves the brace to [close]. */
    public fun `else`(body: BlockScope.() -> Unit) {
        owner.builder.nextControlFlow("else")
        owner.runNested("else", body)
    }

    override fun close() {
        owner.builder.endControlFlow()
    }
}

internal fun BlockScope.runNested(label: String, body: BlockScope.() -> Unit) {
    val inner = child(label)
    inner.body()
    inner.flushPending()
    builder.add(inner.builder.build())
}

/** `if (condition) { … }`, chainable with [IfChain.elseIf] and [IfChain.else]. */
context(b: BlockScope)
public fun `if`(condition: Expr, body: BlockScope.() -> Unit): IfChain {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("if·(%L)", condition.code)
    b.runNested("if", body)
    val chain = IfChain(b)
    b.pending = chain
    return chain
}

/** Alias of [`if`]. */
context(b: BlockScope)
public fun ifThen(condition: Expr, body: BlockScope.() -> Unit): IfChain = `if`(condition, body)

/** `return value`. Records the value's type for return-type inference. */
context(b: BlockScope)
public fun `return`(value: Expr) {
    b.checkOwned(value)
    b.returns += value.type
    b.emitCode(CodeBlock.of("return·%L", value.code))
}

/** `return`. */
context(b: BlockScope)
public fun `return`() {
    b.emitCode(CodeBlock.of("return"))
}

/** Alias of [`return`]. */
context(b: BlockScope)
public fun ret(value: Expr) {
    `return`(value)
}

/** Alias of [`return`]. */
context(b: BlockScope)
public fun ret() {
    `return`()
}
```

Rewrite Task 12's `controlFlow` and `` `for` `` helpers to use `runNested` so there is one nesting implementation, not two.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*IfChainTest*' --tests '*LoopsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/dev/asm0dey/poetdsl/IfChainTest.kt
git commit -m "feat: if/elseIf/else chain with deferred block close"
```

---

### Task 14: `when`

**Files:**
- Modify: `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/WhenTest.kt`

**Interfaces:**
- Consumes: `BlockScope`, `runNested` (Tasks 7, 13).
- Produces:
  - `@BlockDsl class WhenScope internal constructor(internal val owner: BlockScope)` with `fun branch(vararg conditions: Expr, body: BlockScope.() -> Unit)` and `fun `else`(body: BlockScope.() -> Unit)`.
  - `context(b: BlockScope) fun `when`(subject: Expr, body: WhenScope.() -> Unit)`, alias `whenOn`.
  - `context(b: BlockScope) fun whenTrue(body: WhenScope.() -> Unit)` — subjectless `when { … }`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/WhenTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class WhenTest {
    private val subject = expression("subject")

    @Test
    fun `when with single and multiple conditions plus else`() {
        val out = renderBlock {
            `when`(subject) {
                branch(1.lit) { +call("one") }
                branch(2.lit, 3.lit) { +call("few") }
                `else` { +call("many") }
            }
        }
        assertEquals(
            "when (subject) {\n" +
                "  1 -> {\n    one()\n  }\n" +
                "  2, 3 -> {\n    few()\n  }\n" +
                "  else -> {\n    many()\n  }\n" +
                "}\n",
            out,
        )
    }

    @Test
    fun `subjectless when`() {
        val out = renderBlock {
            whenTrue {
                branch(expression("a") lt 0.lit) { +call("neg") }
                `else` { +call("pos") }
            }
        }
        assertEquals(
            "when {\n  a < 0 -> {\n    neg()\n  }\n  else -> {\n    pos()\n  }\n}\n",
            out,
        )
    }

    @Test
    fun `whenOn is an alias`() {
        assertEquals(
            renderBlock { `when`(subject) { branch(1.lit) { +call("a") } } },
            renderBlock { whenOn(subject) { branch(1.lit) { +call("a") } } },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WhenTest*'`
Expected: FAIL — `Unresolved reference: when`.

- [ ] **Step 3: Write the implementation**

Append to `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`:

```kotlin
/** The inside of a `when`. Only branches may be declared here. */
@BlockDsl
public class WhenScope internal constructor(internal val owner: BlockScope) {
    /** One branch. Several conditions are comma-joined, as in Kotlin. */
    public fun branch(vararg conditions: Expr, body: BlockScope.() -> Unit) {
        require(conditions.isNotEmpty()) { "A when branch needs at least one condition; use `else` for the fallback." }
        conditions.forEach(owner::checkOwned)
        val heads = conditions.map { it.code }.reduce { acc, code -> CodeBlock.of("%L,·%L", acc, code) }
        owner.builder.beginControlFlow("%L·->", heads)
        owner.runNested("branch", body)
        owner.builder.endControlFlow()
    }

    /** The `else ->` branch. */
    public fun `else`(body: BlockScope.() -> Unit) {
        owner.builder.beginControlFlow("else·->")
        owner.runNested("else", body)
        owner.builder.endControlFlow()
    }
}

/** `when (subject) { … }`. */
context(b: BlockScope)
public fun `when`(subject: Expr, body: WhenScope.() -> Unit) {
    b.checkOwned(subject)
    b.flushPending()
    b.builder.beginControlFlow("when·(%L)", subject.code)
    WhenScope(b).body()
    b.builder.endControlFlow()
}

/** Alias of [`when`]. */
context(b: BlockScope)
public fun whenOn(subject: Expr, body: WhenScope.() -> Unit) {
    `when`(subject, body)
}

/** Subjectless `when { … }`, where each branch condition is a boolean expression. */
context(b: BlockScope)
public fun whenTrue(body: WhenScope.() -> Unit) {
    b.flushPending()
    b.builder.beginControlFlow("when")
    WhenScope(b).body()
    b.builder.endControlFlow()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WhenTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/dev/asm0dey/poetdsl/WhenTest.kt
git commit -m "feat: when and whenTrue"
```

---

### Task 15: `try` / `catch` / `finally`

**Files:**
- Modify: `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/TryTest.kt`

**Interfaces:**
- Consumes: `BlockScope`, `runNested`, `PendingFlow` (Tasks 7, 13).
- Produces:
  - `class TryChain internal constructor(private val owner: BlockScope) : PendingFlow` with `fun `catch`(name: String, type: TypeName, body: BlockScope.(Expr) -> Unit): TryChain`, `fun finally(body: BlockScope.() -> Unit)`, `override fun close()`.
  - `context(b: BlockScope) fun `try`(body: BlockScope.() -> Unit): TryChain`, alias `tryCatch`.

Same deferred-close mechanism as the `if` chain: `` `try` `` leaves the block open and parks itself in `b.pending`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/TryTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class TryTest {
    @Test
    fun `try catch finally`() {
        val out = renderBlock {
            `try` {
                +call("risky")
            }.`catch`("e", reference<IllegalStateException>()) { e ->
                +call("log", e.call("toString"))
            }.finally {
                +call("cleanup")
            }
        }
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalStateException) {\n" +
                "  log(e.toString())\n} finally {\n  cleanup()\n}\n",
            out,
        )
    }

    @Test
    fun `try with two catches`() {
        val out = renderBlock {
            `try` { +call("risky") }
                .`catch`("e", reference<IllegalArgumentException>()) { +call("a") }
                .`catch`("e", reference<IllegalStateException>()) { +call("b") }
        }
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalArgumentException) {\n  a()\n" +
                "} catch (e2: java.lang.IllegalStateException) {\n  b()\n}\n",
            out,
        )
    }

    @Test
    fun `try is flushed before the next statement`() {
        val out = renderBlock {
            `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
            +call("after")
        }
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\nafter()\n",
            out,
        )
    }
}
```

The second test asserts the exception variable is uniquified to `e2` on the second catch, matching the library-wide naming rule.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TryTest*'`
Expected: FAIL — `Unresolved reference: try`.

- [ ] **Step 3: Write the implementation**

Append to `src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt`:

```kotlin
/** A `try` whose block is still open; flushed by the next emission or at block close. */
public class TryChain internal constructor(private val owner: BlockScope) : PendingFlow {
    /** `catch (name: type) { … }`. The handle is passed to the body. */
    public fun `catch`(name: String, type: TypeName, body: BlockScope.(Expr) -> Unit): TryChain {
        val unique = owner.names.unique(name)
        owner.builder.nextControlFlow("catch·(%L:·%T)", unique, type)
        val inner = owner.child("catch")
        inner.body(Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, inner.id))
        inner.flushPending()
        owner.builder.add(inner.builder.build())
        return this
    }

    /** `finally { … }`. */
    public fun finally(body: BlockScope.() -> Unit) {
        owner.builder.nextControlFlow("finally")
        owner.runNested("finally", body)
    }

    override fun close() {
        owner.builder.endControlFlow()
    }
}

/** `try { … }`, chainable with [TryChain.catch] and [TryChain.finally]. */
context(b: BlockScope)
public fun `try`(body: BlockScope.() -> Unit): TryChain {
    b.flushPending()
    b.builder.beginControlFlow("try")
    b.runNested("try", body)
    val chain = TryChain(b)
    b.pending = chain
    return chain
}

/** Alias of [`try`]. */
context(b: BlockScope)
public fun tryCatch(body: BlockScope.() -> Unit): TryChain = `try`(body)
```

Add the missing imports at the top of `ControlFlow.kt`: `com.squareup.kotlinpoet.TypeName`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TryTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/dev/asm0dey/poetdsl/TryTest.kt
git commit -m "feat: try/catch/finally chain"
```

---

### Task 16: Modifiers

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Modifiers.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/ModifiersTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `@JvmInline value class Modifiers internal constructor(internal val set: Set<KModifier>)`
  - `operator fun KModifier.plus(other: KModifier): Modifiers`
  - `operator fun Modifiers.plus(other: KModifier): Modifiers`
  - `operator fun Modifiers.plus(other: Modifiers): Modifiers`
  - `internal fun Modifiers?.toList(): List<KModifier>`
  - `internal fun KModifier?.toModifiers(): Modifiers`

Order is insertion order (a `LinkedHashSet`), so `SEALED + INTERNAL` renders in the order written; KotlinPoet normalizes modifier order on output anyway.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/ModifiersTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.SUSPEND
import kotlin.test.Test
import kotlin.test.assertEquals

class ModifiersTest {
    @Test
    fun `two modifiers combine`() {
        assertEquals(listOf(SEALED, INTERNAL), (SEALED + INTERNAL).toList())
    }

    @Test
    fun `three modifiers combine`() {
        assertEquals(listOf(PRIVATE, SUSPEND, INTERNAL), (PRIVATE + SUSPEND + INTERNAL).toList())
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(listOf(PRIVATE, SUSPEND), (PRIVATE + SUSPEND + PRIVATE).toList())
    }

    @Test
    fun `null modifiers is an empty list`() {
        assertEquals(emptyList(), (null as Modifiers?).toList())
        assertEquals(listOf(PRIVATE), PRIVATE.toModifiers().toList())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ModifiersTest*'`
Expected: FAIL — `Unresolved reference: Modifiers`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Modifiers.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

/** A set of Kotlin modifiers, built with `+` and always written immediately before the name. */
@JvmInline
public value class Modifiers internal constructor(internal val set: Set<KModifier>)

public operator fun KModifier.plus(other: KModifier): Modifiers =
    Modifiers(linkedSetOf(this, other))

public operator fun Modifiers.plus(other: KModifier): Modifiers =
    Modifiers(LinkedHashSet(set).apply { add(other) })

public operator fun Modifiers.plus(other: Modifiers): Modifiers =
    Modifiers(LinkedHashSet(set).apply { addAll(other.set) })

internal fun Modifiers?.toList(): List<KModifier> = this?.set?.toList().orEmpty()

internal fun KModifier?.toModifiers(): Modifiers = Modifiers(this?.let { linkedSetOf(it) } ?: emptySet())
```

`Modifiers.toList()` is `internal`, but the test lives in the same module, so it resolves.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ModifiersTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Modifiers.kt src/test/kotlin/dev/asm0dey/poetdsl/ModifiersTest.kt
git commit -m "feat: Modifiers value class with plus operators"
```

---

### Task 17: Annotations

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Annotations.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/AnnotationsTest.kt`

**Interfaces:**
- Consumes: `Expr`, `asFormatArg` (Tasks 3, 4).
- Produces:
  - `@JvmInline value class Annotations internal constructor(internal val list: List<AnnotationSpec>)`, with `operator fun Annotations.plus(other: Annotations): Annotations`.
  - `typealias UseSiteTarget = AnnotationSpec.UseSiteTarget` — re-export, including `ALL`, which KotlinPoet 2.3.0 provides natively (no shim needed).
  - `inline fun <reified T : Annotation> annotation(target: UseSiteTarget? = null, vararg args: Expr): Annotations`, alias `ann`
  - `inline fun <reified T : Annotation> annotation(target: UseSiteTarget? = null, vararg named: Pair<String, Expr>): Annotations`, alias `ann`
  - `fun annotation(cls: ClassName, target: UseSiteTarget? = null, vararg args: Expr): Annotations` — for runtime-known types, alias `ann`
  - `interface Annotatable { fun addAnnotation(spec: AnnotationSpec) }` plus `inline fun <reified T : Annotation> Annotatable.annotate(vararg named: Pair<String, Expr>)` and `inline fun <reified T : Annotation> Annotatable.annotate(target: UseSiteTarget? = null, vararg args: Expr)` for the trailing-lambda form.
  - `internal fun buildAnnotation(className: ClassName, target: UseSiteTarget?, positional: List<Expr>, named: List<Pair<String, Expr>>): AnnotationSpec`

The two positional/named overloads differ only in vararg element type, which Kotlin allows because `Expr` and `Pair<String, Expr>` are unrelated types.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/AnnotationsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals

annotation class Email
annotation class SerialName(val value: String)

class AnnotationsTest {
    @Test
    fun `marker annotation`() {
        val spec = annotation<Email>().list.single()
        assertEquals("@dev.asm0dey.poetdsl.Email", spec.toString())
    }

    @Test
    fun `named arguments keep Expr placeholders`() {
        val spec = annotation<SerialName>("value" to "user_name".lit).list.single()
        assertEquals("""@dev.asm0dey.poetdsl.SerialName(value = "user_name")""", spec.toString())
    }

    @Test
    fun `use site target renders`() {
        val spec = annotation<Email>(UseSiteTarget.SET).list.single()
        assertEquals("@set:dev.asm0dey.poetdsl.Email", spec.toString())
    }

    @Test
    fun `the all meta target is available without a shim`() {
        val spec = annotation<Email>(UseSiteTarget.ALL).list.single()
        assertEquals("@all:dev.asm0dey.poetdsl.Email", spec.toString())
    }

    @Test
    fun `annotations combine with plus`() {
        val combined = annotation<Email>(UseSiteTarget.SET) + annotation<SerialName>("value" to "x".lit)
        assertEquals(2, combined.list.size)
    }

    @Test
    fun `runtime known annotation type`() {
        val spec = annotation(ClassName("com.example", "Generated"), args = arrayOf("gen".lit)).list.single()
        assertEquals("""@com.example.Generated("gen")""", spec.toString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnnotationsTest*'`
Expected: FAIL — `Unresolved reference: annotation`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Annotations.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asClassName

/** KotlinPoet's use-site target enum, re-exported. Includes Kotlin 2.2's `@all:` meta-target. */
public typealias UseSiteTarget = AnnotationSpec.UseSiteTarget

/** A list of annotations, combined with `+` and written immediately before the modifiers. */
@JvmInline
public value class Annotations internal constructor(internal val list: List<AnnotationSpec>)

public operator fun Annotations.plus(other: Annotations): Annotations = Annotations(list + other.list)

internal fun buildAnnotation(
    className: ClassName,
    target: UseSiteTarget?,
    positional: List<Expr>,
    named: List<Pair<String, Expr>>,
): AnnotationSpec = AnnotationSpec.builder(className)
    .apply {
        target?.let { useSiteTarget(it) }
        positional.forEach { addMember("%L", it.code) }
        named.forEach { (name, value) -> addMember(CodeBlock.of("%L·=·%L", name, value.code)) }
    }
    .build()

/** An annotation with positional arguments. Arguments are [Expr], so `%T`/`%M` survive and imports resolve. */
public inline fun <reified T : Annotation> annotation(
    target: UseSiteTarget? = null,
    vararg args: Expr,
): Annotations = Annotations(listOf(buildAnnotation(T::class.asClassName(), target, args.toList(), emptyList())))

/** An annotation with named arguments. */
public inline fun <reified T : Annotation> annotation(
    target: UseSiteTarget? = null,
    vararg named: Pair<String, Expr>,
): Annotations = Annotations(listOf(buildAnnotation(T::class.asClassName(), target, emptyList(), named.toList())))

/** An annotation whose type is only known at generation time. */
public fun annotation(
    cls: ClassName,
    target: UseSiteTarget? = null,
    vararg args: Expr,
): Annotations = Annotations(listOf(buildAnnotation(cls, target, args.toList(), emptyList())))

public inline fun <reified T : Annotation> ann(target: UseSiteTarget? = null, vararg args: Expr): Annotations =
    annotation<T>(target, *args)

public inline fun <reified T : Annotation> ann(
    target: UseSiteTarget? = null,
    vararg named: Pair<String, Expr>,
): Annotations = annotation<T>(target, *named)

public fun ann(cls: ClassName, target: UseSiteTarget? = null, vararg args: Expr): Annotations =
    annotation(cls, target, *args)

/** Implemented by every scope, so annotations can also be added from inside a trailing lambda. */
public interface Annotatable {
    public fun addAnnotation(spec: AnnotationSpec)
}

/** Trailing-lambda form: for conditional, computed, or looped annotations. */
public inline fun <reified T : Annotation> Annotatable.annotate(
    target: UseSiteTarget? = null,
    vararg args: Expr,
) {
    annotation<T>(target, *args).list.forEach(::addAnnotation)
}

/** Trailing-lambda form with named arguments. */
public inline fun <reified T : Annotation> Annotatable.annotate(
    target: UseSiteTarget? = null,
    vararg named: Pair<String, Expr>,
) {
    annotation<T>(target, *named).list.forEach(::addAnnotation)
}

/** Adds every annotation in this list. */
public fun Annotatable.addAll(annotations: Annotations?) {
    annotations?.list?.forEach(::addAnnotation)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnnotationsTest*'`
Expected: PASS, 6 tests. `@all:` rendering confirms spec open task 1 is closed in code, not just on paper.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Annotations.kt src/test/kotlin/dev/asm0dey/poetdsl/AnnotationsTest.kt
git commit -m "feat: annotations with use-site targets including @all"
```

---

### Task 18: `FileScope`

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/FileScope.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/FileScopeTest.kt`

**Interfaces:**
- Consumes: `Annotatable`, `Annotations` (Task 17); `NameScope`, `ScopeId` (Task 6).
- Produces:
  - `@FileDsl class FileScope internal constructor(internal val builder: FileSpec.Builder, internal val names: NameScope, internal val id: ScopeId) : Annotatable` — `addAnnotation` defaults the use-site target to `FILE` when none was given.
  - `fun file(packageName: String, fileName: String, body: FileScope.() -> Unit): FileSpec`
  - `context(f: FileScope) operator fun FunSpec.unaryPlus()` / `invoke()` / `emit` / `add`, and the same four for `TypeSpec` and `PropertySpec`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/FileScopeTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class FileScopeTest {
    @Test
    fun `empty file renders its package`() {
        val out = file("com.example", "Api") { }.toString()
        assertEquals("package com.example\n", out)
    }

    @Test
    fun `a prebuilt FunSpec can be emitted four ways`() {
        val f = FunSpec.builder("helper").build()
        val plus = file("com.example", "Api") { +f }.toString()
        assertEquals(plus, file("com.example", "Api") { f() }.toString())
        assertEquals(plus, file("com.example", "Api") { emit(f) }.toString())
        assertEquals(plus, file("com.example", "Api") { add(f) }.toString())
        assertEquals(
            """
            package com.example

            public fun helper() {
            }

            """.trimIndent(),
            plus,
        )
    }

    @Test
    fun `file level annotation defaults to the FILE target`() {
        val out = file("com.example", "Api") {
            annotate<JvmName>("value" to "UserKt".lit)
        }.toString()
        assertEquals(
            """
            @file:JvmName(value = "UserKt")

            package com.example

            """.trimIndent() + "\n",
            out,
        )
    }
}
```

If KotlinPoet renders the file annotation block with different blank-line placement, adjust the expected string to the observed output — the assertion that matters is `@file:JvmName`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FileScopeTest*'`
Expected: FAIL — `Unresolved reference: file`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/FileScope.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/** The file-level scope. Annotations added here default to the `@file:` use-site target. */
@FileDsl
public class FileScope internal constructor(
    internal val builder: FileSpec.Builder,
    internal val names: NameScope,
    internal val id: ScopeId,
) : Annotatable {
    override fun addAnnotation(spec: AnnotationSpec) {
        val targeted = if (spec.useSiteTarget == null) {
            spec.toBuilder().useSiteTarget(UseSiteTarget.FILE).build()
        } else {
            spec
        }
        builder.addAnnotation(targeted)
    }
}

/** Builds a `.kt` file. */
public fun file(packageName: String, fileName: String, body: FileScope.() -> Unit): FileSpec {
    val scope = FileScope(FileSpec.builder(packageName, fileName), NameScope(null), ScopeId(null, "file"))
    scope.body()
    return scope.builder.build()
}

context(f: FileScope)
public operator fun FunSpec.unaryPlus() {
    f.builder.addFunction(this)
}

context(f: FileScope)
public operator fun FunSpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: FunSpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: FunSpec) {
    +spec
}

context(f: FileScope)
public operator fun TypeSpec.unaryPlus() {
    f.builder.addType(this)
}

context(f: FileScope)
public operator fun TypeSpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: TypeSpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: TypeSpec) {
    +spec
}

context(f: FileScope)
public operator fun PropertySpec.unaryPlus() {
    f.builder.addProperty(this)
}

context(f: FileScope)
public operator fun PropertySpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: PropertySpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: PropertySpec) {
    +spec
}
```

`invoke()` is deliberately defined on `Stmt`, `FunSpec`, `TypeSpec` and `PropertySpec` only — never on `Expr` — so `f()` can only ever mean "emit this declaration". A generated call is always `call("f")` or `x.call("f")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FileScopeTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/FileScope.kt src/test/kotlin/dev/asm0dey/poetdsl/FileScopeTest.kt
git commit -m "feat: file scope with file-target annotations and spec emission"
```

---

### Task 19: `TypeScope` — classes, objects, interfaces, properties, constructor parameters

**Deliberate deviation from the spec, flag it in the README:** the spec says "a property handle referenced from a member body where a local shadows it is automatically qualified as `this.name`". This plan achieves the same guarantee by a cheaper route: a member body's `NameScope` is a **child of the type's** `NameScope`, so a local that would shadow a property is uniquified to `name2` at declaration time and shadowing never arises. No `this.` qualification machinery, one mechanism instead of two, and the generated code still references the right binding. If the user wants literal `this.name` output, say so and this task grows a rendering hook.

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/TypeScope.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/TypeScopeTest.kt`

**Interfaces:**
- Consumes: `Annotatable`, `Annotations`, `addAll` (Task 17); `Modifiers`, `toList`, `toModifiers` (Task 16); `FileScope` (Task 18).
- Produces:
  - `@TypeDsl class TypeScope internal constructor(internal val builder: TypeSpec.Builder, internal val names: NameScope, internal val id: ScopeId) : Annotatable`, holding an internal `ctor: FunSpec.Builder` created lazily and attached as the primary constructor at build time.
  - `context(f: FileScope) fun `class`(…)` in the **six-variant shape**: annotations present or absent (non-null, positional, first) × modifiers absent / single `KModifier` / `Modifiers` (non-null, positional, second). Alias `klass`. The same six variants exist for `object`, `interface`, the property-level `` `val` ``/`` `var` ``, and their aliases.

    Why six hand-written variants instead of one function with defaulted nullable parameters: the spec's positional style is `` `class`(ann<Serializable>(), DATA, "User") `` and `` `class`(SEALED + INTERNAL, "Repo") ``. A single signature cannot accept both `DATA` (a `KModifier`) and `SEALED + INTERNAL` (a `Modifiers`) in the same slot, and defaulted nullable parameters would make `` `class`(name = "C") { } `` match every variant at once — an ambiguity error. Presence-and-type-distinguished variants resolve cleanly. Two of the six:

    ```kotlin
    context(f: FileScope)
    public fun `class`(name: String, body: TypeScope.() -> Unit) {
        f.builder.addType(f.type(TypeSpec.classBuilder(name), null, null, body))
    }

    context(f: FileScope)
    public fun `class`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
        f.builder.addType(f.type(TypeSpec.classBuilder(name), null, modifiers, body))
    }
    ```

    The other four differ only in which of `annotations: Annotations` and `modifiers: KModifier`/`Modifiers` they declare; every one of them is a single delegating line to the same private `f.type(...)`/`t.property(...)` implementation. Write all six for each construct — no `// … repeat …` comments in the source.
  - `context(f: FileScope) fun `object`(…)`, `context(f: FileScope) fun `interface`(…)` with the same variant set.
  - `context(t: TypeScope) fun `val`(annotations: Annotations? = null, modifiers: Modifiers? = null, name: String, type: TypeName, init: Expr? = null, by: Expr? = null, body: PropertyScope.() -> Unit = {}): Expr`, alias `property`; and the `` `var` `` twin.
  - `context(t: TypeScope) fun constructorParam(kind: KModifier? = null, annotations: Annotations? = null, name: String, type: TypeName): Expr`, alias `ctorParam`. `kind` is `VAL`, `VAR`, or null for a plain parameter.
  - `@TypeDsl class PropertyScope internal constructor(internal val builder: PropertySpec.Builder) : Annotatable` for the trailing-lambda annotation form.
  - `fun typeSpec(modifiers: Modifiers? = null, name: String, body: TypeScope.() -> Unit): TypeSpec` and `fun propertySpec(name: String, type: TypeName, body: PropertyScope.() -> Unit): PropertySpec` — detached builders.

KotlinPoet requires an explicit type on every property, so `type` is non-null here even where Kotlin would infer it. That is why `by = lazy { … }` still needs a declared type.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/TypeScopeTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.VAL
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeScopeTest {
    @Test
    fun `data class with constructor parameters`() {
        val out = file("com.example", "User") {
            `class`(modifiers = DATA.toModifiers(), name = "User") {
                constructorParam(VAL, name = "username", type = STRING)
                constructorParam(VAL, name = "email", type = STRING)
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
    fun `single modifier and modifier set both resolve positionally`() {
        val positional = file("com.example", "User") {
            `class`(DATA, "User") { constructorParam(VAL, name = "a", type = STRING) }
        }.toString()
        assertEquals(
            file("com.example", "User") {
                `class`(modifiers = DATA.toModifiers(), name = "User") {
                    constructorParam(VAL, name = "a", type = STRING)
                }
            }.toString(),
            positional,
        )
    }

    @Test
    fun `modifiers combine before the name`() {
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
    fun `property with initializer and with delegate`() {
        val out = file("com.example", "User") {
            `class`(name = "User") {
                `val`(name = "secondaryEmail", type = STRING.copy(nullable = true), init = nul)
                `val`(
                    name = "x",
                    type = STRING,
                    by = call(member("kotlin", "lazy")) { +call("calculate") },
                )
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String
            import kotlin.lazy

            public class User {
              public val secondaryEmail: String? = null

              public val x: String by lazy {
                calculate()
              }
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `annotations in parameter form and trailing lambda form both apply`() {
        val out = file("com.example", "User") {
            `class`(name = "User") {
                `val`(annotations = annotation<Email>(), name = "a", type = STRING, init = "x".lit) {
                    annotate<SerialName>("value" to "a_field".lit)
                }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import dev.asm0dey.poetdsl.Email
            import dev.asm0dey.poetdsl.SerialName
            import kotlin.String

            public class User {
              @Email
              @SerialName(value = "a_field")
              public val a: String = "x"
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `constructor parameter handles are visible to sibling members`() {
        val out = file("com.example", "User") {
            `class`(name = "User") {
                val username = constructorParam(VAL, name = "username", type = STRING)
                `val`(name = "shout", type = STRING, init = username.call("uppercase"))
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class User(
              public val username: String,
            ) {
              public val shout: String = username.uppercase()
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `typeSpec builds a detached spec`() {
        val spec = typeSpec(name = "Helper") { }
        val out = file("com.example", "Api") { +spec }.toString()
        assertEquals(
            """
            package com.example

            public class Helper

            """.trimIndent(),
            out,
        )
    }
}
```

Expected output formatting (blank lines between properties, trailing commas in the constructor) is KotlinPoet's; run the test and align the expected strings with the observed rendering before assuming a bug.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TypeScopeTest*'`
Expected: FAIL — `Unresolved reference: class`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/TypeScope.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/** The type-level scope: members are declared here. */
@TypeDsl
public class TypeScope internal constructor(
    internal val builder: TypeSpec.Builder,
    internal val names: NameScope,
    internal val id: ScopeId,
) : Annotatable {
    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(spec)
    }

    internal fun finish(): TypeSpec {
        if (hasCtor) builder.primaryConstructor(ctor.build())
        return builder.build()
    }
}

/** The property-level scope, used only for the trailing-lambda annotation form. */
@TypeDsl
public class PropertyScope internal constructor(internal val builder: PropertySpec.Builder) : Annotatable {
    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(spec)
    }
}

private fun FileScope.type(
    kind: TypeSpec.Builder,
    annotations: Annotations?,
    modifiers: Modifiers?,
    body: TypeScope.() -> Unit,
): TypeSpec {
    val scope = TypeScope(kind.addModifiers(modifiers.toList()), names.child(), id.child("type"))
    scope.addAll(annotations)
    scope.body()
    return scope.finish()
}

/** `class Name { … }`. */
context(f: FileScope)
public fun `class`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    body: TypeScope.() -> Unit,
) {
    f.builder.addType(f.type(TypeSpec.classBuilder(name), annotations, modifiers, body))
}

/** Alias of [`class`]. */
context(f: FileScope)
public fun klass(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    body: TypeScope.() -> Unit,
) {
    `class`(annotations, modifiers, name, body)
}

/** `object Name { … }`. */
context(f: FileScope)
public fun `object`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    body: TypeScope.() -> Unit,
) {
    f.builder.addType(f.type(TypeSpec.objectBuilder(name), annotations, modifiers, body))
}

/** `interface Name { … }`. */
context(f: FileScope)
public fun `interface`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    body: TypeScope.() -> Unit,
) {
    f.builder.addType(f.type(TypeSpec.interfaceBuilder(name), annotations, modifiers, body))
}

private fun TypeScope.property(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName,
    init: Expr?,
    by: Expr?,
    body: PropertyScope.() -> Unit,
): Expr {
    require(init == null || by == null) { "Property '$name' cannot have both an initializer and a delegate." }
    val unique = names.unique(name)
    val spec = PropertySpec.builder(unique, type, modifiers.toList()).mutable(mutable)
    init?.let { spec.initializer("%L", it.code) }
    by?.let { spec.delegate("%L", it.code) }
    val scope = PropertyScope(spec)
    scope.addAll(annotations)
    scope.body()
    builder.addProperty(spec.build())
    return Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id)
}

/** A read-only property. Returns a handle usable by every sibling member. */
context(t: TypeScope)
public fun `val`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
    body: PropertyScope.() -> Unit = {},
): Expr = t.property(false, annotations, modifiers, name, type, init, by, body)

/** Alias of the property-level [`val`]. Note: `prop` is property *access* on an [Expr], a different thing. */
context(t: TypeScope)
public fun property(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
    body: PropertyScope.() -> Unit = {},
): Expr = `val`(annotations, modifiers, name, type, init, by, body)

/** A mutable property. */
context(t: TypeScope)
public fun `var`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
    body: PropertyScope.() -> Unit = {},
): Expr = t.property(true, annotations, modifiers, name, type, init, by, body)

/**
 * Adds a parameter to the primary constructor. `VAL`/`VAR` also add the matching property;
 * null makes it a plain parameter. Returns a handle visible to every sibling member —
 * no nesting, no arity ceiling.
 */
context(t: TypeScope)
public fun constructorParam(
    kind: KModifier? = null,
    annotations: Annotations? = null,
    name: String,
    type: TypeName,
): Expr {
    require(kind == null || kind == KModifier.VAL || kind == KModifier.VAR) {
        "constructorParam kind must be VAL, VAR or null, was $kind."
    }
    val unique = t.names.unique(name)
    val param = ParameterSpec.builder(unique, type)
        .apply { annotations?.list?.forEach { addAnnotation(it) } }
        .build()
    t.ctor.addParameter(param)
    t.hasCtor = true
    if (kind != null) {
        t.builder.addProperty(
            PropertySpec.builder(unique, type)
                .mutable(kind == KModifier.VAR)
                .initializer("%N", param)
                .build(),
        )
    }
    return Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, t.id)
}

/** Alias of [constructorParam]. */
context(t: TypeScope)
public fun ctorParam(
    kind: KModifier? = null,
    annotations: Annotations? = null,
    name: String,
    type: TypeName,
): Expr = constructorParam(kind, annotations, name, type)

/** Detached type builder; returns a KotlinPoet spec, so interop with hand-written KotlinPoet is free. */
public fun typeSpec(modifiers: Modifiers? = null, name: String, body: TypeScope.() -> Unit): TypeSpec {
    val scope = TypeScope(
        TypeSpec.classBuilder(name).addModifiers(modifiers.toList()),
        NameScope(null),
        ScopeId(null, "type"),
    )
    scope.body()
    return scope.finish()
}

/** Detached property builder. */
public fun propertySpec(
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
    body: PropertyScope.() -> Unit = {},
): PropertySpec {
    val spec = PropertySpec.builder(name, type, modifiers.toList())
    init?.let { spec.initializer("%L", it.code) }
    by?.let { spec.delegate("%L", it.code) }
    PropertyScope(spec).body()
    return spec.build()
}
```

Two things the tests will pin down: `TypeSpec.classBuilder(name).addModifiers(list)` takes an `Iterable<KModifier>` overload, and `PropertySpec.builder(name, type, modifiers)` takes a `vararg`/`Iterable` pair — use whichever resolves. `f.type(...)` is a private extension on `FileScope`, so it can read `f.names`/`f.id` without a context parameter.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TypeScopeTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/TypeScope.kt src/test/kotlin/dev/asm0dey/poetdsl/TypeScopeTest.kt
git commit -m "feat: type scope with classes, properties and constructor parameters"
```

---

### Task 20: Functions, parameters, and return-type inference

Arities 0–3 are hand-written here, in the merged `annotations: Annotations? = null, modifiers: Modifiers? = null` form, so the *semantics* — parameter handles, return-type inference, scope nesting — are settled and tested against something small. **Task 21 deletes all of them** and generates arities 0–26 in the six-variant shape instead; `buildFun`, `param`, the list form, and the detached builders survive unchanged. Do not treat this task's signatures as the final public API.

**Files:**
- Create: `src/main/kotlin/dev/asm0dey/poetdsl/Declarations.kt`
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/FunctionsTest.kt`

**Interfaces:**
- Consumes: `TypeScope` (Task 19), `FileScope` (Task 18), `BlockScope` (Task 7), `Modifiers` (Task 16), `Annotations` (Task 17).
- Produces:
  - `fun param(name: String, type: TypeName): ParameterSpec`
  - `internal fun buildFun(name: String, annotations: Annotations?, modifiers: Modifiers?, params: List<ParameterSpec>, returns: TypeName?, parentNames: NameScope?, parentId: ScopeId?, body: BlockScope.(List<Expr>) -> Unit): FunSpec` — the single implementation every arity overload and the list form delegate to. It performs return-type inference.
  - `context(t: TypeScope) fun `fun`(annotations: Annotations? = null, modifiers: Modifiers? = null, name: String, returns: TypeName? = null, body: BlockScope.() -> Unit)` and the 1-, 2-, 3-parameter variants; alias `func`. Same set `context(f: FileScope)` for top-level functions.
  - `context(t: TypeScope) fun `fun`(annotations: Annotations? = null, modifiers: Modifiers? = null, name: String, params: List<ParameterSpec>, returns: TypeName? = null, body: BlockScope.(List<Expr>) -> Unit)` — the list form, for >26 parameters or dynamically sized lists.
  - `fun funSpec(modifiers: Modifiers? = null, name: String, returns: TypeName? = null, body: BlockScope.() -> Unit): FunSpec` — detached, plus the 1–3 parameter variants.

**Return-type inference rule:** if `returns` is given, use it. Otherwise, if the body recorded no `return`, the function returns `Unit` and the type is omitted from the output. Otherwise, if every recorded return type is known and they agree, use it. Otherwise throw `IllegalStateException` naming the function and the fix.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/FunctionsTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.SUSPEND
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionsTest {
    @Test
    fun `no parameters no return`() {
        val out = file("com.example", "Api") {
            `fun`(name = "noop") { +call("work") }
        }.toString()
        assertEquals(
            """
            package com.example

            public fun noop() {
              work()
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `one parameter with modifiers`() {
        val out = file("com.example", "Api") {
            `fun`(modifiers = PRIVATE + SUSPEND, name = "greet", p1 = param("greeting", STRING)) { greeting ->
                +call("println", greeting)
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            private suspend fun greet(greeting: String) {
              println(greeting)
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `return type is inferred from a literal`() {
        val out = file("com.example", "Api") {
            `fun`(name = "one") { ret(1.lit) }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun one(): Int = 1

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `return type is inferred from a parameter`() {
        val out = file("com.example", "Api") {
            `fun`(name = "echo", p1 = param("s", STRING)) { s -> ret(s) }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun echo(s: String): String = s

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `an uninferable return type is a named build error`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "Api") {
                `fun`(name = "mystery", p1 = param("x", STRING)) { x -> ret(x.call("foo")) }
            }
        }
        assertEquals(
            "Cannot infer the return type of 'mystery': the returned expression's type is unknown. " +
                "Pass returns = … explicitly.",
            failure.message,
        )
    }

    @Test
    fun `explicit returns wins`() {
        val out = file("com.example", "Api") {
            `fun`(name = "mystery", returns = INT, p1 = param("x", STRING)) { x -> ret(x.call("foo")) }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public fun mystery(x: String): Int = x.foo()

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `list form for dynamically sized parameter lists`() {
        val out = file("com.example", "Api") {
            `fun`(name = "wide", params = listOf(param("a", INT), param("b", INT))) { ps ->
                +(ps[0] + ps[1])
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun wide(a: Int, b: Int) {
              a + b
            }

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `funSpec builds a detached spec`() {
        val f = funSpec(name = "helper") { ret(1.lit) }
        val out = file("com.example", "Api") { +f }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public fun helper(): Int = 1

            """.trimIndent(),
            out,
        )
    }
}
```

KotlinPoet collapses a single-`return` body into an expression body (`= 1`); if it does not in 2.3.0, the expected strings become block bodies. Check the first failing diff before changing implementation.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FunctionsTest*'`
Expected: FAIL — `Unresolved reference: param`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/dev/asm0dey/poetdsl/Declarations.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName

/** A function parameter. Type-position annotations come free: `param("x", INT.annotated<Positive>())`. */
public fun param(name: String, type: TypeName): ParameterSpec = ParameterSpec.builder(name, type).build()

internal fun buildFun(
    name: String,
    annotations: Annotations?,
    modifiers: Modifiers?,
    params: List<ParameterSpec>,
    returns: TypeName?,
    parentNames: NameScope?,
    parentId: ScopeId?,
    body: BlockScope.(List<Expr>) -> Unit,
): FunSpec {
    val names = (parentNames ?: NameScope(null)).child()
    val id = (parentId ?: ScopeId(null, "root")).child("fun($name)")
    val recorded = mutableListOf<TypeName?>()
    val scope = BlockScope(CodeBlock.builder(), names, id, recorded)

    val handles = params.map { p ->
        names.declare(p.name)
        Expr(CodeBlock.of("%N", p), p.type, Prec.ATOM, p.name, id)
    }
    scope.body(handles)
    scope.flushPending()

    val returnType = when {
        returns != null -> returns
        recorded.isEmpty() -> null
        recorded.all { it != null } && recorded.distinct().size == 1 -> recorded.first()
        else -> error(
            "Cannot infer the return type of '$name': the returned expression's type is unknown. " +
                "Pass returns = … explicitly.",
        )
    }

    return FunSpec.builder(name)
        .apply {
            annotations?.list?.forEach { addAnnotation(it) }
            addModifiers(modifiers.toList())
            params.forEach { addParameter(it) }
            returnType?.let { returns(it) }
            addCode(scope.builder.build())
        }
        .build()
}

// --- top-level functions ---

context(f: FileScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
) {
    f.builder.addFunction(buildFun(name, annotations, modifiers, emptyList(), returns, f.names, f.id) { body() })
}

context(f: FileScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
) {
    f.builder.addFunction(
        buildFun(name, annotations, modifiers, listOf(p1), returns, f.names, f.id) { (a) -> body(a) },
    )
}

context(f: FileScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    p2: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr, Expr) -> Unit,
) {
    f.builder.addFunction(
        buildFun(name, annotations, modifiers, listOf(p1, p2), returns, f.names, f.id) { (a, b2) -> body(a, b2) },
    )
}

context(f: FileScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    p2: ParameterSpec,
    p3: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr, Expr, Expr) -> Unit,
) {
    f.builder.addFunction(
        buildFun(name, annotations, modifiers, listOf(p1, p2, p3), returns, f.names, f.id) { (a, b2, c) ->
            body(a, b2, c)
        },
    )
}

/** The list form: for more than 26 parameters, or a parameter list computed at generation time. */
context(f: FileScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    params: List<ParameterSpec>,
    returns: TypeName? = null,
    body: BlockScope.(List<Expr>) -> Unit,
) {
    f.builder.addFunction(buildFun(name, annotations, modifiers, params, returns, f.names, f.id, body))
}

/** Alias of [`fun`]. */
context(f: FileScope)
public fun func(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
) {
    `fun`(annotations, modifiers, name, returns, body)
}

// --- member functions: the same set, with context(t: TypeScope) and t.builder.addFunction(...) ---

context(t: TypeScope)
public fun `fun`(
    annotations: Annotations? = null,
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
) {
    t.builder.addFunction(buildFun(name, annotations, modifiers, emptyList(), returns, t.names, t.id) { body() })
}

// … repeat the 1-, 2-, 3-parameter and list variants for TypeScope, plus the `func` alias …

// --- detached builders ---

public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
): FunSpec = buildFun(name, null, modifiers, emptyList(), returns, null, null) { body() }

public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
): FunSpec = buildFun(name, null, modifiers, listOf(p1), returns, null, null) { (a) -> body(a) }
```

Write out the `TypeScope` variants in full — do not leave the `// … repeat …` comment in the source. They are mechanically identical to the `FileScope` ones except for the context parameter and the `addFunction` target.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FunctionsTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Add the member-function test and re-run**

Append to `FunctionsTest`:

```kotlin
    @Test
    fun `member function sees constructor parameter handles`() {
        val out = file("com.example", "User") {
            `class`(name = "User") {
                val username = constructorParam(com.squareup.kotlinpoet.KModifier.VAL, name = "username", type = STRING)
                `fun`(modifiers = PRIVATE.toModifiers(), name = "greet", p1 = param("greeting", STRING)) { greeting ->
                    +call("println", greeting)
                    +call("println", username)
                }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.String

            public class User(
              public val username: String,
            ) {
              private fun greet(greeting: String) {
                println(greeting)
                println(username)
              }
            }

            """.trimIndent(),
            out,
        )
    }
```

Run: `./gradlew test --tests '*FunctionsTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/asm0dey/poetdsl/Declarations.kt src/test/kotlin/dev/asm0dey/poetdsl/FunctionsTest.kt
git commit -m "feat: function declarations with return-type inference"
```

---

### Task 21: `buildSrc` arity generator

Parameters are lambda-bound, so they cannot be `vararg` — the lambda's arity must match. Arities 0–26 in three modifier variants (none, single `KModifier`, `Modifiers`) and two annotation variants gives ≈162 overloads per family. Kotlin removed the `Function22` ceiling in 1.3, so arities above 22 use big-arity `FunctionN`; the boxing cost is irrelevant at generation time. The generator is written with plain KotlinPoet — no bootstrap circularity.

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/settings.gradle.kts`
- Create: `buildSrc/src/main/kotlin/ArityGenerator.kt`
- Modify: `build.gradle.kts` (register the task, wire the output into the main source set)
- Delete from `src/main/kotlin/dev/asm0dey/poetdsl/Declarations.kt`: the hand-written arity 1–3 overloads (arity 0, the list form, `param`, `buildFun` and the detached builders stay hand-written)
- Test: `src/test/kotlin/dev/asm0dey/poetdsl/ArityTest.kt`

**Interfaces:**
- Consumes: `buildFun`, `param` (Task 20).
- Produces: `build/generated/source/dsl/dev/asm0dey/poetdsl/FunArity.kt` and `CtorArity.kt`, compiled as part of `main`. Generated signatures match Task 20's hand-written ones exactly, so nothing else changes.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/asm0dey/poetdsl/ArityTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArityTest {
    private fun params(n: Int) = (1..n).map { param("p$it", INT) }

    @Test
    fun `arity 22 compiles and renders`() {
        val ps = params(22)
        val out = file("com.example", "Api") {
            `fun`(
                name = "wide22",
                p1 = ps[0], p2 = ps[1], p3 = ps[2], p4 = ps[3], p5 = ps[4], p6 = ps[5],
                p7 = ps[6], p8 = ps[7], p9 = ps[8], p10 = ps[9], p11 = ps[10], p12 = ps[11],
                p13 = ps[12], p14 = ps[13], p15 = ps[14], p16 = ps[15], p17 = ps[16], p18 = ps[17],
                p19 = ps[18], p20 = ps[19], p21 = ps[20], p22 = ps[21],
            ) { a, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> +a }
        }.toString()
        assertTrue("public fun wide22(" in out)
        assertTrue("p22: Int" in out)
    }

    @Test
    fun `arity 26 is the top of the generated range`() {
        val out = file("com.example", "Api") {
            `fun`(name = "wide26", params = params(26)) { ps -> +ps[25] }
        }.toString()
        assertTrue("p26: Int" in out)
    }

    @Test
    fun `constructor family generates the same arities`() {
        val out = file("com.example", "User") {
            `class`(name = "User") {
                `constructor`(p1 = param("a", INT), p2 = param("b", INT)) { a, b -> +call("init", a, b) }
            }
        }.toString()
        assertTrue("public constructor(" in out)
    }

    @Test
    fun `arity 0 and the list form still work`() {
        assertEquals(
            file("com.example", "Api") { `fun`(name = "n") { } }.toString(),
            file("com.example", "Api") { `fun`(name = "n", params = emptyList()) { } }.toString(),
        )
    }
}
```

Writing the 22-argument call by hand is the point: it proves the generated overload exists and its lambda arity lines up.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ArityTest*'`
Expected: FAIL — no overload with `p4`, and `Unresolved reference: constructor`.

- [ ] **Step 3: Write the generator**

`buildSrc/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
```

`buildSrc/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinpoet)
}
```

`buildSrc/src/main/kotlin/ArityGenerator.kt`:

```kotlin
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

private const val PKG = "dev.asm0dey.poetdsl"
private val EXPR = ClassName(PKG, "Expr")
private val BLOCK_SCOPE = ClassName(PKG, "BlockScope")
private val PARAMETER_SPEC = ClassName("com.squareup.kotlinpoet", "ParameterSpec")
private val TYPE_NAME = ClassName("com.squareup.kotlinpoet", "TypeName")
private val K_MODIFIER = ClassName("com.squareup.kotlinpoet", "KModifier")
private val MODIFIERS = ClassName(PKG, "Modifiers")
private val ANNOTATIONS = ClassName(PKG, "Annotations")
private val FILE_SCOPE = ClassName(PKG, "FileScope")
private val TYPE_SCOPE = ClassName(PKG, "TypeScope")

/** none | single KModifier | Modifiers */
private enum class ModVariant(val type: TypeName?) {
    NONE(null),
    SINGLE(K_MODIFIER),
    SET(MODIFIERS),
}

open class ArityGeneratorTask : DefaultTask() {
    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        write(dir, "FunArity", funFamily())
        write(dir, "CtorArity", ctorFamily())
    }

    private fun write(dir: File, name: String, funs: List<FunSpec>) {
        FileSpec.builder(PKG, name)
            .addFileComment("Generated by ArityGeneratorTask. Do not edit.")
            .apply { funs.forEach { addFunction(it) } }
            .build()
            .writeTo(dir)
    }
}

/** `fun` overloads for arities 0..26, for both FileScope and TypeScope, all modifier/annotation variants. */
private fun funFamily(): List<FunSpec> = buildList {
    for (scope in listOf(FILE_SCOPE, TYPE_SCOPE)) {
        for (arity in 0..26) {
            for (mods in ModVariant.entries) {
                for (annotated in listOf(false, true)) {
                    add(overload("fun", scope, arity, mods, annotated, isCtor = false))
                    add(overload("func", scope, arity, mods, annotated, isCtor = false))
                }
            }
        }
    }
}

private fun ctorFamily(): List<FunSpec> = buildList {
    for (arity in 0..26) {
        for (mods in ModVariant.entries) {
            for (annotated in listOf(false, true)) {
                add(overload("constructor", TYPE_SCOPE, arity, mods, annotated, isCtor = true))
                add(overload("ctor", TYPE_SCOPE, arity, mods, annotated, isCtor = true))
            }
        }
    }
}

private fun overload(
    name: String,
    scope: ClassName,
    arity: Int,
    mods: ModVariant,
    annotated: Boolean,
    isCtor: Boolean,
): FunSpec {
    val bodyType = LambdaTypeName.get(
        receiver = BLOCK_SCOPE,
        parameters = (1..arity).map { ParameterSpec.unnamed(EXPR) },
        returnType = Unit::class.asTypeName(),
    )
    val target = if (scope == FILE_SCOPE) "f" else "t"
    val callArgs = (1..arity).joinToString(", ") { "p$it" }
    val lambdaArgs = (1..arity).joinToString(", ") { "a$it" }
    return FunSpec.builder(name)
        .addKdoc("Generated arity-%L overload. See Declarations.kt for the hand-written arity-0 form.", arity)
        .contextParameter(target, scope)
        .apply {
            // No defaults on `annotations`/`modifiers`: each variant either has the parameter
            // (non-null, positional, before the name) or does not. Defaulted nullable parameters
            // would make `fun(name = "f")` match all six variants at once — an ambiguity error.
            if (annotated) addParameter("annotations", ANNOTATIONS)
            mods.type?.let { addParameter("modifiers", it) }
            addParameter("name", String::class)
            (1..arity).forEach { addParameter("p$it", PARAMETER_SPEC) }
            if (!isCtor) {
                addParameter(ParameterSpec.builder("returns", TYPE_NAME.copy(nullable = true)).defaultValue("null").build())
            }
            addParameter("body", bodyType)
        }
        .addStatement(
            "%L.builder.addFunction(%L)",
            target,
            "buildFun(${if (isCtor) "\"<init>\"" else "name"}, " +
                "${if (annotated) "annotations" else "null"}, " +
                "${modsArgument(mods)}, listOf($callArgs), " +
                "${if (isCtor) "null" else "returns"}, $target.names, $target.id) " +
                "{ ($lambdaArgs) -> body($lambdaArgs) }",
        )
        .build()
}

private fun modsArgument(mods: ModVariant): String = when (mods) {
    ModVariant.NONE -> "null"
    ModVariant.SINGLE -> "modifiers.toModifiers()"
    ModVariant.SET -> "modifiers"
}
```

`FunSpec.Builder.contextParameter(name, type)` is KotlinPoet 2.x's API for emitting `context(name: Type)`. If it is absent in 2.3.0, emit the context parameter as a KDoc-free raw prefix instead: build the function without it and post-process the file text, or switch the generator to plain `String` templates written with `File.writeText`. Check `list_javadoc_symbols` for `FunSpec.Builder` before choosing.

Constructors are a special case: `buildFun` builds a `FunSpec` with a name, but a constructor needs `FunSpec.constructorBuilder()`. Add an `isConstructor: Boolean = false` parameter to `buildFun` in `Declarations.kt` that switches the builder and skips the return type, and have the generator pass it.

- [ ] **Step 4: Wire the generator into the build**

Append to `build.gradle.kts`:

```kotlin
val generateArities = tasks.register<ArityGeneratorTask>("generateArities") {
    outputDir.set(layout.buildDirectory.dir("generated/source/dsl"))
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateArities)
}
```

- [ ] **Step 5: Remove every hand-written scope overload of `fun`/`func`**

Delete the arity 0, 1, 2 and 3 `` `fun` `` and `func` overloads from `Declarations.kt` (both the `FileScope` and the `TypeScope` sets) — the generator now produces arities 0–26 in all six variants, and leaving the hand-written ones causes both redeclaration and ambiguity errors. Keep `param`, `buildFun`, the **list form** (its `params: List<ParameterSpec>` parameter makes it unambiguous against every generated variant), and the detached `funSpec` builders.

Because the generated variants take `annotations`/`modifiers` as non-null positional parameters, the earlier tests' call style keeps working: `` `fun`(name = "noop") { … } `` matches the no-annotation/no-modifier variant, and `` `fun`(modifiers = PRIVATE + SUSPEND, name = "greet", p1 = …) `` matches the `Modifiers` variant. The spec's fully positional style — `` `fun`(PRIVATE + SUSPEND, "greet", param("greeting", STRING)) `` — works for the same reason. Add one positional-style assertion to `ArityTest` proving it:

```kotlin
    @Test
    fun `spec style positional call resolves`() {
        val out = file("com.example", "Api") {
            `fun`(com.squareup.kotlinpoet.KModifier.PRIVATE + com.squareup.kotlinpoet.KModifier.SUSPEND, "greet", param("greeting", com.squareup.kotlinpoet.STRING)) { greeting ->
                +call("println", greeting)
            }
        }.toString()
        assertTrue("private suspend fun greet(greeting: String)" in out)
    }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS, including `ArityTest` and the earlier `FunctionsTest`. If `FunctionsTest` now fails to resolve `p1 = …`, the generated signature's parameter order differs from the hand-written one — align the generator to the hand-written order (`annotations`, `modifiers`, `name`, `p1..pN`, `returns`, `body`).

- [ ] **Step 7: Commit**

```bash
git add buildSrc build.gradle.kts src
git commit -m "build: generate fun and constructor arity overloads 1-26"
```

---

### Task 22: Golden precedence matrix and compile tests

Golden tests are the backbone; compile tests cover the cases where "looks right" is not proof. Compile tests are slow, so there are only a handful.

**Files:**
- Create: `src/test/kotlin/dev/asm0dey/poetdsl/PrecedenceMatrixTest.kt`
- Create: `src/test/kotlin/dev/asm0dey/poetdsl/CompileTest.kt`
- Modify: `build.gradle.kts` (add the kctfork test dependency)

**Interfaces:**
- Consumes: everything built so far.
- Produces: proof that generated output not only matches expected text but actually compiles.

- [ ] **Step 1: Add the test dependency**

In `build.gradle.kts`:

```kotlin
dependencies {
    api(libs.kotlinpoet)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kctfork.core)
}
```

- [ ] **Step 2: Write the precedence matrix golden test**

`src/test/kotlin/dev/asm0dey/poetdsl/PrecedenceMatrixTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class PrecedenceMatrixTest {
    private val a = expression("a")
    private val b = expression("b")
    private val c = expression("c")
    private val d = expression("d")

    @Test
    fun `arithmetic nesting`() {
        assertEquals("a + b * c", (a + b * c).toString())
        assertEquals("(a + b) * c", ((a + b) * c).toString())
        assertEquals("a * b + c", (a * b + c).toString())
        assertEquals("a - (b - c)", (a - (b - c)).toString())
        assertEquals("a / b % c", (a / b % c).toString())
        assertEquals("-(a + b)", (-(a + b)).toString())
    }

    @Test
    fun `comparison over arithmetic`() {
        assertEquals("a + b < c", (a + b lt c).toString())
        assertEquals("a < b == c", ((a lt b) eq c).toString())
        assertEquals("a == (b == c)", (a eq (b eq c)).toString())
    }

    @Test
    fun `logical over comparison`() {
        assertEquals("a < b && c > d", ((a lt b) and (c gt d)).toString())
        assertEquals("!(a && b) || c", ((a and b).not() or c).toString())
        assertEquals("a || b && c", (a or (b and c)).toString())
        assertEquals("(a || b) && c", ((a or b) and c).toString())
    }

    @Test
    fun `elvis combinations`() {
        assertEquals("a ?: b + c", (a elvis (b + c)).toString())
        assertEquals("(a ?: b) + c", ((a elvis b) + c).toString())
        assertEquals("a ?: b ?: c", (a elvis (b elvis c)).toString())
        assertEquals("(a ?: b) == c", ((a elvis b) eq c).toString())
    }

    @Test
    fun `calls bind tightest`() {
        assertEquals("a.f() + b.g()", (a.call("f") + b.call("g")).toString())
        assertEquals("(a + b).f()", ((a + b).call("f")).toString())
        assertEquals("(a ?: b).f()", ((a elvis b).call("f")).toString())
        assertEquals("a?.f().g()", (a.safeCall("f").call("g")).toString())
    }
}
```

- [ ] **Step 3: Run the matrix test**

Run: `./gradlew test --tests '*PrecedenceMatrixTest*'`
Expected: PASS, 5 tests. Any failure here is a real precedence bug in `Prec.kt` or `Operators.kt` — fix the implementation, not the expectation, unless the expectation contradicts the Kotlin grammar.

- [ ] **Step 4: Write the compile tests**

`src/test/kotlin/dev/asm0dey/poetdsl/CompileTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

class CompileTest {
    private fun assertCompiles(spec: FileSpec) {
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("${spec.name}.kt", spec.toString()))
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Generated code did not compile:\n$spec\n${result.messages}",
        )
    }

    @Test
    fun `precedence output is valid kotlin`() {
        assertCompiles(
            file("com.example", "Prec") {
                `fun`(name = "f", p1 = param("a", INT), p2 = param("b", INT), p3 = param("c", INT), returns = INT) { a, b, c ->
                    ret((a + b) * c)
                }
            },
        )
    }

    @Test
    fun `imports resolve for type and member references`() {
        assertCompiles(
            file("com.example", "Imports") {
                `fun`(name = "f") {
                    `val`("xs", init = call(member("kotlin.collections", "listOf"), 1.lit))
                }
            },
        )
    }

    @Test
    fun `shadowed names do not collide`() {
        assertCompiles(
            file("com.example", "Shadow") {
                `class`(name = "Holder") {
                    val item = constructorParam(com.squareup.kotlinpoet.KModifier.VAL, name = "item", type = STRING)
                    `fun`(name = "use", p1 = param("items", LIST.parameterizedBy(STRING))) { items ->
                        `for`(items) { local -> +call("println", local) }
                        +call("println", item)
                    }
                }
            },
        )
    }

    @Test
    fun `lambdas compile`() {
        assertCompiles(
            file("com.example", "Lambdas") {
                `fun`(name = "f", p1 = param("xs", LIST.parameterizedBy(STRING))) { xs ->
                    `val`("lengths", init = xs.call("map") { +it.prop("length") })
                }
            },
        )
    }

    @Test
    fun `delegated properties compile`() {
        assertCompiles(
            file("com.example", "Delegates") {
                `class`(name = "Holder") {
                    `val`(name = "x", type = INT, by = call(member("kotlin", "lazy")) { +1.lit })
                }
            },
        )
    }
}
```

Add `import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy` at the top of the file for `LIST.parameterizedBy(STRING)`.

kctfork keeps the original `com.tschuchort.compiletesting` package names; if the import fails to resolve, check `list_javadoc_symbols` for `dev.zacsweers.kctfork:core:0.13.0` and use the package it reports. `KotlinCompilation.compile()` is deprecated in favour of `compile()` on a configured instance in some versions — follow whatever the deprecation warning says.

- [ ] **Step 5: Run the compile tests**

Run: `./gradlew test --tests '*CompileTest*'`
Expected: PASS, 5 tests. These are slow (each spins a compiler); expect tens of seconds.

- [ ] **Step 6: Add the escape-hatch tests and run the whole suite**

Append to `PrecedenceMatrixTest.kt`:

```kotlin
    @Test
    fun `escape hatch preserves placeholders`() {
        val out = file("com.example", "Esc") {
            `fun`(name = "f", p1 = param("xs", com.squareup.kotlinpoet.LIST.parameterizedBy(com.squareup.kotlinpoet.ANY))) { xs ->
                +expression("%L.filterIsInstance<%T>()", xs, reference<CharSequence>())
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Any
            import kotlin.CharSequence
            import kotlin.collections.List

            public fun f(xs: List<Any>) {
              xs.filterIsInstance<CharSequence>()
            }

            """.trimIndent(),
            out,
        )
    }
```

Run: `./gradlew test`
Expected: PASS, whole suite.

- [ ] **Step 7: Prove the extensibility claim with a user-written helper**

The spec promises that a helper a user writes is indistinguishable from a built-in. That is a testable claim, so test it.

`src/test/kotlin/dev/asm0dey/poetdsl/ExtensionTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

// Written exactly as a library consumer would write it — no internal API, no privileged position.
context(b: BlockScope)
fun Expr.orThrow(message: String) {
    `if`(this eq nul) { `throw`(call("IllegalStateException", message.lit)) }
}

class ExtensionTest {
    @Test
    fun `a user written scope aware extension works like a built in`() {
        val out = renderBlock {
            val x = `val`("x", init = call("find"))
            x.orThrow("missing")
            +x
        }
        assertEquals(
            "val x = find()\n" +
                "if (x == null) {\n  throw IllegalStateException(\"missing\")\n}\n" +
                "x\n",
            out,
        )
    }
}
```

Run: `./gradlew test --tests '*ExtensionTest*'`
Expected: PASS, 1 test. If it fails to compile because `Expr`'s extension slot is taken or the context parameter cannot resolve, the library's central design promise is broken — stop and report, do not work around it inside the test.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts src/test/kotlin/dev/asm0dey/poetdsl
git commit -m "test: precedence matrix, escape-hatch, extensibility and compile tests"
```

---

### Task 23: Alias audit, API surface lock, README, publishing

**Files:**
- Create: `src/test/kotlin/dev/asm0dey/poetdsl/AliasTest.kt`
- Create: `README.md`
- Create: `api/kotlin-poet-dsl.api` (generated)
- Modify: `build.gradle.kts` (publishing block)

**Interfaces:**
- Consumes: everything.
- Produces: a locked public API surface, documented aliases, and a publishable artifact.

- [ ] **Step 1: Write the alias equivalence test**

`src/test/kotlin/dev/asm0dey/poetdsl/AliasTest.kt`:

```kotlin
package dev.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals

class AliasTest {
    private fun bothRender(canonical: BlockScope.() -> Unit, alias: BlockScope.() -> Unit) =
        assertEquals(renderBlock(canonical), renderBlock(alias))

    @Test
    fun `statement level aliases`() {
        bothRender({ statement(1.lit) }, { stmt(1.lit) })
        bothRender({ `return`(1.lit) }, { ret(1.lit) })
        bothRender({ `for`(expression("xs")) { } }, { forIn(expression("xs")) { } })
        bothRender({ `if`(expression("c")) { } }, { ifThen(expression("c")) { } })
        bothRender({ `when`(expression("s")) { branch(1.lit) { } } }, { whenOn(expression("s")) { branch(1.lit) { } } })
        bothRender({ `try` { }.finally { } }, { tryCatch { }.finally { } })
        bothRender({ `throw`(call("E")) }, { throwIt(call("E")) })
        bothRender({ `for`(expression("xs")) { `break`() } }, { `for`(expression("xs")) { brk() } })
        bothRender({ `for`(expression("xs")) { `continue`() } }, { `for`(expression("xs")) { cont() } })
    }

    @Test
    fun `expression level aliases`() {
        assertEquals(1.literal.toString(), 1.lit.toString())
        assertEquals(nullLiteral.toString(), nul.toString())
        assertEquals(reference<String>(), ref<String>())
        assertEquals(member("kotlin", "lazy"), mem("kotlin", "lazy"))
        assertEquals(expression("x").toString(), expr("x").toString())
        assertEquals(annotation<Email>().list, ann<Email>().list)
    }

    @Test
    fun `declaration level aliases`() {
        assertEquals(
            file("com.example", "A") { `class`(name = "C") { } }.toString(),
            file("com.example", "A") { klass(name = "C") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `fun`(name = "f") { } }.toString(),
            file("com.example", "A") { func(name = "f") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`(name = "C") { `val`(name = "p", type = INT, init = 1.lit) } }.toString(),
            file("com.example", "A") { `class`(name = "C") { property(name = "p", type = INT, init = 1.lit) } }.toString(),
        )
        assertEquals(
            file("com.example", "A") {
                `class`(name = "C") { constructorParam(name = "a", type = STRING) }
            }.toString(),
            file("com.example", "A") {
                `class`(name = "C") { ctorParam(name = "a", type = STRING) }
            }.toString(),
        )
    }
}
```

This test is the fixed alias table (spec open task 3) in executable form: every row of the spec's table is one assertion. If a row has no assertion, the alias does not exist yet — add it.

- [ ] **Step 2: Run the alias test**

Run: `./gradlew test --tests '*AliasTest*'`
Expected: PASS, 3 tests. Add any missing alias to the relevant source file until it does.

- [ ] **Step 3: Lock the API surface**

Run: `./gradlew apiDump`
Expected: `api/kotlin-poet-dsl.api` is created. Skim it: every entry should be an intentional public declaration. Anything accidental (a leaked internal helper, a stray `it` property) gets `internal` and a re-dump.

Run: `./gradlew apiCheck`
Expected: PASS.

- [ ] **Step 4: Write the README**

`README.md` must cover, each with a working example copied from a passing test:

```markdown
# kotlin-poet-dsl

A Kotlin DSL over KotlinPoet that makes generator code read like the Kotlin it generates.

## Requirements

Kotlin **2.4.0 or newer** — the DSL is built on context parameters, Stable since 2.4.
Consumers below 2.4 cannot use this library. No compiler flags are needed.

## Emission model

`Unit`-returning API emits. `Expr`-returning API does not. `val`/`var` are the single
exception: they emit and return a handle.

## Every construct has a pure form

<example: stmts { } and funSpec/typeSpec/propertySpec>

## Expressions

<the spec's Kotlin-vs-DSL table, with the reason column>

## Aliases

<the alias table, noting that `property` (declaration) and `prop` (property access) are
deliberately different names>

## Writing your own helpers

Anything this library declares with a context parameter is a pattern you can copy verbatim;
a helper you write is indistinguishable from a built-in.

```kotlin
context(b: BlockScope)
fun Expr.orThrow(message: String): Unit =
    `if`(this eq nul) { `throw`(call("IllegalStateException", message.lit)) }
```

Helpers spanning two scopes declare both — e.g. one that emits a statement *and* registers
an import takes `context(f: FileScope, b: BlockScope)`.

## Not modelled

Ranges, `in`, `is`, casts, spread, labels, explicit generic arguments, string templates.
All reachable through `expression("…")`, which preserves `%T`/`%M` so imports resolve.
Strings inside `expression` bypass scope checking.

## Callable reference limitations

<the table from docs/spikes/2026-08-13-callable-references.md>

## Safety

<what the DslMarker layer actually catches — copy the conclusion from
docs/spikes/2026-08-13-dslmarker-context-parameters.md, do not overpromise>

Handles carry their declaring scope; emitting one outside that scope throws at build time.
Locals that would shadow a member are uniquified (`name` → `name2`) rather than qualified
with `this.`, which keeps one naming mechanism instead of two.
```

- [ ] **Step 5: Add publishing configuration**

In `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bcv)
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("kotlin-poet-dsl")
                description.set("A Kotlin DSL over KotlinPoet for files, types, members and bodies.")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
```

Signing and the Maven Central repository block are left out deliberately — add them when there is an account to publish to.

- [ ] **Step 6: Full verification**

Run: `./gradlew clean build apiCheck`
Expected: PASS. Report the actual test count and any warnings; do not claim success without the output.

- [ ] **Step 7: Commit**

```bash
git add README.md api build.gradle.kts src/test/kotlin/dev/asm0dey/poetdsl/AliasTest.kt
git commit -m "docs: readme, alias audit and locked public API surface"
```

---

## Notes for the implementer

- **KotlinPoet API drift.** Several steps flag "if this method does not exist in 2.3.0, do X". Before decompiling anything or guessing, use the javadocs MCP (`list_javadoc_symbols` / `get_javadoc_symbol` on `com.squareup:kotlinpoet-jvm:2.3.0`) or Context7 (`/square/kotlinpoet`). Decompiling is a last resort.
- **Expected-output strings.** Every golden test's expected string was written from KotlinPoet's documented formatting, not from a run. When one differs only in whitespace or blank-line placement, confirm the generated code is valid Kotlin, then align the expectation. When it differs in *structure*, that is a real bug — fix the code.
- **Order matters.** Tasks 3→7→8 fix the shapes everything else copies. Do not reorder them.
- **Two spec open tasks are resolved in the plan:** `UseSiteTarget.ALL` exists in KotlinPoet 2.3.0 (Task 17 proves it in a test); the alias table is fixed by Task 23's `AliasTest`. The third — callable-reference resolution — is Task 11.









