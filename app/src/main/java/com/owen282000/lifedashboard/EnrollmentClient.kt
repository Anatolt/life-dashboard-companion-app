package com.owen282000.lifedashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@Serializable
data class EnrollmentClientInfo(
    @SerialName("app_id") val appId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("installation_id") val installationId: String,
    val platform: String = "android"
)

@Serializable
data class EnrollmentRequest(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    val code: String,
    val client: EnrollmentClientInfo,
    @SerialName("requested_capabilities") val requestedCapabilities: List<String>
)

@Serializable
data class EnrollmentResponse(
    @SerialName("protocol_version") val protocolVersion: Int,
    val profile: ConnectionProfile
)

@Serializable
data class EnrollmentErrorEnvelope(val error: EnrollmentError)

@Serializable
data class EnrollmentError(
    val code: String,
    val message: String,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null
)

data class EnrollmentInput(val endpoint: String, val code: String)

class EnrollmentException(
    val errorCode: String,
    message: String,
    val retryAfterSeconds: Long? = null,
    val retryable: Boolean = false
) : Exception(message)

object EnrollmentInputParser {
    fun parse(value: String): EnrollmentInput {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid enrollment string")
        }
        require(uri.scheme == "lifedashboard" && uri.host == "enroll") {
            "Enrollment string must start with lifedashboard://enroll"
        }
        val endpoint = parseParameters(uri.rawQuery)["endpoint"]
            ?: throw IllegalArgumentException("Enrollment endpoint is missing")
        val endpointUri = try { URI(endpoint) } catch (_: Exception) { null }
        require(endpointUri?.scheme == "https" && endpointUri.host != null && endpointUri.userInfo == null) {
            "Enrollment endpoint must be an HTTPS URL without credentials"
        }
        val code = parseParameters(uri.rawFragment)["code"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("One-time enrollment code is missing")
        return EnrollmentInput(endpoint, code)
    }

    private fun parseParameters(raw: String?): Map<String, String> = raw.orEmpty()
        .split('&')
        .filter { it.isNotBlank() }
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

class EnrollmentClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun exchange(input: EnrollmentInput, request: EnrollmentRequest): ConnectionProfile {
        val body = json.encodeToString(EnrollmentRequest.serializer(), request.copy(code = input.code))
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(input.endpoint)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                val serverError = runCatching {
                    json.decodeFromString(EnrollmentErrorEnvelope.serializer(), responseBody).error
                }.getOrNull()
                throw EnrollmentException(
                    errorCode = serverError?.code ?: "http_${response.code}",
                    message = serverError?.message ?: "Enrollment failed (HTTP ${response.code})",
                    retryAfterSeconds = serverError?.retryAfterSeconds,
                    retryable = response.code == 429 || response.code >= 500
                )
            }
            val decoded = try {
                json.decodeFromString(EnrollmentResponse.serializer(), responseBody)
            } catch (e: Exception) {
                throw EnrollmentException("invalid_response", "Server returned an invalid enrollment response")
            }
            if (decoded.protocolVersion != 1) {
                throw EnrollmentException("unsupported_protocol", "Server returned an unsupported protocol version")
            }
            validateProfile(decoded.profile)
            return decoded.profile
        }
    }

    private fun validateProfile(profile: ConnectionProfile) {
        val deliveries = listOf(profile.defaultDelivery) +
            profile.capabilityOverrides.values.map { it.resolve(profile.defaultDelivery) }
        require(deliveries.all { delivery ->
            delivery.webhookUrls.all { url ->
                runCatching { URI(url) }.getOrNull()?.let {
                    it.scheme == "https" && it.host != null && it.userInfo == null
                } == true
            }
        }) { "Enrollment profile contains a non-HTTPS webhook URL" }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
