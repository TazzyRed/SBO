package net.sbo.mod.diana

import net.minecraft.world.entity.EquipmentSlot
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Helper
import net.sbo.mod.utils.SboTimerManager
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.data.CrownBaselineEntry
import net.sbo.mod.utils.data.SboDataObject
import net.sbo.mod.utils.data.SboDataObject.sboData
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.entity.DianaMobDeathEvent
import net.sbo.mod.utils.game.ItemLookup
import net.sbo.mod.utils.game.World.isInSkyblock
import java.util.regex.Pattern

/**
 * Tracks coins gained through a worn Crown of Avarice by reading its `collected_coins` NBT
 * directly, instead of relying on chat message amounts (which SBO's existing coin-drop chat
 * parsing shows are inaccurate while the crown is intercepting Diana's coin drops).
 *
 * We don't poll every tick. Instead we re-check the currently worn helmet's NBT whenever
 * something plausibly changed it:
 *  - the player swaps loadouts ("You equipped Loadout N!" chat message, any N)
 *  - the "Wow! You dug out ... coins!" chat message fires (we ignore the printed amount,
 *    it's only used as a signal that a re-check is worthwhile)
 *  - a Diana mob dies (DianaMobDeathEvent, which SBO already fires for every Diana mob,
 *    not just the rare ones)
 *  - the tracker just started (an immediate check, plus a ~1s delayed retry in case the
 *    player/world weren't fully loaded yet at the exact moment init() ran)
 *
 * Per-physical-item bookkeeping: up to 2 physical crowns are tracked concurrently (by NBT
 * uuid), each with its OWN baseline in sboData.crownTrackedBaselines - persisted to disk. This
 * matters because it's common to own a second crown on an alternate/combat loadout that gets
 * swapped to often (e.g. for rare mob fights); if only one uuid were tracked at a time, every
 * single swap between the two would look like "a brand new crown appeared" and silently
 * discard tracking of whichever one wasn't currently being looked at, so the accumulated
 * baseline would never advance. Persisting both baselines also means a second crown you own but
 * haven't worn yet THIS session still has a known starting point (from last time it was seen)
 * instead of starting from scratch - and a full game restart doesn't lose either crown's
 * progress.
 *
 * Ownership-change protection: a positive diff found against an EXISTING baseline is only
 * credited if SboTimerManager's total playtime timer was not Paused (see isPaused()) at that
 * moment - Idle (never started yet this session) is allowed through, only an explicit
 * Paused->resumed transition is treated with suspicion. This is what stops "gave the crown to a
 * friend, they filled it further, I put it back on" from being silently counted as something I
 * earned: while the crown is out of your possession you're not generating any Diana
 * activity of your own, so the timer will have gone Paused (via the existing AFK-timeout
 * mechanism) for the whole time it was gone, and the jump found on the first recheck after
 * getting it back is rejected (baseline resynced, not credited) rather than trusted.
 */
object CrownOfAvariceTracker {
    private const val CROWN_ID = "CROWN_OF_AVARICE"
    private const val COINS_KEY = "collected_coins"
    private const val MAX_TRACKED_CROWNS = 2

    // A tracked crown that hasn't actually gained any coins in this long is treated as
    // "abandoned/done" and preferred for eviction over one that's still slowly climbing - see
    // pickEvictionCandidate. Chosen to comfortably outlast normal gaps between rare mob
    // encounters (which are irregular, not fixed-interval) without also outlasting genuine
    // abandonment.
    private const val STALE_THRESHOLD_MS = 45L * 60 * 1000 // 45 minutes

    // Only used as a fallback when nothing is stale (see pickEvictionCandidate): a tracked crown
    // at or above this collected_coins value is treated as "likely done" over a partially-filled
    // one. Deliberately NOT the primary signal - a crown sitting around 100m could either be
    // freshly abandoned OR a slow-growing second crown that just happens to be passing through
    // that range, and coin value alone can't tell those apart. Staleness can.
    private const val NEAR_FULL_EVICT_THRESHOLD = 900_000_000L

    /** Toggled via /sboverbosecrown - prints each recheck's findings to chat for debugging. */
    private var verbose: Boolean = false

    fun init() {
        // Keeps the Coflnet price estimate in sync with the same cadence Helper.updateItemPriceInfo()
        // uses for every other loot item's bazaar/AH price (immediate fetch on startup, then every
        // 20*60*10 ticks = 10 minutes) - see Helper.kt lines 85-91/115 for the pattern this mirrors.
        // Only gated on the loot tracker being enabled (not on an active Diana session being
        // "running"), since otherwise CoflPricing never got a chance to fetch until the overlay
        // itself rendered.
        if (isInSkyblock() && Diana.lootTracker != Diana.Tracker.OFF) {
            CoflPricing.getCoinMultiplier()
        }

        Register.onTick(20 * 60 * 10) {
            if (isInSkyblock() && Diana.lootTracker != Diana.Tracker.OFF) {
                CoflPricing.getCoinMultiplier()
            }
        }

        // Immediately check whatever's currently worn, plus a ~1s delayed retry in case
        // mc.player/the world weren't ready at the exact moment init() ran - so the currently
        // worn crown's baseline (and re-syncing the other tracked crown's persisted baseline
        // against it) happen close to startup instead of waiting for the next loadout
        // swap/dig/mob-death trigger, which could be a while.
        recheck()
        Register.onTick(20) { unregister -> // ~1s at 20 tps, one-shot
            recheck()
            unregister()
        }

        // "You equipped Loadout 3!" - number is irrelevant, any loadout swap can change helmet.
        Register.onChatMessage(
            Regex("^You equipped Loadout \\d+!$"),
            noFormatting = true
        ) { _, _ ->
            recheck()
        }

        // Reuse the same message SBO's chat-based coin tracker already listens to, purely as a
        // "something coin-related just happened, go re-read the helmet" signal. The amount in
        // this message is intentionally ignored.
        Register.onChatMessageCancelable(
            Pattern.compile("^§6§lWow! §eYou dug out §6(.*?) coins§e!$", Pattern.DOTALL)
        ) { _, _ ->
            recheck()
            true
        }

        // /sbocrownprice <amount> - sets a single assumed crown acquisition cost (e.g. current
        // LBIN) applied to ALL tracked crowns' profit estimates, not just whichever one you're
        // currently wearing. Used together with the Coflnet average full-crown sale price to
        // convert raw collected_coins gains into an estimated real profit instead of counting
        // them 1:1.
        Register.command("sbocrownprice") { args ->
            val amount = args.getOrNull(0)?.replace(",", "")?.toLongOrNull()
            if (amount == null || amount < 0) {
                Chat.chat("§6[SBO] §cUsage: /sbocrownprice <coins per crown, e.g. current LBIN>")
                return@command
            }
            CoflPricing.setCostBasis(amount)
            Chat.chat("§6[SBO] §aCrown acquisition cost set to §e${amount}§a coins - applies to all tracked crowns.")
        }

        // Explicitly replaces one of the (up to 2) tracked crown slots with whatever crown is
        // currently worn. For when you've finished/moved on from one of your two event crowns
        // and swapped in a new physical one to take its place - rather than relying on the
        // automatic "3rd uuid shows up" eviction, which only fires once you're already at the
        // tracking limit and might not pick the crown you actually meant to retire. Deliberately
        // manual: there's no reliable way to tell "this new crown should replace a tracked one"
        // apart from "this is some other crown I use for something unrelated" from game state
        // alone, so this never fires on its own.
        Register.command("sbocrownreplace") {
            replaceTrackedCrownWithCurrentlyWorn()
        }

        // Debugging aids for the Coflnet price estimate, since the exact API response shape
        // couldn't be verified ahead of time.
        Register.command("sboverbosecoflnet") {
            CoflPricing.verbose = !CoflPricing.verbose
            Chat.chat("§6[SBO] §aCoflnet crown price debug logging: ${if (CoflPricing.verbose) "§aON" else "§cOFF"}")
        }

        Register.command("sborefreshcoflnet") {
            Chat.chat("§6[SBO] §7Refreshing Crown of Avarice price estimate from Coflnet...")
            CoflPricing.forceRefresh()
        }

        // Prints what recheck() sees on every trigger: whether a crown is detected, its uuid,
        // its collected_coins, and the diff computed against the stored baseline. Use this to
        // tell "the price estimate is wrong" apart from "the raw NBT reading isn't firing at all".
        Register.command("sboverbosecrown") {
            verbose = !verbose
            Chat.chat("§6[SBO] §aCrown of Avarice tracker debug logging: ${if (verbose) "§aON" else "§cOFF"}")
        }

        // Manually fires a recheck right now, without waiting for a loadout swap/dig
        // message/mob death - useful for testing while standing still with the crown on.
        Register.command("sbocheckcrown") {
            Chat.chat("§6[SBO] §7Manually triggering a Crown of Avarice recheck...")
            recheck()
        }
    }

    @SboEvent
    fun onDianaMobDeath(event: DianaMobDeathEvent) {
        recheck()
    }

    private fun recheck() {
        if (!isInSkyblock()) {
            if (verbose) Chat.chat("§6[SBO] §7recheck: not in Skyblock, skipping.")
            return
        }

        val stack = mc.player?.getItemBySlot(EquipmentSlot.HEAD)
        if (stack == null) {
            if (verbose) Chat.chat("§6[SBO] §7recheck: no player/helmet slot available.")
            return
        }

        val lookup = ItemLookup(stack)

        if (lookup.sbId != CROWN_ID) {
            if (verbose) Chat.chat("§6[SBO] §7recheck: helmet is not a crown (sbId='${lookup.sbId}').")
            return
        }

        val uuid = lookup.uuid
        if (uuid.isEmpty()) {
            if (verbose) Chat.chat("§6[SBO] §crecheck: crown detected but NBT uuid was empty (not populated yet?).")
            return
        }

        val coins = lookup.getLong(COINS_KEY)
        if (coins == null) {
            if (verbose) Chat.chat("§6[SBO] §crecheck: crown detected (uuid=$uuid) but '$COINS_KEY' NBT key was missing/unreadable.")
            return
        }

        processCrownReading(uuid, coins)
    }

    /**
     * Looks up (or creates) this uuid's persisted baseline entry and credits the difference, if
     * any. Each tracked uuid's baseline only ever moves FORWARD to the latest successfully-read
     * value - never resets just because a different uuid was seen in between - so alternating
     * between two known crowns never loses progress on either one.
     */
    private fun processCrownReading(uuid: String, coins: Long) {
        val baselines = sboData.crownTrackedBaselines
        val existing = baselines.find { it.uuid == uuid }

        if (existing == null) {
            if (baselines.size >= MAX_TRACKED_CROWNS) {
                val target = pickEvictionCandidate(baselines)
                if (target != null) {
                    baselines.remove(target)
                    if (verbose) Chat.chat("§6[SBO] §7recheck: already tracking $MAX_TRACKED_CROWNS crowns, dropped ${target.uuid} (${Helper.formatNumber(target.coins)} collected_coins) to track $uuid instead.")
                }
            }
            baselines.add(CrownBaselineEntry(uuid, coins, System.currentTimeMillis(), System.currentTimeMillis()))
            SboDataObject.save("SboData")
            if (verbose) Chat.chat("§6[SBO] §7recheck: new crown uuid=$uuid, baseline established at $coins (no credit given).")
            return
        }

        existing.lastSeenMs = System.currentTimeMillis()

        val diff = coins - existing.coins
        if (diff <= 0) {
            if (diff < 0) {
                // Coins can only go down if the crown was claimed/emptied, or a desync happened -
                // resync down, don't credit a negative.
                existing.coins = coins
                SboDataObject.save("SboData")
                if (verbose) Chat.chat("§6[SBO] §crecheck: crown $uuid collected_coins decreased to $coins (claimed/emptied?), baseline resynced.")
            } else if (verbose) {
                Chat.chat("§6[SBO] §7recheck: crown $uuid diff=0, nothing to credit.")
            }
            return
        }

        // Ownership-change protection: if the total playtime timer was Paused (as opposed to
        // Idle, which is allowed - see isPaused()'s docs) at the moment this jump was found, we
        // can't attribute it to activity we were actually tracking - resync the baseline without
        // crediting, same as a brand-new uuid. lastIncreaseMs still advances - the crown itself
        // genuinely grew, which is what that field means, regardless of whether we credit it.
        if (SboTimerManager.timerTotal.isPaused()) {
            existing.coins = coins
            existing.lastIncreaseMs = System.currentTimeMillis()
            SboDataObject.save("SboData")
            if (verbose) Chat.chat("§6[SBO] §crecheck: crown $uuid gained $diff while the tracker was paused (away/AFK, or possibly out of your possession) - baseline resynced, NOT credited.")
            return
        }

        existing.coins = coins
        existing.lastIncreaseMs = System.currentTimeMillis()
        SboDataObject.save("SboData")

        if (verbose) {
            val multiplier = CoflPricing.getCoinMultiplier()
            Chat.chat("§6[SBO] §araw diff=$diff for crown $uuid (current multiplier ${CoflPricing.truncate2(multiplier)}x -> est. profit ${(diff * multiplier).toLong()})")
        }

        DianaTracker.trackCrownOfAvariceCoins(diff)
    }

    /**
     * Eviction priority, in order:
     *  1. Stale (no real coin increase in STALE_THRESHOLD_MS) - the strongest "abandoned" signal,
     *     regardless of what value it stalled at. This is what correctly tells apart "leveled to
     *     ~100m then moved to a new crown" from "still slowly climbing toward 200m+" - both can
     *     sit at a similar coin value at any given moment, but only the abandoned one stops
     *     getting real increases.
     *  2. Near the 1B cap - only used as a fallback when nothing is stale, since a crown can
     *     legitimately still be actively growing even near the cap.
     *  3. Least-recently-seen - final fallback if neither signal applies to anything tracked.
     */
    private fun pickEvictionCandidate(baselines: List<CrownBaselineEntry>): CrownBaselineEntry? {
        val now = System.currentTimeMillis()
        val stale = baselines.filter { now - it.lastIncreaseMs >= STALE_THRESHOLD_MS }
        if (stale.isNotEmpty()) {
            // Among stale candidates, evict whichever has been stale the longest.
            return stale.minByOrNull { it.lastIncreaseMs }
        }

        val nearFull = baselines.filter { it.coins >= NEAR_FULL_EVICT_THRESHOLD }
        if (nearFull.isNotEmpty()) {
            return nearFull.maxByOrNull { it.coins }
        }

        return baselines.minByOrNull { it.lastSeenMs }
    }

    private fun replaceTrackedCrownWithCurrentlyWorn() {
        if (!isInSkyblock()) {
            Chat.chat("§6[SBO] §cYou need to be in Skyblock to do this.")
            return
        }

        val stack = mc.player?.getItemBySlot(EquipmentSlot.HEAD)
        val lookup = stack?.let { ItemLookup(it) }
        if (lookup == null || lookup.sbId != CROWN_ID) {
            Chat.chat("§6[SBO] §cYou need to be wearing a Crown of Avarice to do this.")
            return
        }

        val uuid = lookup.uuid
        if (uuid.isEmpty()) {
            Chat.chat("§6[SBO] §cCouldn't read this crown's NBT yet - wait a moment and try again.")
            return
        }

        val baselines = sboData.crownTrackedBaselines
        if (baselines.any { it.uuid == uuid }) {
            Chat.chat("§6[SBO] §7This crown is already being tracked.")
            return
        }

        val coins = lookup.getLong(COINS_KEY)
        if (coins == null) {
            Chat.chat("§6[SBO] §cCouldn't read this crown's collected_coins yet - wait a moment and try again.")
            return
        }

        if (baselines.size >= MAX_TRACKED_CROWNS) {
            val target = pickEvictionCandidate(baselines)
            if (target != null) {
                baselines.remove(target)
                Chat.chat("§6[SBO] §7Stopped tracking crown ${target.uuid} (last at ${Helper.formatNumber(target.coins)} collected_coins).")
            }
        }

        baselines.add(CrownBaselineEntry(uuid, coins, System.currentTimeMillis(), System.currentTimeMillis()))
        SboDataObject.save("SboData")
        Chat.chat("§6[SBO] §aNow tracking the crown you're wearing as a fresh baseline (${Helper.formatNumber(coins)} collected_coins so far - no credit for that starting amount).")
    }
}
