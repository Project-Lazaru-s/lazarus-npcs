package com.lazarus.npcs.mixin;

import com.lazarus.npcs.NpcHazards;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at = @At("RETURN"), cancellable = true)
    private void lazarusnpcs$avoidHazards(BlockGetter level, int x, int y, int z, Mob mob,
            CallbackInfoReturnable<BlockPathTypes> result) {
        result.setReturnValue(NpcHazards.pathType(level, new BlockPos(x, y, z), mob, result.getReturnValue()));
    }
}
