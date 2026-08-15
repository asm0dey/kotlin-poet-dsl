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
// **Star imports are blocked and are not offered.** E3 justified the block by claiming KotlinPoet
// enforces it too — "every `addImport` overload carries `require(\"*\" !in names)`" — and that is
// **false**, which the fix round found by disassembling the jar rather than by reading two of the
// six overloads. `javap -c` on `FileSpec$Builder`, KotlinPoet 2.3.0:
//
//   | overload                             | carries `Wildcard imports are not allowed` |
//   |--------------------------------------|--------------------------------------------|
//   | `addImport(String, Iterable<String>)`   | yes                                      |
//   | `addImport(ClassName, Iterable<String>)`| yes                                      |
//   | `addImport(MemberName)`                 | **no**                                   |
//   | `addAliasedImport(ClassName, String)`   | **no**                                   |
//   | `addAliasedImport(MemberName, String)`  | **no**                                   |
//   | `addImport(Import)`                     | no — its constructor is `internal`       |
//
// So the three routes E3 left unchecked were exactly the three with nothing behind them, and
// `` `import`(member("kotlin.math", "*")) `` rendered `` import kotlin.math.`*` `` — *unresolved
// reference '*'* on all three frontends 2.4.10, and an error rather than a warning in Kotlin 2.5.
// Same class as D20. The block is now this DSL's own, asked of **every segment that is emitted**
// rather than of the `names` vararg alone, since a `MemberName`'s simple name, a `ClassName`'s
// simple names and a package name all reach the output. `ImportsTest` carries the canary, and the
// canary is written against the *rendered output* of the three unchecked overloads, because there
// is no exception to catch — that is the finding.
//
// **No shadow, and it is the first construct in this DSL where `context(f: FileScope)` reaching into
// a nested type or a block body is the *right* answer.** The 141 shadows exist because a
// `context(t: TypeScope)` construct written in a member body silently attaches to the enclosing
// **type**, which is the wrong container. An import has exactly one container — the file — so a call
// from anywhere inside it means the same thing, and a caller emitting a helper deep in a type body
// can ask for the import the helper needs where the helper is written. The detached `typeSpec`,
// `funSpec` and `propertySpec` builders have no [FileScope] at all, so a call there is *no context
// argument for 'f: FileScope' found* at the caller's own compile.

/**
 * Rejects a wildcard in **anything that will be emitted** — Global Constraint 26 where KotlinPoet
 * has a `require` of its own to get in front of, and the only check at all on the three overloads
 * where it has none. See this file's header for which are which.
 *
 * [segments] is every string that reaches the rendered import line: the package name, a
 * `ClassName`'s or `MemberName`'s own names, and the `names` vararg. Checking the vararg alone was
 * E3's shape and it left `` `import`(member("kotlin.math", "*")) `` rendering.
 */
private fun checkNotStar(construct: String, segments: List<String>) {
    check(segments.none { "*" in it }) {
        "$construct: a star import is not available. It renders `` import x.`*` ``, which is " +
            "\"unresolved reference '*'\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. " +
            "KotlinPoet 2.3.0 refuses one from `addImport(packageName, names)` and " +
            "`addImport(className, names)` (`require(\"*\" !in names)`) and keeps `Import`'s " +
            "constructor internal — but `addImport(memberName)` and both `addAliasedImport` " +
            "overloads carry no such check, so this one is ours. Name the members you need, or let " +
            "`%T`/`%M` import them — `reference<T>()`, `className(…)` and `member(…)` all resolve " +
            "their own imports."
    }
}

/** Every segment of a [MemberName] that reaches the rendered import line. See [checkNotStar]. */
private fun MemberName.importSegments(): List<String> =
    listOf(packageName, simpleName) + (enclosingClassName?.simpleNames ?: emptyList())

/** Every segment of a [ClassName] that reaches the rendered import line. See [checkNotStar]. */
private fun ClassName.importSegments(): List<String> = listOf(packageName) + simpleNames

/**
 * `import kotlin.math.PI` — one or more members of a package, by name.
 *
 * **[names] may not be empty.** Its KDoc said it may, "which imports nothing", and the code raised
 * KotlinPoet's `IllegalArgumentException: names array is empty` — so the documented shape was one
 * the construct never had. There is no output to give it either: `import kotlin.math` is *packages
 * cannot be imported* on the JVM, on Kotlin/JS and on Kotlin/Wasm alike, so this is a refusal rather
 * than a widening. To import a *type*, use the [ClassName] overload with no [names].
 *
 * Every name is a *simple* name in [packageName], so a nested type is reached through the
 * [ClassName] overload rather than by writing `Outer.Inner` here.
 */
context(f: FileScope)
public fun `import`(packageName: String, vararg names: String) {
    checkNotStar("`import`", listOf(packageName) + names)
    check(names.isNotEmpty()) {
        "`import`: \"$packageName\" is imported with no names, and there is no such import in " +
            "Kotlin — `import $packageName` is \"packages cannot be imported\" on the JVM, on " +
            "Kotlin/JS and on Kotlin/Wasm alike, and KotlinPoet raises \"names array is empty\" " +
            "before it gets that far. Name the members you want — `import`(\"$packageName\", " +
            "\"first\", \"second\") — or, to import a type itself, pass its ClassName: " +
            "`import`(className(\"$packageName\", \"Thing\"))."
    }
    f.builder.addImport(packageName, *names)
}

/**
 * `import com.example.Outer.Inner` — a type, or a member of one.
 *
 * With no [names] this imports [type] itself, which is what `%T` would have done anyway; with them it
 * imports the named members *of* [type] — a companion object's constants, an enum's entries.
 *
 * The zero-[names] half of that sentence was documented and did not work: it reached KotlinPoet's
 * `addImport(className, *names)`, whose `require` is *names array is empty*. It works now, and it
 * works by spelling the type as a member of its own package — `addImport(packageName,
 * "Outer.Inner")` — because the one dotted string is what renders `import com.example.Outer.Inner`.
 * Passing the simple names as separate arguments does not: KotlinPoet reads them as siblings and
 * emits `import com.example.Inner` beside `import com.example.Outer`.
 */
context(f: FileScope)
public fun `import`(type: ClassName, vararg names: String) {
    checkNotStar("`import`", type.importSegments() + names)
    if (names.isEmpty()) {
        f.builder.addImport(type.packageName, type.simpleNames.joinToString("."))
    } else {
        f.builder.addImport(type, *names)
    }
}

/** `import kotlin.math.min` — a top-level function or property, as [member] names it. */
context(f: FileScope)
public fun `import`(member: MemberName) {
    checkNotStar("`import`", member.importSegments())
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
    checkNotStar("aliasedImport", type.importSegments())
    f.checkAlias(alias)
    f.builder.addAliasedImport(type, alias)
}

/** `import kotlin.math.abs as absolute` — the [MemberName] twin of the [aliasedImport] above. */
context(f: FileScope)
public fun aliasedImport(member: MemberName, alias: String) {
    checkNotStar("aliasedImport", member.importSegments())
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
