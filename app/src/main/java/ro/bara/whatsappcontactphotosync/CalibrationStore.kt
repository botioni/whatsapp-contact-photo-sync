package ro.bara.whatsappcontactphotosync

import android.content.Context

object CalibrationStore {
    private const val PREFS = "calibration"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_RIGHT = "right"
    private const val KEY_BOTTOM = "bottom"

    data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun load(context: Context): Crop? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LEFT)) return null
        return Crop(
            prefs.getFloat(KEY_LEFT, 0.04f),
            prefs.getFloat(KEY_TOP, 0.04f),
            prefs.getFloat(KEY_RIGHT, 0.96f),
            prefs.getFloat(KEY_BOTTOM, 0.96f)
        )
    }

    fun save(context: Context, crop: Crop) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LEFT, crop.left)
            .putFloat(KEY_TOP, crop.top)
            .putFloat(KEY_RIGHT, crop.right)
            .putFloat(KEY_BOTTOM, crop.bottom)
            .apply()
    }
}
