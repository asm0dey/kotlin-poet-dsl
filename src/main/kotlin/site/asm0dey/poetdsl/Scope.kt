package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/**
 * The nesting level a builder runs in. Sealed on purpose: every dispatching `when` is
 * exhaustive, so adding a scope makes the compiler list the constructs that forgot a case.
 *
 * A construct valid at more than one level is declared **once** on `Scope` and dispatches
 * on the runtime type — the innermost scope value wins. Two overloads distinguished only
 * by context-parameter type would be an ambiguity error instead. See ADR 0001.
 *
 * A sealed class rather than an interface: subclasses share [names] and [id], and Kotlin
 * does not allow `internal` members on an interface.
 */
public sealed class Scope protected constructor(
    internal val names: NameScope,
    internal val id: ScopeId,
) {
    /**
     * Type names declared directly in this scope (not chained to a parent — a type at file
     * level and a same-named type nested inside another type do not collide). KotlinPoet does
     * not deduplicate types on its own (`TypeSpec.Builder.addType` just appends, and `FileSpec`
     * has no check), so two types named the same in one container would silently render as
     * invalid Kotlin without this. `fun` is deliberately exempt: Kotlin permits overloads, so
     * duplicate function names are legal and must not go through this set.
     */
    internal val declaredTypeNames: MutableSet<String> = mutableSetOf()
}

/** The file-level scope. */
@FileDsl
public class FileScope internal constructor(
    internal val builder: FileSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id), Annotatable {
    /** Annotations added at file level default to the `@file:` use-site target. */
    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(
            if (spec.useSiteTarget == null) spec.toBuilder().useSiteTarget(UseSiteTarget.FILE).build() else spec,
        )
    }
}

/** The type-level scope: members are declared here. */
@TypeDsl
public class TypeScope internal constructor(
    internal val builder: TypeSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id), Annotatable {
    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    internal fun finish(): TypeSpec {
        if (hasCtor) builder.primaryConstructor(ctor.build())
        return builder.build()
    }

    override fun addAnnotation(spec: AnnotationSpec) {
        builder.addAnnotation(spec)
    }
}

/** A control-flow block left open by the builder that started it. */
internal interface PendingFlow {
    fun close()
}

/** The statement-level scope: a function, lambda or control-flow body. */
@BlockDsl
public class BlockScope internal constructor(
    internal val builder: CodeBlock.Builder,
    names: NameScope,
    id: ScopeId,
    internal val returns: MutableList<TypeName?>,
    internal val detachedRoot: Boolean = false,
) : Scope(names, id) {
    internal var pending: PendingFlow? = null
}

/**
 * A nested block.
 *
 * @param isolateReturns true for lambda bodies, whose `return` is a non-local return and
 *   must not drive the enclosing function's inferred return type (ADR 0007). Control-flow
 *   bodies pass false and share the list.
 */
internal fun BlockScope.child(label: String, isolateReturns: Boolean = false): BlockScope =
    BlockScope(
        builder = CodeBlock.builder(),
        names = names.child(),
        id = id.child(label),
        returns = if (isolateReturns) mutableListOf() else returns,
        detachedRoot = detachedRoot,
    )

/** Builds a `.kt` file. */
public fun file(packageName: String, fileName: String, body: FileScope.() -> Unit): FileSpec {
    val scope = FileScope(FileSpec.builder(packageName, fileName), NameScope(null), ScopeId(null, "file"))
    scope.body()
    return scope.builder.build()
}

/** Emits [this] into the innermost scope's builder; a block body is not a legal target. */
context(s: Scope)
public operator fun FunSpec.unaryPlus() {
    when (s) {
        is FileScope -> s.builder.addFunction(this)
        is TypeScope -> s.builder.addFunction(this)
        is BlockScope -> error("FunSpec: a function spec cannot be emitted into a block body.")
    }
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public operator fun FunSpec.invoke() {
    +this
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun emit(spec: FunSpec) {
    +spec
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun add(spec: FunSpec) {
    +spec
}

/** Emits [this] into the innermost scope's builder; a block body is not a legal target. */
context(s: Scope)
public operator fun TypeSpec.unaryPlus() {
    when (s) {
        is FileScope -> s.builder.addType(this)
        is TypeScope -> s.builder.addType(this)
        is BlockScope -> error("TypeSpec: a type spec cannot be emitted into a block body.")
    }
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public operator fun TypeSpec.invoke() {
    +this
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun emit(spec: TypeSpec) {
    +spec
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun add(spec: TypeSpec) {
    +spec
}

/** Emits [this] into the innermost scope's builder; a block body is not a legal target. */
context(s: Scope)
public operator fun PropertySpec.unaryPlus() {
    when (s) {
        is FileScope -> s.builder.addProperty(this)
        is TypeScope -> s.builder.addProperty(this)
        is BlockScope -> error("PropertySpec: a property spec cannot be emitted into a block body.")
    }
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public operator fun PropertySpec.invoke() {
    +this
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun emit(spec: PropertySpec) {
    +spec
}

/** Alias for [unaryPlus]. */
context(s: Scope)
public fun add(spec: PropertySpec) {
    +spec
}
