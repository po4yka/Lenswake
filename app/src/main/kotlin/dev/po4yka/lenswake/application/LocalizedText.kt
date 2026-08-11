package dev.po4yka.lenswake.application

import androidx.annotation.StringRes
import androidx.annotation.PluralsRes

interface LocalizedTextResolver {
    fun get(
        @StringRes resourceId: Int,
        vararg formatArgs: Any,
    ): String

    fun quantity(
        @PluralsRes resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String
}

/** A user-facing message whose copy is owned by Android resources. */
data class LocalizedText(
    @param:StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
) {
    fun resolve(strings: LocalizedTextResolver): String =
        strings.get(resourceId, *formatArgs.toTypedArray())
}

fun localizedText(
    @StringRes resourceId: Int,
    vararg formatArgs: Any,
): LocalizedText = LocalizedText(resourceId, formatArgs.toList())
