package com.hcode91.pocketcarioca

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The on-screen controller: D-pad (or analog stick), buttons 1-4, ZL/ZR, HOME, plus the
 * emulator's own Menu and Fast-forward keys. Multi-touch; each finger is tracked separately so
 * sliding across the D-pad works.
 */
class TouchControls(context: Context) : View(context) {

    var buttons = 0; private set
    var axisX = 0f; private set
    var axisY = 0f; private set
    var analog = false
    var haptics = true
    var opacity = 0.45f
        set(v) { field = v; invalidate() }

    var onMenu: (() -> Unit)? = null
    var onFastForward: ((Boolean) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private class Btn(val mask: Int, val label: String, val round: Boolean) { val rect = RectF() }

    private val face = listOf(
        Btn(Core.B1, "1", true), Btn(Core.B2, "2", true), Btn(Core.B3, "3", true), Btn(Core.B4, "4", true),
    )
    private val shoulders = listOf(Btn(Core.ZL, "ZL", false), Btn(Core.ZR, "ZR", false))
    private val home = Btn(Core.HOME, "HOME", false)
    private val menuBtn = Btn(0, "☰", false)
    private val ffBtn = Btn(0, "»", false)
    private val stick = RectF()

    private var ffHeld = false
    private val pointers = HashMap<Int, PointF>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layout(w.toFloat(), h.toFloat())

    private fun layout(w: Float, h: Float) {
        val u = min(w, h) / 10f
        text.textSize = u * 0.42f
        val landscape = w >= h
        val bottom = if (landscape) h - u * 0.6f else h - u * 1.4f
        val d = u * 3.6f
        stick.set(u * 0.6f, bottom - d, u * 0.6f + d, bottom)

        // Face buttons in a diamond: 1 bottom, 2 right, 3 left, 4 top (like the Z-Pad's layout).
        val cx = w - u * 2.4f; val cy = bottom - d / 2; val r = u * 0.78f; val off = u * 1.25f
        place(face[0], cx, cy + off, r); place(face[1], cx + off, cy, r)
        place(face[2], cx - off, cy, r); place(face[3], cx, cy - off, r)

        val top = if (landscape) u * 0.4f else stick.top - u * 1.8f
        shoulders[0].rect.set(u * 0.4f, top, u * 2.9f, top + u * 1.1f)
        shoulders[1].rect.set(w - u * 2.9f, top, w - u * 0.4f, top + u * 1.1f)

        val by = if (landscape) h - u * 1.1f else h - u * 1.0f
        home.rect.set(w / 2 - u * 1.2f, by, w / 2 + u * 1.2f, by + u * 0.8f)
        menuBtn.rect.set(w / 2 - u * 2.8f, by, w / 2 - u * 1.5f, by + u * 0.8f)
        ffBtn.rect.set(w / 2 + u * 1.5f, by, w / 2 + u * 2.8f, by + u * 0.8f)
    }

    private fun place(b: Btn, cx: Float, cy: Float, r: Float) = b.rect.set(cx - r, cy - r, cx + r, cy + r)

    override fun onDraw(c: Canvas) {
        val a = (opacity * 255).toInt().coerceIn(20, 255)
        fill.color = Color.argb(a / 2, 255, 255, 255)
        stroke.color = Color.argb(a, 255, 255, 255)
        text.color = Color.argb(min(255, a * 2), 255, 255, 255)
        val pressed = Paint(fill).apply { color = Color.argb(min(255, a + 60), 25, 196, 138) }

        if (analog) {
            c.drawOval(stick, fill); c.drawOval(stick, stroke)
            val kr = stick.width() * 0.2f
            val kx = stick.centerX() + axisX * (stick.width() / 2 - kr)
            val ky = stick.centerY() + axisY * (stick.height() / 2 - kr)
            c.drawCircle(kx, ky, kr, pressed)
        } else {
            val t = stick.width() / 3
            val cx = stick.centerX(); val cy = stick.centerY()
            val arms = listOf(
                Core.UP to RectF(cx - t / 2, stick.top, cx + t / 2, cy - t / 2),
                Core.DOWN to RectF(cx - t / 2, cy + t / 2, cx + t / 2, stick.bottom),
                Core.LEFT to RectF(stick.left, cy - t / 2, cx - t / 2, cy + t / 2),
                Core.RIGHT to RectF(cx + t / 2, cy - t / 2, stick.right, cy + t / 2),
            )
            c.drawRect(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2, fill)
            for ((m, r) in arms) {
                c.drawRoundRect(r, 10f, 10f, if (buttons and m != 0) pressed else fill)
                c.drawRoundRect(r, 10f, 10f, stroke)
            }
        }
        for (b in face + shoulders + listOf(home, menuBtn, ffBtn)) {
            val on = (b.mask != 0 && buttons and b.mask != 0) || (b === ffBtn && ffHeld)
            val p = if (on) pressed else fill
            if (b.round) { c.drawOval(b.rect, p); c.drawOval(b.rect, stroke) }
            else { c.drawRoundRect(b.rect, 18f, 18f, p); c.drawRoundRect(b.rect, 18f, 18f, stroke) }
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + text.textSize / 3, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i); val y = e.getY(i)
                when {
                    menuBtn.rect.containsPad(x, y) -> { buzz(); onMenu?.invoke(); return true }
                    ffBtn.rect.containsPad(x, y) -> {
                        buzz()
                        ffHeld = if (toggleFf) !ffHeld else true
                        if (!toggleFf) ffPointer = e.getPointerId(i)
                        onFastForward?.invoke(ffHeld); invalidate(); return true
                    }
                }
                pointers[e.getPointerId(i)] = PointF(x, y)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                pointers[e.getPointerId(i)]?.set(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == ffPointer) releaseFf()
                pointers.remove(id)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointers.clear()
                releaseFf()
            }
        }
        recompute()
        return true
    }

    var toggleFf = true
    private var ffPointer = -1

    private fun releaseFf() {
        if (ffPointer == -1) return
        ffPointer = -1
        if (!toggleFf && ffHeld) { ffHeld = false; onFastForward?.invoke(false); invalidate() }
    }

    private fun RectF.containsPad(x: Float, y: Float): Boolean {
        val px = width() * 0.2f; val py = height() * 0.2f
        return x >= left - px && x <= right + px && y >= top - py && y <= bottom + py
    }

    private fun recompute() {
        var m = 0; var ax = 0f; var ay = 0f
        for (p in pointers.values) {
            val grow = stick.width() * 0.35f
            if (p.x > stick.left - grow && p.x < stick.right + grow && p.y > stick.top - grow && p.y < stick.bottom + grow) {
                val dx = (p.x - stick.centerX()) / (stick.width() / 2)
                val dy = (p.y - stick.centerY()) / (stick.height() / 2)
                if (analog) {
                    val len = hypot(dx, dy)
                    val s = if (len > 1f) 1f / len else 1f
                    ax = dx * s; ay = dy * s
                } else {
                    val dead = 0.25f
                    if (abs(dx) > dead || abs(dy) > dead) {
                        if (dx < -dead && abs(dy) < abs(dx) * 2.4f) m = m or Core.LEFT
                        if (dx > dead && abs(dy) < abs(dx) * 2.4f) m = m or Core.RIGHT
                        if (dy < -dead && abs(dx) < abs(dy) * 2.4f) m = m or Core.UP
                        if (dy > dead && abs(dx) < abs(dy) * 2.4f) m = m or Core.DOWN
                    }
                }
                continue
            }
            for (b in face + shoulders + listOf(home)) if (b.rect.containsPad(p.x, p.y)) m = m or b.mask
        }
        val changed = m != buttons || ax != axisX || ay != axisY
        if (m and buttons.inv() != 0) buzz()
        buttons = m; axisX = ax; axisY = ay
        if (changed) { invalidate(); onChanged?.invoke() }
    }

    fun setFastForwardState(on: Boolean) { ffHeld = on; invalidate() }

    private fun buzz() { if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}
