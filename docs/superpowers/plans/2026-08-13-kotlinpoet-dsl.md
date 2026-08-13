# KotlinPoet DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a publishable Kotlin library that wraps KotlinPoet in a DSL where generator code reads like the Kotlin it generates — files, types, members, and function bodies, with typed handles and `%T`/`%M` placeholders preserved.

**Architecture:** A sealed `Scope` supertype with three implementations — `FileScope`, `TypeScope`, `BlockScope`. Scopes enter user code as **receiver lambdas** (`BlockScope.() -> Unit`); every builder declares the scope it needs as a **context parameter**, and the receiver satisfies it. A construct valid at more than one level is declared **once** on `Scope` and dispatches on the runtime scope, so the innermost scope wins — `` `fun` `` in a body is a local function, in a type a member, at file level a top-level function. Scopes write directly into KotlinPoet builders; indentation and imports stay KotlinPoet's job.

**Tech Stack:** Kotlin 2.4.10 (JVM 17), Gradle 9.7.0, KotlinPoet 2.3.0, kotlin-reflect, `dev.zacsweers.kctfork:core:0.13.0`, binary-compatibility-validator 0.18.1, JUnit 5 via `kotlin("test")`.

**Decision record:** [`docs/adr/`](../../adr/) — eleven ADRs, six of them driven by behaviour measured against `kotlinc` 2.4.10. [`docs/glossary.md`](../../glossary.md) fixes the vocabulary. Read ADR 0001 before Task 2; it explains why the design has this shape and not the one in the spec's first draft.

## Global Constraints

- **Kotlin baseline 2.4.10, JVM target 17** via `jvmToolchain(17)`. Context parameters are Stable in 2.4.0 — **no compiler flag**. Do not add `-Xcontext-parameters` (2.2-era preview) or `-Xexplicit-context-arguments` (a different, still-experimental feature).
- **KotlinPoet 2.3.0 is the only backend.** No own IR, no post-processing. `AnnotationSpec.UseSiteTarget.ALL` exists there — no shim.
- **Group `site.asm0dey`, artifact `kotlin-poet-dsl`, base package `site.asm0dey.poetdsl`**, Apache-2.0. Single Gradle module.
- `explicitApi()` is on: every public declaration needs explicit visibility and an explicit return type.
- **Dependencies:** `api("com.squareup:kotlinpoet:2.3.0")`, `implementation(kotlin("reflect"))`. Nothing else at runtime.
- **One declaration per construct** (ADR 0001). Never two overloads distinguished only by context-parameter type — that is an ambiguity error, not an innermost-wins rule.
- **Six variants per declaration construct** (ADR 0004), distinguished by presence and type, never by defaults: `()`, `(modifiers: KModifier)`, `(modifiers: Modifiers)`, `(annotations: Annotations)`, `(annotations, modifiers: KModifier)`, `(annotations, modifiers: Modifiers)` — all non-null, positional, ahead of `name`.
- **Arity range 0–8** for lambda-bound parameters; wider signatures use `params = listOf(…)` with `body: BlockScope.(List<Expr>) -> Unit`.
- **Naming convention, no exceptions:** full word canonical, short form alias — `annotation`/`ann`, `member`/`mem`, `expression`/`expr`, `reference`/`ref`, `literal`/`lit`, `statement`/`stmt`, `constructorParam`/`ctorParam`. Keyword names are backticked and canonical: `` `return` ``/`ret`, `` `break` ``/`brk`, `` `continue` ``/`cont`, `` `constructor` ``/`ctor`. Aliases are permanent API.
- **Emission rule:** `Unit`-returning API emits; `Expr`-returning API does not. Single exception: `` `val` ``/`` `var` `` emit *and* return a handle. `invoke()` on a spec emits; `invoke()` on an `Expr` is a value call and emits nothing (ADR 0006).
- **Rendered lambda parameter names come from `param =`/`params =`**, never from the caller's own Kotlin binding. There is no `it` property (ADR 0005).
- **Errors are build-time `IllegalStateException`s** naming the offending construct. Never partial or silently wrong output.
- Every task ends with a passing test run and a Conventional Commits commit.

## File Structure

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
buildSrc/settings.gradle.kts
buildSrc/build.gradle.kts
buildSrc/src/main/kotlin/ArityGenerator.kt      — emits FunArity.kt, CtorArity.kt, Shadows.kt
api/kotlin-poet-dsl.api                         — binary-compatibility-validator dump
src/main/kotlin/site/asm0dey/poetdsl/
  Markers.kt        — @FileDsl, @TypeDsl, @BlockDsl
  Prec.kt           — precedence constants, parenthesization, binaryExpr
  Expr.kt           — Expr, Stmt
  Scope.kt          — sealed Scope; FileScope, TypeScope, BlockScope declarations
  Names.kt          — ScopeId, NameScope, singularize
  Literals.kt       — literal/lit, nullLiteral/nul, reference/ref, member/mem, expression/expr, TypeName.nullable
  Operators.kt      — arithmetic, comparison, logical, elvis
  Calls.kt          — call, prop, safeCall, safeProp, Expr.invoke
  Lambdas.kt        — lambda arities 0–8, param/params naming
  Refs.kt           — KFunction/KProperty → MemberName / bare name
  Statements.kt     — statement/stmt/+, stmts { }, splice-time ownership
  Bindings.kt       — `val`/`var` on Scope, assign, compound assignment
  ControlFlow.kt    — for, while, doWhile, if-chain, when, try
  Modifiers.kt      — Modifiers value class, KModifier.plus
  Annotations.kt    — Annotations value class, Annotatable, annotation/ann
  Declarations.kt   — class/object/interface, constructorParam, buildFun, param, detached builders
  FunArity.kt       — GENERATED
  CtorArity.kt      — GENERATED
  Shadows.kt        — GENERATED (@Deprecated(ERROR) members)
src/test/kotlin/site/asm0dey/poetdsl/
  TestSupport.kt    — render helpers for golden tests
  <one golden test file per task>
docs/adr/           — decision record
docs/glossary.md
docs/spikes/        — spike findings (Task 14)
README.md
```

---

### Task 1: Project skeleton and toolchain smoke test

**Files:**
- Create: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`, `.gitignore`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/ToolchainTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `./gradlew test` running Kotlin 2.4.10 JVM tests with KotlinPoet 2.3.0 on the classpath and `explicitApi()` enforced.

- [ ] **Step 1: Create the Gradle wrapper**

Run: `gradle wrapper --gradle-version 9.7.0`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`.

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

- [ ] **Step 3: Write the build scripts**

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

group = "site.asm0dey"
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

`.gitignore`:

```
build/
.gradle/
buildSrc/build/
.kotlin/
```

- [ ] **Step 4: Write the failing smoke test**

`src/test/kotlin/site/asm0dey/poetdsl/ToolchainTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
        assertEquals("ok", withMarker())
    }
}

class Marker(val value: String)

context(m: Marker)
fun readMarker(): String = m.value

fun withMarker(): String = with(Marker("ok")) { readMarker() }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS, 3 tests. A compile failure on the third test means the Kotlin version is wrong.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle .gitignore gradlew gradlew.bat src/test
git commit -m "build: gradle skeleton with kotlin 2.4.10 and kotlinpoet 2.3.0"
```

---

### Task 2: Core model — markers, precedence, `Expr`, `Stmt`, sealed `Scope`

Read [ADR 0001](../../adr/0001-scope-resolution-with-context-parameters.md) first.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Markers.kt`
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Prec.kt`
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Expr.kt`
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Scope.kt`
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Names.kt` (stub; completed in Task 3)
- Test: `src/test/kotlin/site/asm0dey/poetdsl/PrecTest.kt`

**Interfaces:**
- Consumes: Task 1's build.
- Produces:
  - `@FileDsl`, `@TypeDsl`, `@BlockDsl`
  - `Prec` with `ATOM POSTFIX PREFIX MULTIPLICATIVE ADDITIVE ELVIS COMPARISON EQUALITY CONJUNCTION DISJUNCTION`
  - `internal fun Expr.paren(min: Int): CodeBlock`
  - `internal fun binaryExpr(left, op, right, prec, type = null, rightAssoc = false): Expr`
  - `Expr(code, type, prec, name, scope)` and `Stmt(code, usedScopes)` — public classes, internal constructors
  - `public sealed class Scope` — the supertype the dispatching declarations take. A sealed **class**, not an interface: the dispatching code needs `names`/`id` from the supertype, and Kotlin forbids `internal` members in an interface.
  - `ScopeId` stub

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/PrecTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
        assertEquals(
            "a + b * c",
            binaryExpr(a, "+", binaryExpr(b, "*", c, Prec.MULTIPLICATIVE), Prec.ADDITIVE).code.toString(),
        )
    }

    @Test
    fun `lower precedence operand is parenthesized`() {
        assertEquals(
            "(a + b) * c",
            binaryExpr(binaryExpr(a, "+", b, Prec.ADDITIVE), "*", c, Prec.MULTIPLICATIVE).code.toString(),
        )
    }

    @Test
    fun `same precedence on the right is parenthesized for left associative operators`() {
        assertEquals(
            "a - (b - c)",
            binaryExpr(a, "-", binaryExpr(b, "-", c, Prec.ADDITIVE), Prec.ADDITIVE).code.toString(),
        )
    }

    @Test
    fun `right associative operators do not parenthesize the right operand`() {
        val inner = binaryExpr(b, "?:", c, Prec.ELVIS, rightAssoc = true)
        assertEquals(
            "a ?: b ?: c",
            binaryExpr(a, "?:", inner, Prec.ELVIS, rightAssoc = true).code.toString(),
        )
    }

    @Test
    fun `elvis binds tighter than comparison`() {
        val elvis = binaryExpr(a, "?:", b, Prec.ELVIS, rightAssoc = true)
        assertEquals("a ?: b < c", binaryExpr(elvis, "<", c, Prec.COMPARISON).code.toString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PrecTest*'`
Expected: FAIL — `Unresolved reference: Expr`.

- [ ] **Step 3: Write the implementation**

`Markers.kt`:

```kotlin
package site.asm0dey.poetdsl

/**
 * Marks the file-level DSL scope.
 *
 * These markers guard the member-based APIs (`WhenScope.branch`, `IfChain.elseIf`,
 * `TryChain.catch`). They do **not** guard context-parameter functions — measured on
 * Kotlin 2.4.10, `@DslMarker` has no effect on context-argument resolution. See ADR 0001.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class FileDsl

/** Marks the type-level DSL scope. See [FileDsl] for what the markers do and do not cover. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class TypeDsl

/** Marks the statement-level DSL scope. See [FileDsl] for what the markers do and do not cover. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class BlockDsl
```

`Prec.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * Kotlin operator precedence, high binds tighter. Mirrors the grammar:
 * postfix > prefix > multiplicative > additive > elvis > comparison > equality >
 * conjunction > disjunction.
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

/** Renders this expression, parenthesized when it binds looser than [min]. */
internal fun Expr.paren(min: Int): CodeBlock =
    if (prec < min) CodeBlock.of("(%L)", code) else code

/**
 * Builds `left op right` with the minimum parentheses Kotlin needs. Left-associative
 * operators parenthesize an equal-precedence right operand; right-associative ones
 * parenthesize an equal-precedence left operand.
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
    usedScopes = left.usedScopes + right.usedScopes,
)
```

`·` is KotlinPoet's non-breaking space: renders as a space, but the line-wrapper will not break there.

`Expr.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * A generated Kotlin expression. Pure — building one emits nothing.
 *
 * @property type the type when known, used for return inference; null when unknowable.
 * @property prec binding strength, used to parenthesize automatically.
 * @property name the source-level name when this refers to a binding.
 * @property scope the scope that declared this handle, if it is one.
 * @property usedScopes every scope whose handles contributed to this expression, so a
 *   composed expression can be validated where it is finally emitted (ADR 0008).
 */
public class Expr internal constructor(
    internal val code: CodeBlock,
    internal val type: TypeName? = null,
    internal val prec: Int = Prec.ATOM,
    internal val name: String? = null,
    internal val scope: ScopeId? = null,
    internal val usedScopes: Set<ScopeId> = scope?.let(::setOf).orEmpty(),
) {
    override fun toString(): String = code.toString()
}

/** A generated statement produced by the pure form, plus the scopes it referenced. */
public class Stmt internal constructor(
    internal val code: CodeBlock,
    internal val usedScopes: Set<ScopeId> = emptySet(),
) {
    override fun toString(): String = code.toString()
}
```

`Scope.kt`:

```kotlin
package site.asm0dey.poetdsl

/**
 * The nesting level a builder runs in. Sealed on purpose: every dispatching `when` is
 * exhaustive, so adding a scope makes the compiler list the constructs that forgot a case.
 *
 * A construct valid at more than one level is declared **once** on `Scope` and dispatches
 * on the runtime type — the innermost scope value wins. Two overloads distinguished only
 * by context-parameter type would be an ambiguity error instead. See ADR 0001.
 *
 * A sealed class rather than an interface: subclasses share [names] and [id], and Kotlin
 * does not allow `internal` members on an interface.
 */
public sealed class Scope internal constructor(
    internal val names: NameScope,
    internal val id: ScopeId,
)
```

`Names.kt` (stub):

```kotlin
package site.asm0dey.poetdsl

/** Identity of a DSL scope, used to detect handles used outside their declaring scope. */
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
git add src/main/kotlin/site/asm0dey/poetdsl src/test/kotlin/site/asm0dey/poetdsl/PrecTest.kt
git commit -m "feat: expression model, precedence and the sealed Scope supertype"
```

---

### Task 3: Names — scope identity, uniquification, singularization

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/Names.kt` (replaces the stub)
- Test: `src/test/kotlin/site/asm0dey/poetdsl/NamesTest.kt`

**Interfaces:**
- Produces:
  - `ScopeId.child(label)`, `ScopeId.isAncestorOf(other)` (a scope is its own ancestor)
  - `internal class NameScope(parent: NameScope?)` with `unique`, `isTaken`, `declare`, `child`
  - `internal fun singularize(name: String): String`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/NamesTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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

`Names.kt` (full replacement):

```kotlin
package site.asm0dey.poetdsl

/**
 * Identity of a DSL scope. Handles carry the [ScopeId] that declared them, so using one
 * where its scope does not apply is rejected when the code is emitted (ADR 0008).
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

/**
 * Tracks the names bound in a scope so generated names never collide. Nests with the
 * scopes, so a local that would shadow a member is renamed at declaration and nothing is
 * ever qualified with `this.` (ADR 0009).
 */
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

/** Best-effort English singular, for loop-variable defaults. Falls back to `item`. */
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*NamesTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Names.kt src/test/kotlin/site/asm0dey/poetdsl/NamesTest.kt
git commit -m "feat: scope identity, name uniquification, singularization"
```

---

### Task 4: Literals, references, escape hatch

Read [ADR 0010](../../adr/0010-references-return-classname.md): `reference<T>()` returns a `ClassName`, not an `Expr`, because every spec usage site is a type position.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Literals.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/LiteralsTest.kt`

**Interfaces:**
- Produces:
  - `val Int.literal: Expr` and the same for `Long`, `Double`, `Float`, `Boolean`, `Char`, `String`; each with a `lit` alias
  - `val nullLiteral: Expr`, alias `nul`
  - `inline fun <reified T> reference(): ClassName`, alias `ref`
  - `fun member(packageName, simpleName): MemberName`, `fun member(enclosing: ClassName, simpleName): MemberName`, alias `mem`
  - `fun ClassName.expression(): Expr`, `fun MemberName.expression(): Expr`, alias `expr()`
  - `val TypeName.nullable: TypeName`
  - `fun expression(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr`, alias `expr`
  - `internal fun Any?.asFormatArg(): Any?` — unwraps `Expr` to its `CodeBlock`
  - `internal fun scopesOf(args: Array<out Any?>): Set<ScopeId>` — collects `usedScopes` from `Expr` arguments so the escape hatch still participates in splice-time checking

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/LiteralsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.STRING
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
        val e = expression(
            "%L.filterIsInstance<%T>()",
            expression("xs"),
            reference<CharSequence>(),
            prec = Prec.POSTFIX,
        )
        assertEquals("xs.filterIsInstance<kotlin.CharSequence>()", e.code.toString())
        assertEquals(Prec.POSTFIX, e.prec)
    }

    @Test
    fun `nullable sugar`() {
        assertEquals("kotlin.String?", STRING.nullable.toString())
    }
}
```

`CodeBlock.toString()` renders `%T` fully qualified — imports resolve only when a `FileSpec` is built, hence the two `FileSpec`-based tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LiteralsTest*'`
Expected: FAIL — `Unresolved reference: literal`.

- [ ] **Step 3: Write the implementation**

`Literals.kt`:

```kotlin
package site.asm0dey.poetdsl

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
import com.squareup.kotlinpoet.TypeName
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

/** Sugar for `copy(nullable = true)`. */
public val TypeName.nullable: TypeName get() = copy(nullable = true)

/** A type reference: works in type position and as a `%T` argument; the import resolves. */
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

internal fun Any?.asFormatArg(): Any? = if (this is Expr) code else this

internal fun scopesOf(args: Array<out Any?>): Set<ScopeId> =
    args.filterIsInstance<Expr>().flatMapTo(mutableSetOf()) { it.usedScopes }

/**
 * Escape hatch for constructs the DSL does not model. `%T`/`%M` survive, so imports still
 * resolve; [Expr] arguments are unwrapped for `%L` and their scopes are carried through,
 * so splice-time ownership checking still applies to them.
 *
 * Raw strings inside the format bypass scope checking — the documented trade-off.
 *
 * @param prec the result's binding strength. Leave at [Prec.ATOM] for a self-contained
 *   expression; pass the real level (e.g. [Prec.ADDITIVE] for `"a + b"`) so surrounding
 *   operators parenthesize correctly.
 */
public fun expression(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr =
    Expr(
        code = CodeBlock.of(format, *args.map { it.asFormatArg() }.toTypedArray()),
        prec = prec,
        usedScopes = scopesOf(args),
    )

public fun expr(format: String, vararg args: Any?, prec: Int = Prec.ATOM): Expr =
    expression(format, *args, prec = prec)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LiteralsTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Literals.kt src/test/kotlin/site/asm0dey/poetdsl/LiteralsTest.kt
git commit -m "feat: literals, type and member references, expression escape hatch"
```

---

### Task 5: Operators

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Operators.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/OperatorsTest.kt`

**Interfaces:**
- Produces, all pure:
  - `operator fun Expr.plus/minus/times/div/rem(other: Expr): Expr`, `operator fun Expr.unaryMinus(): Expr`
  - `infix fun Expr.eq/neq(other: Expr): Expr` → `==`, `!=`, type `BOOLEAN`
  - `infix fun Expr.lt/le/gt/ge(other: Expr): Expr` → `<`, `<=`, `>`, `>=`, type `BOOLEAN`
  - `infix fun Expr.and/or(other: Expr): Expr`, `fun Expr.not(): Expr`
  - `infix fun Expr.elvis(other: Expr): Expr` — right-associative

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/OperatorsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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

`Operators.kt`:

```kotlin
package site.asm0dey.poetdsl

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
    binaryExpr(this, "%", other, Prec.MULTIPLICATIVE, sharedType(other))

public operator fun Expr.unaryMinus(): Expr =
    Expr(CodeBlock.of("-%L", paren(Prec.PREFIX)), type, Prec.PREFIX, usedScopes = usedScopes)

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
public fun Expr.not(): Expr =
    Expr(CodeBlock.of("!%L", paren(Prec.PREFIX)), BOOLEAN, Prec.PREFIX, usedScopes = usedScopes)

/** `?:`. Right-associative, binds tighter than comparison. */
public infix fun Expr.elvis(other: Expr): Expr =
    binaryExpr(this, "?:", other, Prec.ELVIS, other.type ?: type, rightAssoc = true)
```

The operator strings reach `CodeBlock.of` through `%L`, not as part of the format string, so `%` needs no escaping. If `a % b` renders wrong, that assumption is what to check first.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*OperatorsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Operators.kt src/test/kotlin/site/asm0dey/poetdsl/OperatorsTest.kt
git commit -m "feat: arithmetic, comparison, logical and elvis operators"
```

---

### Task 6: Calls, property access, and `Expr.invoke`

Read [ADR 0006](../../adr/0006-invoke-on-expr.md): `invoke` on an `Expr` calls the *value*; `invoke` on a spec emits.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Calls.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/CallsTest.kt`

**Interfaces:**
- Produces, all pure, all `prec = Prec.POSTFIX`, all `type = null` (a callee's return type is unknowable):
  - `fun Expr.call(name: String, vararg args: Expr): Expr`, `fun Expr.safeCall(…)`
  - `fun Expr.prop(name: String): Expr`, `fun Expr.safeProp(name: String): Expr`
  - `fun call(name: String, vararg args: Expr): Expr` — bare, no import
  - `fun call(member: MemberName, vararg args: Expr): Expr` — `%M`, import resolved
  - `operator fun Expr.invoke(vararg args: Expr): Expr` — calls the value
  - `internal fun argList(args: Array<out Expr>): CodeBlock`

Lambda-taking overloads arrive in Task 13; callable-reference ones in Task 14.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/CallsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
        assertEquals("x.substring(0, 3)", x.call("substring", 0.lit, 3.lit).toString())
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
    fun `invoke calls the value itself`() {
        assertEquals("x()", x().toString())
        assertEquals("x(1)", x(1.lit).toString())
        assertEquals("(a ?: b)(1)", (expression("a") elvis expression("b"))(1.lit).toString())
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

`Calls.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

internal fun argList(args: Array<out Expr>): CodeBlock {
    val builder = CodeBlock.builder()
    args.forEachIndexed { index, arg ->
        if (index > 0) builder.add(",·")
        builder.add("%L", arg.code)
    }
    return builder.build()
}

private fun scopesOf(receiver: Expr?, args: Array<out Expr>): Set<ScopeId> =
    buildSet {
        receiver?.let { addAll(it.usedScopes) }
        args.forEach { addAll(it.usedScopes) }
    }

/** `receiver.name(args)`. The member name is a string: it is unknown when the generator compiles. */
public fun Expr.call(name: String, vararg args: Expr): Expr =
    Expr(
        CodeBlock.of("%L.%L(%L)", paren(Prec.POSTFIX), name, argList(args)),
        prec = Prec.POSTFIX,
        usedScopes = scopesOf(this, args),
    )

/** `receiver?.name(args)`. */
public fun Expr.safeCall(name: String, vararg args: Expr): Expr =
    Expr(
        CodeBlock.of("%L?.%L(%L)", paren(Prec.POSTFIX), name, argList(args)),
        prec = Prec.POSTFIX,
        usedScopes = scopesOf(this, args),
    )

/** `receiver.name`. */
public fun Expr.prop(name: String): Expr =
    Expr(CodeBlock.of("%L.%L", paren(Prec.POSTFIX), name), prec = Prec.POSTFIX, usedScopes = usedScopes)

/** `receiver?.name`. */
public fun Expr.safeProp(name: String): Expr =
    Expr(CodeBlock.of("%L?.%L", paren(Prec.POSTFIX), name), prec = Prec.POSTFIX, usedScopes = usedScopes)

/** `name(args)` — a bare call, no import registered. */
public fun call(name: String, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%L(%L)", name, argList(args)), prec = Prec.POSTFIX, usedScopes = scopesOf(null, args))

/** `name(args)` where `name` is a [MemberName], so `%M` resolves the import. */
public fun call(member: MemberName, vararg args: Expr): Expr =
    Expr(CodeBlock.of("%M(%L)", member, argList(args)), prec = Prec.POSTFIX, usedScopes = scopesOf(null, args))

/**
 * Calls this value: `f(1)` where `f` holds a lambda or a function-typed parameter.
 * Returns an [Expr] and emits nothing — unlike `invoke` on a spec, which emits.
 */
public operator fun Expr.invoke(vararg args: Expr): Expr =
    Expr(
        CodeBlock.of("%L(%L)", paren(Prec.POSTFIX), argList(args)),
        prec = Prec.POSTFIX,
        usedScopes = scopesOf(this, args),
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*CallsTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Calls.kt src/test/kotlin/site/asm0dey/poetdsl/CallsTest.kt
git commit -m "feat: calls, property access and value invocation"
```

---

### Task 7: The three scopes, `file { }`, and spec emission

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/Scope.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/FileScopeTest.kt`

**Interfaces:**
- Consumes: `Scope`, `NameScope`, `ScopeId` (Tasks 2, 3).
- Produces:
  - `@FileDsl class FileScope internal constructor(builder: FileSpec.Builder, names, id) : Scope`
  - `@TypeDsl class TypeScope internal constructor(builder: TypeSpec.Builder, names, id) : Scope`, with a lazy primary-constructor builder and a `hasCtor` flag
  - `@BlockDsl class BlockScope internal constructor(builder: CodeBlock.Builder, names, id, returns, detachedRoot) : Scope`, with `pending: PendingFlow?`
  - `internal interface PendingFlow { fun close() }`
  - `internal fun BlockScope.child(label: String, isolateReturns: Boolean = false): BlockScope` (ADR 0007: lambdas isolate, control flow shares)
  - `fun file(packageName: String, fileName: String, body: FileScope.() -> Unit): FileSpec`
  - `context(f: FileScope)` emission for `FunSpec`, `TypeSpec`, `PropertySpec`: `unaryPlus`, `invoke`, `emit`, `add`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/FileScopeTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FunSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class FileScopeTest {
    @Test
    fun `empty file renders its package`() {
        assertEquals("package com.example\n", file("com.example", "Api") { }.toString())
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FileScopeTest*'`
Expected: FAIL — `Unresolved reference: file`.

- [ ] **Step 3: Write the implementation**

Append to `Scope.kt`:

```kotlin
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/** The file-level scope. */
@FileDsl
public class FileScope internal constructor(
    internal val builder: FileSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id)

/** The type-level scope: members are declared here. */
@TypeDsl
public class TypeScope internal constructor(
    internal val builder: TypeSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id) {
    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    internal fun finish(): TypeSpec {
        if (hasCtor) builder.primaryConstructor(ctor.build())
        return builder.build()
    }
}

/** A control-flow block left open by the builder that started it. */
internal interface PendingFlow {
    fun close()
}

/** The statement-level scope: a function, lambda or control-flow body. */
@BlockDsl
public class BlockScope internal constructor(
    internal val builder: CodeBlock.Builder,
    names: NameScope,
    id: ScopeId,
    internal val returns: MutableList<TypeName?>,
    internal val detachedRoot: Boolean = false,
) : Scope(names, id) {
    internal var pending: PendingFlow? = null
}

/**
 * A nested block.
 *
 * @param isolateReturns true for lambda bodies, whose `return` is a non-local return and
 *   must not drive the enclosing function's inferred return type (ADR 0007). Control-flow
 *   bodies pass false and share the list.
 */
internal fun BlockScope.child(label: String, isolateReturns: Boolean = false): BlockScope =
    BlockScope(
        builder = CodeBlock.builder(),
        names = names.child(),
        id = id.child(label),
        returns = if (isolateReturns) mutableListOf() else returns,
        detachedRoot = detachedRoot,
    )

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FileScopeTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Scope.kt src/test/kotlin/site/asm0dey/poetdsl/FileScopeTest.kt
git commit -m "feat: file, type and block scopes with spec emission"
```

---

### Task 8: Modifiers

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Modifiers.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/ModifiersTest.kt`

**Interfaces:**
- Produces:
  - `@JvmInline value class Modifiers internal constructor(internal val set: Set<KModifier>)`
  - `operator fun KModifier.plus(other: KModifier): Modifiers`
  - `operator fun Modifiers.plus(other: KModifier): Modifiers`
  - `operator fun Modifiers.plus(other: Modifiers): Modifiers`
  - `internal fun Modifiers?.toList(): List<KModifier>`
  - `fun KModifier.toModifiers(): Modifiers` — public, needed by callers that build variants programmatically

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/ModifiersTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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

`Modifiers.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

/** A set of Kotlin modifiers, built with `+` and written immediately before the name. */
@JvmInline
public value class Modifiers internal constructor(internal val set: Set<KModifier>)

public operator fun KModifier.plus(other: KModifier): Modifiers = Modifiers(linkedSetOf(this, other))

public operator fun Modifiers.plus(other: KModifier): Modifiers =
    Modifiers(LinkedHashSet(set).apply { add(other) })

public operator fun Modifiers.plus(other: Modifiers): Modifiers =
    Modifiers(LinkedHashSet(set).apply { addAll(other.set) })

/** Wraps a single modifier, for callers building variants programmatically. */
public fun KModifier.toModifiers(): Modifiers = Modifiers(linkedSetOf(this))

internal fun Modifiers?.toList(): List<KModifier> = this?.set?.toList().orEmpty()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ModifiersTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Modifiers.kt src/test/kotlin/site/asm0dey/poetdsl/ModifiersTest.kt
git commit -m "feat: Modifiers value class with plus operators"
```

---

### Task 9: Annotations

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Annotations.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/AnnotationsTest.kt`

**Interfaces:**
- Produces:
  - `@JvmInline value class Annotations internal constructor(internal val list: List<AnnotationSpec>)`, with `plus`
  - `typealias UseSiteTarget = AnnotationSpec.UseSiteTarget` — includes `ALL`, native in KotlinPoet 2.3.0
  - `inline fun <reified T : Annotation> annotation(target: UseSiteTarget? = null, vararg args: Expr): Annotations`, alias `ann`
  - `inline fun <reified T : Annotation> annotation(target: UseSiteTarget? = null, vararg named: Pair<String, Expr>): Annotations`, alias `ann`
  - `fun annotation(cls: ClassName, target: UseSiteTarget? = null, vararg args: Expr): Annotations`, alias `ann`
  - `interface Annotatable { fun addAnnotation(spec: AnnotationSpec) }`, plus `annotate` extensions and `addAll`
  - `FileScope` and `TypeScope` implement `Annotatable`; `FileScope.addAnnotation` defaults the target to `FILE`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/AnnotationsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnnotationsTest*'`
Expected: FAIL — `Unresolved reference: annotation`.

- [ ] **Step 3: Write the implementation**

`Annotations.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asClassName

/** KotlinPoet's use-site target enum, re-exported. Includes Kotlin 2.2's `@all:` meta-target. */
public typealias UseSiteTarget = AnnotationSpec.UseSiteTarget

/** A list of annotations, combined with `+`, written before the modifiers. */
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

/** An annotation with positional arguments; `%T`/`%M` in them survive and imports resolve. */
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

/** Implemented by the scopes, so annotations can also be added from a trailing lambda. */
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

/** Adds every annotation in the list, if any. */
public fun Annotatable.addAll(annotations: Annotations?) {
    annotations?.list?.forEach(::addAnnotation)
}
```

In `Scope.kt`, make the scopes `Annotatable`:

```kotlin
@FileDsl
public class FileScope internal constructor(
    internal val builder: FileSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id), Annotatable {
    /** Annotations added at file level default to the `@file:` use-site target. */
    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(
            if (spec.useSiteTarget == null) spec.toBuilder().useSiteTarget(UseSiteTarget.FILE).build() else spec,
        )
    }
}
```

and on `TypeScope`:

```kotlin
    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(spec)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnnotationsTest*'`
Expected: PASS, 7 tests. The `@all:` test is what closes spec open task 1 in code.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl src/test/kotlin/site/asm0dey/poetdsl/AnnotationsTest.kt
git commit -m "feat: annotations with use-site targets including @all"
```

---

### Task 10: Type declarations and constructor parameters

Hand-write the private implementations plus two variants each, so the semantics are tested against something small. **Task 20 generates the full six-variant set and deletes the hand-written variants** — the private impls, `constructorParam` and the detached builders survive.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Declarations.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/TypeScopeTest.kt`

**Interfaces:**
- Consumes: `FileScope`, `TypeScope` (Task 7), `Modifiers` (Task 8), `Annotations`/`Annotatable` (Task 9).
- Produces:
  - `internal fun Scope.declareType(builder: TypeSpec.Builder, annotations: Annotations?, modifiers: Modifiers?, body: TypeScope.() -> Unit)` — dispatches: `FileScope` → `addType`, `TypeScope` → `addType` (nested), `BlockScope` → local class via `emitCode`
  - `context(s: Scope) fun `class`(name: String, body: TypeScope.() -> Unit)` and `context(s: Scope) fun `class`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit)`; alias `klass`
  - `context(s: Scope) fun `object`(…)`, `context(s: Scope) fun `interface`(…)` — same two variants each. Both are invalid in a `BlockScope`; Task 20 adds the shadow members that make that a compile error, and the `when` branch throws meanwhile
  - `context(t: TypeScope) fun constructorParam(kind: KModifier? = null, name: String, type: TypeName): Expr` plus the `annotations` variant; alias `ctorParam`
  - `fun typeSpec(modifiers: Modifiers? = null, name: String, body: TypeScope.() -> Unit): TypeSpec` — detached

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/TypeScopeTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TypeScopeTest*'`
Expected: FAIL — `Unresolved reference: class`.

- [ ] **Step 3: Write the implementation**

`Declarations.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * Adds a type declaration to whichever scope is innermost: a top-level type in a file, a
 * nested type in a type, a local class in a block. Kotlin allows local classes but not
 * local named objects, interfaces, enums or annotation classes — [localAllowed] says
 * which is which, and Task 20's shadow members turn the invalid cases into compile errors.
 */
internal fun Scope.declareType(
    builder: TypeSpec.Builder,
    kindName: String,
    localAllowed: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    body: TypeScope.() -> Unit,
) {
    val scope = TypeScope(builder.addModifiers(modifiers.toList()), names.child(), id.child("type"))
    scope.addAll(annotations)
    scope.body()
    val spec = scope.finish()
    when (this) {
        is FileScope -> this.builder.addType(spec)
        is TypeScope -> this.builder.addType(spec)
        is BlockScope -> {
            check(localAllowed) {
                "A local $kindName is not valid Kotlin. Declare it at file or type level."
            }
            emitCode(CodeBlock.of("%L", spec))
        }
    }
}

/** `class Name { … }` — top-level, nested or local, depending on the innermost scope. */
context(s: Scope)
public fun `class`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", localAllowed = true, null, null, body)
}

context(s: Scope)
public fun `class`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.classBuilder(name), "class", localAllowed = true, null, modifiers, body)
}

/** Alias of [`class`]. */
context(s: Scope)
public fun klass(name: String, body: TypeScope.() -> Unit) {
    `class`(name, body)
}

context(s: Scope)
public fun klass(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    `class`(modifiers, name, body)
}

/** `object Name { … }`. Not valid inside a function body — Kotlin has no local named objects. */
context(s: Scope)
public fun `object`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.objectBuilder(name), "named object", localAllowed = false, null, null, body)
}

context(s: Scope)
public fun `object`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.objectBuilder(name), "named object", localAllowed = false, null, modifiers, body)
}

/** `interface Name { … }`. Not valid inside a function body. */
context(s: Scope)
public fun `interface`(name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.interfaceBuilder(name), "interface", localAllowed = false, null, null, body)
}

context(s: Scope)
public fun `interface`(modifiers: Modifiers, name: String, body: TypeScope.() -> Unit) {
    s.declareType(TypeSpec.interfaceBuilder(name), "interface", localAllowed = false, null, modifiers, body)
}

/**
 * Adds a parameter to the primary constructor. `VAL`/`VAR` also add the matching property;
 * null makes it a plain parameter. Returns a handle visible to every sibling member —
 * no nesting, no arity ceiling.
 */
context(t: TypeScope)
public fun constructorParam(kind: KModifier? = null, name: String, type: TypeName): Expr =
    t.addConstructorParam(kind, null, name, type)

context(t: TypeScope)
public fun constructorParam(
    kind: KModifier?,
    annotations: Annotations,
    name: String,
    type: TypeName,
): Expr = t.addConstructorParam(kind, annotations, name, type)

/** Alias of [constructorParam]. */
context(t: TypeScope)
public fun ctorParam(kind: KModifier? = null, name: String, type: TypeName): Expr =
    constructorParam(kind, name, type)

context(t: TypeScope)
public fun ctorParam(kind: KModifier?, annotations: Annotations, name: String, type: TypeName): Expr =
    constructorParam(kind, annotations, name, type)

internal fun TypeScope.addConstructorParam(
    kind: KModifier?,
    annotations: Annotations?,
    name: String,
    type: TypeName,
): Expr {
    require(kind == null || kind == KModifier.VAL || kind == KModifier.VAR) {
        "constructorParam kind must be VAL, VAR or null, was $kind."
    }
    val unique = names.unique(name)
    val param = ParameterSpec.builder(unique, type)
        .apply { annotations?.list?.forEach { addAnnotation(it) } }
        .build()
    ctor.addParameter(param)
    hasCtor = true
    if (kind != null) {
        builder.addProperty(
            PropertySpec.builder(unique, type)
                .mutable(kind == KModifier.VAR)
                .initializer("%N", param)
                .build(),
        )
    }
    return Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id)
}

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
```

`declareType`'s `BlockScope` branch renders the whole `TypeSpec` through `%L` into the block's `CodeBlock` via `emitCode`, which Task 11 owns. Write that one helper here as part of this task:

```kotlin
// Statements.kt — created here, extended in Task 11
internal fun BlockScope.emitCode(code: CodeBlock) {
    flushPending()
    builder.addStatement("%L", code)
}

internal fun BlockScope.flushPending() {
    val open = pending ?: return
    pending = null
    open.close()
}
```

Task 11 adds the rest of the file around them.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TypeScopeTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Declarations.kt src/test/kotlin/site/asm0dey/poetdsl/TypeScopeTest.kt
git commit -m "feat: class, object and interface declarations with scope dispatch"
```

---

### Task 11: Statements, the pure `stmts { }` form, splice-time ownership

Read [ADR 0008](../../adr/0008-ownership-checked-at-splice-time.md).

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Statements.kt`
- Create: `src/test/kotlin/site/asm0dey/poetdsl/TestSupport.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/StatementsTest.kt`

**Interfaces:**
- Produces:
  - `internal fun BlockScope.emitCode(code: CodeBlock)` — flushes pending control flow, then `addStatement`
  - `internal fun BlockScope.flushPending()`
  - `internal fun BlockScope.checkOwned(scope: ScopeId)` and `internal fun BlockScope.checkOwned(expr: Expr)`
  - `internal fun BlockScope.runNested(label: String, isolateReturns: Boolean = false, body: BlockScope.() -> Unit)`
  - `context(b: BlockScope) fun statement(expr: Expr)`, alias `stmt`, and `operator fun Expr.unaryPlus()`
  - `context(b: BlockScope) operator fun Stmt.unaryPlus()`, `invoke()`, `emit(stmt)`, `add(stmt)` — each validating `usedScopes`
  - `fun stmts(body: BlockScope.() -> Unit): Stmt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/TestSupport.kt`:

```kotlin
package site.asm0dey.poetdsl

/** Renders a detached block, for golden assertions on statement output. */
internal fun renderBlock(body: BlockScope.() -> Unit): String = stmts(body).code.toString()
```

`src/test/kotlin/site/asm0dey/poetdsl/StatementsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatementsTest {
    @Test
    fun `unary plus emits a statement`() {
        assertEquals("compute()\n", renderBlock { +call("compute") })
    }

    @Test
    fun `statement and stmt are the same construct`() {
        assertEquals(renderBlock { statement(expression("a")) }, renderBlock { stmt(expression("a")) })
    }

    @Test
    fun `statements emit in order`() {
        assertEquals("first()\nsecond()\n", renderBlock { +call("first"); +call("second") })
    }

    @Test
    fun `a pure Stmt can be spliced into another block`() {
        val guard: Stmt = stmts { +call("check") }
        assertEquals("check()\n", renderBlock { +guard })
    }

    @Test
    fun `emit add and invoke are equivalent spellings`() {
        val s = stmts { +call("x") }
        assertEquals(renderBlock { +s }, renderBlock { emit(s) })
        assertEquals(renderBlock { +s }, renderBlock { add(s) })
        assertEquals(renderBlock { +s }, renderBlock { s() })
    }

    @Test
    fun `a handle from an unrelated scope is rejected at the splice`() {
        val foreign = ScopeId(null, "fun(other)")
        val smuggled = Stmt(CodeBlock.of("leaked\n"), setOf(foreign))
        val failure = assertFailsWith<IllegalStateException> { renderBlock { +smuggled } }
        assertEquals(
            "Handle from scope 'fun(other)' does not enclose the current scope 'block'.",
            failure.message,
        )
    }

    @Test
    fun `a pure form may reference handles from the block it is spliced into`() {
        val out = renderBlock {
            val x = `val`("x", init = 1.lit)
            +stmts { +x.call("inc") }
        }
        assertEquals("val x = 1\nx.inc()\n", out)
    }
}
```

The last test is the one that forces splice-time checking: `x` belongs to the outer block, and the `stmts { }` scope is detached, so a construction-time check would reject it while a splice-time check accepts it correctly. It depends on Task 12's `` `val` ``; write it now and expect this single test to fail until Task 12 lands, or move it to Task 12's file — either is fine, but do not delete it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*StatementsTest*'`
Expected: FAIL — `Unresolved reference: stmts`.

- [ ] **Step 3: Write the implementation**

`Statements.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock

/** Closes any control-flow block left open by a previous builder. */
internal fun BlockScope.flushPending() {
    val open = pending ?: return
    pending = null
    open.close()
}

/** Rejects a scope that does not enclose this one. Detached roots accept anything. */
internal fun BlockScope.checkOwned(owner: ScopeId) {
    if (detachedRoot) return
    check(owner.isAncestorOf(id)) {
        "Handle from scope '${owner.label}' does not enclose the current scope '${id.label}'."
    }
}

internal fun BlockScope.checkOwned(expr: Expr) {
    expr.usedScopes.forEach { checkOwned(it) }
}

internal fun BlockScope.emitCode(code: CodeBlock) {
    flushPending()
    builder.addStatement("%L", code)
}

/** Runs [body] in a nested block and folds the result back in. */
internal fun BlockScope.runNested(
    label: String,
    isolateReturns: Boolean = false,
    body: BlockScope.() -> Unit,
) {
    val inner = child(label, isolateReturns)
    inner.body()
    inner.flushPending()
    builder.add(inner.builder.build())
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
    usedScopes.forEach { b.checkOwned(it) }
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
 * The pure form: the same statement builders run against a detached scope, returning the
 * result instead of emitting it. Handles referenced inside are validated when the result
 * is spliced, which is the only point where ownership can be judged.
 */
public fun stmts(body: BlockScope.() -> Unit): Stmt {
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = NameScope(null),
        id = ScopeId(null, "block"),
        returns = mutableListOf(),
        detachedRoot = true,
    )
    scope.body()
    scope.flushPending()
    return Stmt(scope.builder.build(), scope.referenced)
}
```

`scope.referenced` needs a home: add `internal val referenced: MutableSet<ScopeId> = mutableSetOf()` to `BlockScope`, and have `checkOwned(owner)` record into it when `detachedRoot` is true, before returning. That is how a detached scope reports what it touched without rejecting it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*StatementsTest*'`
Expected: PASS, 6 tests (7 once Task 12 lands).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl src/test/kotlin/site/asm0dey/poetdsl
git commit -m "feat: statement emission, pure stmts form, splice-time ownership"
```

---

### Task 12: `val`, `var`, assignment, compound assignment

Read [ADR 0003](../../adr/0003-property-and-local-binding-unification.md): one declaration across all three scopes, union parameters, per-scope validation.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Bindings.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/BindingsTest.kt`

**Interfaces:**
- Produces:
  - `context(s: Scope) fun `val`(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr` and the `Modifiers` variant; alias `property`. Same for `` `var` ``. Task 20 generates the remaining variants
  - `context(b: BlockScope) infix fun Expr.assign(value: Expr)`
  - `context(b: BlockScope) operator fun Expr.plusAssign/minusAssign/timesAssign/divAssign/remAssign(value: Expr)`
- Validation, all `IllegalStateException`: no type outside a block; `init` and `by` together; annotations or modifiers on a local.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/BindingsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BindingsTest {
    @Test
    fun `local val with and without an explicit type`() {
        assertEquals("val n: kotlin.Int = 1\n", renderBlock { `val`("n", INT, 1.lit) })
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
        assertEquals("var t: kotlin.Int = 0\nt += 1\nt -= 2\nt *= 3\nt /= 4\nt %= 5\n", out)
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
    fun `colliding local names are uniquified`() {
        val out = renderBlock {
            `val`("item", init = 1.lit)
            val second = `val`("item", init = 2.lit)
            +second
        }
        assertEquals("val item = 1\nval item2 = 2\nitem2\n", out)
    }

    @Test
    fun `a local never shadows a member`() {
        val out = file("com.example", "User") {
            `class`("User") {
                `val`("username", STRING, init = "a".lit)
            }
        }.toString()
        assertEquals(true, out.contains("public val username: String = \"a\""))
    }

    @Test
    fun `property with a delegate`() {
        val out = file("com.example", "User") {
            `class`("User") {
                `val`("x", INT, by = call(member("kotlin", "lazy")) { +call("calculate") })
            }
        }.toString()
        assertEquals(true, out.contains("public val x: Int by lazy"))
    }

    @Test
    fun `a property without a type is a named error`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "User") { `class`("User") { `val`("x", init = 1.lit) } }
        }
        assertEquals(
            "Property 'x' requires an explicit type; KotlinPoet cannot infer it.",
            failure.message,
        )
    }

    @Test
    fun `initializer and delegate together is an error`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { `val`("x", INT, init = 1.lit, by = call("lazy")) }
        }
        assertEquals("Binding 'x' cannot have both an initializer and a delegate.", failure.message)
    }
}
```

The delegate test needs the lambda-taking `call` overload from Task 13; if you run this task first, replace that argument with `expression("lazy { calculate() }")` and restore it in Task 13.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BindingsTest*'`
Expected: FAIL — `Unresolved reference: val`.

- [ ] **Step 3: Write the implementation**

`Bindings.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName

private fun Scope.bind(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
): Expr {
    check(init == null || by == null) {
        "Binding '$name' cannot have both an initializer and a delegate."
    }
    val unique = names.unique(name)
    return when (this) {
        is BlockScope -> {
            check(annotations == null && modifiers == null) {
                "A local binding ('$name') cannot carry annotations or modifiers."
            }
            init?.let { checkOwned(it) }
            by?.let { checkOwned(it) }
            val keyword = if (mutable) "var" else "val"
            val code = when {
                type == null && by != null -> CodeBlock.of("%L·%L·by·%L", keyword, unique, by.code)
                type == null -> CodeBlock.of("%L·%L·=·%L", keyword, unique, init?.code)
                by != null -> CodeBlock.of("%L·%L:·%T·by·%L", keyword, unique, type, by.code)
                else -> CodeBlock.of("%L·%L:·%T·=·%L", keyword, unique, type, init?.code)
            }
            emitCode(code)
            Expr(CodeBlock.of("%L", unique), type ?: init?.type, Prec.ATOM, unique, id)
        }

        is FileScope, is TypeScope -> {
            checkNotNull(type) {
                "Property '$name' requires an explicit type; KotlinPoet cannot infer it."
            }
            val spec = PropertySpec.builder(unique, type, modifiers.toList()).mutable(mutable)
            init?.let { spec.initializer("%L", it.code) }
            by?.let { spec.delegate("%L", it.code) }
            annotations?.list?.forEach { spec.addAnnotation(it) }
            when (this) {
                is FileScope -> builder.addProperty(spec.build())
                is TypeScope -> builder.addProperty(spec.build())
                else -> error("unreachable")
            }
            Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, id)
        }
    }
}

/**
 * A read-only binding: a local `val` in a block, a property at file or type level.
 * Emits **and** returns a handle — the single exception to "Unit emits, Expr does not".
 *
 * @param type mandatory for a property (KotlinPoet cannot infer); optional for a local.
 */
context(s: Scope)
public fun `val`(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    s.bind(false, null, null, name, type, init, by)

context(s: Scope)
public fun `val`(
    modifiers: Modifiers,
    name: String,
    type: TypeName? = null,
    init: Expr? = null,
    by: Expr? = null,
): Expr = s.bind(false, null, modifiers, name, type, init, by)

/** Alias of the declaration-level [`val`]. `prop` is property *access* — a different thing. */
context(s: Scope)
public fun property(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    `val`(name, type, init, by)

/** A mutable binding. */
context(s: Scope)
public fun `var`(name: String, type: TypeName? = null, init: Expr? = null, by: Expr? = null): Expr =
    s.bind(true, null, null, name, type, init, by)

context(s: Scope)
public fun `var`(
    modifiers: Modifiers,
    name: String,
    type: TypeName? = null,
    init: Expr? = null,
    by: Expr? = null,
): Expr = s.bind(true, null, modifiers, name, type, init, by)

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
    b.compound(this, "%=", value)
}
```

The five compound-assignment operators exist in emitting form only — Kotlin requires `plusAssign` to return `Unit`, so operator syntax can never be pure. Their pure twins are `stmts { total += x }` and `total assign (total + x)`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*BindingsTest*' --tests '*StatementsTest*'`
Expected: PASS, including `StatementsTest`'s splice test.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Bindings.kt src/test/kotlin/site/asm0dey/poetdsl/BindingsTest.kt
git commit -m "feat: val and var across scopes, assignment and compound assignment"
```

---

### Task 13: Lambdas

Read [ADR 0005](../../adr/0005-lambda-parameter-naming.md). There is **no `it` property** — Kotlin's own `it` silently shadows one inside nested lambdas, which would emit the wrong handle with no warning.

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Lambdas.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/LambdasTest.kt`

**Interfaces:**
- Produces:
  - `internal fun lambdaCode(params: List<String>, body: CodeBlock): CodeBlock`
  - `internal fun BlockScope.lambdaOf(requested: List<String?>, body: BlockScope.(List<Expr>) -> Unit): CodeBlock` — a `null` request renders as `it` and emits no parameter list; a name is uniquified and emitted
  - `context(b: BlockScope) fun lambda(body: BlockScope.() -> Unit): Expr` and a standalone `fun lambda(…)` for use outside any block
  - `context(b: BlockScope) fun Expr.call(name: String, vararg args: Expr, param: String? = null, body: BlockScope.(Expr) -> Unit): Expr`
  - `context(b: BlockScope) fun Expr.call(name: String, vararg args: Expr, params: List<String?>, body: BlockScope.(List<Expr>) -> Unit): Expr`
  - the same two shapes for `call(member: MemberName, …)` and for `Expr.invoke`
  - a zero-parameter shape: `body: BlockScope.() -> Unit`

Lambda bodies isolate return inference (ADR 0007) — they pass `isolateReturns = true`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/LambdasTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class LambdasTest {
    private val items = expression("items")

    @Test
    fun `omitted param renders implicit it`() {
        assertEquals(
            "items.map {\n  it.name\n}\n",
            renderBlock { +items.call("map") { p -> +p.prop("name") } },
        )
    }

    @Test
    fun `named param renders a parameter list`() {
        assertEquals(
            "items.map { item ->\n  item.name\n}\n",
            renderBlock { +items.call("map", param = "item") { item -> +item.prop("name") } },
        )
    }

    @Test
    fun `the callers own lambda binding name never reaches the output`() {
        val viaP = renderBlock { +items.call("map") { p -> +p.prop("name") } }
        val viaIt = renderBlock { +items.call("map") { whatever -> +whatever.prop("name") } }
        assertEquals(viaP, viaIt)
    }

    @Test
    fun `multiple parameters`() {
        assertEquals(
            "items.fold(0) { acc, x ->\n  acc + x\n}\n",
            renderBlock {
                +items.call("fold", 0.lit, params = listOf("acc", "x")) { (acc, x) -> +(acc + x) }
            },
        )
    }

    @Test
    fun `standalone lambda value`() {
        assertEquals(
            "val f = {\n  calculate()\n}\n",
            renderBlock { `val`("f", init = lambda { +call("calculate") }) },
        )
    }

    @Test
    fun `named lambda params are uniquified against the enclosing scope`() {
        assertEquals(
            "val item = 1\nitems.map { item2 ->\n  item2\n}\n",
            renderBlock {
                `val`("item", init = 1.lit)
                +items.call("map", param = "item") { item -> +item }
            },
        )
    }

    @Test
    fun `nested lambdas keep distinct handles`() {
        assertEquals(
            "for (item in items) {\n  item.map {\n    it.length\n  }\n}\n",
            renderBlock {
                `for`(items) { item -> +item.call("map") { p -> +p.prop("length") } }
            },
        )
    }
}
```

The last test needs `` `for` `` from Task 15; run it after that task, or move it into `LoopsTest`. It is the regression test for the `it`-shadowing hazard, so it must exist somewhere.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LambdasTest*'`
Expected: FAIL — no `call` overload taking a body.

- [ ] **Step 3: Write the implementation**

`Lambdas.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

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

/**
 * Builds a lambda body in a nested scope.
 *
 * @param requested one entry per parameter. `null` renders the handle as `it` and emits no
 *   parameter list; a name is uniquified against the enclosing scope and emitted.
 */
internal fun BlockScope.lambdaOf(
    requested: List<String?>,
    body: BlockScope.(List<Expr>) -> Unit,
): CodeBlock {
    val scope = child("lambda", isolateReturns = true)
    val rendered = requested.map { it?.let(scope.names::unique) }
    val handles = rendered.map { name ->
        Expr(CodeBlock.of("%L", name ?: "it"), name = name, scope = scope.id)
    }
    scope.body(handles)
    scope.flushPending()
    return lambdaCode(rendered.filterNotNull(), scope.builder.build())
}

/** A standalone `{ … }` value inside a block. */
context(b: BlockScope)
public fun lambda(body: BlockScope.() -> Unit): Expr =
    Expr(b.lambdaOf(emptyList()) { body() }, prec = Prec.ATOM)

/** A standalone `{ … }` value outside any block, for building specs up front. */
public fun lambda(body: BlockScope.() -> Unit, detached: Boolean = true): Expr {
    val scope = BlockScope(
        CodeBlock.builder(),
        NameScope(null),
        ScopeId(null, "lambda"),
        mutableListOf(),
        detachedRoot = true,
    )
    return Expr(scope.lambdaOf(emptyList()) { body() }, prec = Prec.ATOM)
}

context(b: BlockScope)
public fun Expr.call(name: String, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    Expr(
        CodeBlock.of("%L.%L(%L)·%L", paren(Prec.POSTFIX), name, argList(args), b.lambdaOf(emptyList()) { body() }),
        prec = Prec.POSTFIX,
        usedScopes = usedScopes + args.flatMap { it.usedScopes },
    )

context(b: BlockScope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    param: String? = null,
    body: BlockScope.(Expr) -> Unit,
): Expr = Expr(
    CodeBlock.of(
        "%L.%L(%L)·%L",
        paren(Prec.POSTFIX),
        name,
        argList(args),
        b.lambdaOf(listOf(param)) { (p) -> body(p) },
    ),
    prec = Prec.POSTFIX,
    usedScopes = usedScopes + args.flatMap { it.usedScopes },
)

context(b: BlockScope)
public fun Expr.call(
    name: String,
    vararg args: Expr,
    params: List<String?>,
    body: BlockScope.(List<Expr>) -> Unit,
): Expr = Expr(
    CodeBlock.of("%L.%L(%L)·%L", paren(Prec.POSTFIX), name, argList(args), b.lambdaOf(params, body)),
    prec = Prec.POSTFIX,
    usedScopes = usedScopes + args.flatMap { it.usedScopes },
)

context(b: BlockScope)
public fun call(member: MemberName, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    Expr(
        CodeBlock.of("%M(%L)·%L", member, argList(args), b.lambdaOf(emptyList()) { body() }),
        prec = Prec.POSTFIX,
        usedScopes = args.flatMapTo(mutableSetOf()) { it.usedScopes },
    )

context(b: BlockScope)
public fun call(
    member: MemberName,
    vararg args: Expr,
    param: String? = null,
    body: BlockScope.(Expr) -> Unit,
): Expr = Expr(
    CodeBlock.of("%M(%L)·%L", member, argList(args), b.lambdaOf(listOf(param)) { (p) -> body(p) }),
    prec = Prec.POSTFIX,
    usedScopes = args.flatMapTo(mutableSetOf()) { it.usedScopes },
)

context(b: BlockScope)
public operator fun Expr.invoke(vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    Expr(
        CodeBlock.of("%L(%L)·%L", paren(Prec.POSTFIX), argList(args), b.lambdaOf(emptyList()) { body() }),
        prec = Prec.POSTFIX,
        usedScopes = usedScopes + args.flatMap { it.usedScopes },
    )
```

Two things to settle against the compiler, not by guessing:

1. `Expr.call(name, vararg args, body)` and `Expr.call(name, vararg args, param, body)` differ in the body's arity, which Kotlin resolves — but a call passing neither `param` nor an explicit lambda parameter is ambiguous. If the compiler complains, drop the zero-parameter overload for `call` and require `params = emptyList()` for it; keep the zero-parameter shape only on `lambda`.
2. The standalone `lambda(body, detached)` overload above has an unused parameter purely to distinguish it from the context version. Prefer renaming it `detachedLambda(body)` if the two collide; the tests only exercise the context version plus `lambda { }` at block level.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LambdasTest*'`
Expected: PASS, 6 tests (7 after Task 15).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Lambdas.kt src/test/kotlin/site/asm0dey/poetdsl/LambdasTest.kt
git commit -m "feat: lambdas with explicit rendered parameter names"
```

---

### Task 14: Callable references (spec open task 2)

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/Refs.kt`
- Create: `docs/spikes/2026-08-13-callable-references.md`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/RefsTest.kt`

**Interfaces:**
- Produces:
  - `fun KFunction<*>.asMemberName(): MemberName` — top-level functions; throws naming the function when the owner cannot be resolved
  - `fun Expr.call(ref: KFunction<*>, vararg args: Expr): Expr`, `fun Expr.prop(ref: KProperty<*>): Expr` — name source only
  - `fun call(ref: KFunction<*>, vararg args: Expr): Expr` — `%M`, import resolved
  - `context(b: BlockScope) fun call(ref: KFunction<*>, vararg args: Expr, body: BlockScope.() -> Unit): Expr`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/RefsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import kotlin.reflect.KFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefsTest {
    @Test
    fun `member function reference contributes a bare name`() {
        assertEquals("x.isNotEmpty()", expression("x").call(String::isNotEmpty).toString())
    }

    @Test
    fun `property reference contributes a bare name`() {
        assertEquals("s.length", expression("s").prop(String::length).toString())
    }

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
    fun `an unresolvable reference fails with a named error`() {
        val local = { 1 }
        val failure = assertFailsWith<IllegalStateException> { call(local as KFunction<*>) }
        assertEquals(true, failure.message!!.endsWith("Use member(\"pkg\", \"name\") instead."))
    }
}

fun topLevelHelper(n: Int): Int = n
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*RefsTest*'`
Expected: FAIL — no `call` overload taking a `KFunction`.

- [ ] **Step 3: Write the implementation**

`Refs.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Resolves a top-level function reference to a [MemberName], so `%M` registers the import.
 *
 * Two documented limitations, both unfixable:
 * - a reference is a **name source only** — `Expr` is untyped, so `someInt.call(String::isNotEmpty)`
 *   compiles and generates invalid Kotlin, exactly as `call("isNotEmpty")` would;
 * - inline functions with reified type parameters (`arrayOf`, `emptyArray`, `typeOf`) cannot be
 *   referenced at all — Kotlin rejects `::arrayOf`. Use `member("kotlin", "arrayOf")`.
 */
public fun KFunction<*>.asMemberName(): MemberName {
    val owner = javaMethod?.declaringClass
        ?: error(
            "Cannot resolve a MemberName for '$name': no declaring class. " +
                "Use member(\"pkg\", \"name\") instead.",
        )
    return MemberName(owner.`package`?.name.orEmpty(), name)
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
        CodeBlock.of("%M(%L)·%L", ref.asMemberName(), argList(args), b.lambdaOf(emptyList()) { body() }),
        prec = Prec.POSTFIX,
        usedScopes = args.flatMapTo(mutableSetOf()) { it.usedScopes },
    )
```

- [ ] **Step 4: Run the test and record the findings**

Run: `./gradlew test --tests '*RefsTest*'`
Expected: PASS, 4 tests.

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
| inline + reified (`::arrayOf`) | no | Kotlin rejects the reference at the call site |
| local function / lambda | no | throws IllegalStateException naming the function |

Filled in from real runs of `RefsTest`. Add a test per row before writing "yes".
```

Every `<FILL IN>` must come from a test you actually ran. Do not guess.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Refs.kt src/test/kotlin/site/asm0dey/poetdsl/RefsTest.kt docs/spikes
git commit -m "feat: callable reference support with documented limitations"
```

---

### Task 15: Loops, `break`, `continue`, `throw`, `return`

**Files:**
- Create: `src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/LoopsTest.kt`

**Interfaces:**
- Produces (all `context(b: BlockScope)`):
  - `` `for`(items: Expr, name: String? = null, body: BlockScope.(Expr) -> Unit) ``, alias `forIn`
  - `` `while`(condition: Expr, body: BlockScope.() -> Unit) ``, `doWhile(condition, body)`
  - `` `break`() ``/`brk`, `` `continue`() ``/`cont`, `` `throw`(value) ``/`throwIt`
  - `` `return`(value) ``/`ret`, `` `return`() ``/`ret` — records `value.type` into `b.returns` (ADR 0007)

Loop variable: explicit `name =` wins, else `singularize(items.name)`, else `item`; always uniquified.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/LoopsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals

class LoopsTest {
    @Test
    fun `for over a named handle singularizes the loop variable`() {
        assertEquals(
            "val items = load()\nfor (item in items) {\n  item.run()\n}\n",
            renderBlock {
                val items = `val`("items", init = call("load"))
                `for`(items) { item -> +item.call("run") }
            },
        )
    }

    @Test
    fun `explicit name wins`() {
        assertEquals(
            "for (user in users) {\n  user\n}\n",
            renderBlock { `for`(expression("users"), name = "user") { user -> +user } },
        )
    }

    @Test
    fun `loop variable is uniquified against the enclosing scope`() {
        assertEquals(
            "val item = 1\nfor (item2 in items) {\n  item2\n}\n",
            renderBlock {
                `val`("item", init = 1.lit)
                `for`(expression("items")) { item -> +item }
            },
        )
    }

    @Test
    fun `while and doWhile`() {
        assertEquals(
            "var n: kotlin.Int = 0\nwhile (n < 10) {\n  n += 1\n}\ndo {\n  n -= 1\n} while (n > 0)\n",
            renderBlock {
                val n = `var`("n", INT, 0.lit)
                `while`(n lt 10.lit) { n += 1.lit }
                doWhile(n gt 0.lit) { n -= 1.lit }
            },
        )
    }

    @Test
    fun `break continue and throw`() {
        assertEquals(
            "for (item in items) {\n  break\n  continue\n  throw IllegalStateException(\"bad\")\n}\n",
            renderBlock {
                `for`(expression("items")) {
                    `break`()
                    `continue`()
                    `throw`(call("IllegalStateException", "bad".lit))
                }
            },
        )
    }

    @Test
    fun `aliases match the backticked forms`() {
        assertEquals(
            renderBlock { `for`(expression("xs")) { x -> +x } },
            renderBlock { forIn(expression("xs")) { x -> +x } },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*LoopsTest*'`
Expected: FAIL — `Unresolved reference: for`.

- [ ] **Step 3: Write the implementation**

`ControlFlow.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName

/**
 * `for (name in items) { … }`.
 *
 * @param name the loop variable. Defaults to the singular of the iterable handle's name
 *   (`items` → `item`), falling back to `item`. Always uniquified.
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
    b.flushPending()
    b.builder.beginControlFlow("while·(%L)", condition.code)
    b.runNested("while", body = body)
    b.builder.endControlFlow()
}

/** `do { … } while (condition)`. */
context(b: BlockScope)
public fun doWhile(condition: Expr, body: BlockScope.() -> Unit) {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("do")
    b.runNested("doWhile", body = body)
    b.builder.unindent()
    b.builder.add("}·while·(%L)\n", condition.code)
}

/** `break`. */
context(b: BlockScope)
public fun `break`() {
    b.emitCode(CodeBlock.of("break"))
}

context(b: BlockScope)
public fun brk() {
    `break`()
}

/** `continue`. */
context(b: BlockScope)
public fun `continue`() {
    b.emitCode(CodeBlock.of("continue"))
}

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

context(b: BlockScope)
public fun throwIt(value: Expr) {
    `throw`(value)
}

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

context(b: BlockScope)
public fun ret(value: Expr) {
    `return`(value)
}

context(b: BlockScope)
public fun ret() {
    `return`()
}
```

`doWhile` avoids `endControlFlow(format, …)` because KotlinPoet 2.3.0 may only expose the no-argument form; the `unindent()` + `add("}·while·(…)")` pair produces the same output either way. If the closing brace lands at the wrong indent, check `beginControlFlow("do")` — it indents once, and `runNested` must not indent again.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*LoopsTest*' --tests '*LambdasTest*'`
Expected: PASS — including the nested-lambda regression test from Task 13.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/site/asm0dey/poetdsl/LoopsTest.kt
git commit -m "feat: loops, break, continue, throw and return"
```

---

### Task 16: The `if` chain

KotlinPoet's control-flow API is linear, so `` `if` `` emits `beginControlFlow` plus its body and **leaves the block open**, returning an `IfChain` parked in `b.pending`. The next emission flushes it, as does closing the block. `elseIf`/`else` call `nextControlFlow` instead.

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/IfChainTest.kt`

**Interfaces:**
- Produces:
  - `class IfChain internal constructor(owner: BlockScope) : PendingFlow` with `elseIf(condition, body): IfChain`, `` `else`(body) ``, `close()`
  - `context(b: BlockScope) fun `if`(condition: Expr, body: BlockScope.() -> Unit): IfChain`, alias `ifThen`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/IfChainTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class IfChainTest {
    private val x = expression("x")

    @Test
    fun `bare if is flushed at block close`() {
        assertEquals(
            "if (x < 0) {\n  return false\n}\n",
            renderBlock { `if`(x lt 0.lit) { ret(false.lit) } },
        )
    }

    @Test
    fun `if is flushed before the next statement`() {
        assertEquals(
            "if (x < 0) {\n  return false\n}\nafter()\n",
            renderBlock {
                `if`(x lt 0.lit) { ret(false.lit) }
                +call("after")
            },
        )
    }

    @Test
    fun `full chain`() {
        assertEquals(
            "if (x < 0) {\n  return false\n} else if (x > 100) {\n  return false\n} else {\n  return true\n}\n",
            renderBlock {
                `if`(x lt 0.lit) { ret(false.lit) }
                    .elseIf(x gt 100.lit) { ret(false.lit) }
                    .`else` { ret(true.lit) }
            },
        )
    }

    @Test
    fun `chain inside a loop`() {
        assertEquals(
            "for (item in items) {\n  if (item == null) {\n    continue\n  }\n  item.run()\n}\n",
            renderBlock {
                `for`(expression("items")) { item ->
                    `if`(item eq nul) { `continue`() }
                    +item.call("run")
                }
            },
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

Append to `ControlFlow.kt`:

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
        owner.runNested("elseIf", body = body)
        return this
    }

    /** `else { … }`. */
    public fun `else`(body: BlockScope.() -> Unit) {
        owner.builder.nextControlFlow("else")
        owner.runNested("else", body = body)
    }

    override fun close() {
        owner.builder.endControlFlow()
    }
}

/** `if (condition) { … }`, chainable with [IfChain.elseIf] and [IfChain.else]. */
context(b: BlockScope)
public fun `if`(condition: Expr, body: BlockScope.() -> Unit): IfChain {
    b.checkOwned(condition)
    b.flushPending()
    b.builder.beginControlFlow("if·(%L)", condition.code)
    b.runNested("if", body = body)
    return IfChain(b).also { b.pending = it }
}

/** Alias of [`if`]. */
context(b: BlockScope)
public fun ifThen(condition: Expr, body: BlockScope.() -> Unit): IfChain = `if`(condition, body)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*IfChainTest*' --tests '*LoopsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/site/asm0dey/poetdsl/IfChainTest.kt
git commit -m "feat: if/elseIf/else chain with deferred block close"
```

---

### Task 17: `when`

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/WhenTest.kt`

**Interfaces:**
- Produces:
  - `@BlockDsl class WhenScope internal constructor(owner: BlockScope)` with `branch(vararg conditions: Expr, body: BlockScope.() -> Unit)` and `` `else`(body) ``
  - `context(b: BlockScope) fun `when`(subject: Expr, body: WhenScope.() -> Unit)`, alias `whenOn`
  - `context(b: BlockScope) fun whenTrue(body: WhenScope.() -> Unit)`

`WhenScope`'s members are where `@DslMarker` still earns its place: without `@BlockDsl` on `BlockScope`, `branch(…)` would resolve to the outer `WhenScope` receiver from inside a branch body.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/WhenTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class WhenTest {
    @Test
    fun `when with single and multiple conditions plus else`() {
        assertEquals(
            "when (subject) {\n" +
                "  1 -> {\n    one()\n  }\n" +
                "  2, 3 -> {\n    few()\n  }\n" +
                "  else -> {\n    many()\n  }\n" +
                "}\n",
            renderBlock {
                `when`(expression("subject")) {
                    branch(1.lit) { +call("one") }
                    branch(2.lit, 3.lit) { +call("few") }
                    `else` { +call("many") }
                }
            },
        )
    }

    @Test
    fun `subjectless when`() {
        assertEquals(
            "when {\n  a < 0 -> {\n    neg()\n  }\n  else -> {\n    pos()\n  }\n}\n",
            renderBlock {
                whenTrue {
                    branch(expression("a") lt 0.lit) { +call("neg") }
                    `else` { +call("pos") }
                }
            },
        )
    }

    @Test
    fun `whenOn is an alias`() {
        val subject = expression("subject")
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

Append to `ControlFlow.kt`:

```kotlin
/** The inside of a `when`. Only branches may be declared here. */
@BlockDsl
public class WhenScope internal constructor(internal val owner: BlockScope) {
    /** One branch. Several conditions are comma-joined, as in Kotlin. */
    public fun branch(vararg conditions: Expr, body: BlockScope.() -> Unit) {
        require(conditions.isNotEmpty()) {
            "A when branch needs at least one condition; use `else` for the fallback."
        }
        conditions.forEach(owner::checkOwned)
        val heads = conditions.map { it.code }.reduce { acc, code -> CodeBlock.of("%L,·%L", acc, code) }
        owner.builder.beginControlFlow("%L·->", heads)
        owner.runNested("branch", body = body)
        owner.builder.endControlFlow()
    }

    /** The `else ->` branch. */
    public fun `else`(body: BlockScope.() -> Unit) {
        owner.builder.beginControlFlow("else·->")
        owner.runNested("else", body = body)
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
git add src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/site/asm0dey/poetdsl/WhenTest.kt
git commit -m "feat: when and whenTrue"
```

---

### Task 18: `try` / `catch` / `finally`

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/TryTest.kt`

**Interfaces:**
- Produces:
  - `class TryChain internal constructor(owner: BlockScope) : PendingFlow` with `` `catch`(name: String, type: TypeName, body: BlockScope.(Expr) -> Unit): TryChain ``, `finally(body)`, `close()`
  - `context(b: BlockScope) fun `try`(body: BlockScope.() -> Unit): TryChain`, alias `tryCatch`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/TryTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import kotlin.test.Test
import kotlin.test.assertEquals

class TryTest {
    @Test
    fun `try catch finally`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalStateException) {\n  log(e.toString())\n} " +
                "finally {\n  cleanup()\n}\n",
            renderBlock {
                `try` { +call("risky") }
                    .`catch`("e", reference<IllegalStateException>()) { e -> +call("log", e.call("toString")) }
                    .finally { +call("cleanup") }
            },
        )
    }

    @Test
    fun `two catches uniquify the exception variable`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.IllegalArgumentException) {\n  a()\n} " +
                "catch (e2: java.lang.IllegalStateException) {\n  b()\n}\n",
            renderBlock {
                `try` { +call("risky") }
                    .`catch`("e", reference<IllegalArgumentException>()) { +call("a") }
                    .`catch`("e", reference<IllegalStateException>()) { +call("b") }
            },
        )
    }

    @Test
    fun `try is flushed before the next statement`() {
        assertEquals(
            "try {\n  risky()\n} catch (e: java.lang.Exception) {\n  handle()\n}\nafter()\n",
            renderBlock {
                `try` { +call("risky") }.`catch`("e", reference<Exception>()) { +call("handle") }
                +call("after")
            },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TryTest*'`
Expected: FAIL — `Unresolved reference: try`.

- [ ] **Step 3: Write the implementation**

Append to `ControlFlow.kt`:

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
        owner.runNested("finally", body = body)
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
    b.runNested("try", body = body)
    return TryChain(b).also { b.pending = it }
}

/** Alias of [`try`]. */
context(b: BlockScope)
public fun tryCatch(body: BlockScope.() -> Unit): TryChain = `try`(body)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TryTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/ControlFlow.kt src/test/kotlin/site/asm0dey/poetdsl/TryTest.kt
git commit -m "feat: try/catch/finally chain"
```

---

### Task 19: Functions, parameters, return-type inference, detached builders

Hand-write arities 0 and 1 so the semantics are tested small; **Task 20 generates 0–8 in six variants and deletes the hand-written scope overloads.** `buildFun`, `param`, the list form and the detached builders survive.

**Files:**
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/Declarations.kt`
- Test: `src/test/kotlin/site/asm0dey/poetdsl/FunctionsTest.kt`

**Interfaces:**
- Produces:
  - `fun param(name: String, type: TypeName): ParameterSpec`
  - `internal fun buildFun(name: String, isConstructor: Boolean, annotations: Annotations?, modifiers: Modifiers?, params: List<ParameterSpec>, returns: TypeName?, parent: Scope?, body: BlockScope.(List<Expr>) -> Unit): FunSpec` — the single implementation behind every arity overload, performing return-type inference
  - `internal fun Scope.declareFun(spec: FunSpec)` — dispatch: file → top-level, type → member, block → local function
  - `context(s: Scope) fun `fun`(name: String, returns: TypeName? = null, body: BlockScope.() -> Unit)` and the arity-1 variant; alias `func`
  - `context(s: Scope) fun `fun`(name: String, params: List<ParameterSpec>, returns: TypeName? = null, body: BlockScope.(List<Expr>) -> Unit)` — list form
  - `context(t: TypeScope) fun `constructor`(…)`, alias `ctor` — arity 0 and 1 hand-written
  - `fun funSpec(modifiers: Modifiers? = null, name: String, returns: TypeName? = null, body: BlockScope.() -> Unit): FunSpec` and the arity-1 variant
  - `fun propertySpec(modifiers: Modifiers? = null, name: String, type: TypeName, init: Expr? = null, by: Expr? = null): PropertySpec`

**Inference rule:** explicit `returns` wins. No recorded return → `Unit`, type omitted. All recorded types known and equal → that type. Otherwise `IllegalStateException` naming the function and the fix.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/FunctionsTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.VAL
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `explicit returns wins`() {
        assertEquals(
            true,
            file("com.example", "Api") {
                `fun`("mystery", param("x", STRING), returns = INT) { x -> ret(x.call("foo")) }
            }.toString().contains("public fun mystery(x: String): Int = x.foo()"),
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
    }

    @Test
    fun `a local function is emitted inside a body`() {
        assertEquals(
            true,
            file("com.example", "Api") {
                `fun`("outer") {
                    `fun`("helper") { ret(1.lit) }
                    +call("helper")
                }
            }.toString().contains("fun helper(): Int = 1"),
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
    fun `funSpec builds a detached spec`() {
        assertEquals(
            true,
            file("com.example", "Api") { +funSpec(name = "helper") { ret(1.lit) } }
                .toString()
                .contains("public fun helper(): Int = 1"),
        )
    }
}
```

The local-function test is the one that proves ADR 0001's dispatch: the same `` `fun` `` call reaches a different target because a `BlockScope` is innermost.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FunctionsTest*'`
Expected: FAIL — `Unresolved reference: param`.

- [ ] **Step 3: Write the implementation**

Append to `Declarations.kt`:

```kotlin
/** A function parameter. Type-position annotations come free: `param("x", INT.annotated<Positive>())`. */
public fun param(name: String, type: TypeName): ParameterSpec = ParameterSpec.builder(name, type).build()

internal fun buildFun(
    name: String,
    isConstructor: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    params: List<ParameterSpec>,
    returns: TypeName?,
    parent: Scope?,
    body: BlockScope.(List<Expr>) -> Unit,
): FunSpec {
    val names = (parent?.names ?: NameScope(null)).child()
    val id = (parent?.id ?: ScopeId(null, "root")).child("fun($name)")
    val recorded = mutableListOf<TypeName?>()
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = names,
        id = id,
        returns = recorded,
        detachedRoot = parent == null,
    )

    val handles = params.map { p ->
        names.declare(p.name)
        Expr(CodeBlock.of("%N", p), p.type, Prec.ATOM, p.name, id)
    }
    scope.body(handles)
    scope.flushPending()

    val returnType = when {
        isConstructor -> null
        returns != null -> returns
        recorded.isEmpty() -> null
        recorded.all { it != null } && recorded.distinct().size == 1 -> recorded.first()
        else -> error(
            "Cannot infer the return type of '$name': the returned expression's type is unknown. " +
                "Pass returns = … explicitly.",
        )
    }

    val builder = if (isConstructor) FunSpec.constructorBuilder() else FunSpec.builder(name)
    return builder
        .apply {
            annotations?.list?.forEach { addAnnotation(it) }
            addModifiers(modifiers.toList())
            params.forEach { addParameter(it) }
            returnType?.let { returns(it) }
            addCode(scope.builder.build())
        }
        .build()
}

/** Adds a function to whichever scope is innermost: top-level, member, or local. */
internal fun Scope.declareFun(spec: FunSpec) {
    when (this) {
        is FileScope -> builder.addFunction(spec)
        is TypeScope -> builder.addFunction(spec)
        is BlockScope -> emitCode(CodeBlock.of("%L", spec))
    }
}

/** `fun name() { … }` — top-level, member or local, depending on the innermost scope. */
context(s: Scope)
public fun `fun`(name: String, returns: TypeName? = null, body: BlockScope.() -> Unit) {
    s.declareFun(buildFun(name, false, null, null, emptyList(), returns, s) { body() })
}

context(s: Scope)
public fun `fun`(
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, null, listOf(p1), returns, s) { (a) -> body(a) })
}

context(s: Scope)
public fun `fun`(
    modifiers: Modifiers,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, modifiers, listOf(p1), returns, s) { (a) -> body(a) })
}

/** The list form: for more than eight parameters, or a list computed at generation time. */
context(s: Scope)
public fun `fun`(
    name: String,
    params: List<ParameterSpec>,
    returns: TypeName? = null,
    body: BlockScope.(List<Expr>) -> Unit,
) {
    s.declareFun(buildFun(name, false, null, null, params, returns, s, body))
}

/** Alias of [`fun`]. */
context(s: Scope)
public fun func(name: String, returns: TypeName? = null, body: BlockScope.() -> Unit) {
    `fun`(name, returns, body)
}

/** A secondary or primary constructor written as a member. */
context(t: TypeScope)
public fun `constructor`(body: BlockScope.() -> Unit) {
    t.builder.addFunction(buildFun("<init>", true, null, null, emptyList(), null, t) { body() })
}

context(t: TypeScope)
public fun `constructor`(p1: ParameterSpec, body: BlockScope.(Expr) -> Unit) {
    t.builder.addFunction(buildFun("<init>", true, null, null, listOf(p1), null, t) { (a) -> body(a) })
}

/** Alias of [`constructor`]. */
context(t: TypeScope)
public fun ctor(body: BlockScope.() -> Unit) {
    `constructor`(body)
}

/** Detached function builder. */
public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    returns: TypeName? = null,
    body: BlockScope.() -> Unit,
): FunSpec = buildFun(name, false, null, modifiers, emptyList(), returns, null) { body() }

public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    returns: TypeName? = null,
    body: BlockScope.(Expr) -> Unit,
): FunSpec = buildFun(name, false, null, modifiers, listOf(p1), returns, null) { (a) -> body(a) }

/** Detached property builder. */
public fun propertySpec(
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
): PropertySpec {
    val spec = PropertySpec.builder(name, type, modifiers.toList())
    init?.let { spec.initializer("%L", it.code) }
    by?.let { spec.delegate("%L", it.code) }
    return spec.build()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FunctionsTest*'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/site/asm0dey/poetdsl/Declarations.kt src/test/kotlin/site/asm0dey/poetdsl/FunctionsTest.kt
git commit -m "feat: function declarations with scope dispatch and return inference"
```

---

### Task 20: `buildSrc` generator — arity overloads, declaration variants, shadow members

Read [ADR 0002](../../adr/0002-construct-validity-per-scope.md) and [ADR 0004](../../adr/0004-overload-variants-and-arity-cap.md). Three generated files, one variant table, so overloads and their shadows cannot drift apart.

**Files:**
- Create: `buildSrc/settings.gradle.kts`, `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/ArityGenerator.kt`
- Modify: `build.gradle.kts` (register the task, wire the output into `main`)
- Modify: `src/main/kotlin/site/asm0dey/poetdsl/Declarations.kt`, `Bindings.kt` (delete the hand-written variants)
- Test: `src/test/kotlin/site/asm0dey/poetdsl/ArityTest.kt`

**Interfaces:**
- Produces: `FunArity.kt` (54 `fun` + 54 `func` overloads, arities 0–8 × six variants), `CtorArity.kt` (the same for `constructor`/`ctor`), `Shadows.kt` (`@Deprecated(ERROR)` members on `BlockScope` for `object`, `interface`, `constructorParam` and their aliases, one per public overload).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/site/asm0dey/poetdsl/ArityTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.SUSPEND
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArityTest {
    private fun params(n: Int) = (1..n).map { param("p$it", INT) }

    @Test
    fun `arity 8 is generated`() {
        val ps = params(8)
        val out = file("com.example", "Api") {
            `fun`(
                "wide8",
                ps[0], ps[1], ps[2], ps[3], ps[4], ps[5], ps[6], ps[7],
            ) { a, _, _, _, _, _, _, _ -> +a }
        }.toString()
        assertTrue("public fun wide8(" in out)
        assertTrue("p8: Int" in out)
    }

    @Test
    fun `beyond eight uses the list form`() {
        val out = file("com.example", "Api") {
            `fun`("wide12", params = params(12)) { ps -> +ps[11] }
        }.toString()
        assertTrue("p12: Int" in out)
    }

    @Test
    fun `spec style positional call resolves`() {
        val out = file("com.example", "Api") {
            `fun`(PRIVATE + SUSPEND, "greet", param("greeting", STRING)) { greeting ->
                +call("println", greeting)
            }
        }.toString()
        assertTrue("private suspend fun greet(greeting: String)" in out)
    }

    @Test
    fun `single modifier variant resolves`() {
        val out = file("com.example", "Api") {
            `fun`(PRIVATE, "hidden") { +call("work") }
        }.toString()
        assertTrue("private fun hidden()" in out)
    }

    @Test
    fun `constructor family is generated`() {
        val out = file("com.example", "User") {
            `class`("User") {
                `constructor`(param("a", INT), param("b", INT)) { a, b -> +call("init", a, b) }
            }
        }.toString()
        assertTrue("public constructor(" in out)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ArityTest*'`
Expected: FAIL — no overload with `p4`, no single-`KModifier` variant.

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
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

private const val PKG = "site.asm0dey.poetdsl"
private const val MAX_ARITY = 8

/** none | single KModifier | Modifiers, crossed with present/absent annotations. */
private data class Variant(val annotated: Boolean, val modifiers: String?) {
    val leading: String = buildString {
        if (annotated) append("annotations: Annotations, ")
        modifiers?.let { append("modifiers: $it, ") }
    }
    val annotationsArg: String = if (annotated) "annotations" else "null"
    val modifiersArg: String = when (modifiers) {
        null -> "null"
        "KModifier" -> "modifiers.toModifiers()"
        else -> "modifiers"
    }
}

private val VARIANTS: List<Variant> = listOf(
    Variant(false, null),
    Variant(false, "KModifier"),
    Variant(false, "Modifiers"),
    Variant(true, null),
    Variant(true, "KModifier"),
    Variant(true, "Modifiers"),
)

open class ArityGeneratorTask : DefaultTask() {
    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve(PKG.replace('.', '/'))
        dir.parentFile.deleteRecursively()
        dir.mkdirs()
        dir.resolve("FunArity.kt").writeText(funFile())
        dir.resolve("CtorArity.kt").writeText(ctorFile())
        dir.resolve("Shadows.kt").writeText(shadowFile())
    }
}

private fun header() = buildString {
    appendLine("// Generated by ArityGeneratorTask. Do not edit.")
    appendLine("package $PKG")
    appendLine()
    appendLine("import com.squareup.kotlinpoet.KModifier")
    appendLine("import com.squareup.kotlinpoet.ParameterSpec")
    appendLine("import com.squareup.kotlinpoet.TypeName")
    appendLine()
}

private fun funFile(): String = buildString {
    append(header())
    for (name in listOf("`fun`", "func")) {
        for (arity in 0..MAX_ARITY) {
            for (v in VARIANTS) {
                append(funOverload(name, arity, v))
            }
        }
    }
}

private fun funOverload(name: String, arity: Int, v: Variant): String {
    val params = (1..arity).joinToString("") { "    p$it: ParameterSpec,\n" }
    val lambdaParams = (1..arity).joinToString(", ") { "Expr" }
    val bodyType = if (arity == 0) "BlockScope.() -> Unit" else "BlockScope.($lambdaParams) -> Unit"
    val args = (1..arity).joinToString(", ") { "p$it" }
    val destructured = if (arity == 0) "" else "(" + (1..arity).joinToString(", ") { "a$it" } + ")"
    val invoke = if (arity == 0) "body()" else "$destructured -> body(" + (1..arity).joinToString(", ") { "a$it" } + ")"
    return """
        |/** Generated arity-$arity overload. */
        |context(s: Scope)
        |public fun $name(
        |    ${v.leading}name: String,
        |$params    returns: TypeName? = null,
        |    body: $bodyType,
        |) {
        |    s.declareFun(
        |        buildFun(name, false, ${v.annotationsArg}, ${v.modifiersArg}, listOf($args), returns, s) { $invoke },
        |    )
        |}
        |
    """.trimMargin() + "\n"
}

private fun ctorFile(): String = buildString {
    append(header())
    for (name in listOf("`constructor`", "ctor")) {
        for (arity in 0..MAX_ARITY) {
            for (v in VARIANTS) {
                append(ctorOverload(name, arity, v))
            }
        }
    }
}

private fun ctorOverload(name: String, arity: Int, v: Variant): String {
    val params = (1..arity).joinToString("") { "    p$it: ParameterSpec,\n" }
    val lambdaParams = (1..arity).joinToString(", ") { "Expr" }
    val bodyType = if (arity == 0) "BlockScope.() -> Unit" else "BlockScope.($lambdaParams) -> Unit"
    val args = (1..arity).joinToString(", ") { "p$it" }
    val destructured = if (arity == 0) "" else "(" + (1..arity).joinToString(", ") { "a$it" } + ")"
    val invoke = if (arity == 0) "body()" else "$destructured -> body(" + (1..arity).joinToString(", ") { "a$it" } + ")"
    return """
        |/** Generated arity-$arity constructor overload. */
        |context(t: TypeScope)
        |public fun $name(
        |    ${v.leading}
        |$params    body: $bodyType,
        |) {
        |    t.builder.addFunction(
        |        buildFun("<init>", true, ${v.annotationsArg}, ${v.modifiersArg}, listOf($args), null, t) { $invoke },
        |    )
        |}
        |
    """.trimMargin() + "\n"
}

/**
 * Shadow members for constructs invalid inside a function body. Each mirrors a real
 * overload's signature exactly — a `vararg Any?` catch-all silently fails to shadow
 * (measured; see ADR 0002).
 */
private fun shadowFile(): String = buildString {
    appendLine("// Generated by ArityGeneratorTask. Do not edit.")
    appendLine("package $PKG")
    appendLine()
    appendLine("import com.squareup.kotlinpoet.KModifier")
    appendLine("import com.squareup.kotlinpoet.TypeName")
    appendLine()
    appendLine("/** Compile-time guards: constructs that are not valid Kotlin inside a function body. */")
    appendLine("public class BlockScopeShadows internal constructor()")
    appendLine()
    for ((name, reason) in listOf(
        "`object`" to "A named object cannot be local in Kotlin. Declare it at file or type level, or use an anonymous object.",
        "`interface`" to "An interface cannot be local in Kotlin. Declare it at file or type level.",
    )) {
        for (v in VARIANTS.filter { it.modifiers != "KModifier" }) {
            appendLine("@Deprecated(\"$reason\", level = DeprecationLevel.ERROR)")
            appendLine("public fun BlockScope.$name(${v.leading}name: String, body: TypeScope.() -> Unit): Nothing =")
            appendLine("    throw UnsupportedOperationException()")
            appendLine()
        }
    }
    appendLine(
        """
        @Deprecated(
            "constructorParam is only valid inside a class or object body.",
            level = DeprecationLevel.ERROR,
        )
        public fun BlockScope.constructorParam(
            kind: KModifier? = null,
            name: String,
            type: TypeName,
        ): Nothing = throw UnsupportedOperationException()

        @Deprecated(
            "ctorParam is only valid inside a class or object body.",
            level = DeprecationLevel.ERROR,
        )
        public fun BlockScope.ctorParam(
            kind: KModifier? = null,
            name: String,
            type: TypeName,
        ): Nothing = throw UnsupportedOperationException()
        """.trimIndent(),
    )
}
```

The shadows are written as **extensions on `BlockScope`**, not members, because the real declarations are context functions on `Scope` and an extension on the more specific receiver wins. Verify that with the test in Step 6; if extensions do not outrank context functions, move them into the `BlockScope` class body — ADR 0002 proved *members* work, so that is the fallback that is known to hold.

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

- [ ] **Step 5: Delete the hand-written variants**

From `Declarations.kt`: every `context(s: Scope) fun \`fun\`(…)` and `func(…)` overload except the **list form**, and both `` `constructor` ``/`ctor` overloads. From `Declarations.kt` also delete the two-variant `class`/`object`/`interface` overloads if you extend the generator to them; otherwise leave them and note in the README that those three keep two variants only. Keep `param`, `buildFun`, `declareFun`, `declareType`, `constructorParam`, `typeSpec`, `funSpec`, `propertySpec`.

Leaving both hand-written and generated overloads causes redeclaration errors — the build will tell you immediately.

- [ ] **Step 6: Verify the shadow guard actually guards**

Add to `ArityTest`:

```kotlin
    @Test
    fun `the shadow member is a compile error, not a silent fallthrough`() {
        // This test documents intent; the guard itself is verified by the compile test below.
        // Uncommenting the next line inside a block body must fail the build:
        //   file("com.example", "A") { `fun`("f") { `object`("Local") { } } }
        assertTrue(true)
    }
```

and verify manually once: uncomment the line, run `./gradlew compileTestKotlin`, and confirm the error message is the ADR 0002 text. Record the exact message in `docs/adr/0002-construct-validity-per-scope.md` under a **Verified** heading, then re-comment the line.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, including `ArityTest` and every earlier test. If `FunctionsTest` stops resolving a call, the generated signature order differs from the hand-written one — align the generator to `annotations, modifiers, name, p1..pN, returns, body`.

- [ ] **Step 8: Commit**

```bash
git add buildSrc build.gradle.kts src docs/adr
git commit -m "build: generate arity overloads, variants and shadow guards"
```

---

### Task 21: Precedence matrix, escape hatch, extensibility, compile tests

Golden tests are the backbone; compile tests cover the cases where "looks right" is not proof. Compile tests are slow, so there are only a handful.

**Files:**
- Create: `src/test/kotlin/site/asm0dey/poetdsl/PrecedenceMatrixTest.kt`
- Create: `src/test/kotlin/site/asm0dey/poetdsl/ExtensionTest.kt`
- Create: `src/test/kotlin/site/asm0dey/poetdsl/CompileTest.kt`
- Modify: `build.gradle.kts` (kctfork test dependency)

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

- [ ] **Step 2: Write the precedence matrix**

`src/test/kotlin/site/asm0dey/poetdsl/PrecedenceMatrixTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
    fun `calls and value invocation bind tightest`() {
        assertEquals("a.f() + b.g()", (a.call("f") + b.call("g")).toString())
        assertEquals("(a + b).f()", (a + b).call("f").toString())
        assertEquals("(a ?: b).f()", (a elvis b).call("f").toString())
        assertEquals("(a ?: b)(1)", (a elvis b)(1.lit).toString())
        assertEquals("a?.f().g()", a.safeCall("f").call("g").toString())
    }

    @Test
    fun `escape hatch preserves placeholders`() {
        val out = file("com.example", "Esc") {
            `fun`("f", param("xs", com.squareup.kotlinpoet.LIST.parameterizedBy(com.squareup.kotlinpoet.ANY))) { xs ->
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
}
```

Add `import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy` at the top.

Any failure in this file is a real precedence bug in `Prec.kt`/`Operators.kt` — fix the implementation, not the expectation, unless the expectation contradicts the Kotlin grammar.

- [ ] **Step 3: Write the extensibility test**

The spec promises a user-written helper is indistinguishable from a built-in. That is testable, so test it.

`src/test/kotlin/site/asm0dey/poetdsl/ExtensionTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
        assertEquals(
            "val x = find()\nif (x == null) {\n  throw IllegalStateException(\"missing\")\n}\nx\n",
            renderBlock {
                val x = `val`("x", init = call("find"))
                x.orThrow("missing")
                +x
            },
        )
    }
}
```

If this fails to compile, the library's central design promise is broken — stop and report rather than working around it in the test.

- [ ] **Step 4: Write the compile tests**

`src/test/kotlin/site/asm0dey/poetdsl/CompileTest.kt`:

```kotlin
package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.VAL
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
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
                `fun`("f", param("a", INT), param("b", INT), param("c", INT), returns = INT) { a, b, c ->
                    ret((a + b) * c)
                }
            },
        )
    }

    @Test
    fun `imports resolve for type and member references`() {
        assertCompiles(
            file("com.example", "Imports") {
                `fun`("f") { `val`("xs", init = call(member("kotlin.collections", "listOf"), 1.lit)) }
            },
        )
    }

    @Test
    fun `uniquified locals do not collide with members`() {
        assertCompiles(
            file("com.example", "Shadow") {
                `class`("Holder") {
                    constructorParam(VAL, "item", STRING)
                    `fun`("use", param("items", LIST.parameterizedBy(STRING))) { items ->
                        `for`(items) { local -> +call("println", local) }
                    }
                }
            },
        )
    }

    @Test
    fun `lambdas compile with implicit and named parameters`() {
        assertCompiles(
            file("com.example", "Lambdas") {
                `fun`("f", param("xs", LIST.parameterizedBy(STRING))) { xs ->
                    `val`("a", init = xs.call("map") { p -> +p.prop("length") })
                    `val`("b", init = xs.call("map", param = "s") { s -> +s.prop("length") })
                }
            },
        )
    }

    @Test
    fun `delegated properties compile`() {
        assertCompiles(
            file("com.example", "Delegates") {
                `class`("Holder") {
                    `val`("x", INT, by = call(member("kotlin", "lazy")) { +1.lit })
                }
            },
        )
    }

    @Test
    fun `local declarations compile`() {
        assertCompiles(
            file("com.example", "Locals") {
                `fun`("outer") {
                    `fun`("helper", returns = INT) { ret(1.lit) }
                    `class`("Local") { }
                    +call("helper")
                }
            },
        )
    }
}
```

kctfork keeps the original `com.tschuchort.compiletesting` package names. If an import fails to resolve, check `list_javadoc_symbols` for `dev.zacsweers.kctfork:core:0.13.0` — do not guess or decompile.

- [ ] **Step 5: Run everything**

Run: `./gradlew test`
Expected: PASS. The compile tests are slow — tens of seconds each. Report the real counts.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/test/kotlin/site/asm0dey/poetdsl
git commit -m "test: precedence matrix, escape hatch, extensibility and compile tests"
```

---

### Task 22: Alias audit, API surface lock, README, publishing

**Files:**
- Create: `src/test/kotlin/site/asm0dey/poetdsl/AliasTest.kt`
- Create: `README.md`
- Create: `api/kotlin-poet-dsl.api` (generated)
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write the alias equivalence test**

This test *is* the alias table (spec open task 3) in executable form: one assertion per row.

`src/test/kotlin/site/asm0dey/poetdsl/AliasTest.kt`:

```kotlin
package site.asm0dey.poetdsl

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
        bothRender({ `for`(expression("xs")) { x -> +x } }, { forIn(expression("xs")) { x -> +x } })
        bothRender({ `if`(expression("c")) { } }, { ifThen(expression("c")) { } })
        bothRender(
            { `when`(expression("s")) { branch(1.lit) { } } },
            { whenOn(expression("s")) { branch(1.lit) { } } },
        )
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
            file("com.example", "A") { `class`("C") { } }.toString(),
            file("com.example", "A") { klass("C") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `fun`("f") { } }.toString(),
            file("com.example", "A") { func("f") { } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`("C") { `val`("p", INT, init = 1.lit) } }.toString(),
            file("com.example", "A") { `class`("C") { property("p", INT, init = 1.lit) } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`("C") { constructorParam(name = "a", type = STRING) } }.toString(),
            file("com.example", "A") { `class`("C") { ctorParam(name = "a", type = STRING) } }.toString(),
        )
        assertEquals(
            file("com.example", "A") { `class`("C") { `constructor` { } } }.toString(),
            file("com.example", "A") { `class`("C") { ctor { } } }.toString(),
        )
    }
}
```

Any row without an assertion means the alias does not exist yet — add it.

- [ ] **Step 2: Run the alias test**

Run: `./gradlew test --tests '*AliasTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 3: Lock the API surface**

Run: `./gradlew apiDump`
Then read `api/kotlin-poet-dsl.api`. Every entry must be an intentional public declaration; anything accidental gets `internal` and a re-dump.

Run: `./gradlew apiCheck`
Expected: PASS.

- [ ] **Step 4: Write the README**

`README.md`, each section with an example copied from a passing test:

```markdown
# kotlin-poet-dsl

A Kotlin DSL over KotlinPoet that makes generator code read like the Kotlin it generates.

## Requirements

Kotlin **2.4.0 or newer** — the DSL is built on context parameters, Stable since 2.4.
No compiler flags. Consumers below 2.4 cannot use this library.

## Emission model

`Unit`-returning API emits. `Expr`-returning API does not. `val`/`var` are the single
exception: they emit and return a handle. `invoke()` on a spec emits; `invoke()` on an
`Expr` calls that value and emits nothing.

## Scope dispatch

The same construct targets the innermost scope: `fun` is top-level in a file, a member in
a type, a local function in a body. <example from FunctionsTest>

## Every construct has a pure form

<example: stmts { }, funSpec/typeSpec/propertySpec>

## Expressions

<the Kotlin-vs-DSL table with the reason column>

## Lambdas

The rendered parameter name comes from `param =`, not from your own lambda binding.
Omit it for implicit `it`. <example from LambdasTest>

## Writing your own helpers

<the orThrow example from ExtensionTest, verbatim>

## Aliases

<the alias table; note that `property` (declaration) and `prop` (property access) are
deliberately different names>

## Not modelled

Ranges, `in`, `is`, casts, spread, labels, explicit generic arguments, string templates.
All reachable through `expression("…")`, which preserves `%T`/`%M`. Raw strings inside it
bypass scope checking.

## Callable reference limitations

<the table from docs/spikes/2026-08-13-callable-references.md>

## Safety — what is and is not caught

- `@DslMarker` guards the member-based APIs only. It has **no** effect on
  context-parameter resolution (measured; ADR 0001).
- Constructs invalid at a level are compile errors via shadow members: `object`,
  `interface` and `constructorParam` inside a function body.
- Handles carry their declaring scope; using one where that scope does not apply throws at
  generation time, checked when the code is spliced.
- Locals that would shadow a member are uniquified (`name` → `name2`) rather than
  qualified with `this.`.
```

- [ ] **Step 5: Add publishing configuration**

In `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bcv)
    `maven-publish`
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

Signing and the Maven Central repository block are deliberately absent — add them when there is an account to publish to (ADR 0011).

- [ ] **Step 6: Full verification**

Run: `./gradlew clean build apiCheck`
Expected: PASS. Report the real test count and any warnings; do not claim success without the output.

- [ ] **Step 7: Commit**

```bash
git add README.md api build.gradle.kts src/test/kotlin/site/asm0dey/poetdsl/AliasTest.kt
git commit -m "docs: readme, alias audit and locked public API surface"
```

---

## Notes for the implementer

- **Check APIs, do not guess or decompile.** Several steps flag "if this does not exist in 2.3.0, do X". Use the javadocs MCP (`list_javadoc_symbols` / `get_javadoc_symbol` on `com.squareup:kotlinpoet-jvm:2.3.0`) or Context7 (`/square/kotlinpoet`) first. Decompiling is a last resort.
- **Expected-output strings** were written from KotlinPoet's documented formatting, not from a run. A whitespace-only difference: confirm the generated code is valid Kotlin, then align the expectation. A structural difference: that is a real bug — fix the code.
- **Order matters.** Tasks 2 → 7 → 11 → 12 fix the shapes everything else copies. Task 10's local-class branch needs Task 11's `emitCode`; if you hit that, implement Task 11 first.
- **Two spec open tasks are closed by this plan:** `UseSiteTarget.ALL` exists in KotlinPoet 2.3.0 (Task 9 proves it in a test), and the alias table is fixed executably by Task 22. The third — callable-reference resolution — is Task 14.
- **The ADRs are binding.** Where this plan and the original spec disagree, the ADRs say which won and why, with the measurement that settled it.






