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
        val previousEncodedIntent = preferences.all[key] as? String
        return if (preferences.edit().putString(key, encodedIntent).commit()) {
            Entry(key, work)
        } else {
            val rollback = preferences.edit()
            if (previousEncodedIntent == null) {
                rollback.remove(key)
            } else {
                rollback.putString(key, previousEncodedIntent)
            }
            rollback.commit()
            null
        }
    }

    fun read(): Snapshot {
        val decoded = mutableListOf<Entry>()
        val corruptEntries = mutableListOf<CorruptEntry>()
        preferences.all.forEach { (key, value) ->
            val encodedIntent = value as? String
            if (encodedIntent == null) {
                corruptEntries += CorruptEntry(key, CorruptionReason.NonStringValue)
                return@forEach
            }
            val intent = try {
                Intent.parseUri(encodedIntent, Intent.URI_INTENT_SCHEME)
            } catch (error: Exception) {
                corruptEntries += CorruptEntry(
                    key,
                    CorruptionReason.InvalidIntentUri(error.javaClass.simpleName),
                )
                return@forEach
            }
            val work = try {
                AlarmDeliveryWorkContract.parse(intent)
            } catch (error: Exception) {
                corruptEntries += CorruptEntry(
                    key,
                    CorruptionReason.InvalidDeliveryWork(error.javaClass.simpleName),
                )
                return@forEach
            }
            if (work == null) {
                corruptEntries += CorruptEntry(key, CorruptionReason.UnrecognizedDeliveryWork)
                return@forEach
            }
            decoded += Entry(key, work)
        }
        val winners = decoded
            .groupBy { it.work.markerId }
            .values
            .map { duplicates ->
                duplicates.maxWith(
                    compareBy<Entry>(Entry::deliveryAttempt, Entry::key),
                )
            }
        val winnerKeys = winners.mapTo(mutableSetOf(), Entry::key)
        val staleKeys = decoded.mapNotNull { entry -> entry.key.takeUnless(winnerKeys::contains) }
        if (staleKeys.isNotEmpty()) {
            val cleanup = preferences.edit()
            staleKeys.forEach(cleanup::remove)
            cleanup.commit()
        }
        return Snapshot(
            entries = winners,
            corruptEntries = corruptEntries.sortedBy(CorruptEntry::key),
        )
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
    ) {
        val deliveryAttempt: Int
            get() = work.deliveryAttempt
    }

    data class Snapshot(
        val entries: List<Entry>,
        val corruptEntries: List<CorruptEntry>,
    )

    data class CorruptEntry(
        val key: String,
        val reason: CorruptionReason,
    )

    sealed interface CorruptionReason {
        data object NonStringValue : CorruptionReason
        data class InvalidIntentUri(val causeType: String) : CorruptionReason
        data class InvalidDeliveryWork(val causeType: String) : CorruptionReason
        data object UnrecognizedDeliveryWork : CorruptionReason
    }

    private fun key(encodedIntent: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encodedIntent.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private companion object {
        const val PREFERENCE_NAME = "alarm_delivery_journal"
    }
}
