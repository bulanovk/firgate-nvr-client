package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.FrigateReviewSummary
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.UrlComponents
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

internal interface EventsRepository {
    suspend fun load(query: EventsQuery): EventsSnapshot
    suspend fun markViewed(ids: List<String>, reviewed: Boolean)
    suspend fun delete(ids: List<String>)
    suspend fun previews(cameraIds: List<String>, range: EventsRange): List<ReviewPreview>
    suspend fun motion(query: EventsQuery): List<MotionPoint>
    suspend fun recordings(cameraId: String, range: EventsRange): List<MotionRecording>
    suspend fun previewFrames(cameraId: String, range: EventsRange): List<PreviewFrame>
    suspend fun exportSingle(review: FrigateReview, now: Double): ExportJob
    suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult
    suspend fun exports(): List<ExportJob>
    suspend fun exportCases(): List<ExportCase>
}

internal data class ReviewLoadPolicy(val pageSize: Int = 2_000, val maxDepth: Int = 24, val maxItems: Int = 20_000) {
    init { require(pageSize > 0 && maxDepth >= 0 && maxItems > 0) }
}
internal class EventsCapacityException : IOException("Too many events. Choose fewer cameras or a shorter date range.")

internal class ClientEventsRepository(
    private val client: FrigateClient,
    private val policy: ReviewLoadPolicy = ReviewLoadPolicy(),
) : EventsRepository {
    override suspend fun load(query: EventsQuery): EventsSnapshot = io {
        if (query.cameraIds.isEmpty()) return@io EventsSnapshot(emptyList(), FrigateReviewSummary(null, emptyMap()))
        val items = linkedMapOf<String, FrigateReview>()
        suspend fun collect(after: Double, before: Double, depth: Int) {
            currentCoroutineContext().ensureActive()
            val page = client.reviews(query.cameraIds, limit = policy.pageSize, before = before, after = after,
                labels = query.labels, zones = query.zones)
            if (page.size >= policy.pageSize) {
                if (depth >= policy.maxDepth || before - after < 0.01) throw EventsCapacityException()
                val middle = (after + before) / 2
                collect(after, middle + 0.001, depth + 1)
                collect(middle - 0.001, before, depth + 1)
            } else {
                page.forEach { items[it.id] = it }
                if (items.size > policy.maxItems) throw EventsCapacityException()
            }
        }
        collect(query.range.after, query.range.before, 0)
        currentCoroutineContext().ensureActive()
        EventsSnapshot(items.values.sortedByDescending { it.startTime }, client.reviewSummary(query.cameraIds, query.timezone, query.labels, query.zones))
    }

    override suspend fun markViewed(ids: List<String>, reviewed: Boolean) = io {
        if (ids.isNotEmpty()) client.apiJson("POST", "reviews/viewed", idsBody(ids).apply { addProperty("reviewed", reviewed) })
        Unit
    }

    override suspend fun delete(ids: List<String>) = io {
        if (ids.isNotEmpty()) client.apiJson("POST", "reviews/delete", idsBody(ids))
        Unit
    }

    override suspend fun previews(cameraIds: List<String>, range: EventsRange): List<ReviewPreview> = io {
        if (cameraIds.isEmpty()) return@io emptyList()
        val camera = if (cameraIds.size == 1) encode(cameraIds.single()) else "all"
        try {
            client.apiJson("GET", "preview/$camera/start/${range.after.roundToLong()}/end/${range.before.roundToLong()}").asJsonArray.mapNotNull {
                val value = it.asJsonObject
                val src = value.text("src") ?: return@mapNotNull null
                val id = value.text("camera") ?: return@mapNotNull null
                val start = value.number("start") ?: return@mapNotNull null
                val end = value.number("end") ?: return@mapNotNull null
                if (id !in cameraIds || end <= start || eventsMediaUrl(client.server, src) == null) return@mapNotNull null
                ReviewPreview(id, start, end, src)
            }
        } catch (error: FrigateHttpException) {
            if (error.code == 404) emptyList() else throw error
        }
    }

    override suspend fun motion(query: EventsQuery): List<MotionPoint> = io {
        if (query.cameraIds.isEmpty()) emptyList() else client.reviewMotion(query.cameraIds.joinToString(","),
            ceil(query.range.before / 30) * 30, floor(query.range.after / 30) * 30, 15)
    }

    override suspend fun recordings(cameraId: String, range: EventsRange): List<MotionRecording> = io {
        client.apiJson("GET", "${encode(cameraId)}/recordings?after=${floor(range.after).toLong()}&before=${ceil(range.before).toLong()}").asJsonArray.mapNotNull {
            val value = it.asJsonObject
            val start = value.number("start_time") ?: return@mapNotNull null
            val end = value.number("end_time") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val heatmap = value.get("motion_heatmap")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?.entrySet()?.mapNotNull { (key, intensity) ->
                    val index = key.toIntOrNull()?.takeIf { cell -> cell in 0..255 } ?: return@mapNotNull null
                    val level = intensity.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber }?.asInt ?: return@mapNotNull null
                    index to level.coerceIn(0, 255)
                }?.toMap().orEmpty()
            MotionRecording(start, end, heatmap)
        }
    }

    override suspend fun previewFrames(cameraId: String, range: EventsRange): List<PreviewFrame> = io {
        val prefix = "preview_$cameraId-"
        client.apiJson("GET", "preview/${encode(cameraId)}/start/${floor(range.after).toLong()}/end/${ceil(range.before).toLong()}/frames").asJsonArray.mapNotNull {
            val name = it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString ?: return@mapNotNull null
            if (!name.startsWith(prefix) || !name.endsWith(".webp")) return@mapNotNull null
            val time = name.removePrefix(prefix).removeSuffix(".webp").toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val src = "api/preview/$name/thumbnail.webp"
            if (eventsMediaUrl(client.server, src) == null) return@mapNotNull null
            PreviewFrame(cameraId, time, src)
        }.sortedBy { it.timestamp }
    }

    override suspend fun exportSingle(review: FrigateReview, now: Double): ExportJob = io {
        val start = review.startTime - 4
        val end = review.endTime?.plus(4) ?: now
        require(end > start && start.isFinite() && end.isFinite())
        val body = JsonObject().apply {
            addProperty("playback", "realtime")
            review.thumbnailPath?.let { addProperty("image_path", it) }
        }
        val result = client.apiJson("POST", "export/${encode(review.camera)}/start/${stamp(start)}/end/${stamp(end)}", body).asJsonObject
        val id = result.text("export_id") ?: throw IOException("Server did not queue an export")
        ExportJob(id, result.text("status") ?: "queued")
    }

    override suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult = io {
        require(reviews.size in 1..50 && reviews.map { it.id }.distinct().size == reviews.size)
        val body = JsonObject().apply {
            add("items", JsonArray().apply { reviews.forEach { review ->
                val end = (review.endTime ?: now) + 4
                require(end > review.startTime - 4 && end.isFinite())
                add(JsonObject().apply {
                    addProperty("camera", review.camera)
                    addProperty("start_time", review.startTime - 4)
                    addProperty("end_time", end)
                    addProperty("client_item_id", review.id)
                    review.thumbnailPath?.let { addProperty("image_path", it) }
                    review.title?.trim()?.takeIf(String::isNotEmpty)?.let { addProperty("friendly_name", it) }
                })
            } })
            case?.id?.let { addProperty("export_case_id", it) }
            case?.name?.let { addProperty("new_case_name", it.trim()) }
            case?.description?.let { addProperty("new_case_description", it.trim()) }
        }
        val result = client.apiJson("POST", "exports/batch", body).asJsonObject
        val succeeded = linkedMapOf<String, ExportJob>()
        val failed = linkedMapOf<String, String>()
        result.getAsJsonArray("results").forEach { item ->
            val value = item.asJsonObject
            val id = value.text("client_item_id") ?: value.number("item_index")?.toInt()?.let { reviews.getOrNull(it)?.id } ?: return@forEach
            if (reviews.none { it.id == id }) return@forEach
            val job = value.text("export_id")
            if (value.get("success")?.asBoolean == true && !job.isNullOrBlank()) {
                succeeded[id] = ExportJob(job, value.text("status") ?: "queued")
            } else failed[id] = value.text("error") ?: "Export failed"
        }
        reviews.filterNot { it.id in succeeded || it.id in failed }.forEach { failed[it.id] = "No result returned" }
        ExportBatchResult(succeeded, failed)
    }

    override suspend fun exports(): List<ExportJob> = io {
        client.apiJson("GET", "exports").asJsonArray.map { item ->
            val value = item.asJsonObject
            val path = value.text("video_path")?.takeIf { it.startsWith("/media/frigate/exports/") }
                ?.removePrefix("/media/frigate/")?.takeIf { eventsMediaUrl(client.server, it) != null }
            val progress = value.get("in_progress")?.asBoolean ?: true
            ExportJob(value.text("id") ?: throw IOException("Invalid export"), if (progress) "running" else "completed", if (progress) null else path)
        }
    }

    override suspend fun exportCases(): List<ExportCase> = io {
        client.apiJson("GET", "cases").asJsonArray.map { item ->
            val value = item.asJsonObject
            ExportCase(value.text("id") ?: throw IOException("Invalid export case"), value.text("name").orEmpty(), value.text("description"))
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        block().also { currentCoroutineContext().ensureActive() }
    }
    private fun idsBody(ids: List<String>) = JsonObject().apply { add("ids", JsonArray().apply { ids.distinct().forEach { add(it) } }) }
    private fun encode(value: String) = UrlComponents.encodeComponent(value)
    private fun stamp(value: Double) = UrlComponents.formatTimestamp(value)
    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.number(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf(Double::isFinite)
}
