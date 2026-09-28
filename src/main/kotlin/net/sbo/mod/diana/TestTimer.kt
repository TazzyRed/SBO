package net.sbo.mod.diana

import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Helper.showTitle
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.data.SboDataObject.SBOConfigBundle
import net.sbo.mod.utils.events.Register

/**
 * Fires a notification when the session tracker's accumulated active playtime
 * (dianaTrackerSessionData.items.TIME) hits 1 hour. Toggled via
 * Diana.testTimerEnabled. Since it watches the session tracker's playtime
 * rather than wall-clock time, it only counts time you're actually active -
 * pausing/AFK doesn't advance it.
 */
object TestTimer {

    private const val DURATION_MS = 3_600_000L // 1 hour in ms
    private var fired = false

    fun start() {
        fired = false
        Chat.chat("§6[SBO] §aTest timer started - you'll get a notification when your session hits 1 hour of playtime.")
    }

    fun cancel() {
        Chat.chat("§6[SBO] §7Test timer cancelled.")
        fired = false
    }

    fun init() {
        Register.onTick(20) {
            if (!Diana.testTimerEnabled || fired) return@onTick
            val sessionMs = SBOConfigBundle.dianaTrackerSessionData.items.TIME
            if (sessionMs < DURATION_MS) return@onTick

            fired = true
            Diana.testTimerEnabled = false
            showTitle("§6§l1 HOUR REACHED!", "§eYour session has hit 1 hour of active playtime.", 10, 80, 20)
            Chat.chat("§6[SBO] §aYour session tracker has hit 1 hour of active playtime!")
        }
    }
}
