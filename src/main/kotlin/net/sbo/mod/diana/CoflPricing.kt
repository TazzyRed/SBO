package net.sbo.mod.diana

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.http.Http
import net.sbo.mod.utils.data.SboDataObject.sboData
import net.sbo.mod.utils.data.SboDataObject

/**
 * Estimates real coin-profit-per-collected-coin for the Crown of Avarice via Coflnet's
 * (sky.coflnet.com) public auction price analysis API, instead of assuming a naive 1:1
 * relationship between `collected_coins` NBT and actual profit.
 *
 * Rationale (see CrownOfAvariceTracker for the raw NBT tracking side): a full 1B-coin crown
 * sells on the AH for MORE than 1B coins while Diana events are active and LBIN crowns get
 * bought up ahead of time, then LESS as the market gets flooded with freshly-filled crowns
 * once the event ends. The true "coins per collected coin" is therefore:
 *
 *   (average sale price of a ~full crown over a recent window - cost of the empty crown) / 1e9
 *
 * CONFIRMED against a live response: the analysis endpoint returns "avgPrice" and "medianPrice"
 * at the root of the JSON (we use medianPrice - see extractAveragePrice for why), and the
 * `CollectedCoins=min-max` range filter works as expected. Previously tried adding `HasRune` and
 * `ultimate_habanero_tactics` filters to exclude value-inflated crowns more precisely, but at
 * least one of their exact accepted formats was wrong and Coflnet rejected the whole request
 * outright instead of ignoring the bad param - reverted rather than guess again blind (see
 * maybeRefresh). If the price ever looks wrong, sboverbosecoflnet prints the raw response to
 * check against.
 */
object CoflPricing {
    private const val ITEM_TAG = "CROWN_OF_AVARICE"
    private const val ANALYSIS_DAYS = 7

    // Treat anything at or above this as "essentially a full crown" - the exact cap is
    // 1,000,000,000 but leaving a little slack widens the sample size within the week window.
    private const val NEAR_FULL_MIN = 950_000_000L
    private const val NEAR_FULL_MAX = 1_000_000_000L

    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L // matches Helper.updateItemPriceInfo()'s cadence

    private var cachedAveragePrice: Double? = null
    private var lastFetchAttempt: Long = 0L
    private var fetchInFlight: Boolean = false

    /** Toggled via /sboverbosecoflnet - prints the raw Coflnet response to chat for debugging. */
    var verbose: Boolean = false

    /**
     * Returns the multiplier to apply to raw `collected_coins` diffs to get an estimated real
     * profit. Falls back to 1.0 (naive 1:1) whenever we don't yet have a confident average price,
     * so this NEVER blocks or reduces tracking - it only refines it once data is available.
     */
    fun getCoinMultiplier(): Double {
        maybeRefresh()

        val avgFullPrice = cachedAveragePrice ?: return 1.0
        val costBasis = sboData.crownOfAvariceCostBasis.toDouble()

        val profitPerCrown = avgFullPrice - costBasis
        if (profitPerCrown <= 0.0) return 1.0 // don't invert profit into a negative multiplier

        return profitPerCrown / 1_000_000_000.0
    }

    /**
     * Converts a raw collected-coins-equivalent total into an estimated real profit, using
     * whatever the CURRENT multiplier is. Deliberately not baked in/stored anywhere - computing
     * this live means the number always reflects the latest Coflnet price and cost basis, instead
     * of being frozen at whatever multiplier happened to be cached the moment coins were credited.
     */
    fun estimateProfit(rawCoins: Long): Long {
        if (rawCoins <= 0) return 0L
        return (rawCoins * getCoinMultiplier()).toLong().coerceAtLeast(0L)
    }

    /**
     * Formats a value to exactly 2 decimal places by truncating rather than rounding, so e.g. a
     * multiplier of 2.199 always displays as "2.19x", never rounds up to "2.20x".
     */
    fun truncate2(value: Double): String {
        val truncated = kotlin.math.floor(value * 100.0) / 100.0
        return "%.2f".format(truncated)
    }

    fun setCostBasis(amount: Long) {
        sboData.crownOfAvariceCostBasis = amount
        SboDataObject.save("SboData")
    }

    /** Forces an immediate refresh regardless of the cache interval, used by /sborefreshcoflnet. */
    fun forceRefresh() {
        lastFetchAttempt = 0L
        maybeRefresh()
    }

    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (fetchInFlight || now - lastFetchAttempt < REFRESH_INTERVAL_MS) return
        lastFetchAttempt = now
        fetchInFlight = true

        // CollectedCoins is the one filter confirmed to actually work (verified against a live
        // response earlier). HasRune and ultimate_habanero_tactics were added as an attempt to
        // exclude value-inflated crowns (runes, Habanero Tactics), but at least one of their
        // exact accepted formats was wrong - Coflnet rejected the whole request with "invalid
        // parameters in the http url or the post data" instead of just ignoring the bad param(s),
        // breaking the fetch entirely. Reverted to the known-good query rather than guess again
        // blind. extractAveragePrice() still prefers the median over the mean as a (weaker, but
        // functioning) mitigation for the same outlier-price problem those filters were meant to
        // address more precisely.
        val url = "https://sky.coflnet.com/api/item/price/$ITEM_TAG/analysis" +
                "?days=$ANALYSIS_DAYS&CollectedCoins=$NEAR_FULL_MIN-$NEAR_FULL_MAX"

        Http.sendGetRequest(url)
            .toJsonObject { json ->
                fetchInFlight = false
                if (verbose) Chat.chat("§6[SBO] §7Coflnet crown price response: $json")

                val price = extractAveragePrice(json)
                if (price != null && price > 0.0) {
                    cachedAveragePrice = price
                    if (verbose) Chat.chat("§6[SBO] §aParsed average full-crown price: §e${price.toLong()}")
                } else if (verbose) {
                    Chat.chat("§6[SBO] §cCould not find a recognizable price field in the response above.")
                }
            }
            .error { e ->
                fetchInFlight = false
                if (verbose) Chat.chat("§6[SBO] §cCoflnet crown price request failed: ${e.message}")
            }
    }

    /**
     * Confirmed against a live response: the analysis endpoint returns "avgPrice" AND
     * "medianPrice" at the root of the JSON. We prefer medianPrice: even with HasRune and
     * ultimate_habanero_tactics filtered out server-side (see maybeRefresh), there could be other
     * value-adding attributes we're not filtering for, and a handful of such outlier sales can
     * pull the MEAN well above what a plain crown actually sells for. The median needs a genuine
     * majority of inflated sales to move it, not just a few, so it's a much safer default.
     */
    private fun extractAveragePrice(json: JsonObject): Double? {
        val candidateKeys = listOf(
            "medianPrice", "median",
            "avgPrice", "averagePrice", "average", "avg"
        )

        for (key in candidateKeys) {
            val value = json[key]?.jsonPrimitive?.doubleOrNull
            if (value != null) return value
        }

        return null
    }
}
