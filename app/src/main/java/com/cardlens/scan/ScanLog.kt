package com.cardlens.scan

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One scan that is waiting for the user to pick, fix, or discard it. */
class PendingScan(
    val scanId: String,
    val startedAt: Long,
    val model: String,
    val identifyMs: Long,
    var priceMs: Long,
    val id: CardId,
    var printings: List<Printing>,
    var researched: Boolean = false,
    var corrected: CardId? = null,
)

data class LogStats(
    val counted: Int,
    val firstTry: Int,
    val inList: Int,
    val fixed: Int,
    val missed: Int,
    val avgSeconds: Double,
    val totalLogged: Int,
)

/**
 * Keeps every scan as training data: the exact photo sent for identification, what the app
 * guessed, and what the user actually chose or corrected. Stored in the app's private files.
 *
 * result values:
 *  first_try  = the top match was the right card (finish choice doesn't count against it)
 *  in_list    = right card was in the list, but not at the top
 *  fixed      = user had to correct set/number/name and search again
 *  manual     = user typed their own price (no match found)
 *  discarded  = user threw the scan away
 *  abandoned  = user started a new scan without choosing
 *  error      = identification or price lookup failed
 */
class ScanLog(private val dir: File) {
    init {
        dir.mkdirs()
    }

    private val logFile get() = File(dir, "log.jsonl")

    fun newScanId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    fun savePhoto(scanId: String, jpeg: ByteArray) {
        File(dir, "$scanId.jpg").writeBytes(jpeg)
    }

    @Synchronized
    private fun append(o: JSONObject) {
        logFile.appendText(o.toString() + "\n")
    }

    private fun idJson(id: CardId) = JSONObject()
        .put("game", id.game).put("language", id.language).put("name", id.name)
        .put("set_name", id.setName).put("set_code", id.setCode).put("number", id.number)
        .put("rarity", id.rarity).put("variant", id.variant)
        .put("confidence", id.confidence).put("notes", id.notes)

    private fun printingJson(p: Printing) = JSONObject()
        .put("product_id", p.productId).put("name", p.name).put("set", p.groupName)
        .put("number", p.number).put("rarity", p.rarity).put("finish", p.subType)
        .put("market", p.market ?: JSONObject.NULL).put("url", p.url)

    fun finish(
        p: PendingScan,
        outcome: String,
        chosen: Printing?,
        manualPrice: Double?,
        condition: Condition,
    ) {
        val distinct = p.printings.map { it.productId }.distinct()
        val rank = if (chosen != null) distinct.indexOf(chosen.productId) else -1
        val result = when (outcome) {
            "added" -> when {
                p.researched -> "fixed"
                rank == 0 -> "first_try"
                else -> "in_list"
            }
            else -> outcome
        }
        val o = JSONObject()
            .put("scan_id", p.scanId)
            .put("photo", "${p.scanId}.jpg")
            .put("at", p.startedAt)
            .put("model", p.model)
            .put("identify_ms", p.identifyMs)
            .put("price_ms", p.priceMs)
            .put("result", result)
            .put("app_read", idJson(p.id))
            .put("candidates", p.printings.size)
            .put("top_candidate", p.printings.firstOrNull()?.let { printingJson(it) } ?: JSONObject.NULL)
            .put("chosen", chosen?.let { printingJson(it) } ?: JSONObject.NULL)
            .put("chosen_rank", rank)
            .put("corrected_to", p.corrected?.let { idJson(it) } ?: JSONObject.NULL)
            .put("manual_price", manualPrice ?: JSONObject.NULL)
            .put("condition", condition.name)
        append(o)
    }

    fun error(scanId: String, startedAt: Long, model: String, stage: String, message: String) {
        append(
            JSONObject()
                .put("scan_id", scanId).put("photo", "$scanId.jpg").put("at", startedAt)
                .put("model", model).put("result", "error").put("stage", stage).put("error", message)
        )
    }

    fun stats(lastN: Int = 30): LogStats {
        val lines = if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() } else emptyList()
        val recent = lines.takeLast(lastN).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        var first = 0
        var inList = 0
        var fixed = 0
        var missed = 0
        var timeSum = 0L
        var timed = 0
        for (o in recent) {
            when (o.optString("result")) {
                "first_try" -> first++
                "in_list" -> inList++
                "fixed" -> fixed++
                else -> missed++
            }
            if (o.has("identify_ms")) {
                timeSum += o.optLong("identify_ms") + o.optLong("price_ms")
                timed++
            }
        }
        return LogStats(
            counted = recent.size,
            firstTry = first,
            inList = inList,
            fixed = fixed,
            missed = missed,
            avgSeconds = if (timed > 0) timeSum / 1000.0 / timed else 0.0,
            totalLogged = lines.size,
        )
    }

    /** Zips the log and every photo so it can be shared off the phone. */
    fun exportZip(outDir: File): File {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val zip = File(outDir, "cardlens-scans-$stamp.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isFile) {
                    out.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return zip
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
