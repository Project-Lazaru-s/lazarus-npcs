package com.lazarus.npcs.mixin;

import com.lazarus.npcs.ProfessionSkins;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Swaps the body texture of Rovers/Wardens with an assigned profession; layers (armor, items) are untouched. */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    // require = 0: if another mod rewrites this call the NPCs keep their original skins instead of crashing the client.
    @Redirect(method = "getRenderType", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getTextureLocation(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/resources/ResourceLocation;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResourceLocation lazarusnpcs$professionSkin(LivingEntityRenderer renderer, Entity entity) {
        ResourceLocation skin = ProfessionSkins.texture(entity);
        return skin != null ? skin : ((EntityRenderer) renderer).getTextureLocation(entity);
    }
}
