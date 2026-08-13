package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asClassName

/** KotlinPoet's use-site target enum, re-exported. Includes Kotlin 2.2's `@all:` meta-target. */
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

@PublishedApi
internal fun buildAnnotation(
    className: ClassName,
    target: UseSiteTarget?,
    positional: List<Expr>,
    named: List<Pair<String, Expr>>,
): AnnotationSpec = AnnotationSpec.builder(className)
    .apply {
        target?.let { useSiteTarget(it) }
        positional.forEach { addMember("%L", it.code) }
        named.forEach { (name, value) -> addMember(CodeBlock.of("%L·=·%L", name, value.code)) }
    }
    .build()

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
