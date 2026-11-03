package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsRepositoryTest {
    private fun query() = EventsQuery(listOf("yard"), EventsRange(100.0, 200.0, null), "Europe/Berlin")
    private fun repository(server: MockWebServer, policy: ReviewLoadPolicy = ReviewLoadPolicy()) =
        ClientEventsRepository(FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test=value"), policy)

    @Test
    fun loadUsesBoundedReviewsAndIndependentSummary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"r","camera":"yard","start_time":120,"severity":"alert"}]"""))
            server.enqueue(MockResponse().setBody("""{"last24Hours":{"total_alert":12,"reviewed_alert":3}}"""))
            val snapshot = repository(server).load(query())
            assertEquals(listOf("r"), snapshot.items.map { it.id })
            assertEquals(12, snapshot.summary.last24Hours?.totalAlert)
            val list = server.takeRequest()
            assertEquals("/frigate/api/review?cameras=yard&limit=2000&before=200&after=100", list.path)
            assertEquals("test=value", list.getHeader("Cookie"))
            assertEquals("/frigate/api/review/summary?cameras=yard&timezone=Europe%2FBerlin", server.takeRequest().path)
        }
    }

    @Test
    fun overlappingWindowDeduplicatesBoundaryIds() = runBlocking {
        MockWebServer().use { server ->
            val two = """[{"id":"r1","camera":"yard","start_time":140},{"id":"r2","camera":"yard","start_time":160}]"""
            val one = """[{"id":"r1","camera":"yard","start_time":140}]"""
            server.enqueue(MockResponse().setBody(two))
            server.enqueue(MockResponse().setBody(one))
            server.enqueue(MockResponse().setBody("""[{"id":"r2","camera":"yard","start_time":160}]"""))
            server.enqueue(MockResponse().setBody("{}"))
            val snapshot = repository(server, ReviewLoadPolicy(pageSize = 2)).load(query())
            assertEquals(setOf("r1", "r2"), snapshot.items.map { it.id }.toSet())
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun denseTimestampReportsCapacity() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""[{"id":"r","camera":"yard","start_time":140}]""")) }
            assertThrows(EventsCapacityException::class.java) {
                runBlocking { repository(server, ReviewLoadPolicy(pageSize = 1, maxDepth = 1)).load(query()) }
            }
        }
    }

    @Test
    fun previewMetadataAndHeatmapUseActualServerFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"camera":"yard","src":"/clips/previews/yard.mp4","type":"video/mp4","start":100,"end":200}]"""))
            server.enqueue(MockResponse().setBody("""[{"start_time":110,"end_time":120,"motion_heatmap":{"0":255,"255":30}}]"""))
            server.enqueue(MockResponse().setBody("""["preview_yard-150.webp"]"""))
            val repo = repository(server)
            assertEquals("/clips/previews/yard.mp4", repo.previews(listOf("yard"), query().range).single().src)
            assertEquals("/frigate/api/preview/yard/start/100/end/200", server.takeRequest().path)
            assertEquals(mapOf(0 to 255, 255 to 30), repo.recordings("yard", query().range).single().heatmap)
            assertEquals("/frigate/api/yard/recordings?after=100&before=200", server.takeRequest().path)
            assertEquals(150.0, repo.previewFrames("yard", query().range).single().timestamp)
        }
    }

    @Test
    fun batchRetainsFailedItemsAndPaddingMatchesPwa() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"export_ids":["job1"],"results":[{"camera":"yard","export_id":"job1","success":true,"status":"queued","client_item_id":"r1","item_index":0},{"camera":"yard","success":false,"error":"missing recording","client_item_id":"r2","item_index":1}]}"""))
            val result = repository(server).exportBatch(listOf(review("r1"), review("r2", null)), 180.0, null)
            assertEquals("queued", result.succeeded.getValue("r1").status)
            assertNull(result.succeeded.getValue("r1").downloadPath)
            assertEquals("missing recording", result.failed["r2"])
            val request = server.takeRequest()
            assertEquals("/frigate/api/exports/batch", request.path)
            assertEquals("1", request.getHeader("X-CSRF-TOKEN"))
            val items = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("items")
            assertEquals(116.0, items[0].asJsonObject["start_time"].asDouble)
            assertEquals(164.0, items[0].asJsonObject["end_time"].asDouble)
            assertEquals(184.0, items[1].asJsonObject["end_time"].asDouble)
        }
    }

    @Test
    fun singleOngoingExportEndsNowAndCompletedListMapsVideoPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"success":true,"export_id":"job","status":"queued"}"""))
            server.enqueue(MockResponse().setBody("""[{"id":"job","camera":"yard","name":"Clip","date":200,"video_path":"/media/frigate/exports/yard.mp4","thumb_path":"","in_progress":false}]"""))
            val repo = repository(server)
            assertEquals("queued", repo.exportSingle(review("r", null), 180.0).status)
            assertEquals("/frigate/api/export/yard/start/116/end/180", server.takeRequest().path)
            assertEquals("exports/yard.mp4", repo.exports().single().downloadPath)
        }
    }

    @Test
    fun mutationsRejectApplicationErrorsUnauthorizedAndRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            val repo = repository(server)
            assertThrows(java.io.IOException::class.java) { runBlocking { repo.delete(listOf("r")) } }
            assertEquals(401, assertThrows(FrigateHttpException::class.java) { runBlocking { repo.markViewed(listOf("r"), true) } }.code)
            assertThrows(FrigateHttpException::class.java) { runBlocking { repo.exports() } }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun oversizedJsonIsRejectedAndCasesPreserveServerFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[" + " ".repeat(16 * 1024 * 1024) + "]"))
            assertThrows(java.io.IOException::class.java) { runBlocking { repository(server).exports() } }
            server.enqueue(MockResponse().setBody("""[{"id":"case-1","name":"Visitors","description":"Door"}]"""))
            assertEquals(listOf(ExportCase("case-1", "Visitors", "Door")), repository(server).exportCases())
        }
    }

    @Test
    fun emptyCamerasAndCancelledCallsDoNotRequestServer() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server)
            assertTrue(repo.load(query().copy(cameraIds = emptyList())).items.isEmpty())
            assertTrue(repo.previews(emptyList(), query().range).isEmpty())
            assertTrue(repo.motion(query().copy(cameraIds = emptyList())).isEmpty())
            val job = kotlinx.coroutines.Job().apply { cancel() }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking(job) { repo.exportCases() }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun forbiddenAndMaliciousPreviewAreNotSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(403, assertThrows(FrigateHttpException::class.java) {
                runBlocking { repository(server).delete(listOf("r")) }
            }.code)
            server.enqueue(MockResponse().setBody("""[{"camera":"yard","src":"https://other.test/video.mp4","start":100,"end":200}]"""))
            assertTrue(repository(server).previews(listOf("yard"), query().range).isEmpty())
        }
    }

    private fun review(id: String, end: Double? = 160.0) =
        FrigateReview(id, "yard", 120.0, end, "alert", false, null, listOf("person"))
}
