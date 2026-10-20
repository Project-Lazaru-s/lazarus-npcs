package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraftforge.registries.ForgeRegistries;

public final class NpcHazards {
    private static final TagKey<Block> HAZARDS = BlockTags.create(new ResourceLocation(LazarusNpcs.ID, "npc_hazards"));

    private NpcHazards() {}

    public static BlockPathTypes pathType(BlockGetter level, BlockPos pos, Mob mob, BlockPathTypes original) {
        if (NpcIdentity.role(mob) == null && HumanEncounters.kind(mob) == null) return original;
        if (hazard(level, pos) || hazard(level, pos.below())) return BlockPathTypes.BLOCKED;
        if (lockedOrCustomDoor(level, pos)) return BlockPathTypes.BLOCKED;
        if (original != BlockPathTypes.OPEN && original != BlockPathTypes.WALKABLE) return original;
        for (BlockPos neighbor : new BlockPos[] {pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (hazard(level, neighbor) || hazard(level, neighbor.below())) return BlockPathTypes.DANGER_OTHER;
        }
        return original;
    }

    private static boolean hazard(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(HAZARDS);
    }

    private static boolean lockedOrCustomDoor(BlockGetter level, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        var id = ForgeRegistries.BLOCKS.getKey(block);
        if (id != null && (id.getNamespace().contains("locks") || id.getNamespace().contains("realisticdoor")))
            return true;
        if (!(block instanceof DoorBlock)) return false;
        for (BlockPos around : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 2, 1))) {
            var near = ForgeRegistries.BLOCKS.getKey(level.getBlockState(around).getBlock());
            if (near != null && (near.getNamespace().contains("locks") || near.getNamespace().contains("realisticdoor")))
                return true;
        }
        return false;
    }
}
