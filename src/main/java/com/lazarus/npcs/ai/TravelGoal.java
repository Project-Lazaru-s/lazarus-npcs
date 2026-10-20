package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcAiHooks;
import com.lazarus.npcs.NpcPerf;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** Saved route walked back and forth. Combat preempts movement; a squad regroups on its scout afterwards. */
public final class TravelGoal extends Goal {
    private static final String KEY = "LazarusNpcsTravel";
    private final Mob actor;
    private int repath;

    private TravelGoal(Mob actor) { this.actor = actor; setFlags(EnumSet.of(Flag.MOVE)); }

    public static void attach(Mob actor) {
        for (WrappedGoal entry : actor.goalSelector.getAvailableGoals())
            if (entry.getGoal() instanceof TravelGoal) return;
        actor.goalSelector.addGoal(-1, new TravelGoal(actor));
    }

    public static void clear(Mob actor) { actor.getPersistentData().remove(KEY); }

    /** The waypoint the squad is heading for, or null without a saved route. */
    public static BlockPos waypoint(Mob actor) {
        CompoundTag tag = actor.getPersistentData().getCompound(KEY);
        long[] points = tag.getLongArray("Points");
        return points.length == 0 ? null : BlockPos.of(points[Math.min(points.length - 1, Math.max(0, tag.getInt("Index")))]);
    }

    public static void route(Mob actor, ResourceLocation owner, UUID encounter, List<BlockPos> points) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Owner", owner.toString());
        tag.putUUID("Encounter", encounter);
        tag.putLongArray("Points", points.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putInt("Index", 0);
        actor.getPersistentData().put(KEY, tag);
        attach(actor);
    }

    private ScenarioAssignment assignment() {
        ScenarioAssignment a = NpcApi.assignment(actor).orElse(null);
        if (a == null || a.order() != ScenarioOrder.TRAVEL || actor.getTarget() != null || NpcAiHooks.busy(actor))
            return null;
        CompoundTag tag = actor.getPersistentData().getCompound(KEY);
        return tag.hasUUID("Encounter") && tag.getUUID("Encounter").equals(a.encounterId())
                && tag.getString("Owner").equals(a.owner().toString()) ? a : null;
    }

    @Override public boolean canUse() { return assignment() != null; }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void stop() { actor.getNavigation().stop(); repath = 0; }

    @Override public void tick() {
        long start = System.nanoTime();
        try { step(); }
        finally { NpcPerf.record("travel", System.nanoTime() - start); }
    }

    private void step() {
        ScenarioAssignment a = assignment();
        if (a == null || !(actor.level() instanceof ServerLevel level)) return;
        List<Mob> members = Squads.scenario(actor);
        if (members.isEmpty()) members = List.of(actor);
        Mob scout = members.get(0);
        CompoundTag scoutTag = scout.getPersistentData().getCompound(KEY);
        CompoundTag own = actor.getPersistentData().getCompound(KEY);
        long[] points = own.getLongArray("Points");
        if (points.length < 4) return;
        // Followers mirror the scout's progress so whoever becomes scout next continues from the same point.
        if (scout != actor && scoutTag.hasUUID("Encounter") && scoutTag.getUUID("Encounter").equals(own.getUUID("Encounter")))
            actor.getPersistentData().put(KEY, scoutTag.copy());
        int index = Math.min(points.length - 1, Math.max(0, scoutTag.getInt("Index")));
        BlockPos waypoint = BlockPos.of(points[index]);
        long now = level.getGameTime();
        if (!level.isLoaded(waypoint)) {
            // Never request an unloaded chunk. A route that stalls here is ended by the encounter's expiry.
            actor.getNavigation().stop();
            return;
        }
        if (scout == actor && now % 20 == 0 && !scoutTag.contains("Detour") && horde(level, waypoint)) {
            BlockPos side = waypoint.offset(8, 0, 8);
            if (PointPicker.standable(actor, side)) {
                scoutTag.putLong("Detour", side.asLong());
                scoutTag.putLong("DetourUntil", now + 200);
            }
        }
        if (scout == actor && scoutTag.contains("Detour")) {
            BlockPos detour = BlockPos.of(scoutTag.getLong("Detour"));
            if (actor.distanceToSqr(Vec3.atBottomCenterOf(detour)) < 9 || now > scoutTag.getLong("DetourUntil")) {
                scoutTag.remove("Detour");
                scoutTag.remove("DetourUntil");
            } else waypoint = detour;
        }
        if (scout == actor && actor.distanceToSqr(Vec3.atBottomCenterOf(waypoint)) < 9) {
            long pause = scoutTag.getLong("PauseUntil");
            if (pause == 0 && index % 2 == 0) {
                pause = now + 60 + actor.getRandom().nextInt(41);
                scoutTag.putLong("PauseUntil", pause);
            }
            // Wait for stragglers at a halt, but never forever: a member stuck after a fight must not freeze the squad.
            boolean gathered = members.stream().allMatch(member -> member.distanceToSqr(actor) < 12 * 12);
            if (now >= pause && (pause == 0 || gathered || now >= pause + 200)) {
                // The route is walked back and forth until the encounter expires.
                int direction = scoutTag.getInt("Dir") < 0 ? -1 : 1;
                if (index + direction < 0 || index + direction >= points.length) direction = -direction;
                scoutTag.putInt("Dir", direction);
                scoutTag.putInt("Index", index + direction);
                scoutTag.putLong("PauseUntil", 0);
            }
            scoutTag.remove("Done");
            actor.getPersistentData().put(KEY, scoutTag);
            actor.getNavigation().stop();
            return;
        }
        if (scoutTag.getLong("PauseUntil") > now) {
            actor.getNavigation().stop();
            return;
        }
        Vec3 destination = scout == actor ? Vec3.atBottomCenterOf(waypoint)
                : scout.position().add((members.indexOf(actor) % 2 == 0 ? 2 : -2), 0,
                    -2 - members.indexOf(actor) / 2 * 2);
        if (actor.distanceToSqr(destination) < (scout == actor ? 4 : 9)) {
            actor.getNavigation().stop();
            return;
        }
        if (repath-- > 0 && !actor.getNavigation().isDone()) return;
        if (!Budget.path(level)) return;
        repath = 15;
        // Pathfinding remains bounded even when a route spans several chunks.
        Vec3 step = destination.subtract(actor.position());
        if (step.lengthSqr() > 16 * 16) destination = actor.position().add(step.normalize().scale(16));
        actor.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0);
    }

    private boolean horde(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(20),
                entity -> entity.isAlive() && entity != actor)) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
            if (mob instanceof net.minecraft.world.entity.monster.Zombie || id != null
                    && (id.getNamespace().contains("undead") || id.getPath().contains("walker")
                    || id.getPath().contains("runner"))) count++;
            if (count >= 6) return true;
        }
        return false;
    }
}
