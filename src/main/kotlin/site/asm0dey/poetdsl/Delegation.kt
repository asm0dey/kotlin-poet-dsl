package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock

// Deviation D25: the constructor delegation calls, `: this(…)` and `: super(…)`.
//
// Written *inside* the secondary constructor's body, reading like the Kotlin they generate:
//
//     `class`("User", param(VAL, "id", LONG), param(VAL, "name", STRING)) { id, name ->
//         `constructor`(param("id", LONG)) { pid ->
//             `this`(pid, "anonymous".lit)          // constructor(id: Long) : this(id, "anonymous")
//         }
//     }
//
// A `delegatesTo = …` parameter on `` `constructor` `` was the alternative and was rejected: it
// would multiply against that construct's arity 0-8 × six-variant matrix — 120 real overloads and
// 120 shadows — for something written at most once per constructor.
//
// Neither construct emits. A delegation call belongs to the constructor's *header*, so it is
// captured in [BlockScope.delegation] and handed to `FunSpec.Builder.callThisConstructor` /
// `callSuperConstructor` by [buildFun]; it deliberately does not go through `emitCode`, which would
// render it as a statement in the body. That also means writing it after other statements is fine —
// it lands in the header wherever it appears.

/**
 * Which constructor is delegated to. An enum rather than the raw `"this"`/`"super"` string
 * KotlinPoet stores, so [buildFun]'s dispatch is exhaustive without an unreachable `else` branch;
 * [keyword] is both what the message prints and what `FunSpec.delegateConstructor` compares equal to.
 */
internal enum class DelegationTarget(val keyword: String) {
    THIS("this"),
    SUPER("super"),
}

/**
 * The one-shot slot a secondary constructor's body writes its delegation call into.
 *
 * A mutable holder rather than two `var`s on [BlockScope]: [buildFun] creates one only for a
 * constructor body, so a `null` slot *is* the "not a secondary constructor" answer, and nothing
 * else in this DSL has to know the field exists.
 */
internal class ConstructorDelegation {
    /** Null until [`this`] or [`super`] is called; one of them, once. */
    var target: DelegationTarget? = null

    var args: List<CodeBlock> = emptyList()
}

/**
 * `: this(args)` — delegates to another constructor of the same class.
 *
 * Required by Kotlin of every secondary constructor of a class that has a primary one; without it
 * the pair is `e: Primary constructor call expected.`, which is why declaring both was rejected
 * outright before D25.
 *
 * Valid only directly in a secondary `` `constructor` ``'s body: Kotlin has no delegation call
 * inside an `if`, a lambda or an ordinary function, and neither has this. Callable at most once,
 * counting [`super`] — a constructor delegates to exactly one other constructor.
 */
context(b: BlockScope)
public fun `this`(vararg args: Expr) {
    b.delegateConstructor(DelegationTarget.THIS, args)
}

/**
 * `: super(args)` — delegates to the superclass constructor.
 *
 * The other legal home for the arguments `superclass(Base, …)` would otherwise carry in the class
 * header (D26): a class may pass them in the header **or** here, never both, because the header
 * form needs a primary constructor and this form exists precisely for a class that has none.
 *
 * Same two rules as [`this`]: only directly in a secondary `` `constructor` ``'s body, and only
 * once per constructor.
 */
context(b: BlockScope)
public fun `super`(vararg args: Expr) {
    b.delegateConstructor(DelegationTarget.SUPER, args)
}

/**
 * Records the delegation call, having checked the two things that make it valid.
 *
 * The handles are validated against this block exactly as a statement's are (ADR 0008): a
 * delegation call is emitted with the constructor it belongs to, so a handle from a foreign scope
 * is as wrong there as anywhere else.
 */
private fun BlockScope.delegateConstructor(target: DelegationTarget, args: Array<out Expr>) {
    val keyword = target.keyword
    val slot = delegation
    check(slot != null) {
        "`$keyword`: a constructor delegation call is only valid directly in a secondary " +
            "`constructor`'s body — not in a function body, a lambda, a nested block or a " +
            "`stmts { }` fragment."
    }
    check(slot.target == null) {
        "`$keyword`: this constructor already delegates with `${slot.target?.keyword}(…)`. A " +
            "constructor delegates to exactly one other constructor."
    }
    args.forEach { checkOwned(it) }
    slot.target = target
    // One `CodeBlock` per argument, not one joined block, for the same reason `superclass` keeps
    // them separate: KotlinPoet joins them itself and can wrap a long delegation call. `%L` of the
    // expression's own code keeps `%T`/`%M` placeholders intact, so imports still resolve.
    slot.args = args.map { CodeBlock.of("%L", it.code) }
}
