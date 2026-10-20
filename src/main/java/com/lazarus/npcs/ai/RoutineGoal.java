package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcAiHooks;
import com.lazarus.npcs.api.ai.Activity;
import com.lazarus.npcs.api.ai.NightDuty;
import com.lazarus.npcs.api.ai.Routine;
import java.util.EnumSet;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;

/** Weighted daytime activity and night shelter, with immediate preemption during an alarm. */
public final class RoutineGoal extends Goal {
    private static final WeakHashMap<Mob, Routine> ROUTINES = new WeakHashMap<>();
    private final Mob actor;
    private Vec3 destination;
    private BlockPos wanted;
    private long changeAt;
    private int repath;
    private boolean sleeping;

    private RoutineGoal(Mob actor) { this.actor = actor; setFlags(EnumSet.of(Flag.MOVE)); }

    public static Routine routine(Mob actor) { return ROUTINES.get(actor); }

    public static boolean sameBase(Mob actor, LivingEntity other) {
        Routine own = routine(actor);
        return own != null && other instanceof Mob mob && routine(mob) != null
                && own.baseId().equals(routine(mob).baseId());
    }

    public static void assign(Mob actor, Routine routine) {
        ROUTINES.put(actor, routine);
        for (WrappedGoal entry : actor.goalSelector.getAvailableGoals())
            if (entry.getGoal() instanceof RoutineGoal) return;
        actor.goalSelector.addGoal(-1, new RoutineGoal(actor));
    }

    private boolean night() {
        long time = actor.level().getDayTime() % 24000;
        return time >= 13000 && time < 23000;
    }

    @Override public boolean canUse() {
        Routine schedule = routine(actor);
        if (schedule == null || !(actor.level() instanceof ServerLevel) || actor.getTarget() != null
                || NpcAiHooks.busy(actor) || Lod.band(actor) == Lod.Band.FAR) return false;
        return schedule.night() != NightDuty.WATCH || BaseAlert.active(actor) || night();
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void stop() {
        if (actor.isSleeping()) actor.stopSleeping();
        else actor.setPose(Pose.STANDING);
        sleeping = false;
        destination = null;
        wanted = null;
        PointPicker.release(actor);
        actor.getNavigation().stop();
    }

    @Override public void tick() {
        Routine schedule = routine(actor);
        if (schedule == null) return;
        BaseAlert.watch(actor);
        long now = actor.level().getGameTime();
        boolean alarm = BaseAlert.active(actor);
        boolean bedExists = schedule.bed() != null && actor.level().getBlockState(schedule.bed())
                .getBlock() instanceof net.minecraft.world.level.block.BedBlock;
        if (sleeping && (alarm || !night() || !bedExists)) {
            if (actor.isSleeping()) actor.stopSleeping();
            else actor.setPose(Pose.STANDING);
            sleeping = false;
            destination = null;
            wanted = null;
        }
        if (alarm || night()) {
            if (alarm && schedule.night() != NightDuty.WATCH) target(schedule.shelter(), now);
            else if (schedule.night() == NightDuty.SLEEP && !alarm && bedExists) {
                target(schedule.bed(), now);
                if (destination != null && actor.distanceToSqr(destination) < 4) {
                    actor.getNavigation().stop();
                    actor.startSleeping(schedule.bed());
                    sleeping = true;
                    return;
                }
            } else target(schedule.night() == NightDuty.WATCH ? schedule.defensePost() : schedule.shelter(), now);
        } else if (destination == null || now >= changeAt) {
            actor.setPose(Pose.STANDING);
            List<Activity> activities = schedule.activities();
            int total = activities.stream().mapToInt(Activity::weight).sum();
            int roll = actor.getRandom().nextInt(total);
            Activity selected = activities.get(0);
            for (Activity activity : activities) { roll -= activity.weight(); if (roll < 0) { selected = activity; break; } }
            target(selected.at(), now);
            changeAt = now + 260 + actor.getRandom().nextInt(260);
        }
        if (destination != null && actor.distanceToSqr(destination) > 2.5 && ++repath >= 20) {
            repath = 0;
            if (Budget.path((ServerLevel) actor.level())) actor.getNavigation().moveTo(destination.x,
                    destination.y, destination.z, 0.8);
        }
    }

    private void target(BlockPos wanted, long now) {
        if (destination != null && wanted.equals(this.wanted)) return;
        this.wanted = wanted;
        PointPicker.release(actor);
        for (int radius = 0; radius <= 2; radius++)
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = wanted.offset(dx, 0, dz);
                if (PointPicker.reserve(actor, pos, 120)) {
                    destination = Vec3.atBottomCenterOf(pos);
                    repath = 20;
                    return;
                }
            }
        destination = null;
    }
}
