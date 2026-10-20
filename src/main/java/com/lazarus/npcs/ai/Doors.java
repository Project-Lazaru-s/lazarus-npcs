package com.lazarus.npcs.ai;

import com.lazarus.npcs.LazarusNpcs;
import com.lazarus.npcs.NpcAiHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Only ordinary unlocked doors are allowed; custom Realistic Door and nearby Locks blocks stay closed. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class Doors {
    private Doors() {}

    public static boolean usable(Mob actor, BlockPos pos) {
        if (!actor.level().isLoaded(pos)) return false;
        for (BlockPos around : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 2, 1))) {
            if (!actor.level().isLoaded(around)) return false;
            var id = ForgeRegistries.BLOCKS.getKey(actor.level().getBlockState(around).getBlock());
            if (id != null && (id.getNamespace().contains("locks") || id.getNamespace().contains("realisticdoor")))
                return false;
        }
        var state = actor.level().getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && !state.hasBlockEntity();
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob actor)
                || !NpcAiHooks.canScavenge(actor) || !(actor.getNavigation() instanceof GroundPathNavigation navigation)) return;
        navigation.setCanOpenDoors(true);
        for (WrappedGoal goal : actor.goalSelector.getAvailableGoals())
            if (goal.getGoal() instanceof SafeOpenDoorGoal) return;
        for (WrappedGoal goal : java.util.List.copyOf(actor.goalSelector.getAvailableGoals()))
            if (goal.getGoal() instanceof OpenDoorGoal) actor.goalSelector.removeGoal(goal.getGoal());
        actor.goalSelector.addGoal(3, new SafeOpenDoorGoal(actor));
    }

    private static final class SafeOpenDoorGoal extends OpenDoorGoal {
        private final Mob actor;
        SafeOpenDoorGoal(Mob actor) { super(actor, true); this.actor = actor; }
        @Override public boolean canUse() { return super.canUse() && usable(actor, doorPos); }
        @Override public boolean canContinueToUse() { return super.canContinueToUse() && usable(actor, doorPos); }
    }
}
