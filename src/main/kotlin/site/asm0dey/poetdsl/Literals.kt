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

public val Double.literal: Expr
    get() {
        check(isFinite()) { "Double.literal: NaN and Infinity have no Kotlin literal form." }
        return Expr(CodeBlock.of("%L", this), DOUBLE)
    }
public val Double.lit: Expr get() = literal

public val Float.literal: Expr
    get() {
        check(isFinite()) { "Float.literal: NaN and Infinity have no Kotlin literal form." }
        return Expr(CodeBlock.of("%LF", this), FLOAT)
    }
public val Float.lit: Expr get() = literal

public val Boolean.literal: Expr get() = Expr(CodeBlock.of("%L", this), BOOLEAN)
public val Boolean.lit: Expr get() = literal

/** A char literal. Escaping is done here, not by KotlinPoet. */
public val Char.literal: Expr get() = Expr(CodeBlock.of("%L", "'${escapeCharLiteral(this)}'"), CHAR)
public val Char.lit: Expr get() = literal

/**
 * Escapes a single character for use inside a Kotlin char literal (the body between the
 * quotes). KotlinPoet 2.3.0 has no public `%C`-style specifier or exposed helper for this
 * (`characterLiteralWithoutSingleQuotes` in its `Util.kt` is `internal`), so this is a
 * minimal, from-scratch implementation covering the JLS/Kotlin escape set.
 */
private fun escapeCharLiteral(c: Char): String = when (c) {
    '\\' -> "\\\\"
    '\'' -> "\\'"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\t' -> "\\t"
    '\b' -> "\\b"
    else -> if (c.isISOControl() || c.isSurrogate()) "\\u%04x".format(c.code) else c.toString()
}

/** A string literal. Escaping is KotlinPoet's `%S`. */
public val String.literal: Expr get() = Expr(CodeBlock.of("%S", this), STRING)
public val String.lit: Expr get() = literal

/**
 * `[a, b, c]` — a Kotlin collection literal, joining a **computed** list of expressions into one
 * (deviation D28).
 *
 * The shape it exists for is an annotation argument built from a model, where the elements are
 * derived rather than written out:
 *
 *     val nodes = names.map { expression("%T(%L)", nan, it.lit) }
 *     annotation(neg, "attributeNodes" to arrayLiteral(nodes))
 *
 * Without it that call needs a hand-built format string — `nodes.joinToString(",·") { "%L" }` and a
 * spread — which leaks exactly the KotlinPoet formatting the DSL exists to hide. The elements' own
 * `%T`/`%M` placeholders survive, so imports still resolve.
 *
 * **Not** named `arrayOf`, deliberately: that is a stdlib function every caller has imported by
 * default, and shadowing it inside a generator would be a trap in precisely the context this helper
 * is used in. There is no bare comma-joined sibling either — every variadic position in this DSL
 * (`call`, `annotation`, `superclass`, `` `this` ``) takes a `vararg Expr`, so a computed list
 * already reaches them as `*list.toTypedArray()`; the bracketed form is the one with no such route.
 *
 * The scopes of every element are carried through, exactly as `call` carries its arguments' — so a
 * joined list of literals stays a compile-time constant that an annotation argument accepts, and one
 * built from handles is rejected there by the same check any other handle would be.
 */
public fun arrayLiteral(elements: List<Expr>): Expr =
    Expr(
        CodeBlock.of("[%L]", argList(elements)),
        usedScopes = elements.flatMapTo(mutableSetOf()) { it.usedScopes },
    )

/** Alias of [arrayLiteral]. */
public fun arrayLit(elements: List<Expr>): Expr = arrayLiteral(elements)

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
