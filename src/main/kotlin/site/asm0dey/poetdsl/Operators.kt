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
