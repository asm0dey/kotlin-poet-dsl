package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.KModifier

/** A set of Kotlin modifiers, built with `+` and written immediately before the name. */
@JvmInline
public value class Modifiers internal constructor(internal val set: Set<KModifier>)

public operator fun KModifier.plus(other: KModifier): Modifiers = Modifiers(linkedSetOf(this, other))

public operator fun Modifiers.plus(other: KModifier): Modifiers =
    Modifiers(LinkedHashSet(set).apply { add(other) })

public operator fun Modifiers.plus(other: Modifiers): Modifiers =
    Modifiers(LinkedHashSet(set).apply { addAll(other.set) })

/** Wraps a single modifier, for callers building variants programmatically. */
public fun KModifier.toModifiers(): Modifiers = Modifiers(linkedSetOf(this))

internal fun Modifiers?.toList(): List<KModifier> = this?.set?.toList().orEmpty()
