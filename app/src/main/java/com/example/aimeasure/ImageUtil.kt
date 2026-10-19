package com.example.aimeasure

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtil {

    /** Copies an ARCore YUV_420_888 camera image into an NV21 byte array, handling row/pixel strides. */
    fun yuvToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        var pos = 0
        for (r in 0 until h) {
            val rowStart = r * yP.rowStride
            for (c in 0 until w) out[pos++] = yBuf.get(rowStart + c * yP.pixelStride)
        }
        for (r in 0 until h / 2) {
            for (c in 0 until w / 2) {
                out[pos++] = vBuf.get(r * vP.rowStride + c * vP.pixelStride)
                out[pos++] = uBuf.get(r * uP.rowStride + c * uP.pixelStride)
            }
        }
        return out
    }

    /** NV21 -> upright (rotated by the sensor orientation) JPEG, longest side capped at [maxSide]. */
    fun nv21ToUprightJpeg(nv21: ByteArray, w: Int, h: Int, rotation: Int, maxSide: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, raw)
        val bytes = raw.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (upright !== bmp) upright.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * Maps a normalized box found in the upright (rotated clockwise by [rotation]) image
     * back to the camera image's own normalized coordinates.
     */
    fun unrotateBox(box: RectF, rotation: Int): RectF {
        val deg = ((rotation % 360) + 360) % 360
        fun inv(x: Float, y: Float): Pair<Float, Float> = when (deg) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
        val a = inv(box.left, box.top)
        val b = inv(box.right, box.bottom)
        return RectF(
            min(a.first, b.first), min(a.second, b.second),
            max(a.first, b.first), max(a.second, b.second)
        )
    }
}
