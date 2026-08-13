package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.MemberName
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
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
 * - an inline function with a reified type parameter cannot be written inline: `call(::emptyArray)`
 *   does not compile, because `T` has nothing to be inferred from in `KFunction<*>` ("Cannot infer
 *   type for type parameter 'T'", measured on Kotlin 2.4.10). Bound to an explicitly typed
 *   reference — `val ref: KFunction<Array<Int>> = ::emptyArray` — it resolves correctly, but the
 *   type argument is erased, so the generated call carries none. Use `member("kotlin", "arrayOf")`
 *   and pass the type argument yourself when the generated call needs one;
 * - a member, companion, object or Java-static function has an owner this cannot express as a
 *   package, and a local function has no JVM owner at all. Both are build-time errors naming the
 *   function; use [member] with the owning class.
 */
public fun KFunction<*>.asMemberName(): MemberName {
    // `javaMethod` *throws* KotlinReflectionInternalError for a local function rather than
    // returning null, so the access is guarded rather than null-checked.
    val owner = runCatching { javaMethod }.getOrNull()?.declaringClass
        ?: error(
            "Cannot resolve a MemberName for '$name': no declaring class — a local function, a " +
                "constructor or a synthetic member has no owner kotlin-reflect can resolve. " +
                "Use member(\"pkg\", \"name\") instead.",
        )
    check(owner.getAnnotation(Metadata::class.java)?.kind in TOP_LEVEL_KINDS) {
        "Cannot resolve a MemberName for '$name': it is declared in '${owner.name}', not at file " +
            "level, so its package is not its import. " +
            "Use member(reference<Owner>(), \"$name\") with the owning class instead."
    }
    return MemberName(owner.`package`?.name.orEmpty(), name)
}

/**
 * The [MemberName] for a *bare* call — `name(args)`, with nothing in receiver position.
 *
 * An extension function resolves to a perfectly good [MemberName], but it cannot be called this
 * way: `isNotEmpty(x)` is not how an extension is invoked. Rendering it would be silently invalid
 * Kotlin, so it is a build-time error pointing at the receiver form instead.
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

/** `name(args) { … }` for a top-level function; the import resolves through `%M`. */
context(b: BlockScope)
public fun call(ref: KFunction<*>, vararg args: Expr, body: BlockScope.() -> Unit): Expr =
    memberLambda(ref.asBareCallMemberName(), args, b.lambdaOf(emptyList()) { body() })
