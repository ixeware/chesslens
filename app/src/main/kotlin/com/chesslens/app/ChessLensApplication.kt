package com.chesslens.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ChessLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrElse {
            Log.e("ChessLens", "OpenCV failed to load", it)
            false
        }
    }

    companion object {
        @Volatile
        var openCvReady: Boolean = false
            private set
    }
}
