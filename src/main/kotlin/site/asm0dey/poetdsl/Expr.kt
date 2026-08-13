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
