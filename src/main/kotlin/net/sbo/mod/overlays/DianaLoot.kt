package net.sbo.mod.overlays

import net.minecraft.ChatFormatting.*
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.diana.CrownMarket
import net.sbo.mod.diana.LoadoutCrownTracker
import net.sbo.mod.overlays.OverlayUtils.LootItemData
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Helper
import net.sbo.mod.utils.Helper.calcPercentOne
import net.sbo.mod.utils.Helper.removeFormatting
import net.sbo.mod.utils.SboTimerManager
import net.sbo.mod.utils.data.DianaTracker
import net.sbo.mod.utils.data.SboDataObject.SBOConfigBundle
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.guis.GuiCloseEvent
import net.sbo.mod.utils.events.impl.guis.GuiOpenEvent
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.overlay.*
import net.sbo.mod.utils.render.RenderUtils2D
import java.util.concurrent.TimeUnit

object DianaLoot : DirtyFlushableOverlay() {
    private var isSellTypeHovered = false
    private val timerLine: OverlayTextLine = OverlayTextLine("")

    override val overlay = Overlay("Diana Loot", 10f, 10f,
        allowedScreens = listOf(CHAT_SCREEN_FILTER, CRAFTING_PLAYER_INVENTORY_FILTER)
    )
        .setCondition { Diana.lootTracker != Diana.Tracker.OFF && Helper.hasSpade && World.getWorld() == "Hub" }

    private val changeView: OverlayTextLine = OverlayUtils.createClickableTextLine(
        text = "${YELLOW}Change View",
        hoverText = "$YELLOW${UNDERLINE}Change View",
        defaultText = "${YELLOW}Change View",
        onClick = {
            Diana.lootTracker = Diana.lootTracker.next()
            updateLines()
        },
        lineBreak = false
    )

    private val delimiter = OverlayTextLine(" | ", linebreak = false)

    private val changeSellType: OverlayTextLine = OverlayUtils.createClickableTextLine(
        text = "",
        onClick = {
            Diana.bazaarSettingDiana = Diana.bazaarSettingDiana.next()
            updateLines()
        },
        onMouseEnter = {
            isSellTypeHovered = true
            updateLines()
        },
        onMouseLeave = {
            isSellTypeHovered = false
            updateLines()
        }
    )

    private val resetSession = OverlayUtils.createClickableTextLine(
        text = "${RED}Reset Session",
        hoverText = "$RED${UNDERLINE}Reset Session",
        defaultText = "${RED}Reset Session",
        onClick = {
            resetSession()
        }
    )

    fun resetSession() {
        SboTimerManager.timerSession.reset()
        SBOConfigBundle.dianaTrackerSessionData.reset().save()
        LoadoutCrownTracker.resetLootTrackerBaselines()
        updateLines()
        DianaMobs.updateLines()
        DianaCrownTracker.updateLines()
    }

    private val LOOT_ITEMS = listOf(
        LootItemData("MYTHOLOGICAL_DYE", "Mythological Dye", RED),
        LootItemData("MYTH_THE_FISH", "Myth the Fish", RED),
        LootItemData("SHIMMERING_WOOL", "Shimmering Wool", RED, combined = true, dropMobId = "KING_MINOS", dropMobLsId = "KING_MINOS_LS"),
        LootItemData("MANTI_CORE", "Manti-core", RED, combined = true, dropMobId = "MANTICORE", dropMobLsId = "MANTICORE_LS"),
        LootItemData("KING_MINOS_SHARD", "King Minos Shard", RED, isRarerDrop = true, dropMobId = "KING_MINOS"),
        LootItemData("FATEFUL_STINGER", "Fateful Stinger", LIGHT_PURPLE, combined = true, dropMobId = "MANTICORE", dropMobLsId = "MANTICORE_LS"),
        LootItemData("CHIMERA", "Chimera", LIGHT_PURPLE, combined = true, dropMobId = "MINOS_INQUISITOR", dropMobLsId = "MINOS_INQUISITOR_LS"),
        LootItemData("BRAIN_FOOD", "Brain Food", DARK_PURPLE, combined = true, dropMobId = "SPHINX", dropMobLsId = "SPHINX_LS"),
        LootItemData("MINOS_RELIC", "Minos Relic", DARK_PURPLE, isRarerDrop = true, dropMobId = "MINOS_CHAMPION"),
        LootItemData("SPHINX_SHARD", "Sphinx Shard", DARK_PURPLE, isRarerDrop = true, dropMobId = "SPHINX"),
        LootItemData("BRAIDED_GRIFFIN_FEATHER", "Braided Griffin Feather", DARK_PURPLE, isRarerDrop = true),
        LootItemData("DAEDALUS_STICK", "Daedalus Stick", GOLD, isRarerDrop = true, dropMobId = "MINOTAUR"),
        LootItemData("MINOTAUR_SHARD", "Minotaur Shard", GOLD, isRarerDrop = true),
        LootItemData("CROWN_OF_GREED", "Crown of Greed", GOLD, isRarerDrop = true),
        LootItemData("WASHED_UP_SOUVENIR", "Washed-up Souvenir", GOLD, isRarerDrop = true),
        LootItemData("GRIFFIN_FEATHER", "Griffin Feather", GOLD),
        LootItemData("MYTHOS_FRAGMENT", "Mytho Fragment", GOLD),
        LootItemData("CRETAN_URN", "Cretan Urn", DARK_GREEN),
        LootItemData("DWARF_TURTLE_SHELMET", "Dwarf Turtle Shelmet", DARK_GREEN),
        LootItemData("CROCHET_TIGER_PLUSHIE", "Crochet Tiger Plushie", DARK_GREEN),
        LootItemData("ANTIQUE_REMEDIES", "Antique Remedies", DARK_GREEN),
        LootItemData("CRETAN_BULL_SHARD", "Cretan Bull Shard", DARK_GREEN),
        LootItemData("HARPY_SHARD", "Harpy Shard", DARK_GREEN),
        LootItemData("HILT_OF_REVELATIONS", "Hilt of Revelations", BLUE),
        LootItemData("ANCIENT_CLAW", "Ancient Claw", BLUE),
        LootItemData("ENCHANTED_ANCIENT_CLAW", "Enchanted Ancient Claw", BLUE),
        LootItemData("ENCHANTED_GOLD", "Enchanted Gold", BLUE)
    )

    fun init() {
        overlay.init()
        updateTimerText()
        updateLines()

        Register.onTick(1) { updateTimerText() }
        Register.onTick(20) {
            updateLines()
        }
    }

    @SboEvent
    fun onGuiClose(event: GuiCloseEvent) {
        if (CRAFTING_PLAYER_INVENTORY_FILTER(event.screen)) {
            overlay.removeLines(listOf(changeView, delimiter, changeSellType, resetSession))
        }
    }

    @SboEvent
    fun onGuiOpen(event: GuiOpenEvent) {
        if (CRAFTING_PLAYER_INVENTORY_FILTER(event.screen)) {
            updateLines()
        }
    }

    private fun hideLine(name: String) {
        if (!isCraftingScreenOpen()) return
        val hideList = SBOConfigBundle.sboData.hideTrackerLines
        if (name in hideList) hideList.remove(name) else hideList.add(name)
        updateLines()
    }

    private fun createLootLine(data: LootItemData, tracker: DianaTracker): OverlayTextLine {
        val itemName = data.id
        val amount = tracker.getAmountOf(itemName)
        val formattedName = "${data.color}${data.name}: ${AQUA}${Helper.formatNumber(amount, withCommas = true)}"
        val price = Helper.getItemPriceFormatted(itemName.replace("_LS", ""), amount)
        val percent = data.dropMobId?.let { dropId ->
            calcPercentOne(tracker.items, tracker.mobs, itemName, dropId)
        }
        val percentText = percent?.let { " $GRAY($AQUA${it}%$GRAY)" } ?: ""
        val formattedText = "$GOLD$price $GRAY| $formattedName$percentText"
        val line = OverlayTextLine(formattedText).onClick { hideLine(itemName) }
            .setCondition {
                val meetsZeroValueCondition = amount > 0 || !Diana.hideUnobtainedItems
                val meetsManualHideCondition = !(!isCraftingScreenOpen() && itemName in SBOConfigBundle.sboData.hideTrackerLines)
                meetsZeroValueCondition && meetsManualHideCondition
            }
        if (itemName in SBOConfigBundle.sboData.hideTrackerLines) {
            line.text = "$GRAY$STRIKETHROUGH${formattedText.removeFormatting()}"
        }
        return line
    }

    private fun createCombinedLootLine(data: LootItemData, tracker: DianaTracker): OverlayTextLine {
        val itemNameBase = data.id
        val itemNameLs = "${data.id}_LS"
        val amountBase = tracker.getAmountOf(itemNameBase)
        val amountLs = tracker.getAmountOf(itemNameLs)
        val totalAmount = amountBase + amountLs
        val priceLs = Helper.getItemPriceFormatted(itemNameLs.replace("_LS", ""), amountLs)
        val priceCombined = Helper.getItemPriceFormatted(itemNameBase, totalAmount)
        val percent = data.dropMobId?.let { dropId ->
            calcPercentOne(tracker.items, tracker.mobs, itemNameBase, dropId)
        }
        val percentText = percent?.let { " $GRAY($AQUA${it}%$GRAY)" } ?: ""
        val percentLs = data.dropMobLsId?.let { dropLsId ->
            calcPercentOne(tracker.items, tracker.mobs, itemNameLs, dropLsId)
        }
        val percentLsText = percentLs?.let { " $GRAY($AQUA${it}%$GRAY)" } ?: ""
        val baseText = "$GOLD$priceCombined $GRAY| ${data.color}${data.name}: $AQUA${Helper.formatNumber(amountBase,
            withCommas = true
        )}$percentText"
        val lsText = "$GOLD$priceLs $GRAY| ${data.color}${data.name} $GRAY[${AQUA}LS$GRAY]: $AQUA${Helper.formatNumber(amountLs,
            withCommas = true
        )}$percentLsText"
        val combinedText = "$baseText $GRAY[${AQUA}LS$GRAY:$AQUA${Helper.formatNumber(amountLs, withCommas = true)}$GRAY]"

        val line = OverlayTextLine(combinedText).onClick { hideLine(itemNameBase) }
            .setCondition {
                val meetsZeroValueCondition = totalAmount > 0 || !Diana.hideUnobtainedItems
                val meetsManualHideCondition = !(!isCraftingScreenOpen() && itemNameBase in SBOConfigBundle.sboData.hideTrackerLines)
                meetsZeroValueCondition && meetsManualHideCondition
            }
            .onHover { drawContext, textRenderer ->
                val scaleFactor = mc.window.guiScale
                val mouseX = mc.mouseHandler.xpos() / scaleFactor
                val mouseY = mc.mouseHandler.ypos() / scaleFactor

                RenderUtils2D.drawHoveringString(
                    drawContext,
                    "$YELLOW${Helper.toTitleCase(itemNameBase.replace("_", " ").lowercase())} LS Details:\n" +
                            lsText,
                    mouseX, mouseY, textRenderer
                )
            }
        if (itemNameBase in SBOConfigBundle.sboData.hideTrackerLines) {
            line.text = "$GRAY$STRIKETHROUGH${combinedText.removeFormatting()}"
        }
        return line
    }

    override fun generateLines(): List<OverlayTextLine> {
        val type = Diana.lootTracker
        val tracker = getDianaTracker(type) ?: run {
            return@generateLines emptyList()
        }

        val isCraftingOpen = CRAFTING_PLAYER_INVENTORY_FILTER(mc.screen)
        val lines = mutableListOf<OverlayTextLine>()

        updateControlLines(lines, isCraftingOpen)
        lines.add(OverlayTextLine("$YELLOW${BOLD}Diana Loot $GRAY($YELLOW${Helper.toTitleCase("$type")}$GRAY)"))
        lines.addAll(generateLootLines(tracker))
        lines.addAll(generateStatisticsLines(tracker, type))

        return lines
    }

    private fun getDianaTracker(type: Diana.Tracker): DianaTracker? {
        return when (type) {
            Diana.Tracker.TOTAL -> SBOConfigBundle.dianaTrackerTotalData
            Diana.Tracker.EVENT -> SBOConfigBundle.dianaTrackerMayorData
            Diana.Tracker.SESSION -> SBOConfigBundle.dianaTrackerSessionData
            Diana.Tracker.OFF -> null
        }
    }

    private fun updateControlLines(lines: MutableList<OverlayTextLine>, isCraftingOpen: Boolean) {
        val screenOpen = isCraftingOpen || isCraftingScreenOpen()
        val sellTypeText = if (isSellTypeHovered) {
            "$YELLOW${UNDERLINE}${Helper.toTitleCase(Diana.bazaarSettingDiana.toString())}"
        } else {
            "${YELLOW}${Helper.toTitleCase(Diana.bazaarSettingDiana.toString())}"
        }
        changeSellType.text = sellTypeText

        if (screenOpen) {
            lines.addAll(listOf(changeView, delimiter, changeSellType))
            if (Diana.lootTracker == Diana.Tracker.SESSION) lines.add(resetSession)
        }
    }

    private fun generateLootLines(tracker: DianaTracker): List<OverlayTextLine> {
        val lines = mutableListOf<OverlayTextLine>()
        for (data in LOOT_ITEMS) {
            if (Diana.combineLootLines && data.combined) {
                lines.add(createCombinedLootLine(data, tracker))
            } else if (data.combined) {
                lines.add(createLootLine(data, tracker))
                val lsData = data.copy(
                    id = "${data.id}_LS",
                    name = "${data.name} $GRAY[${AQUA}LS$GRAY]",
                    combined = false,
                    isRarerDrop = true
                )
                lines.add(createLootLine(lsData, tracker))
            } else {
                lines.add(createLootLine(data, tracker))
            }
        }
        return lines
    }

    private fun generateStatisticsLines(tracker: DianaTracker, type: Diana.Tracker): List<OverlayTextLine> {
        val timer = when (type) {
            Diana.Tracker.TOTAL -> SboTimerManager.timerTotal
            Diana.Tracker.EVENT -> SboTimerManager.timerMayor
            Diana.Tracker.SESSION -> SboTimerManager.timerSession
            else -> SboTimerManager.timerMayor
        }
        val totalBurrows = tracker.items.TOTAL_BURROWS
        val totalProfitValue = totalProfit(tracker)
        val playTimeHrs = tracker.items.TIME.toDouble() / TimeUnit.HOURS.toMillis(1)
        val totalEvents = SBOConfigBundle.pastDianaEventsData.events.size

        val burrowsPerHr = Helper.getBurrowsPerHr(tracker, timer)
        val bphText = if (burrowsPerHr.isNaN()) "" else " $GRAY[$AQUA$burrowsPerHr$GRAY/${AQUA}hr$GRAY]"

        val profitPerHr = if (playTimeHrs > 0) Helper.formatNumber(totalProfitValue / playTimeHrs) else 0.0
        val profitPerBurrow = if (totalBurrows > 0) Helper.formatNumber(totalProfitValue / totalBurrows) else 0.0

        val stats = mutableListOf(
            OverlayTextLine("${GRAY}Total Burrows: $AQUA${Helper.formatNumber(totalBurrows, withCommas = true)}$bphText")
        )

        // Raw crown coin line - hidden when detached (it moves to DianaCrownTracker
        // overlay instead), and hidden entirely for someone with no crown tracked
        // at all, same as the detached overlay - nothing to show, nothing worth a
        // permanent "Raw Coins: 0" line for.
        if (!Diana.detachedCrownTracker && Diana.enableLoadoutCrownTracker &&
            LoadoutCrownTracker.getConfirmedCrowns().isNotEmpty()) {
            stats.add(createCoinLine(playTimeHrs))
        }

        stats.add(createProfitLine(totalProfitValue, profitPerHr, profitPerBurrow))

        stats.add(timerLine)
        if (type == Diana.Tracker.TOTAL) stats.add(OverlayTextLine("${YELLOW}Total Events: $AQUA$totalEvents"))

        return stats
    }

    private fun createCoinLine(playTimeHrs: Double): OverlayTextLine {
        val totalGained = LoadoutCrownTracker.lootTrackerTotalGained
        // Multiplier is per-crown (its cost basis vs the current market value of a
        // filled crown) - the most active confirmed crown ("Damage Crown") stands
        // in for the whole line, since this line combines every crown's coins.
        val primaryCrown = LoadoutCrownTracker.getConfirmedCrowns().firstOrNull()
        val hasPrice = primaryCrown != null && primaryCrown.costBasis >= 0L
        val multiplier = if (hasPrice) LoadoutCrownTracker.multiplierFor(primaryCrown!!) else 1.0
        val estimatedProfit = (totalGained * multiplier).toLong()
        val profitPerHr = if (playTimeHrs > 0) estimatedProfit / playTimeHrs else 0.0

        // An unset cost basis would otherwise silently render as a fake-looking
        // "1.00x" (multiplierFor()'s neutral fallback) - warn instead, since that
        // number isn't a real calibration. No crown tracked yet at all just omits
        // the bracket entirely - there's nothing actionable to warn about yet.
        val multiText = when {
            !hasPrice && primaryCrown != null -> " $RED(no price set - $YELLOW/sbocrown price$RED)"
            hasPrice -> " $GRAY(${AQUA}${"%.2f".format(java.util.Locale.US, multiplier)}x$GRAY)"
            else -> ""
        }
        val cphText = if (hasPrice && profitPerHr > 0) " $GRAY[$AQUA${Helper.formatNumber(profitPerHr)}$GRAY/${AQUA}hr$GRAY]" else ""
        return OverlayTextLine("${YELLOW}Raw Coins: $AQUA${Helper.formatNumber(totalGained)}$multiText$cphText")
            .onHover { drawContext, textRenderer ->
                val scaleFactor = mc.window.guiScale
                val mouseX = mc.mouseHandler.xpos() / scaleFactor
                val mouseY = mc.mouseHandler.ypos() / scaleFactor
                val costText = when {
                    primaryCrown == null -> "not tracked yet"
                    primaryCrown.costBasis < 0L -> "unset - use /sbocrown price <amount>"
                    else -> {
                        val sourceText = when (primaryCrown.costBasisSource) {
                            "MANUAL" -> " (manual)"
                            "AH_HISTORY" -> " (found in AH history)"
                            "PURSE_DETECTED" -> " (auto-detected)"
                            else -> ""
                        }
                        "${Helper.formatNumber(primaryCrown.costBasis, withCommas = true)}$sourceText"
                    }
                }
                val startingCoinsText = if (primaryCrown != null && primaryCrown.startingCoins > 0) {
                    "\n${GOLD}Started pre-filled with: $AQUA${Helper.formatNumber(primaryCrown.startingCoins, withCommas = true)}"
                } else ""
                val multiplierText = if (hasPrice) "${"%.2f".format(java.util.Locale.US, multiplier)}x" else "not set"
                RenderUtils2D.drawHoveringString(drawContext,
                    "${YELLOW}${BOLD}Crown Coin Breakdown:\n" +
                    "${GOLD}Raw Coins (this session): $AQUA${Helper.formatNumber(totalGained)}\n" +
                    "${GOLD}Filled crown market value (1wk, cofl): $AQUA${Helper.formatNumber(CrownMarket.weeklyAvgFilledPrice, withCommas = true)}\n" +
                    "${GOLD}This crown's cost: $AQUA$costText$startingCoinsText\n" +
                    "${GOLD}Multiplier: $AQUA$multiplierText\n" +
                    "${GOLD}Est. Profit: $AQUA${Helper.formatNumber(estimatedProfit)}\n" +
                    "$DARK_GRAY(/sbocrown price <amount> to set your crown's cost)",
                    mouseX, mouseY, textRenderer)
            }
    }

    private fun createProfitLine(totalProfitValue: Long, profitPerHr: Any, profitPerBurrow: Any): OverlayTextLine {
        val pphText = if (profitPerHr == "NaN" || profitPerHr == "0.0") "" else "$GRAY[$AQUA$profitPerHr$GRAY/${AQUA}hr$GRAY]"
        return OverlayTextLine("${YELLOW}Total Profit: $AQUA${Helper.formatNumber(totalProfitValue)} $pphText")
            .onHover { drawContext, textRenderer ->
                val scaleFactor = mc.window.guiScale
                val mouseX = mc.mouseHandler.xpos() / scaleFactor
                val mouseY = mc.mouseHandler.ypos() / scaleFactor
                RenderUtils2D.drawHoveringString(drawContext,
                    "$GOLD$profitPerHr coins/hr\n" +
                            "$GOLD$profitPerBurrow coins/burrow",
                    mouseX, mouseY, textRenderer)
            }
    }

    fun totalProfit(tracker: DianaTracker): Long {
        var totalProfit = 0L
        for (item in tracker.items::class.java.declaredFields) {
            item.isAccessible = true
            var itemName = item.name
            if (itemName == "TIME" || itemName == "TOTAL_BURROWS" || itemName == "COINS" || itemName == "SCAVENGER_COINS" || itemName == "FISH_COINS") continue
            itemName = itemName.replace("_LS", "")
            val itemValue = item.get(tracker.items) as Int
            if (itemValue <= 0) continue
            val itemPrice = Helper.getItemPrice(itemName)
            if (itemPrice > 0) {
                totalProfit += itemPrice * itemValue
            }
        }
        return if (Diana.excludeCoinsFromProfit) totalProfit else totalProfit + tracker.items.COINS
    }

    private fun updateTimerText() {
        val type = Diana.lootTracker
        val tracker = when (type) {
            Diana.Tracker.TOTAL -> SBOConfigBundle.dianaTrackerTotalData
            Diana.Tracker.EVENT -> SBOConfigBundle.dianaTrackerMayorData
            Diana.Tracker.SESSION -> SBOConfigBundle.dianaTrackerSessionData
            Diana.Tracker.OFF -> {
                timerLine.text = ""
                return
            }
        }

        val timer = when (type) {
            Diana.Tracker.TOTAL -> SboTimerManager.timerTotal
            Diana.Tracker.EVENT -> SboTimerManager.timerMayor
            Diana.Tracker.SESSION -> SboTimerManager.timerSession
            Diana.Tracker.OFF -> {
                timerLine.text = ""
                return
            }
        }

        val formattedTime = Helper.formatTime(tracker.items.TIME)
        val text = if (timer.isRunning()) {
            "${YELLOW}Playtime: $AQUA$formattedTime"
        } else {
            "${YELLOW}Playtime: $AQUA$formattedTime ${GRAY}[${RED}PAUSED${GRAY}]"
        }
        timerLine.text = text
    }

}
