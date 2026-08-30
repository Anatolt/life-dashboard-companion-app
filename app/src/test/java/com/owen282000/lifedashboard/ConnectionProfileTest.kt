package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun `capability override inherits omitted fields and replaces present fields`() {
        val default = DeliveryConfiguration(
            webhookUrls = listOf("https://example.test/default"),
            headers = mapOf("Authorization" to "Bearer default"),
            signingSecret = "default-secret"
        )
        val resolved = DeliveryOverride(
            webhookUrls = listOf("https://example.test/health"),
            headers = emptyMap(),
            signingSecret = ""
        ).resolve(default)

        assertEquals(listOf("https://example.test/health"), resolved.webhookUrls)
        assertEquals(emptyMap<String, String>(), resolved.headers)
        assertEquals("", resolved.signingSecret)
    }

    @Test
    fun `response contract decodes omitted override fields as inherited`() {
        val response = Json.decodeFromString<EnrollmentResponse>(
            """{
              "protocol_version": 1,
              "profile": {
                "name": "Home",
                "default_delivery": {
                  "webhook_urls": ["https://example.test/ingest"],
                  "headers": {"Authorization": "Bearer token"},
                  "signing_secret": "secret"
                },
                "capability_overrides": {
                  "screen_time": {"webhook_urls": ["https://example.test/screen-time"]}
                }
              }
            }"""
        )

        val resolved = response.profile.resolve(ConnectionCapabilities.SCREEN_TIME)
        assertEquals(listOf("https://example.test/screen-time"), resolved.webhookUrls)
        assertEquals(mapOf("Authorization" to "Bearer token"), resolved.headers)
        assertEquals("secret", resolved.signingSecret)
    }

    @Test
    fun `identical legacy settings become the common profile`() {
        val old = DeliveryConfiguration(listOf("https://example.test/hook"), mapOf("X-Key" to "v"), "s")
        val plan = LegacyConnectionMigration.plan(old, old)

        assertEquals(old, plan.common)
        assertFalse(plan.healthOverride)
        assertFalse(plan.screenTimeOverride)
    }

    @Test
    fun `different legacy settings remain category overrides`() {
        val health = DeliveryConfiguration(listOf("https://example.test/health"))
        val screen = DeliveryConfiguration(listOf("https://example.test/screen"))
        val plan = LegacyConnectionMigration.plan(health, screen)

        assertEquals(DeliveryConfiguration(), plan.common)
        assertTrue(plan.healthOverride)
        assertTrue(plan.screenTimeOverride)
    }
}
