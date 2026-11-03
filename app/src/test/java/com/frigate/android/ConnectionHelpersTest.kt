package com.frigate.android

import com.frigate.android.core.server.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionHelpersTest {
    @Test
    fun startupRetainsConfiguredFiltersAndUiTimezoneFromOneConfigRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"username":"test","role":"viewer"}"""))
            server.enqueue(MockResponse().setBody("""{
                "ui":{"timezone":"Asia/Kolkata"},
                "cameras":{
                    "yard":{"objects":{"track":["person","car"]},
                        "audio":{"enabled_in_config":true,"listen":["bark"]},
                        "review":{"alerts":{"required_zones":["driveway"]},
                            "detections":{"required_zones":["gate","driveway"]}}},
                    "plate":{"type":"lpr","objects":{"track":["car"]},
                        "audio":{"enabled_in_config":false,"listen":["speech"]}}
                }}"""))
            val profile = ServerProfile("Test", server.url("/").toString())
            val result = loadConnectedState(profile, FrigateClient(profile, "test=value"))
            assertEquals("Asia/Kolkata", result.uiTimezone)
            val catalog = com.frigate.android.feature.events.eventsCatalog(result.cameras, result.user.role)
            assertEquals(listOf("bark", "car", "license_plate", "person"), catalog.labels)
            assertEquals(listOf("driveway", "gate"), catalog.zones)
            val plate = com.frigate.android.feature.events.eventsCatalog(result.cameras, result.user.role, listOf("plate"))
            assertEquals(listOf("car", "license_plate"), plate.labels)
            assertTrue(plate.zones.isEmpty())
            assertEquals(2, server.requestCount)
            assertEquals("/api/profile", server.takeRequest().path)
            assertEquals("/api/config", server.takeRequest().path)
        }
    }

    @Test
    fun invalidOrAbsentUiTimezoneFallsBackToDeviceZone() {
        listOf(
            """{"cameras":{}}""",
            """{"cameras":{},"ui":{"timezone":"invalid/zone"}}""",
            """{"cameras":{},"ui":{"timezone":null}}""",
        ).forEach { body ->
            assertNull(FrigateApiParser.parseConfiguration(body).uiTimezone)
        }
    }

    @Test
    fun connectedStartupDoesNotDependOnLegacyEventsAvailability() = runBlocking {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(paths) { paths += request.path.orEmpty() }
                    return when (request.path) {
                        "/api/profile" -> MockResponse().setBody("""{"username":"test","role":"viewer"}""")
                        "/api/config" -> MockResponse().setBody("""{"cameras":{"yard":{"enabled":true}}}""")
                        else -> MockResponse().setResponseCode(503)
                    }
                }
            }
            val profile = ServerProfile("Test", server.url("/").toString())
            val result = loadConnectedState(profile, FrigateClient(profile, "test=value"))
            assertEquals("test",result.user.username)
            assertEquals(listOf("yard"),result.cameras.map { it.id })
            assertEquals(listOf("/api/profile","/api/config"), paths)
        }
    }
}
