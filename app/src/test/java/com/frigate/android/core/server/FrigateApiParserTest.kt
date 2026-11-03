package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrigateApiParserTest {
    @Test
    fun parsesConfigCamerasIncludingDisabledPlaceholders() {
        val config = """
            {"cameras": {
              "front_door": {"enabled": true, "friendly_name": "Front Door"},
              "garage": {"enabled": false, "friendly_name": "Garage"}
            }}
        """.trimIndent()

        val cameras = FrigateApiParser.parseCameras(config)

        assertEquals(2, cameras.size)
        assertEquals("front_door", cameras.first().id)
        assertEquals("Front Door", cameras.first().name)
        assertTrue(cameras.first().enabled)
        assertEquals(false, cameras.last().enabled)
    }

    @Test
    fun parsesDetectDimensionsForUncroppedPicture() {
        val camera = FrigateApiParser.parseCameras(
            """{"cameras":{"portrait":{"detect":{"width":720,"height":1280},"live":{"streams":{"low":"portrait_sub"}}}}}""",
        ).single()
        assertEquals(720, camera.detectWidth)
        assertEquals(1280, camera.detectHeight)
        assertEquals("portrait_sub", camera.streamName)
    }

    @Test
    fun parsesConfiguredLiveStreamName() {
        val config = """
            {"cameras": {
              "back_door1": {
                "enabled": true,
                "friendly_name": "Back door",
                "live": {"streams": {"main": "back_door1_main"}}
              }
            }}
        """.trimIndent()

        val cameras = FrigateApiParser.parseCameras(config)

        assertEquals("back_door1_main", cameras.single().streamName)
    }

    @Test
    fun keepsEveryLiveStreamInConfiguredOrder() {
        val camera = FrigateApiParser.parseCameras(
            """{"cameras":{"yard":{"live":{"streams":{"4K":"yard_main","Low":"yard_sub"}}}}}""",
        ).single()

        assertEquals(listOf("4K", "Low"), camera.streams.keys.toList())
        assertEquals(listOf("yard_main", "yard_sub"), camera.streams.values.toList())
        assertEquals("yard_main", camera.streamName)
    }

    @Test
    fun keepsRuntimeValuesSeparateFromConfiguredFeatureAvailability() {
        val camera = FrigateApiParser.parseCameras(
            """{"cameras":{"yard":{
              "enabled":false,"enabled_in_config":true,
              "record":{"enabled":false,"enabled_in_config":true},
              "snapshots":{"enabled":true},
              "audio":{"enabled":false,"enabled_in_config":true},
              "audio_transcription":{"enabled":false,"enabled_in_config":true},
              "onvif":{"host":"camera.internal","autotracking":{"enabled":false,"enabled_in_config":true}}
            }}}""",
        ).single()

        assertTrue(camera.enabledInConfig)
        assertTrue(camera.recordEnabledInConfig)
        assertTrue(camera.snapshotsEnabled)
        assertTrue(camera.audioEnabledInConfig)
        assertTrue(camera.transcriptionEnabledInConfig)
        assertTrue(camera.autotrackingEnabledInConfig)
        assertEquals(false, camera.audioEnabled)
        assertEquals(false, camera.transcriptionEnabled)
        assertEquals(false, camera.autotrackingEnabled)
        assertTrue(camera.hasOnvif)
    }

    @Test
    fun missingOptionalFeaturesDoNotInventCameraCapabilities() {
        val camera = FrigateApiParser.parseCameras("""{"cameras":{"yard":{}}}""").single()
        assertEquals(mapOf("yard" to "yard"), camera.streams)
        assertEquals(false, camera.audioEnabledInConfig)
        assertEquals(false, camera.transcriptionEnabledInConfig)
        assertEquals(false, camera.autotrackingEnabledInConfig)
        assertEquals(false, camera.hasOnvif)
    }

    @Test
    fun liveTranscriptionSwitchDoesNotUseManualTranscriptionFlag() {
        val camera = FrigateApiParser.parseCameras(
            """{"cameras":{"yard":{"audio_transcription":{"enabled":true,"live_enabled":false,"enabled_in_config":true}}}}""",
        ).single()
        assertEquals(false, camera.transcriptionEnabled)
        assertTrue(camera.transcriptionEnabledInConfig)
    }

    @Test
    fun parsesCameraRecordAndDetectSettings() {
        val config = """
            {"cameras": {
              "front_door": {
                "enabled": true,
                "record": {"enabled": true},
                "detect": {"enabled": false}
              }
            }}
        """.trimIndent()

        val camera = FrigateApiParser.parseCameras(config).single()

        assertTrue(camera.recordEnabled)
        assertEquals(false, camera.detectEnabled)
    }

    @Test
    fun parsesEventMetadataAndMediaFlags() {
        val events = """
            [{"id":"evt-1","camera":"front_door","label":"person",
              "start_time":1710000000,"end_time":1710000010,
              "has_clip":true,"has_snapshot":true}]
        """.trimIndent()

        val result = FrigateApiParser.parseEvents(events)

        assertEquals(1, result.size)
        assertEquals("evt-1", result.single().id)
        assertEquals("front_door", result.single().camera)
        assertEquals("person", result.single().label)
        assertTrue(result.single().hasClip)
        assertTrue(result.single().hasSnapshot)
    }

    @Test
    fun parsesEmptyCollectionsOngoingEventsDefaultsAndExtraFields() {
        assertTrue(FrigateApiParser.parseCameras("""{"cameras":{}}""").isEmpty())
        assertTrue(FrigateApiParser.parseEvents("[]").isEmpty())

        val event = FrigateApiParser.parseEvents(
            """[{"id":"evt-open","camera":"street","label":"person","start_time":1710000000.5,"end_time":null,"extra":"ignored"}]""",
        ).single()

        assertEquals("evt-open", event.id)
        assertEquals("street", event.camera)
        assertEquals(1710000000.5, event.startTime)
        assertEquals(null, event.endTime)
        assertEquals(false, event.hasClip)
        assertEquals(false, event.hasSnapshot)
    }

    @Test
    fun parsesReviewSegmentsWithUnixTimestampsAndObjects() {
        val body = """
            [{"id":"review-1","camera":"front_door","start_time":1710000000.5,"end_time":1710000010.25,
              "severity":"alert","thumb_path":"/media/frigate/clips/review/thumb-front_door-review-1.webp",
              "has_been_reviewed":false,
              "data":{"objects":["person","car"],"zones":["porch"],"audio":[],"detections":["evt-1"]}}]
        """.trimIndent()

        val review = FrigateApiParser.parseReviews(body).single()

        assertEquals("review-1", review.id)
        assertEquals("front_door", review.camera)
        assertEquals(1710000000.5, review.startTime)
        assertEquals(1710000010.25, review.endTime)
        assertEquals("alert", review.severity)
        assertEquals(listOf("person", "car"), review.objectLabels)
        assertEquals(listOf("porch"), review.zones)
        assertEquals(
            "/media/frigate/clips/review/thumb-front_door-review-1.webp",
            review.thumbnailPath,
        )
        assertEquals(false, review.hasBeenReviewed)
    }

    @Test
    fun keepsOngoingReviewWithoutEndTimeAndSkipsBrokenEntries() {
        val body = """
            [{"id":"ongoing","camera":"cam","start_time":1710000000,"end_time":null,"severity":"detection",
              "thumb_path":null,"has_been_reviewed":true,"data":{"objects":[]}},
             {"camera":"cam","start_time":1710000000,"severity":"alert"},
             {"id":"","camera":"cam","start_time":1710000000,"severity":"alert"}]
        """.trimIndent()

        val reviews = FrigateApiParser.parseReviews(body)

        assertEquals(1, reviews.size)
        assertEquals("ongoing", reviews.single().id)
        assertNull(reviews.single().endTime)
        assertNull(reviews.single().thumbnailPath)
        assertTrue(reviews.single().hasBeenReviewed)
        assertEquals(emptyList<String>(), reviews.single().objectLabels)
        assertEquals(emptyList<String>(), reviews.single().zones)
    }

    @Test
    fun parsesReviewObjectsAudioSubLabelsAndThreatLevel() {
        val body = """
            [{"id":"review-1","camera":"front_door","start_time":1710000000,"end_time":1710000010,
              "severity":"alert","has_been_reviewed":false,
              "data":{"objects":["person","car"],"audio":["speech"],"sub_labels":["person","adult"],
                      "zones":["porch"],"metadata":{"potential_threat_level":2}}}]
        """.trimIndent()

        val review = FrigateApiParser.parseReviews(body).single()

        assertEquals(listOf("person", "car"), review.objectLabels)
        assertEquals(listOf("speech"), review.audioLabels)
        assertEquals(listOf("person", "adult"), review.subLabels)
        assertEquals(listOf("porch"), review.zones)
        assertEquals(2, review.threatLevel)
    }

    @Test
    fun reviewWithoutThreatMetadataHasNoThreatLevel() {
        val body = """
            [{"id":"review-1","camera":"front_door","start_time":1710000000,"end_time":1710000010,
              "severity":"detection","data":{"objects":[],"metadata":{}}}]
        """.trimIndent()

        val review = FrigateApiParser.parseReviews(body).single()

        assertNull(review.threatLevel)
        assertEquals(emptyList<String>(), review.audioLabels)
        assertEquals(emptyList<String>(), review.subLabels)
    }

    @Test
    fun preservesDetectionIdsForMotionExclusion() {
        val review = FrigateApiParser.parseReviews("""[{"id":"r","camera":"yard","start_time":10,"data":{"detections":["object-1"],"metadata":{"title":"  Visitor  ","potential_threat_level":{}}}}]""").single()

        assertEquals(listOf("object-1"), review.detections)
        assertEquals("Visitor", review.title)
        assertNull(review.threatLevel)
    }

    @Test
    fun parsesReviewSummaryWithTheLastDayAndCalendarDays() {
        val body = """
            {"last24Hours":{"reviewed_alert":3,"reviewed_detection":8,"total_alert":12,"total_detection":34},
             "2026-11-01":{"day":"2026-11-01","reviewed_alert":1,"reviewed_detection":2,
                           "total_alert":5,"total_detection":9}}
        """.trimIndent()

        val summary = FrigateApiParser.parseReviewSummary(body)

        assertEquals(12, summary.last24Hours?.totalAlert)
        assertEquals(3, summary.last24Hours?.reviewedAlert)
        assertEquals(34, summary.last24Hours?.totalDetection)
        assertEquals(8, summary.last24Hours?.reviewedDetection)
        assertEquals(5, summary.days.getValue("2026-11-01").totalAlert)
        assertEquals(9, summary.days.getValue("2026-11-01").totalDetection)
        assertEquals(1, summary.days.getValue("2026-11-01").reviewedAlert)
        assertEquals(2, summary.days.getValue("2026-11-01").reviewedDetection)
    }

    @Test
    fun parsesEmptyReviewSummaryAsNoCounts() {
        val summary = FrigateApiParser.parseReviewSummary("{}")

        assertNull(summary.last24Hours)
        assertEquals(emptyMap<String, FrigateReviewCounts>(), summary.days)
    }

    @Test
    fun rejectsInvalidJsonAndUnexpectedRootShape() {
        assertThrows(RuntimeException::class.java) { FrigateApiParser.parseCameras("{") }
        assertThrows(RuntimeException::class.java) { FrigateApiParser.parseCameras("[]") }
        assertThrows(RuntimeException::class.java) { FrigateApiParser.parseEvents("{}") }
    }

    @Test
    fun parsesMotionActivityPoints() {
        val body = """[{"start_time":1710000000,"motion":42.5,"camera":"front_door"},
                       {"start_time":1710000015,"motion":0.0,"camera":"front_door"}]"""

        val points = FrigateApiParser.parseMotionActivity(body)

        assertEquals(2, points.size)
        assertEquals(1710000000.0, points.first().startTime, 0.001)
        assertEquals(42.5, points.first().motion, 0.001)
        assertEquals("front_door", points.first().camera)
    }

    @Test
    fun parsesUnavailableRecordingWindowsForTheRequestedCamera() {
        val body = """[{"start_time":1710000000,"end_time":1710000030}]"""

        val spans = FrigateApiParser.parseUnavailableRecordings(body, "front_door")

        assertEquals(1, spans.size)
        assertEquals("front_door", spans.single().camera)
        assertEquals(1710000000.0, spans.single().startTime, 0.001)
        assertEquals(1710000030.0, spans.single().endTime, 0.001)
    }

    @Test
    fun parsesRecordingCoverageSpansAndRealizedTimelines() {
        val body = """
            {"spans":[{"start_time":1710000000,"end_time":1710003600,"streams":["main","sub"]}],
             "codecs_compatible":true,
             "streams":{"main":{"video_codec":"h264"}},
             "timelines":{"auto":[{"start_time":1710000000,"end_time":1710003600,"duration":3605000}],
                          "main":[],"sub":[]}}
        """.trimIndent()

        val coverage = FrigateApiParser.parseRecordingCoverage(body)

        assertEquals(1, coverage.spans.size)
        assertEquals(listOf("main", "sub"), coverage.spans.single().streams)
        assertEquals(1710000000.0, coverage.spans.single().startTime, 0.001)
        assertEquals(1710003600.0, coverage.spans.single().endTime, 0.001)
        assertEquals(1, coverage.timelines.getValue("auto").size)
        assertEquals(3_605_000, coverage.timelines.getValue("auto").single().durationMs)
        assertEquals(emptyList<TimelineSpan>(), coverage.timelines.getValue("main"))
    }

    @Test
    fun parsesCoverageWithoutTimelinesAsAnEmptyTimeline() {
        val body = """{"spans":[],"codecs_compatible":true,"streams":{}}"""

        val coverage = FrigateApiParser.parseRecordingCoverage(body)

        assertEquals(emptyList<CoverageSpan>(), coverage.spans)
        assertEquals(emptyMap<String, List<TimelineSpan>>(), coverage.timelines)
    }
}
