package com.example.aimeasure

import android.graphics.RectF
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What Claude found in the photo. [box] is normalized to the upright photo that was sent. */
data class AiDetection(
    val found: Boolean,
    val label: String,
    val shape: String,
    val box: RectF?,
    val confidence: Float,
    val typicalCm: FloatArray?,
    val tip: String
)

class ClaudeClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) {
    /** Blocking. Finds the object at the image center. Run off the main and GL threads. */
    fun detect(jpeg: ByteArray): AiDetection {
        val content = JSONArray().put(imageBlock(jpeg)).put(textBlock(PROMPT))
        return parse(send(content, 800, null))
    }

    /** Blocking. Answers a free-form question about a measurement, optionally with the photo. */
    fun ask(question: String, measurementContext: String, jpeg: ByteArray?): String {
        val content = JSONArray()
        if (jpeg != null) content.put(imageBlock(jpeg))
        content.put(textBlock("Measurement from the app:\n$measurementContext\n\nMy question: $question"))
        return extractText(send(content, 700, ASK_SYSTEM)).trim()
    }

    /** Blocking. Cheap request that confirms the key and model work. */
    fun ping(): String = extractText(send(JSONArray().put(textBlock("Reply with exactly: OK")), 10, null)).trim()

    private fun send(content: JSONArray, maxTokens: Int, system: String?): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (system != null) body.put("system", system)

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("content-type", "application/json")
            .header("anthropic-version", "2023-06-01")
            .apply { if (apiKey.isNotBlank()) header("x-api-key", apiKey) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(friendlyError(resp.code, raw))
            return raw
        }
    }

    private fun friendlyError(code: Int, raw: String): String {
        val apiMsg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "Claude rejected the API key. Check it in Claude settings."
            403 -> "This API key isn’t allowed to use that model."
            404 -> "Model not found. Check the model name in Claude settings."
            429 -> "Rate limit reached. Wait a moment and try again."
            529, 503 -> "Claude is busy right now. Try again shortly."
            else -> "HTTP $code" + (apiMsg?.let { ": $it" } ?: "")
        }
    }

    private fun extractText(raw: String): String {
        val content = JSONObject(raw).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val o = content.getJSONObject(i)
                if (o.optString("type") == "text") append(o.optString("text"))
            }
        }
    }

    private fun imageBlock(jpeg: ByteArray) = JSONObject()
        .put("type", "image")
        .put("source", JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    private fun textBlock(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun parse(raw: String): AiDetection {
        val text = extractText(raw)
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) throw IOException("Claude’s reply wasn’t valid JSON")
        val j = JSONObject(text.substring(s, e + 1))

        val box = j.optJSONArray("box")?.takeIf { it.length() == 4 }?.let { a ->
            fun v(i: Int) = a.optDouble(i, -1.0).toFloat().coerceIn(0f, 1f)
            RectF(minOf(v(0), v(2)), minOf(v(1), v(3)), maxOf(v(0), v(2)), maxOf(v(1), v(3)))
        }?.takeIf { it.width() > 0.02f && it.height() > 0.02f }

        val typical = j.optJSONArray("typical_size_cm")?.takeIf { it.length() == 3 }?.let { a ->
            FloatArray(3) { i -> a.optDouble(i, Double.NaN).toFloat() }
        }?.takeIf { arr -> arr.all { it.isFinite() && it > 0f } }

        return AiDetection(
            found = j.optBoolean("found", false),
            label = j.optString("label", "object").ifBlank { "object" },
            shape = j.optString("shape", "irregular"),
            box = box,
            confidence = j.optDouble("confidence", 0.0).toFloat(),
            typicalCm = typical,
            tip = j.optString("tip", "")
        )
    }

    companion object {
        /** One shared HTTP client for the whole app. */
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        private const val ASK_SYSTEM =
            "You help people make decisions using measurements from a phone AR measuring app. " +
            "Answer in plain language, under 120 words. Measurements have about ±1–2 cm error; " +
            "say so when that could change the answer. Give both cm and inches when you mention sizes."

        private val PROMPT = """
            You are the vision step of a phone AR measuring app. The user is aiming the CENTER of this photo at one object they want measured.
            Find that object (the one at or nearest the image center) and return ONLY a JSON object, no other text:
            {"found": true, "label": "short object name", "shape": "box|cylinder|sphere|irregular", "box": [x_min, y_min, x_max, y_max], "confidence": 0.0, "typical_size_cm": [length, width, height], "tip": ""}
            Rules:
            - box: tight bounding box around the WHOLE object, as fractions of image width (x: 0 = left, 1 = right) and height (y: 0 = top, 1 = bottom), 3 decimals. Include the object down to where it touches the surface, with as little background as possible.
            - typical_size_cm: typical real-world size of this kind of object, or null if it varies too much.
            - tip: one short sentence if the photo makes measuring hard (object cut off, too dark, too close, no visible surface around it); otherwise "".
            - If no clear object is near the center, set found to false and explain in tip.
        """.trimIndent()
    }
}
