package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrigateClientTest {
    @Test
    fun manualRecordingUsesAuthenticatedCreateAndEndRequests() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true,"event_id":"event-1"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true}"""))
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")
            assertEquals("event-1", client.startManualRecording("yard"))
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/frigate/api/events/yard/on_demand/create", create.path)
            assertEquals("test_cookie=value", create.getHeader("Cookie"))
            assertEquals("1", create.getHeader("X-CSRF-TOKEN"))
            assertEquals("""{"include_recording":true,"duration":null}""", create.body.readUtf8())
            client.endManualRecording("event-1", 1234L)
            val end = server.takeRequest()
            assertEquals("PUT", end.method)
            assertEquals("/frigate/api/events/event-1/end", end.path)
            assertEquals("""{"end_time":1234}""", end.body.readUtf8())
        }
    }

    @Test
    fun manualRecordingRejectsHttpAndApplicationFailures() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":false}"""))
            val client = FrigateClient(ServerProfile("Test", server.url("/").toString()))
            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) { client.startManualRecording("yard") }
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) { client.startManualRecording("yard") }
        }
    }

    @Test
    fun streamCapabilitiesComeFromProducersNotConsumers() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"producers":[{"medias":["video, recvonly, H264","audio, recvonly, AAC","audio, sendonly, PCMA"]}],"consumers":[]}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"producers":[],"consumers":[{"medias":["audio, recvonly, AAC"]}]}"""))
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")
            val info = client.liveStreamInfo("yard main")
            assertTrue(info.audioOutput)
            assertTrue(info.twoWayAudio)
            val request = server.takeRequest()
            assertEquals("/frigate/api/go2rtc/streams/yard%20main", request.path)
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
            assertEquals(false, client.liveStreamInfo("silent").audioOutput)
        }
    }

    @Test
    fun loadsInitialStatsUnderBasePathWithSession() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"service":{"uptime":70},"cameras":{}}"""))
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")
            assertTrue(client.stats().contains("uptime"))
            val request = server.takeRequest()
            assertEquals("/frigate/api/stats", request.path)
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun statsDoesNotForwardSessionThroughRedirect() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            val client = FrigateClient(ServerProfile("Test", server.url("/").toString()), "test_cookie=value")
            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) { client.stats() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun buildsApiUrlUnderBasePath() {
        val client = FrigateClient(ServerProfile("Home", "https://frigate.example.com/frigate"))

        assertEquals(
            "https://frigate.example.com/frigate/api/events?limit=50",
            client.apiUrl("events?limit=50"),
        )
    }

    @Test
    fun extractsCookieHeaderFromSetCookie() {
        val client = FrigateClient(ServerProfile("Home", "https://frigate.example.com"))

        client.acceptSetCookie("frigate_token=abc; Path=/; HttpOnly")

        assertTrue(client.cookieHeader().contains("frigate_token=abc"))
    }

    @Test
    fun copiesSessionCookieToClientUsedForRefresh() {
        val loggedInClient = FrigateClient(ServerProfile("Home", "https://frigate.example.com"))
        loggedInClient.acceptSetCookie("frigate_token=abc; Path=/; HttpOnly")

        val refreshClient = FrigateClient(
            server = ServerProfile("Home", "https://frigate.example.com"),
            sessionCookie = loggedInClient.cookieHeader(),
        )

        assertEquals(loggedInClient.cookieHeader(), refreshClient.cookieHeader())
    }

    @Test
    fun loadsReviewsWithCameraFilterTimeRangeAndSessionCookie() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"id":"r1","camera":"front_door","start_time":1710000000,"end_time":null,
                       "severity":"alert","thumb_path":"/media/frigate/clips/review/thumb-front_door-r1.webp",
                       "has_been_reviewed":false,"data":{"objects":["person"]}}]""",
                ),
            )
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            val reviews = client.reviews(
                cameraIds = listOf("front_door", "side_door"),
                severity = "alert",
                reviewed = 0,
                limit = 10,
                before = 1710003600.0,
                after = 1710000000.0,
            )

            assertEquals(1, reviews.size)
            assertEquals("r1", reviews.single().id)
            assertEquals(listOf("person"), reviews.single().objectLabels)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/review?cameras=front_door%2Cside_door&severity=alert&reviewed=0&limit=10&before=1710003600&after=1710000000",
                request.path,
            )
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun doesNotRequestReviewsWithoutCameras() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val client = FrigateClient(ServerProfile("Test", server.url("/").toString()), "test_cookie=value")

            assertTrue(client.reviews(cameraIds = emptyList()).isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun marksReviewsViewedWithIdsAndReviewedFlag() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true}"""))
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            client.markReviewsViewed(listOf("r1", "r2"))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/frigate/api/reviews/viewed", request.path)
            assertEquals("""{"ids":["r1","r2"],"reviewed":true}""", request.body.readUtf8())
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun reviewsRejectUnauthorizedAndDoNotFollowRedirects() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]"))
            val client = FrigateClient(ServerProfile("Test", server.url("/").toString()), "test_cookie=value")

            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) {
                client.reviews(cameraIds = listOf("front_door"))
            }
            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) {
                client.reviews(cameraIds = listOf("front_door"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun reviewQueryAddsLabelsAndZonesOnlyWhenTheyAreChosen() {
        val client = FrigateClient(ServerProfile("Home", "https://frigate.example.com"))

        assertEquals(
            "cameras=front_door%2Cside_door&labels=person%2Ccar&zones=porch&before=1710003600&after=1710000000",
            client.reviewQuery(
                cameraIds = listOf("front_door", "side_door"),
                labels = listOf("person", "car"),
                zones = listOf("porch"),
                before = 1710003600.0,
                after = 1710000000.0,
            ),
        )

        assertEquals(
            "cameras=front_door&before=1710003600&after=1710000000",
            client.reviewQuery(
                cameraIds = listOf("front_door"),
                before = 1710003600.0,
                after = 1710000000.0,
            ),
        )
    }

    @Test
    fun reviewSummaryQueryCarriesTheTimezoneAndTheSameFilters() {
        val client = FrigateClient(ServerProfile("Home", "https://frigate.example.com"))

        assertEquals(
            "cameras=front_door%2Cside_door&labels=person&zones=porch&timezone=Europe%2FBerlin",
            client.reviewSummaryQuery(
                cameraIds = listOf("front_door", "side_door"),
                labels = listOf("person"),
                zones = listOf("porch"),
                timezone = "Europe/Berlin",
            ),
        )

        assertEquals(
            "cameras=front_door&timezone=Europe%2FBerlin",
            client.reviewSummaryQuery(
                cameraIds = listOf("front_door"),
                timezone = "Europe/Berlin",
            ),
        )
    }

    @Test
    fun loadsReviewSummaryWithTimezoneAndSessionCookie() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """{"last24Hours":{"reviewed_alert":1,"reviewed_detection":2,"total_alert":3,"total_detection":4}}""",
                ),
            )
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            val summary = client.reviewSummary(
                cameraIds = listOf("front_door"),
                timezone = "Europe/Berlin",
            )

            assertEquals(3, summary.last24Hours?.totalAlert)
            assertEquals(4, summary.last24Hours?.totalDetection)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/review/summary?cameras=front_door&timezone=Europe%2FBerlin",
                request.path,
            )
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun loadsMotionActivityWithScaleAndSessionCookie() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"start_time":1710000000,"motion":42.5,"camera":"front_door"}]""",
                ),
            )
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            val points = client.reviewMotion(
                camera = "front_door",
                before = 1710003600.0,
                after = 1710000000.0,
                scale = 15,
            )

            assertEquals(1, points.size)
            assertEquals("front_door", points.single().camera)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/review/activity/motion?cameras=front_door&before=1710003600&after=1710000000&scale=15",
                request.path,
            )
            assertEquals("test_cookie=value", request.getHeader("Cookie"))
        }
    }

    @Test
    fun loadsUnavailableRecordingWindows() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody("""[{"start_time":1710000000,"end_time":1710000030}]"""),
            )
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            val spans = client.unavailableRecordings(
                camera = "front_door",
                before = 1710003600.0,
                after = 1710000000.0,
                scale = 30,
            )

            assertEquals("front_door", spans.single().camera)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/recordings/unavailable?cameras=front_door&before=1710003600&after=1710000000&scale=30",
                request.path,
            )
        }
    }

    @Test
    fun loadsRecordingCoverageWithTimelines() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """{"spans":[{"start_time":1710000000,"end_time":1710003600,"streams":["main"]}],
                        "codecs_compatible":true,"streams":{},
                        "timelines":{"auto":[{"start_time":1710000000,"end_time":1710003600,"duration":3600000}],
                                     "main":[],"sub":[]}}""",
                ),
            )
            val client = FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "test_cookie=value")

            val coverage = client.recordingCoverage(
                camera = "front_door",
                before = 1710003600.0,
                after = 1710000000.0,
            )

            assertEquals(listOf("main"), coverage.spans.single().streams)
            assertEquals(1, coverage.timelines.getValue("auto").size)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/front_door/recordings/coverage?before=1710003600&after=1710000000&timelines=true",
                request.path,
            )
        }
    }

    @Test
    fun archiveRequestsRejectUnauthorizedResponses() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            repeat(3) {
                server.enqueue(
                    okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}"""),
                )
            }
            val client = FrigateClient(ServerProfile("Test", server.url("/").toString()), "test_cookie=value")

            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) {
                client.reviewMotion("front_door", 1710003600.0, 1710000000.0, 15)
            }
            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) {
                client.unavailableRecordings("front_door", 1710003600.0, 1710000000.0, 30)
            }
            org.junit.jupiter.api.Assertions.assertThrows(FrigateHttpException::class.java) {
                client.recordingCoverage("front_door", 1710003600.0, 1710000000.0)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun readsTheBodyOfAnAnswer() {
        val body = readBoundedBody(java.io.StringReader("""{"uptime":70}"""), limit = 64)

        assertEquals("""{"uptime":70}""", body)
    }

    @Test
    fun refusesAnAnswerThatIsBiggerThanTheLimit() {
        // A body that is cut in the middle would be parsed as a broken answer and would hide the
        // real reason of the failure.
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
            readBoundedBody(java.io.StringReader("x".repeat(100)), limit = 16)
        }
    }
}
