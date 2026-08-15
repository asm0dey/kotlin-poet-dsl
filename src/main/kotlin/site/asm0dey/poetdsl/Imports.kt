package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

// E3, deviation D43: explicit and aliased imports.
//
// `%T` and `%M` already import everything a generated file *names*, which is why D31 filed this as
// "escape-hatch only" rather than absent. Two things they do not do:
//
//   - **an aliased import is the only fix when two generated types share a simple name.** KotlinPoet
//     falls back to a fully-qualified name for the second one, which compiles and reads badly;
//     `import com.b.User as BUser` is the fix and there was no way to ask for it.
//   - an explicit import of something the file never names through a placeholder — an extension
//     function brought into scope so that `expression("xs.sorted()")` resolves, say.
//
// **Star imports are blocked and are not offered**, which the E3 feasibility note established and
// this round confirms at the API boundary: every `addImport` overload carries
// `require("*" !in names)`, `Import`'s constructor is `internal`, and the one route left renders
// `` import com.foo.`*` ``, which is *unresolved reference '*'* on all three frontends and becomes an
// error in Kotlin 2.5. Same class as D20. `ImportsTest` carries the canary.
//
// **No shadow, and it is the first construct in this DSL where `context(f: FileScope)` reaching into
// a nested type or a block body is the *right* answer.** The 141 shadows exist because a
// `context(t: TypeScope)` construct written in a member body silently attaches to the enclosing
// **type**, which is the wrong container. An import has exactly one container — the file — so a call
// from anywhere inside it means the same thing, and a caller emitting a helper deep in a type body
// can ask for the import the helper needs where the helper is written. The detached `typeSpec`,
// `funSpec` and `propertySpec` builders have no [FileScope] at all, so a call there is *no context
// argument for 'f: FileScope' found* at the caller's own compile.

/** Rejects a wildcard before KotlinPoet's own `require` does (Global Constraint 26). */
private fun checkNotStar(construct: String, names: List<String>) {
    check(names.none { "*" in it }) {
        "$construct: a star import is not available. KotlinPoet 2.3.0 refuses one from every " +
            "`addImport` overload (`require(\"*\" !in names)`) and keeps `Import`'s constructor " +
            "internal, so the only route left renders `` import x.`*` ``, which is \"unresolved " +
            "reference '*'\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. Name the members " +
            "you need, or let `%T`/`%M` import them — `reference<T>()`, `className(…)` and " +
            "`member(…)` all resolve their own imports."
    }
}

/**
 * `import kotlin.math.PI` — one or more members of a package, by name.
 *
 * [names] may be empty, which imports nothing and is the shape `` `import`(className) `` uses; pass
 * at least one to be useful. Every name is a *simple* name in [packageName], so a nested type is
 * reached through the [ClassName] overload rather than by writing `Outer.Inner` here.
 */
context(f: FileScope)
public fun `import`(packageName: String, vararg names: String) {
    checkNotStar("`import`", names.toList())
    f.builder.addImport(packageName, *names)
}

/**
 * `import com.example.Outer.Inner` — a type, or a member of one.
 *
 * With no [names] this imports [type] itself, which is what `%T` would have done anyway; with them it
 * imports the named members *of* [type] — a companion object's constants, an enum's entries.
 */
context(f: FileScope)
public fun `import`(type: ClassName, vararg names: String) {
    checkNotStar("`import`", names.toList())
    f.builder.addImport(type, *names)
}

/** `import kotlin.math.min` — a top-level function or property, as [member] names it. */
context(f: FileScope)
public fun `import`(member: MemberName) {
    f.builder.addImport(member)
}

/**
 * `import com.b.User as BUser` — the motivating case, and the only fix for it.
 *
 * When two generated types share a simple name, KotlinPoet resolves the collision by writing the
 * second one fully qualified: `com.b.User` in every position. That compiles, and it is what a
 * generator produces when it has no way to ask for anything else. An alias replaces it with a name.
 *
 * [alias] must be a valid Kotlin identifier; it is emitted verbatim. Two aliased imports sharing one
 * alias are refused — Kotlin's answer is *conflicting import: imported name 'X' is ambiguous* — which
 * KotlinPoet does not check, because its import set is keyed on the imported name rather than on the
 * alias.
 */
context(f: FileScope)
public fun aliasedImport(type: ClassName, alias: String) {
    f.checkAlias(alias)
    f.builder.addAliasedImport(type, alias)
}

/** `import kotlin.math.abs as absolute` — the [MemberName] twin of the [aliasedImport] above. */
context(f: FileScope)
public fun aliasedImport(member: MemberName, alias: String) {
    f.checkAlias(alias)
    f.builder.addAliasedImport(member, alias)
}

/**
 * The one thing KotlinPoet does not check about an alias: that no two of them collide.
 *
 * Its `memberImports` is a `TreeSet<Import>` ordered by the *imported* name, so two aliased imports
 * of different types under one alias are two distinct entries and both are emitted. Kotlin's answer is
 * *conflicting import: imported name 'A' is ambiguous* on all three frontends; the control —
 * `import kotlin.collections.List` beside `import kotlin.collections.List as L2` — is clean, so this
 * is keyed on the alias and never on the type.
 */
private fun FileScope.checkAlias(alias: String) {
    check(alias.isNotBlank()) {
        "aliasedImport: the alias is blank. An aliased import is emitted verbatim as " +
            "`import x.Y as <alias>`, so the alias has to be a Kotlin identifier."
    }
    check(declaredImportAliases.add(alias)) {
        "aliasedImport: \"$alias\" is already used by another aliased import in this file, and " +
            "Kotlin answers a second one with \"conflicting import: imported name '$alias' is " +
            "ambiguous\". KotlinPoet keeps its imports keyed on the *imported* name, so it emits " +
            "both. Pick a different alias."
    }
}
