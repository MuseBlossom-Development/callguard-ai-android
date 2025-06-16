package com.whispercpp

import android.content.res.AssetManager
import java.io.InputStream

class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper")
        }

        @JvmStatic
        external fun initContext(modelPath: String): Long

        @JvmStatic
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long

        @JvmStatic
        external fun initContextFromInputStream(inputStream: InputStream): Long

        @JvmStatic
        external fun freeContext(contextPtr: Long)

        @JvmStatic
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

        @JvmStatic
        external fun getTextSegmentCount(contextPtr: Long): Int

        @JvmStatic
        external fun getTextSegment(contextPtr: Long, index: Int): String

        @JvmStatic
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long

        @JvmStatic
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

        @JvmStatic
        external fun getSystemInfo(): String

        @JvmStatic
        external fun benchMemcpy(nThreads: Int): String

        @JvmStatic
        external fun benchGgmlMulMat(nThreads: Int): String
    }
}