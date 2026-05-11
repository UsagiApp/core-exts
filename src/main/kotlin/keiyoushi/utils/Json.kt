package keiyoushi.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
public val jsonInstance: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}

public inline fun <reified T> String.parseAs(json: Json = jsonInstance): T = json.decodeFromString(this)

public inline fun <reified T> String.parseAs(json: Json = jsonInstance, transform: (String) -> String): T = transform(this).parseAs(json)

public inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T = use { it.body.string().parseAs(json) }

public inline fun <reified T> Response.parseAs(json: Json = jsonInstance, transform: (String) -> String): T = use { it.body.string().parseAs(json, transform) }

@OptIn(ExperimentalSerializationApi::class)
public inline fun <reified T> JsonElement.parseAs(json: Json = jsonInstance): T = json.decodeFromJsonElement(this)

public inline fun <reified T> InputStream.parseAs(json: Json = jsonInstance): T = use { it.bufferedReader().use { reader -> reader.readText() }.parseAs(json) }

public inline fun <reified T> T.toJsonString(json: Json = jsonInstance): String = json.encodeToString(kotlinx.serialization.serializer(), this)

public inline fun <reified T> T.toJsonRequestBody(json: Json = jsonInstance): RequestBody = toJsonString(json).toRequestBody("application/json".toMediaType())
