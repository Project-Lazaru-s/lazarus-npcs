package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.BlockPathTypes;

public final class NpcHazards {
    private static final TagKey<Block> HAZARDS = BlockTags.create(new ResourceLocation(LazarusNpcs.ID, "npc_hazards"));

    private NpcHazards() {}

    public static BlockPathTypes pathType(BlockGetter level, BlockPos pos, Mob mob, BlockPathTypes original) {
        if (NpcIdentity.role(mob) == null && HumanEncounters.kind(mob) == null) return original;
        if (hazard(level, pos) || hazard(level, pos.below())) return BlockPathTypes.BLOCKED;
        if (original != BlockPathTypes.OPEN && original != BlockPathTypes.WALKABLE) return original;
        for (BlockPos neighbor : new BlockPos[] {pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (hazard(level, neighbor) || hazard(level, neighbor.below())) return BlockPathTypes.DANGER_OTHER;
        }
        return original;
    }

    private static boolean hazard(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(HAZARDS);
    }
}
