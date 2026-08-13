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

    /**
     * Property names declared directly in this scope via [propertyOf] — not chained to a
     * parent, mirroring [declaredTypeNames]. A second `` `val`("username", …) `` in the same
     * container is a compile error in Kotlin (two properties, one name), and there is no valid
     * output for [NameScope.unique] to preserve by renaming it, so this set exists to reject it
     * outright instead (ADR 0009, amended by D21). Deliberately a *separate* set from
     * [TypeScope.declaredConstructorParamNames]: a property colliding with a constructor
     * parameter is a collision between different constructs, which ADR 0009's uniquifier still
     * has to handle — sharing one registry between the two would rebuff that case as well.
     */
    internal val declaredPropertyNames: MutableSet<String> = mutableSetOf()
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
    /**
     * What is being declared — `"class"`, `"named object"`, `"interface"` — as [declareType] names it
     * in its messages. Kept because a [TypeSpec.Builder] does not expose its own kind, and
     * [superclass] has to reject an interface with a message that says which construct to use
     * instead. Defaulted to `"class"` for the detached [typeSpec] builder, which only ever builds one.
     */
    internal val kindName: String = "class",
) : Scope(names, id), Annotatable {
    /**
     * Whether [superclass] already ran. KotlinPoet tracks the same fact but keeps it internal, and
     * its own `check` message ("superclass already set to …") names neither this DSL's construct nor
     * the type it happened in.
     */
    internal var hasSuperclass: Boolean = false

    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    /**
     * Whether a secondary `` `constructor` `` was already declared. Kotlin requires every secondary
     * constructor of a class that has a primary one to delegate to it with `: this(…)`, and this DSL
     * has no way to express that call, so the two constructs are mutually exclusive here — see the
     * guards in [addConstructorParam] and in the `` `constructor` `` builders. Tracked in both
     * directions because the two can be written in either order and the broken output is the same.
     */
    internal var hasSecondaryCtor: Boolean = false

    /**
     * Constructor parameter names declared directly in this type via [addConstructorParam].
     * Same rationale and same "reject, don't rename" treatment as [declaredPropertyNames] — two
     * constructor parameters named `id` is a compile error with no valid output to preserve —
     * kept as its own set for the same reason: a parameter colliding with a *property* name is a
     * cross-construct collision that still has to uniquify (ADR 0009, amended by D21).
     */
    internal val declaredConstructorParamNames: MutableSet<String> = mutableSetOf()

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
    /**
     * Foreign scopes whose handles were used somewhere in this block tree. Only a detached root
     * records — an attached one rejects instead — and the set is shared with every control-flow
     * child, so a handle used inside a nested `if` or `for` still reaches the `stmts` root that
     * reports it for the splice (ADR 0008). A *lambda* body starts a fresh set, for the same reason
     * [captured] does and with nothing lost by it — see [child].
     */
    internal val referenced: MutableSet<ScopeId> = mutableSetOf(),
    /**
     * Every scope [checkOwned] validated anywhere in this block tree, whether it accepted or
     * recorded it — [referenced] only ever holds the *foreign* ones, and only in a detached tree.
     * Shared with children like [referenced], so a handle used in a nested block still lands here,
     * but a lambda body starts a fresh set: a lambda is a value that can escape the block it was
     * built in, so what its body captured has to be attributable to that body alone (ADR 0008).
     */
    internal val captured: MutableSet<ScopeId> = mutableSetOf(),
) : Scope(names, id) {
    internal var pending: PendingFlow? = null
}

/**
 * A nested block.
 *
 * @param isolateReturns true for lambda bodies, whose `return` is a non-local return and
 *   must not drive the enclosing function's inferred return type (ADR 0007). Control-flow
 *   bodies pass false and share the list.
 * @param isolateCaptures true for lambda bodies, whose captures belong to the value they
 *   produce rather than to the enclosing block. Control-flow bodies pass false and share both sets.
 */
internal fun BlockScope.child(
    label: String,
    isolateReturns: Boolean = false,
    isolateCaptures: Boolean = false,
): BlockScope =
    BlockScope(
        builder = CodeBlock.builder(),
        names = names.child(),
        id = id.child(label),
        returns = if (isolateReturns) mutableListOf() else returns,
        detachedRoot = detachedRoot,
        // Isolated for a lambda body on exactly the same grounds as `captured`, and nothing is lost
        // by it: a lambda is a value, so what its body touched has to be attributable to that body
        // alone. The `stmts` root still learns about a capture made inside a lambda, but through
        // the lambda *value* — `lambdaOf` puts it in the returned handle's `usedScopes`, and the
        // enclosing block runs `checkOwned` on that handle wherever it is used, which records it
        // there. Sharing the set instead made the record flow the other way as well: every lambda
        // built in a `stmts { }` fragment inherited whatever its *earlier siblings* had touched,
        // so a lambda capturing nothing foreign was rejected at splice because of a statement
        // written above it, and reordering the two changed the answer (Task 21, B3).
        referenced = if (isolateCaptures) mutableSetOf() else referenced,
        captured = if (isolateCaptures) mutableSetOf() else captured,
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
