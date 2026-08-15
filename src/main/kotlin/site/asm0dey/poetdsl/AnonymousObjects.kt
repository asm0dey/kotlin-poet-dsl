package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

// E3, deviation D43: `object : Runnable { … }` as a **value**.
//
// Every other construct in this DSL is a declaration; this one is an expression, which is what makes
// it the only place ADR 0008's question — *what handles escape it?* — has a new answer to give.

/**
 * `object : Base(args), Iface { … }` — an anonymous object, as an [Expr].
 *
 * The only construct in this DSL that produces a **value** rather than a declaration, so it is used
 * wherever an expression is: a property initializer, an argument, a `ret(…)`. It emits nothing by
 * itself, which is why it takes `context(s: Scope)` and needs no `@Deprecated(ERROR)` shadow — a
 * block body is its most common home, not a mistake.
 *
 * Its [body] is a [TypeScope] whose members read exactly as a class's do, minus what the language
 * takes away. Measured, one file per cell, `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, all
 * three agreeing on every row (D43):
 *
 *     object { val p: Int = 1 }        clean    object { constructor(q: Int) }  objects cannot have
 *     object { var p: Int = 1 }        clean                                     constructors.
 *     object { fun f(): Int = 1 }      clean    object { class N }              'Class' is prohibited
 *     object { fun g() { } }           clean                                     here.
 *     object { init { } }              clean    object { object O }             named object 'O'
 *     object { override fun … }        clean                                     cannot be local.
 *     object { private val p: Int = 1 }   clean object { interface I }          'Interface' is
 *     object { protected val p: Int = 1 } clean                                  prohibited here.
 *                                              object { companion object }      modifier 'companion'
 *                                                                                is not applicable.
 *
 * `protected` is the **one** cell of 192 where this body and an enum entry's differ — clean here,
 * *modifier 'protected' is not applicable inside 'enum entry'* there — which is why the two are
 * separate [TypeScope.kindName]s and not one.
 *
 * **Supertypes are parameters here and body constructs everywhere else**, and that is forced rather
 * than chosen. ADR 0002's `BlockScope` shadows are *extension* functions and a context parameter
 * loses to an extension receiver in Kotlin's resolution, so inside
 * `` `fun`("f") { `val`("v", init = anonymousObject { superinterface(I) }) } `` the enclosing
 * function body's `BlockScope` is still an implicit receiver and `superinterface` binds to
 * `BlockScope.superinterface`, the `@Deprecated(ERROR)` shadow. That is the limitation ADR 0002
 * already records for a `typeSpec { }` written lexically inside a block — except that a block body is
 * this construct's *most common* home, so inheriting it would leave the construct unable to declare
 * the supertype it exists to implement, and only when written in a block. Parameters resolve the same
 * way in every scope.
 *
 * [superclassArgs] are not optional where the superclass has a constructor: `object : Base0 { }`
 * without parentheses is *this type has a constructor, so it must be initialized here*. KotlinPoet
 * writes them from `%T(%L)`, so there is nothing to guard.
 *
 * `` `init` `` has the same shadow and no parameter, so an initializer block is unreachable in an
 * anonymous object written inside a block body. A property initializer does the same work; recorded
 * rather than worked around, because a second parameter for one rare member buys less than it costs
 * on a surface about to be locked.
 *
 * **The one language rule here that needs no guard, and the reason is not the obvious one.** A
 * *non-private* declaration whose inferred type is an anonymous object with **two or more**
 * supertypes is *right-hand side has an anonymous type. Specify the type explicitly.* Measured, all
 * three frontends:
 *
 *     val v = object : Iface, J { … }                right-hand side has an anonymous type.
 *     class C { val v = object : Iface, J { … } }    right-hand side has an anonymous type.
 *
 *     val v = object { … }                           clean — nought or one supertype collapses the
 *     val v = object : Iface { … }                   clean   inferred type, so this never fires
 *     val v: Iface = object : Iface, J { … }         clean
 *     private val v = object : Iface, J { … }        clean
 *     fun h() { val v = object : Iface, J { … } }    clean — a local infers freely
 *
 * This DSL cannot render either invalid row: a file-level or member `` `val` `` already *requires*
 * an explicit type (`Property 'v' requires an explicit type; KotlinPoet cannot infer it`), and a
 * local — the one binding that may omit it — is the clean row. So the rule is closed by a check that
 * predates this construct, not by a new one. Both halves are built in the test rather than reasoned
 * about, which is what separates "unreachable" from "untested".
 *
 * ADR 0008: the body's [ScopeId] is a **child of the enclosing scope's**, not re-parented at the file
 * the way [declareType] roots a nested type — because an anonymous object *captures* and a nested
 * class does not. Measured, all three frontends: `fun h() { val x = 1; val v = object { fun f(): Int
 * = x } }` and `class O(val id: Long) { val v = object { fun f(): Long = id } }` are clean, while
 * `class O(val id: Long) { class N { fun f(): Long = id } }` is *outer class … of non-inner class
 * cannot be used as receiver*. So [checkOwned] accepts an enclosing handle here and rejects a foreign
 * one, with no change to [checkOwned] itself.
 *
 * The returned handle carries **no** used scopes of its own. Nothing inside the body can smuggle a
 * handle out: every use is checked at its own member site against a scope chained under this one, and
 * a member body's [BlockScope] is built by [buildFun] with `detachedRoot = false` because its parent
 * is a [TypeScope]. What that does *not* give is the `stmts { }` fragment's recording behaviour — an
 * anonymous object built inside a detached fragment checks its members eagerly instead of deferring
 * to the splice. That is the strict direction, and it is stated rather than discovered.
 */
context(s: Scope)
public fun anonymousObject(
    superclass: TypeName? = null,
    vararg superclassArgs: Expr,
    superinterfaces: List<TypeName> = emptyList(),
    body: TypeScope.() -> Unit = { },
): Expr {
    val scope = TypeScope(
        TypeSpec.anonymousClassBuilder(),
        s.names.child(),
        s.id.child(ANONYMOUS_OBJECT),
        ANONYMOUS_OBJECT,
        if (s is TypeScope) s.fileId else s.id,
        // Inherited exactly as a nested type's is: an anonymous object inside an `expect` type is
        // reached only through a property initializer, which the `expect` family already refuses, but
        // the fact belongs to the whole nest and this is the site that propagates it.
        s.isExpectContainer,
        s.isExternalContainer,
    )
    // Through the same machinery the body constructs call, so every guard `superclass` and
    // `superinterface` carry — one superclass, no duplicate interface, `kotlin.Any` with arguments,
    // the `expect` family's — still runs. Only the *spelling* moved.
    superclass?.let { scope.applySuperclass(it, superclassArgs) }
    superinterfaces.forEach { scope.applySuperinterface(it) }
    check(superclass != null || superclassArgs.isEmpty()) {
        "anonymousObject: constructor arguments were given with no superclass to pass them to. An " +
            "`object { }` with no supertype takes none."
    }
    scope.body()
    // `finish()` rather than a bare `build()`, unlike an enum entry's body: an anonymous object may
    // declare a superclass with arguments, so the checks there about a header — the parentheses, the
    // secondary constructors — have something to answer about. Every one of them is a no-op for a
    // body that declares none.
    return Expr(CodeBlock.of("%L", scope.finish()))
}
