package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
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
    /**
     * Aliases already used by an `aliasedImport` in this file (D43). KotlinPoet keeps its imports in
     * a `TreeSet` keyed on the *imported* name, so two aliases that collide are two distinct entries
     * and both are emitted; Kotlin answers that with *conflicting import: imported name 'X' is
     * ambiguous*. Its own set, not [declaredTypeNames]: an alias and a declared type of the same name
     * are a different question, and this DSL has measured no answer for it.
     */
    internal val declaredImportAliases: MutableSet<String> = mutableSetOf()

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
    /**
     * The [ScopeId] of the [FileScope] this type ultimately came from, threaded down unchanged
     * through every level of [declareType] and [addCompanionObject] — a **second, separate**
     * identity from [id], carried because the two things [id] was being asked to express are not
     * the same thing:
     *
     * - **instance ownership**, which stops at a non-`inner` type or companion boundary, so
     *   [checkOwned] refuses an enclosing type's property or constructor-parameter handle;
     * - **lexical file visibility**, which a top-level declaration keeps at *any* nesting depth —
     *   `val limit = 10` at file level is readable inside `class Outer { class Inner { … } }` and
     *   inside a `companion object`, and kotlinc agrees.
     *
     * Rooting a nested type's [id] at `ScopeId(null, …)` killed both at once. Parenting it at
     * `fileId.child(…)` instead keeps them apart: every intermediate type's own [id] is a
     * *sibling* under [fileId], never an ancestor of the nested child's [id] (they are distinct
     * [ScopeId] instances and [ScopeId.isAncestorOf] compares by identity), so an enclosing
     * instance's handle is still refused at every depth — while a file-level handle's owner **is**
     * [fileId], which is always an ancestor, so it stays accepted.
     *
     * Defaults to a fresh root for a type with no file above it — [typeSpec]'s detached builder and
     * directly-constructed test scopes. Nothing carries that id, so nested types under it chain to
     * a scope no handle can ever name, which is the strict answer those cases already had.
     */
    internal val fileId: ScopeId = ScopeId(null, "file"),
    /**
     * Whether this type is `expect` — **including implicitly**, by being declared inside one.
     * Threaded down through [declareType] and [addCompanionObject] exactly the way [fileId] is, and
     * for the same kind of reason: the fact belongs to the whole nest, and the immediate builder's
     * own modifiers cannot answer it.
     *
     * Kotlin puts no `expect` keyword on a nested classifier of an `expect class` — writing one is
     * itself rejected — yet applies every `expect` rule to it. Measured, kotlinc 2.4.10 with
     * `-Xmulti-platform`: `expect class E { class N { val x: Int = 1 } }` is *expected property
     * cannot have an initializer*, and so is the same shape with `companion object` in place of
     * `class N`, while dropping the initializer leaves the compiler with nothing to say about
     * initialization anywhere in the tree. So a property with no value is right there, and
     * [PropertyContainer] reads this flag rather than `builder.modifiers` to know it — which
     * [UninitializedPropertyCompileTest] pins on this DSL's own output.
     */
    internal val isExpect: Boolean = false,
    /**
     * Whether this type is `external` — **including implicitly**, by being declared inside one.
     * Threaded down exactly the way [isExpect] and [fileId] are, and for the same kind of reason:
     * the fact belongs to the whole nest.
     *
     * KotlinPoet threads the same fact down itself, and at *every* depth: `TypeSpec.emit` passes
     * `kind.implicitFunctionModifiers(modifiers + implicitModifiers)` to every member function
     * (`TypeSpec.kt:348`), so a function inside `external class C { class N { … } }` is emitted with
     * an implicit `EXTERNAL` and **its body is omitted**. That is what makes
     * `external class C { class N { fun f(): Int } }` a signature rather than an empty block, and it
     * is clean on Kotlin/JS and Kotlin/Wasm at every depth (measured). The empty-body rule in
     * [buildFun] has to know it or it would refuse output two frontends accept.
     *
     * **Deliberately not the same reading as [PropertyContainer.externalAllowed]**, which asks the
     * *immediate* builder and must keep doing so: a **property**'s implicit modifiers come from
     * `kind.implicitPropertyModifiers(modifiers)` (`TypeSpec.kt:313`), the immediate builder's alone,
     * so the keyword survives one level down where a function's does not. Two questions about one
     * keyword, and KotlinPoet answers them differently — see D37 and D40's suppression table.
     */
    internal val isExternal: Boolean = false,
) : Scope(names, id), Annotatable {
    /**
     * What [superclass] was given, or null if it has not run. KotlinPoet tracks the same fact —
     * `TypeSpec.Builder.superclass` — but keeps the field `internal` (`TypeSpec.kt:529`), so it can
     * be neither read back for a message nor read back by [finish]'s parenthesis check; and its own
     * `check` message ("superclass already set to …") names neither this DSL's construct nor the
     * type it happened in.
     *
     * The [TypeName] rather than a bare flag, because [finish] needs to *name* it: the one member of
     * the `expect` family whose refusal is decided after the whole body has run reads this.
     */
    internal var superclassType: TypeName? = null

    /**
     * Where a **plain** primary-constructor parameter — `param(null, …)` / `constructorParam(null, …)`,
     * no `val`/`var`, so no property — lives, on both axes at once. Deviation D30.
     *
     * A plain parameter is in scope in a property initializer and in an `init { }` block, and
     * nowhere else: `class A(x: Int) { fun f() = x }` is `e: Unresolved reference 'x'.` (measured).
     * The type's own [names]/[id] are therefore the *member* level — properties, `val`/`var`
     * parameters, member bodies — and this pair is a child level nested inside it, holding what only
     * initializers can see:
     *
     * - [initializerNames] is a child [NameScope], so a member function's parameters (declared
     *   against [names]`.child()`) no longer rename against a plain parameter they cannot shadow,
     *   while a property or a `val`/`var` parameter still steps over one — see
     *   [NameScope.uniqueAvoiding] for why that direction still has to hold.
     * - [initializerId] is a child [ScopeId], so ADR 0008 answers the *use* side with no change to
     *   [checkOwned]: a plain parameter's handle encloses an `init { }` block, whose [BlockScope] is
     *   built as its child, and does not enclose a member body, whose [BlockScope] is a child of
     *   [id] — so `` `fun`("f") { +seed } `` is rejected instead of rendering an unresolved name.
     *   A `val`/`var` parameter is a property and keeps [id] itself, so it stays usable everywhere.
     */
    internal val initializerNames: NameScope = names.child()

    /**
     * See [initializerNames]. The label is what [checkOwned] prints when a plain parameter's handle
     * is used in a member body — "Handle from scope 'the primary constructor's plain parameters (use
     * param(VAL, …) to reach it from a member body)' does not enclose the current scope 'fun(f)'." —
     * so it names the level rather than the type, and, since [checkOwned] only ever interpolates
     * `owner.label` and takes no message of its own, folds the remedy into the label rather than
     * touching [checkOwned] itself.
     */
    internal val initializerId: ScopeId = id.child(
        "the primary constructor's plain parameters (use param(VAL, …) to reach it from a member body)",
    )

    /**
     * Whether a `companionObject` was already declared. Kotlin allows one per type, and KotlinPoet
     * agrees ("Multiple companion objects are present but only one is allowed.") — but only from
     * `TypeSpec.Builder.build`, once the whole type is assembled and with no idea which construct
     * wrote what. Deviation D29.
     */
    internal var hasCompanionObject: Boolean = false

    internal val ctor: FunSpec.Builder by lazy(LazyThreadSafetyMode.NONE) { FunSpec.constructorBuilder() }
    internal var hasCtor: Boolean = false

    /**
     * Whether a secondary `` `constructor` `` was declared that does **not** delegate with
     * `` `this`(…) `` — either it delegates with `` `super`(…) `` or it delegates not at all.
     *
     * That is the half of the primary/secondary combination that is still invalid after D25:
     * Kotlin requires every secondary constructor of a class that *has* a primary one to delegate
     * to it with `: this(…)`, and answers anything else with `Primary constructor call expected`
     * (measured, both for a plain secondary and for one calling `: super(…)`). Tracked because the
     * two constructs can be written in either order and the broken output is the same — see the
     * guards in [addConstructorParam] and [addSecondaryConstructor].
     *
     * Unlike [finish]'s two checks below, this one has to be eager — a `constructorParam` written
     * *after* an undelegated secondary constructor is the pair D25 rejects outright, at the call
     * that creates it — so it stays a field rather than something read off [builder]. See the
     * review note on [finish] for why the other two do not.
     */
    internal var hasUndelegatedSecondaryCtor: Boolean = false

    /**
     * Constructor parameter names declared directly in this type via [addConstructorParam].
     * Same rationale and same "reject, don't rename" treatment as [declaredPropertyNames] — two
     * constructor parameters named `id` is a compile error with no valid output to preserve —
     * kept as its own set for the same reason: a parameter colliding with a *property* name is a
     * cross-construct collision that still has to uniquify (ADR 0009, amended by D21).
     */
    internal val declaredConstructorParamNames: MutableSet<String> = mutableSetOf()

    /**
     * Every `enumEntry` declared in this type, with the number of constructor arguments it was given
     * (D43). Read once by [checkEnumEntryArgs] from [finish], because the enum's primary constructor
     * can be written *after* the entry — a `constructorParam` at the end of the body still supplies
     * it — so an eager check would answer on writing order alone. The same deferral D25's three
     * checks take, for the same reason.
     */
    internal val enumEntryArgCounts: MutableList<Pair<String, Int>> = mutableListOf()

    /**
     * Whether an `` `init` `` block was added. KotlinPoet keeps its own record in
     * `TypeSpec.Builder.initializerBlock`, which is `internal`, and [enumBodyNeedsASemicolon] needs
     * the fact — it is one of the four member kinds whose presence makes KotlinPoet write the `;`
     * that separates an enum's entries from its body. Set only by [addInitializerBlock], which is the
     * only route to one in this DSL: a spliced `` +funSpec `` or `` +propertySpec `` cannot carry an
     * initializer block, and `TypeSpec.Builder.addInitializerBlock` is reached from nowhere else.
     */
    internal var hasInitializerBlock: Boolean = false

    /**
     * Builds the type, after the one check that can only be made once the whole body has run.
     *
     * Superclass constructor arguments in the header need a primary constructor to hang from as
     * soon as the type also declares a secondary constructor, and the three constructs involved —
     * `superclass(…, args)`, `` `constructor` `` and `constructorParam` — may be written in any
     * order, with the last of them able to *fix* the combination as easily as to break it. Checking
     * eagerly at each of them would therefore reject `superclass(Base, x)` + a delegating
     * `` `constructor` `` + a later `constructorParam`, which is valid Kotlin. Here the answer does
     * not depend on writing order. D25's other guard needs no such deferral: neither a primary
     * constructor nor an undelegated secondary one can be taken back by a later call, so
     * [addConstructorParam] and [addSecondaryConstructor] catch that pair eagerly, at the call that
     * creates it.
     *
     * The self-delegation cycle checked second is deferred for the identical reason: a class whose
     * only constructor delegates with `` `this`(…) `` calls itself, but a `constructorParam` written
     * further down the body still turns that into a legal call to the primary constructor.
     *
     * Both checks below count secondary constructors by reading [builder]`.funSpecs` (KotlinPoet's
     * own record) rather than an incrementally-tracked field. A secondary constructor spliced in as
     * a raw `FunSpec` — `` +ctorSpec `` via [unaryPlus] — bypasses [addSecondaryConstructor]
     * entirely, so a field that only [addSecondaryConstructor] incremented undercounted it: a class
     * with one `` `constructor` `` delegating with `` `this`(…) `` plus one spliced sibling was
     * measured tripping [LONE_SECONDARY_DELEGATING_TO_ITSELF] even though it has two constructors
     * and kotlinc accepts the identical Kotlin. `builder.funSpecs` already carries every secondary
     * constructor regardless of how it was added, and `FunSpec.isConstructor`/`delegateConstructor`
     * read back exactly what [addSecondaryConstructor] would otherwise have tracked by hand — so
     * reading the builder here, once, replaces three fields (`hasSecondaryCtor`, `secondaryCtorCount`,
     * `thisDelegatingSecondaryCtorCount`) with none.
     */
    internal fun finish(): TypeSpec {
        // The classifier-kind family's one deferred question, here for the same reason the three
        // below are: a `constructorParam` written at the end of the body still supplies the
        // parameter, so asking eagerly would answer on writing order alone. See [Kinds].
        // …and `kindName == "class"`, because a **`data object`** has no primary constructor to
        // give it and is valid Kotlin (1.9 and later): `data object O` is clean on all three
        // frontends, and this rule refused it in every container. D42's one false rejection. A
        // `data interface` never reaches here — the modifier family refuses it in `declareType`,
        // with the sentence the frontends print (*modifier 'data' is not applicable to
        // 'interface'*) rather than this one, which was about a constructor an interface has no
        // notion of.
        if (KModifier.DATA in builder.modifiers && kindName == "class" && !hasCtor) {
            dataClassNeedsAParameter(kindName)
        }
        // D43's deferred half, here for the reason the three below are: the enum's primary
        // constructor can be written after the entry that calls it. See [checkEnumEntryArgs].
        if (enumEntryArgCounts.isNotEmpty()) checkEnumEntryArgs()
        // …and D43's other deferred check, for the same reason: a property or a function written
        // after the `typealias` supplies the `;` KotlinPoet omits for it. See
        // [checkEnumTypeAliasRenders].
        if (KModifier.ENUM in builder.modifiers && builder.typeAliasSpecs.isNotEmpty()) {
            checkEnumTypeAliasRenders()
        }
        val secondaryCtors = builder.funSpecs.filter { it.isConstructor }
        check(builder.superclassConstructorParameters.isEmpty() || secondaryCtors.isEmpty() || hasCtor) {
            superclassArgsPlusSecondary(kindName)
        }
        val delegatesToPrimary = { spec: FunSpec -> spec.delegateConstructor == DelegationTarget.THIS.keyword }
        check(hasCtor || secondaryCtors.size != 1 || !delegatesToPrimary(secondaryCtors.single())) {
            LONE_SECONDARY_DELEGATING_TO_ITSELF
        }
        // The `expect` family's sixth member, and the third check here that can only be answered once
        // the whole body has run — for the same reason as the two above, and one more. Whether
        // KotlinPoet writes `: Base` or `: Base()` depends on the constructors this type ends up
        // with (`TypeSpec.kt:238`), and those can be written after the `superclass(…)` call, so an
        // eager check in `applySuperclass` would answer on writing order alone.
        //
        // `EXPECT` is read off this builder's **own** modifiers because that is exactly what
        // `TypeSpec.emit` reads at `:239` — [isExpect] is the inherited fact, which is what makes the
        // *frontends* apply the rule, and the two together are the render gap. See
        // [nestedSupertypeRenderGap].
        //
        // `TypeSpec.emit` reads `areNestedExternal` on the same line, and this check deliberately
        // does **not**: the term failed zero tests under falsification, and the reason is that the
        // shape it would protect is refused on other grounds by every frontend —
        // `expect class E { external class N : Base }` is *expected declaration cannot be external*
        // on all three, plus *external type extends non-external type 'Base'* and *non-top-level
        // 'external' declaration* on JS and Wasm (measured; `expect class E { external class O {
        // class N : Base } }`, the propagated form, answers identically because `isNestedExternal`
        // is threaded down at `TypeSpec.kt:360`). So no target accepts it either way, and a term
        // nothing can falsify is a term nothing is maintaining. The one imprecision that leaves is
        // in the message, which says KotlinPoet renders `: Base()` where for an `external` type —
        // its own or an enclosing one's — it renders `: Base`; the rows below pin both refusals so
        // the trade is recorded rather than discovered.
        //
        // **`ANY` is not a supertype KotlinPoet renders at all.** `TypeSpec.emit` filters it out
        // one line *above* the parenthesis decision — `listOf(superclass).filter { it != ANY }`
        // (`TypeSpec.kt:235`) — so `superclass(ANY)` produces no supertype clause in any container,
        // `expect` or not, and there is nothing here to refuse: `` `class`(EXPECT, "E") {
        // `class`("N") { superclass(ANY) } } `` renders `public class N` and
        // `expect class E { class N }` is clean on all three frontends. Without this term the check
        // refused a shape that rendered valid Kotlin at `68df28f`, with a message asserting a render
        // (`: kotlin.Any()`) that KotlinPoet never produces for this type.
        val parenthesized = hasCtor || secondaryCtors.isEmpty()
        if (isExpect && parenthesized && KModifier.EXPECT !in builder.modifiers) {
            superclassType?.takeIf { it != ANY }?.let { nestedSupertypeRenderGap(kindName, it) }
        }
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
    /**
     * The `: this(…)` / `: super(…)` slot the `` `this` ``/`` `super` `` constructs write into (D25).
     *
     * Non-null in exactly one place: the *root* block of a secondary constructor's body, which
     * [buildFun] is the only creator of. A delegation call is part of a constructor's header, not
     * its body, so it is captured here and handed to `FunSpec.Builder.callThisConstructor` instead
     * of being emitted — and Kotlin allows it nowhere else, which is why [child] does not propagate
     * it: a `` `this`(…) `` inside a lambda, an `if` body or a `stmts { }` fragment finds `null` and
     * is rejected.
     */
    internal val delegation: ConstructorDelegation? = null,
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

/**
 * Builds a `.kt` file.
 *
 * [fileComment] is the banner above the `package` line — "Generated by X. Do not edit." — which is
 * the single most common thing asked of a code generator. A **defaulted parameter** rather than a
 * `context(f: FileScope)` construct: a construct would need a shadow, since `context(f: FileScope)`
 * resolves from inside a nested type and a block body alike (the hazard that produced the 141
 * shadows), and a file-level comment written in a function body would silently attach itself to the
 * file. That is a new public declaration and a new shadow for one line of convenience, and it
 * reopens ADR 0002; a slot on the one construct that *is* the file costs neither.
 *
 * The text goes through `"%L"`: `addFileComment` is a format function, and
 * `addFileComment("100% done")` raises `IllegalArgumentException: index 1 for '% ' not in range`.
 * See [docBlock] — spelled out here rather than reused, because `addFileComment` has no `CodeBlock`
 * overload.
 */
public fun file(
    packageName: String,
    fileName: String,
    fileComment: String? = null,
    body: FileScope.() -> Unit,
): FileSpec {
    val scope = FileScope(FileSpec.builder(packageName, fileName), NameScope(null), ScopeId(null, "file"))
    fileComment?.let { scope.builder.addFileComment("%L", it) }
    scope.body()
    return scope.builder.build()
}

/** Emits [this] into the innermost scope's builder; a block body is not a legal target. */
context(s: Scope)
public operator fun FunSpec.unaryPlus() {
    s.checkAnonymousBodySplice("FunSpec", name, modifiers)
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
    s.checkAnonymousBodyTypeSplice(this)
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
    s.checkAnonymousBodySplice("PropertySpec", name, modifiers)
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
