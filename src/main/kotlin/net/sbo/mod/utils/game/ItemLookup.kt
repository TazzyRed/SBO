package net.sbo.mod.utils.game

import gg.essential.universal.utils.toFormattedString
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

class ItemLookup(private val stack: ItemStack) {
    private val customData: CustomData? by lazy(LazyThreadSafetyMode.NONE) { this.stack.get(DataComponents.CUSTOM_DATA) }
    private val nbt: CompoundTag? by lazy(LazyThreadSafetyMode.NONE) { this.customData?.copyTag() }

    val sbId: String by lazy(LazyThreadSafetyMode.NONE) { this.nbt?.getString("id")?.orElse("") ?: "" }
    val uuid: String by lazy(LazyThreadSafetyMode.NONE) { this.nbt?.getString("uuid")?.orElse("") ?: "" }
    val timestamp: Long by lazy(LazyThreadSafetyMode.NONE) { this.nbt?.getLong("timestamp")?.orElse(0L) ?: 0L }

    val displayName: String by lazy(LazyThreadSafetyMode.NONE) { this.stack.hoverName.toFormattedString() }
    val loreList: List<String> by lazy (LazyThreadSafetyMode.NONE) {
        val lines = this.stack.get(DataComponents.LORE)?.lines ?: return@lazy emptyList()
        lines.map { it.toFormattedString() }
    }

    /**
     * Reads an arbitrary Long-valued key straight out of the item's ExtraAttributes-equivalent
     * NBT (the same tag [sbId]/[uuid]/[timestamp] are read from). Returns null if the key is
     * absent, rather than defaulting to 0, so callers can distinguish "no data yet" from "0".
     */
    fun getLong(key: String): Long? = this.nbt?.getLong(key)?.orElse(null)
}
