package com.lazarus.npcs;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla's shift-click path assumes the Merchant is also an Entity when playing
 * its trade sound. Our merchant wraps an Apocalypse Now entity instead, so the
 * result slot needs the same transfer flow without that unsafe cast.
 */
final class SafeMerchantMenu extends MerchantMenu {
    private final NpcMerchant merchant;

    SafeMerchantMenu(int containerId, Inventory inventory, NpcMerchant merchant) {
        super(containerId, inventory, merchant);
        this.merchant = merchant;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index != 2) return super.quickMoveStack(player, index);

        Slot resultSlot = slots.get(2);
        if (!resultSlot.hasItem()) return ItemStack.EMPTY;
        ItemStack result = resultSlot.getItem();
        ItemStack original = result.copy();
        if (!moveItemStackTo(result, 3, 39, true)) return ItemStack.EMPTY;

        resultSlot.onQuickCraft(result, original);
        merchant.playTradeSound();
        if (result.isEmpty()) resultSlot.setByPlayer(ItemStack.EMPTY);
        else resultSlot.setChanged();
        if (result.getCount() == original.getCount()) return ItemStack.EMPTY;
        resultSlot.onTake(player, result);
        return original;
    }
}
