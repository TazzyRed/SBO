package net.sbo.mod.overlays

import net.minecraft.ChatFormatting.*
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.diana.LoadoutCrownTracker
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Helper
import net.sbo.mod.utils.Helper.removeFormatting
import net.sbo.mod.utils.data.CrownBaselineEntry
import net.sbo.mod.utils.data.SboDataObject.SBOConfigBundle
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.overlay.*
import net.sbo.mod.utils.render.RenderUtils2D
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Standalone detached crown tracker overlay. Toggle via Diana.detachedCrownTracker
 * setting in /sbo -> Diana. All timing uses the loot tracker's active playtime
 * so it pauses/resumes in sync with the rest of the tracker.
 */
object DianaCrownTracker : DirtyFlushableOverlay() {

    override val overlay = Overlay("Diana Crown Tracker", 10f, 120f,
        allowedScreens = listOf(CHAT_SCREEN_FILTER, CRAFTING_PLAYER_INVENTORY_FILTER)
    ).setCondition {
        // Stays fully hidden - not just empty placeholder lines - until at least
        // one crown loadout has actually been confirmed. A fresh install has
        // nothing to show yet, so there's nothing worth taking up screen space for.
        Diana.detachedCrownTracker && Diana.enableLoadoutCrownTracker &&
            Helper.hasSpade && World.getWorld() == "Hub" &&
            LoadoutCrownTracker.getConfirmedCrowns().isNotEmpty()
    }

    fun init() {
        overlay.init()
        updateLines()
        Register.onTick(20) { updateLines() }
    }

    /**
     * Returns the loot tracker's accumulated active playtime in hours.
     * Using this instead of wall-clock means the crown tracker's coins/hr
     * pauses and resumes in sync with the loot tracker's timer.
     */
    private fun playTimeHrs(): Double {
        val type = Diana.lootTracker
        val timeMs = when (type) {
            Diana.Tracker.TOTAL -> SBOConfigBundle.dianaTrackerTotalData.items.TIME
            Diana.Tracker.EVENT -> SBOConfigBundle.dianaTrackerMayorData.items.TIME
            Diana.Tracker.SESSION -> SBOConfigBundle.dianaTrackerSessionData.items.TIME
            Diana.Tracker.OFF -> 0L
        }
        return timeMs.toDouble() / TimeUnit.HOURS.toMillis(1).toDouble()
    }

    /** Which period label to show on the coin-gain line - synced with the loot tracker. */
    private fun periodLineLabel(): String = when (Diana.lootTracker) {
        Diana.Tracker.TOTAL -> "Total Coins"
        Diana.Tracker.EVENT -> "Coins This Event"
        else -> "Coins This Session"
    }

    private fun periodGain(): Long = LoadoutCrownTracker.getPeriodGain(Diana.lootTracker)

    /** Same hide/strikethrough toggle DianaLoot uses for individual loot lines. */
    private fun hideableLine(key: String, formattedText: String): OverlayTextLine {
        val hideList = SBOConfigBundle.sboData.hideTrackerLines
        val isHidden = hideList.contains(key)
        val displayText = if (isHidden) "$GRAY$STRIKETHROUGH${formattedText.removeFormatting()}" else formattedText
        return OverlayTextLine(displayText)
            .onClick {
                if (!isCraftingScreenOpen()) return@onClick
                if (hideList.contains(key)) hideList.remove(key) else hideList.add(key)
                updateLines()
            }
            .setCondition { isCraftingScreenOpen() || !isHidden }
    }

    private fun drawCrownIdentityTooltip(entry: CrownBaselineEntry, drawContext: GuiGraphicsExtractor, textRenderer: Font) {
        val scaleFactor = mc.window.guiScale
        val mouseX = mc.mouseHandler.xpos() / scaleFactor
        val mouseY = mc.mouseHandler.ypos() / scaleFactor
        val costText = if (entry.costBasis >= 0L) {
            val sourceText = when (entry.costBasisSource) {
                "MANUAL" -> "manual"
                "AH_HISTORY" -> "found in AH history"
                "PURSE_DETECTED" -> "auto-detected"
                else -> "unknown source - set before source tracking existed"
            }
            "${Helper.formatNumber(entry.costBasis, withCommas = true)} ($sourceText)"
        } else {
            "not set"
        }
        RenderUtils2D.drawHoveringString(
            drawContext,
            "${YELLOW}${BOLD}${entry.loadoutName}\n" +
            "${GOLD}Confirmed: $AQUA${entry.confirmed}\n" +
            "${GOLD}Cost: $AQUA$costText\n" +
            "${GOLD}Lifetime gain: $AQUA${Helper.formatNumber(entry.totalGained, withCommas = true)} $GRAY(${entry.updateCount} updates)",
            mouseX, mouseY, textRenderer
        )
    }

    override fun generateLines(): List<OverlayTextLine> {
        val hrs = playTimeHrs()
        val gain = periodGain()
        val coinsPerHour = if (hrs > 0) gain / hrs else 0.0

        val lines = mutableListOf(
            OverlayTextLine("${YELLOW}${BOLD}Crown Tracker"),
            hideableLine("CROWN_LAST_ANY",
                "${GOLD}Last Gained: $AQUA${Helper.formatNumber(LoadoutCrownTracker.sessionLastAnyGain)}"),
            hideableLine("CROWN_COINS_PER_HOUR",
                "${GOLD}Coins Per Hour: $AQUA${Helper.formatNumber(coinsPerHour)}"),
            hideableLine("CROWN_PERIOD_GAINED",
                "${GOLD}${periodLineLabel()}: $AQUA${Helper.formatNumber(gain, withCommas = true)}")
        )

        // Damage Crown and Magic Find Crown - the two highest-gain confirmed crown loadouts,
        // each followed by its own coins/hr line since the two crowns are worn (and so gain
        // coins) at very different rates. Someone tracking only one crown sees a generic
        // "Crown" label instead of an assumed (and possibly wrong) role, unless they've
        // pinned one with /sbocrown damage or /sbocrown magicfind.
        val crowns = LoadoutCrownTracker.getConfirmedCrowns()
        crowns.forEachIndexed { i, entry ->
            val label = LoadoutCrownTracker.roleLabel(entry)
                ?: if (crowns.size == 1) "Crown" else if (i == 0) "Damage Crown" else "Magic Find Crown"
            val key = label.replace(" ", "_").uppercase()
            // Hover to see which actual loadout/crown this label currently maps to -
            // ranking (and so which crown gets which label) can shift as gain data
            // comes in, so this is the only reliable way to identify it at a glance.
            lines.add(hideableLine("CROWN_$key",
                "${GOLD}$label: $AQUA${Helper.formatNumber(entry.coins)}")
                .onHover { drawContext, textRenderer -> drawCrownIdentityTooltip(entry, drawContext, textRenderer) })

            val crownGain = LoadoutCrownTracker.getPeriodGainForCrown(entry, Diana.lootTracker)
            val crownCph = if (hrs > 0) crownGain / hrs else 0.0
            // Multiplier is per-crown (its own cost basis vs the current filled-crown
            // market value) - shown here, next to the rate it actually applies to,
            // rather than bolted onto a combined line where it's ambiguous which
            // crown it was even talking about. An unset cost basis would otherwise
            // silently render as a fake-looking "1.00x" (multiplierFor()'s neutral
            // fallback) - warn instead, since that number isn't a real calibration.
            val rateSuffix = if (entry.costBasis < 0L) {
                " $RED(no price set - $YELLOW/sbocrown price$RED)"
            } else {
                val multiplier = LoadoutCrownTracker.multiplierFor(entry)
                val estimatedCph = crownCph * multiplier
                val multiText = " $GRAY(${AQUA}${"%.2f".format(Locale.US, multiplier)}x$GRAY)"
                val estText = if (estimatedCph > 0) " $GRAY[$AQUA${Helper.formatNumber(estimatedCph)}$GRAY/${AQUA}hr$GRAY]" else ""
                "$multiText$estText"
            }
            lines.add(hideableLine("CROWN_${key}_RATE",
                "${GOLD}$label Coins/Hr: $AQUA${Helper.formatNumber(crownCph)}$rateSuffix"))
        }

        return lines
    }
}
