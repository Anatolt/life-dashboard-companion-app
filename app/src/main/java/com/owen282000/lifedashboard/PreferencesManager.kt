package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Keystore-backed storage for secrets (webhook headers with auth tokens, HMAC secrets). */
    private val securePrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore can be briefly unavailable right after boot; fall back to plain
        // prefs rather than crash so background syncs keep working.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        migrateSecretsToEncryptedStorage()
        migrateLegacyConnections()
    }

    /** One-time migration of secrets that older versions kept in plain SharedPreferences. */
    private fun migrateSecretsToEncryptedStorage() {
        if (securePrefs === prefs) return  // Keystore unavailable, nothing to migrate into
        val secretKeys = listOf(
            KEY_HEALTH_WEBHOOK_HEADERS, KEY_SCREENTIME_WEBHOOK_HEADERS,
            KEY_HEALTH_WEBHOOK_SECRET, KEY_SCREENTIME_WEBHOOK_SECRET,
            KEY_COMMON_WEBHOOK_HEADERS, KEY_COMMON_WEBHOOK_SECRET
        )
        for (key in secretKeys) {
            val plainValue = prefs.getString(key, null) ?: continue
            if (securePrefs.getString(key, null) == null) {
                securePrefs.edit().putString(key, plainValue).apply()
            }
            prefs.edit().remove(key).apply()
        }
    }

    fun getMqttSettings(): MqttSettings = MqttSettings(
        enabled = prefs.getBoolean(KEY_MQTT_ENABLED, false),
        host = prefs.getString(KEY_MQTT_HOST, "") ?: "",
        port = prefs.getInt(KEY_MQTT_PORT, 1883),
        useTls = prefs.getBoolean(KEY_MQTT_TLS, false),
        username = securePrefs.getString(KEY_MQTT_USERNAME, null)?.takeIf { it.isNotBlank() },
        password = securePrefs.getString(KEY_MQTT_PASSWORD, null)?.takeIf { it.isNotBlank() },
        baseTopic = prefs.getString(KEY_MQTT_BASE_TOPIC, MqttSupport.DEFAULT_BASE_TOPIC)
            ?.takeIf { it.isNotBlank() } ?: MqttSupport.DEFAULT_BASE_TOPIC
    )

    fun setMqttSettings(settings: MqttSettings) {
        prefs.edit()
            .putBoolean(KEY_MQTT_ENABLED, settings.enabled)
            .putString(KEY_MQTT_HOST, settings.host.trim())
            .putInt(KEY_MQTT_PORT, settings.port)
            .putBoolean(KEY_MQTT_TLS, settings.useTls)
            .putString(KEY_MQTT_BASE_TOPIC, settings.baseTopic.trim())
            .apply()
        securePrefs.edit()
            .putString(KEY_MQTT_USERNAME, settings.username ?: "")
            .putString(KEY_MQTT_PASSWORD, settings.password ?: "")
            .apply()
    }

    fun getLastMqttStatus(): String? = prefs.getString(KEY_MQTT_LAST_STATUS, null)

    fun setLastMqttStatus(status: String) {
        prefs.edit().putString(KEY_MQTT_LAST_STATUS, status).apply()
    }

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"
        private const val SECURE_PREFS_NAME = "life_dashboard_secure_prefs"

        // MQTT keys (username/password live in securePrefs)
        private const val KEY_INCLUDE_DAILY_TOTALS = "include_daily_totals"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_TLS = "mqtt_tls"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_BASE_TOPIC = "mqtt_base_topic"
        private const val KEY_MQTT_LAST_STATUS = "mqtt_last_status"

        // Health Connect keys
        private const val KEY_HEALTH_LAST_SYNC_TS_PREFIX = "health_last_sync_ts_"
        private const val KEY_HEALTH_SYNC_INTERVAL_MINUTES = "health_sync_interval_minutes"
        private const val KEY_HEALTH_WEBHOOK_URLS = "health_webhook_urls"
        private const val KEY_HEALTH_ENABLED_DATA_TYPES = "health_enabled_data_types"

        // Screen Time keys
        private const val KEY_SCREENTIME_LAST_SYNC_TS = "screentime_last_sync_ts"
        private const val KEY_SCREENTIME_SYNC_INTERVAL_MINUTES = "screentime_sync_interval_minutes"
        private const val KEY_SCREENTIME_WEBHOOK_URLS = "screentime_webhook_urls"
        private const val KEY_SCREENTIME_DAY_BOUNDARY_HOUR = "screentime_day_boundary_hour"
        private const val KEY_SCREENTIME_USE_DAY_BOUNDARY = "screentime_use_day_boundary"

        // Webhook header keys
        private const val KEY_HEALTH_WEBHOOK_HEADERS = "health_webhook_headers"
        private const val KEY_SCREENTIME_WEBHOOK_HEADERS = "screentime_webhook_headers"
        private const val KEY_HEALTH_WEBHOOK_SECRET = "health_webhook_secret"
        private const val KEY_SCREENTIME_WEBHOOK_SECRET = "screentime_webhook_secret"

        // Shared connection profile. Category keys above remain as optional overrides.
        private const val KEY_CONNECTION_MIGRATION_VERSION = "connection_migration_version"
        private const val KEY_CONNECTION_PROFILE_NAME = "connection_profile_name"
        private const val KEY_COMMON_WEBHOOK_URLS = "common_webhook_urls"
        private const val KEY_COMMON_WEBHOOK_HEADERS = "common_webhook_headers"
        private const val KEY_COMMON_WEBHOOK_SECRET = "common_webhook_secret"
        private const val KEY_HEALTH_CONNECTION_OVERRIDE = "health_connection_override"
        private const val KEY_SCREENTIME_CONNECTION_OVERRIDE = "screentime_connection_override"
        private const val KEY_INSTALLATION_ID = "installation_id"

        // Shared keys
        private const val KEY_WEBHOOK_LOGS = "webhook_logs"

        // Defaults
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        private const val DEFAULT_DAY_BOUNDARY_HOUR = 4
        private const val MAX_LOGS = 100
    }

    // ==================== Health Connect Settings ====================

    fun getHealthSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setHealthSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getHealthWebhookUrls(): List<String> {
        return if (isHealthConnectionOverrideEnabled()) getStoredUrls(KEY_HEALTH_WEBHOOK_URLS)
        else getCommonDelivery().webhookUrls
    }

    fun setHealthWebhookUrls(urls: List<String>) {
        setStoredUrls(KEY_HEALTH_WEBHOOK_URLS, urls)
        setHealthConnectionOverrideEnabled(true)
    }

    fun getHealthEnabledDataTypes(): Set<HealthDataType> {
        val typesString = prefs.getString(KEY_HEALTH_ENABLED_DATA_TYPES, "") ?: ""
        return if (typesString.isEmpty()) {
            emptySet()
        } else {
            typesString.split(",").mapNotNull {
                try { HealthDataType.valueOf(it) } catch (e: Exception) { null }
            }.toSet()
        }
    }

    fun setHealthEnabledDataTypes(types: Set<HealthDataType>) {
        val typesString = types.joinToString(",") { it.name }
        prefs.edit().putString(KEY_HEALTH_ENABLED_DATA_TYPES, typesString).apply()
    }

    fun getHealthLastSyncTimestamp(type: HealthDataType): Long? {
        val timestamp = prefs.getLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, -1)
        return if (timestamp == -1L) null else timestamp
    }

    fun setHealthLastSyncTimestamp(type: HealthDataType, timestamp: Long) {
        prefs.edit().putLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, timestamp).apply()
    }

    fun getHealthWebhookHeaders(): Map<String, String> {
        return if (isHealthConnectionOverrideEnabled()) getStoredHeaders(KEY_HEALTH_WEBHOOK_HEADERS)
        else getCommonDelivery().headers
    }

    fun setHealthWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_HEADERS, headersJson).apply()
        setHealthConnectionOverrideEnabled(true)
    }

    /** Daily deduplicated totals in the payload (aggregate API merges phone + watch). */
    fun includeDailyTotals(): Boolean = prefs.getBoolean(KEY_INCLUDE_DAILY_TOTALS, true)

    fun setIncludeDailyTotals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_DAILY_TOTALS, enabled).apply()
    }

    fun getHealthWebhookSecret(): String? {
        return if (isHealthConnectionOverrideEnabled()) getStoredSecret(KEY_HEALTH_WEBHOOK_SECRET)
        else getCommonDelivery().signingSecret
    }

    fun setHealthWebhookSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_HEALTH_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_SECRET, secret).apply()
        }
        setHealthConnectionOverrideEnabled(true)
    }

    // ==================== Screen Time Settings ====================

    fun getScreenTimeSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setScreenTimeSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getScreenTimeWebhookUrls(): List<String> {
        return if (isScreenTimeConnectionOverrideEnabled()) getStoredUrls(KEY_SCREENTIME_WEBHOOK_URLS)
        else getCommonDelivery().webhookUrls
    }

    fun setScreenTimeWebhookUrls(urls: List<String>) {
        setStoredUrls(KEY_SCREENTIME_WEBHOOK_URLS, urls)
        setScreenTimeConnectionOverrideEnabled(true)
    }

    fun getScreenTimeWebhookHeaders(): Map<String, String> {
        return if (isScreenTimeConnectionOverrideEnabled()) getStoredHeaders(KEY_SCREENTIME_WEBHOOK_HEADERS)
        else getCommonDelivery().headers
    }

    fun setScreenTimeWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_HEADERS, headersJson).apply()
        setScreenTimeConnectionOverrideEnabled(true)
    }

    fun getScreenTimeWebhookSecret(): String? {
        return if (isScreenTimeConnectionOverrideEnabled()) getStoredSecret(KEY_SCREENTIME_WEBHOOK_SECRET)
        else getCommonDelivery().signingSecret
    }

    fun setScreenTimeWebhookSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_SCREENTIME_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_SECRET, secret).apply()
        }
        setScreenTimeConnectionOverrideEnabled(true)
    }

    fun getScreenTimeLastSyncTimestamp(): Long? {
        val timestamp = prefs.getLong(KEY_SCREENTIME_LAST_SYNC_TS, -1)
        return if (timestamp == -1L) null else timestamp
    }

    fun setScreenTimeLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_SCREENTIME_LAST_SYNC_TS, timestamp).apply()
    }

    fun getScreenTimeDayBoundaryHour(): Int {
        return prefs.getInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, DEFAULT_DAY_BOUNDARY_HOUR)
    }

    fun setScreenTimeDayBoundaryHour(hour: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun useScreenTimeDayBoundary(): Boolean {
        return prefs.getBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, true)
    }

    fun setUseScreenTimeDayBoundary(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, enabled).apply()
    }

    // ==================== Shared Connection Profile ====================

    fun getConnectionProfileName(): String =
        prefs.getString(KEY_CONNECTION_PROFILE_NAME, "Life Dashboard") ?: "Life Dashboard"

    fun getInstallationId(): String {
        prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    fun getCommonDelivery(): DeliveryConfiguration = DeliveryConfiguration(
        webhookUrls = getStoredUrls(KEY_COMMON_WEBHOOK_URLS),
        headers = getStoredHeaders(KEY_COMMON_WEBHOOK_HEADERS),
        signingSecret = getStoredSecret(KEY_COMMON_WEBHOOK_SECRET)
    )

    fun getConnectionProfile(): ConnectionProfile {
        val overrides = buildMap {
            if (isHealthConnectionOverrideEnabled()) {
                put(ConnectionCapabilities.HEALTH, storedOverride(
                    KEY_HEALTH_WEBHOOK_URLS, KEY_HEALTH_WEBHOOK_HEADERS, KEY_HEALTH_WEBHOOK_SECRET
                ))
            }
            if (isScreenTimeConnectionOverrideEnabled()) {
                put(ConnectionCapabilities.SCREEN_TIME, storedOverride(
                    KEY_SCREENTIME_WEBHOOK_URLS, KEY_SCREENTIME_WEBHOOK_HEADERS,
                    KEY_SCREENTIME_WEBHOOK_SECRET
                ))
            }
        }
        return ConnectionProfile(getConnectionProfileName(), getCommonDelivery(), overrides)
    }

    /** Saves an enrollment result. Long-lived headers and signing secrets stay encrypted at rest. */
    fun applyEnrollmentProfile(profile: ConnectionProfile) {
        prefs.edit()
            .putString(KEY_CONNECTION_PROFILE_NAME, profile.name)
            .putString(KEY_COMMON_WEBHOOK_URLS, profile.defaultDelivery.webhookUrls.joinToString(","))
            .apply()
        setStoredHeaders(KEY_COMMON_WEBHOOK_HEADERS, profile.defaultDelivery.headers)
        setStoredSecret(KEY_COMMON_WEBHOOK_SECRET, profile.defaultDelivery.signingSecret)
        applyEnrolledCapability(
            profile, ConnectionCapabilities.HEALTH, KEY_HEALTH_CONNECTION_OVERRIDE,
            KEY_HEALTH_WEBHOOK_URLS, KEY_HEALTH_WEBHOOK_HEADERS, KEY_HEALTH_WEBHOOK_SECRET
        )
        applyEnrolledCapability(
            profile, ConnectionCapabilities.SCREEN_TIME, KEY_SCREENTIME_CONNECTION_OVERRIDE,
            KEY_SCREENTIME_WEBHOOK_URLS, KEY_SCREENTIME_WEBHOOK_HEADERS,
            KEY_SCREENTIME_WEBHOOK_SECRET
        )
    }

    fun isHealthConnectionOverrideEnabled(): Boolean =
        prefs.getBoolean(KEY_HEALTH_CONNECTION_OVERRIDE, false)

    fun setHealthConnectionOverrideEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HEALTH_CONNECTION_OVERRIDE, enabled).apply()
    }

    fun isScreenTimeConnectionOverrideEnabled(): Boolean =
        prefs.getBoolean(KEY_SCREENTIME_CONNECTION_OVERRIDE, false)

    fun setScreenTimeConnectionOverrideEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENTIME_CONNECTION_OVERRIDE, enabled).apply()
    }

    private fun migrateLegacyConnections() {
        if (prefs.getInt(KEY_CONNECTION_MIGRATION_VERSION, 0) >= 1) return
        val health = storedDelivery(
            KEY_HEALTH_WEBHOOK_URLS, KEY_HEALTH_WEBHOOK_HEADERS, KEY_HEALTH_WEBHOOK_SECRET
        )
        val screenTime = storedDelivery(
            KEY_SCREENTIME_WEBHOOK_URLS, KEY_SCREENTIME_WEBHOOK_HEADERS,
            KEY_SCREENTIME_WEBHOOK_SECRET
        )
        val plan = LegacyConnectionMigration.plan(health, screenTime)
        prefs.edit()
            .putString(KEY_COMMON_WEBHOOK_URLS, plan.common.webhookUrls.joinToString(","))
            .putBoolean(KEY_HEALTH_CONNECTION_OVERRIDE, plan.healthOverride)
            .putBoolean(KEY_SCREENTIME_CONNECTION_OVERRIDE, plan.screenTimeOverride)
            .apply()
        setStoredHeaders(KEY_COMMON_WEBHOOK_HEADERS, plan.common.headers)
        setStoredSecret(KEY_COMMON_WEBHOOK_SECRET, plan.common.signingSecret)
        prefs.edit().putInt(KEY_CONNECTION_MIGRATION_VERSION, 1).apply()
    }

    private fun applyEnrolledCapability(
        profile: ConnectionProfile,
        capability: String,
        flagKey: String,
        urlsKey: String,
        headersKey: String,
        secretKey: String
    ) {
        val override = profile.capabilityOverrides[capability]
        prefs.edit().putBoolean(flagKey, override != null).apply()
        if (override != null) {
            val resolved = override.resolve(profile.defaultDelivery)
            setStoredUrls(urlsKey, resolved.webhookUrls)
            setStoredHeaders(headersKey, resolved.headers)
            setStoredSecret(secretKey, resolved.signingSecret)
        }
    }

    private fun storedDelivery(urlsKey: String, headersKey: String, secretKey: String) =
        DeliveryConfiguration(getStoredUrls(urlsKey), getStoredHeaders(headersKey), getStoredSecret(secretKey))

    private fun storedOverride(urlsKey: String, headersKey: String, secretKey: String) =
        storedDelivery(urlsKey, headersKey, secretKey).let {
            DeliveryOverride(it.webhookUrls, it.headers, it.signingSecret ?: "")
        }

    private fun getStoredUrls(key: String): List<String> =
        (prefs.getString(key, "") ?: "").split(',').filter { it.isNotBlank() }

    private fun setStoredUrls(key: String, urls: List<String>) {
        prefs.edit().putString(key, urls.joinToString(",")).apply()
    }

    private fun getStoredHeaders(key: String): Map<String, String> {
        val value = securePrefs.getString(key, null) ?: return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())
    }

    private fun setStoredHeaders(key: String, headers: Map<String, String>) {
        securePrefs.edit().putString(key, Json.encodeToString(headers)).apply()
    }

    private fun getStoredSecret(key: String): String? =
        securePrefs.getString(key, null)?.takeIf { it.isNotBlank() }

    private fun setStoredSecret(key: String, secret: String?) {
        securePrefs.edit().putString(key, secret.orEmpty()).apply()
    }

    // ==================== Webhook Logs (Shared) ====================

    fun getWebhookLogs(filterType: LogType? = null): List<WebhookLog> {
        val logsJson = prefs.getString(KEY_WEBHOOK_LOGS, null) ?: return emptyList()
        return try {
            val allLogs = Json.decodeFromString<List<WebhookLog>>(logsJson)
            if (filterType != null) {
                allLogs.filter { it.logType == filterType.name }
            } else {
                allLogs
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addWebhookLog(log: WebhookLog) {
        val currentLogs = getWebhookLogs().toMutableList()
        currentLogs.add(0, log) // Add to beginning

        // Keep only the most recent MAX_LOGS entries
        val trimmedLogs = currentLogs.take(MAX_LOGS)

        val logsJson = Json.encodeToString(trimmedLogs)
        prefs.edit().putString(KEY_WEBHOOK_LOGS, logsJson).apply()
    }

    fun clearWebhookLogs(filterType: LogType? = null) {
        if (filterType == null) {
            prefs.edit().remove(KEY_WEBHOOK_LOGS).apply()
        } else {
            val currentLogs = getWebhookLogs().toMutableList()
            val filteredLogs = currentLogs.filter { it.logType != filterType.name }
            val logsJson = Json.encodeToString(filteredLogs)
            prefs.edit().putString(KEY_WEBHOOK_LOGS, logsJson).apply()
        }
    }
}
