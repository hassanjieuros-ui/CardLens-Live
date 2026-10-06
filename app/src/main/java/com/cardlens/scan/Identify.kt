package com.cardlens.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

data class CardId(
    val game: String,       // pokemon | yugioh | onepiece | other
    val language: String,
    val name: String,
    val setName: String,
    val setCode: String,
    val number: String,
    val rarity: String,
    val variant: String,
    val confidence: Double,
    val notes: String,
)

object ImageUtil {
    /** Loads a photo, fixes rotation, shrinks it to keep API calls fast and cheap. */
    fun load(context: Context, uri: Uri, maxSide: Int = 1280): Pair<Bitmap, String> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decoder returns null by design; only the sizes get filled in.
        val opened = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            true
        } ?: false
        if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Couldn't open the photo")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Couldn't read the photo")

        val rotation = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return bmp to b64
    }
}

object Identifier {
    private const val PROMPT = """You identify a single trading card from a photo for a card shop's buy counter.
Return ONLY a JSON object, no prose and no code fences, with these keys:
"game": one of "pokemon", "yugioh", "onepiece", "other"
"language": "english", "japanese", or the language printed
"name": card name exactly as printed
"set_name": full expansion/set name (use the set symbol, set code, regulation mark and card frame to decide)
"set_code": for Yu-Gi-Oh the full code like "LOB-EN001"; for One Piece the full code like "OP01-001"; for Pokemon the small set abbreviation printed bottom-left like "SVI" or "PAF", or "" if none is printed
"number": collector number exactly as printed, e.g. "025/198", "TG05/TG30", "SWSH050", "LOB-EN001", "OP01-001"
"rarity": rarity as printed or apparent
"variant": comma-separated from: holo, reverse holo, 1st edition, unlimited, alt art, parallel, full art, promo, stamped; or ""
"confidence": number 0 to 1 for how sure you are of name + set + number together
"notes": very short, only if something was unreadable or ambiguous
If several cards are visible, use the most centered, largest one."""

    fun identify(apiKey: String, model: String, imageB64: String): CardId {
        if (apiKey.isBlank()) throw IOException("Add your Anthropic API key in Settings first.")
        val content = JSONArray()
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)
                )
            )
            .put(JSONObject().put("type", "text").put("text", PROMPT))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val (code, text) = Http.postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey.trim(), "anthropic-version" to "2023-06-01"),
            body.toString()
        )
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message") ?: text
            } catch (e: Exception) {
                text
            }
            throw IOException(
                when (code) {
                    401 -> "API key was rejected. Check it in Settings."
                    429 -> "Too many scans too fast. Wait a few seconds."
                    else -> "Card ID failed ($code): ${msg.take(160)}"
                }
            )
        }
        val blocks = JSONObject(text).optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        val raw = sb.toString()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Couldn't read the card. Try a closer, flatter photo.")
        val j = JSONObject(raw.substring(start, end + 1))
        return CardId(
            game = j.optString("game", "other").lowercase().replace(Regex("[^a-z]"), ""),
            language = j.optString("language", "english").lowercase(),
            name = j.optString("name", ""),
            setName = j.optString("set_name", ""),
            setCode = j.optString("set_code", ""),
            number = j.optString("number", ""),
            rarity = j.optString("rarity", ""),
            variant = j.optString("variant", ""),
            confidence = j.optDouble("confidence", 0.0),
            notes = j.optString("notes", ""),
        )
    }
}
