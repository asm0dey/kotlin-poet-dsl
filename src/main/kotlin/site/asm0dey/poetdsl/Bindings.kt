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
 * | container | no value | `abstract` | `expect` on the property | `external` on the property |
 * |---|---|---|---|---|
 * | file | *Property must be initialized.* | *modifier 'abstract' is not applicable to 'top level property without backing field or delegate'* | OK | JVM: *modifier 'external' is not applicable to 'property'*; **JS and Wasm: OK** |
 * | `class` | *…or be abstract.* | *abstract property 'a' in non-abstract class 'C'* | *modifier 'expect' is not applicable to 'member property without backing field or delegate'* | JVM: as above; JS and Wasm: *non-top-level 'external' declaration* |
 * | `abstract`/`sealed`/`enum class` | *…or be abstract.* | OK | as above | as above |
 * | `interface` | OK | OK (rendered without the keyword — it is implicit) | as above | as above |
 * | `object`, `companion object` | *…or be abstract.* | *abstract property 'a' in non-abstract class 'O'* | as above | as above |
 * | `expect class` body | OK | *abstract property 'a' in non-abstract class 'E'* | as above | as above |
 *
 * The `external` column is the one that needs three frontends rather than one, and it is why
 * [externalAllowed] exists as a container fact at all: the file row has a target where the output
 * compiles and every member row has none. `kotlinc-js`/`kotlinc-wasm` run with the matching
 * `kotlin-stdlib-js.klib` / `kotlin-stdlib-wasm-js.klib` from the same distribution. See D37.
 *
 * [abstractAllowed] is also what keeps KotlinPoet's own
 * `IllegalArgumentException: non-abstract type C cannot declare abstract property x` — thrown from
 * `TypeSpec.build` whenever `ABSTRACT` is on a property of a type that is not an interface and
 * carries none of `ABSTRACT`/`SEALED`/`ENUM`, and reading `non-abstract type **null**` for an
 * anonymous companion object — out of this DSL's output. Global Constraint 26 forbids that exception
 * type, and its message names neither construct.
 */
internal class PropertyContainer(
    val needsValue: Boolean,
    val abstractAllowed: Boolean,
    val expectAllowed: Boolean,
    val externalAllowed: Boolean,
) {
    internal companion object {
        /**
         * [propertySpec]'s detached builder, which has no container and cannot be given one: it
         * returns a bare `PropertySpec` and an interface body is a legitimate destination. Every
         * container-dependent rule is therefore off here, which is the same answer
         * `containerNeedsValue = false` gave before this class existed.
         */
        val UNKNOWN: PropertyContainer = PropertyContainer(
            needsValue = false,
            abstractAllowed = true,
            expectAllowed = true,
            externalAllowed = true,
        )
    }
}

/** See [PropertyContainer]. A [BlockScope] never reaches here — [bindLocal] takes that branch. */
private fun Scope.propertyContainer(): PropertyContainer {
    if (this !is TypeScope) {
        return PropertyContainer(
            needsValue = true,
            abstractAllowed = false,
            expectAllowed = true,
            // The file level is the only place an `external` property has a target that accepts it,
            // and this branch *is* the file level. See the exempt list in [checkProperty] and D37.
            externalAllowed = true,
        )
    }
    val typeModifiers = builder.modifiers
    return PropertyContainer(
        // [TypeScope.isExpect], not `EXPECT in typeModifiers`: the modifier sits on the *outermost*
        // `expect class` only, and every classifier nested inside one inherits the rule.
        needsValue = !(kindName == "interface" || isExpect),
        // Exactly Kotlin's list, which is narrower than KotlinPoet's on one row: KotlinPoet accepts
        // an abstract property in an `abstract object`, and Kotlin has no such thing. `kindName`
        // separates the three builders this DSL uses — `classBuilder`, `objectBuilder`,
        // `interfaceBuilder` — plus the companion object's, which is `"companion object"`.
        abstractAllowed = kindName == "interface" || (
            kindName == "class" && typeModifiers.any {
                it == KModifier.ABSTRACT || it == KModifier.SEALED || it == KModifier.ENUM
            }
            ),
        expectAllowed = false,
        // A *member* `external` property is refused by every frontend that ships with Kotlin 2.4.10
        // — *non-top-level 'external' declaration* on JS and on Wasm, and not applicable to a
        // property at all on the JVM — so there is no target where this renders something that
        // compiles, which is the bar the file-level row clears. See [PropertyContainer]'s table.
        externalAllowed = false,
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
        check(getter != null || by != null) {
            "$construct: '$name' is an extension property, which has no backing field, so it needs a " +
                "getter (or a delegate)."
        }
        check(!mutable || setter != null || by != null) {
            "$construct: '$name' is a mutable extension property, which has no backing field, so it " +
                "needs a setter as well as a getter (or a delegate)."
        }
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
    // - **modifier**: ABSTRACT, LATEINIT and EXPECT everywhere, plus EXTERNAL **at file level only**.
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
    // But the exemption is **gated on the container**, which is the whole of what justifies it: an
    // exemption is earned by there existing a target platform where the output compiles, and a
    // *member* `external` property has none (measured, same three frontends, one file each):
    //
    //     kotlinc        class C { external val a: Int } → modifier 'external' is not applicable to 'property'
    //     kotlinc-js     class C { external val a: Int } → non-top-level 'external' declaration.
    //     kotlinc-wasm   class C { external val a: Int } → non-top-level 'external' declaration.
    //     …and identically for `object O { … }`.
    //
    // Ungated, the exemption emitted exactly those, where the DSL had refused them since the check
    // landed. [PropertyContainer.externalAllowed] carries the file/member split down from the scope,
    // the same way `needsValue` carries the interface and `expect` containers. Recorded as D37.
    //
    // The one platform not measured either way is Kotlin/Native. It is an argument for keeping the
    // file-level exemption — an unmeasured target cannot license a refusal — and not an argument for
    // the member position, which three frontends agree on.
    //
    // Unlike the `expect` rows none of this is measurable by the suite: kctfork compiles for the
    // JVM only, so the lines above are hand-run and the tests pin the render and the refusal alone.
    //
    // What an `external` *class* makes of its members is a separate question and still open. Measured
    // on the same three frontends: `external class C { val a: Int }` and `external object O { val a:
    // Int }` both compile clean on JS and on Wasm (and are *modifier 'external' is not applicable to
    // 'class'* on the JVM), so the container exempts its members there — and this DSL refuses both,
    // as it did before any of this. That is the container-inheritance shape D36 solved for `expect`,
    // not yet solved for `external`, and a new exemption rather than a restoration.
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
        val exempt = buildList {
            add(KModifier.ABSTRACT)
            add(KModifier.LATEINIT)
            add(KModifier.EXPECT)
            if (container.externalAllowed) add(KModifier.EXTERNAL)
        }
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

/** `"A"`, `"A or B"`, `"A, B or C"` — the remedy list, spelled the way the rest of a message is. */
private fun List<String>.joinToOr(): String =
    if (size == 1) single() else dropLast(1).joinToString(", ") + " or " + last()

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
