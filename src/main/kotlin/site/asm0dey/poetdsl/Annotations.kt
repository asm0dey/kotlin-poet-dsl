package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asClassName

/**
 * KotlinPoet's use-site target enum, re-exported. Includes Kotlin 2.2's `@all:` meta-target.
 *
 * This is a plain typealias, not a wrapper, so KotlinPoet's own opt-in requirements travel with it:
 * [UseSiteTarget.ALL] is `@ExperimentalKotlinPoetApi`, and a caller writing `@all:` must therefore
 * add `@OptIn(com.squareup.kotlinpoet.ExperimentalKotlinPoetApi::class)` themselves. No other entry
 * needs one.
 */
public typealias UseSiteTarget = AnnotationSpec.UseSiteTarget

/**
 * A list of annotations, combined with `+`, written before the modifiers.
 *
 * The constructor and [list] are `internal`, but `@PublishedApi` because the public inline
 * `annotation`/`ann`/`annotate` functions below — inline so `reified T` can resolve the
 * annotation's [ClassName] — construct and read them directly; without `@PublishedApi` those
 * public inline functions could not reach non-public-API members (a fact the brief's own
 * listing omits).
 */
@JvmInline
public value class Annotations @PublishedApi internal constructor(
    @PublishedApi internal val list: List<AnnotationSpec>,
)

public operator fun Annotations.plus(other: Annotations): Annotations = Annotations(list + other.list)

/**
 * Rejects an annotation argument that names a runtime binding.
 *
 * A Kotlin annotation argument must be a compile-time constant. A handle — or anything derived
 * from one — names a local, a parameter or a property, so `@Ann(x)` is `e: An annotation argument
 * must be a compile-time constant` however it is written, whatever scope `x` came from. That makes
 * this a *stricter* condition than ADR 0008's ownership relation and not something the ownership
 * checker would be the right guard for: ownership is a [BlockScope]-to-[BlockScope] question, and
 * [Annotatable] is implemented only by [FileScope] and [TypeScope] (see the Task 11 report).
 *
 * [Expr.usedScopes] is the signal available here, and it is *neither* exactly necessary nor exactly
 * sufficient, because the [annotation] builders take no scope at all — by design, since an
 * annotation is written wherever it is needed and belongs to no block:
 *
 * - Not necessary: `call("compute")` is equally non-constant and carries no scope, so it still
 *   reaches `kotlinc` unchallenged. The check closes what the DSL can see and no more.
 * - Not quite sufficient either: a `` const `val` `` declared *through this DSL* also hands back a
 *   handle, and `@Ann(MAX)` referring to it is legal Kotlin — yet it is rejected here, because
 *   nothing in an [Expr] distinguishes a file-level `const` from a local. That is the one false
 *   rejection, it has a one-line answer, and the message names it: reference the constant by
 *   name instead, with `member("pkg", "MAX").expression()` (which also resolves the import) or the
 *   `expression("MAX")` escape hatch.
 *
 * The alternative was to keep rendering `@Ann(x)` for a local and let the caller discover it
 * whenever they next compiled the output. A loud false rejection with a documented workaround beats
 * silently wrong generated code.
 *
 * A narrower alternative was weighed for the false-rejection case specifically: an internal
 * `isConst` flag on [Expr], set only by the binding that declares a `` const `val` ``, would have
 * let this check tell that one case apart and stop rejecting it, at the cost of one internal field
 * threaded through every [Expr]-producing call site. Not taken, because `member(…)` is genuinely the
 * more correct spelling regardless: it resolves the import and is right at every destination a
 * constant reference is written to, not just this one, so the flag would only have removed a
 * workaround that is itself an improvement.
 */
private fun checkAnnotationArgument(arg: Expr) {
    check(arg.usedScopes.isEmpty()) {
        "annotation: '${arg.name ?: arg}' is a handle, but a Kotlin annotation argument must be a " +
            "compile-time constant. Use a literal, or name the constant with " +
            "member(\"pkg\", \"NAME\").expression() — a handle is rejected even for a `const val`, " +
            "because an Expr cannot tell one from a local."
    }
}

/**
 * The one place an [Expr] is consumed without threading its `usedScopes` forward, and
 * deliberately so: [Annotations] is a terminal artifact — a bag of KotlinPoet [AnnotationSpec]s
 * — not a composable value like [Expr] or [Stmt], and ADR 0008's ownership check is defined only
 * between [BlockScope]s. An annotation never splices into a block, so there is no site at which
 * a recorded scope could be judged. Making [Annotations] carry scopes would also cost it its
 * `@JvmInline` single-field representation.
 *
 * Dropping the scopes is safe *because* [checkAnnotationArgument] runs first: an argument carrying
 * any scope at all is rejected outright, so no scope that could have needed judging survives to be
 * discarded.
 */
@PublishedApi
internal fun buildAnnotation(
    className: ClassName,
    target: UseSiteTarget?,
    positional: List<Expr>,
    named: List<Pair<String, Expr>>,
): AnnotationSpec {
    positional.forEach(::checkAnnotationArgument)
    named.forEach { (_, value) -> checkAnnotationArgument(value) }
    return AnnotationSpec.builder(className)
        .apply {
            target?.let { useSiteTarget(it) }
            positional.forEach { addMember("%L", it.code) }
            named.forEach { (name, value) -> addMember(CodeBlock.of("%L·=·%L", name, value.code)) }
        }
        .build()
}

/** An annotation with positional arguments; `%T`/`%M` in them survive and imports resolve. */
public inline fun <reified T : Annotation> annotation(
    target: UseSiteTarget? = null,
    vararg args: Expr,
): Annotations = Annotations(listOf(buildAnnotation(T::class.asClassName(), target, args.toList(), emptyList())))

/**
 * An annotation with named arguments.
 *
 * [first] is a required parameter, not part of the vararg, so that a zero-argument call such
 * as `annotation<Email>()` resolves unambiguously to the positional overload above instead of
 * failing overload resolution — see spec deviation D2.
 *
 * [first] and [rest] come *before* [target] (unlike the positional overload above), and
 * [target] is therefore named-only here. Positional arguments bind to parameters strictly by
 * declared order regardless of defaults, so `target` first would make a lone positional
 * `"k" to v` argument bind to `target: UseSiteTarget?` and fail to type-check — exactly the
 * brief's own `annotation<SerialName>("value" to "user_name".lit)` call site. This ordering
 * is what makes that call site (and the rest of the brief's named-argument calls) compile.
 */
public inline fun <reified T : Annotation> annotation(
    first: Pair<String, Expr>,
    vararg rest: Pair<String, Expr>,
    target: UseSiteTarget? = null,
): Annotations = Annotations(
    listOf(buildAnnotation(T::class.asClassName(), target, emptyList(), listOf(first) + rest)),
)

/** An annotation whose type is only known at generation time. */
public fun annotation(
    cls: ClassName,
    target: UseSiteTarget? = null,
    vararg args: Expr,
): Annotations = Annotations(listOf(buildAnnotation(cls, target, args.toList(), emptyList())))

/**
 * An annotation whose type is only known at generation time, with named arguments (deviation D27).
 *
 * The reified form has had both an argument shape and a named one from the start; this form had
 * only the positional one, which D2 recorded as a fact without weighing it. It is the wrong way
 * round: in real codegen the annotation type is usually *not* on the generator's classpath, so a
 * [ClassName] is all there is — and that is exactly when named arguments were unavailable.
 *
 * Same shape as the reified named form, for the same reasons: [first] is a required parameter
 * rather than part of the vararg, so a zero-argument `annotation(cls)` still resolves to the
 * positional overload above; and [target] comes last and is named-only, because Kotlin binds
 * positional arguments by declared order regardless of defaults, so a leading `target` would
 * swallow the first `Pair` and fail to type-check.
 */
public fun annotation(
    cls: ClassName,
    first: Pair<String, Expr>,
    vararg rest: Pair<String, Expr>,
    target: UseSiteTarget? = null,
): Annotations = Annotations(listOf(buildAnnotation(cls, target, emptyList(), listOf(first) + rest)))

/** Alias for [annotation] with positional arguments. */
public inline fun <reified T : Annotation> ann(target: UseSiteTarget? = null, vararg args: Expr): Annotations =
    annotation<T>(target, *args)

/** Alias for [annotation] with named arguments; see D2 on the parameter order. */
public inline fun <reified T : Annotation> ann(
    first: Pair<String, Expr>,
    vararg rest: Pair<String, Expr>,
    target: UseSiteTarget? = null,
): Annotations = annotation<T>(first, *rest, target = target)

/** Alias for [annotation] with a runtime-known annotation type. */
public fun ann(cls: ClassName, target: UseSiteTarget? = null, vararg args: Expr): Annotations =
    annotation(cls, target, *args)

/**
 * Alias for [annotation] with a runtime-known annotation type and named arguments (D27); see D2 on
 * the parameter order.
 */
public fun ann(
    cls: ClassName,
    first: Pair<String, Expr>,
    vararg rest: Pair<String, Expr>,
    target: UseSiteTarget? = null,
): Annotations = annotation(cls, first, *rest, target = target)

/** Implemented by the scopes, so annotations can also be added from a trailing lambda. */
public interface Annotatable {
    public fun addAnnotation(spec: AnnotationSpec)
}

/** Trailing-lambda form: for conditional, computed, or looped annotations. */
public inline fun <reified T : Annotation> Annotatable.annotate(
    target: UseSiteTarget? = null,
    vararg args: Expr,
) {
    annotation<T>(target, *args).list.forEach(::addAnnotation)
}

/**
 * Trailing-lambda form with named arguments. Same parameter order as [annotation]'s named
 * form, for the same reason — see D2.
 */
public inline fun <reified T : Annotation> Annotatable.annotate(
    first: Pair<String, Expr>,
    vararg rest: Pair<String, Expr>,
    target: UseSiteTarget? = null,
) {
    annotation<T>(first, *rest, target = target).list.forEach(::addAnnotation)
}

/** Adds every annotation in the list, if any. */
public fun Annotatable.addAll(annotations: Annotations?) {
    annotations?.list?.forEach(::addAnnotation)
}
