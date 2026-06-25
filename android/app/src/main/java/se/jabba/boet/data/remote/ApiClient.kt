package se.jabba.boet.data.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Thin OkHttp + kotlinx.serialization REST client. The base URL is provided
// dynamically so it can be changed in Settings without rebuilding the client.
class ApiClient(private val baseUrlProvider: () -> String) {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = baseUrlProvider().trimEnd('/') + path

    class HttpException(val code: Int, message: String) : RuntimeException(message)

    private inline fun <reified T> request(method: String, path: String, body: String?): T {
        val reqBody = body?.toRequestBody(jsonMedia)
            ?: if (method != "GET" && method != "DELETE") "".toRequestBody(jsonMedia) else null
        val req = Request.Builder().url(url(path)).method(method, reqBody).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, text)
            return if (text.isBlank()) Unit as T else json.decodeFromString(text)
        }
    }

    // --- Reads -------------------------------------------------------------
    fun bootstrap(): BootstrapDto = request("GET", "/api/bootstrap", null)
    fun history(limit: Int = 40): List<HistoryItem> = request("GET", "/api/history?limit=$limit", null)
    fun favorites(): List<FavoriteDto> = request("GET", "/api/favorites", null)
    fun parseRecipe(text: String): RecipeResponse =
        request("POST", "/api/recipe/parse", json.encodeToString(RecipeReq.serializer(), RecipeReq(text)))

    // --- Generic mutations (also used by the offline outbox) ---------------
    fun send(method: String, path: String, body: String?): String {
        val reqBody = body?.toRequestBody(jsonMedia)
            ?: if (method != "GET" && method != "DELETE") "".toRequestBody(jsonMedia) else null
        val req = Request.Builder().url(url(path)).method(method, reqBody).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, text)
            return text
        }
    }
}

@kotlinx.serialization.Serializable
private data class RecipeReq(val text: String)
