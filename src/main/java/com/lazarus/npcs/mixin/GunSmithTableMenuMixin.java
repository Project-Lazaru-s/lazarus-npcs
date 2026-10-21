package com.lazarus.npcs.mixin;

import com.lazarus.npcs.GunSkillLock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refuses a gun the player has not learned before the TaCZ gun smith table takes any material. Skipped without TaCZ. */
@Pseudo
@Mixin(targets = "com.tacz.guns.inventory.GunSmithTableMenu", remap = false)
abstract class GunSmithTableMenuMixin {
    @Inject(method = "doCraft", at = @At("HEAD"), cancellable = true)
    private void lazarus$skillLock(ResourceLocation recipeId, Player player, CallbackInfo ci) {
        if (!GunSkillLock.allowed(player, recipeId)) ci.cancel();
    }
}
