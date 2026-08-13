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
