package com.hcode91.pocketcarioca

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class GameActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var gl: GLSurfaceView
    private lateinit var touch: TouchControls
    private lateinit var settings: Settings
    private lateinit var path: String

    // Written by the UI thread, read by the GL thread every frame.
    @Volatile private var padButtons = 0
    @Volatile private var padX = 0f
    @Volatile private var padY = 0f
    @Volatile private var hatButtons = 0
    @Volatile private var triggerButtons = 0
    @Volatile private var paused = false
    @Volatile private var fastForward = false
    @Volatile private var finishing = false

    private var sensors: SensorManager? = null
    private var rest = floatArrayOf(0f, 0f, 1f)
    private var lastMotion = floatArrayOf(0f, 0f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.ensureInit(this)
        Core.applySettings(this)
        settings = Settings(this)
        path = intent.getStringExtra("path") ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 8)
            // The session's GPU textures live in this context: keep it across pauses.
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        touch = TouchControls(this).apply {
            visibility = if (settings.showOverlay) View.VISIBLE else View.GONE
            analog = settings.analogStick
            haptics = settings.haptics
            opacity = settings.overlayOpacity / 100f
            toggleFf = settings.fastForwardToggle
            onMenu = { showMenu() }
            onFastForward = { fastForward = it }
        }
        val root = FrameLayout(this)
        root.addView(gl)
        root.addView(touch)
        setContentView(root)
        hideSystemBars()

        if (settings.boomerang) sensors = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    private val renderer = object : GLSurfaceView.Renderer {
        private var w = 1
        private var h = 1

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            Core.surfaceCreated()
            // First start, or the context was lost and the core closed the game with it.
            if (!Core.isRunning()) {
                val err = Core.open(path)
                if (err != null) fail(getString(R.string.open_failed, err))
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) { w = width; h = height }

        override fun onDrawFrame(unused: GL10?) {
            if (finishing) return
            val buttons = touch.buttons or padButtons or hatButtons or triggerButtons
            val ax = if (abs(padX) > 0.08f) padX else touch.axisX
            val ay = if (abs(padY) > 0.08f) padY else touch.axisY
            when (Core.frame(w, h, buttons, ax, ay, fastForward, paused)) {
                1 -> done(null)
                2 -> done(Core.lastError())
            }
        }
    }

    private fun done(error: String?) {
        if (finishing) return
        finishing = true
        runOnUiThread {
            if (error != null) Toast.makeText(this, getString(R.string.game_failed, error), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun fail(msg: String) {
        finishing = true
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        gl.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        // One paused frame freezes the audio before the GL thread stops calling us.
        gl.queueEvent { Core.frame(1, 1, 0, 0f, 0f, false, true) }
        gl.onPause()
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            finishing = true
            // Close on the GL thread if it is still alive; otherwise the next surfaceCreated does.
            gl.queueEvent { Core.close() }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ motion (Boomerang)

    override fun onSensorChanged(e: SensorEvent) {
        // Into display coordinates (x right, y up the screen, z out of it), then into g.
        val (sx, sy) = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> -e.values[1] to e.values[0]
            Surface.ROTATION_180 -> -e.values[0] to -e.values[1]
            Surface.ROTATION_270 -> e.values[1] to -e.values[0]
            else -> e.values[0] to e.values[1]
        }
        val g = SensorManager.GRAVITY_EARTH
        val k = settings.motionSensitivity
        lastMotion = floatArrayOf(sx / g, sy / g, e.values[2] / g)
        // Boomerang frame: x along the controller, y forward, z out of the button face. With the
        // phone flat and its top edge pointing forward, that is the display frame. "Recenter"
        // makes the current pose read as flat.
        Core.motion(
            (lastMotion[0] - rest[0]) * k,
            (lastMotion[1] - rest[1]) * k,
            1f + (lastMotion[2] - rest[2]) * k,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun recenter() { rest = lastMotion.copyOf() }

    // ------------------------------------------------------------------ controllers

    private fun keyBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> Core.B1
        KeyEvent.KEYCODE_BUTTON_B -> Core.B2
        KeyEvent.KEYCODE_BUTTON_X -> Core.B3
        KeyEvent.KEYCODE_BUTTON_Y -> Core.B4
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> Core.ZL
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> Core.ZR
        KeyEvent.KEYCODE_BUTTON_START -> Core.HOME
        KeyEvent.KEYCODE_DPAD_UP -> Core.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Core.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Core.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Core.RIGHT
        // keyboard
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_ENTER -> Core.B1
        KeyEvent.KEYCODE_X -> Core.B2
        KeyEvent.KEYCODE_A -> Core.B3
        KeyEvent.KEYCODE_S -> Core.B4
        KeyEvent.KEYCODE_Q -> Core.ZL
        KeyEvent.KEYCODE_W -> Core.ZR
        KeyEvent.KEYCODE_H -> Core.HOME
        else -> 0
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_SELECT ||
            code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR || code == KeyEvent.KEYCODE_TAB) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                fastForward = !fastForward; touch.setFastForwardState(fastForward)
            }
            return true
        }
        val bit = keyBit(code)
        if (bit == 0) return super.dispatchKeyEvent(e)
        padButtons = if (e.action == KeyEvent.ACTION_DOWN) padButtons or bit else padButtons and bit.inv()
        if (e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_JOYSTICK)) hideTouchForPad()
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(e)
        }
        padX = e.getAxisValue(MotionEvent.AXIS_X)
        padY = e.getAxisValue(MotionEvent.AXIS_Y)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var h = 0
        if (hx < -0.5f) h = h or Core.LEFT
        if (hx > 0.5f) h = h or Core.RIGHT
        if (hy < -0.5f) h = h or Core.UP
        if (hy > 0.5f) h = h or Core.DOWN
        hatButtons = h
        var t = 0
        if (maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f) t = t or Core.ZL
        if (maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f) t = t or Core.ZR
        triggerButtons = t
        if (abs(padX) > 0.3f || abs(padY) > 0.3f || h != 0) hideTouchForPad()
        return true
    }

    private fun hideTouchForPad() {
        if (touch.visibility == View.VISIBLE) runOnUiThread { touch.visibility = View.GONE }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Touching the screen brings the controls back after a gamepad hid them.
        if (settings.showOverlay && touch.visibility != View.VISIBLE && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touch.visibility = View.VISIBLE
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        if (isFinishing) return
        paused = true
        val items = mutableListOf(
            getString(R.string.menu_resume) to {},
            getString(R.string.menu_save) to { slotPicker(save = true) },
            getString(R.string.menu_load) to { slotPicker(save = false) },
            getString(if (fastForward) R.string.menu_ff_on else R.string.menu_ff_off) to {
                fastForward = !fastForward; touch.setFastForwardState(fastForward)
            },
            getString(R.string.menu_screenshot) to { screenshot() },
            getString(if (touch.visibility == View.VISIBLE) R.string.menu_controls_hide else R.string.menu_controls_show) to {
                touch.visibility = if (touch.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            },
        )
        if (settings.boomerang) items += getString(R.string.menu_recalibrate) to { recenter() }
        items += getString(R.string.menu_restart) to {
            gl.queueEvent {
                Core.close()
                Core.open(path)?.let { fail(getString(R.string.open_failed, it)) }
            }
        }
        items += getString(R.string.menu_settings) to { startActivity(Intent(this, SettingsActivity::class.java)) }
        items += getString(R.string.menu_quit) to { finishing = true; finish() }

        var chosen = -1
        AlertDialog.Builder(this)
            .setTitle(Core.title()?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> chosen = i }
            .setOnDismissListener {
                // The slot pickers (items 1 and 2) keep the game paused until they close.
                if (chosen !in 1..2) {
                    paused = false
                    hideSystemBars()
                }
                if (chosen >= 0) items[chosen].second()
            }
            .show()
    }

    private fun slotPicker(save: Boolean) {
        val filled = Core.slots()
        val grid = GridView(this).apply {
            numColumns = 2
            verticalSpacing = 12; horizontalSpacing = 12
            setPadding(24, 24, 24, 24)
        }
        val thumbs = arrayOfNulls<Bitmap>(Core.SLOTS)
        for (i in 0 until Core.SLOTS) if (filled and (1 shl i) != 0) thumbs[i] = Core.toBitmap(Core.slotThumbnail(i))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (save) R.string.menu_save else R.string.menu_load)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { paused = false; hideSystemBars() }
            .create()

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = Core.SLOTS
            override fun getItem(p: Int) = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: layoutInflater.inflate(R.layout.item_slot, parent, false)
                val img = v.findViewById<ImageView>(R.id.thumb)
                val label = v.findViewById<TextView>(R.id.label)
                val has = filled and (1 shl p) != 0
                label.text = getString(R.string.slot_n, p + 1) + if (has) "" else " · " + getString(R.string.slot_empty)
                if (thumbs[p] != null) img.setImageBitmap(thumbs[p]) else img.setImageDrawable(null)
                v.alpha = if (!save && !has) 0.4f else 1f
                return v
            }
        }
        grid.setOnItemClickListener { _, _, slot, _ ->
            if (!save && filled and (1 shl slot) == 0) return@setOnItemClickListener
            dialog.dismiss()
            gl.queueEvent {
                val err = if (save) Core.saveSlot(slot) else Core.loadSlot(slot)
                runOnUiThread {
                    val msg = err ?: getString(if (save) R.string.saved_slot else R.string.loaded_slot, slot + 1)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun screenshot() {
        gl.queueEvent {
            val bmp = Core.toBitmap(Core.screenshot()) ?: return@queueEvent
            val name = "PocketCarioca_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketCarioca")
            }
            val ok = try {
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri != null && contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            } catch (_: Exception) { false }
            if (ok) runOnUiThread { Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
