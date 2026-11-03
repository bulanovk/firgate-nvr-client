package com.frigate.android.feature.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore

/**
 * Saves a frame of a player into the gallery of the device.
 *
 * The archive and the live panel share it: the frame of a camera is the same picture in both.
 *
 * The picture of a 4K camera is shrunk before it is written: the frame of the panel is taken in
 * the resolution of the source, and a 33 megabyte bitmap has no place in the gallery of a phone.
 */
internal suspend fun saveFrameToGallery(context: Context, bitmap: Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "frigate-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Frigate")
    }
    val frame = shrinkFrame(bitmap, MAX_SAVED_FRAME_WIDTH_PX)
    return runCatching {
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val stream = uri?.let { context.contentResolver.openOutputStream(it) }
        if (stream == null) {
            false
        } else {
            stream.use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }.getOrDefault(false)
}

/** The width of the picture in the gallery; a frame wider than that is scaled down. */
private const val MAX_SAVED_FRAME_WIDTH_PX = 1920

private fun shrinkFrame(bitmap: Bitmap, maxWidthPx: Int): Bitmap {
    if (bitmap.width <= maxWidthPx) return bitmap
    val height = (bitmap.height.toFloat() / bitmap.width * maxWidthPx).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, maxWidthPx, height, true)
}
