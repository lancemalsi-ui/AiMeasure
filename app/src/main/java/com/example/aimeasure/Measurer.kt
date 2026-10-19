package com.example.aimeasure

import android.graphics.RectF
import android.media.Image
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Pose
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One depth frame copied off the GL thread, with the camera pose and intrinsics it was taken with. */
class DepthSnapshot(
    val depthMm: ShortArray, val w: Int, val h: Int,
    private val fx: Float, private val fy: Float, private val cx: Float, private val cy: Float,
    private val pose: FloatArray // camera-to-world, column-major 4x4
) {
    /** Depth pixel (u, v) at [z] meters -> world-space point in [out]. */
    fun unproject(u: Int, v: Int, z: Float, out: FloatArray) {
        val x = (u + 0.5f - cx) * z / fx
        val y = -(v + 0.5f - cy) * z / fy // image y points down, camera +Y points up
        val zc = -z                         // camera looks down -Z
        val m = pose
        out[0] = m[0] * x + m[4] * y + m[8] * zc + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * zc + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * zc + m[14]
    }

    companion object {
        /** [intrinsics] must be the camera *texture* intrinsics, which the depth image is aligned with. */
        fun from(img: Image, cameraPose: Pose, intrinsics: CameraIntrinsics): DepthSnapshot {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val arr = ShortArray(w * h)
            for (y in 0 until h) {
                val row = y * plane.rowStride
                for (x in 0 until w) arr[y * w + x] = buf.getShort(row + x * plane.pixelStride)
            }
            val f = intrinsics.focalLength
            val p = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            val sx = w.toFloat() / dims[0]
            val sy = h.toFloat() / dims[1]
            val m = FloatArray(16).also { cameraPose.toMatrix(it, 0) }
            return DepthSnapshot(arr, w, h, f[0] * sx, f[1] * sy, p[0] * sx, p[1] * sy, m)
        }
    }
}

/** Result in meters, in ARCore world space (Y is up). */
class Measurement(
    val length: Float, val width: Float, val height: Float,
    val corners: Array<FloatArray>, // 0-3 bottom, 4-7 top
    val pointCount: Int
)

object Measurer {

    /**
     * @param box object box in TEXTURE_NORMALIZED coordinates (same space as the depth image)
     * @param planeYs heights of tracked upward-facing planes
     */
    fun measure(frames: List<DepthSnapshot>, box: RectF, planeYs: List<Float>): Measurement? {
        val xs = FloatList(); val ys = FloatList(); val zs = FloatList(); val ringYs = FloatList()
        val p = FloatArray(3)
        val padX = box.width() * 0.25f
        val padY = box.height() * 0.25f

        for (f in frames) {
            val u0 = (box.left * f.w).toInt(); val u1 = (box.right * f.w).toInt()
            val v0 = (box.top * f.h).toInt(); val v1 = (box.bottom * f.h).toInt()
            val eu0 = ((box.left - padX) * f.w).toInt().coerceAtLeast(0)
            val eu1 = ((box.right + padX) * f.w).toInt().coerceAtMost(f.w - 1)
            val ev0 = ((box.top - padY) * f.h).toInt().coerceAtLeast(0)
            val ev1 = ((box.bottom + padY) * f.h).toInt().coerceAtMost(f.h - 1)
            for (v in ev0..ev1) for (u in eu0..eu1) {
                val d = f.depthMm[v * f.w + u].toInt() and 0xFFFF
                if (d == 0 || d > 6000) continue
                f.unproject(u, v, d / 1000f, p)
                if (u in u0..u1 && v in v0..v1) { xs.add(p[0]); ys.add(p[1]); zs.add(p[2]) }
                else ringYs.add(p[1])
            }
        }
        if (ys.size < 150) return null

        // 1. Find the surface the object rests on. Prefer an ARCore plane near the lowest depth points.
        val low = percentile(ys.toArray(), 0.05f)
        val planeY = planeYs.minByOrNull { abs(it - low) }?.takeIf { abs(it - low) < 0.08f }
        val floorY = planeY ?: if (ringYs.size > 50) percentile(ringYs.toArray(), 0.2f) else low

        // 2. Keep points standing above that surface.
        val ox = FloatList(); val oz = FloatList(); val oh = FloatList()
        for (i in 0 until ys.size) {
            val hgt = ys[i] - floorY
            if (hgt > 0.012f && hgt < 2.5f) { ox.add(xs[i]); oz.add(zs[i]); oh.add(hgt) }
        }
        if (ox.size < 80) return null

        // 3. Drop stray background points far from the object's horizontal center.
        val mx = percentile(ox.toArray(), 0.5f)
        val mz = percentile(oz.toArray(), 0.5f)
        val r = FloatArray(ox.size) { hypot(ox[it] - mx, oz[it] - mz) }
        val limit = max(0.05f, 2.5f * percentile(r, 0.5f))
        val stride = max(1, ox.size / 5000)
        val kx = FloatList(); val kz = FloatList(); val kh = FloatList()
        var i = 0
        while (i < ox.size) {
            if (r[i] <= limit) { kx.add(ox[i]); kz.add(oz[i]); kh.add(oh[i]) }
            i += stride
        }
        val n = kx.size
        if (n < 60) return null

        // 4. Minimum-area rectangle of the footprint (robust 2nd–98th percentile extents).
        var bestArea = Float.MAX_VALUE
        var best = floatArrayOf(0f, 0f, 0f, 0f, 0f) // angle, u0, u1, w0, w1
        val us = FloatArray(n); val ws = FloatArray(n)
        for (deg in 0 until 90) {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            for (k in 0 until n) {
                us[k] = kx[k] * c + kz[k] * s
                ws[k] = -kx[k] * s + kz[k] * c
            }
            val su = us.copyOf().also { it.sort() }
            val sw = ws.copyOf().also { it.sort() }
            val uLo = pick(su, 0.02f); val uHi = pick(su, 0.98f)
            val wLo = pick(sw, 0.02f); val wHi = pick(sw, 0.98f)
            val area = (uHi - uLo) * (wHi - wLo)
            if (area < bestArea) { bestArea = area; best = floatArrayOf(a.toFloat(), uLo, uHi, wLo, wHi) }
        }
        val height = percentile(kh.toArray(), 0.98f)

        val c = cos(best[0]); val s = sin(best[0])
        fun toWorld(u: Float, w: Float, y: Float) = floatArrayOf(u * c - w * s, y, u * s + w * c)
        val top = floorY + height
        val corners = arrayOf(
            toWorld(best[1], best[3], floorY), toWorld(best[2], best[3], floorY),
            toWorld(best[2], best[4], floorY), toWorld(best[1], best[4], floorY),
            toWorld(best[1], best[3], top), toWorld(best[2], best[3], top),
            toWorld(best[2], best[4], top), toWorld(best[1], best[4], top)
        )
        val e1 = best[2] - best[1]
        val e2 = best[4] - best[3]
        return Measurement(max(e1, e2), min(e1, e2), height, corners, n)
    }

    private fun pick(sorted: FloatArray, q: Float) = sorted[((sorted.size - 1) * q).roundToInt()]
    private fun percentile(a: FloatArray, q: Float) = pick(a.copyOf().also { it.sort() }, q)
}

/** Growable primitive float list (avoids boxing tens of thousands of points). */
class FloatList(capacity: Int = 4096) {
    private var data = FloatArray(capacity)
    var size = 0
        private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun toArray(): FloatArray = data.copyOf(size)
}
