package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcAiHooks;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.ai.Doctrine;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side movement for human gunfights. The firing goal owns aiming and ammunition. Every state ends in either a
 * reachable destination or holding a firing position with line of sight; a target that cannot be seen is approached,
 * and one that stays out of sight for ten seconds is dropped, so an actor never stands frozen with a stale target.
 */
public final class CombatMoveGoal extends Goal {
    public enum State { TAKE_COVER, ENGAGE, REPOSITION, BOUND, FLANK, SUPPRESS, RELOAD, FALL_BACK, MELEE_RUSH }
    private static final int GIVE_UP_UNSEEN = 200;
    private final Mob actor;
    private State state = State.ENGAGE;
    private Vec3 destination;
    private int nextDecision, nextPath, observedBursts, lastRepositionBurst, flankSign = 1;
    private int meleeCooldown, unseen;

    private CombatMoveGoal(Mob actor) {
        this.actor = actor;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Mob actor) {
        for (WrappedGoal entry : actor.goalSelector.getAvailableGoals())
            if (entry.getGoal() instanceof CombatMoveGoal) return;
        actor.goalSelector.addGoal(-2, new CombatMoveGoal(actor));
    }

    public State state() { return state; }

    private boolean active() {
        LivingEntity target = actor.getTarget();
        return actor.level() instanceof ServerLevel && target != null && target.isAlive()
                && target.level() == actor.level() && !NpcAiHooks.busy(actor)
                && NpcApi.identity(actor).isPresent() && Lod.band(actor) != Lod.Band.FAR;
    }

    @Override public boolean canUse() { return active(); }
    @Override public boolean canContinueToUse() { return active(); }
    @Override public void start() { nextDecision = 10; unseen = 0; }
    @Override public void stop() { destination = null; unseen = 0; PointPicker.release(actor); actor.getNavigation().stop(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        LivingEntity target = actor.getTarget();
        if (target == null) return;
        unseen = actor.getSensing().hasLineOfSight(target) ? 0 : unseen + 1;
        if (unseen > GIVE_UP_UNSEEN) {
            actor.setTarget(null); // lost contact; the next threat check picks a fresh target
            return;
        }
        if (meleeCooldown > 0) meleeCooldown--;
        // Unarmed actors rush; armed ones strike with the weapon at arm's length instead of standing idle.
        double reach = state == State.MELEE_RUSH ? 6.25 : 4.84;
        if (meleeCooldown == 0 && actor.distanceToSqr(target) < reach && unseen == 0
                && actor.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            actor.swing(InteractionHand.MAIN_HAND);
            actor.doHurtTarget(target);
            meleeCooldown = 20;
        }
        if (++nextDecision >= 10) { nextDecision = 0; decide(target); }
        if (destination != null && ++nextPath >= 12) {
            nextPath = 0;
            if (actor.distanceToSqr(destination) < 3) { destination = null; actor.getNavigation().stop(); }
            else if (Budget.path((ServerLevel) actor.level()) && !actor.getNavigation().moveTo(destination.x,
                    destination.y, destination.z, state == State.FALL_BACK || state == State.MELEE_RUSH ? 1.15 : 1.0))
                destination = null;
        }
    }

    private void decide(LivingEntity target) {
        Doctrine doctrine = CombatProfiles.doctrine(actor);
        double distance = actor.distanceTo(target);
        double gunRange = NpcAiHooks.gunRange(actor);
        if (gunRange <= 0) { state = State.MELEE_RUSH; go(target.position()); return; }
        // A doctrine range beyond the gun's reach would leave the actor holding a spot it cannot shoot from.
        double range = Math.max(4, Math.min(doctrine.preferredRange(), gunRange - 2));
        boolean sees = actor.getSensing().hasLineOfSight(target);
        List<Mob> squad = squad();
        Squads.Role role = Squads.role(actor, squad, doctrine, actor.level().getGameTime());

        if (AiFireState.reloading(actor)) state = State.RELOAD;
        else if (actor.getHealth() / actor.getMaxHealth() < doctrine.retreatHealth()) state = State.FALL_BACK;
        else if (!sees) state = State.REPOSITION;
        else if (distance < 3) state = State.REPOSITION;
        else if (distance > range) state = State.BOUND;
        else if (role == Squads.Role.FLANK) state = State.FLANK;
        else if (role == Squads.Role.ADVANCE) state = State.BOUND;
        else if (role == Squads.Role.SUPPRESS) state = State.SUPPRESS;
        else state = State.ENGAGE;

        Vec3 away = flat(actor.position().subtract(target.position()));
        Vec3 side = new Vec3(-away.z * flankSign, 0, away.x * flankSign);
        switch (state) {
            case RELOAD, FALL_BACK -> {
                Vec3 cover = CoverFinder.find(actor, List.of(target), squad, doctrine);
                if (cover != null) { go(cover); return; }
                if (moveCandidate(actor.position().add(away.scale(state == State.FALL_BACK ? 7 : 4)), null)) {
                    if (state == State.FALL_BACK) Callouts.say(actor, "Falling back!");
                    return;
                }
                hold();
            }
            case REPOSITION -> {
                if (sees) { // too close: open the distance a little and keep shooting
                    if (!moveCandidate(actor.position().add(away.scale(4)), target)) hold();
                    return;
                }
                // Out of sight: step around the obstacle toward the target, or walk straight at it.
                Vec3 around = actor.position().add(side.scale(5)).subtract(away.scale(3));
                if (moveCandidate(around, target)) return;
                flankSign = -flankSign;
                go(target.position());
            }
            case BOUND -> {
                double stop = distance > range ? range * 0.8 : Math.max(4, range * 0.6);
                if (moveCandidate(target.position().add(away.scale(stop)), target)
                        || moveCandidate(target.position().add(away.scale(stop)), null)) {
                    if (distance <= range) Callouts.say(actor, "Moving up!");
                    return;
                }
                if (distance > range) go(target.position()); else hold();
            }
            case FLANK -> {
                if (moveCandidate(actor.position().add(side.scale(7)).subtract(away.scale(3)), target)) {
                    Callouts.say(actor, "Flanking!");
                    return;
                }
                flankSign = -flankSign;
                hold();
            }
            default -> { // ENGAGE and SUPPRESS hold a firing position, shifting sideways after a few bursts
                int bursts = AiFireState.bursts(actor);
                if (bursts != observedBursts) {
                    observedBursts = bursts;
                    if (bursts - lastRepositionBurst >= doctrine.repositionAfterBursts()) {
                        lastRepositionBurst = bursts;
                        flankSign = actor.getRandom().nextBoolean() ? 1 : -1;
                        if (moveCandidate(actor.position().add(side.scale(4)), target)) return;
                    }
                }
                if (destination == null || actor.distanceToSqr(destination) < 3) hold();
            }
        }
    }

    private List<Mob> squad() {
        List<Mob> squad = Squads.scenario(actor);
        if (squad.isEmpty()) squad = actor.level().getEntitiesOfClass(Mob.class,
                actor.getBoundingBox().inflate(16), other -> other != actor && other.isAlive()
                        && NpcApi.identity(other).map(i -> NpcApi.identity(actor)
                                .map(self -> self.faction().equals(i.faction()) && self.role().equals(i.role()))
                                .orElse(false)).orElse(false));
        if (squad.contains(actor)) return squad;
        java.util.ArrayList<Mob> members = new java.util.ArrayList<>(squad);
        members.add(actor);
        members.sort(java.util.Comparator.comparing(Mob::getUUID));
        return List.copyOf(members);
    }

    private static Vec3 flat(Vec3 direction) {
        Vec3 horizontal = new Vec3(direction.x, 0, direction.z);
        return horizontal.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : horizontal.normalize();
    }

    private void go(Vec3 point) {
        if (destination == null || destination.distanceToSqr(point) > 4) nextPath = 12; // path on the next tick
        destination = point;
    }

    private void hold() {
        if (destination != null || !actor.getNavigation().isDone()) actor.getNavigation().stop();
        destination = null;
    }

    /** Reserves a standable point near the candidate, preferring one with a line of fire to {@code mustSee}. */
    private boolean moveCandidate(Vec3 candidate, LivingEntity mustSee) {
        var assignment = NpcApi.assignment(actor);
        // Travel orders move with the squad; other orders keep fights near their area.
        if (assignment.isPresent() && assignment.get().order() != ScenarioOrder.TRAVEL
                && candidate.distanceToSqr(Vec3.atCenterOf(assignment.get().anchor().pos()))
                > Math.pow(Math.max(8, assignment.get().radius()) + 8, 2)) return false;
        BlockPos center = BlockPos.containing(candidate);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            for (int dy : new int[] {0, 1, -1}) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (mustSee != null && (!PointPicker.standable(actor, pos) || !Exposure.visible(mustSee,
                        Vec3.atBottomCenterOf(pos).add(0, actor.getEyeHeight(), 0)))) continue;
                if (PointPicker.reserve(actor, pos, 60)) { go(Vec3.atBottomCenterOf(pos)); return true; }
            }
        return false;
    }
}
