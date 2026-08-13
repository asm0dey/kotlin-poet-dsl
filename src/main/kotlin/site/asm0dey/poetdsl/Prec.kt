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
