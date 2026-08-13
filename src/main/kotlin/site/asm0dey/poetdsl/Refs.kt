package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.MemberName
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.javaMethod

/** `@Metadata.kind` of a file facade (`FooKt`) — the class a top-level declaration compiles into. */
private const val KIND_FILE_FACADE = 2

/** `@Metadata.kind` of a `@JvmMultifileClass` facade. */
private const val KIND_MULTIFILE_CLASS = 4

/** `@Metadata.kind` of one part of a multifile facade, e.g. `kotlin.text.StringsKt__StringsKt`. */
private const val KIND_MULTIFILE_CLASS_PART = 5

/**
 * The `@Metadata.kind` values whose JVM class is a container for *top-level* Kotlin declarations.
 *
 * This is the only signal that separates a top-level function from a member: `instanceParameter`
 * does not — it is null for a function declared in a `companion object` or an `object`, whose
 * declaring class is that object, not the file (measured on kotlin-reflect 2.4.10, see
 * `docs/spikes/2026-08-13-callable-references.md`). Deriving a package from the declaring class of
 * a member would silently produce `import pkg.memberFun`, an import of something that does not
 * exist, so the kind is checked and anything else is rejected.
 */
private val TOP_LEVEL_KINDS = setOf(KIND_FILE_FACADE, KIND_MULTIFILE_CLASS, KIND_MULTIFILE_CLASS_PART)

/**
 * Resolves a top-level function reference to a [MemberName], so `%M` registers the import.
 *
 * The package comes from the declaring class's package, never from its name: a stdlib extension
 * lives in a *part* class (`kotlin.text.StringsKt__StringsKt`) that is not the facade it is
 * imported from, and only the package survives that indirection.
 *
 * Documented limitations, all unfixable here:
 * - a reference used on a receiver is a **name source only** — [Expr] is untyped, so
 *   `someInt.call(String::isNotEmpty)` compiles and generates invalid Kotlin, exactly as
 *   `call("isNotEmpty")` would;
 * - an inline function with a reified type parameter backed by a real JVM method
 *   (`::emptyArray`) cannot be written inline: `call(::emptyArray)` does not compile, because `T`
 *   has nothing to be inferred from in `KFunction<*>` ("Cannot infer type for type parameter 'T'",
 *   measured on Kotlin 2.4.10). Bound to an explicitly typed reference —
 *   `val ref: KFunction<Array<Int>> = ::emptyArray` — it resolves correctly, but the type argument
 *   is erased, so the generated call carries none. A pure intrinsic with no JVM method at all
 *   (`::arrayOf`) never resolves, typed or not — `javaMethod` throws even on a typed reference. Use
 *   `member("kotlin", "arrayOf")` and pass the type argument yourself when the generated call needs
 *   one;
 * - a member, companion, object or Java function has an owner this cannot express as a package,
 *   and a local function, a constructor or a Java static has no JVM owner kotlin-reflect can
 *   resolve at all. Both are build-time errors naming the function; use [member] with the owning
 *   class;
 * - a `private` or `internal` top-level function passes the metadata-kind check — its owner *is*
 *   the file facade — but its import is not usable where the generated code lands: `private`
 *   never, `internal` only from within the same module. Both are rejected; see the visibility
 *   checks below.
 */
public fun KFunction<*>.asMemberName(): MemberName {
    // `javaMethod` *throws* KotlinReflectionInternalError for a local function or a Java static
    // rather than returning null, so the access is guarded rather than null-checked.
    val owner = runCatching { javaMethod }.getOrNull()?.declaringClass
        ?: error(
            "Cannot resolve a MemberName for '$name': no declaring class — a local function, a " +
                "constructor, a Java static method or a synthetic member has no owner kotlin-reflect " +
                "can resolve. Use member(\"pkg\", \"name\") instead.",
        )
    check(owner.getAnnotation(Metadata::class.java)?.kind in TOP_LEVEL_KINDS) {
        "Cannot resolve a MemberName for '$name': it is declared in '${owner.name}', not at file " +
            "level, so its package is not its import. " +
            "Use member(reference<Owner>(), \"$name\") with the owning class instead."
    }
    // `@Metadata.kind` only proves the owner is a file facade — it says nothing about whether an
    // import can actually reach the function from wherever the generated code lands.
    check(visibility != KVisibility.PRIVATE) {
        "Cannot resolve a MemberName for '$name': it is private, so no import can ever reach it. " +
            "Make '$name' public, or reference it directly from within its declaring file instead."
    }
    check(visibility == KVisibility.PUBLIC) {
        "Cannot resolve a MemberName for '$name': it is internal, so its import only compiles when " +
            "the generated output lands in the same module. Make '$name' public, or use " +
            "member(\"pkg\", \"name\") only if you control that placement."
    }
    return MemberName(owner.`package`?.name.orEmpty(), name)
}

/**
 * The [MemberName] for a *bare* call — `name(args)`, with nothing in receiver position.
 *
 * An extension function resolves to a perfectly good [MemberName], but it cannot be called this
 * way: `isNotEmpty(x)` is not how an extension is invoked. Rendering it would be silently invalid
 * Kotlin, so it is a build-time error pointing at the receiver form instead.
 *
 * This guard is bypassable via `call(member("kotlin.text", "isNotEmpty"))`, which emits the same
 * code with no check — that escape hatch is legitimate wherever the receiver is implicit, e.g.
 * inside the generated body of an extension function or a `with(x) { }` block.
 */
private fun KFunction<*>.asBareCallMemberName(): MemberName {
    val member = asMemberName()
    check(extensionReceiverParameter == null) {
        "'$name' is an extension function: a bare call cannot render its receiver. " +
            "Use receiver.call(\"$name\") instead."
    }
    return member
}

/** `receiver.name(args)` with the name taken from a reference — typo-safe when the API is on the classpath. */
public fun Expr.call(ref: KFunction<*>, vararg args: Expr): Expr = call(ref.name, *args)

/** `receiver.name` with the name taken from a property reference. */
public fun Expr.prop(ref: KProperty<*>): Expr = prop(ref.name)

/** `name(args)` for a top-level function; the import resolves through `%M`. */
public fun call(ref: KFunction<*>, vararg args: Expr): Expr = call(ref.asBareCallMemberName(), *args)

/**
 * `name(args) { … }` for a top-level function; the import resolves through `%M`.
 *
 * `context(s: Scope)`, not `context(b: BlockScope)`: a lambda is just as valid at
 * property-initializer and property-delegate position, where no block is in scope (D3, see the
 * comment on [lambda] in `Lambdas.kt`). This is the `MemberName` twin of [call] above and must
 * stay as widely usable as it is.
 */
context(s: Scope)
public fun call(ref: KFunction<*>, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    memberLambda(ref.asBareCallMemberName(), args, s.lambdaOf(emptyList()) { body() })
