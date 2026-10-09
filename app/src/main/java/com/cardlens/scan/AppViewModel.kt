package com.cardlens.scan

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Condition(val label: String, val mult: Double) {
    NM("NM", 1.0), LP("LP", 0.85), MP("MP", 0.70), HP("HP", 0.50), DMG("DMG", 0.30)
}

data class LotItem(
    val name: String,
    val detail: String,
    val condition: Condition,
    val market: Double,
)

sealed interface Phase {
    data object Idle : Phase
    data class Working(val message: String) : Phase
    data class Result(val photo: Bitmap?, val id: CardId, val printings: List<Printing>) : Phase
    data class Failed(val message: String) : Phase
}

object Models {
    const val FAST = "claude-haiku-4-5-20251001"
    const val ACCURATE = "claude-sonnet-5"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cardlens", Context.MODE_PRIVATE)
    private val cacheDir = File(app.cacheDir, "tcgcsv")
    private val prices = TcgPrices(DiskCache(cacheDir))
    private val scanLog = ScanLog(File(app.filesDir, "scans"))
    private var pending: PendingScan? = null

    var apiKey by mutableStateOf(prefs.getString("apiKey", "") ?: "")
        private set
    var model by mutableStateOf(prefs.getString("model", Models.FAST) ?: Models.FAST)
        private set
    var buyPercent by mutableStateOf(prefs.getInt("buyPercent", 60))
        private set
    var condition by mutableStateOf(Condition.NM)
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var toast by mutableStateOf<String?>(null)

    val lot = mutableStateListOf<LotItem>()

    init {
        loadLot()
    }

    fun offerFor(market: Double, cond: Condition): Double = market * cond.mult * buyPercent / 100.0

    val lotMarket: Double get() = lot.sumOf { it.market * it.condition.mult }
    val lotOffer: Double get() = lot.sumOf { offerFor(it.market, it.condition) }

    fun saveSettings(key: String, newModel: String, percent: Int) {
        apiKey = key.trim()
        model = newModel
        buyPercent = percent.coerceIn(1, 100)
        prefs.edit()
            .putString("apiKey", apiKey)
            .putString("model", model)
            .putInt("buyPercent", buyPercent)
            .apply()
    }

    fun nudgeBuyPercent(delta: Int) {
        saveSettings(apiKey, model, buyPercent + delta)
    }

    fun clearPriceCache() {
        DiskCache(cacheDir).clear()
        toast = "Prices will re-download on the next scan"
    }

    fun onPhoto(uri: Uri) {
        val ctx = getApplication<Application>()
        // A new scan replaces one the user never chose from.
        pending?.let { logFinish(it, "abandoned", null, null) }
        pending = null
        val scanId = scanLog.newScanId()
        val started = System.currentTimeMillis()
        val usedModel = model
        viewModelScope.launch {
            var stage = "photo"
            try {
                phase = Phase.Working("Reading photo…")
                val (bmp, b64) = withContext(Dispatchers.IO) { ImageUtil.load(ctx, uri) }
                withContext(Dispatchers.IO) {
                    runCatching { scanLog.savePhoto(scanId, Base64.decode(b64, Base64.NO_WRAP)) }
                }
                stage = "identify"
                phase = Phase.Working("Identifying card…")
                val t0 = System.currentTimeMillis()
                val id = withContext(Dispatchers.IO) { Identifier.identify(apiKey, usedModel, b64) }
                val identifyMs = System.currentTimeMillis() - t0
                if (id.game == "other") {
                    pending = PendingScan(scanId, started, usedModel, identifyMs, 0, id, emptyList())
                    phase = Phase.Result(bmp, id, emptyList())
                    return@launch
                }
                stage = "price"
                phase = Phase.Working("Pulling TCGplayer prices…")
                val t1 = System.currentTimeMillis()
                val found = withContext(Dispatchers.IO) { prices.find(id) }
                val priceMs = System.currentTimeMillis() - t1
                pending = PendingScan(scanId, started, usedModel, identifyMs, priceMs, id, found)
                phase = Phase.Result(bmp, id, found)
            } catch (e: Exception) {
                val msg = e.message ?: "Something went wrong. Try again."
                withContext(Dispatchers.IO) {
                    runCatching { scanLog.error(scanId, started, usedModel, stage, msg) }
                }
                phase = Phase.Failed(msg)
            }
        }
    }

    /** Re-run the price match with corrected set/number, without paying for another scan. */
    fun research(setName: String, number: String, name: String) {
        val current = phase as? Phase.Result ?: return
        val id = current.id.copy(setName = setName, number = number, name = name, setCode = "")
        viewModelScope.launch {
            try {
                phase = Phase.Working("Searching again…")
                val found = withContext(Dispatchers.IO) { prices.find(id, setName, number) }
                pending?.let {
                    it.researched = true
                    it.corrected = id
                    it.printings = found
                }
                phase = Phase.Result(current.photo, id, found)
            } catch (e: Exception) {
                toast = e.message ?: "Search failed. Check your connection."
                phase = current
            }
        }
    }

    fun add(p: Printing) {
        pending?.let { logFinish(it, "added", p, null) }
        pending = null
        val m = p.market ?: p.low ?: 0.0
        val detail = listOf(p.groupName, p.number, p.subType).filter { it.isNotBlank() }.joinToString(" · ")
        addItem(LotItem(p.name, detail, condition, m))
    }

    fun addManual(name: String, market: Double) {
        pending?.let { logFinish(it, "manual", null, market) }
        pending = null
        addItem(LotItem(name.ifBlank { "Manual card" }, "Manual price", condition, market))
    }

    private fun logFinish(p: PendingScan, outcome: String, chosen: Printing?, manualPrice: Double?) {
        val cond = condition
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { scanLog.finish(p, outcome, chosen, manualPrice, cond) }
        }
    }

    fun scanStats(): LogStats = runCatching { scanLog.stats(30) }
        .getOrDefault(LogStats(0, 0, 0, 0, 0, 0.0, 0))

    fun exportScans(): File = scanLog.exportZip(File(getApplication<Application>().cacheDir, "exports"))

    fun clearScans() {
        pending = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { scanLog.clear() } }
        toast = "Scan log cleared"
    }

    private fun addItem(item: LotItem) {
        lot.add(0, item)
        saveLot()
        toast = "Added ${item.name} · offer ${money(offerFor(item.market, item.condition))}"
        phase = Phase.Idle
    }

    fun remove(item: LotItem) {
        lot.remove(item)
        saveLot()
    }

    fun clearLot() {
        lot.clear()
        saveLot()
    }

    fun dismiss() {
        if (phase is Phase.Result) {
            pending?.let { logFinish(it, "discarded", null, null) }
            pending = null
        }
        phase = Phase.Idle
    }

    private fun saveLot() {
        val arr = JSONArray()
        lot.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("detail", it.detail)
                    .put("cond", it.condition.name).put("market", it.market)
            )
        }
        prefs.edit().putString("lot", arr.toString()).apply()
    }

    private fun loadLot() {
        try {
            val arr = JSONArray(prefs.getString("lot", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lot.add(
                    LotItem(
                        o.optString("name"), o.optString("detail"),
                        runCatching { Condition.valueOf(o.optString("cond")) }.getOrDefault(Condition.NM),
                        o.optDouble("market", 0.0)
                    )
                )
            }
        } catch (e: Exception) {
            // start with an empty lot
        }
    }
}

fun money(v: Double): String = String.format(java.util.Locale.US, "$%,.2f", v)
