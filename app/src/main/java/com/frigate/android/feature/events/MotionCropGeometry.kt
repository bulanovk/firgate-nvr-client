package com.frigate.android.feature.events

internal data class MotionCropGeometry(val frameWidth: Double, val frameHeight: Double, val scale: Double, val translationX: Double, val translationY: Double)

internal fun motionCropGeometry(width: Double, height: Double, sourceAspect: Double, crop: NormalizedRect?): MotionCropGeometry {
    require(width > 0 && height > 0 && sourceAspect > 0)
    val frameWidth = minOf(width, height * sourceAspect)
    val frameHeight = frameWidth / sourceAspect
    if (crop == null) return MotionCropGeometry(frameWidth, frameHeight, 1.0, 0.0, 0.0)
    val scale = minOf(width / (frameWidth * (crop.right - crop.left)), height / (frameHeight * (crop.bottom - crop.top)))
    return MotionCropGeometry(frameWidth, frameHeight, scale,
        (.5 - (crop.left + crop.right) / 2) * frameWidth * scale,
        (.5 - (crop.top + crop.bottom) / 2) * frameHeight * scale)
}
