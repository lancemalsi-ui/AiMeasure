package com.example.aimeasure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.opengl.Matrix as GLMatrix
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt

/** Draws world-anchored measurements on top of the camera view by projecting 3D points every frame. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class FrameData(
        val view: FloatArray,
        val proj: FloatArray,
        val tapePoints: List<FloatArray>,
        val box: Measurement?,
        val aiBoxView: FloatArray?, // 4 corners in view pixels
        val crosshair: Boolean,
        val inches: Boolean
    )

    @Volatile private var data: FrameData? = null
    private val vp = FloatArray(16)
    private val dp = resources.displayMetrics.density

    private val tape = Color.parseColor("#F2C230")
    private val blue = Color.parseColor("#4C9BFF")

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * dp; color = Color.argb(140, 0, 0, 0); strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.WHITE }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10 * dp, 8 * dp), 0f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    fun setFrame(d: FrameData) { data = d; postInvalidate() }
    fun clearFrame() { data = null; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: return
        GLMatrix.multiplyMM(vp, 0, d.proj, 0, d.view, 0)

        if (d.crosshair) {
            dot.color = Color.WHITE
            canvas.drawCircle(width / 2f, height / 2f, 5 * dp, dot)
            canvas.drawCircle(width / 2f, height / 2f, 16 * dp, ring)
        }

        d.aiBoxView?.let { b ->
            val path = Path().apply {
                moveTo(b[0], b[1]); lineTo(b[2], b[3]); lineTo(b[4], b[5]); lineTo(b[6], b[7]); close()
            }
            canvas.drawPath(path, dashed)
        }

        // Tape points
        val pts = d.tapePoints
        val sA = FloatArray(2); val sB = FloatArray(2)
        for (i in 1 until pts.size) {
            if (project(pts[i - 1], sA) && project(pts[i], sB)) {
                segment(canvas, sA, sB, tape)
                label(canvas, (sA[0] + sB[0]) / 2, (sA[1] + sB[1]) / 2 - 18 * dp,
                    Units.fmt(dist(pts[i - 1], pts[i]), d.inches), tape, Color.BLACK)
            }
        }
        for (p in pts) if (project(p, sA)) {
            dot.color = tape
            canvas.drawCircle(sA[0], sA[1], 8 * dp, dot)
            canvas.drawCircle(sA[0], sA[1], 8 * dp, ring)
        }

        // AI 3D box
        d.box?.let { m ->
            val c = m.corners
            val s = Array(8) { FloatArray(2) }
            val ok = BooleanArray(8) { project(c[it], s[it]) }
            val edges = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
            for ((a, b) in edges) if (ok[a] && ok[b]) segment(canvas, s[a], s[b], blue)
            for ((a, b) in arrayOf(0 to 1, 1 to 2, 0 to 4)) if (ok[a] && ok[b]) {
                label(canvas, (s[a][0] + s[b][0]) / 2, (s[a][1] + s[b][1]) / 2,
                    Units.fmt(dist(c[a], c[b]), d.inches), blue, Color.WHITE)
            }
        }
    }

    private fun segment(c: Canvas, a: FloatArray, b: FloatArray, color: Int) {
        c.drawLine(a[0], a[1], b[0], b[1], shadow)
        line.color = color
        c.drawLine(a[0], a[1], b[0], b[1], line)
    }

    private fun label(c: Canvas, x: Float, y: Float, text: String, bg: Int, fg: Int) {
        val w = labelText.measureText(text) + 14 * dp
        val h = 24 * dp
        labelBg.color = bg
        c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6 * dp, 6 * dp, labelBg)
        labelText.color = fg
        c.drawText(text, x, y + 5 * dp, labelText)
    }

    /** World point -> view pixels. Returns false when the point is behind the camera. */
    private fun project(p: FloatArray, out: FloatArray): Boolean {
        val x = p[0]; val y = p[1]; val z = p[2]
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= 0.01f) return false
        out[0] = (cx / cw + 1f) / 2f * width
        out[1] = (1f - cy / cw) / 2f * height
        return true
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
