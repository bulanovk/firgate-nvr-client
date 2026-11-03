package com.frigate.android.feature.events

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.ServerProfile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun decodeEventImage(transport: EventsImageTransport, path: String, height: Int? = null): Bitmap = withContext(Dispatchers.IO) {
    val bytes = transport.load(path, height)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 32000000) throw IOException("Invalid image")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Invalid image")
}

@Composable
internal fun EventsImage(profile: ServerProfile, cookie: String, path: String?, description: String?, onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(), height: Int? = null) {
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val transport = remember(profile, cookie) { EventsImageTransport(profile, cookie) }
    val bitmap by produceState<Bitmap?>(null, transport, path, height) {
        value = null
        if (path != null) try { value = decodeEventImage(transport, path, height) }
        catch (error: CancellationException) { throw error }
        catch (error: FrigateHttpException) { if (error.code == 401) unauthorized() }
        catch (_: IOException) { /* Keep the card's unavailable background. */ }
    }
    bitmap?.let { Image(it.asImageBitmap(), description, modifier, contentScale = ContentScale.Fit) }
}
