package net.sbo.mod.diana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.http.Http
import java.time.Instant

/**
 * Tracks the current market value of a filled (1B+ coins) Crown of Avarice, sourced
 * from Coflnet's sold-auction history, so the crown coin multiplier never has to be
 * typed in by hand. See LoadoutCrownTracker.multiplierFor() for how this combines
 * with a crown's own cost basis to produce its per-coin profit multiplier.
 */
object CrownMarket {
    private const val SOLD_URL = "https://sky.coflnet.com/api/auctions/tag/CROWN_OF_AVARICE/sold"
    private const val BIN_URL = "https://sky.coflnet.com/api/auctions/tag/CROWN_OF_AVARICE/active/bin"
    private const val MIN_FILLED_COINS = 1_000_000_000L
    private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000L
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    private const val SAMPLE_SIZE = 20

    /** Flat approximation of Hypixel's AH claim tax on a ~1B+ BIN sale. */
    const val AH_TAX = 40_000_000L

    var weeklyAvgFilledPrice: Double = 0.0
        private set
    private var lastFetchMs: Long = 0L
    var lastFetchFailed: Boolean = false
        private set

    fun init() {
        refresh()
        Register.onTick(1200) {
            if (System.currentTimeMillis() - lastFetchMs >= REFRESH_INTERVAL_MS) refresh()
        }
    }

    fun refresh() {
        lastFetchMs = System.currentTimeMillis()
        Http.sendGetRequest(SOLD_URL).result { response ->
            if (!response.isSuccessful) {
                fetchActiveBinsFallback()
                return@result
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                fetchActiveBinsFallback()
                return@result
            }
            try {
                val prices = extractFilledPrices(body, filterToWeek = true)
                if (prices.isEmpty()) fetchActiveBinsFallback() else applyAverage(prices)
            } catch (e: Exception) {
                fetchActiveBinsFallback()
            }
        }.error { fetchActiveBinsFallback() }
    }

    private fun fetchActiveBinsFallback() {
        Http.sendGetRequest(BIN_URL).result { response ->
            if (!response.isSuccessful) {
                lastFetchFailed = true
                return@result
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                lastFetchFailed = true
                return@result
            }
            try {
                val prices = extractFilledPrices(body, filterToWeek = false)
                if (prices.isEmpty()) lastFetchFailed = true else applyAverage(prices.sorted().take(5))
            } catch (e: Exception) {
                lastFetchFailed = true
            }
        }.error { lastFetchFailed = true }
    }

    private fun applyAverage(prices: List<Double>) {
        weeklyAvgFilledPrice = prices.average()
        lastFetchFailed = false
        LoadoutCrownTracker.onMarketDataUpdated()
    }

    private fun extractFilledPrices(body: String, filterToWeek: Boolean): List<Double> {
        val root = Json.parseToJsonElement(body)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["auctions"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        val cutoff = System.currentTimeMillis() - ONE_WEEK_MS
        val results = mutableListOf<Double>()
        for (el in array) {
            val obj = el as? JsonObject ?: continue
            val coins = collectedCoinsOf(obj) ?: continue
            if (coins < MIN_FILLED_COINS) continue
            if (filterToWeek) {
                val end = obj["end"]?.jsonPrimitive?.longOrNull
                    ?: obj["timestamp"]?.jsonPrimitive?.longOrNull
                if (end != null && end < cutoff) continue
            }
            val price = obj["highestBidAmount"]?.jsonPrimitive?.doubleOrNull
                ?: obj["startingBid"]?.jsonPrimitive?.doubleOrNull
                ?: continue
            if (price <= 0) continue
            results.add(price)
            if (results.size >= SAMPLE_SIZE) break
        }
        return results
    }

    private fun collectedCoinsOf(obj: JsonObject): Long? {
        val flatEl = obj["flattenedNbt"]
        val flatObj: JsonObject? = when (flatEl) {
            is JsonObject -> flatEl
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(flatEl.content).jsonObject }.getOrNull()
            else -> null
        }
        flatObj?.get("collected_coins")?.jsonPrimitive?.longOrNull?.let { return it }

        val nbtEl = obj["nbtData"]
        val nbtObj: JsonObject = when (nbtEl) {
            is JsonObject -> nbtEl
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(nbtEl.content).jsonObject }.getOrNull()
            else -> null
        } ?: return null
        val items = nbtObj["i"] as? JsonArray ?: return null
        for (item in items) {
            val tag = (item as? JsonObject)?.get("tag") as? JsonObject ?: continue
            val ea = tag["ExtraAttributes"] as? JsonObject ?: continue
            val coins = ea["collected_coins"]?.jsonPrimitive?.longOrNull
            if (coins != null) return coins
        }
        return null
    }

    /** One recorded past sale of a specific physical crown, from Coflnet's per-item AH history. */
    data class ItemSale(val price: Long, val timestampMs: Long, val itemTag: String?)

    /**
     * Looks up this exact physical item's (by its own persistent uuid, not the
     * CROWN_OF_AVARICE item tag) full AH sale history via Coflnet's per-item
     * lookup - meant upstream for dupe detection, but it's exactly what's needed
     * to find what a specific crown was actually paid for. This is retroactive:
     * it works the same whether the sale happened yesterday or long before this
     * mod ever saw the crown, unlike the purse-drop heuristic which only catches
     * a purchase/craft happening live.
     *
     * Calls [onResult] with null if the request itself failed (network/parse
     * error - unknown, not "no sale"), or with a list (possibly empty, meaning
     * genuinely no recorded AH sale - likely crafted, traded, or gifted) on success.
     *
     * Only records tagged CROWN_OF_AVARICE are returned. A crown's uuid can carry
     * sale history from BEFORE it was a crown at all: Crown of Greed upgrades into
     * Crown of Avarice in place, keeping the same physical-item uuid, so an
     * unfiltered lookup can return a handful of coins from when it was still an
     * unrelated, far cheaper item. A record with no tag at all (can't verify) is
     * kept rather than dropped, since Coflnet doesn't guarantee it's always present.
     */
    fun lookupSaleHistory(uuid: String, onResult: (List<ItemSale>?) -> Unit) {
        val url = "https://sky.coflnet.com/api/auctions/uid/$uuid/sold"
        Http.sendGetRequest(url).result { response ->
            if (!response.isSuccessful) {
                onResult(null)
                return@result
            }
            val body = response.body?.string()
            if (body == null) {
                onResult(null)
                return@result
            }
            try {
                onResult(parseItemSales(body))
            } catch (e: Exception) {
                onResult(null)
            }
        }.error { onResult(null) }
    }

    private fun parseItemSales(body: String): List<ItemSale> {
        if (body.isBlank()) return emptyList()
        val array = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        val result = mutableListOf<ItemSale>()
        for (el in array) {
            val obj = el as? JsonObject ?: continue
            val tag = obj["itemTag"]?.jsonPrimitive?.contentOrNull
            if (tag != null && tag != "CROWN_OF_AVARICE") continue
            val price = obj["highestBid"]?.jsonPrimitive?.longOrNull ?: continue
            // The API docs say ISO-8601 date-time, but that's not always what comes
            // back in practice - fall back to treating it as a raw epoch (ms, or
            // seconds if it's clearly too small to be milliseconds) rather than
            // silently landing on 0 and making "most recent sale" picks arbitrary.
            val tsField = obj["timestamp"]?.jsonPrimitive
            val tsMs = tsField?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: tsField?.longOrNull?.let { if (it in 1..9_999_999_999L) it * 1000 else it }
                ?: 0L
            result.add(ItemSale(price, tsMs, tag))
        }
        return result
    }

    /**
     * Per-coin profit multiplier for a crown with the given cost basis:
     * (current 1-week market value of a filled crown - AH tax - what this crown
     * cost) spread across only the coins YOU actually deposit - the crown's 1B
     * capacity minus whatever collected_coins was already on it when first seen
     * (startingCoins - e.g. bought pre-filled off the AH). Without netting this
     * out, a pre-filled crown would understate its real per-coin return, since
     * part of that 1B was never coins you personally ground out. Falls back to
     * 1.0x (raw coins, no markup) when market data or a cost basis isn't
     * available yet.
     */
    fun multiplierFor(costBasis: Long, startingCoins: Long = 0L): Double {
        if (weeklyAvgFilledPrice <= 0.0 || costBasis < 0L) return 1.0
        val net = weeklyAvgFilledPrice - AH_TAX - costBasis
        val yourDeposit = (MIN_FILLED_COINS - startingCoins.coerceAtLeast(0L)).coerceAtLeast(1L)
        return (net / yourDeposit).coerceAtLeast(0.0)
    }
}
