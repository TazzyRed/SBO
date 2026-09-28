package net.sbo.mod.diana

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.EquipmentSlot
import net.sbo.mod.SBOKotlin
import net.sbo.mod.overlays.DianaCrownTracker
import net.sbo.mod.overlays.DianaLoot
import net.sbo.mod.settings.categories.Debug
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Helper
import net.sbo.mod.utils.Helper.removeFormatting
import net.sbo.mod.utils.SboTimerManager
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.data.CrownBaselineEntry
import net.sbo.mod.utils.data.SboDataObject
import net.sbo.mod.utils.events.DianaEvents
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.diana.BurrowDugEvent
import net.sbo.mod.utils.events.impl.entity.DianaMobDeathEvent
import net.sbo.mod.utils.events.impl.game.InventorySlotUpdateEvent
import net.sbo.mod.utils.events.impl.guis.GuiOpenEvent
import net.sbo.mod.utils.game.ItemLookup
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.math.SboVec
import java.time.Instant

/**
 * Tracks Crown of Avarice progress by reading the player-head NBT shown in the
 * /loadout menu. Identity is keyed by LOADOUT NAME (as shown in the menu / in
 * the "You equipped X!" chat message), not by crown uuid, since:
 *  - loadout names are custom text set by the player and are the stable,
 *    human-meaningful identifier ("LCM set", "farming", "Loadout 6", etc.)
 *  - the physical crown sitting in a given loadout can be swapped out over
 *    time (e.g. replacing it with a freshly-leveled one), so the uuid is
 *    expected to change under an otherwise-trusted, already-confirmed name.
 *
 * A loadout only becomes "confirmed" (i.e. its coin gains actually get
 * credited to DianaTracker) once we've seen it equipped via chat at least
 * SWAP_THRESHOLD times. Until then its baseline is tracked silently so no
 * data is lost, but nothing is credited - this stops an unrelated loadout
 * that happens to have an old/idle crown in it from polluting stats.
 *
 * Once a name is confirmed, seeing a NEW crown uuid under that SAME name is
 * treated as a trusted crown replacement (immediate re-baseline, no need to
 * re-earn confirmation) rather than as a brand new unverified loadout.
 */
object LoadoutCrownTracker {

    private const val CROWN_ID = "CROWN_OF_AVARICE"
    private const val COINS_KEY = "collected_coins"
    private const val MAX_TRACKED_CROWNS = 3
    private const val STALE_THRESHOLD_MS = 2_700_000L
    private const val SWAP_THRESHOLD = 2

    // How close (blocks) and how soon (ms) after a burrow dig a mob death must be
    // to be considered "our" burrow's mob. Generous on purpose: boss-type Diana
    // mobs (Minos Inquisitor especially) commonly take well over a minute of
    // chasing/fighting before the kill, and the chase itself can drift you a
    // fair distance from the original dig site. Too tight here means the death
    // silently falls outside the window - no activity gets marked, no recheck
    // happens, and any pause from AFK during the chase never gets lifted.
    private const val BURROW_MOB_MAX_DIST = 30.0
    private const val BURROW_MOB_MAX_MS = 90_000L

    // How recently a purse decrease must have happened, relative to a brand-new
    // crown uuid showing up, to be trusted as that crown's cost basis. Covers
    // both crafting it (materials bought/consumed around the same moment) and
    // buying it filled off the AH - mechanically both are just "coins left the
    // purse right as this new crown appeared", which is all we need.
    private const val PURSE_DROP_WINDOW_MS = 15_000L

    // A single credited reading above this is treated as implausible for solo
    // play and gets rejected (see the diff check in handleReading). A single
    // treasure burrow can legitimately deposit up to ~6M on its own, so this
    // sits just above that - enough headroom for one dig, without opening the
    // door wide enough to let a traded-away-and-back crown's gap slip through
    // uncaught (the scenario this exists to catch).
    private const val SUSPICIOUS_JUMP_THRESHOLD = 7_500_000L

    // Hypixel's own default label for an unnamed loadout slot ("Loadout 1",
    // "Loadout 2", etc.) - one of the /loadout GUI's views falls back to this
    // instead of the crown's real custom name. See the rename-detection guard
    // in handleReading() for why this matters.
    private val GENERIC_LOADOUT_NAME_REGEX = Regex("""^Loadout \d+$""", RegexOption.IGNORE_CASE)

    private const val ROLE_DAMAGE = "DAMAGE"
    private const val ROLE_MAGIC_FIND = "MAGIC_FIND"

    private const val SOURCE_MANUAL = "MANUAL"
    private const val SOURCE_AH_HISTORY = "AH_HISTORY"
    private const val SOURCE_PURSE_DETECTED = "PURSE_DETECTED"
    private const val SOURCE_UNSET = "UNSET"

    private val equipRegex = Regex("(?i)^you equipped (.+)!$")

    // Shares the same "Debug Messages" toggle as the rest of the mod (/sbo ->
    // Debug), rather than a separate crown-only flag - one switch, whether it's
    // flipped from the settings GUI or via /sbocrown debug.
    private val verbose: Boolean get() = Debug.debugMessages

    private var lastBurrowDugPos: SboVec? = null
    private var lastBurrowDugAtMs: Long = 0L

    private var lastKnownPurse: Long = -1L
    private var lastPurseDropAmount: Long = 0L
    private var lastPurseDropAtMs: Long = 0L

    /** Which trigger produced a given credited reading. */
    enum class TrackSource { GUI, BURROW, MOB, CHAT }

    // --- Detached overlay session stats ---
    var sessionStartMs: Long = System.currentTimeMillis()
        private set
    var sessionTotalGained: Long = 0L
        private set
    var sessionLastAnyGain: Long = 0L
        private set

    // Per-crown session gains (in-memory only, not persisted)
    private val crownSessionGains: MutableMap<String, Long> = mutableMapOf()

    /**
     * Confirmed crowns as [Damage Crown, Magic Find Crown] (either slot may be
     * absent if you're not tracking that many). A crown manually tagged via
     * /sbocrown damage or /sbocrown magicfind always fills its slot; any
     * remaining slot is auto-filled from the rest, ranked by lifetime gain (then
     * update count) - which is only a meaningful signal once you've actually
     * ground some Diana, so untagged crowns before that point land in a
     * basically arbitrary order until real data comes in.
     */
    fun getConfirmedCrowns(): List<CrownBaselineEntry> {
        val confirmed = SboDataObject.sboData.crownTrackedBaselines.filter { it.confirmed }
        val damageSlot = confirmed.firstOrNull { it.role == ROLE_DAMAGE }
        val magicFindSlot = confirmed.firstOrNull { it.role == ROLE_MAGIC_FIND }
        val pool = confirmed
            .filter { it !== damageSlot && it !== magicFindSlot }
            .sortedWith(compareByDescending<CrownBaselineEntry> { it.totalGained }.thenByDescending { it.updateCount })
            .toMutableList()

        val slot0 = damageSlot ?: pool.removeFirstOrNullCompat()
        val slot1 = magicFindSlot ?: pool.removeFirstOrNullCompat()
        return listOfNotNull(slot0, slot1)
    }

    private fun MutableList<CrownBaselineEntry>.removeFirstOrNullCompat(): CrownBaselineEntry? =
        if (isEmpty()) null else removeAt(0)

    /** Display label for a manually-tagged crown, or null if it's untagged (caller picks a fallback). */
    fun roleLabel(entry: CrownBaselineEntry): String? = when (entry.role) {
        ROLE_DAMAGE -> "Damage Crown"
        ROLE_MAGIC_FIND -> "Magic Find Crown"
        else -> null
    }

    fun getSessionGainForCrown(loadoutName: String): Long =
        crownSessionGains[loadoutName] ?: 0L

    fun resetDetachedSessionStats() {
        sessionStartMs = System.currentTimeMillis()
        sessionTotalGained = 0L
        sessionLastAnyGain = 0L
        crownSessionGains.clear()
    }

    /** Raw crown coins gained for the given loot-tracker period (SESSION/EVENT/TOTAL). */
    fun getPeriodGain(type: Diana.Tracker): Long = when (type) {
        Diana.Tracker.TOTAL -> SboDataObject.sboData.crownGainedTotal
        Diana.Tracker.EVENT -> SboDataObject.sboData.crownGainedEvent
        else -> lootTrackerTotalGained
    }

    /** Same as getPeriodGain(), scoped to a single crown - powers each crown's own coins/hr line. */
    fun getPeriodGainForCrown(entry: CrownBaselineEntry, type: Diana.Tracker): Long = when (type) {
        Diana.Tracker.TOTAL -> entry.totalGained
        Diana.Tracker.EVENT -> entry.eventGained
        else -> getSessionGainForCrown(entry.loadoutName)
    }

    /** Resets the EVENT-period crown gain (global and per-crown) - called when a new mayor term starts. */
    fun resetEventGain() {
        SboDataObject.sboData.crownGainedEvent = 0L
        for (entry in SboDataObject.sboData.crownTrackedBaselines) entry.eventGained = 0L
        SboDataObject.save("SboData")
    }

    fun multiplierFor(entry: CrownBaselineEntry): Double =
        CrownMarket.multiplierFor(entry.costBasis, entry.startingCoins)

    fun onMarketDataUpdated() {
        DianaCrownTracker.updateLines()
        DianaLoot.updateLines()
    }

    // --- Loot tracker period stats (synced with session/event reset) ---
    /** Total raw crown coins gained since the last loot tracker session reset. */
    var lootTrackerTotalGained: Long = 0L
        private set

    /**
     * Resets the per-crown session baselines and the loot-tracker total.
     * Called by DianaLoot.resetSession() so the crown line resets in sync
     * with the rest of the tracker.
     */
    fun resetLootTrackerBaselines() {
        lootTrackerTotalGained = 0L
        crownSessionGains.clear()
        val baselines = SboDataObject.sboData.crownTrackedBaselines
        for (entry in baselines) {
            entry.sessionBaseCoins = entry.coins
        }
        SboDataObject.save("SboData")
    }

    fun init() {
        Register.onChatMessage(Regex("(?i)^you equipped .+!$"), true) { message, _ ->
            if (!Diana.enableLoadoutCrownTracker) return@onChatMessage
            val match = equipRegex.find(message.string) ?: return@onChatMessage
            val loadoutName = match.groupValues[1].removeFormatting().trim()
            if (loadoutName.isNotEmpty()) onLoadoutSwapped(loadoutName)
        }

        // The crown's own "you dug out X coins" popup - a direct signal that the
        // worn crown's collected_coins just changed, moved over from the old
        // CrownOfAvariceTracker (which used it to trigger the worn-item recheck).
        val dugCoinsPattern = java.util.regex.Pattern.compile(
            "^\u00a76\u00a7lWow! \u00a7eYou dug out \u00a76(.*?) coins\u00a7e!$",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )
        Register.onChatMessageCancelable(dugCoinsPattern) { _, _ ->
            if (!Diana.enableLoadoutCrownTracker) return@onChatMessageCancelable false
            // Hypixel's own server message confirming a real coin gain - about
            // as trustworthy a proof of activity as we'll ever get.
            SboTimerManager.updateAllActivity()
            recheckWornItem(TrackSource.CHAT)
            true
        }

        // BurrowDugEvent only covers one of three real "burrow was dug" cases -
        // see onRealBurrowDig's doc comment. These two chat triggers cover the
        // other two, using the exact same regexes BurrowDetector.kt already
        // uses to solve this same problem.
        Register.onChatMessage(Regex("""^§eYou finished the Griffin burrow chain!.*$""")) { _, _ ->
            if (Diana.enableLoadoutCrownTracker) onRealBurrowDig()
        }
        Register.onChatMessage(Regex(""".*§eYou (?:just )?dug out(?!.*\(\d+/\d+\)$).*""")) { _, _ ->
            if (Diana.enableLoadoutCrownTracker) onRealBurrowDig()
        }

        Register.command("sboresetcrownloadouts") {
            SboDataObject.sboData.crownLoadoutSwapCounts.clear()
            SboDataObject.sboData.crownTrackedBaselines.clear()
            SboDataObject.save("SboData")
            Chat.chat("§6[SBO] §aCleared all tracked crown loadouts and baselines.")
        }

        Register.command("sbocrown") { args -> handleSbocrownCommand(args) }

        Register.onTick(5) { trackPurseForCostBasis() }

        sanitizeLegacyBaselines()
        mergeDuplicateUuidEntries()
    }

    private fun handleSbocrownCommand(args: Array<String>) {
        if (args.isEmpty()) {
            sendSbocrownUsage()
            return
        }
        when (args[0].lowercase()) {
            "price" -> handlePriceSubcommand(args)
            "damage", "dmg" -> tagWornCrown(ROLE_DAMAGE)
            "magicfind", "mf" -> tagWornCrown(ROLE_MAGIC_FIND)
            "cleartag", "clearrole", "auto" -> tagWornCrown(null)
            "resetprice", "clearprice", "redetect" -> resetAndRedetectPrice()
            "debug" -> toggleDebug()
            else -> sendSbocrownUsage()
        }
    }

    private fun sendSbocrownUsage() {
        Chat.chat("§6[SBO] §eCrown tracker commands:")
        Chat.chat("§7 /sbocrown price <amount> §8- §7coin cost of the crown you're wearing (500m, 1b, etc).")
        Chat.chat("§7 /sbocrown damage §8- §7tag the worn crown as the Damage Crown.")
        Chat.chat("§7 /sbocrown magicfind §8- §7tag the worn crown as the Magic Find Crown.")
        Chat.chat("§7 /sbocrown cleartag §8- §7remove a manual tag, back to auto-ranking by gain.")
        Chat.chat("§7 /sbocrown resetprice §8- §7clear the worn crown's price and re-run auto-detection.")
        Chat.chat("§7 /sbocrown debug §8- §7toggle /sbo -> Debug -> Debug Messages (shared mod-wide, also logs crown detection attempts).")
    }

    private fun toggleDebug() {
        // Toggles the shared /sbo -> Debug -> "Debug Messages" setting, not a
        // crown-only flag - this affects debug logging mod-wide, same as
        // flipping it from the settings GUI would.
        Debug.debugMessages = !Debug.debugMessages
        Chat.chat("§6[SBO] §eDebug messages: ${if (Debug.debugMessages) "§aON" else "§cOFF"}")
    }

    /**
     * Clears the worn crown's price (including its manual-override map entry, if
     * any, so a prior /sbocrown price doesn't just get silently reapplied) and
     * immediately re-runs both detection paths - the purse-drop check (only finds
     * something if you just spent coins) and the AH-history lookup (works
     * regardless of timing). Meant for verifying detection actually works, or
     * getting a manually-set price out of the way to see what auto-detection
     * would have found instead.
     */
    private fun resetAndRedetectPrice() {
        val uuid = wornCrownUuid()
        if (uuid == null) {
            Chat.chat("§6[SBO] §cWear a Crown of Avarice first, then run this command.")
            return
        }
        val data = SboDataObject.sboData
        data.crownCostBasisByUuid.remove(uuid)
        val entry = data.crownTrackedBaselines.find { it.uuid == uuid }
        if (entry == null) {
            Chat.chat("§6[SBO] §cThis crown isn't tracked yet - open /loadout once so it can be identified, then retry.")
            return
        }
        entry.costBasis = -1L
        entry.costBasisSource = SOURCE_UNSET
        SboDataObject.save("SboData")
        Chat.chat("§6[SBO] §eReset '${entry.loadoutName}''s crown price - re-running detection...")
        applyKnownCostBasis(entry, uuid)
        resolveCostBasisAsync(entry, uuid)
        DianaCrownTracker.updateLines()
        DianaLoot.updateLines()
    }

    private fun handlePriceSubcommand(args: Array<String>) {
        if (args.size < 2) {
            Chat.chat("§6[SBO] §cUsage: /sbocrown price <amount>")
            return
        }
        val amount = parseAmount(args[1])
        if (amount == null || amount < 0L) {
            Chat.chat("§6[SBO] §cInvalid amount: '${args[1]}'")
            return
        }
        val uuid = wornCrownUuid()
        if (uuid == null) {
            Chat.chat("§6[SBO] §cWear a Crown of Avarice first, then run this command.")
            return
        }
        val data = SboDataObject.sboData
        // Keyed by uuid, not loadout name, so this works immediately - it doesn't
        // need the crown to already be a tracked/confirmed loadout entry.
        data.crownCostBasisByUuid[uuid] = amount
        val matching = data.crownTrackedBaselines.filter { it.uuid == uuid }
        matching.forEach {
            it.costBasis = amount
            it.costBasisSource = SOURCE_MANUAL
            // A manually typed price has no evidence of what coins (if any) were
            // already on the crown when it was acquired - only an auto-detected
            // price (AH history or purse-drop, both tied to the actual moment of
            // acquisition) gets to assume a pre-fill amount. Manual assumes 0.
            it.startingCoins = 0L
        }
        SboDataObject.save("SboData")
        val label = matching.firstOrNull { it.confirmed }?.loadoutName ?: matching.firstOrNull()?.loadoutName
        val whom = if (label != null) "'$label'" else "the crown you're wearing"
        Chat.chat("§6[SBO] §aSet $whom cost to §6${Helper.formatNumber(amount, withCommas = true)}§a coins.")
        DianaCrownTracker.updateLines()
        DianaLoot.updateLines()
    }

    /**
     * Manually pins the worn crown's loadout as Damage/Magic Find (or clears that
     * pin with role=null) - see getConfirmedCrowns(). Unlike price, this needs an
     * already-tracked entry since the tag is loadout-identity-based, not uuid-based:
     * it should stick even if the physical crown under that loadout gets replaced.
     */
    private fun tagWornCrown(role: String?) {
        val uuid = wornCrownUuid()
        if (uuid == null) {
            Chat.chat("§6[SBO] §cWear a Crown of Avarice first, then run this command.")
            return
        }
        val entry = SboDataObject.sboData.crownTrackedBaselines.find { it.uuid == uuid }
        if (entry == null) {
            Chat.chat("§6[SBO] §cThis crown isn't tracked yet - open /loadout once so it can be identified, then retry.")
            return
        }
        if (role != null) {
            // Only one crown can hold a given role at a time.
            for (other in SboDataObject.sboData.crownTrackedBaselines) {
                if (other !== entry && other.role == role) other.role = null
            }
        }
        entry.role = role
        SboDataObject.save("SboData")
        val message = when (role) {
            ROLE_DAMAGE -> "Tagged '${entry.loadoutName}' as the Damage Crown."
            ROLE_MAGIC_FIND -> "Tagged '${entry.loadoutName}' as the Magic Find Crown."
            else -> "Cleared '${entry.loadoutName}''s crown tag - it'll go back to auto-ranking by gain."
        }
        Chat.chat("§6[SBO] §a$message")
        DianaCrownTracker.updateLines()
        DianaLoot.updateLines()
    }

    private fun parseAmount(raw: String): Long? {
        val cleaned = raw.trim().replace(",", "").lowercase()
        if (cleaned.isEmpty()) return null
        val multiplier = when (cleaned.last()) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            'b' -> 1_000_000_000.0
            else -> null
        }
        val numberPart = if (multiplier != null) cleaned.dropLast(1) else cleaned
        val base = numberPart.toDoubleOrNull() ?: return null
        return (base * (multiplier ?: 1.0)).toLong()
    }

    private fun wornCrownUuid(): String? {
        val player = SBOKotlin.mc.player ?: return null
        val stack = player.getItemBySlot(EquipmentSlot.HEAD) ?: return null
        val lookup = ItemLookup(stack)
        if (lookup.sbId != CROWN_ID) return null
        return lookup.uuid.takeIf { it.isNotEmpty() }
    }

    /**
     * Watches purse balance so a freshly-seen crown uuid (see handleReading) can be
     * matched to whatever coins were just spent - covers both crafting and buying
     * one filled/empty off the AH without needing to parse either's chat message.
     */
    private fun trackPurseForCostBasis() {
        if (!World.isInSkyblock()) return
        val purse = Helper.getPurse()
        if (lastKnownPurse < 0L) {
            lastKnownPurse = purse
            return
        }
        val delta = lastKnownPurse - purse
        if (delta > 0L) {
            lastPurseDropAmount = delta
            lastPurseDropAtMs = System.currentTimeMillis()
            if (verbose) {
                Chat.chat("§6[SBO] §7[crown debug] Purse dropped by ${Helper.formatNumber(delta, withCommas = true)} - candidate cost basis for the next ${PURSE_DROP_WINDOW_MS / 1000}s if a new crown uuid shows up.")
            }
        }
        lastKnownPurse = purse
    }

    private fun detectedCostBasis(): Long? {
        if (System.currentTimeMillis() - lastPurseDropAtMs > PURSE_DROP_WINDOW_MS) return null
        return lastPurseDropAmount.takeIf { it > 0L }
    }

    /**
     * Fast synchronous pass: a price set via /sbocrown price (keyed by uuid)
     * always wins, even if it was set before this uuid had a tracked baseline
     * entry at all. Otherwise falls back to the purse-drop heuristic. Either
     * way, resolveCostBasisAsync() below still runs afterwards and can upgrade
     * this to the more authoritative AH-history price once it comes back.
     */
    private fun applyKnownCostBasis(entry: CrownBaselineEntry, uuid: String) {
        val data = SboDataObject.sboData
        val manualPrice = data.crownCostBasisByUuid[uuid]
        if (manualPrice != null) {
            entry.costBasis = manualPrice
            entry.costBasisSource = SOURCE_MANUAL
            // See handlePriceSubcommand() - a manual price has no evidence of a
            // pre-fill amount, so it assumes 0 regardless of what this crown's
            // very first NBT reading happened to show.
            entry.startingCoins = 0L
            return
        }
        detectedCostBasis()?.let {
            entry.costBasis = it
            entry.costBasisSource = SOURCE_PURSE_DETECTED
            if (verbose) Chat.chat("§6[SBO] §7[crown debug] Applied purse-drop cost basis to '${entry.loadoutName}': ${Helper.formatNumber(it, withCommas = true)}.")
        }
    }

    /**
     * Looks up this crown's own AH sale history via Coflnet (see CrownMarket.
     * lookupSaleHistory) - retroactive, so it works for crowns acquired long
     * before this version existed, not just live going forward. Runs once per
     * newly-seen uuid, right after the synchronous applyKnownCostBasis() above.
     *
     * A found sale always overrides a purse-drop guess (an exact historical
     * record beats "something nearby happened to cost coins"), but never a
     * manually-set price - the user said so explicitly, that wins regardless.
     * When nothing at all is known once this resolves, emits ONE of two
     * distinct warnings so it's clear which lookup came up empty: an actual
     * request failure vs. a clean "no sale on record" result.
     */
    private fun resolveCostBasisAsync(entry: CrownBaselineEntry, uuid: String) {
        if (verbose) Chat.chat("§6[SBO] §7[crown debug] Looking up AH sale history for '${entry.loadoutName}' (uuid=$uuid)...")
        CrownMarket.lookupSaleHistory(uuid) { sales ->
            when {
                sales == null -> {
                    if (verbose) Chat.chat("§6[SBO] §7[crown debug] AH history lookup for '${entry.loadoutName}' failed (network/parse error).")
                    if (entry.costBasis < 0L) {
                        Chat.chat("§6[SBO] §cCouldn't check '${entry.loadoutName}''s AH sale history right now (Coflnet unreachable) - if it's still unpriced, set it with §e/sbocrown price <amount>§c.")
                    }
                }
                sales.isEmpty() -> {
                    if (verbose) Chat.chat("§6[SBO] §7[crown debug] AH history lookup for '${entry.loadoutName}' returned 0 sale records.")
                    if (entry.costBasis < 0L) {
                        Chat.chat("§6[SBO] §eNo AH sale on record for '${entry.loadoutName}''s crown (likely crafted, traded, or gifted) - set its cost with §a/sbocrown price <amount>§e for an accurate multiplier.")
                    }
                }
                else -> {
                    if (verbose) {
                        Chat.chat("§6[SBO] §7[crown debug] Found ${sales.size} sale record(s) for '${entry.loadoutName}':")
                        sales.sortedByDescending { it.timestampMs }.take(5).forEach { sale ->
                            val soldAt = if (sale.timestampMs > 0L) Instant.ofEpochMilli(sale.timestampMs).toString() else "unknown time"
                            Chat.chat("§6[SBO] §7[crown debug]   ${Helper.formatNumber(sale.price, withCommas = true)} coins - tag=${sale.itemTag ?: "?"} - $soldAt")
                        }
                    }
                    if (entry.costBasisSource != SOURCE_MANUAL) {
                        val mostRecent = sales.maxByOrNull { it.timestampMs } ?: return@lookupSaleHistory
                        entry.costBasis = mostRecent.price
                        entry.costBasisSource = SOURCE_AH_HISTORY
                        SboDataObject.save("SboData")
                        Chat.chat("§6[SBO] §aFound '${entry.loadoutName}''s crown in Coflnet's AH history - cost set to §6${Helper.formatNumber(mostRecent.price, withCommas = true)}§a coins.")
                        DianaCrownTracker.updateLines()
                        DianaLoot.updateLines()
                    } else if (verbose) {
                        Chat.chat("§6[SBO] §7[crown debug] Skipping AH-history price for '${entry.loadoutName}' - a manual price is already set.")
                    }
                }
            }
        }
    }

    /**
     * Older saves (from before loadoutName/confirmed existed on CrownBaselineEntry,
     * and before crownLoadoutSwapCounts existed on SboData) deserialize through
     * plain Gson, which uses reflection and does NOT respect Kotlin's non-null
     * guarantees or default values - any field missing from an old save comes back
     * as an actual runtime null, even though its declared Kotlin type says it can
     * never be null. Calling a non-null-receiver function (like .isBlank()) on such
     * a field throws immediately, which is exactly what crashed here. This repairs
     * both risk points before anything else touches them: entries with a genuinely
     * null/blank loadoutName get dropped (a fresh /loadout scan will recreate them
     * correctly), and a null crownLoadoutSwapCounts map gets replaced with an empty
     * one.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeLegacyBaselines() {
        val data = SboDataObject.sboData

        if (data.crownLoadoutSwapCounts == null) {
            data.crownLoadoutSwapCounts = mutableMapOf()
        }
        if (data.crownCostBasisByUuid == null) {
            data.crownCostBasisByUuid = mutableMapOf()
        }

        val baselines = data.crownTrackedBaselines
        if (baselines == null) {
            data.crownTrackedBaselines = mutableListOf()
            SboDataObject.save("SboData")
            return
        }

        var removedCount = 0
        var fixedCostBasisCount = 0
        val iterator = baselines.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // Order matters: if loadoutName is truly null, the == null check
            // short-circuits the || before isBlank() ever runs on it.
            if (entry.loadoutName == null || entry.loadoutName.isBlank()) {
                iterator.remove()
                removedCount++
                continue
            }
            // Same reflection gap as above, but for costBasis (added after this
            // field already existed on disk for some players): Gson leaves a
            // missing Long field at the JVM default of 0, not our -1L "unset"
            // sentinel. A real crown never costs exactly 0 coins, so treat a
            // loaded 0 as unset rather than as a free crown.
            if (entry.costBasis == 0L) {
                entry.costBasis = -1L
                fixedCostBasisCount++
            }
            // Missing String field on an old save deserializes to null despite the
            // declared non-null type - same reflection gap as above.
            if (entry.costBasisSource == null) {
                entry.costBasisSource = SOURCE_UNSET
            }
        }
        if (removedCount == 0 && fixedCostBasisCount == 0) return
        SboDataObject.save("SboData")
        if (removedCount > 0) {
            Chat.chat("§6[SBO] §eRemoved $removedCount corrupted crown-loadout entr${if (removedCount == 1) "y" else "ies"} left over from before loadout tracking was added. A fresh /loadout scan will re-establish them correctly.")
        }
    }

    /**
     * One-time cleanup for duplicate entries that already exist on disk from
     * before handleReading() detected renames (see the uuid check at the top of
     * its existing==null branch) - a loadout renamed in-game used to be tracked
     * as a brand new, separate crown under the new name, silently orphaning the
     * old entry's confirmation/cost/gain history behind a duplicate. Keeps the
     * most recently-seen entry's name, merges gain totals from its duplicates
     * into it, and keeps whichever had a real cost basis / confirmed status.
     */
    private fun mergeDuplicateUuidEntries() {
        val baselines = SboDataObject.sboData.crownTrackedBaselines
        val byUuid = baselines.filter { it.uuid.isNotEmpty() }.groupBy { it.uuid }
        var mergedCount = 0
        for (group in byUuid.values) {
            if (group.size < 2) continue
            // Prefer a specific (non-"Loadout N") name as the keeper's identity
            // over a merely more-recently-seen generic one - see the same guard
            // in handleReading() for why a generic label isn't a real name.
            val specificNamed = group.filterNot { GENERIC_LOADOUT_NAME_REGEX.matches(it.loadoutName) }
            val keeper = (specificNamed.ifEmpty { group }).maxByOrNull { it.lastSeenMs } ?: continue
            for (dup in group) {
                if (dup === keeper) continue
                keeper.confirmed = keeper.confirmed || dup.confirmed
                keeper.totalGained += dup.totalGained
                keeper.eventGained += dup.eventGained
                keeper.updateCount += dup.updateCount
                if (keeper.costBasis < 0L && dup.costBasis >= 0L) {
                    keeper.costBasis = dup.costBasis
                    keeper.costBasisSource = dup.costBasisSource
                }
                baselines.remove(dup)
                mergedCount++
            }
        }
        if (mergedCount == 0) return
        SboDataObject.save("SboData")
        Chat.chat("§6[SBO] §eMerged $mergedCount duplicate crown-loadout entr${if (mergedCount == 1) "y" else "ies"} that were tracking the same physical crown under an old name (likely from a loadout rename before this was fixed).")
    }

    // --- GUI scanning: primary data source -----------------------------

    @SboEvent
    fun onGuiOpen(event: GuiOpenEvent) {
        if (!Diana.enableLoadoutCrownTracker) {
            if (verbose) Chat.chat("§6[SBO] §7[crown debug] Loadout Crown Tracking is disabled in /sbo -> Diana - ignoring GUI open.")
            return
        }
        scanIfLoadoutScreen(event.screen)
    }

    @SboEvent
    fun onInventorySlotUpdate(@Suppress("UNUSED_PARAMETER") event: InventorySlotUpdateEvent) {
        if (!Diana.enableLoadoutCrownTracker) return
        val screen = SBOKotlin.mc.screen ?: return
        scanIfLoadoutScreen(screen)
    }

    private fun scanIfLoadoutScreen(screen: Screen) {
        if (!World.isInSkyblock()) return
        if (screen !is AbstractContainerScreen<*>) return
        val title = screen.title.string
        if (!title.contains("Loadout", ignoreCase = true)) {
            if (verbose && title.isNotBlank()) {
                Chat.chat("§6[SBO] §7[crown debug] Screen title '$title' doesn't contain 'Loadout' - not scanning.")
            }
            return
        }

        if (verbose) Chat.chat("§6[SBO] §7[crown debug] Scanning loadout screen '$title' (${screen.menu.slots.size} slots)...")

        // Scan every crown head visible on the currently-rendered page.
        var crownItemsSeen = 0
        for (slot in screen.menu.slots) {
            val stack = slot.item ?: continue
            if (stack.isEmpty) continue
            val lookup = ItemLookup(stack)
            if (lookup.sbId != CROWN_ID) continue
            crownItemsSeen++

            val loadoutName = lookup.displayName.removeFormatting().trim()
            if (loadoutName.isEmpty()) {
                if (verbose) Chat.chat("§6[SBO] §7[crown debug] Found a crown (uuid=${lookup.uuid}) but its display name was blank - skipped.")
                continue
            }
            val uuid = lookup.uuid
            if (uuid.isEmpty()) {
                if (verbose) Chat.chat("§6[SBO] §7[crown debug] Found crown '$loadoutName' but its uuid was blank - skipped.")
                continue
            }
            val coins = stack[net.minecraft.core.component.DataComponents.CUSTOM_DATA]
                ?.copyTag()?.getLong(COINS_KEY)?.orElse(-1L)?.takeIf { it >= 0 }
            if (coins == null) {
                if (verbose) Chat.chat("§6[SBO] §7[crown debug] Found crown '$loadoutName' (uuid=$uuid) but couldn't read collected_coins from its NBT - skipped.")
                continue
            }

            if (verbose) Chat.chat("§6[SBO] §7[crown debug] Scanned crown '$loadoutName' (uuid=$uuid, coins=$coins).")
            handleReading(loadoutName, uuid, coins, TrackSource.GUI)
        }
        if (verbose && crownItemsSeen == 0) {
            Chat.chat("§6[SBO] §7[crown debug] No Crown of Avarice items found among this screen's slots.")
        }
    }

    // --- Burrow-verified single-shot worn-item read ---------------------
    //
    // BurrowDugEvent alone is NOT a complete signal for "a burrow was just
    // dug" - per BurrowDetector.kt (which has to solve this exact problem),
    // it only fires for a "second dig, not the end of a chain" completion.
    // It does NOT fire for:
    //   - the first dig of a burrow, when you get a treasure/mob-spawn message
    //   - the message when a full Griffin burrow chain finishes
    // BurrowDetector.kt covers all three cases by combining BurrowDugEvent
    // with two chat-message regexes and a shared position source
    // (DianaEvents.lastWaypointClicked) - this mirrors that exact approach.

    private fun onRealBurrowDig() {
        if (!Diana.enableLoadoutCrownTracker) return
        lastBurrowDugPos = DianaEvents.lastWaypointClicked ?: SboVec(0.0, 0.0, 0.0)
        lastBurrowDugAtMs = System.currentTimeMillis()

        // Mark activity BEFORE rechecking, not after. Digging our own burrow is
        // unambiguous proof of real activity, but normally the only thing that
        // un-pauses the timer is trackCrownOfAvariceCoins() itself (called from
        // handleReading, further down this same call chain) - which won't run at
        // all if handleReading finds the timer still paused. Without this, a
        // burrow dug right as the timer resumes from AFK would see itself as
        // still-paused and defer its own gain indefinitely, since nothing else
        // would be left to flip the timer back to Running.
        SboTimerManager.updateAllActivity()
        recheckWornItem(TrackSource.BURROW)
    }

    @SboEvent
    fun onBurrowDug(@Suppress("UNUSED_PARAMETER") event: BurrowDugEvent) {
        // Covers: second dig, not the end of a chain.
        onRealBurrowDig()
    }

    @SboEvent
    fun onDianaMobDeath(event: DianaMobDeathEvent) {
        if (!Diana.enableLoadoutCrownTracker) return
        val burrowPos = lastBurrowDugPos
        if (burrowPos == null) {
            if (verbose) Chat.chat("§6[SBO] §7Diana mob '${event.name}' died but no recent burrow dig is tracked - skipping recheck.")
            return
        }
        val elapsed = System.currentTimeMillis() - lastBurrowDugAtMs
        if (elapsed > BURROW_MOB_MAX_MS) {
            if (verbose) Chat.chat("§6[SBO] §7Diana mob '${event.name}' died ${elapsed}ms after the last dig (too long) - not attributing to our burrow.")
            return
        }
        val entityPos = event.entity.position()
        val dist = burrowPos.distanceTo(SboVec(entityPos.x, entityPos.y, entityPos.z))
        if (dist > BURROW_MOB_MAX_DIST) {
            if (verbose) Chat.chat("§6[SBO] §7Diana mob '${event.name}' died ${"%.1f".format(dist)} blocks from our last burrow - likely not ours, skipping.")
            return
        }
        if (verbose) Chat.chat("§6[SBO] §7Diana mob '${event.name}' confirmed from our burrow (${"%.1f".format(dist)}b, ${elapsed}ms) - rechecking crown.")
        SboTimerManager.updateAllActivity()
        recheckWornItem(TrackSource.MOB)
    }

    private fun recheckWornItem(source: TrackSource) {
        if (!Diana.enableLoadoutCrownTracker) return
        if (!World.isInSkyblock()) return
        val player = SBOKotlin.mc.player ?: return
        val stack = player.getItemBySlot(EquipmentSlot.HEAD) ?: return
        val lookup = ItemLookup(stack)
        if (lookup.sbId != CROWN_ID) return
        val uuid = lookup.uuid
        if (uuid.isEmpty()) return
        val coins = stack[net.minecraft.core.component.DataComponents.CUSTOM_DATA]
            ?.copyTag()?.getLong(COINS_KEY)?.orElse(-1L)?.takeIf { it >= 0 } ?: return

        // We don't know which loadout name this worn crown belongs to from the
        // worn item alone - match it to whichever tracked entry currently holds
        // this uuid. If none does yet, there's nothing to credit until a GUI
        // scan or a chat equip event tells us the name.
        // Prefer a confirmed, properly-named entry if multiple happen to share this
        // uuid (shouldn't normally happen, but see sanitizeLegacyBaselines for one
        // way it used to) - falling back to any non-blank-named match, so we never
        // hand a "null"/blank loadout name into handleReading.
        val candidates = SboDataObject.sboData.crownTrackedBaselines.filter { it.uuid == uuid && !it.loadoutName.isNullOrBlank() }
        val entry = candidates.firstOrNull { it.confirmed } ?: candidates.firstOrNull() ?: return
        handleReading(entry.loadoutName, uuid, coins, source)
    }

    // --- Swap tracking / auto-detection ---------------------------------

    private fun onLoadoutSwapped(loadoutName: String) {
        if (!Diana.enableLoadoutCrownTracker) return
        val data = SboDataObject.sboData
        val entry = data.crownTrackedBaselines.find { it.loadoutName == loadoutName }
        if (entry == null) {
            if (verbose) Chat.chat("§6[SBO] §7Swapped to '$loadoutName' - no known crown in it yet, ignoring.")
            return
        }

        if (!entry.confirmed) {
            val counts = data.crownLoadoutSwapCounts
            val newCount = (counts[loadoutName] ?: 0) + 1
            counts[loadoutName] = newCount

            if (newCount >= SWAP_THRESHOLD) {
                val confirmedCount = data.crownTrackedBaselines.count { it.confirmed }
                if (confirmedCount < MAX_TRACKED_CROWNS) {
                    entry.confirmed = true
                    Chat.chat("§6[SBO] §a'$loadoutName' confirmed as a Diana crown loadout (swapped to it $newCount times).")
                } else if (verbose) {
                    Chat.chat("§6[SBO] §7'$loadoutName' qualifies as a crown loadout but the $MAX_TRACKED_CROWNS-crown limit is already reached.")
                }
            }
        }
        // No worn-item recheck on swap - swap events only drive confirmation
        // counting. Actual coin readings come from GUI scans, burrow digs,
        // mob kills, and the server's dug-out-coins chat message only.
        SboDataObject.save("SboData")
    }

    // --- Core diff/credit logic, keyed by loadout name -------------------

    private fun handleReading(loadoutName: String, uuid: String, coins: Long, source: TrackSource) {
        val data = SboDataObject.sboData
        val baselines = data.crownTrackedBaselines
        val existing = baselines.find { it.loadoutName == loadoutName }

        if (existing == null) {
            // No entry under this exact name, but the same physical crown (same
            // uuid) is already tracked under a DIFFERENT name - the loadout was
            // renamed in-game. Re-key that entry in place rather than treating
            // this as a brand new loadout, or its confirmation/cost/gain history
            // would be silently orphaned behind a duplicate.
            val renamedFrom = baselines.find { it.uuid == uuid }
            if (renamedFrom != null) {
                val oldName = renamedFrom.loadoutName
                // The /loadout GUI has more than one view of the same crown, and
                // at least one of them (the slot overview) falls back to a generic
                // "Loadout N" label instead of the crown's real custom name - not
                // an actual rename, just a less specific view flickering through.
                // Only treat it as a real rename when the new name isn't that
                // generic pattern, or when there was no specific name to lose
                // (the current tracked name is itself still generic).
                val newIsGeneric = GENERIC_LOADOUT_NAME_REGEX.matches(loadoutName)
                val oldIsGeneric = GENERIC_LOADOUT_NAME_REGEX.matches(oldName)
                if (newIsGeneric && !oldIsGeneric) {
                    renamedFrom.lastSeenMs = System.currentTimeMillis()
                    if (verbose) {
                        Chat.chat("§6[SBO] §7[crown debug] Saw generic label '$loadoutName' for uuid=$uuid but keeping tracked name '$oldName' - looks like the loadout overview page, not a real rename.")
                    }
                    return
                }
                renamedFrom.loadoutName = loadoutName
                renamedFrom.lastSeenMs = System.currentTimeMillis()
                SboDataObject.save("SboData")
                Chat.chat("§6[SBO] §7Loadout renamed: '$oldName' -> '$loadoutName' - carried its tracked history over.")
                return
            }

            // Brand new loadout name we've never seen with a crown before.
            // Untrusted until it clears the swap-count threshold via chat.
            if (baselines.size >= MAX_TRACKED_CROWNS && baselines.none { !it.confirmed }) {
                val target = pickEvictionCandidate(baselines) ?: return
                baselines.remove(target)
                if (verbose) Chat.chat("§6[SBO] §7Already tracking $MAX_TRACKED_CROWNS crowns, dropped '${target.loadoutName}' to track '$loadoutName' instead.")
            }
            val newEntry = CrownBaselineEntry(loadoutName, uuid, coins, System.currentTimeMillis(), System.currentTimeMillis(), confirmed = false, startingCoins = coins)
            applyKnownCostBasis(newEntry, uuid)
            baselines.add(newEntry)
            SboDataObject.save("SboData")
            resolveCostBasisAsync(newEntry, uuid)
            if (verbose) Chat.chat("§6[SBO] §7New loadout '$loadoutName' has a crown (uuid=$uuid), baseline established at $coins - not yet confirmed.")
            return
        }

        if (existing.uuid != uuid) {
            // The crown physically in this loadout changed.
            if (existing.confirmed) {
                // Trusted loadout name -> treat as a deliberate crown swap-in.
                // No credit for the jump (it's a different physical crown), but
                // no need to re-earn confirmation either.
                Chat.chat("§6[SBO] §7Loadout '$loadoutName' now has a different crown (was ${existing.uuid}, now $uuid) - re-baselined, still confirmed.")
                existing.uuid = uuid
                existing.coins = coins
                existing.startingCoins = coins
                existing.lastSeenMs = System.currentTimeMillis()
                existing.lastIncreaseMs = System.currentTimeMillis()
                applyKnownCostBasis(existing, uuid)
                SboDataObject.save("SboData")
                resolveCostBasisAsync(existing, uuid)
            } else {
                // Not yet confirmed AND the crown under this name is also
                // shifting - too unstable to trust yet, just track the latest.
                existing.uuid = uuid
                existing.coins = coins
                existing.startingCoins = coins
                existing.lastSeenMs = System.currentTimeMillis()
                SboDataObject.save("SboData")
                if (verbose) Chat.chat("§6[SBO] §7Unconfirmed loadout '$loadoutName' also changed crowns (now $uuid) - still waiting on repeated equips before trusting it.")
            }
            return
        }

        // Same name, same uuid -> normal diff/credit path.
        existing.lastSeenMs = System.currentTimeMillis()
        val diff = coins - existing.coins

        if (diff <= 0L) {
            if (diff < 0L) {
                existing.coins = coins
                SboDataObject.save("SboData")
                if (verbose) Chat.chat("§6[SBO] §ccrown '$loadoutName' collected_coins decreased to $coins (claimed/emptied?), baseline resynced.")
            } else if (verbose) {
                Chat.chat("§6[SBO] §7crown '$loadoutName' diff=0, nothing to credit.")
            }
            return
        }

        if (!existing.confirmed) {
            // Track the baseline but don't credit anything until confirmed.
            existing.coins = coins
            SboDataObject.save("SboData")
            if (verbose) Chat.chat("§6[SBO] §7crown '$loadoutName' gained $diff but is not yet a confirmed leveling loadout - not credited.")
            return
        }

        if (diff > SUSPICIOUS_JUMP_THRESHOLD) {
            // Bigger than any normal solo-play burst could plausibly deposit
            // between two readings - e.g. traded to someone else, they ground
            // it up, then traded it back to you. Re-baseline so future gains
            // still track correctly, but don't attribute someone else's grind
            // to your own stats. lastIncreaseMs is deliberately left untouched -
            // this isn't a real "increase" for staleness-eviction purposes.
            existing.coins = coins
            SboDataObject.save("SboData")
            Chat.chat("§6[SBO] §c'$loadoutName' jumped by ${Helper.formatNumber(diff, withCommas = true)} coins in a single reading - too large to be normal solo progress, so it wasn't credited (likely traded away and back). Re-synced to the new baseline.")
            return
        }

        existing.coins = coins
        existing.lastIncreaseMs = System.currentTimeMillis()
        existing.totalGained += diff
        existing.updateCount += 1
        existing.eventGained += diff
        data.crownGainedTotal += diff
        data.crownGainedEvent += diff
        SboDataObject.save("SboData")
        if (verbose) {
            Chat.chat("§6[SBO] §araw diff=$diff for crown '$loadoutName' (source=$source)")
        }

        sessionTotalGained += diff
        sessionLastAnyGain = diff
        lootTrackerTotalGained += diff
        crownSessionGains[loadoutName] = (crownSessionGains[loadoutName] ?: 0L) + diff
    }

    private fun pickEvictionCandidate(baselines: List<CrownBaselineEntry>): CrownBaselineEntry? {
        val now = System.currentTimeMillis()
        val stale = baselines.filter { now - it.lastIncreaseMs >= STALE_THRESHOLD_MS }
        if (stale.isNotEmpty()) return stale.minByOrNull { it.lastIncreaseMs }
        return baselines.minByOrNull { it.lastSeenMs }
    }
}
