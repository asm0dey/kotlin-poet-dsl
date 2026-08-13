package site.asm0dey.poetdsl

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
)

/** The file-level scope. */
@FileDsl
public class FileScope internal constructor(
    internal val builder: FileSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id)

/** The type-level scope: members are declared here. */
@TypeDsl
public class TypeScope internal constructor(
    internal val builder: TypeSpec.Builder,
    names: NameScope,
    id: ScopeId,
) : Scope(names, id) {
    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    internal fun finish(): TypeSpec {
        if (hasCtor) builder.primaryConstructor(ctor.build())
        return builder.build()
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

context(f: FileScope)
public operator fun FunSpec.unaryPlus() {
    f.builder.addFunction(this)
}

context(f: FileScope)
public operator fun FunSpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: FunSpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: FunSpec) {
    +spec
}

context(f: FileScope)
public operator fun TypeSpec.unaryPlus() {
    f.builder.addType(this)
}

context(f: FileScope)
public operator fun TypeSpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: TypeSpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: TypeSpec) {
    +spec
}

context(f: FileScope)
public operator fun PropertySpec.unaryPlus() {
    f.builder.addProperty(this)
}

context(f: FileScope)
public operator fun PropertySpec.invoke() {
    +this
}

context(f: FileScope)
public fun emit(spec: PropertySpec) {
    +spec
}

context(f: FileScope)
public fun add(spec: PropertySpec) {
    +spec
}
