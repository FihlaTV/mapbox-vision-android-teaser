package com.mapbox.vision.examples.utils

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

fun AssetManager.getBitmap(path: String): Bitmap? {
    var bitmap: Bitmap? = null

    open(path).use { stream ->
        bitmap = BitmapFactory.decodeStream(stream)
    }

    return bitmap
}
