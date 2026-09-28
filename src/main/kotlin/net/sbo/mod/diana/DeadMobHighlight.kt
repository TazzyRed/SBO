package net.sbo.mod.diana

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.world.entity.Entity
import net.sbo.mod.settings.categories.Customization
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.events.annotations.SboEvent
import net.sbo.mod.utils.events.impl.entity.DianaMobDeathEvent
import net.sbo.mod.utils.events.impl.entity.EntityUnloadEvent
import net.sbo.mod.utils.render.RenderUtils3D
import java.awt.Color

/**
 * A dying Diana mob's corpse can linger for a bit before it actually despawns, and it's
 * easy to confuse for a still-live one in the middle of a fight. Fills the corpse's own
 * live bounding box with a solid translucent color - unambiguous, unlike a thin glow
 * outline which reads too similarly to the vanilla hurt-flash red mobs already show
 * while dying. Uses the same Gizmos-based box renderer the waypoint boxes use, so no
 * mixins or render-pipeline internals involved.
 */
object DeadMobHighlight : LevelRenderEvents.CollectSubmits {
    private val highlighted = mutableSetOf<Entity>()

    fun init() {
        Register.onTick(20) { cleanupRemovedEntities() }
        LevelRenderEvents.COLLECT_SUBMITS.register(this)
    }

    @SboEvent
    fun onDianaMobDeath(event: DianaMobDeathEvent) {
        if (!Diana.translucentDeadMobs) return
        highlighted.add(event.entity)
    }

    @SboEvent
    fun onEntityUnload(event: EntityUnloadEvent) {
        highlighted.remove(event.entity)
    }

    override fun collectSubmits(context: LevelRenderContext) {
        if (highlighted.isEmpty()) return

        val color = Color(Customization.DeadMobHighlightColor)
        val rgb = floatArrayOf(color.red / 255f, color.green / 255f, color.blue / 255f)

        highlighted.forEach { entity ->
            RenderUtils3D.renderBoxFill(entity.boundingBox, rgb, 0.45f)
        }
    }

    /** Safety net in case an unload event is ever missed for one of these. */
    private fun cleanupRemovedEntities() {
        val iterator = highlighted.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isRemoved) iterator.remove()
        }
    }
}
