# kotlin-poet-dsl

A Kotlin DSL over [KotlinPoet](https://github.com/square/kotlinpoet) whose generator code reads like
the Kotlin it generates — and which **refuses rather than guesses**. Where it has measured the
language, a call that would produce Kotlin no compiler accepts throws instead of rendering: it names
the construct, quotes the sentence the frontend would have printed, and says what to write instead.
Where it has not, it says so — that list is [below](#what-it-does-not-guarantee), and it is the most
useful part of this file.

```
site.asm0dey:kotlin-poet-dsl:0.1.0-SNAPSHOT
```

Built and tested against Kotlin 2.4.10 and KotlinPoet 2.3.0, on a JVM 17 toolchain. Every construct
is a `context(…)` function, so a consumer needs a compiler that accepts context parameters.
**Not published to any repository yet** — `./gradlew publishToMavenLocal` is how you get it today.

---

## The example

A JPA-style entity: an annotation whose type is only a `ClassName`, named arguments, and an array of
nested annotations built from a list the generator computed.

<!-- readme-example -->
```kotlin
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import site.asm0dey.poetdsl.*

fun user(): String {
    val entity = ClassName("com.example.jpa", "Entity")
    val graph = ClassName("com.example.jpa", "NamedEntityGraph")
    val node = ClassName("com.example.jpa", "NamedAttributeNode")
    val eager = listOf("orders", "address")

    return file("com.example.app", "User") {
        `class`(
            annotation(entity) + annotation(
                graph,
                "name" to "User.detail".lit,
                "attributeNodes" to arrayLit(eager.map { expression("%T(%L)", node, it.lit) }),
            ),
            "User",
            param(ParamKind.VAL, "id", LONG),
            param(ParamKind.VAL, "name", STRING),
        ) { id, name ->
            `fun`("label", returns = STRING) {
                `if`(name.call("isBlank")) {
                    ret("user-".lit + id.call("toString"))
                }
                ret(name)
            }
        }
    }.toString()
}
```

renders

<!-- readme-output -->
```kotlin
package com.example.app

import com.example.jpa.Entity
import com.example.jpa.NamedAttributeNode
import com.example.jpa.NamedEntityGraph
import kotlin.Long
import kotlin.String

@Entity
@NamedEntityGraph(
  name = "User.detail",
  attributeNodes = [NamedAttributeNode("orders"), NamedAttributeNode("address")],
)
public class User(
  public val id: Long,
  public val name: String,
) {
  public fun label(): String {
    if (name.isBlank()) {
      return "user-" + id.toString()
    }
    return name
  }
}
```

Both blocks above are extracted from this file by `ReadmeTest` on every build: the first is compiled
and **run**, its output is compared against the second character for character, and the second is
compiled together with a stub declaring the three annotations. If the API moves, the README fails
before anything else does.

Two things worth noticing. `id` and `name` arrive in the body as **handles**, so the function body
refers to them without ever spelling a string; and `expression("%T(%L)", node, …)` keeps KotlinPoet's
`%T`, so the import of `NamedAttributeNode` is resolved for you. Nested annotations are not
first-class — an annotation's arguments are expressions — and `expression("%T(…)", …)` is the
sanctioned route.

## The shapes

Three nested scopes, each with its own constructs, resolved by context parameters:

* `file(package, name) { }` opens a **`FileScope`** — types, top-level functions and properties,
  imports, type aliases.
* `` `class`("C") { } ``, `` `object` ``, `` `interface` `` open a **`TypeScope`** — members,
  constructors, `init` blocks, companion objects, nested types.
* `` `fun`("f") { } `` and any control-flow builder open a **`BlockScope`** — statements,
  `val`/`var`, `if`/`when`/`for`/`while`/`try`, and every one of them hands back an `Expr` handle for
  what it declared.

<!-- readme-shapes -->
```kotlin
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.DATA
import site.asm0dey.poetdsl.*

fun shapes(): String = file("com.example", "Shapes") {
    `class`(DATA, "Point", param(ParamKind.VAL, "x", INT), param(ParamKind.VAL, "y", INT)) { x, y ->
        `fun`("quadrant", returns = INT) {
            val sum = `val`("sum", init = x + y)
            `when`(sum) {
                branch(0.lit) { ret(0.lit) }
                `else` { ret(sum) }
            }
        }

        companionObject {
            `val`("DIMENSIONS", INT, init = 2.lit)
        }
    }
}.toString()
```

Most declaration constructs come in six variants — no leading argument, one `KModifier`, a
`Modifiers` set, annotations, and annotations with either modifier spelling — and one with a primary
constructor comes in a variant per arity from zero to eight, so a call never has to pass `null` for
something it does not want. That is 406 of the 774 public declarations, generated from one table
rather than written out, which is why the variants and their `BlockScope` shadows cannot drift apart.

## What it guarantees

**One thing, and it is narrow on purpose:** within the axes that have been measured, this DSL does
not render Kotlin that no target platform compiles. It throws instead.

The axes, each measured cell by cell on the JVM, Kotlin/JS and Kotlin/Wasm frontends of Kotlin
2.4.10, one file per cell:

| axis | size | what it settled |
|---|---|---|
| the `expect` family | every builder call site that can reach an `expect` container | an `expect` container refuses every member carrying a body or a value |
| classifier kind × body member | 832 cells | what a classifier's own kind forbids in its own body |
| modifier × declaration form × position | 768 cells | which single modifiers are invalid on which form in which container |
| modifier **pairs** on a class | 153 pairs, re-run on every build | what remains open — see below |

"No target platform" is not "not the JVM". `external val x: Int` at file level is rejected by the JVM
frontend and accepted by Kotlin/JS and Kotlin/Wasm, so this DSL renders it; a `value class` needs
`@JvmInline` on the JVM and nowhere else, so the annotation is yours to add and the class is not
refused. A guard justified by a JVM-only measurement would make Kotlin/JS declarations ungenerable,
which is why no row above was decided on one frontend.

Every refusal has a **control row** — the nearest *valid* neighbour of the refused shape, compiled on
all three frontends — because falsification can show a guard is load-bearing but only a control row
can show it is not over-broad. Several guards in this library were written, measured against their
neighbours, found to refuse valid Kotlin, and deleted.

Failures are `IllegalStateException`s thrown while you generate, never `require`, and never
KotlinPoet's own `IllegalArgumentException`:

<!-- readme-refusal-inner -->
```
`class`: 'N' is INNER and is declared in a file, which has no enclosing instance for it to be
inner to, so this is "modifier 'inner' is not applicable inside 'file'" on the JVM, on Kotlin/JS
and on Kotlin/Wasm alike. Drop INNER — a nested classifier needs no modifier — or declare 'N'
inside a class.
```

<!-- readme-refusal-annotation -->
```
`fun`: 'f' is declared in an `annotation class`, which declares a shape and holds nothing but its
primary-constructor parameters, so this is "members are prohibited in annotation classes" on the
JVM, on Kotlin/JS and on Kotlin/Wasm alike. Move it to a nested class or to the annotation's
companion object, both of which an annotation class still holds, or declare it as a
primary-constructor parameter with param(VAL, …).
```

The quoted sentence in the middle is a real diagnostic, transcribed from a compiler run on that
exact render rather than paraphrased from the rule — including the two above, which `ReadmeTest`
produces by running the calls that print them and compares with what you are reading.

## What it does not guarantee

Every item here is something this library renders and some Kotlin frontend rejects, or something it
does not look at. None of it is hypothetical.

**1. Modifier pairs are not guarded.** Single modifiers are. Thirty pairs on a class declaration
render and no frontend accepts them:

    final × {open, abstract, sealed, annotation}
    open × {sealed, enum, data, annotation, value, inline}
    abstract × {enum, data, annotation, value, inline}
    sealed × {enum, data, annotation, value, inline}
    enum × {annotation, data, value, inline}
    annotation × {data, value, inline}
    data × {value, inline}
    value × {inline}

None of them can be closed by a rule: `open abstract`, `abstract sealed`, `final enum` and
`final data` are all clean on all three frontends, so each of the thirty is its own row. The list is
not maintained by hand — `ModifierPairTest` renders all 153 pairs and compiles all 121 renders on
every build, so it fails the day one of these stops being invalid.

**2. `override` cannot be decided here.** `override fun f()` on a class with no supertype declaring
`f` is *'f' overrides nothing* and renders. This DSL is handed a supertype's `ClassName` and never
its members, so the fact needed to decide it does not exist in the process. Nine cells, named rather
than hidden.

**3. A spliced spec bypasses most of the container's questions.** `+typeSpec { }`, `+funSpec { }` and
`+propertySpec(…)` build a detached spec and splice it in with `+`. A detached builder does not know
its future parent, so the container rules that a directly declared member answers are mostly not
asked of a spliced one. Two containers — an anonymous object's body and an enum entry's — do ask;
everything else is open.

**4. `external` below the top level.** `external class N` nested in a class renders and is invalid
everywhere, with or without `inner`. Closing it means a rule about every non-top-level `external`
declaration including the platform split, which has not been written.

**5. `expect` + `actual` on one declaration** renders. It fails alongside the `expect` family's own
container and file-layout errors, so it is left with that family rather than with the pair axis.

**6. Local classes and local functions are unsupported.** Not because they are invalid Kotlin — they
are perfectly valid — but because KotlinPoet 2.3.0 emits an explicit visibility on every type and an
implicit `public` on every function spliced into a code block, and Kotlin allows neither on a local
declaration. Both are refused with that reason, and the refusal is a render gap rather than a
language claim.

**7. Eleven constructs that belong to a type body have `@Deprecated(level = ERROR)` shadows on
`BlockScope`** — 142 overloads in all — so writing `` `constructor` { } `` in a function body is a
*compile* error in your generator rather than a silently misplaced declaration. The shadows have one
known false positive: a `typeSpec { }` written lexically inside a block resolves them too, because a
shadow is an extension and an extension receiver beats a context parameter. Build that type outside
the block, or splice it in with `+`.

**8. Nothing here checks meaning.** Unresolved references, type mismatches, wrong arity, a `when`
that is not exhaustive — none of it is checked, and `expression("…")` is a raw escape hatch that is
not parsed at all. This library checks the **shape** of a declaration against the language's rules
for that shape, and it is not a compiler.

**9. Nothing beyond pairs has been measured.** No triple of modifiers, in any container, on any form.

**10. The pair census is one container and one declaration form.** A pair on a function, a property
or a type alias is not covered by it — on a function `final abstract` draws the container's sentence
before the pair's, so the class rows do not transfer.

## The public surface is frozen

`api/kotlin-poet-dsl.api` is a binary-compatibility-validator dump of all 774 public declarations,
checked by `./gradlew apiCheck` on every build. Adding a refusal is not a binary-compatibility change
and stays possible; adding or removing a declaration is, and now fails loudly.

## Building

```
./gradlew build              # compile, test, apiCheck
./gradlew publishToMavenLocal
```

The suite compiles its own claims rather than comparing them to golden strings: 336 call sites run
a real Kotlin frontend over rendered output, 44 of them on Kotlin/JS and Kotlin/Wasm as well as the
JVM, plus the 153-pair census, which runs all three frontends over every one of its 121 renders. The
generated sources are checked to regenerate byte-identically.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
