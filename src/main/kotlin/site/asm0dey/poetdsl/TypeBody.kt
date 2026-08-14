package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

// Deviation D29: the two body-level constructs a generated type could not express — the `init { }`
// block that validates a constructor's arguments, and the companion object that holds the factory
// methods and constants.
//
//     `class`("User", param(VAL, "id", LONG), param(null, "seed", INT)) { id, seed ->
//         `val`("cache", INT, init = seed)                 // plain parameter: visible here
//         `init` { +call("require", id gt 0.lit) }         // and here
//         companionObject { `fun`("of", param("raw", STRING)) { raw -> ret(…) } }
//     }
//
// Neither goes through ADR 0004's six-variant matrix. An `init` block takes no annotations and no
// modifiers at all — Kotlin has nowhere to put them — and a companion object takes none in practice;
// the only axis either has is the companion's name, which is two overloads. The generated surface is
// already past 500 declarations, and a variant matrix here would buy nothing that is valid Kotlin.
//
// The public entry points are *generated*, from the same list that generates their
// `@Deprecated(ERROR)` shadows — `buildSrc/src/main/kotlin/ArityGenerator.kt`, D7 — for the reason
// D26's supertypes are: a shadow that is not derived from the declaration it mirrors is the
// hand-maintained second list the generator exists to prevent. Both need one, and for the same
// measured reason `` `constructor` `` does: `context(t: TypeScope)` does not stop a call inside a
// member body, where the enclosing type's context parameter is still in scope, so an `init { }`
// written in a function would silently attach an initializer block to the enclosing type.

/**
 * What the generated `` `init` `` runs.
 *
 * The block's [BlockScope] is built one level *inside* the type on both axes, which is what makes an
 * initializer see exactly what Kotlin says it sees (D30):
 *
 * - its [ScopeId] is [TypeScope.initializerId]`.child("init")`, so ADR 0008 accepts a handle from
 *   the type itself (a property, a `val`/`var` primary-constructor parameter) *and* one from the
 *   initializer level (a plain primary-constructor parameter), while a handle from any unrelated
 *   scope is rejected exactly as it is in a member body — [checkOwned] is untouched;
 * - its [NameScope] is [TypeScope.initializerNames]`.child()`, so a local declared in the block
 *   renames away from both levels, and the name is released again when the block ends.
 *
 * `return` is not allowed in an initializer block at all. A `return` **with a value** is rejected
 * here, off the types the body recorded — the same read [buildFun] makes for a constructor body. A
 * valueless `ret()` records nothing anywhere in this DSL, so it is left to kotlinc, the way
 * `` `super` `` with no declared superclass is.
 */
internal fun TypeScope.addInitializerBlock(body: BlockScope.() -> Unit) {
    check(kindName != "interface") {
        "`init`: an interface cannot have an initializer block; it has no state to initialize. " +
            "Move the code to a property initializer or a function."
    }
    val recorded = mutableListOf<TypeName?>()
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = initializerNames.child(),
        id = initializerId.child("init"),
        returns = recorded,
    )
    scope.body()
    scope.flushPending()
    check(recorded.isEmpty()) {
        "`init`: an initializer block cannot return a value; Kotlin allows no `return` there. " +
            "Move the code to a function, or make it a property initializer."
    }
    builder.addInitializerBlock(scope.builder.build())
}

/**
 * What both generated `companionObject` overloads run; [name] is null for the anonymous form,
 * which Kotlin renders as a bare `companion object` and refers to as `Companion`.
 *
 * The companion's [NameScope] is a fresh root, and its [ScopeId] is re-parented at the *file* —
 * [TypeScope.fileId]`.child("companion object")`, the same shape [declareType] gives a nested type,
 * and for the same reason: a companion object is a *nested* object, so it cannot see the enclosing
 * type's instance members, and chaining to that type would carry them in. Chaining the names would
 * rename the companion's own members against members they can never shadow; chaining the [ScopeId]
 * is worse — it would make [checkOwned] *accept* an enclosing-instance handle (a constructor
 * parameter or property of the type the companion belongs to) inside the companion body, and Kotlin
 * does not: `class F(val id: Long) { companion object { fun show() { println(id) } } }` is
 * `e: Unresolved reference 'id'.` (measured). Parenting at [TypeScope.fileId] rejects that exactly
 * as a bare root would — the enclosing type's own [ScopeId] is a *sibling* under the same
 * [TypeScope.fileId], so [ScopeId.isAncestorOf] walks up from the use site and never reaches it,
 * any more than it reaches a handle smuggled in from an unrelated scope.
 *
 * The [ScopeId] used to be a bare `ScopeId(null, "companion object")`, rooted **unconditionally** —
 * which cost *every* companion, at any depth including one directly inside a top-level class, its
 * access to file-level handles: `` `val`("limit", …) `` at file level used inside
 * `` `class`("Top") { companionObject { `fun`("f") { +call("println", limit) } } } `` threw, while
 * kotlinc compiles the equivalent Kotlin cleanly. [TypeScope.fileId] closed that; a file-level
 * handle's owner *is* [TypeScope.fileId], which is always an ancestor of the companion's body.
 *
 * Its [TypeScope.kindName] is `"companion object"`, which is what makes the messages of the
 * constructs it does *not* support name it: an object has no constructors, so
 * `` `constructor` `` inside one says so, and a companion object inside a companion object is
 * refused by the kind check below rather than rendered.
 */
internal fun TypeScope.addCompanionObject(name: String?, body: TypeScope.() -> Unit) {
    check(kindName == "class" || kindName == "interface") {
        "companionObject: a $kindName cannot declare a companion object; only a class or an " +
            "interface can."
    }
    check(!hasCompanionObject) {
        "companionObject: this $kindName already declares a companion object, and Kotlin allows one."
    }
    // The anonymous form is still a declared name — `Companion` — so it collides with a nested type
    // of that name exactly as a named one does, and is registered under it.
    val declaredName = name ?: "Companion"
    check(declaredName !in declaredTypeNames) {
        "A type named \"$declaredName\" is already declared in this scope."
    }
    hasCompanionObject = true
    declaredTypeNames += declaredName
    val scope = TypeScope(
        TypeSpec.companionObjectBuilder(name),
        NameScope(null),
        fileId.child("companion object"),
        "companion object",
        fileId,
    )
    scope.body()
    builder.addType(scope.finish())
}
