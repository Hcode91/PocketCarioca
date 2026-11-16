package com.hcode91.pocketcarioca

import android.content.Context
import android.graphics.Bitmap

/**
 * The Zeebx core, through `libpocketcarioca.so`.
 *
 * Calls marked GL must run on the GLSurfaceView's render thread (Renderer callbacks or
 * `queueEvent`): the session and its GPU textures live there.
 */
object Core {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("pocketcarioca")
    }

    // Button bits, in the order of zeebx::input::BUTTON_NAMES.
    const val B1 = 1 shl 0
    const val B2 = 1 shl 1
    const val B3 = 1 shl 2
    const val B4 = 1 shl 3
    const val ZR = 1 shl 4
    const val ZL = 1 shl 6
    const val HOME = 1 shl 9
    const val START = 1 shl 11
    const val UP = 1 shl 12
    const val LEFT = 1 shl 13
    const val DOWN = 1 shl 14
    const val RIGHT = 1 shl 15

    const val SLOTS = 10

    @JvmStatic external fun init(context: Context, filesDir: String)
    @JvmStatic external fun setOptions(
        gpu: Boolean, internalResolution: Int, smooth: Boolean, audio: Boolean,
        volume: Int, aspect: Int, boomerang: Boolean,
    )
    @JvmStatic external fun setSoundfont(path: String)
    @JvmStatic external fun motion(x: Float, y: Float, z: Float)
    @JvmStatic external fun key(avk: Int, pressed: Boolean)
    @JvmStatic external fun isRunning(): Boolean
    @JvmStatic external fun lastError(): String?
    @JvmStatic external fun title(): String?

    @JvmStatic external fun scan(dir: String): Array<String?>?
    @JvmStatic external fun cover(path: String): IntArray?
    @JvmStatic external fun slots(): Int
    @JvmStatic external fun slotThumbnail(slot: Int): IntArray?

    // GL thread
    @JvmStatic external fun surfaceCreated()
    @JvmStatic external fun open(path: String): String?
    @JvmStatic external fun frame(
        width: Int, height: Int, buttons: Int, axisX: Float, axisY: Float,
        fastForward: Boolean, paused: Boolean,
    ): Int
    @JvmStatic external fun close()
    @JvmStatic external fun saveSlot(slot: Int): String?
    @JvmStatic external fun loadSlot(slot: Int): String?
    @JvmStatic external fun screenshot(): IntArray?
    @JvmStatic external fun diagnostics(): String?

    /** `[w, h, argb...]` from the core into a Bitmap. */
    fun toBitmap(data: IntArray?): Bitmap? {
        if (data == null || data.size < 2) return null
        val w = data[0]; val h = data[1]
        if (w <= 0 || h <= 0 || data.size < 2 + w * h) return null
        return Bitmap.createBitmap(data, 2, w, w, h, Bitmap.Config.ARGB_8888)
    }

    private var initialised = false

    fun ensureInit(ctx: Context) {
        if (initialised) return
        init(ctx.applicationContext, ctx.filesDir.absolutePath)
        initialised = true
    }

    fun applySettings(ctx: Context) {
        val s = Settings(ctx)
        setOptions(s.gpu, s.internalResolution, s.smooth, s.audio, s.volume, s.aspect, s.boomerang)
        setSoundfont(s.soundfont ?: "")
    }
}
