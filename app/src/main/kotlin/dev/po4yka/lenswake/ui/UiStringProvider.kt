package dev.po4yka.lenswake.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

interface UiStringProvider {
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

class AndroidUiStringProvider(
    context: Context,
) : UiStringProvider {
    private val resources = context.applicationContext.resources

    override fun get(
        resourceId: Int,
        vararg formatArgs: Any,
    ): String = resources.getString(resourceId, *formatArgs)

    override fun quantity(
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = resources.getQuantityString(resourceId, quantity, *formatArgs)
}
