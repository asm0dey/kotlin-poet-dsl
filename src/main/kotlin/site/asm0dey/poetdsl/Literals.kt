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
