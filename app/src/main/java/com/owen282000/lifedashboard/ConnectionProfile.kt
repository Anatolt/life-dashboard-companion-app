package com.owen282000.lifedashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A reusable delivery configuration shared by every data capability. */
@Serializable
data class DeliveryConfiguration(
    @SerialName("webhook_urls") val webhookUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    @SerialName("signing_secret") val signingSecret: String? = null
)

/**
 * Fields omitted from a capability override inherit the default. Present fields replace the
 * corresponding default field as a whole; an empty list/map/string explicitly clears it.
 */
@Serializable
data class DeliveryOverride(
    @SerialName("webhook_urls") val webhookUrls: List<String>? = null,
    val headers: Map<String, String>? = null,
    @SerialName("signing_secret") val signingSecret: String? = null
) {
    fun resolve(default: DeliveryConfiguration): DeliveryConfiguration = DeliveryConfiguration(
        webhookUrls = webhookUrls ?: default.webhookUrls,
        headers = headers ?: default.headers,
        signingSecret = signingSecret ?: default.signingSecret
    )
}

@Serializable
data class ConnectionProfile(
    val name: String = "Life Dashboard",
    @SerialName("default_delivery") val defaultDelivery: DeliveryConfiguration,
    @SerialName("capability_overrides") val capabilityOverrides: Map<String, DeliveryOverride> = emptyMap()
) {
    fun resolve(capability: String): DeliveryConfiguration =
        capabilityOverrides[capability]?.resolve(defaultDelivery) ?: defaultDelivery
}

object ConnectionCapabilities {
    const val HEALTH = "health"
    const val SCREEN_TIME = "screen_time"
    const val DEVICE_BATTERY = "device_battery"
}

data class LegacyConnectionMigrationResult(
    val common: DeliveryConfiguration,
    val healthOverride: Boolean,
    val screenTimeOverride: Boolean
)

/** Pure migration policy, separated so preservation of old installs is unit-testable. */
object LegacyConnectionMigration {
    fun plan(
        health: DeliveryConfiguration,
        screenTime: DeliveryConfiguration
    ): LegacyConnectionMigrationResult {
        val healthConfigured = health.isConfigured()
        val screenTimeConfigured = screenTime.isConfigured()
        return if (healthConfigured && health == screenTime) {
            LegacyConnectionMigrationResult(health, healthOverride = false, screenTimeOverride = false)
        } else {
            LegacyConnectionMigrationResult(
                common = DeliveryConfiguration(),
                healthOverride = healthConfigured,
                screenTimeOverride = screenTimeConfigured
            )
        }
    }

    private fun DeliveryConfiguration.isConfigured(): Boolean =
        webhookUrls.isNotEmpty() || headers.isNotEmpty() || !signingSecret.isNullOrBlank()
}
