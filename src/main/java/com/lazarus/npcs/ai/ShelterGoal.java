package com.lazarus.npcs.ai;

import com.lazarus.npcs.LazarusNpcs;
import com.lazarus.npcs.NpcAiHooks;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** At night, unassigned peaceful humans seek a nearby roof and wait there until dawn. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class ShelterGoal extends Goal {
    private final Mob actor;
    private BlockPos shelter;
    private long searchAt;
    private int repath;

    private ShelterGoal(Mob actor) { this.actor = actor; setFlags(EnumSet.of(Flag.MOVE)); }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob actor)
                || !NpcAiHooks.peacefulScavenger(actor)) return;
        for (WrappedGoal goal : actor.goalSelector.getAvailableGoals())
            if (goal.getGoal() instanceof ShelterGoal) return;
        actor.goalSelector.addGoal(0, new ShelterGoal(actor));
    }

    private boolean night() {
        long time = actor.level().getDayTime() % 24000;
        return time >= 13000 && time < 23000;
    }

    @Override public boolean canUse() {
        return actor.level() instanceof ServerLevel && night() && NpcAiHooks.canScavenge(actor)
                && Lod.band(actor) == Lod.Band.FULL;
    }

    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void stop() { actor.getNavigation().stop(); shelter = null; }

    @Override public void tick() {
        ServerLevel level = (ServerLevel) actor.level();
        long now = level.getGameTime();
        if (shelter == null && now >= searchAt && Budget.container(level)) {
            searchAt = now + 100;
            BlockPos home = actor.blockPosition();
            double best = Double.POSITIVE_INFINITY;
            for (int dx = -24; dx <= 24; dx += 4) for (int dz = -24; dz <= 24; dz += 4) {
                BlockPos pos = home.offset(dx, 0, dz);
                if (!level.isLoaded(pos) || level.canSeeSky(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                        || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                        || !level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below())) continue;
                double distance = pos.distSqr(home);
                if (distance < best) { best = distance; shelter = pos; }
            }
        }
        if (shelter == null || !level.isLoaded(shelter) || level.canSeeSky(shelter)) return;
        if (actor.distanceToSqr(shelter.getX() + .5, shelter.getY(), shelter.getZ() + .5) < 4) {
            actor.getNavigation().stop();
            return;
        }
        if (repath-- <= 0 && Budget.path(level)) {
            repath = 20;
            actor.getNavigation().moveTo(shelter.getX() + .5, shelter.getY(), shelter.getZ() + .5, 0.85);
        }
    }
}
