package app.nicotv.firetv.data.nico

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale

/** 取得元の応答形式が変わっても落ちずに「不明」として扱うための小さな道具。 */
internal operator fun JsonElement?.get(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

internal operator fun JsonElement?.get(index: Int): JsonElement? =
    (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
internal val JsonElement?.int: Int? get() = long?.toInt()
internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

internal fun parseIsoMillis(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)?.time
    } catch (_: Exception) {
        null
    }
}
