package com.writeright.ai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiWordCorrection(
    val original: String,
    val corrected: String,
    val explanation: String,
)

data class AiReview(
    val correctedText: String,
    val summary: String,
    val corrections: List<AiWordCorrection>,
)

/** OpenRouter chat-completions client. The request contains the recognized text and a board image. */
class OpenRouterClient {
    suspend fun review(apiKey: String, model: String, transcript: String, imageDataUrl: String): AiReview =
        withContext(Dispatchers.IO) {
            val prompt = """
                You are an English handwriting and writing tutor.
                The attached image is the student's handwritten English writing. The OCR transcript is only a hint and may contain recognition errors.
                Read the handwriting from the image, use the transcript as a cross-check, then correct spelling, capitalization, punctuation, and basic grammar.
                Do not invent words that are not supported by the handwriting. If handwriting is unclear, do not guess.
                Return ONLY valid JSON with this exact shape:
                {
                  "corrected_text": "the full corrected sentence or text",
                  "summary_ar": "short Arabic feedback for the learner",
                  "corrections": [
                    {"original":"exact recognized/written word", "corrected":"replacement word", "explanation_ar":"short Arabic explanation"}
                  ]
                }
                Include only real changes in corrections. Keep original matched to a word from the transcript whenever possible.
                OCR transcript: $transcript
            """.trimIndent()

            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", imageDataUrl))
                )
            val messages = JSONArray()
                .put(JSONObject().put("role", "user").put("content", content))
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.1)
                .put("max_tokens", 2000)
                .put("messages", messages)

            val response = post(apiKey, body)
            val message = response.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("OpenRouter رجّع استجابة غير متوقعة")
            val raw = extractContent(message)
            val parsed = parseJsonObject(raw)
            val items = parsed.optJSONArray("corrections") ?: JSONArray()
            val corrections = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val original = item.optString("original").trim()
                val corrected = item.optString("corrected").trim()
                if (original.isBlank() || corrected.isBlank() || original.equals(corrected, true)) null
                else AiWordCorrection(original, corrected, item.optString("explanation_ar"))
            }
            AiReview(
                correctedText = parsed.optString("corrected_text", transcript),
                summary = parsed.optString("summary_ar", "تمت مراجعة الكتابة."),
                corrections = corrections,
            )
        }

    /** The reply text can be a string or a list of parts, depending on the provider. */
    private fun extractContent(message: JSONObject): String {
        val content = message.opt("content")
        val text = when (content) {
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.opt(i)
                    if (part is JSONObject) append(part.optString("text")) else if (part is String) append(part)
                }
            }
            null, JSONObject.NULL -> ""
            else -> content.toString()
        }
        return text.trim()
    }

    /** Takes the JSON object out of the reply even when the model adds text or code fences around it. */
    private fun parseJsonObject(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("النموذج ما رجّعش رد مفهوم. جرّب موديل تاني يدعم الصور")
        }
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("رد النموذج مش بصيغة صحيحة. جرّب تكتب تاني أو غيّر الموديل")
        }
    }

    suspend fun test(apiKey: String, model: String) = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", "Reply with exactly: WriteRight AI connected")
        )
        val body = JSONObject().put("model", model).put("max_tokens", 30).put("messages", messages)
        post(apiKey, body)
    }

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/alrussihack-cmyk/writeright-ai")
            connection.setRequestProperty("X-Title", "WriteRight AI")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(responseText).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenRouter HTTP $code")
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
