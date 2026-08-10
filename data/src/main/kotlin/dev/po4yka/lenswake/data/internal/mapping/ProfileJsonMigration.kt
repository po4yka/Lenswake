package dev.po4yka.lenswake.data.internal.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Upgrades historical profile payloads before the current strict codec reads them. */
internal object ProfileJsonMigration {
    private const val CURRENT_SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1

    private val json = Json

    fun targets(encoded: String): String = migrateSelectorCollection(encoded, "targets")

    fun speedTargets(encoded: String): String = migrateSelectorCollection(encoded, "targets")

    fun stateSignals(encoded: String): String = migrateSelectorCollection(encoded, "signals")

    private fun migrateSelectorCollection(
        encoded: String,
        collectionKey: String,
    ): String {
        val root = json.parseToJsonElement(encoded)
        val collection = when (root) {
            is JsonArray -> root
            is JsonObject -> collectionFromVersionedPayload(root, collectionKey)
            else -> error("Persisted profile $collectionKey JSON must be an object or array")
        }
        val migratedCollection = JsonArray(collection.map(::migrateEntry))
        return JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION),
                collectionKey to migratedCollection,
            ),
        ).toString()
    }

    private fun collectionFromVersionedPayload(
        payload: JsonObject,
        collectionKey: String,
    ): JsonArray {
        require(payload.keys == setOf("schemaVersion", collectionKey)) {
            "Persisted profile $collectionKey JSON contains unsupported fields"
        }
        val version = payload["schemaVersion"]?.jsonPrimitive?.intOrNull
        require(version == LEGACY_SCHEMA_VERSION || version == CURRENT_SCHEMA_VERSION) {
            "Unsupported persisted profile $collectionKey JSON schema version: $version"
        }
        return payload[collectionKey]?.jsonArray
            ?: error("Persisted profile $collectionKey JSON has no collection")
    }

    private fun migrateEntry(element: JsonElement): JsonObject {
        val entry = element.jsonObject
        val selectors = entry["selectors"]?.jsonArray
            ?: error("Persisted profile selector collection entry has no selectors")
        val migratedSelectors = selectors.map { selectorElement ->
            val selector = selectorElement.jsonObject.toMutableMap()
            selector.putIfAbsent("expectedSelected", JsonNull)
            selector.putIfAbsent("expectedChecked", JsonNull)
            JsonObject(selector)
        }
        return JsonObject(
            entry + ("selectors" to JsonArray(migratedSelectors)),
        )
    }
}
