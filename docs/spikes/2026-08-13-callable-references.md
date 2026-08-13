# Spike: KFunction/KProperty → MemberName (kotlin-reflect 2.4.10)

Measured on Kotlin 2.4.10 / JVM 17 with `implementation(kotlin("reflect"))`, by running a probe in
the test source set and printing what reflection returns for each reference kind. Every row below
is backed by a test in `src/test/kotlin/site/asm0dey/poetdsl/RefsTest.kt`.

| Reference kind | Resolves? | Notes |
|---|---|---|
| top-level function (`::topLevelHelper`) | yes | package from `javaMethod.declaringClass.package` |
| member function (`String::isNotEmpty`) | name only | qualified by the receiver `Expr`; `Expr.call(ref)` never emits `%M` |
| extension function | yes for `asMemberName`, rejected for a bare call | package is right (`kotlin.text`); `isNotEmpty(x)` is not how an extension is invoked, so `call(ref)` fails loudly |
| member property (`String::length`) | name only | `javaMethod` and `javaField` are both null for a stdlib member property |
| top-level property | not implemented | `javaField.declaringClass` *is* the file facade, so the package is recoverable; there is no `prop(ref)` free function in this task's API. Use `member("pkg", "name")` |
| inline + reified, JVM-backed (`::emptyArray`) | only through an explicitly typed reference | `call(::emptyArray)` does not compile — "Cannot infer type for type parameter 'T'". `val ref: KFunction<Array<Int>> = ::emptyArray` resolves to `kotlin.emptyArray` (backed by `kotlin.ArrayIntrinsicsKt`), but the type argument is erased and the generated call carries none |
| inline + reified, pure intrinsic (`::arrayOf`) | no | measured: even `val ref: KFunction<Array<Int>> = ::arrayOf` throws `KotlinReflectionInternalError` from `javaMethod` ("not resolved in class kotlin.jvm.internal.Intrinsics$Kotlin: no methods found") — `arrayOf` has no JVM method at all, so it never reaches the metadata check and fails via the "no declaring class" branch, not the kind check |
| local function / lambda | no | throws `IllegalStateException` naming the function |
| constructor (`::RefsSample`) | no | `javaMethod` is null (it is a `javaConstructor`); name is `<init>` |
| companion / object function | no | declaring class is the companion/object, not the file |
| Java instance member (`File::mkdir`) | no | reaches the metadata branch: `javaMethod` resolves, `@Metadata` is absent on `java.io.File`, rejected there |
| Java static (`Integer::bitCount`) | no | never reaches the metadata branch — measured: `javaMethod` throws `KotlinReflectionInternalError` ("Function 'bitCount' … not resolved in class kotlin.Int: no members found"), so it fails via the "no declaring class" branch, the same one local functions and constructors hit |
| top-level function, non-public (`private`/`internal`) | no | passes the metadata-kind check (owner is the file facade, kind 2) but is rejected on `KVisibility`: `private` can never be imported from outside its file, `internal` compiles only if the generated file lands in the same module |

## What separates a top-level function from a member

`instanceParameter` does **not**: it is null for a function declared in a `companion object` or an
`object`, whose declaring class is that object rather than a file facade. Deriving a package from
such a declaring class produces `MemberName("site.asm0dey.poetdsl", "companionFun")`, which renders
`%M` as an import of something that does not exist — silently wrong output.

The signal that does work is the Kotlin `@Metadata` annotation on the declaring class, which has
`RUNTIME` retention. Its `kind` is 2 for a file facade, 4 for a multifile facade and 5 for a
multifile part; 1 for a class, companion or object; the annotation is absent on a Java class.
Measured:

```
PROBE-MD | file facade     | site.asm0dey.poetdsl.SpikeProbeKt      | kind=2 | pkg=site.asm0dey.poetdsl
PROBE-MD | multifile part  | kotlin.text.StringsKt__StringsKt       | kind=5 | pkg=kotlin.text
PROBE-MD | stdlib facade   | kotlin.io.ConsoleKt                    | kind=2 | pkg=kotlin.io
PROBE-MD | class           | site.asm0dey.poetdsl.SpikeSample       | kind=1 | pkg=site.asm0dey.poetdsl
PROBE-MD | companion       | site.asm0dey.poetdsl.SpikeSample$Companion | kind=1 | pkg=site.asm0dey.poetdsl
PROBE-MD | object          | site.asm0dey.poetdsl.SpikeObject       | kind=1 | pkg=site.asm0dey.poetdsl
PROBE-MD | java class      | java.io.File                           | kind=null | pkg=java.io
```

`asMemberName` therefore reads the **package** of the declaring class and rejects any declaring
class whose kind is not a top-level container.

## Why the package, never the declaring class name

A stdlib extension's declaring class is a multifile *part*, not the facade it is imported from:

```
PROBE | stdlib extension (String::isNotEmpty) | javaMethod=private static final boolean
        kotlin.text.StringsKt__StringsKt.isNotEmpty(java.lang.CharSequence) | pkg=kotlin.text
```

`kotlin.text.StringsKt__StringsKt` is not importable (the method is even `private`); `kotlin.text`
is. Only the package survives the indirection, which is what `MemberName(packageName, simpleName)`
wants anyway.

## Local functions throw rather than return null

```
PROBE | local function | javaMethod=THREW KotlinReflectionInternalError: Method 'localFun'
        (JVM signature: ()I) not resolved in class kotlin.jvm.internal.Intrinsics$Kotlin:
        no methods found
```

`instanceParameter` and `extensionReceiverParameter` throw the same error. A plain
`javaMethod?.declaringClass ?: error(…)` would therefore propagate a `KotlinReflectionInternalError`
(an `Error`, not an exception) instead of the build-time `IllegalStateException` this project
promises, so the access is wrapped in `runCatching { javaMethod }.getOrNull()`.

## A lambda is not a `KFunction` at all

```
PROBE | lambda class=site.asm0dey.poetdsl.SpikeProbe$$Lambda$436/0x00007fa50424f868 isKFunction=false
PROBE | lambda as KFunction<*> -> THREW java.lang.ClassCastException
PROBE | anonymous fun class=site.asm0dey.poetdsl.SpikeProbe$$Lambda$437/0x00007fa50424fa88 isKFunction=false
```

Kotlin 2.x compiles lambdas and anonymous functions with `invokedynamic`, so the instance implements
only `FunctionN` — `{ 1 } as KFunction<*>` throws `ClassCastException` before any DSL code runs.
A lambda can never reach `asMemberName`; the reachable "no owner" cases are local functions,
constructors, Java statics and synthetic members, and those are what the test exercises.

## Passing the metadata-kind check is not enough: visibility

`@Metadata.kind` only proves the declaring class is a file facade — it says nothing about whether an
import can actually reach the function from wherever the generated code lands. A `private` or
`internal` top-level function has the same owner and kind as a public one:

```kotlin
private fun secret(): Int = 1
internal fun internalTop(): Int = 1
```

Both pass `TOP_LEVEL_KINDS` (owner `ProbeKt`, kind 2) and, before this fix, resolved to
`MemberName("gapprobe", "secret")` / `MemberName("gapprobe", "internalTop")` — imports KotlinPoet
would happily emit (`import gapprobe.secret`) that fail only at the *consumer's* compile, not at
generation. Measured directly: `secret` reflects `visibility = KVisibility.PRIVATE`, `internalTop`
reflects `visibility = KVisibility.INTERNAL`. `KVisibility` is free on any `KCallable` — no new
dependency — so `asMemberName()` now checks it after the kind check: `PRIVATE` is rejected
unconditionally (no import can ever reach it), `INTERNAL` is rejected with a message noting it
compiles only in-module (the DSL cannot know where its output will land, so it still fails loudly
rather than guessing).

## Consequences for the implementation

1. `asMemberName()` resolves a **public** top-level function (including extensions and multifile
   parts) and fails with a build-time `IllegalStateException` naming the function and its visibility
   for everything else, including a `private` or `internal` top-level function that passes the
   metadata-kind check but whose import is not usable at the generation site.
2. Bare `call(ref, …)` additionally rejects an extension function: reflection's answer is correct,
   but rendering `%M(args)` for it would be invalid Kotlin.
3. `Expr.call(ref, …)` and `Expr.prop(ref)` are name sources only — no import, no `%M`, and no type
   checking, since `Expr` is untyped. `someInt.call(String::isNotEmpty)` compiles and generates
   nonsense, exactly as `call("isNotEmpty")` would.
