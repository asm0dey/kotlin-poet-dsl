package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName

// `val`, `var` and the `property` alias are generated into `DeclarationVariants.kt` by
// `buildSrc/src/main/kotlin/ArityGenerator.kt`, in ADR 0004's six variants — which is what makes
// an annotated property expressible at all: every hand-written entry point passed `null` into
// [bind]'s `annotations` slot. The compound assignments below are hand-written, because they have
// no variants: a local carries neither annotations nor modifiers.

/**
 * The handle a binding hands back: the (possibly uniquified) name it was actually declared
 * under, tagged with the scope that declared it so ADR 0008 can judge it at every later use.
 *
 * [owner] defaults to the declaring scope and is overridden for exactly one binding — an extension
 * property, see [extensionPropertyId].
 */
private fun Scope.handle(unique: String, type: TypeName?, mutable: Boolean? = null, owner: ScopeId = id): Expr =
    Expr(CodeBlock.of("%L", unique), type, Prec.ATOM, unique, owner, mutable = mutable)

/**
 * The owning [ScopeId] of an **extension property's** handle: a child of the declaring scope that
 * nothing is ever nested inside, so [checkOwned] — untouched — refuses the handle in every position
 * [checkOwned] judges. That is not every position: a property initializer, a delegate and
 * `superclass(…, args)` never run it at all (see the note at `constructorParam`), so
 * `` `val`("x", INT, init = size) `` still renders `public val x: Int = size`, which does not
 * compile. That boundary predates this owner and is unchanged by it.
 *
 * The handle renders as a bare name, and a bare name is not how an extension property is reached:
 * `` `val`("size", INT, receiver = STRING) { … } `` followed by `` `fun`("f") { +size } `` rendered
 * `size` inside `f`, which is `Unresolved reference 'size'`. That is the same shape D30 met with a
 * plain primary-constructor parameter — a handle legal in some positions and unspellable in others —
 * and it gets D30's answer: an owner scope that encloses only the legal positions, with the remedy
 * folded into the label, since [checkOwned] interpolates `owner.label` and takes no message of its
 * own.
 *
 * Here the *reachable* legal set is empty. Kotlin resolves the bare name wherever a receiver of the
 * matching type is in scope — inside another extension declaration on that receiver (measured:
 * `fun String.f(): Int = size` compiles), and equally on any *implicit* receiver, which this DSL can
 * express: `s.call("apply") { … }`, `call(member("kotlin", "with"), s) { … }`, or a member extension
 * inside a `class`. **Every one of those rendered working Kotlin from the handle at base and throws
 * now.** That is the cost of the ruling, and it is wider than "one shape": the refusal is total.
 *
 * It is deliberate anyway, because none of those positions is something ownership can recognise — an
 * extension function's body is built as a child of the file or type scope, not of any property, and
 * an implicit receiver is not a scope at all. Deciding them would mean comparing receiver *types*, a
 * second mechanism ADR 0008 does not have. So the label names the spellings that work instead:
 * `h.prop("size")` through a receiver handle, and `expression("size")` wherever a receiver of the
 * right type is in scope. Nothing becomes unreachable; every refused position keeps a working
 * spelling, and the refusal is loud rather than an unresolved reference in generated output.
 */
private fun Scope.ownerOf(name: String, receiver: TypeName?): ScopeId =
    if (receiver == null) id else extensionPropertyId(name, receiver)

private fun Scope.extensionPropertyId(name: String, receiver: TypeName): ScopeId = id.child(
    "the extension property `$name` on $receiver (reach it through a receiver handle — " +
        "h.prop(\"$name\") — or, wherever a $receiver receiver is in scope, as expression(\"$name\"))",
)

/**
 * `val name: T = init` / `var name by delegate` as a local statement.
 *
 * Both `init` and `by` are validated against this block first: a handle from a scope that does
 * not enclose this one is rejected before anything is emitted (ADR 0008).
 *
 * A local is the only binding Kotlin can infer a type for, so all four combinations of
 * type/initializer are renderable — including the bare `var t: Int` that a later `assign`
 * completes. The one hole is a binding with neither a type nor a value: nothing could be
 * inferred from it, and emitting `val x = null` instead would be silently wrong output.
 */
private fun BlockScope.bindLocal(
    mutable: Boolean,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
): Expr {
    check(type != null || init != null || by != null) {
        "Binding '$name' needs a type, an initializer or a delegate."
    }
    init?.let { checkOwned(it) }
    by?.let { checkOwned(it) }
    val unique = names.unique(name)
    val code = CodeBlock.builder()
        .add("%L·%L", if (mutable) "var" else "val", unique)
        .apply { if (type != null) add(":·%T", type) }
        .apply {
            if (init != null) add("·=·%L", init.code)
            if (by != null) add("·by·%L", by.code)
        }
        .build()
    emitCode(code)
    return handle(unique, type ?: init?.type, mutable)
}

/**
 * The `PropertySpec` for a file- or type-level binding.
 *
 * KotlinPoet cannot infer, so the type is mandatory here — the single rule that makes a property
 * more than a local with a different parent (ADR 0003).
 *
 * A duplicate name *is* an error (ADR 0009, amended by D21): two properties named `username` in
 * one container is a compile error in Kotlin, and there is no valid output for renaming to
 * preserve, so the second `username` is rejected rather than invented as `username2`.
 * [Scope.declaredPropertyNames] catches that before the [NameScope] uniquifier ever runs. A
 * property colliding with a *different* construct — most notably a constructor parameter — is
 * untouched by this check and still goes through the uniquifier, exactly as ADR 0009 originally
 * prescribed.
 */
private fun Scope.propertyOf(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
    typeVariables: List<TypeVariableName>,
    receiver: TypeName?,
    setterParam: String,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
    kdoc: String?,
): PropertySpec {
    checkNotNull(type) {
        "Property '$name' requires an explicit type; KotlinPoet cannot infer it."
    }
    val construct = if (mutable) "`var`" else "`val`"
    checkProperty(
        construct, name, mutable, init, by, typeVariables, receiver, setter, getter,
        modifiers, propertyContainer(),
    )
    // An extension property is keyed by receiver *and* name: `val String.size` and `val Int.size`
    // are two different declarations and both are legal in one file, while two of the same name on
    // the same receiver is the compile error D21 rejects. A plain property's key is its bare name,
    // exactly as before.
    val key = receiver?.let { "$it." }.orEmpty() + name
    check(key !in declaredPropertyNames) {
        "A property named \"$key\" is already declared in this scope."
    }
    declaredPropertyNames += key
    // `uniqueMemberName`, not `names.unique`: a property is visible in every member body, so it is
    // declared at the member level — but its own initializer is evaluated where a plain primary
    // constructor parameter is also in scope, so it still has to step over one (D30).
    //
    // An *extension* property is exempt, and has to be: its name is only ever reached through a
    // receiver, so it shadows nothing and nothing shadows it. Uniquifying it renamed `val Int.size`
    // to `size2` merely because `val String.size` had been declared above it (measured) — two
    // declarations Kotlin keeps apart by receiver, and a rename here would invent a public API name
    // for a collision that does not exist.
    val declaredName = if (receiver == null) uniqueMemberName(name) else name
    return PropertySpec.builder(declaredName, type, modifiers.toList())
        .mutable(mutable)
        .apply {
            addTypeVariables(typeVariables)
            receiver?.let { receiver(it) }
            init?.let { initializer("%L", it.code) }
            by?.let { delegate("%L", it.code) }
            annotations?.list?.forEach { addAnnotation(it) }
            kdoc?.let { addKdoc(docBlock(it)) }
            addAccessors(this@propertyOf, name, type, setterParam, setter, getter)
        }
        .build()
}

/**
 * Builds whichever accessor bodies were passed, against [parent] as the enclosing scope.
 *
 * Both go through [buildFun], so an accessor body is a nested block exactly like a member
 * function's: its names chain from the member level and its [ScopeId] is a child of it, which is
 * what makes ADR 0008 judge a handle used inside one — in either direction, and reached through a
 * *property* rather than a function — with no change to [checkOwned]. A null [parent] is
 * [propertySpec]'s detached case, and [buildFun] gives it the same detached-root treatment it gives
 * [funSpec].
 *
 * The accessor is passed the *property's* name rather than `get()`/`set()`:
 * `FunSpec.getterBuilder()` names itself, and the name is what lets ADR 0008's rejection message say
 * which property's accessor a smuggled handle turned up in.
 */
internal fun PropertySpec.Builder.addAccessors(
    parent: Scope?,
    name: String,
    type: TypeName,
    setterParam: String,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
) {
    getter?.let { body ->
        getter(
            buildFun(
                name, FunKind.GETTER, null, null, emptyList(), emptyList(), null, null,
                null, null, null, parent,
            ) { body() },
        )
    }
    setter?.let { body ->
        setter(
            buildFun(
                name, FunKind.SETTER, null, null, listOf(param(setterParam, type)), emptyList(), null, null,
                null, null, null, parent,
            ) { (value) -> body(value) },
        )
    }
}

/**
 * The facts about a property's **container** that the property itself cannot carry, read off the
 * scope in one place so that [checkProperty] never has to infer a container from what the property
 * holds. Every row below is one kotlinc 2.4.10 run; `expect` needs `-Xmulti-platform`.
 *
 * **Four fields, because these are four questions**, and each is asked at the site that needs it
 * rather than folded into another. Two rounds of this work have already produced the same defect —
 * a container fact consulted at one site and missing at another — and its shape here was that
 * "must a property in this container be given a value?" ([needsValue]) and "which modifiers are
 * legal in this container?" ([abstractAllowed], [expectAllowed], [externalAllowed]) were being
 * asked in one place: inside the `if (needsValue …)` branch that only the first belongs to. An
 * interface body and every `expect` body have `needsValue == false`, so they validated no modifier
 * at all.
 *
 * | container | no value | `abstract` | `expect` on the property | `external` on the property |
 * |---|---|---|---|---|
 * | file | *Property must be initialized.* | *modifier 'abstract' is not applicable to 'top level property without backing field or delegate'* | OK | JVM: *modifier 'external' is not applicable to 'property'*; **JS and Wasm: OK** |
 * | `class` | *…or be abstract.* | *abstract property 'a' in non-abstract class 'C'* | *modifier 'expect' is not applicable to 'member property without backing field or delegate'* | JVM: as above; JS and Wasm: *non-top-level 'external' declaration* |
 * | `abstract`/`sealed`/`enum class` | *…or be abstract.* | OK | as above | as above |
 * | `interface` | OK | OK (rendered without the keyword — it is implicit) | as above | as above |
 * | `object`, `companion object` | *…or be abstract.* | *abstract property 'a' in non-abstract class 'O'* | as above | as above |
 * | `expect class` body | OK | *abstract property 'a' in non-abstract class 'E'* | **OK** — KotlinPoet drops the keyword, and the render is valid | *expected declaration cannot be external*, plus the row above |
 * | body nested in an `expect class` | OK | as its own kind | as the `class` row — the keyword is **not** dropped here | as above |
 * | `external class` body | *Property must be initialized.* (D37's open row) | as its own kind | as above | **OK** — KotlinPoet drops the keyword, and JS and Wasm accept the render |
 *
 * The `external` column is the one that needs three frontends rather than one, and it is why
 * [externalAllowed] exists as a container fact at all: the file row has a target where the output
 * compiles and the ordinary member rows have none. `kotlinc-js`/`kotlinc-wasm` run with the matching
 * `kotlin-stdlib-js.klib` / `kotlin-stdlib-wasm-js.klib` from the same distribution. See D37.
 *
 * [abstractAllowed] is also what keeps KotlinPoet's own
 * `IllegalArgumentException: non-abstract type C cannot declare abstract property x` — thrown from
 * `TypeSpec.build` whenever `ABSTRACT` is on a property of a type that is not an interface and
 * carries none of `ABSTRACT`/`SEALED`/`ENUM`, and reading `non-abstract type **null**` for an
 * anonymous companion object — out of this DSL's output. Global Constraint 26 forbids that exception
 * type, and its message names neither construct.
 *
 * @property needsValue whether a property here *must* be given an initializer, a delegate or a
 *   getter. False in an interface body and in every `expect` body, at any nesting depth.
 * @property backingFieldAllowed whether a property here may have storage — an initializer, a
 *   delegate or `LATEINIT`. False in an interface body only, where Kotlin answers with three
 *   different sentences for one reason: *property initializers in interfaces are prohibited*,
 *   *delegated properties in interfaces are prohibited*, and *'lateinit' modifier is not allowed on
 *   abstract properties*. An interface's *companion object* allows all three.
 * @property isExpectContext whether a property here **is** `expect` by virtue of the container, so
 *   that it is a signature and may carry no value of any kind. The same [TypeScope.isExpect] that
 *   [needsValue] reads, asked the opposite way round: one says a value is not required, this one
 *   says a value is not permitted, and the two are separate fields because they are separate
 *   questions with separate call sites. Inherited to every nesting depth, exactly as `needsValue`'s
 *   `expect` term is.
 * @property abstractAllowed whether `ABSTRACT` is legal on a property here.
 * @property expectAllowed whether `EXPECT` is legal on a property here — read off the **immediate**
 *   builder's own modifiers rather than [TypeScope.isExpect], and the difference is load-bearing in
 *   both directions. KotlinPoet hands a `TypeSpec`'s own `EXPECT` down to its direct members as an
 *   *implicit* modifier, so inside `expect class E` the keyword is never printed and
 *   `expect class E { val a: Int }` is what renders; one level down —
 *   `expect class E { class N { expect val a: Int } }` — it *is* printed, and all three frontends
 *   answer *modifier 'expect' is not applicable to 'member property without backing field or
 *   delegate'*.
 * @property backingFieldDenial which kind, if any, forbids a property here from having *storage* —
 *   the reason [backingFieldAllowed] is false, carried with the answer because the three sentences
 *   the frontends print are the kind's and not this DSL's. `null` means storage is fine. One
 *   question, one field: an interface and a value class both forbid the backing field, and folding
 *   them into two booleans would be the second reader D38 warns about, not the second question.
 * @property funInterface whether this container is a `fun interface`, which holds no **abstract**
 *   property — a different question from [backingFieldAllowed], which it also answers `false` to as
 *   any interface does, and from [abstractAllowed], which is about the modifier rather than about
 *   the property being abstract by having no accessor.
 * @property membersAllowed whether a property may be declared here **at all** — false in an
 *   `annotation class`, which declares a shape and holds nothing but its primary-constructor
 *   parameters. Its own field rather than a reading of [backingFieldAllowed], because it is a
 *   different question with a different answer: an interface forbids the *storage* and takes a
 *   getter, an annotation class forbids the *declaration* and takes nothing. See [Kinds].
 * @property internalAllowed whether `INTERNAL` is legal on a property here — false in an interface
 *   body, whose members are the contract it publishes. See [Scope.internalAllowed].
 * @property finalAllowed whether `FINAL` is legal on a property here — false in an interface body,
 *   whose members are open. Its own field beside [memberInheritanceAllowed], which asks the other
 *   question about the same keyword: *is there a hierarchy at all?* is the file's answer, *may a
 *   member of this hierarchy be final?* is the interface's. See [Scope.finalAllowed].
 * @property isInterfaceBody whether this container is an interface body, which no other field
 *   answers: the two above are per-modifier and this one is what a **shape** rule needs — a
 *   `private` property with no accessor is abstract, and an abstract member cannot be private.
 * @property externalAllowed whether `EXTERNAL` is legal on a property here, on the same
 *   immediate-builder rule and for the same measured reason: `external class C { val a: Int }` — the
 *   render KotlinPoet produces once it drops the member's keyword — compiles clean on Kotlin/JS and
 *   Kotlin/Wasm, while `external class C { class N { external val a: Int } }`, where the keyword
 *   survives, is *non-top-level 'external' declaration* on both.
 */
internal class PropertyContainer(
    val needsValue: Boolean,
    val backingFieldDenial: BackingFieldDenial?,
    val isExpectContext: Boolean,
    val abstractAllowed: Boolean,
    val expectAllowed: Boolean,
    val externalAllowed: Boolean,
    val membersAllowed: Boolean,
    val funInterface: Boolean,
    val protectedAllowed: Boolean,
    val memberInheritanceAllowed: Boolean,
    val constAllowed: Boolean,
    val internalAllowed: Boolean,
    val finalAllowed: Boolean,
    val isInterfaceBody: Boolean,
) {
    /** See [backingFieldDenial]. */
    val backingFieldAllowed: Boolean get() = backingFieldDenial == null

    internal companion object {
        /**
         * [propertySpec]'s detached builder, which has no container and cannot be given one: it
         * returns a bare `PropertySpec` and an interface body is a legitimate destination. Every
         * container-dependent rule is therefore off here, which is the same answer
         * `containerNeedsValue = false` gave before this class existed.
         *
         * The rules that are **not** container-dependent still run, and one of them reads a field on
         * this object: [isExpectContext] is false, so `expect`-ness can only come from the
         * property's own `EXPECT` modifier — and a property carrying it is a signature wherever it
         * is spliced, so `propertySpec(EXPECT.toModifiers(), …, init = 1.lit)` is refused here as it
         * is at file level. Nothing about *where* it lands changes that answer, which is exactly why
         * it is safe to give here.
         */
        val UNKNOWN: PropertyContainer = PropertyContainer(
            needsValue = false,
            isExpectContext = false,
            abstractAllowed = true,
            expectAllowed = true,
            externalAllowed = true,
            membersAllowed = true,
            backingFieldDenial = null,
            funInterface = false,
            protectedAllowed = true,
            memberInheritanceAllowed = true,
            constAllowed = true,
            internalAllowed = true,
            finalAllowed = true,
            isInterfaceBody = false,
        )
    }
}

/** See [PropertyContainer]. A [BlockScope] never reaches here — [bindLocal] takes that branch. */
private fun Scope.propertyContainer(): PropertyContainer {
    if (this !is TypeScope) {
        return PropertyContainer(
            needsValue = true,
            backingFieldDenial = null,
            funInterface = false,
            // [Scope.isExpectContainer], which is false here: a *file* is not an `expect` container.
            // Read through the shared predicate rather than written as `false` so that both branches
            // of this function, and every other site in the family, read one fact from one place.
            isExpectContext = isExpectContainer,
            abstractAllowed = false,
            expectAllowed = true,
            // The file level is the only place an `external` property renders the keyword and still
            // has a target that accepts it, and this branch *is* the file level. See D37.
            externalAllowed = true,
            // [Scope.membersAllowed], which is true here: a *file* is not an annotation class. Read
            // through the shared predicate for the same reason `isExpectContext` is.
            membersAllowed = membersAllowed,
            // [Scope.protectedAllowed], false here — a file has no subclass for the visibility to
            // mean anything to. Read through the shared predicate, which `declareFun` and
            // `declareType` also read, so the modifier axis has one reader as the `expect` family
            // has one in `isExpectContainer`.
            protectedAllowed = protectedAllowed,
            // `final`, `open` and `override` are a member's modifiers, and this branch is not a
            // member's container. See [MEMBER_INHERITANCE_MODIFIERS].
            memberInheritanceAllowed = false,
            // …and the one modifier whose container rule points the other way: a `const val` belongs
            // to the file or to an object, and the file is where it is most at home.
            constAllowed = true,
            // [Scope.internalAllowed] and [Scope.finalAllowed], both true here — a *file* is not an
            // interface — read through the shared predicates for the same reason the four above are.
            internalAllowed = internalAllowed,
            finalAllowed = finalAllowed,
            isInterfaceBody = false,
        )
    }
    val typeModifiers = builder.modifiers
    return PropertyContainer(
        // [Scope.isExpectContainer] — [TypeScope.isExpect], not `EXPECT in typeModifiers`: the modifier sits on the *outermost*
        // `expect class` only, and every classifier nested inside one inherits the rule.
        // …and [Scope.isExternalContainer], the third term, added by E2c's Part 3 harness the first
        // time a test could ask Kotlin/JS anything. `external class C { val a: Int }` is clean on
        // Kotlin/JS and Kotlin/Wasm — at every depth, and in a nested class, an object, a companion
        // object, an `external object` and an `external interface` alike (measured, one file per
        // row) — and this DSL refused every one of them. That false rejection is D36's
        // container-inheritance shape, which the ledger has carried as "unsolved for `external`"
        // since the E2b fix round; what solves it is [TypeScope.isExternal], which this round added
        // for the empty-body rule.
        needsValue = !(kindName == "interface" || isExpectContainer || isExternalContainer),
        // The same `kindName == "interface"` the line above reads, asked the other way round — and
        // the third container fact in this class that used to be consulted at one site only. An
        // interface holds no state, so a property there both *may* have no value and *may not* have
        // one that needs storage. Nothing inherited: an interface's companion object is a container
        // of its own and takes all three (measured, all three frontends) — and so is a value
        // class's, which is why the second denial reads the immediate builder too.
        backingFieldDenial = when {
            kindName == "interface" -> BackingFieldDenial.INTERFACE
            KModifier.VALUE in typeModifiers -> BackingFieldDenial.VALUE_CLASS
            else -> null
        },
        // [KModifier.FUN] on an interface builder is what makes it a `fun interface`; KotlinPoet
        // records the same fact as `isFunInterface` and keeps it internal.
        funInterface = kindName == "interface" && KModifier.FUN in typeModifiers,
        // The same [TypeScope.isExpect] the line above reads, asked the other way round: an `expect`
        // property needs no value *and* may have none. Two questions, two fields, two call sites —
        // and read through [Scope.isExpectContainer], the one predicate every member of the `expect`
        // family asks (see [Expect]), so that a property is not answering it privately.
        isExpectContext = isExpectContainer,
        // [Scope.abstractMemberAllowed], not this list written out again: the same question is
        // asked of a *function* in `declareFun`, and the two had drifted to the point where the
        // property side refused what the function side handed to KotlinPoet's own
        // `IllegalArgumentException`. See [Kinds] for the measured rows.
        abstractAllowed = abstractMemberAllowed,
        // Both of these read the **immediate** builder's own modifiers, and not [TypeScope.isExpect]
        // or anything else inherited, because that is exactly what decides whether the keyword
        // reaches the output: KotlinPoet hands a `TypeSpec`'s own `EXPECT`/`EXTERNAL` down to its
        // direct members as an *implicit* modifier, so the member's copy is not printed — and the
        // render that results is valid (`expect class E { val a: Int }` everywhere,
        // `external class C { val a: Int }` on JS and Wasm). One level down the keyword is printed
        // again and every frontend refuses it. Guarding what is *rendered* is the whole job;
        // guarding the modifier list as passed would refuse output two frontends accept, which is
        // the direction D37's standing rule exists to prevent.
        expectAllowed = KModifier.EXPECT in typeModifiers,
        externalAllowed = KModifier.EXTERNAL in typeModifiers,
        // [Scope.membersAllowed] — the same predicate `declareFun`, `beginSecondaryConstructor`,
        // `addInitializerBlock` and the two supertype constructs read, so the kind family has one
        // reader here as the `expect` family has one in `isExpectContainer`.
        membersAllowed = membersAllowed,
        // [Scope.protectedAllowed] — a class body and nothing else. An interface and an object both
        // answer false here and both refuse a `protected` member on all three frontends.
        protectedAllowed = protectedAllowed,
        // Any type body will do for `final`/`open`/`override`: `object O { final val x: Int = 1 }`
        // is clean, so this is keyed on the *file* and not on "a class", which is the reading
        // [protectedAllowed] takes and this one deliberately does not. Two questions, two fields.
        memberInheritanceAllowed = true,
        // A `const val` lives at file level, in a named object or in a companion object — never on
        // an instance. `kindName` separates all three of this DSL's type builders.
        constAllowed = kindName == "named object" || kindName == "companion object",
        // The interface body's two, read through the same predicates `declareType` and `buildFun`
        // read — one fact, one reader, as the `expect` family has in `isExpectContainer`.
        internalAllowed = internalAllowed,
        finalAllowed = finalAllowed,
        // …and the container itself, for the shape rule that needs more than a modifier list. The
        // fourth reading of `kindName == "interface"` in this function, and the fourth question:
        // may a member have no value, may it have storage, is this a `fun interface`, and is a
        // private member of it abstract.
        isInterfaceBody = kindName == "interface",
    )
}

/**
 * Everything E2a's slots make expressible that is not valid Kotlin, rejected before KotlinPoet sees
 * it (Global Constraint 26). KotlinPoet catches exactly one of these itself — `PropertySpec`'s
 * `require(mutable || setter == null)` — and as an `IllegalArgumentException` whose message names
 * neither the property nor the construct.
 *
 * The `field` keyword deliberately gets no construct of its own: `expression("field")` already
 * renders it, and a `field` construct would be valid only inside an accessor body, so it would need
 * a `BlockScope` shadow it could not have — the accessor body *is* a `BlockScope` — reopening ADR
 * 0002 for two words.
 *
 * One rejection here is deliberately **narrower than the language rule it comes from**: a `var` with
 * exactly one custom accessor and no initializer or delegate. Kotlin's actual rule is that any
 * property whose accessors reach the backing field needs an initializer, and that is not decidable
 * from this signature — a custom setter writing `field` reaches it too, and `field` arrives as an
 * opaque `expression("field")`. The pair guarded below is the half that *is* decidable; the rest is
 * left to the caller's own compile rather than guessed at, because guessing wrong there refuses
 * valid generator code. See the comment at the check.
 *
 * @param construct the DSL spelling to name in the message — `` `val` ``, `` `var` ``, or
 *   `propertySpec` for the detached builder — matching [checkTypeVariables]'s parameter of the same
 *   name, which this hands it straight through to.
 * @param container what the *scope* knows and the property does not; see [PropertyContainer]. Two of
 *   the rules below read it, and the remedy sentence of the third is built from it, so that no
 *   message ever recommends a modifier that does not compile where it is offered.
 */
internal fun checkProperty(
    construct: String,
    name: String,
    mutable: Boolean,
    init: Expr?,
    by: Expr?,
    typeVariables: List<TypeVariableName>,
    receiver: TypeName?,
    setter: (BlockScope.(Expr) -> Unit)?,
    getter: (BlockScope.() -> Unit)?,
    modifiers: Modifiers?,
    container: PropertyContainer,
) {
    val declared = modifiers.toList()
    // The modifier family, before anything that reads the container: nineteen of the thirty-two
    // `KModifier` values are not applicable to a property in any container, and every one of them
    // reached **KotlinPoet's own `IllegalArgumentException`** — *unexpected modifier SUSPEND for
    // PROPERTY* — which Global Constraint 26 forbids and which names neither the construct nor the
    // property. 152 cells of D42's matrix, all from this one row. See [Applicability].
    checkModifiers(construct, DeclarationForm.PROPERTY, "'$name'", declared)
    // `const` is a `val`'s modifier and this is the only place that knows which one this is; its
    // *container* rule is the next question down, and they are separate because a `const var` is
    // wrong in every container and a `const val` is wrong in only some.
    if (KModifier.CONST in declared) {
        if (mutable) constNeedsAVal(construct, name)
        if (!container.constAllowed) constNeedsATopLevelOrObjectContainer(construct, name)
    }
    // The container half of the modifier axis on the property side, reading the same two predicates
    // `declareType` and `buildFun` read. The noun is the frontends' own and depends on the
    // container, which is why it is built here rather than inside the refusal.
    if (KModifier.PROTECTED in declared && !container.protectedAllowed) {
        protectedNotAllowedHere(construct, name)
    }
    // …and the interface body's own two, which reach a property exactly as they reach a function and
    // a nested classifier. See [Scope.internalAllowed] and [Scope.finalAllowed].
    if (KModifier.INTERNAL in declared && !container.internalAllowed) {
        interfaceBodyRefusal(construct, "'$name'", KModifier.INTERNAL)
    }
    if (KModifier.FINAL in declared && !container.finalAllowed) {
        interfaceBodyRefusal(construct, "'$name'", KModifier.FINAL)
    }
    // A `private` interface property with no accessor is **abstract**, and an abstract member cannot
    // be private — the shape half of what `private` costs in an interface body, and the reason
    // [PropertyContainer.isInterfaceBody] exists beside the two per-modifier fields. An initializer
    // or a delegate cannot get this far in an interface: both are refused below by the backing-field
    // rules, whose sentences are more specific and belong to them.
    if (container.isInterfaceBody &&
        KModifier.PRIVATE in declared &&
        getter == null && init == null && by == null
    ) {
        privateInterfacePropertyNeedsAnAccessor(construct, name)
    }
    if (!container.memberInheritanceAllowed) {
        declared.firstOrNull { it in MEMBER_INHERITANCE_MODIFIERS }?.let {
            memberModifierNeedsAType(
                construct, "'$name'", it,
                if (init != null || by != null) {
                    "top level property with backing field"
                } else {
                    "top level property without backing field or delegate"
                },
            )
        }
    }
    // `lateinit` promises the value arrives later, so a property that already has one is a
    // contradiction — decidable from the two things this function is handed, in every container.
    check(KModifier.LATEINIT !in declared || (init == null && by == null)) {
        "$construct: '$name' is LATEINIT and is given " +
            (if (init != null) "an initializer" else "a delegate") +
            ", so this is \"'lateinit' modifier is not allowed on properties with " +
            (if (init != null) "initializer" else "delegate") +
            "\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. Drop LATEINIT, or drop the " +
            (if (init != null) "initializer" else "delegate") + "."
    }
    // An `annotation class` holds no member of any kind, so its `val`, its `var` and its
    // `val … get()` are one refusal and not three. All three **rendered** — *members are prohibited
    // in annotation classes* on all three frontends. See [Kinds].
    //
    // This used to say *"Before every other question, because there is no property here to ask them
    // about"*, and it was the first check in this function when it did. It is not any more — the
    // modifier family and its container half were added above it — and the ordering is right either
    // way, because the frontends report **both** sentences. Measured, kotlinc 2.4.10:
    //
    //     annotation class A { internal val p: Int = 1 }  modifier 'internal' is not applicable
    //                                                     inside 'annotation class'.
    //                                                     members are prohibited in annotation
    //                                                     classes.
    //     annotation class A { suspend val p: Int = 1 }   modifier 'suspend' is not applicable to
    //                                                     'member property with backing field'.
    //                                                     members are prohibited in annotation
    //                                                     classes.
    //
    // So whichever of the two fires first quotes a sentence the caller's own compiler would print.
    // The behaviour is unchanged; only the claim of primacy was false.
    if (!container.membersAllowed) annotationHoldsNoMembers(construct, "'$name'")
    check(mutable || KModifier.LATEINIT !in declared) {
        "$construct: '$name' is a `val` and cannot be LATEINIT; Kotlin allows the modifier only on " +
            "a mutable property (\"'lateinit' modifier is allowed only on mutable properties.\"). " +
            "Declare it with `var`, or drop LATEINIT."
    }
    // Not folded into the missing-value check below, because KotlinPoet's `require` is not either:
    // it reads the modifier alone, so `abstract val x: Int = 1` in a non-abstract class raises the
    // same `IllegalArgumentException` an uninitialized one does. See [PropertyContainer].
    check(container.abstractAllowed || KModifier.ABSTRACT !in declared) {
        "$construct: '$name' is ABSTRACT, which Kotlin allows only in an interface or in an " +
            "ABSTRACT, SEALED or ENUM class — not at file level, not in an object or a companion " +
            "object, and not in a class that is none of those. Declare the container ABSTRACT, or " +
            "give '$name' a value."
    }
    // The other two container-and-modifier questions, asked here — **unconditionally**, beside the
    // one above — rather than inside the missing-value branch below, which is where they used to be
    // asked and which an interface body and every `expect` body never enter. That is what let
    // `` `interface`("I") { `val`(EXTERNAL, "x", INT) } `` render `public external val x: Int`, and
    // `` `class`("C") { `val`(EXTERNAL, "x", INT, init = 1.lit) } `` render it with an initializer:
    // one branch answering two questions, only one of which is about values.
    //
    // Neither modifier is refused by its name. Both are refused by whether **the render carries the
    // keyword**, which is what a frontend sees: KotlinPoet suppresses a member's `EXPECT`/`EXTERNAL`
    // when the enclosing `TypeSpec` already carries it, so `` `class`(EXPECT, "E") ``'s and
    // `` `class`(EXTERNAL, "C") ``'s direct members print no keyword and their renders are accepted
    // (`expect class E { val a: Int }` on all three frontends; `external class C { val a: Int }` on
    // JS and Wasm, D37 row 6). One level down, and in every other container, the keyword survives:
    //
    //     interface I    { external val a: Int }  jvm  not applicable to 'property'
    //                                             js   non-top-level 'external' declaration.
    //                                             wasm non-top-level 'external' declaration.
    //     expect class E { external val a: Int }  all three, plus `expected declaration cannot be
    //                                             external.`
    //     interface I    { expect   val a: Int }  all three: modifier 'expect' is not applicable to
    //     class C        { expect   val a: Int = 1 }        'member property without backing field
    //     expect class E { class N { expect val a: Int } }   or delegate'.
    //
    // — measured one file per row, kotlinc 2.4.10 with `kotlin-stdlib-js.klib` /
    // `kotlin-stdlib-wasm-js.klib` for the two non-JVM frontends. No target accepts any of them, so
    // every refusal here costs nothing that D37's standing rule protects.
    check(container.expectAllowed || KModifier.EXPECT !in declared) {
        "$construct: '$name' is EXPECT, and this container is not one where that renders something " +
            "any target accepts: \"modifier 'expect' is not applicable to 'member property without " +
            "backing field or delegate'\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. Drop " +
            "EXPECT — inside an `expect` type Kotlin makes it implicit, and at file level it is legal."
    }
    check(container.externalAllowed || KModifier.EXTERNAL !in declared) {
        "$construct: '$name' is EXTERNAL, and this container is not one where that renders something " +
            "any target accepts: a member `external` property is \"non-top-level 'external' " +
            "declaration\" on Kotlin/JS and Kotlin/Wasm and \"modifier 'external' is not applicable " +
            "to 'property'\" on the JVM. Drop EXTERNAL — inside an `external` type Kotlin makes it " +
            "implicit, and at file level it is the one position Kotlin/JS and Kotlin/Wasm accept."
    }
    check(mutable || setter == null) {
        "$construct: '$name' is a `val` and has no setter. Declare it with `var`, or drop the setter."
    }
    // "Delegated property cannot have accessors with non-default implementations" — the delegate
    // *is* the accessor pair.
    check(by == null || (getter == null && setter == null)) {
        "$construct: '$name' is delegated with `by`, and a delegated property cannot have accessors. " +
            "Drop the delegate, or drop the accessors."
    }
    // A `var` that customises exactly one accessor gets the **default** other one, which reads or
    // writes the backing field — and a property that has a backing field and no initializer does not
    // compile anywhere: `Property must be initialized.` at file level, in a class, an object, a
    // companion object and an anonymous object; `Property in interface cannot have a backing field.`
    // in an interface; `Property with getter implementation cannot be abstract.` under `abstract`;
    // `'lateinit' modifier is not allowed on properties with a custom getter or setter.` under
    // `lateinit`; and the same under `external`/`expect`. All measured with kctfork, which is why
    // this needs no modifier or container awareness to be sound.
    //
    // **This is the decidable pair only, and deliberately stops there.** The general rule —
    // "a property whose accessors touch the backing field needs an initializer" — is undecidable
    // here: a *custom* setter that writes `field` forces an initializer too, and `field` is spelled
    // `expression("field")`, an opaque `CodeBlock` this DSL cannot look inside. Completing the rule
    // would start refusing valid generator code, which is the expensive direction. Do not "finish"
    // this check.
    if (mutable && receiver == null && init == null && by == null) {
        val missing = if (getter == null) "getter" else "setter"
        val present = if (getter == null) "setter" else "getter"
        check((getter == null) == (setter == null)) {
            "$construct: '$name' has a $present but no $missing, so Kotlin generates the $missing, " +
                "which needs a backing field, which needs an initializer. Add an initializer, or " +
                "write the $missing as well."
        }
    }
    if (receiver != null) {
        check(init == null) {
            "$construct: '$name' is an extension property, which has no backing field, so it cannot " +
                "have an initializer. Move the value into the getter."
        }
        // …but the two accessor requirements are the same question the missing-value check asks —
        // "does this container need a value?" — and they were answering it for themselves, which is
        // the reason [PropertyContainer.needsValue] is read here as well and not only below. An
        // extension property in an interface body or an `expect` body is *abstract*, and needs no
        // accessor at all. Measured, all three frontends:
        //
        //     interface I    { val String.a: Int }   OK  OK  OK
        //     interface I    { var String.a: Int }   OK  OK  OK
        //     expect class E { val String.a: Int }   nothing but the missing-`actual` complaint
        //     object O       { val String.a: Int }   extension property must have accessors or be
        //     val String.a: Int                      abstract.   (the two controls)
        //
        // The refusal below was therefore a *false rejection* in exactly the two containers whose
        // whole point is that a property may be a signature there. It is the same fact, read at the
        // second site that needs it.
        //
        // …and the container was only half of it. Kotlin's own sentence is *extension property must
        // have accessors **or be abstract***, and the clause above implements the first half of it
        // for one of the two ways a property can be abstract. The other is the declaration's own
        // modifier, and without it both of these were refused — the second from **both** sides, so
        // that the shape had no spelling at all: with no getter this rule fired, and with one the
        // `expect`-signature rule above did. Measured, all three frontends:
        //
        //     abstract class C { abstract val String.a: Int }   OK  OK  OK
        //     abstract class C { abstract var String.a: Int }   OK  OK  OK
        //     expect val String.a: Int                          OK  OK  OK
        //     expect var String.a: Int                          OK  OK  OK
        //
        // The exempt set is **measured rather than copied** from the missing-value check's below,
        // which also carries LATEINIT and EXTERNAL. Neither is legal on an extension property
        // anywhere: `lateinit var String.a: String` is *'lateinit' modifier is not allowed on
        // extension properties* on all three, and `external val String.a: Int` is *modifier
        // 'external' is not applicable to 'property'* on the JVM and *declaration of such kind
        // (extension property) cannot be external* on Kotlin/JS and Kotlin/Wasm. Copying the list
        // would have traded one false rejection for two renders no frontend accepts.
        val signature = KModifier.ABSTRACT in declared || KModifier.EXPECT in declared
        if (container.needsValue && !signature) {
            check(getter != null || by != null) {
                "$construct: '$name' is an extension property, which has no backing field, so it " +
                    "needs a getter (or a delegate)."
            }
        }
        // The **pair** requirement, and it is *not* the same question as the one above: an accessor
        // is not required here, but once a `var` has one of the two, Kotlin generates the **other**,
        // and the generated one reads or writes a backing field an extension property does not have.
        // That holds in every container, and exempting it along with the requirement above would
        // have been a new false permission — measured, all three frontends:
        //
        //     abstract class C { abstract var String.a: Int get() = 1 }  property with getter
        //     abstract class C { abstract var String.a: Int              implementation cannot be
        //                            set(value) { } }                    abstract.  /  …with setter
        //                                                                implementation cannot be
        //                                                                abstract.
        //     interface I      { var String.a: Int get() = 1 }           property in interface
        //     interface I      { var String.a: Int set(value) { } }      cannot have a backing field.
        //     class C          { var String.a: Int set(value) { } }      extension property must
        //                                                                have accessors or be
        //                                                                abstract.
        //     abstract class C { abstract var String.a: Int }            OK  OK  OK — the controls,
        //     interface I      { var String.a: Int }                     all three still render.
        //     object O         { var String.a: Int get() = 1
        //                            set(value) { } }
        //     expect class E   { class N { var String.a: Int } }         — and the nested one
        //
        // **Symmetric, because the pair is symmetric.** The first form of this check was
        // `!mutable || getter == null || setter != null`, which short-circuits on `getter == null`
        // and so let a setter with no getter through in every container — including the two rows
        // above that render an *abstract* or *interface* property with a setter body. It is
        // `(getter == null) == (setter == null)` now, the same shape the non-extension accessor pair
        // uses a hundred lines up, with the same missing/present message.
        //
        // `by` needs no term: a delegated property takes no accessors at all, which the check near
        // the top of this function has already settled, so both sides are null and the pair holds.
        check(!mutable || (getter == null) == (setter == null)) {
            val missing = if (getter == null) "getter" else "setter"
            val present = if (getter == null) "setter" else "getter"
            "$construct: '$name' is a mutable extension property with a $present but no $missing, " +
                "so Kotlin generates the $missing, which needs a backing field, and an extension " +
                "property has none. Write the $missing as well, or drop the $present."
        }
    }
    // The dual of [PropertyContainer.needsValue], and the reason it is a second field rather than a
    // second reading of the first: an `expect` property needs no value *and* may have none. It is a
    // signature, and the `actual` declaration on each platform carries everything else.
    //
    // Two sources make a property `expect`, and both have to be asked or the rule holds at one site
    // only — which is the defect this round removes elsewhere. Its own `EXPECT` modifier is the file
    // level's answer; [PropertyContainer.isExpectContext] is the container's, inherited to every
    // depth because Kotlin writes no keyword on a classifier nested inside an `expect` one (D36).
    //
    // KotlinPoet checks a fragment of this itself, from `TypeSpec.Builder.addProperty`, and as
    // `IllegalArgumentException: properties in expect classes can't have initializers` / `… can't
    // have getters and setters` — Global Constraint 26's forbidden exception type, with a message
    // naming neither construct. Its check reads the *immediate* builder's own `EXPECT`, so it sees
    // the first row below and none of the others; guarding only what it guards would have been the
    // same one-site mistake in a new place. Measured, one file per row, all three frontends:
    //
    //     expect class E { val a: Int = 1 }               (KotlinPoet's own row)
    //     expect val a: Int = 1                           expected property cannot have an initializer.
    //     expect class E { class N { val a: Int = 1 } }        — D36's table, row 2
    //     expect class E { companion object { val a: Int = 1 } }
    //     expect class E { object N { val a: Int = 1 } }
    //     expect val a: Int by lazy { 1 }                 expected property cannot be delegated.
    //     expect class E { val a: Int by lazy { 1 } }
    //     expect val a: Int get() = 1                     expected declaration cannot have a body.
    //     expect class E { class N { val a: Int get() = 1 } }
    //     expect lateinit var a: String                   expected property cannot be 'lateinit'.
    //     expect class E { lateinit var a: String }
    //
    // Every one of them rendered from this DSL until this round, and no frontend accepts any. The
    // control that keeps the boundary honest is `expect class E { val a: Int }`, which draws nothing
    // but the missing-`actual` complaint on all three, and still renders.
    if (container.isExpectContext || KModifier.EXPECT in declared) {
        // [expectRefusal] and [expectSubject], not four `check`s of this function's own: a property
        // is one member of the `expect` family and the family is one rule, so these four raise the
        // same sentence a `val`/`var` constructor parameter, a function body, an `init` block, a
        // delegation call and a supertype's arguments raise. See [Expect].
        val subject = expectSubject("'$name'")
        if (init != null) {
            expectRefusal(
                construct, "$subject carries an initializer",
                "expected property cannot have an initializer",
                "Drop init = …; the value belongs on the `actual` declaration.",
            )
        }
        if (by != null) {
            expectRefusal(
                construct, "$subject carries a delegate", "expected property cannot be delegated",
                "Drop by = …; the delegate belongs on the `actual` declaration.",
            )
        }
        if (getter != null || setter != null) {
            expectRefusal(
                construct, "$subject carries an accessor", "expected declaration cannot have a body",
                "Drop the accessor; it belongs on the `actual` declaration.",
            )
        }
        if (KModifier.LATEINIT in declared) {
            expectRefusal(
                construct, "$subject is LATEINIT", "expected property cannot be 'lateinit'",
                "Drop LATEINIT; it belongs on the `actual` declaration.",
            )
        }
    }
    // The same shape once more, on the third container fact this class carries. An interface holds
    // no state, and `kindName == "interface"` was read only where it decides whether a value is
    // *required* — so a property that needs storage rendered, and compiles nowhere. Measured, one
    // file per row, all three frontends:
    //
    //     interface I { val a: Int = 1 }           property initializers in interfaces are prohibited.
    //     interface I { var a: Int = 1 }
    //     interface I { val a: Int by lazy { 1 } } delegated properties in interfaces are prohibited.
    //     interface I { lateinit var a: String }   'lateinit' modifier is not allowed on abstract
    //                                              properties.
    //
    // A getter is the shape that *does* work (`interface I { val a: Int get() = 1 }`, clean on all
    // three), and so is the interface's companion object, which is a container of its own:
    // `interface I { companion object { val a: Int = 1 } }` and the `by lazy` form both compile
    // clean. That is why [PropertyContainer.backingFieldAllowed] reads `kindName` and nothing
    // inherited — the exemption stops at the body, exactly as `needsValue`'s does.
    container.backingFieldDenial?.let { denial ->
        when (denial) {
            BackingFieldDenial.INTERFACE -> {
                val inInterface = "$construct: '$name' is declared in an interface body, and an " +
                    "interface holds no state, so its properties have no backing field: "
                check(init == null) {
                    inInterface + "\"property initializers in interfaces are prohibited\" on all " +
                        "three frontends. Move the value into a getter, or declare it in the " +
                        "interface's companion object."
                }
                check(by == null) {
                    inInterface + "\"delegated properties in interfaces are prohibited\" on all " +
                        "three frontends. Move the delegate into a getter, or declare it in the " +
                        "interface's companion object."
                }
                check(KModifier.LATEINIT !in declared) {
                    inInterface + "\"'lateinit' modifier is not allowed on abstract properties\" " +
                        "on all three frontends. Drop LATEINIT; an interface property is abstract, " +
                        "and the implementing class carries the storage."
                }
            }
            // The same question, the same three shapes, a different kind — and the frontends have
            // their own two sentences for it. See [Kinds].
            BackingFieldDenial.VALUE_CLASS -> {
                if (by != null) valueClassHoldsNoStorage(construct, name, "a delegate", true)
                if (init != null) valueClassHoldsNoStorage(construct, name, "an initializer", false)
                if (KModifier.LATEINIT in declared) {
                    valueClassHoldsNoStorage(construct, name, "LATEINIT", false)
                }
            }
        }
    }
    // A `fun interface` holds no *abstract* property, and a property with no accessor in an
    // interface body is abstract whether or not the modifier is written. See [Kinds].
    if (container.funInterface && getter == null && (KModifier.ABSTRACT in declared || by == null)) {
        funInterfaceHoldsNoAbstractProperty(construct, name)
    }
    // A property with no initializer, no delegate and no getter renders `val x: Int`, and kotlinc
    // answers `Property must be initialized.` at file level and `Property must be initialized or be
    // abstract.` in a class, an object, a companion object, a nested type, an enum and a sealed class
    // — all measured with kctfork. It has rendered since Task 12 and predates E2a's accessors, which
    // is what finally made the *decidable* half of it decidable: the getter is the third way to give
    // a property a value, and until E2a there was no slot to check.
    //
    // Two kinds of exemption, both measured rather than assumed:
    //
    // - **container**: an interface body, or an `expect` type's body — including every classifier
    //   nested inside one, which Kotlin marks with no keyword of its own ([TypeScope.isExpect]).
    //   Nothing else exempts: not a companion object inside an interface, not an enum, not a sealed
    //   class. [PropertyContainer.needsValue] carries the answer down from the scope rather than
    //   being inferred from anything visible here.
    // - **modifier**: ABSTRACT, LATEINIT, EXPECT and EXTERNAL, and the list needs no container term
    //   of its own any more — the two gates near the top of this function have already refused every
    //   modifier that is illegal *here*, so reaching this line with EXPECT or EXTERNAL declared is
    //   itself proof that the container allows it. The list used to carry `if
    //   (container.externalAllowed)`, which restated a decision this function was not otherwise
    //   taking at all.
    //
    // EXTERNAL is on that list against E2b's finding, which read *"`external` is not an exempt case,
    // and never could be"* off a JVM-only measurement. It is a **platform** question, and two of the
    // three frontends shipped with Kotlin 2.4.10 disagree with the JVM (one file each, measured):
    //
    //     kotlinc        external val a: Int  → modifier 'external' is not applicable to 'property'
    //     kotlinc-js     external val a: Int  → OK        external var a: Int → OK
    //     kotlinc-wasm   external val a: Int  → OK
    //     kotlinc-js     external val a: Int = 1
    //                        → wrong initializer of external declaration. Must be ' = definedExternally'.
    //
    // Where an `external` property exists at all it takes *no* initializer, so "Pass init = …" was
    // the one remedy guaranteed to be wrong for it — and a `check` refusing the modifier outright,
    // which the previous fix brief asked for, would have made Kotlin/JS external declarations
    // ungenerable. EXTERNAL is deliberately *not* named in the remedy list below — "declare it
    // EXTERNAL" means "the definition lives in JavaScript", which is not an answer to "this property
    // has no value".
    //
    // Where the modifier is *legal* is decided above and not here: an exemption is earned by there
    // existing a target platform where the render compiles, and an ordinary member `external`
    // property has none (measured, same three frontends, one file each):
    //
    //     kotlinc        class C { external val a: Int } → modifier 'external' is not applicable to 'property'
    //     kotlinc-js     class C { external val a: Int } → non-top-level 'external' declaration.
    //     kotlinc-wasm   class C { external val a: Int } → non-top-level 'external' declaration.
    //     …and identically for `object O { … }`.
    //
    // The one platform not measured either way is Kotlin/Native. It is an argument for keeping the
    // file-level exemption — an unmeasured target cannot license a refusal — and not an argument for
    // the member position, which three frontends agree on.
    //
    // Unlike the `expect` rows none of this is measurable by the suite: kctfork compiles for the
    // JVM only, so the lines above are hand-run and the tests pin the render and the refusal alone.
    //
    // What an `external` *class* makes of its members is **half** answered. Measured on the same
    // three frontends, `external class C { val a: Int }` and `external object O { val a: Int }`
    // compile clean on JS and on Wasm (and are *modifier 'external' is not applicable to 'class'* on
    // the JVM), so the container exempts its members there. Since this round, writing the member's
    // own `EXTERNAL` reaches that render — KotlinPoet drops the keyword, `externalAllowed` is true,
    // and this exempt list lets the missing value through. What is still refused is the same
    // container with the modifier *omitted* — `` `class`(EXTERNAL, "C") { `val`("x", INT) } `` —
    // because `needsValue` has no `external` term to match `isExpect`. That is D36's
    // container-inheritance shape, still unsolved for `external`; the difference from before is that
    // the shape now has a working spelling rather than none.
    //
    // Both `expect` rows *are* measured, contrary to E2b's report: `-Xmulti-platform` gets the
    // frontend past `'expect' and 'actual' declarations can be used only in multiplatform projects`,
    // and `expect val a: Int = 1` is then `expected property cannot have an initializer` while
    // `expect val a: Int` draws no initialization diagnostic at all. `UninitializedPropertyCompileTest`
    // runs that through kctfork's own `multiplatform` flag, on this DSL's output.
    //
    // Deliberately *after* the extension-property rules just above, not before them: an extension
    // property with no getter is refused there with a message that says why it can have no backing
    // field, which is more useful than this one. By the time control reaches here a `receiver`
    // implies a getter or a delegate, so the condition needs no term for it.
    if (container.needsValue && init == null && by == null && getter == null) {
        // Unconditional, and it can be: this branch is now only ever reached by a property whose
        // modifiers are already legal in this container, because the two gates above ran first. A
        // declared `EXPECT` implies `container.expectAllowed` and a declared `EXTERNAL` implies
        // `container.externalAllowed` by the time control gets here, so folding either container
        // fact into this list a second time would restate a decision already taken — which is the
        // shape of defect this round exists to remove.
        val exempt = listOf(
            KModifier.ABSTRACT,
            KModifier.LATEINIT,
            KModifier.EXPECT,
            KModifier.EXTERNAL,
        )
        // The remedy list is built from what is legal *in this container*, not from the exempt list.
        // The two are not the same set, and a remedy that does not compile where it is offered is
        // worse than none: `abstract val a: Int` at file level is `modifier 'abstract' is not
        // applicable to 'top level property without backing field or delegate'`, `expect val` inside
        // a class is the same sentence for `'member property …'`, and `lateinit val` is `'lateinit'
        // modifier is allowed only on mutable properties` — which the LATEINIT check at the top of
        // this function now refuses outright. (LATEINIT's *other* two preconditions are not checked
        // here: `lateinit var a: Int` is `'lateinit' modifier is not allowed on properties of
        // primitive types` and `lateinit var a: String?` is `… of a type with nullable upper bound`,
        // both measured. Naming the preconditions in the remedy is deliberate; guarding them is a
        // separate refusal and out of this round's scope.)
        val remedies = buildList {
            if (container.abstractAllowed) add("ABSTRACT")
            if (mutable) add("LATEINIT")
            if (container.expectAllowed) add("EXPECT")
        }
        check(declared.any { it in exempt }) {
            "$construct: '$name' has no initializer, no delegate and no getter, so it renders " +
                "`${if (mutable) "var" else "val"} $name: T` — \"Property must be initialized.\" " +
                "Pass init = …, by = … or a getter" +
                (if (remedies.isEmpty()) "" else ", or declare it ${remedies.joinToOr()}") +
                ". A property in an interface body needs none of these, and this check does not " +
                "fire there."
        }
    }
    // A property's type parameters take no declaration-site variance and no `reified`, for the same
    // reasons a function's do not.
    checkTypeVariables(construct, name, typeVariables, varianceAllowed = false, reifiedAllowed = false)
    if (typeVariables.isEmpty()) return
    checkNotNull(receiver) {
        "$construct: '$name' declares type parameters but has no receiver. Kotlin allows a property's " +
            "type parameter only where its receiver type uses it."
    }
    typeVariables.forEach { variable ->
        check(receiver.mentions(variable)) {
            "$construct: type parameter \"${variable.name}\" of '$name' is not used in the receiver " +
                "type. Kotlin allows a property's type parameter only where its receiver type uses it."
        }
    }
}

/**
 * `""`, `"A"`, `"A or B"`, `"A, B or C"` — the remedy list, spelled the way the rest of a message is.
 *
 * The empty row is there so the helper is total on its own rather than only in context: `last()`
 * throws on an empty list, and the sole call site happens to guard emptiness before calling — but it
 * guards it because an empty remedy list needs the whole *clause* dropped, not an empty tail, which
 * is a decision about the sentence rather than about this function. Nothing reaches this row today.
 */
private fun List<String>.joinToOr(): String = when (size) {
    0 -> ""
    1 -> single()
    else -> dropLast(1).joinToString(", ") + " or " + last()
}

/**
 * Whether [variable] occurs anywhere in this type — `T` itself, `List<T>`, `out T`, `(T) -> Unit`.
 *
 * Compared by name rather than by value: the [TypeVariableName] written into the receiver may carry
 * different bounds from the one in the `typeVariables` list, and Kotlin resolves it by name.
 */
private fun TypeName.mentions(variable: TypeVariableName): Boolean = when (this) {
    is TypeVariableName -> name == variable.name || bounds.any { it.mentions(variable) }
    is ParameterizedTypeName -> typeArguments.any { it.mentions(variable) }
    is WildcardTypeName -> (inTypes + outTypes).any { it.mentions(variable) }
    is LambdaTypeName ->
        this.receiver?.mentions(variable) == true ||
            parameters.any { it.type.mentions(variable) } ||
            returnType.mentions(variable)

    else -> false
}

/**
 * One binding construct for all three scopes, taking the union of their parameters and rejecting
 * the combinations that scope cannot express (ADR 0003).
 *
 * The `when` is exhaustive over the sealed [Scope] hierarchy with no `else`, so a fourth scope
 * breaks the build here rather than falling through silently (D17).
 */
internal fun Scope.bind(
    mutable: Boolean,
    annotations: Annotations?,
    modifiers: Modifiers?,
    name: String,
    type: TypeName?,
    init: Expr?,
    by: Expr?,
    typeVariables: List<TypeVariableName> = emptyList(),
    receiver: TypeName? = null,
    setterParam: String = "value",
    setter: (BlockScope.(Expr) -> Unit)? = null,
    getter: (BlockScope.() -> Unit)? = null,
    kdoc: String? = null,
): Expr {
    check(init == null || by == null) {
        "Binding '$name' cannot have both an initializer and a delegate."
    }
    return when (this) {
        is BlockScope -> {
            check(annotations == null && modifiers == null) {
                "A local binding ('$name') cannot carry annotations or modifiers."
            }
            // A local variable has no accessors ("Local variable with getter/setter"), no receiver
            // and no type parameters — all three are property-only in Kotlin, and all three would
            // otherwise be silently dropped here, since `bindLocal` builds a `CodeBlock` and has
            // nowhere to put them.
            check(typeVariables.isEmpty() && receiver == null && setter == null && getter == null) {
                "A local binding ('$name') cannot have accessors, an extension receiver or type " +
                    "parameters; only a property can."
            }
            // A local variable takes no KDoc either: `bindLocal` builds a `CodeBlock`, which has no
            // documentation slot, so the text would be silently dropped. Kotlin has no syntax for it
            // anyway — a KDoc comment on a local is just a comment.
            check(kdoc == null) {
                "A local binding ('$name') cannot carry KDoc; only a declaration can."
            }
            bindLocal(mutable, name, type, init, by)
        }

        is FileScope -> {
            val spec = propertyOf(
                mutable, annotations, modifiers, name, type, init, by,
                typeVariables, receiver, setterParam, setter, getter, kdoc,
            )
            builder.addProperty(spec)
            handle(spec.name, type, mutable, ownerOf(spec.name, receiver))
        }

        is TypeScope -> {
            val spec = propertyOf(
                mutable, annotations, modifiers, name, type, init, by,
                typeVariables, receiver, setterParam, setter, getter, kdoc,
            )
            builder.addProperty(spec)
            handle(spec.name, type, mutable, ownerOf(spec.name, receiver))
        }
    }
}

/** `a = b`. Named because `=` is not overloadable. */
context(b: BlockScope)
public infix fun Expr.assign(value: Expr) {
    check(mutable != false) {
        "assign: '${name ?: this}' is a val and cannot be reassigned."
    }
    b.checkOwned(this)
    b.checkOwned(value)
    b.emitCode(CodeBlock.of("%L·=·%L", code, value.code))
}

/** `a op= b`, for the five compound assignments Kotlin defines. */
private fun BlockScope.compound(target: Expr, op: String, opName: String, value: Expr) {
    check(target.mutable != false) {
        "$opName: '${target.name ?: target}' is a val and cannot be reassigned."
    }
    checkOwned(target)
    checkOwned(value)
    emitCode(CodeBlock.of("%L·%L·%L", target.code, op, value.code))
}

// The five compound assignments exist in emitting form only: Kotlin requires `plusAssign` and
// friends to return `Unit`, so operator syntax can never be pure. The pure twins are
// `stmts { total += x }` and `total assign (total + x)`.

/** `a += b`. */
context(b: BlockScope)
public operator fun Expr.plusAssign(value: Expr) {
    b.compound(this, "+=", "plusAssign", value)
}

/** `a -= b`. */
context(b: BlockScope)
public operator fun Expr.minusAssign(value: Expr) {
    b.compound(this, "-=", "minusAssign", value)
}

/** `a *= b`. */
context(b: BlockScope)
public operator fun Expr.timesAssign(value: Expr) {
    b.compound(this, "*=", "timesAssign", value)
}

/** `a /= b`. */
context(b: BlockScope)
public operator fun Expr.divAssign(value: Expr) {
    b.compound(this, "/=", "divAssign", value)
}

/** `a %= b`. */
context(b: BlockScope)
public operator fun Expr.remAssign(value: Expr) {
    b.compound(this, "%=", "remAssign", value)
}
