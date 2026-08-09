package dev.po4yka.lenswake.alarm

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Private transport journal for intents accepted by the service but not yet reconciled. Domain
 * truth remains in Room and every restored entry is revalidated by [AlarmTriggerCoordinator].
 */
internal class AlarmDeliveryJournal(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun persist(intent: Intent): Entry? {
        val work = AlarmDeliveryWorkContract.parse(intent) ?: return null
        val encodedIntent = intent.toUri(Intent.URI_INTENT_SCHEME)
        val key = key(encodedIntent)
        return if (preferences.edit().putString(key, encodedIntent).commit()) {
            Entry(key, work)
        } else {
            null
        }
    }

    fun entries(): List<Entry> = preferences.all.mapNotNull { (key, value) ->
        val encodedIntent = value as? String ?: return@mapNotNull null
        val work = runCatching {
            AlarmDeliveryWorkContract.parse(Intent.parseUri(encodedIntent, Intent.URI_INTENT_SCHEME))
        }.getOrNull() ?: return@mapNotNull null
        Entry(key, work)
    }

    fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    fun replace(key: String, intent: Intent): Entry? {
        val work = AlarmDeliveryWorkContract.parse(intent) ?: return null
        val encodedIntent = intent.toUri(Intent.URI_INTENT_SCHEME)
        val replacementKey = key(encodedIntent)
        return if (
            preferences.edit()
                .remove(key)
                .putString(replacementKey, encodedIntent)
                .commit()
        ) {
            Entry(replacementKey, work)
        } else {
            null
        }
    }

    data class Entry(
        val key: String,
        val work: AlarmDeliveryWork,
    )

    private fun key(encodedIntent: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encodedIntent.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private companion object {
        const val PREFERENCE_NAME = "alarm_delivery_journal"
    }
}
