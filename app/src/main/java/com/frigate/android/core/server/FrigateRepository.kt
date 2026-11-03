package com.frigate.android.core.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Review segments of the Live carousel and of the History screen. */
interface ReviewRepository {
    suspend fun loadReviews(
        cameraIds: List<String>,
        severity: String? = null,
        reviewed: Int? = null,
        limit: Int? = null,
        before: Double? = null,
        after: Double? = null,
    ): List<FrigateReview>

    suspend fun markReviewsViewed(ids: List<String>, reviewed: Boolean = true)
}

class FrigateRepository(private val client: FrigateClient) : ReviewRepository {
    suspend fun login(user: String, password: String): FrigateProfile = withContext(Dispatchers.IO) {
        client.login(user, password)
    }

    suspend fun loadProfile(): FrigateProfile = withContext(Dispatchers.IO) {
        client.profile()
    }

    suspend fun loadConfiguration(): FrigateConfiguration = withContext(Dispatchers.IO) {
        client.configuration()
    }

    suspend fun loadCameras(): List<FrigateCamera> = withContext(Dispatchers.IO) {
        client.cameras()
    }

    suspend fun loadEvents(): List<FrigateEvent> = withContext(Dispatchers.IO) {
        client.events()
    }

    override suspend fun loadReviews(
        cameraIds: List<String>,
        severity: String?,
        reviewed: Int?,
        limit: Int?,
        before: Double?,
        after: Double?,
    ): List<FrigateReview> = withContext(Dispatchers.IO) {
        client.reviews(cameraIds, severity, reviewed, limit, before, after)
    }

    override suspend fun markReviewsViewed(ids: List<String>, reviewed: Boolean) = withContext(Dispatchers.IO) {
        client.markReviewsViewed(ids, reviewed)
    }
}
