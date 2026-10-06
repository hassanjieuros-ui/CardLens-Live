package com.cardlens.scan

import org.json.JSONArray
import org.json.JSONObject

data class Group(val id: Int, val name: String, val abbr: String)

data class Printing(
    val productId: Int,
    val name: String,
    val groupName: String,
    val number: String,
    val rarity: String,
    val subType: String,
    val market: Double?,
    val low: Double?,
    val imageUrl: String,
    val url: String,
    val score: Int,
)

/**
 * TCGplayer prices via tcgcsv.com, which mirrors TCGplayer's catalog and prices once a day.
 * Layout: /tcgplayer/categories -> /{cat}/groups -> /{cat}/{group}/products and /prices
 */
class TcgPrices(private val cache: DiskCache) {
    private val base = "https://tcgcsv.com/tcgplayer"
    private val day = 12L * 60 * 60 * 1000
    private val week = 7L * 24 * 60 * 60 * 1000

    private fun results(text: String): JSONArray {
        val t = text.trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("results") ?: JSONArray()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it != "the" && it != "and" }.toSet()

    private fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    fun categoryId(game: String, language: String): Int {
        val japanese = language.startsWith("jap")
        fun wanted(n: String): Boolean = when (game) {
            "pokemon" -> if (japanese) n == "pokemonjapan" else n == "pokemon"
            "yugioh" -> n == "yugioh"
            "onepiece" -> n.startsWith("onepiece")
            else -> false
        }
        try {
            val cats = results(cache.get("$base/categories", week))
            for (i in 0 until cats.length()) {
                val c = cats.getJSONObject(i)
                if (wanted(norm(c.optString("name")))) return c.getInt("categoryId")
            }
        } catch (e: Exception) {
            // fall through to known ids
        }
        return when (game) {
            "pokemon" -> if (japanese) 85 else 3
            "yugioh" -> 2
            "onepiece" -> 68
            else -> -1
        }
    }

    fun groups(categoryId: Int): List<Group> {
        val arr = results(cache.get("$base/$categoryId/groups", day))
        return (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            Group(g.getInt("groupId"), g.optString("name"), g.optString("abbreviation"))
        }
    }

    /** Set prefix from the printed code: "LOB-EN001" -> "LOB", "OP01-001" -> "OP01". */
    private fun setPrefix(id: CardId): String {
        val code = id.setCode.ifBlank { id.number }
        return when (id.game) {
            "yugioh", "onepiece" -> if (code.contains("-")) code.substringBefore("-").trim() else ""
            else -> id.setCode.trim()
        }
    }

    fun rankGroups(all: List<Group>, id: CardId, setNameOverride: String? = null): List<Group> {
        val prefix = setPrefix(id)
        val setName = setNameOverride ?: id.setName
        return all.map { g ->
            var s = 0.0
            if (prefix.isNotBlank() && g.abbr.isNotBlank()) {
                if (g.abbr.equals(prefix, true)) s += 100
                else if (norm(g.abbr) == norm(prefix)) s += 90
            }
            s += similarity(setName, g.name) * 60
            if (setName.isNotBlank() && norm(g.name).contains(norm(setName))) s += 20
            g to s
        }.filter { it.second > 5 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
    }

    private fun numberKeys(game: String, raw: String): Pair<String, String> {
        val clean = raw.uppercase().replace(" ", "")
        if (game == "pokemon" || game == "other") {
            val left = clean.substringBefore("/")
            val numerator = left.replace(Regex("^([A-Z]*)0+(?=\\d)"), "$1")
            return clean to numerator
        }
        return clean to clean
    }

    fun find(id: CardId, setNameOverride: String? = null, numberOverride: String? = null): List<Printing> {
        val cat = categoryId(id.game, id.language)
        if (cat < 0) return emptyList()
        val number = numberOverride ?: id.number.ifBlank { id.setCode }
        val candidatesGroups = rankGroups(groups(cat), id, setNameOverride)
        val (wantFull, wantNum) = numberKeys(id.game, number)

        val out = mutableListOf<Printing>()
        for (g in candidatesGroups) {
            val products = results(cache.get("$base/$cat/${g.id}/products", day))
            val prices = results(cache.get("$base/$cat/${g.id}/prices", day))
            val priceMap = HashMap<Int, MutableList<JSONObject>>()
            for (i in 0 until prices.length()) {
                val p = prices.getJSONObject(i)
                priceMap.getOrPut(p.optInt("productId")) { mutableListOf() }.add(p)
            }
            for (i in 0 until products.length()) {
                val p = products.getJSONObject(i)
                var num = ""
                var rarity = ""
                val ext = p.optJSONArray("extendedData") ?: JSONArray()
                for (k in 0 until ext.length()) {
                    val e = ext.getJSONObject(k)
                    when (e.optString("name")) {
                        "Number" -> num = e.optString("value")
                        "Rarity" -> rarity = e.optString("value")
                    }
                }
                val name = p.optString("name")
                val (full, numer) = numberKeys(id.game, num)
                val nameSim = similarity(id.name, p.optString("cleanName", name))
                var score = 0.0
                if (num.isNotBlank() && wantFull.isNotBlank() && full == wantFull) score += 100
                else if (num.isNotBlank() && wantNum.isNotBlank() && numer == wantNum) score += 75
                score += nameSim * 40
                if (score < 30) continue

                val rows = priceMap[p.optInt("productId")].orEmpty()
                val proto = Printing(
                    productId = p.optInt("productId"),
                    name = name,
                    groupName = g.name,
                    number = num,
                    rarity = rarity,
                    subType = "",
                    market = null,
                    low = null,
                    imageUrl = p.optString("imageUrl"),
                    url = p.optString("url"),
                    score = score.toInt(),
                )
                if (rows.isEmpty()) {
                    out.add(proto)
                } else {
                    for (r in rows) {
                        out.add(
                            proto.copy(
                                subType = r.optString("subTypeName"),
                                market = r.optDoubleOrNull("marketPrice"),
                                low = r.optDoubleOrNull("lowPrice"),
                            )
                        )
                    }
                }
            }
        }
        val variant = id.variant.lowercase()
        return out.sortedWith(
            compareByDescending<Printing> { it.score }
                .thenByDescending { variantHint(variant, it.subType) }
                .thenByDescending { it.market ?: 0.0 }
        ).take(12)
    }

    private fun variantHint(variant: String, subType: String): Int {
        val s = subType.lowercase()
        return when {
            variant.contains("reverse") && s.contains("reverse") -> 2
            variant.contains("1st") && s.contains("1st") -> 2
            variant.contains("holo") && !variant.contains("reverse") && s.contains("holo") && !s.contains("reverse") -> 1
            variant.isBlank() && s == "normal" -> 1
            else -> 0
        }
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
