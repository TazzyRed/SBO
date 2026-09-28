package net.sbo.mod.qol

import net.sbo.mod.settings.categories.QOL
import net.sbo.mod.utils.events.Register
import java.util.regex.Pattern

object MessageHider {
    private data class HideRule(val pattern: Pattern, val isEnabled: () -> Boolean)

    private val generalRules = listOf(
        HideRule(Pattern.compile("^§cAutopet §eequipped your (.*?) §a§lVIEW RULE$")) { QOL.hideAutoPetMSG },
        HideRule(Pattern.compile("^§7Your Implosion hit (.*?) §7damage\\.$")) { QOL.hideImplosionMSG },
    )

    private val dianaRules = listOf(
        HideRule(Pattern.compile("^§eFollow the arrows to find the §6treasure§e!$")) { QOL.dianaMessageHider },
        HideRule(Pattern.compile("^§cThis ability is on cooldown for (.*?)$")) { QOL.dianaMessageHider },
        HideRule(Pattern.compile("^§7Warping\\.\\.\\.$")) { QOL.dianaMessageHider },
        HideRule(Pattern.compile("^§cThere are blocks in the way!$")) { QOL.dianaMessageHider },
        // Only unanchored/loose on this one since the exact leading color code
        // isn't confirmed - a substring match still reliably catches it either way.
        HideRule(Pattern.compile("Couldn't find an appropriate burrow\\.")) { QOL.dianaMessageHider },
        // "Wow! You dug out X coins!" - still processed for real by LoadoutCrownTracker's
        // NBT recheck and DianaTracker's coin tracking regardless of chat visibility;
        // this only suppresses the raw Hypixel line itself.
        HideRule(Pattern.compile("^§6§lWow! §eYou dug out §6(.*?) coins§e!$")) { QOL.dianaMessageHider },
        // Burrow/treasure-sourced rare drops only ("You dug out a X!") - mob-kill rare
        // drops (Chimera, Hilt of Revelations, etc.) use a different message template
        // entirely, so this can't accidentally catch those.
        HideRule(Pattern.compile("^§6§lRARE DROP! §eYou dug out a (.*?)§e!$")) { QOL.dianaMessageHider },
    )

    fun init() {
        (generalRules + dianaRules).forEach { rule ->
            Register.onChatMessageCancelable(rule.pattern) { _, _ -> !rule.isEnabled() }
        }
    }
}