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
| inline + reified (`::arrayOf`, `::emptyArray`) | only through an explicitly typed reference | `call(::emptyArray)` does not compile — "Cannot infer type for type parameter 'T'". `val ref: KFunction<Array<Int>> = ::emptyArray` resolves to `kotlin.emptyArray`, but the type argument is erased and the generated call carries none |
| local function / lambda | no | throws `IllegalStateException` naming the function |
| constructor (`::RefsSample`) | no | `javaMethod` is null (it is a `javaConstructor`); name is `<init>` |
| companion / object function | no | declaring class is the companion/object, not the file |
| Java member or static | no | no `@Metadata` at all |

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
constructors and synthetic members, and those are what the test exercises.

## Consequences for the implementation

1. `asMemberName()` resolves a top-level function (including extensions and multifile parts) and
   fails with a build-time `IllegalStateException` naming the function for everything else.
2. Bare `call(ref, …)` additionally rejects an extension function: reflection's answer is correct,
   but rendering `%M(args)` for it would be invalid Kotlin.
3. `Expr.call(ref, …)` and `Expr.prop(ref)` are name sources only — no import, no `%M`, and no type
   checking, since `Expr` is untyped. `someInt.call(String::isNotEmpty)` compiles and generates
   nonsense, exactly as `call("isNotEmpty")` would.
