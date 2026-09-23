package com.kandong.app.magnification

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.os.Build

internal class AndroidMagnifier(private val controller: AccessibilityService.MagnificationController) {
    fun read(): MagnifierObservation? = try {
        controller.magnificationConfig?.let { config ->
            val region = controller.currentMagnificationRegion
            val bounds = if (region.isEmpty) null else region.bounds.let {
                SourceBounds(it.left, it.top, it.right, it.bottom)
            }
            MagnifierObservation(when (config.mode) {
                MagnificationConfig.MAGNIFICATION_MODE_WINDOW -> MagnifierMode.WINDOW
                MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN -> MagnifierMode.FULLSCREEN
                else -> MagnifierMode.UNKNOWN
            }, config.scale, if (Build.VERSION.SDK_INT >= 34) config.isActivated else null, bounds)
        }
    } catch (_: RuntimeException) { null }

    fun open(scale: Float, x: Float, y: Float): Boolean? = request(
        MagnificationConfig.Builder().setMode(MagnificationConfig.MAGNIFICATION_MODE_WINDOW)
            .setScale(scale).setCenterX(x).setCenterY(y).apply {
                if (Build.VERSION.SDK_INT >= 34) setActivated(true)
            }.build())

    // Unset centers remain NaN: changing scale must preserve the user's lens position.
    fun scale(value: Float): Boolean? = request(MagnificationConfig.Builder()
        .setMode(MagnificationConfig.MAGNIFICATION_MODE_WINDOW).setScale(value).build())
    fun center(x: Float, y: Float): Boolean? = request(MagnificationConfig.Builder()
        .setMode(MagnificationConfig.MAGNIFICATION_MODE_WINDOW).setCenterX(x).setCenterY(y).build())
    private fun request(config: MagnificationConfig): Boolean? = try {
        controller.setMagnificationConfig(config, false)
    } catch (_: RuntimeException) { null }
    fun reset(): Boolean = try { controller.resetCurrentMagnification(false) }
    catch (_: RuntimeException) { false }
}
