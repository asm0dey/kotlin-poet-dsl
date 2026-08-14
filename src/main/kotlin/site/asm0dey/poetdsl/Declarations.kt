package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

// The public entry points of every construct in this file — `class`/`klass`, `object`,
// `interface`, `constructorParam`/`ctorParam`, `fun`/`func` and `constructor`/`ctor` — are
// generated into `FunArity.kt`, `CtorArity.kt` and `DeclarationVariants.kt` by
// `buildSrc/src/main/kotlin/ArityGenerator.kt`, in ADR 0004's six variants and (for the three
// parameter-taking ones — `class` since D23) nine arities plus D24's list form. What stays here is
// the machinery they all call, and the parameter descriptors they are handed.

/**
 * Wraps documentation prose so that KotlinPoet treats it as text and not as a format string.
 *
 * `Documentable.Builder.addKdoc`, `FileSpec.Builder.addFileComment` and the `kdoc` arguments of
 * `FunSpec.Builder.returns`/`receiver` are all **format** functions, and documentation is exactly
 * the place a `%` turns up in ordinary prose: a percentage, a `%s` in an example, a `%T` pasted from
 * KotlinPoet's own docs. Handed straight through, `addKdoc("100% done")` raises
 * `IllegalArgumentException: index 1 for '% ' not in range (received 0 arguments)` and
 * `addKdoc("a %S b", …)` silently consumes whatever argument comes next (both measured against
 * KotlinPoet 2.3.0). `%L` with the whole string as its single argument is the escape: the argument
 * is emitted verbatim and never re-scanned.
 *
 * **Every route from a caller's text into KotlinPoet's documentation API goes through here or
 * through the identical `"%L"` spelling.** Nothing in this DSL passes user text as a format string.
 */
internal fun docBlock(text: String): CodeBlock = CodeBlock.of("%L", text)

/**
 * Adds a type declaration to whichever scope is innermost: a top-level type in a file, a
 * nested type in a type. Kotlin allows local classes but not local named objects, interfaces,
 * enums or annotation classes — [localAllowed] says which is which, and Task 20's shadow
 * members turn the invalid cases into compile errors.
 *
 * The local-class case is valid Kotlin that KotlinPoet 2.3.0 cannot render, so it currently
 * throws too, with a different message — see [localClassIsUnrenderable].
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a future
 * fourth scope breaks the build here rather than falling through silently (D17).
 *
 * The nested type gets a *fresh* root [NameScope] rather than `names.child()`: a nested
 * (non-`inner`) class cannot see an enclosing type's members, so there is nothing for one of
 * its constructor parameters to shadow, and chaining would only rename it for no reason,
 * changing the generated public API. `typeSpec`'s detached path already does this; this makes
 * the attached path agree. `inner class` is the one case where a same-named parameter really
 * would shadow an outer member — accepted as-is rather than special-cased: renaming the
 * parameter would change the public API, which is worse than the shadowing. Member *bodies*
 * (Task 19's local functions and beyond) still chain through `BlockScope.child`, which is the
 * case ADR 0009's shadowing rationale actually describes — this only changes how a *type's own*
 * scope is rooted.
 *
 * A nested type's [ScopeId], not just its [NameScope], is re-parented too: it is
 * [TypeScope.fileId]`.child("type")` — a child of the *file*, skipping every intermediate type —
 * rather than `id.child("type")`, when the enclosing scope is itself a [TypeScope]. Chaining to the
 * enclosing type would let `checkOwned` *accept* a handle from that type's own scope (a constructor
 * parameter or property) inside the nested type's member bodies, which Kotlin does not allow a
 * non-`inner` nested class — `class N2(val id: Long) { class Inner { init { println(id) } } }` is
 * `e: Unresolved reference 'id'.` (measured). Re-parenting at the file rejects that just as a bare
 * root would, because the enclosing type's own [id] is a *sibling* under the same [TypeScope.fileId]
 * and never an ancestor — while keeping the direction that *is* legitimate at every depth: a
 * top-level property or function is visible in every type's body in the same file however deeply
 * nested, and Kotlin agrees. A *top-level* type takes the [FileScope]'s own [id] as its
 * [TypeScope.fileId], which is the same `id.child("type")` it always had.
 *
 * This paragraph used to record the opposite trade-off — that rooting the [TypeScope] branch at
 * `ScopeId(null, "type")` cost a type nested two or more levels deep its file-level handle access,
 * an accepted narrower gap. [TypeScope.fileId] closed it; the gap no longer applies, in this
 * construct or in [addCompanionObject], which had the same hole at *every* depth.
 *
 * [typeVariables] are D31's: the declaration's own `<T>`, added before the body runs so that a
 * member declared inside it renders against the same [TypeVariableName] values the signature does.
 * Only a class and an interface reach this with a non-empty list — an `object` has no type parameters
 * in Kotlin, so its generated overloads carry no slot to pass one through.
 */
internal fun Scope.declareType(
    builder: TypeSpec.Builder,
    kindName: String,
    name: String,
    localAllowed: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    params: List<ParameterSpec>,
    typeVariables: List<TypeVariableName>,
    kdoc: String?,
    body: TypeScope.(List<Expr>) -> Unit,
) {
    if (this is BlockScope) {
        check(localAllowed) {
            "A local $kindName is not valid Kotlin. Declare it at file or type level."
        }
    }
    // The two rules of the classifier-kind family that are about *this* declaration's placement
    // rather than about its body, asked before the name is registered so that a refused declaration
    // does not burn a name — the same ordering Task 12 gave a rejected binding. See [Kinds] for the
    // measured rows and their controls; both fire in every container and neither is inherited.
    if (KModifier.INNER in modifiers.toList() && !innerAllowed) {
        innerNeedsAnEnclosingClass(kindName, name)
    }
    if (!nestedTypesAllowed && KModifier.INNER !in modifiers.toList()) {
        innerHoldsNoNestedType(kindName, name)
    }
    // Types only: Kotlin permits function overloads, so duplicate `fun` names are legal and
    // must never go through `declaredTypeNames`.
    check(name !in declaredTypeNames) {
        "A $kindName named \"$name\" is already declared in this scope."
    }
    declaredTypeNames += name
    // Declaration-site variance is exactly what a class or an interface is allowed; `reified` is
    // exactly what it is not, being an inline function's business. An `enum class` takes no type
    // parameters at all, and the modifiers that say so are right here.
    checkTypeVariables(
        "`$kindName`",
        name,
        typeVariables,
        varianceAllowed = true,
        reifiedAllowed = false,
        isEnum = KModifier.ENUM in modifiers.toList(),
    )

    // The file this type belongs to, threaded on unchanged: a nested type inherits the enclosing
    // type's `fileId`, a top-level one takes the file's own `id`. (A `BlockScope` has no file above
    // it — a local type is rejected below either way — so its own `id` stands in, which is the
    // scope a local type would nest under if KotlinPoet could render one.)
    val enclosingFileId = if (this is TypeScope) fileId else id
    // `expect` is inherited by every classifier nested inside an `expect` one, and Kotlin puts no
    // keyword on the nested declaration to say so. See [TypeScope.isExpect].
    //
    // [Scope.isExpectContainer], not `this is TypeScope && isExpect` written out again. This is the
    // site that *propagates* the fact to every nested scope, so it is the last place that should
    // spell it privately — and because the two are value-identical today, no falsification can catch
    // the day they stop being. Five rounds of this project's recurring defect say that is exactly
    // how it starts.
    val expected = KModifier.EXPECT in modifiers.toList() || isExpectContainer
    kdoc?.let { builder.addKdoc(docBlock(it)) }
    val scope = TypeScope(
        builder.addModifiers(modifiers.toList()).addTypeVariables(typeVariables),
        NameScope(null),
        enclosingFileId.child("type"),
        kindName,
        enclosingFileId,
        expected,
    )
    scope.addAll(annotations)
    // The primary-constructor parameters of D23's signature form go in before the body runs, so the
    // body sees their handles — and so a `superclass(…, x)` written in the body can pass one. They
    // take the same path a hand-written `constructorParam` does, duplicate names and all: the two
    // forms are the same construct, only spelled at different places.
    val handles = params.map { scope.addConstructorParam(it.tag(ParamKind::class), it) }
    scope.body(handles)
    val spec = scope.finish()
    when (this) {
        is FileScope -> this.builder.addType(spec)
        is TypeScope -> this.builder.addType(spec)
        is BlockScope -> {
            // A local class *is* valid Kotlin, but KotlinPoet 2.3.0 cannot render one. See
            // `localClassIsUnrenderable` below; the guard is here rather than at the call site
            // so the two reasons stay distinguishable in the message.
            localClassIsUnrenderable()
        }
    }
}

/**
 * Rejects a local class instead of emitting Kotlin that does not compile (Global Constraint 26).
 *
 * `TypeSpec.emit` hardcodes `implicitModifiers = setOf(PUBLIC)` when it calls
 * `CodeWriter.emitModifiers` (KotlinPoet 2.3.0, `TypeSpec.kt:183-186`), and
 * `CodeWriter.shouldEmitPublicModifier` (`CodeWriter.kt:670-690`) then emits an explicit
 * visibility keyword for **every** `TypeSpec`: `public` when none was set, or the one that was.
 * `CodeBlock.of("%L", spec)` therefore always yields `public class Name`, and Kotlin rejects
 * *any* visibility modifier on a local class — measured for `public`, `private`, `internal` and
 * `protected`, all "Modifier 'x' is not applicable to 'local class'". KotlinPoet exposes no way
 * to suppress it (`TypeSpec.emit` is internal, and the `omitImplicitModifiers` escape hatch in
 * `CodeWriter.emitLiteral` applies only to `FunSpec`), and stripping the keyword afterwards
 * would be string surgery on rendered output, which the backend constraint forbids and which
 * would break `%T` import resolution.
 *
 * Flip this to the emission call the moment the backend can render a local class; the
 * `local class rendering is still blocked by KotlinPoet` test in `TypeScopeTest` is the canary
 * that fails when that happens. Task 19's local functions hit the identical wall.
 */
private fun localClassIsUnrenderable(): Nothing = error(
    "A local class cannot be rendered: KotlinPoet 2.3.0 emits an explicit visibility " +
        "modifier on every type, and Kotlin allows none on a local class. Declare it at file " +
        "or type level.",
)

/**
 * The indefinite article for a noun a message is about to name, spelled once.
 *
 * Every noun that reaches this is a [TypeScope.kindName] (`"class"`, `"named object"`,
 * `"companion object"`, `"interface"`) or a classifier keyword (`"annotation"`, `"value"`), so the
 * first letter decides it and no irregular case can arise. It exists because two files were
 * deciding it independently — [annotationOrValueRenderGap] got it right and
 * [addConstructorParam]'s container-kind message printed *a interface*.
 */
internal fun article(noun: String): String = if (noun.first() in "aeiou") "an" else "a"

/**
 * Whether a constructor parameter also declares a property, and if so whether it is mutable.
 * `null` in [constructorParam]'s `kind` slot means a plain parameter with no property.
 *
 * The plan spells this slot `KModifier?` and its call sites pass `KModifier.VAL`/`KModifier.VAR`,
 * but KotlinPoet 2.3.0's `KModifier` has no such entries — `val`/`var` are not modifiers in
 * KotlinPoet's model, which expresses a constructor property as a `PropertySpec` whose
 * `mutable` flag and `%N` initializer tie it back to the parameter. A dedicated enum makes the
 * three legal states exactly the three representable ones, so no runtime kind check is needed
 * or possible. Imported entry-wise (`import site.asm0dey.poetdsl.ParamKind.VAL`) it reads at
 * the call site exactly as the plan writes it. See deviation D19.
 */
public enum class ParamKind {
    /** A read-only property: `val name: T`. */
    VAL,

    /** A mutable property: `var name: T`. */
    VAR,
}

internal fun TypeScope.addConstructorParam(
    kind: ParamKind?,
    annotations: Annotations?,
    name: String,
    type: TypeName,
    default: Expr?,
    modifiers: KModifier?,
    kdoc: String?,
): Expr = addConstructorParam(
    kind,
    buildParam(name, type, default, modifiers, kdoc) { annotations?.list?.forEach { addAnnotation(it) } },
)

/**
 * The [ParameterSpec] form: what D23's `` `class`(…, param(VAL, "id", LONG)) `` signature hands over,
 * and what the name-and-type form above builds for the in-body `constructorParam`. One path, so a
 * parameter declared either way gets the same duplicate check, the same uniquifying and the same
 * handle.
 */
internal fun TypeScope.addConstructorParam(kind: ParamKind?, spec: ParameterSpec): Expr {
    val name = spec.name
    val type = spec.type
    // The same container fact [beginSecondaryConstructor] asks 700 lines below, at the one other
    // construct that puts something on a constructor — asked here too, because a primary constructor
    // is exactly as impossible in an `object`, a `companion object` or an `interface` as a secondary
    // one is. Without it, `` `object`("O") { constructorParam(VAL, "x", INT) } `` reached KotlinPoet's
    // own `IllegalStateException: OBJECT can't have a primary constructor` from [TypeScope.finish] —
    // the right exception type with a message naming neither construct — and the `expect` refusal
    // below fired first for `` `object`(EXPECT, "O") { … } ``, telling the caller to write a *plain*
    // parameter on a primary constructor that cannot exist either way. An `enum class` is a `class`
    // here and keeps its primary constructor.
    check(kindName == "class") {
        "constructorParam: ${article(kindName)} $kindName has no primary constructor; only a class " +
            "can declare one. Declare it as a property in the body instead."
    }
    // E2b's parameter modifiers and defaults, judged against the parameters this primary constructor
    // already has: the vararg count is the one rule that spans a whole parameter list, and a primary
    // constructor is assembled one call at a time, so the running list is where it has to be read.
    // `inline` is not a thing a constructor can be, so `noinline`/`crossinline` are refused outright
    // here rather than conditionally.
    checkParams(
        owner = "the primary constructor",
        inlineRemedy = "A constructor is never `inline`, so drop the parameter modifier.",
        isInline = false,
        isOverride = false,
        earlier = ctor.parameters,
        params = listOf(spec),
    )
    // A second constructor parameter named `name` is a compile error in Kotlin with no valid
    // output to preserve, so it is rejected outright rather than renamed to `name2` (ADR 0009,
    // amended by D21) — the same treatment `propertyOf` gives a duplicate property, and for the
    // same reason `declaredConstructorParamNames` is its own set rather than shared with
    // `declaredPropertyNames`: a parameter colliding with a *property* name is a cross-construct
    // collision that still has to uniquify.
    check(name !in declaredConstructorParamNames) {
        "A constructor parameter named \"$name\" is already declared in this scope."
    }
    // The other half of the guard in `addSecondaryConstructor`, for the reverse writing order: a
    // secondary constructor that does not delegate with `: this(…)` was legal until this parameter
    // gave the class a primary constructor. Same broken output either way, so the same message
    // names both constructs. A secondary that *does* delegate is fine here — that is D25.
    //
    // Not in an `expect` type, where Kotlin requires the opposite: `expect class E(x: Int) {
    // constructor(p: Long) }` is clean on all three frontends and *adding* the delegation call is
    // "explicit delegation call for constructor of expected class is prohibited" (measured). Without
    // the exemption the two halves of this rule would make the shape unspellable. See [Expect].
    check(!hasUndelegatedSecondaryCtor || isExpectContainer) { SECONDARY_MUST_DELEGATE_TO_PRIMARY }
    // Two refusals that are about the **classifier's own kind**, not about its container, and that
    // therefore come first — the caller who wrote `annotation class N(var x: Int)` needs to hear
    // about the `var`, whatever container they wrote it in.
    //
    // The first is the language rule underneath the `expect` exemption below, read for its own sake:
    // Kotlin requires the primary-constructor parameters of an `annotation class` and a `value
    // class` to be `val`, which is exactly why those two kinds are exempt from the `expect` rule.
    // The exemption was written keyed on "has one of these kinds" and measured only with `val`, so
    // `` `class`(EXPECT, "E") { `class`(ANNOTATION, "N") { constructorParam(VAR, "x", INT) } } ``
    // rendered `annotation class N(var x: Int)` — *an annotation parameter cannot be 'var'* on all
    // three frontends — and the identical shape rendered outside `expect` too, where no round had
    // ever looked. See [propertyParamMustBeVal] for the measured rows and their controls.
    val propertyParamKind = builder.modifiers.firstOrNull { it in PROPERTY_PARAM_KINDS }
    if (propertyParamKind != null && kind != ParamKind.VAL) {
        propertyParamMustBeVal(name, kind, propertyParamKind)
    }
    // The second does depend on the container, but not on `kind`: an `expect` `enum class` has no
    // primary constructor of any sort — *expected enum class cannot have a constructor* on all three
    // frontends, with `val`, with a plain parameter and with an empty parameter list alike. So the
    // family's generic remedy below ("declare it as a plain parameter") named a shape this DSL
    // rendered and no frontend accepts, which is why this refusal comes before it rather than after.
    // See [expectEnumConstructor].
    if (isExpectContainer && KModifier.ENUM in builder.modifiers) expectEnumConstructor(name)
    // A `val`/`var` parameter *is* a property — the [PropertySpec] built below, with a `%N`
    // initializer — so it is the same member the `expect` rule already refuses, reached through the
    // one property-construction site that never goes near [checkProperty]. Refused here, before
    // anything is added to either builder: KotlinPoet's `addProperty` answers the *direct* case with
    // `IllegalArgumentException: properties in expect classes can't have initializers` (Global
    // Constraint 26's forbidden type, naming neither construct), and one level down it answers not
    // at all — `expect class E { class N(val x: Int) }` rendered, and all three frontends call it
    // *expected class constructor cannot have a property parameter*. See [Expect].
    if (kind != null && isExpectContainer) {
        val keyword = if (kind == ParamKind.VAR) "var" else "val"
        when {
            // The **control row**: Kotlin's rule has exactly two exceptions, and they are the two
            // kinds whose primary-constructor parameters Kotlin *requires* to be `val`, so the rule
            // it would break has nothing left to say. Measured, all three frontends, one file per
            // row — and the exempt set is measured rather than reasoned, because every other
            // classifier modifier is refused:
            //
            //     expect class E { annotation class N(val x: Int) }  clean      (the exemption)
            //     expect class E { value class V(val y: Int) }       clean      (the exemption)
            //     expect class E { annotation class N(x: Int) }      'val' keyword is missing in
            //                                                        annotation parameter.
            //     expect class E { value class V(y: Int) }           value class primary constructor
            //                                                        must only have final read-only
            //                                                        ('val') property parameters.
            //     expect class E { data class D(val x: Int) }        expected class constructor
            //     expect class E { inner class N(val x: Int) }       cannot have a property
            //     expect class E { sealed class S(val x: Int) }      parameter.
            //     expect class E { abstract class A(val x: Int) }
            //     expect class E { open class O(val x: Int) }
            //     expect class E { enum class Z(val x: Int) { A(1) } }
            //
            // So the refusal below made a nested `annotation class` with parameters *unspellable* —
            // `val` refused here and a plain parameter invalid Kotlin — which is the shape D34's
            // "refused from both sides at once" names. The exemption reads the **immediate**
            // builder's own modifiers and nothing inherited, exactly as `expectAllowed` and
            // `externalAllowed` do, and for the same reason: it is a fact about what *this*
            // declaration renders.
            propertyParamKind == null -> expectRefusal(
                "constructorParam",
                "'$name' declares a `$keyword` property on the primary constructor of an `expect` " +
                    "class",
                "expected class constructor cannot have a property parameter",
                "Declare it as a plain parameter — constructorParam(null, \"$name\", …) or " +
                    "param(null, \"$name\", …) — and put the property on the `actual` class.",
            )
            // …and the other side of that boundary. `expect annotation class A(val x: Int)` is clean
            // on all three frontends too, and KotlinPoet 2.3.0 renders it by no route at all:
            // `TypeSpec.Builder.addProperty` (`TypeSpec.kt:725-733`) `require`s a null initializer
            // whenever the builder's *own* modifiers carry EXPECT, with no exemption of its own, and
            // a `val`/`var` primary-constructor parameter is a property with a `%N` initializer —
            // the only shape KotlinPoet's `constructorProperties` recognises (D19). At `fa12efe`
            // this raised that `require`'s own `IllegalArgumentException`; what changes here is the
            // exception type and a message that names the construct and the gap, rather than
            // quoting a frontend diagnostic no frontend produces.
            // Only `val` reaches here — [propertyParamMustBeVal] above refused every other kind for
            // these two classifiers, in every container.
            KModifier.EXPECT in builder.modifiers -> error(annotationOrValueRenderGap(name, propertyParamKind))
        }
    }
    declaredConstructorParamNames += name
    // D30's split, in one line. A `val`/`var` parameter *is* a property: it is visible in every
    // member body, so it is declared at the member level and a member parameter of the same name
    // still renames against it (ADR 0009's cross-construct case). A plain parameter is visible only
    // in property initializers and `init { }` blocks, so it is declared one level in — where it
    // still steps over every member name, but nothing declared against the member level steps over
    // it. See [TypeScope.initializerNames].
    val unique = if (kind == null) initializerNames.unique(name) else uniqueMemberName(name)
    // Rebuilt only when the name actually moved: `toBuilder` carries the annotations — and the
    // `ParamKind` tag — across, but an untouched spec is the one the caller passed.
    val param = if (unique == name) spec else spec.toBuilder(name = unique).build()
    ctor.addParameter(param)
    hasCtor = true
    if (kind != null) {
        builder.addProperty(
            PropertySpec.builder(unique, type)
                .mutable(kind == ParamKind.VAR)
                .initializer("%N", param)
                .build(),
        )
    }
    // A bare parameter (kind == null, no property) is still immutable — Kotlin has no `var`
    // constructor parameter without a property — so it is `false`, same as ParamKind.VAL.
    //
    // The owner is the other half of D30: a `val`/`var` parameter is a property of this type, so its
    // handle belongs to the type's own scope and works in every member body; a plain one belongs to
    // the initializer scope nested inside it, so ADR 0008's existing check accepts it in an
    // `init { }` block and rejects it in a member body, where the name it renders is unresolvable.
    return Expr(
        CodeBlock.of("%L", unique),
        type,
        Prec.ATOM,
        unique,
        if (kind == null) initializerId else id,
        mutable = kind == ParamKind.VAR,
    )
}

/**
 * Detached type builder; returns a KotlinPoet spec, so interop with hand-written KotlinPoet is free.
 *
 * That return type makes the type's own *non-block* positions an **unchecked boundary** for ADR
 * 0008: a property initializer or delegate declared directly on this type
 * (`` `val`("p", INT, init = leaked()) ``) is built by the same property path `FileScope` and
 * `TypeScope` share, which never runs `checkOwned` on a binding, so a handle from an unrelated scope
 * is accepted there and never re-judged. `stmts { }` can hand its recorded scopes to the splice
 * because [Stmt] is this DSL's own type, introduced for that purpose; a [TypeSpec] has nowhere to
 * carry one, so the outermost `+spec` has nothing to validate either.
 *
 * Member *bodies* are a different story: every `` `fun` `` declared inside this type is built with a
 * non-null parent scope, so its body is *not* a detached root — a handle from an unrelated scope
 * used inside `` `fun`("f") { … } `` still throws, exactly as it would nested in an attached type.
 * Only the type's own non-block positions and the final `+spec` go unchecked; wrapping the spec to
 * fix that would take the KotlinPoet type out of the return position, which is the whole feature.
 * See ADR 0008's Task 21 amendment.
 */
public fun typeSpec(
    modifiers: Modifiers? = null,
    name: String,
    typeVariables: List<TypeVariableName> = emptyList(),
    kdoc: String? = null,
    body: TypeScope.() -> Unit,
): TypeSpec {
    checkTypeVariables(
        "typeSpec",
        name,
        typeVariables,
        varianceAllowed = true,
        reifiedAllowed = false,
        isEnum = KModifier.ENUM in modifiers.toList(),
    )
    val scope = TypeScope(
        TypeSpec.classBuilder(name)
            .addModifiers(modifiers.toList())
            .addTypeVariables(typeVariables)
            .apply { kdoc?.let { addKdoc(docBlock(it)) } },
        NameScope(null),
        ScopeId(null, "type"),
        // The third and last construction site of a [TypeScope], and the only one with nothing above
        // it: a detached builder has no enclosing scope, so `modifiers` is the whole answer, with no
        // inherited term to add — `declareType`'s `|| isExpectContainer` has nothing to
        // read here. Omitting it defaulted the flag to false, which silently *narrowed* what
        // [PropertyContainer] used to read straight off `builder.modifiers`, and made
        // `typeSpec(EXPECT.toModifiers(), name = "E") { `val`("x", INT) }` — valid multiplatform
        // output that rendered before [TypeScope.isExpect] existed — throw, with an empty remedy list
        // for a message. See `a detached expect typeSpec exempts its own members`.
        isExpect = KModifier.EXPECT in modifiers.toList(),
    )
    scope.body()
    return scope.finish()
}

// --- functions --------------------------------------------------------------------------------

/**
 * A function parameter. Type-position annotations come through [annotated], this DSL's bridge from
 * [annotation]/[ann] to a [TypeName]:
 *
 *     param("x", INT.annotated(ann<Positive>("min" to 0.lit)))   // x: @Positive(min = 0) Int
 *     param("x", INT.annotated(Positive::class))                 // KotlinPoet's own, for a marker
 *
 * The example this KDoc carried until D31 — `param("x", INT.annotated<Positive>())` — matched no
 * overload and never compiled: KotlinPoet's `TypeName.annotated` takes `vararg AnnotationSpec`,
 * `List<AnnotationSpec>`, `vararg ClassName` or `vararg KClass<out Annotation>`, and 2.3.0 has no
 * reified form. The spelling that replaced it — spreading `ann<Positive>().list` — was no better: it
 * reaches `@PublishedApi internal` state and does not compile outside this module at all. [annotated]
 * is the supported route, and the only public one for an annotation *with arguments* in type
 * position; KotlinPoet's `KClass` overload still covers a marker in fewer characters.
 *
 * [name] is a *request*, not a guarantee: a parameter that would shadow a binding of the enclosing
 * scope is uniquified when the function is built, exactly as a lambda parameter is (ADR 0009). The
 * handle the body receives always carries the name actually rendered.
 *
 * @param default the `= …` in `fun f(n: Int = 0)`. Rejected on an `override` function's parameter —
 *   *An overriding function is not allowed to specify default values for its parameters.* Unlike a
 *   handle used in a body, a default value runs **no ownership check**: [param] is a descriptor
 *   evaluated at the call site, before the declaration it belongs to exists, so a handle from an
 *   unrelated scope is accepted here and never judged (the same unchecked boundary annotation
 *   arguments have — ADR 0008, Task 11's tracked debt).
 * @param modifiers a **single** [KModifier], not this DSL's usual [Modifiers] set, because Kotlin's
 *   parameter modifiers are exactly `VARARG`, `NOINLINE` and `CROSSINLINE` and **no two of them ever
 *   legally combine**: `noinline` and `crossinline` are alternatives to each other, and
 *   `inline fun f(vararg noinline g: () -> Unit)` is *Modifier is only allowed for function
 *   parameters of an inline function* — a vararg parameter's type is `Array<out …>`, never a
 *   function type (measured). The slot's type therefore says what the language says. Two rules are
 *   enforced when the declaration is built rather than here, because neither is decidable from one
 *   parameter: **at most one `vararg` per parameter list** (*Multiple vararg parameters are
 *   prohibited.*), counted across a whole primary constructor even when it is assembled one
 *   `constructorParam` call at a time; and `noinline`/`crossinline` only inside an `INLINE`
 *   function, which a constructor can never be. The `vararg`'s *position* is deliberately not
 *   checked: `fun f(vararg xs: Int, y: Int)` compiles and callers pass `y` by name, so a
 *   "vararg must be last" rule would refuse valid generator code. `private`/`override` on
 *   `class C(private val x: Int)` is not a fourth parameter modifier — it belongs to the
 *   **property** the parameter declares, which is built separately, and passing it here is refused.
 * @param kdoc the parameter's own documentation, which KotlinPoet renders as an `@param` tag on the
 *   **enclosing** declaration — the function's KDoc, or the class's for a primary-constructor
 *   parameter. Three things worth knowing about where it comes out:
 *   - a `VAL`/`VAR` primary-constructor parameter's text renders **twice**, as `@param` on the class
 *     *and* inline above the parameter in the constructor list. `ParameterSpec.kdoc` drives both
 *     inside KotlinPoet with no way to set one without the other, and the alternative — stripping
 *     the parameter's kdoc and hand-assembling `@param` lines — reimplements KotlinPoet for a
 *     cosmetic gain. Accepted, compiled and pinned by a golden;
 *   - a **plain** primary-constructor parameter (`param(null, …)`) and every function parameter
 *     render the `@param` tag once, with no inline block;
 *   - a **secondary** constructor's parameter cannot carry this at all and says so: KotlinPoet emits
 *     a member constructor with `includeKdocTags = false`, which drops every `@param`. Write the tag
 *     into the constructor's own `kdoc` instead.
 */
public fun param(
    name: String,
    type: TypeName,
    default: Expr? = null,
    modifiers: KModifier? = null,
    kdoc: String? = null,
): ParameterSpec = buildParam(name, type, default, modifiers, kdoc) {}

/**
 * The three parameter modifiers Kotlin has, and the whole of them: `vararg` on a value parameter,
 * `noinline` and `crossinline` on a function-typed parameter of an `inline` function. Every other
 * [KModifier] renders (KotlinPoet's `addModifiers` takes any) and none of them compiles in this
 * position.
 *
 * A *primary-constructor* parameter looks like a fourth case — `class C(private val x: Int)`,
 * `class C(override val id: Long)` — but it is not: those modifiers belong to the **property** the
 * parameter declares, which this DSL builds as a separate [PropertySpec] in [addConstructorParam],
 * not to the [ParameterSpec]. Putting them here would render them on the parameter, which is not
 * where Kotlin accepts them.
 */
private val PARAMETER_MODIFIERS: Set<KModifier> =
    setOf(KModifier.VARARG, KModifier.NOINLINE, KModifier.CROSSINLINE)

/**
 * The one place a [ParameterSpec] is built from [param]'s and [constructorParam]'s slots, so the
 * three of them cannot drift.
 *
 * [modifiers] is a **single** [KModifier] rather than this DSL's usual [Modifiers] set, because no
 * two parameter modifiers combine: `vararg noinline g: () -> Unit` in an `inline` function is
 * `Modifier is only allowed for function parameters of an inline function` (measured — a vararg
 * parameter's type is `Array<out …>`, not a function type), and `noinline`/`crossinline` are
 * alternatives to each other. The slot's type therefore says what the language says. The set-valued
 * check in [checkParams] is still needed, because a caller can hand-build a [ParameterSpec] in raw
 * KotlinPoet and pass it into any of this DSL's parameter slots.
 */
private fun buildParam(
    name: String,
    type: TypeName,
    default: Expr?,
    modifiers: KModifier?,
    kdoc: String?,
    extras: ParameterSpec.Builder.() -> Unit,
): ParameterSpec = ParameterSpec.builder(name, type)
    .apply {
        modifiers?.let {
            // Ahead of `addModifiers`, which raises an `IllegalArgumentException` naming neither
            // this construct nor the parameter (Global Constraint 26 wants an IllegalStateException
            // that names both). KotlinPoet's own list and [PARAMETER_MODIFIERS] agree exactly; this
            // check exists for the exception type and the message, not for a rule KotlinPoet misses.
            check(it in PARAMETER_MODIFIERS) {
                "param: '$name' cannot be $it. Kotlin allows only VARARG, NOINLINE and CROSSINLINE " +
                    "on a parameter, and never two at once. A `private`/`override` primary-" +
                    "constructor property is not expressible here — that modifier belongs to the " +
                    "property, not to the parameter."
            }
            addModifiers(it)
        }
        default?.let { defaultValue("%L", it.code) }
        // Renders as an `@param` tag on the *enclosing* declaration's KDoc — the function's, or the
        // class's for a primary-constructor parameter. KotlinPoet puts it in the right place; all
        // this has to get right is not treating the text as a format string.
        kdoc?.let { addKdoc(docBlock(it)) }
        extras()
    }
    .build()

/**
 * Everything E2b's [param] slots make expressible that is not valid Kotlin, rejected before
 * KotlinPoet sees it (Global Constraint 26). KotlinPoet checks none of it: `addModifiers` takes any
 * [KModifier] and `defaultValue` takes any [CodeBlock].
 *
 * Called with the parameters a declaration is about to take and, for a primary constructor, the ones
 * it already has — [earlier] — because the vararg rule is the one that spans a whole parameter list
 * and a primary constructor is assembled one [constructorParam] call at a time.
 *
 * **What is deliberately not checked:** *where* the `vararg` sits. Kotlin allows a parameter after
 * one — `fun f(vararg xs: Int, y: Int)` compiles, and callers pass `y` by name — so a
 * "vararg must be last" rule would refuse valid generator code, which is the expensive direction.
 * Nor is the parameter's *type* checked against `noinline`/`crossinline`: those are legal only on a
 * function-typed parameter, but a `typealias` for a function type arrives here as a plain
 * [ClassName] and there would be no way to tell it from a mistake.
 *
 * The *set* of legal modifiers is not checked here either, and could not usefully be:
 * `ParameterSpec.Builder.addModifiers` already refuses anything outside [PARAMETER_MODIFIERS], so a
 * [ParameterSpec] carrying one cannot exist — not even hand-built in raw KotlinPoet, since that is
 * the only route to a [ParameterSpec] there is. What it refuses with is an `IllegalArgumentException`
 * naming neither this DSL's construct nor the parameter, so [buildParam] says it first (Global
 * Constraint 26) and by the time a spec reaches here the question is already settled.
 */
private fun checkParams(
    owner: String,
    inlineRemedy: String,
    isInline: Boolean,
    isOverride: Boolean,
    earlier: List<ParameterSpec>,
    params: List<ParameterSpec>,
) {
    var hasVararg = earlier.any { KModifier.VARARG in it.modifiers }
    params.forEach { p ->
        if (KModifier.VARARG in p.modifiers) {
            check(!hasVararg) {
                "param: \"${p.name}\" is the second `vararg` parameter of $owner, and Kotlin allows " +
                    "one per function (\"Multiple vararg parameters are prohibited.\"). Drop the " +
                    "modifier, or fold the two into one."
            }
            hasVararg = true
        }
        (p.modifiers - KModifier.VARARG).forEach { m ->
            check(isInline) {
                "param: \"${p.name}\" is `${m.name.lowercase()}`, which Kotlin allows only on a " +
                    "parameter of an `inline` function (\"Modifier is only allowed for function " +
                    "parameters of an inline function.\"). $inlineRemedy"
            }
        }
        check(!isOverride || p.defaultValue == null) {
            "param: \"${p.name}\" carries a default value and $owner is `override`, which Kotlin " +
                "does not allow (\"An overriding function is not allowed to specify default values " +
                "for its parameters.\"). Drop the default, or declare the value in the base function."
        }
    }
}

/**
 * A primary-constructor parameter for D23's signature form: `` `class`("User", param(VAL, "id", LONG)) ``.
 * [kind] `VAL`/`VAR` also declares the matching property, exactly as [constructorParam] does; `null`
 * makes it a plain parameter, the same thing [param]`(name, type)` produces.
 *
 * This is a **descriptor**, not an emitter: it is evaluated at the call site, where the type being
 * declared does not exist yet, so it cannot be the `context(t: TypeScope)` [constructorParam] — and
 * must not be a same-signature sibling of it either, since two declarations differing only by
 * context parameter are an ambiguity error rather than innermost-wins (ADR 0001). It is an overload
 * of [param] distinguished by presence and type (Global Constraint 21), and it returns the same
 * [ParameterSpec] every other parameter slot in this DSL takes, so one computed `List<ParameterSpec>`
 * can feed the list forms of `` `class` ``, `` `fun` `` and `` `constructor` `` alike. The `val`/`var`
 * choice rides along as a KotlinPoet tag, which is what tags are for; nothing else reads it, and
 * [buildFun] rejects a tagged parameter wherever `val`/`var` would not be valid Kotlin.
 *
 * [default], [modifiers] and [kdoc] behave exactly as they do on the two-argument [param] — see its
 * KDoc, and note in particular that a `VAL`/`VAR` parameter's [kdoc] renders **twice**, as `@param`
 * on the class and inline above the parameter, while the `null`-kind form renders it once.
 */
public fun param(
    kind: ParamKind?,
    name: String,
    type: TypeName,
    default: Expr? = null,
    modifiers: KModifier? = null,
    kdoc: String? = null,
): ParameterSpec =
    buildParam(name, type, default, modifiers, kdoc) { if (kind != null) tag(ParamKind::class, kind) }

/**
 * What [buildFun] is building. Four shapes share one implementation because they share everything
 * that is hard — parameter uniquifying, the body's [NameScope]/[ScopeId] chaining, ADR 0008's
 * ownership check — and differ only in which builder they start from and what they do with the
 * types the body's `return`s recorded.
 *
 * The two accessors are E2a's. KotlinPoet models them as ordinary [FunSpec]s named `get()`/`set()`
 * (`FunSpec.Companion.isAccessor`), so nothing new is needed to *build* one — but
 * `FunSpec.Builder.returns` carries `check(!name.isAccessor)` and throws, so an accessor must never
 * reach [inferReturnType]'s output. A getter's return type is the property's declared type, which
 * is explicit, so ADR 0007's "explicit wins" already answers it: the accessor infers nothing, and a
 * `ret(x)` inside one is a plain `return` rather than a type claim.
 */
internal enum class FunKind {
    FUNCTION,
    CONSTRUCTOR,

    /** `get() { … }` on a property: no parameters, and `ret(x)` is how a multi-statement body ends. */
    GETTER,

    /** `set(value) { … }`: exactly one parameter, and only the valueless `ret()` is legal in it. */
    SETTER,
    ;

    internal val isAccessor: Boolean get() = this == GETTER || this == SETTER
}

/**
 * The single implementation behind every function overload, including the detached builders.
 *
 * Names live in a *child* [NameScope] of the parent's, so a parameter that would shadow an
 * enclosing property or constructor parameter is renamed at declaration — and the name is released
 * again when the body ends, the same shape `lambdaOf`, `` `for` `` and `TryChain`'s catch parameters
 * use. Renaming means rebuilding the [ParameterSpec], since the signature and the body handle have
 * to agree on the name.
 *
 * The body block's [ScopeId] is a child of the parent's, so [checkOwned] accepts a handle declared
 * by any enclosing scope — a constructor parameter or property of the surrounding type, for
 * instance — and rejects one smuggled out of a sibling body (ADR 0008). With no parent — [funSpec]
 * and [propertySpec]'s case — the body is a detached root and records foreign scopes instead of
 * rejecting them. [typeSpec] always passes a non-null parent into a member `` `fun` ``'s call here,
 * so a member body is checked like any attached one; only [typeSpec]'s own non-block positions share
 * this unchecked shape, and by a different mechanism — see [typeSpec]'s KDoc.
 *
 * Return-type inference follows ADR 0007 and is done here because this is the only place that sees
 * both the explicit [returns] and the types the body recorded.
 *
 * [typeVariables] is D31's slot and is always empty for a constructor: Kotlin gives a constructor no
 * type parameters of its own — it uses the class's — so `` `constructor` ``'s generated overloads
 * carry no slot to pass one through, and the check below would reject one anyway. It is empty for
 * an accessor too, for the same reason: `val <T> T.first: T` declares `T` on the *property*, and
 * [propertyOf] is what carries it.
 *
 * [receiver] is E2a's extension-receiver slot: `fun String.shout()`. The body gets no handle for
 * `this` — see [FunKind] and the accessor KDoc on `` `val` `` for the `expression("this")` spelling.
 */
internal fun buildFun(
    name: String,
    kind: FunKind,
    annotations: Annotations?,
    modifiers: Modifiers?,
    params: List<ParameterSpec>,
    typeVariables: List<TypeVariableName>,
    returns: TypeName?,
    receiver: TypeName?,
    kdoc: String?,
    receiverKdoc: String?,
    returnsKdoc: String?,
    parent: Scope?,
    body: BlockScope.(List<Expr>) -> Unit,
): FunSpec {
    // What ADR 0008's rejection message calls this body. An accessor is passed the *property's*
    // name — `FunSpec.getterBuilder()` names itself `get()`, so the name is never rendered — which
    // is what lets the message say which property's getter a smuggled handle turned up in.
    val label = when (kind) {
        FunKind.GETTER -> "get() of '$name'"
        FunKind.SETTER -> "set() of '$name'"
        FunKind.FUNCTION, FunKind.CONSTRUCTOR -> "fun($name)"
    }
    // Before anything is built: a function's type parameters take no declaration-site variance, and
    // take `reified` only when the function is `inline`. Both render happily in KotlinPoet and
    // neither compiles. A constructor names itself `<init>` here, so it says `constructor` instead.
    checkTypeVariables(
        if (kind == FunKind.CONSTRUCTOR) "`constructor`" else "`fun`",
        name,
        typeVariables,
        varianceAllowed = false,
        reifiedAllowed = KModifier.INLINE in modifiers.toList(),
    )
    // A `@receiver` tag with no receiver would be dropped on the floor by KotlinPoet — `receiverKdoc`
    // only ever reaches the builder through `receiver(type, kdoc)`, so with no type there is no call
    // to carry it. Silently losing the caller's prose is the "partial or silently wrong output"
    // Global Constraint 26 forbids, so it says so instead. `returnsKdoc`'s twin is below, where the
    // inferred return type is known.
    check(receiverKdoc == null || receiver != null) {
        "`fun`: '$name' documents a receiver with receiverKdoc but declares none. Pass receiver = …, " +
            "or move the text into kdoc."
    }
    val names = (parent?.names ?: NameScope(null)).child()
    val id = (parent?.id ?: ScopeId(null, "root")).child(label)
    val recorded = mutableListOf<TypeName?>()
    // The slot D25's `` `this` ``/`` `super` `` write into, and the only one ever created: a
    // delegation call is part of a constructor's header, so nothing else in the DSL may accept one.
    // `BlockScope.child` does not propagate it, which is what rejects a delegation call written
    // inside a lambda or a nested block — Kotlin allows one in neither.
    val delegation = if (kind == FunKind.CONSTRUCTOR) ConstructorDelegation() else null
    val scope = BlockScope(
        builder = CodeBlock.builder(),
        names = names,
        id = id,
        returns = recorded,
        detachedRoot = parent == null,
        delegation = delegation,
    )

    // Two parameters of one function with the same name is a compile error in Kotlin with no valid
    // output to preserve, so it is rejected rather than uniquified to `x2` (D21) — the same call
    // `addConstructorParam` and `propertyOf` make. This is *not* the shadowing case below: a
    // parameter colliding with an *enclosing* binding still renames, because there the output is
    // valid and only the name has to move. Function *names* are deliberately exempt from any such
    // check: Kotlin permits overloads.
    params.map { it.name }.groupingBy { it }.eachCount().forEach { (paramName, count) ->
        check(count == 1) {
            "param: a parameter named \"$paramName\" is already declared in this function."
        }
    }

    // `val`/`var` is legal on a *primary* constructor's parameters and nowhere else, so a
    // descriptor built by `param(VAL, …)` cannot be used here — not for a function, and not for a
    // secondary constructor either. Dropping the tag silently would render a parameter the caller
    // asked to be a property as a plain one; this says so instead.
    params.forEach { p ->
        val kind = p.tag(ParamKind::class)
        check(kind == null) {
            "param: \"${p.name}\" is a `val`/`var` parameter, which is only valid in a class's " +
                "primary constructor. Declare it with `class`(…, param($kind, " +
                "\"${p.name}\", …)) or constructorParam, or drop the kind here."
        }
    }

    // A **secondary** constructor's parameter documentation reaches no output, so it is refused
    // rather than dropped — the fourth of this feature's "nowhere to put it" rejections, beside
    // `receiverKdoc` with no receiver, `returnsKdoc` on a `Unit` function, and a local binding's
    // `kdoc`. KotlinPoet 2.3.0's `TypeSpec.emit` walks its `funSpecs` twice and passes
    // `includeKdocTags = false` on the `isConstructor` pass and `true` on the other (read off the
    // bytecode: `iconst_0` against `iconst_1` at the two `FunSpec.emit` calls); with the flag false
    // `FunSpec.emit` writes `kdoc` instead of `kdocWithTags()`, and `@param` lives only in the
    // latter. The *primary* constructor is untouched — its tags are folded into the class's own
    // KDoc — and so is every `` `fun` ``, attached or detached.
    if (kind == FunKind.CONSTRUCTOR) {
        params.forEach { p ->
            check(p.kdoc.isEmpty()) {
                "param: \"${p.name}\" carries kdoc and this is a secondary constructor, where " +
                    "KotlinPoet emits the constructor's own kdoc without the `@param` tags — so " +
                    "the text would reach no output at all. Write it into the constructor's kdoc " +
                    "as an `@param ${p.name} …` line, or drop it."
            }
        }
    }

    // E2b's parameter modifiers and defaults, checked against the names the *caller* wrote — before
    // the uniquifier below can move any of them — and against the declaration's own modifiers, which
    // is what makes `noinline` and an `override` default decidable here rather than guessed at.
    // A constructor is never `inline`, so it gets the flat remedy the primary constructor gets.
    checkParams(
        owner = if (kind == FunKind.CONSTRUCTOR) "this constructor" else "'$name'",
        inlineRemedy = if (kind == FunKind.CONSTRUCTOR) {
            "A constructor is never `inline`, so drop the parameter modifier."
        } else {
            "Declare '$name' with the INLINE modifier, or drop the parameter modifier."
        },
        isInline = KModifier.INLINE in modifiers.toList(),
        isOverride = KModifier.OVERRIDE in modifiers.toList(),
        earlier = emptyList(),
        params = params,
    )

    val declared = params.map { p ->
        val unique = names.unique(p.name)
        if (unique == p.name) p else p.toBuilder(name = unique).build()
    }
    // `%N` rather than `%L`: KotlinPoet escapes a name that is a Kotlin keyword, so
    // `param("value", …)` renders as `` `value` `` in both the signature and the body.
    // A Kotlin function parameter cannot be reassigned, so the handle is a `val`, not
    // "mutability unknown" (D22) — the same call `catch` parameters make.
    val handles = declared.map { p ->
        Expr(CodeBlock.of("%N", p), p.type, Prec.ATOM, p.name, id, mutable = false)
    }
    scope.body(handles)
    scope.flushPending()

    // A constructor's body is a `Unit` body, so `return 1` in one is `e: Return type mismatch`.
    // Inference is skipped for a constructor (see `inferReturnType`), which means nothing else
    // would ever look at what the body recorded — `recorded` is non-empty only if a *value*
    // `return` ran, since the valueless `ret()` records nothing and is legal here. Reachable
    // through a spliced fragment too (`ctor { +stmts { ret(1.lit) } }`), which replays its
    // recorded types into this list at the splice.
    check(kind != FunKind.CONSTRUCTOR || recorded.isEmpty()) {
        "constructor: a constructor cannot return a value. Remove the returned expression, or " +
            "move the code to a function."
    }

    // A setter returns `Unit`, so `return value` in one is `e: Return type mismatch` — while the
    // valueless `ret()` is a legal early exit and records nothing. The getter is the asymmetric
    // half: its `ret(x)` is the whole point of an accessor body that does more than one thing, and
    // the type it recorded is deliberately dropped rather than checked against the property's
    // declared type, because that type is explicit and ADR 0007 gives explicit the win.
    check(kind != FunKind.SETTER || recorded.isEmpty()) {
        "$label: a setter cannot return a value; it returns Unit. Use the valueless ret() to " +
            "leave early, or assign to `field` — expression(\"field\") — instead."
    }

    // The function side of the `expect` family, refused here because this is the one place that sees
    // both the container and the body. Two sources of `expect`-ness and both have to be asked, or
    // the rule holds at one site only: the declaration's own EXPECT modifier — which is what makes
    // `` `fun`(EXPECT, "f") { … } `` at file level and `funSpec(EXPECT.toModifiers(), …)` answer for
    // themselves, exactly as `propertySpec` does — and [Scope.isExpectContainer], inherited to every
    // depth (D36). What is refused is the **body**, not the function: a bodiless member function, a
    // bodiless secondary constructor and a parameter default are all valid in an `expect` type and
    // all still render. See [Expect] for the three-frontend table and for what KotlinPoet does with
    // each of these (an `IllegalArgumentException` for a direct member, an `IllegalStateException`
    // thrown from `toString()` one level down).
    if (parent?.isExpectContainer == true || KModifier.EXPECT in modifiers.toList()) {
        val construct = when (kind) {
            FunKind.CONSTRUCTOR -> "`constructor`"
            FunKind.GETTER, FunKind.SETTER -> label
            FunKind.FUNCTION -> "`fun`"
        }
        // A constructor names itself `<init>`, which is not a name to quote back at the caller.
        val subject = if (kind == FunKind.CONSTRUCTOR) {
            "this secondary constructor is declared in an `expect` type and"
        } else {
            expectSubject("'$name'")
        }
        if (!scope.builder.build().isEmpty()) {
            expectRefusal(
                construct, "$subject has a body", "expected declaration cannot have a body",
                "Drop the body; the implementation belongs on the `actual` declaration.",
            )
        }
        delegation?.target?.let { target ->
            expectRefusal(
                construct, "$subject delegates with `${target.keyword}`(…)",
                "explicit delegation call for constructor of expected class is prohibited",
                "Drop the delegation call; it belongs on the `actual` declaration.",
            )
        }
    }

    val builder = when (kind) {
        FunKind.CONSTRUCTOR -> FunSpec.constructorBuilder()
        FunKind.GETTER -> FunSpec.getterBuilder()
        FunKind.SETTER -> FunSpec.setterBuilder()
        FunKind.FUNCTION -> FunSpec.builder(name)
    }
    return builder
        .apply {
            annotations?.list?.forEach { addAnnotation(it) }
            addModifiers(modifiers.toList())
            addTypeVariables(typeVariables)
            kdoc?.let { addKdoc(docBlock(it)) }
            // `receiver(type, kdoc)` and `returns(type, kdoc)` rather than an `@receiver`/`@return`
            // line written into `kdoc` by hand: KotlinPoet emits the tags in the order Kotlin
            // documents them — `@receiver`, then one `@param` per documented parameter, then
            // `@return` — and prose cannot slot itself in between the `@param`s.
            receiver?.let { r -> if (receiverKdoc == null) receiver(r) else receiver(r, docBlock(receiverKdoc)) }
            declared.forEach { addParameter(it) }
            // Never for an accessor: `FunSpec.Builder.returns` is `check(!name.isAccessor)` and
            // throws, and there is nothing to say anyway — a getter returns the property's declared
            // type and a setter returns `Unit`.
            if (!kind.isAccessor) {
                val inferred = inferReturnType(name, kind, returns, recorded)
                // The `@return` half of the receiverKdoc check above. `inferred == null` is the
                // `Unit` case — ADR 0007 omits the type entirely — and a `Unit` function returns
                // nothing to document.
                check(returnsKdoc == null || inferred != null) {
                    "`fun`: '$name' documents a return value with returnsKdoc but returns Unit. " +
                        "Pass returns = …, or move the text into kdoc."
                }
                inferred?.let { r -> if (returnsKdoc == null) returns(r) else returns(r, docBlock(returnsKdoc)) }
            }
            // Before the body: the delegation call is the constructor's header, and KotlinPoet
            // renders it there whatever order the builder is fed in. Written through
            // `callThisConstructor`/`callSuperConstructor` rather than emitted, per D25.
            when (delegation?.target) {
                null -> Unit
                DelegationTarget.THIS -> callThisConstructor(delegation.args)
                DelegationTarget.SUPER -> callSuperConstructor(delegation.args)
            }
            addCode(scope.builder.build())
        }
        .build()
}

/**
 * ADR 0007's rule, in order: an explicit [returns] wins; no recorded return means `Unit` with the
 * type omitted; all recorded types known and equal means that type; anything else is an error.
 *
 * A constructor has no return type at all, so nothing is inferred for one.
 *
 * The two failures get different messages because they need different fixes read differently: one
 * says the DSL could not know a type, the other says the author's own `return`s disagree. Both name
 * the function and both point at `returns = …`, per the ADR.
 */
private fun inferReturnType(
    name: String,
    kind: FunKind,
    returns: TypeName?,
    recorded: List<TypeName?>,
): TypeName? = when {
    kind == FunKind.CONSTRUCTOR -> null
    returns != null -> returns
    recorded.isEmpty() -> null
    else -> {
        val distinct = recorded.distinct()
        check(distinct.size == 1) {
            "Cannot infer the return type of '$name': its returns have different types " +
                "(${distinct.joinToString { it?.toString() ?: "unknown" }}). Pass returns = … explicitly."
        }
        checkNotNull(distinct.single()) {
            "Cannot infer the return type of '$name': the returned expression's type is unknown. " +
                "Pass returns = … explicitly."
        }
    }
}

/**
 * Adds a function to whichever scope is innermost: top-level in a file, a member of a type, or —
 * were it renderable — a local function in a block.
 *
 * Unlike a type name, a *function* name is never checked for duplicates: Kotlin permits overloads,
 * so two `` `fun`("show", …) `` calls in one container are legal and must not go through
 * [Scope.declaredTypeNames].
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a future fourth
 * scope breaks the build here rather than falling through silently (D17).
 */
internal fun Scope.declareFun(spec: FunSpec) {
    // The classifier-kind family's function side, asked at the one place every `` `fun` `` — attached,
    // generated or spliced through `declareFun` — passes through. KotlinPoet answers the annotation
    // row itself, with `IllegalArgumentException: annotation class N cannot declare member function f`
    // (Global Constraint 26's forbidden type, naming neither construct), and the abstract row with
    // `IllegalArgumentException: non-abstract type N cannot declare abstract function f`. See [Kinds].
    if (!membersAllowed) annotationHoldsNoMembers("`fun`", "'${spec.name}'")
    if (KModifier.ABSTRACT in spec.modifiers && !abstractMemberAllowed) {
        abstractNeedsAnAbstractContainer("`fun`", spec.name)
    }
    when (this) {
        is FileScope -> builder.addFunction(spec)
        is TypeScope -> builder.addFunction(spec)
        // A local function *is* valid Kotlin, but KotlinPoet 2.3.0 cannot render one, the same
        // wall Task 10's local classes hit. The guard sits here, at the dispatch, so the reason
        // stays attached to the branch it disables.
        is BlockScope -> localFunIsUnrenderable()
    }
}

/**
 * Rejects a local function instead of emitting Kotlin that does not compile (Global Constraint 26).
 *
 * `CodeWriter.emitLiteral` splices a `FunSpec` with `implicitModifiers = setOf(KModifier.PUBLIC)`
 * unless `omitImplicitModifiers` is set (KotlinPoet 2.3.0, `CodeWriter.kt:416-427`), and the only
 * caller that sets it is `FileSpec`'s script body (`FileSpec.kt:202`) — never a `CodeBlock` nested
 * in a function body. `CodeWriter.shouldEmitPublicModifier` then emits `public`, and Kotlin rejects
 * every visibility modifier on a local function. Declaring one explicitly does not help: `private`,
 * `internal` and `protected` suppress the implicit `public` but are themselves illegal there.
 * KotlinPoet exposes no way to reach `omitImplicitModifiers` (`FunSpec.emit` is internal), and
 * stripping the keyword afterwards would be string surgery on rendered output, which the backend
 * constraint forbids and which would break `%T` import resolution.
 *
 * Flip this to `emitCode(CodeBlock.of("%L", spec))` the moment the backend can render a local
 * function; the `local function rendering is still blocked by KotlinPoet` test in `FunctionsTest`
 * is the canary that fails when that happens.
 */
private fun localFunIsUnrenderable(): Nothing = error(
    "A local function cannot be rendered: KotlinPoet 2.3.0 emits an implicit `public` on every " +
        "function spliced into a code block, and Kotlin allows no visibility modifier on a local " +
        "function. Declare it at file or type level.",
)

/**
 * The message both halves of the primary/secondary guard raise, as D25 leaves it.
 *
 * A class with a primary constructor requires every secondary constructor to delegate to it with
 * `: this(…)`; a secondary constructor that delegates with `: super(…)` or not at all is
 * `e: Primary constructor call expected.` either way (both measured). That call is now expressible
 * — `` `this`(…) `` inside the constructor's body, over `FunSpec.Builder.callThisConstructor` — so
 * the combination is no longer rejected outright: only the undelegated form is (Global Constraint 26).
 */
internal const val SECONDARY_MUST_DELEGATE_TO_PRIMARY: String =
    "constructor: a class with a primary constructor (from `class`(…, param(…)) or " +
        "constructorParam) requires every secondary `constructor` to delegate to it with " +
        "`: this(…)`. Call `this`(…) in the secondary constructor's body — `super`(…) does not " +
        "satisfy it — or fold the parameters into one constructor."

/**
 * The one delegation cycle that is decidable without following which constructor delegates to which.
 *
 * `class A { constructor(q: Int) : this(q) }` — no primary constructor, exactly one secondary, and
 * it delegates with `` `this` `` — can only be delegating to itself, and Kotlin answers
 * `e: There's a cycle in the delegation calls chain.` (measured). Cycles through two or more
 * secondary constructors are out of reach, because `` `this`(…) `` carries arguments rather than a
 * target: which overload a call resolves to is a typing question this DSL does not answer. Checked
 * in [TypeScope.finish], since a `constructorParam` written later can still supply the primary
 * constructor that makes the call legal.
 */
internal const val LONE_SECONDARY_DELEGATING_TO_ITSELF: String =
    "constructor: this class's only constructor delegates with `this`(…), so it delegates to " +
        "itself — a cycle in the delegation calls chain. Delegate with `super`(…) instead, drop " +
        "the delegation call, or give the class the constructor this one should delegate to " +
        "(`class`(…, param(…)) or constructorParam for a primary one)."

/**
 * What every generated `` `constructor` ``/`ctor` overload runs *before* it builds the secondary
 * constructor: the one thing that can be known before the body has run, stated once instead of
 * inlined into ~120 generated bodies.
 *
 * Only a class has constructors at all. `object O { constructor(x: Int) }` and the same in an
 * `interface` are both `IllegalStateException` here rather than rendered output the Kotlin compiler
 * refuses (Global Constraint 26) — this is the one central place every `` `constructor` `` overload
 * passes through, and [TypeScope.kindName] is what makes the message name the kind as well as the
 * construct.
 *
 * The other two guards that used to live here have moved, because D25 made both of them depend on
 * facts that do not exist yet at this point:
 * - the primary/secondary pair is now legal when the secondary delegates with `` `this`(…) ``, and
 *   whether it does is only known once its body has run — so that check is in
 *   [addSecondaryConstructor], on the built [FunSpec];
 * - header superclass arguments alongside a secondary constructor are legal when the class also has
 *   a primary constructor, which a `constructorParam` written later in the body can still supply —
 *   so that check is in [TypeScope.finish], where writing order no longer matters.
 */
internal fun TypeScope.beginSecondaryConstructor() {
    check(kindName == "class") {
        // [article], not a hard-coded "a": this printed *a interface* until this round, the same
        // grammar bug the previous round fixed at `addConstructorParam` and left here.
        "constructor: ${article(kindName)} $kindName cannot declare a constructor; only a class can."
    }
    // An `annotation class` passes the check above — its `kindName` is `"class"` — and holds no
    // member of any kind, a secondary constructor included. See [Kinds].
    if (!membersAllowed) annotationHoldsNoMembers("`constructor`", "this secondary constructor")
}

/**
 * What every generated `` `constructor` ``/`ctor` overload runs *after* it has built the secondary
 * constructor: the half of the primary/secondary guard that needs to know whether the body wrote a
 * `` `this`(…) `` delegation call, plus the emission itself.
 *
 * `FunSpec.delegateConstructor` is KotlinPoet's own public record of it — `"this"`, `"super"` or
 * null — so the answer is read off the spec that was just built rather than threaded back out of
 * the block scope by hand.
 */
internal fun TypeScope.addSecondaryConstructor(spec: FunSpec) {
    val delegatesToPrimary = spec.delegateConstructor == DelegationTarget.THIS.keyword
    // The `expect` exemption is the same one [addConstructorParam] carries, for the same measured
    // reason: an `expect` class's secondary constructor must **not** delegate, so requiring it here
    // would refuse `expect class E(x: Int) { constructor(p: Long) }`, which is clean on all three
    // frontends, and there would be no other spelling — the delegating form is refused in [buildFun].
    check(!hasCtor || delegatesToPrimary || isExpectContainer) { SECONDARY_MUST_DELEGATE_TO_PRIMARY }
    if (!delegatesToPrimary) hasUndelegatedSecondaryCtor = true
    // The self-delegation cycle [TypeScope.finish] rejects is counted there, off `builder.funSpecs`
    // — not here — so a sibling constructor spliced in as a raw `FunSpec` (bypassing this function
    // entirely) still counts. See [TypeScope.finish] for why it cannot be decided at this call either.
    builder.addFunction(spec)
}

/**
 * Detached function builder; returns a KotlinPoet spec, so interop is free — and, for the same
 * reason as [typeSpec], is an unchecked boundary for ADR 0008: a [FunSpec] cannot carry the scopes
 * its body referenced, so adding it validates nothing. See ADR 0008's Task 21 amendment.
 */
public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    typeVariables: List<TypeVariableName> = emptyList(),
    returns: TypeName? = null,
    receiver: TypeName? = null,
    kdoc: String? = null,
    receiverKdoc: String? = null,
    returnsKdoc: String? = null,
    body: BlockScope.() -> Unit,
): FunSpec = buildFun(
    name, FunKind.FUNCTION, null, modifiers, emptyList(), typeVariables, returns, receiver,
    kdoc, receiverKdoc, returnsKdoc, null,
) { body() }

/** [funSpec] with one parameter; the body receives its handle. */
public fun funSpec(
    modifiers: Modifiers? = null,
    name: String,
    p1: ParameterSpec,
    typeVariables: List<TypeVariableName> = emptyList(),
    returns: TypeName? = null,
    receiver: TypeName? = null,
    kdoc: String? = null,
    receiverKdoc: String? = null,
    returnsKdoc: String? = null,
    body: BlockScope.(Expr) -> Unit,
): FunSpec = buildFun(
    name, FunKind.FUNCTION, null, modifiers, listOf(p1), typeVariables, returns, receiver,
    kdoc, receiverKdoc, returnsKdoc, null,
) { (a) -> body(a) }

/**
 * Detached property builder. The type is mandatory for the same reason it is on a `` `val` ``
 * property: KotlinPoet cannot infer one (ADR 0003).
 *
 * Like [funSpec] and [typeSpec], an unchecked boundary for ADR 0008: a [PropertySpec] cannot carry
 * the scopes [init] or [by] were built from, so adding it validates nothing. See ADR 0008's Task 21
 * amendment.
 *
 * E2a's slots track the attached `` `val` ``/`` `var` `` — same guards, the same [checkProperty] and
 * the same [addAccessors] — and E2b closed the one gap they left: [mutable], with [setterParam] and
 * [setter] beside it. Until then this builder had no `mutable` slot, so it only ever produced a
 * `val`, and `PropertySpec.build` is `require(mutable || setter == null)` — which meant a setter slot
 * here could only ever have been an error. That is an argument *from* the gap, not a reason for it:
 * nothing about a detached property is read-only, and a `var` built here is the same declaration
 * `` `var`(…) `` builds in a file or type body.
 *
 * The one thing this builder cannot check is the one thing it cannot see: **where the property will
 * be spliced.** So it runs none of the container-dependent rules the attached `` `val` ``/`` `var` ``
 * run — see [PropertyContainer.UNKNOWN] — and `propertySpec(name = "x", type = INT)` still yields
 * `public val x: Int`, which compiles only in an interface body or an `expect` type's body.
 * `modifiers = ABSTRACT` is the spelling for a declaration with no value, but it is **not** correct
 * in every container: `abstract val a: Int` compiles in an interface, an `abstract class`, a
 * `sealed class` and an `enum class`, and nowhere else — at file level it is *modifier 'abstract' is
 * not applicable to 'top level property without backing field or delegate'*, and in a plain class,
 * an object or a companion object it is *abstract property 'a' in non-abstract class 'C'* (all
 * measured, kotlinc 2.4.10). Splicing this builder's output into one of those is the caller's own
 * compile to answer for.
 */
public fun propertySpec(
    modifiers: Modifiers? = null,
    name: String,
    type: TypeName,
    init: Expr? = null,
    by: Expr? = null,
    typeVariables: List<TypeVariableName> = emptyList(),
    receiver: TypeName? = null,
    mutable: Boolean = false,
    setterParam: String = "value",
    setter: (BlockScope.(Expr) -> Unit)? = null,
    kdoc: String? = null,
    getter: (BlockScope.() -> Unit)? = null,
): PropertySpec {
    check(init == null || by == null) {
        "propertySpec: '$name' cannot have both an initializer and a delegate."
    }
    // "propertySpec", not `` `val` ``: Global Constraint 26 wants the construct the caller actually
    // wrote, and the caller wrote this one — the init/by check just above says so too.
    // [PropertyContainer.UNKNOWN]: this builder returns a bare [PropertySpec] and has no idea where
    // it will be spliced. An interface body is a legitimate destination, and there a property with
    // no initializer, delegate or getter — or an ABSTRACT one — is exactly right, so every
    // container-dependent check the attached `` `val` ``/`` `var` `` runs would refuse valid output
    // here. See this function's KDoc for what `modifiers = ABSTRACT` does and does not buy.
    checkProperty(
        "propertySpec", name, mutable, init, by, typeVariables, receiver, setter, getter,
        modifiers, PropertyContainer.UNKNOWN,
    )
    val spec = PropertySpec.builder(name, type, modifiers.toList())
    spec.mutable(mutable)
    spec.addTypeVariables(typeVariables)
    kdoc?.let { spec.addKdoc(docBlock(it)) }
    receiver?.let { spec.receiver(it) }
    init?.let { spec.initializer("%L", it.code) }
    by?.let { spec.delegate("%L", it.code) }
    spec.addAccessors(parent = null, name = name, type = type, setterParam = setterParam, setter = setter, getter = getter)
    return spec.build()
}
