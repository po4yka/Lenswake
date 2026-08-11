package dev.po4yka.lenswake.platform

import android.app.ActivityOptions
import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

internal object PendingIntentCreatorBackgroundActivityStartMode {
    fun resolve(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        Api36Impl.allowAlways()
    } else {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private object Api36Impl {
        @DoNotInline
        fun allowAlways(): Int = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
    }
}
