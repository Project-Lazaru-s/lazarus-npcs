package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.event.NpcApiEvents.ScenarioEnded.Reason;
import com.lazarus.npcs.ai.Budget;
import com.lazarus.npcs.ai.TravelGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Scenario orders from other mods. The order is saved on the entity, revalidated when it loads, and run by one
 * goal at priority 0, so wandering (1 and up) always yields to it. It steps aside itself whenever combat, a
 * player's contract, surrender or a tactical retreat applies, which keeps the documented precedence.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcScenarios {
    static final String KEY = "LazarusNpcsScenario";
    private static final String PATROL = "LazarusNpcsPatrol";

    private NpcScenarios() {}

    static boolean supported(LivingEntity npc) {
        return NpcIdentity.role(npc) != null || HumanEncounters.kind(npc) != null;
    }

    // ---- Storage

    private static CompoundTag write(ScenarioAssignment a, long until) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Owner", a.owner().toString());
        tag.putUUID("Encounter", a.encounterId());
        tag.putString("Order", a.order().name());
        tag.putString("Dim", a.anchor().dimension().location().toString());
        tag.putLong("Anchor", a.anchor().pos().asLong());
        if (a.target() != null) tag.putUUID("Target", a.target());
        tag.putInt("Radius", a.radius());
        tag.putLong("Duration", a.durationTicks());
        tag.putInt("Priority", a.priority());
        tag.putLong("Until", until);
        return tag;
    }

    private static ScenarioAssignment read(CompoundTag tag) {
        ResourceKey<net.minecraft.world.level.Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("Dim")));
        return new ScenarioAssignment(new ResourceLocation(tag.getString("Owner")), tag.getUUID("Encounter"),
                ScenarioOrder.valueOf(tag.getString("Order")), GlobalPos.of(dim, BlockPos.of(tag.getLong("Anchor"))),
                tag.hasUUID("Target") ? tag.getUUID("Target") : null, tag.getInt("Radius"), tag.getLong("Duration"),
                tag.getInt("Priority"));
    }

    /** The unexpired order, if any. Does not clean up; the periodic check does. */
    static Optional<ScenarioAssignment> current(LivingEntity npc) {
        CompoundTag tag = npc.getPersistentData().getCompound(KEY);
        if (tag.isEmpty() || tag.getLong("Until") <= npc.level().getGameTime()) return Optional.empty();
        try {
            return Optional.of(read(tag));
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    // ---- API operations

    static ApiResult assign(Mob npc, ScenarioAssignment a) {
        if (!supported(npc)) return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "not a Lazarus human NPC");
        if (!npc.isAlive()) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "NPC is dead");
        String invalid = validate(npc, a);
        if (invalid != null) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, invalid);
        if (HumanSurrender.surrendered(npc)) return ApiResult.fail(ApiResult.Status.BUSY, "NPC has surrendered");
        if (NpcContracts.contract(npc) != null) return ApiResult.fail(ApiResult.Status.BUSY, "NPC is under a player's contract");
        Optional<ScenarioAssignment> existing = current(npc);
        boolean same = existing.filter(old -> old.owner().equals(a.owner()) && old.encounterId().equals(a.encounterId())).isPresent();
        if (existing.isPresent() && !same && existing.get().priority() >= a.priority())
            return ApiResult.fail(ApiResult.Status.BUSY, "NPC already has an order from " + existing.get().owner());
        if (existing.isPresent() && !same) {
            npc.getPersistentData().remove(PATROL);
            TravelGoal.clear(npc);
            ApiBackend.post(new NpcApiEvents.ScenarioEnded(npc, existing.get(), Reason.REPLACED));
        }
        npc.getPersistentData().put(KEY, write(a, npc.level().getGameTime() + a.durationTicks()));
        attach(npc);
        npc.getNavigation().stop();
        ApiBackend.post(new NpcApiEvents.ScenarioStarted(npc, a));
        return ApiResult.ok("order " + a.order() + " assigned");
    }

    static String validate(Mob npc, ScenarioAssignment a) {
        if (a == null || a.owner() == null || a.encounterId() == null || a.order() == null || a.anchor() == null)
            return "owner, encounterId, order and anchor are required";
        if (!ModList.get().isLoaded(a.owner().getNamespace())) return "owner namespace is not a loaded mod";
        if (a.anchor().dimension() != npc.level().dimension()) return "anchor is in another dimension";
        if (a.order().needsTarget() && a.target() == null) return a.order() + " needs a target";
        if (a.radius() < 1 || a.radius() > ScenarioAssignment.MAX_RADIUS) return "radius must be 1-" + ScenarioAssignment.MAX_RADIUS;
        if (a.durationTicks() < 20 || a.durationTicks() > ScenarioAssignment.MAX_DURATION) return "duration out of range";
        if (a.priority() < 0 || a.priority() > ScenarioAssignment.MAX_PRIORITY) return "priority must be 0-" + ScenarioAssignment.MAX_PRIORITY;
        return null;
    }

    static boolean clear(Mob npc, ResourceLocation owner, UUID encounterId) {
        CompoundTag tag = npc.getPersistentData().getCompound(KEY);
        if (tag.isEmpty() || !tag.getString("Owner").equals(String.valueOf(owner))
                || !tag.hasUUID("Encounter") || !tag.getUUID("Encounter").equals(encounterId)) return false;
        end(npc, Reason.CLEARED);
        return true;
    }

    static ApiResult setPatrolRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> points) {
        Optional<ScenarioAssignment> active = current(npc);
        if (active.isEmpty() || !active.get().owner().equals(owner) || !active.get().encounterId().equals(encounterId)
                || active.get().order() != ScenarioOrder.GUARD_AREA)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "matching guard-area order required");
        if (points == null || points.size() < 2 || points.size() > 8 || points.stream().anyMatch(p -> p == null
                || p.distSqr(active.get().anchor().pos()) > active.get().radius() * active.get().radius()))
            return ApiResult.fail(ApiResult.Status.LIMIT, "2-8 points inside the guard radius required");
        CompoundTag route = new CompoundTag();
        route.putString("Owner", owner.toString());
        route.putUUID("Encounter", encounterId);
        route.putLongArray("Points", points.stream().mapToLong(BlockPos::asLong).toArray());
        route.putInt("Index", 0);
        npc.getPersistentData().put(PATROL, route);
        return ApiResult.ok("patrol route assigned");
    }

    static ApiResult setTravelRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> points) {
        Optional<ScenarioAssignment> active = current(npc);
        if (active.isEmpty() || !active.get().owner().equals(owner) || !active.get().encounterId().equals(encounterId)
                || active.get().order() != ScenarioOrder.TRAVEL)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "matching travel order required");
        if (points == null || points.size() < 4 || points.size() > 8 || points.stream().anyMatch(p -> p == null
                || !npc.level().isLoaded(p) || Math.abs(p.getY() - npc.getBlockY()) > 16))
            return ApiResult.fail(ApiResult.Status.LIMIT, "4-8 loaded route points at reachable height required");
        TravelGoal.route(npc, owner, encounterId, points);
        return ApiResult.ok("travel route assigned");
    }

    private static CompoundTag patrol(Mob npc, ScenarioAssignment order) {
        CompoundTag route = npc.getPersistentData().getCompound(PATROL);
        return route.getString("Owner").equals(order.owner().toString()) && route.hasUUID("Encounter")
                && route.getUUID("Encounter").equals(order.encounterId()) ? route : new CompoundTag();
    }

    private static BlockPos patrolPoint(Mob mob, CompoundTag route, long packed) {
        BlockPos point = BlockPos.of(packed);
        BlockPos varied = point.offset(route.getInt("OffsetX"), 0, route.getInt("OffsetZ"));
        if (varied.equals(point) || !mob.level().getBlockState(varied).getCollisionShape(mob.level(), varied).isEmpty()
                || !mob.level().getBlockState(varied.above()).getCollisionShape(mob.level(), varied.above()).isEmpty()
                || !mob.level().getBlockState(varied.below()).isCollisionShapeFullBlock(mob.level(), varied.below())
                || NpcHazards.pathType(mob.level(), varied, mob,
                        net.minecraft.world.level.pathfinder.BlockPathTypes.WALKABLE)
                        != net.minecraft.world.level.pathfinder.BlockPathTypes.WALKABLE) return point;
        return varied;
    }

    private static void end(LivingEntity npc, Reason reason) {
        CompoundTag tag = npc.getPersistentData().getCompound(KEY);
        npc.getPersistentData().remove(KEY);
        npc.getPersistentData().remove(PATROL);
        if (npc instanceof Mob mob) TravelGoal.clear(mob);
        if (npc instanceof Mob mob) mob.getNavigation().stop();
        try {
            ApiBackend.post(new NpcApiEvents.ScenarioEnded(npc, read(tag), reason));
        } catch (RuntimeException broken) {
            LazarusNpcs.LOGGER.warn("[api] dropped unreadable scenario order on {}", npc.getUUID());
        }
    }

    /** Ends expired orders and orders whose owner mod is gone. Returns the live order. */
    private static Optional<ScenarioAssignment> revalidate(LivingEntity npc) {
        CompoundTag tag = npc.getPersistentData().getCompound(KEY);
        if (tag.isEmpty()) return Optional.empty();
        if (tag.getLong("Until") <= npc.level().getGameTime()) {
            end(npc, Reason.EXPIRED);
            return Optional.empty();
        }
        String owner = tag.getString("Owner");
        if (!ModList.get().isLoaded(owner.contains(":") ? owner.substring(0, owner.indexOf(':')) : owner)) {
            end(npc, Reason.OWNER_MISSING);
            return Optional.empty();
        }
        return current(npc);
    }

    // ---- Lifecycle

    private static void attach(Mob mob) {
        TravelGoal.attach(mob);
        for (WrappedGoal goal : mob.goalSelector.getAvailableGoals()) if (goal.getGoal() instanceof ScenarioGoal) return;
        mob.goalSelector.addGoal(0, new ScenarioGoal(mob));
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)
                || !mob.getPersistentData().contains(KEY)) return;
        if (revalidate(mob).isPresent()) attach(mob);
    }

    @SubscribeEvent
    public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || mob.tickCount % 20 != 11
                || !mob.getPersistentData().contains(KEY)) return;
        long start = System.nanoTime();
        revalidate(mob).ifPresent(a -> {
            if (NpcContracts.contract(mob) == null && !HumanSurrender.surrendered(mob)) engage(mob, a);
        });
        NpcPerf.record("scenario orders", System.nanoTime() - start);
    }

    /** Keeps fights inside the order's area and picks up threats near the post or the protected ally. */
    private static void engage(Mob mob, ScenarioAssignment a) {
        Vec3 center = center(mob, a);
        if (center == null) return;
        LivingEntity target = mob.getTarget();
        double leash = a.radius() + 8;
        if (target != null) {
            if (!target.isAlive() || target.position().distanceTo(center) > leash) mob.setTarget(null);
            return;
        }
        if (a.order() == ScenarioOrder.WAIT || a.order() == ScenarioOrder.RETREAT || a.order() == ScenarioOrder.FOLLOW_PLAYER) return;
        if (a.order() == ScenarioOrder.TRAVEL) return;
        double reach = a.order() == ScenarioOrder.GUARD_AREA ? a.radius() + 2 : 6;
        mob.level().getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(reach, 6, reach),
                        e -> e.isAlive() && e != mob && NpcContracts.isThreat(e) && !NpcGuns.sameSide(mob, e)
                                && e.position().distanceTo(center) <= reach)
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(center))).ifPresent(mob::setTarget);
    }

    /** The point an order revolves around: the anchor, or the followed/defended entity when it is loaded. */
    static Vec3 center(Mob mob, ScenarioAssignment a) {
        // A travelling squad fights around the waypoint it is heading for; the anchor is only where the route began.
        if (a.order() == ScenarioOrder.TRAVEL) {
            BlockPos waypoint = TravelGoal.waypoint(mob);
            return waypoint == null ? mob.position() : Vec3.atBottomCenterOf(waypoint);
        }
        if (a.target() != null && (a.order() == ScenarioOrder.DEFEND_ALLY || a.order() == ScenarioOrder.FOLLOW_PLAYER)) {
            Entity target = ((ServerLevel) mob.level()).getEntity(a.target());
            return target == null || !target.isAlive() ? null : target.position();
        }
        return Vec3.atBottomCenterOf(a.anchor().pos());
    }

    /**
     * Keeps the NPC near its order: targets far from the post (or the followed/defended entity) are refused unless
     * they attacked the NPC. Players are left to NpcHostility.
     */
    @SubscribeEvent
    public static void target(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || event.getNewTarget() == null
                || event.getNewTarget() instanceof Player || !mob.getPersistentData().contains(KEY)) return;
        Optional<ScenarioAssignment> order = current(mob);
        if (order.isEmpty() || NpcContracts.contract(mob) != null) return;
        if (NpcGuns.sameSide(mob, event.getNewTarget())) {
            event.setNewTarget(null);
            return;
        }
        // Mid-fight with a rival faction, a zombie only takes over once it is within striking distance or attacks.
        LivingEntity current = mob.getTarget();
        if (current != null && current.isAlive() && rival(mob, current) && !rival(mob, event.getNewTarget())
                && event.getNewTarget() != mob.getLastHurtByMob() && event.getNewTarget().distanceToSqr(mob) > 16) {
            event.setCanceled(true);
            return;
        }
        if (event.getNewTarget() == mob.getLastHurtByMob()) return;
        Vec3 center = center(mob, order.get());
        double reach = switch (order.get().order()) {
            case GUARD_AREA -> order.get().radius() + 8;
            case WAIT, RETREAT -> 8;
            default -> 16;
        };
        if (center == null || event.getNewTarget().position().distanceTo(center) > reach) event.setNewTarget(null);
    }

    static boolean rival(LivingEntity mob, LivingEntity other) {
        String faction = NpcIdentity.faction(mob), otherFaction = NpcIdentity.faction(other);
        return faction != null && otherFaction != null && com.lazarus.npcs.api.NpcFactions.rivals(faction, otherFaction);
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (victim.getPersistentData().contains(KEY))
            current(victim).ifPresent(a -> ApiBackend.post(new NpcApiEvents.ScenarioActorHurt(victim, a, event.getSource(), event.getAmount())));
        // Defenders and followers fight whoever hurts the entity they protect (players are left to NpcHostility).
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker instanceof Player || attacker == victim
                || !(victim instanceof Player || supported(victim))) return;
        for (Mob defender : victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(24),
                m -> m != attacker && m.getPersistentData().contains(KEY))) {
            Optional<ScenarioAssignment> a = current(defender);
            if (a.isEmpty() || !victim.getUUID().equals(a.get().target()) || defender.getTarget() != null
                    || NpcContracts.contract(defender) != null || NpcGuns.sameSide(defender, attacker)) continue;
            defender.setTarget(attacker);
        }
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        LivingEntity npc = event.getEntity();
        if (npc.level().isClientSide() || !npc.getPersistentData().contains(KEY)) return;
        Optional<ScenarioAssignment> a = current(npc);
        a.ifPresent(order -> ApiBackend.post(new NpcApiEvents.ScenarioActorDied(npc, order, event.getSource())));
        if (a.isPresent()) end(npc, Reason.DIED);
        else npc.getPersistentData().remove(KEY);
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide() || !(entity instanceof LivingEntity npc) || !npc.getPersistentData().contains(KEY)) return;
        var reason = entity.getRemovalReason();
        if (reason == Entity.RemovalReason.DISCARDED || reason == Entity.RemovalReason.CHANGED_DIMENSION) end(npc, Reason.REMOVED);
    }

    // ---- Movement

    /** One goal per NPC: walks to the post, holds it, or follows. Idle (false) whenever combat or a contract applies. */
    static final class ScenarioGoal extends Goal {
        private final Mob mob;
        private int repath;

        ScenarioGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        private Optional<ScenarioAssignment> active() {
            if (mob.getTarget() != null || NpcContracts.contract(mob) != null || HumanSurrender.surrendered(mob)
                    || HumanTactics.ordering(mob)) return Optional.empty();
            return current(mob);
        }

        @Override
        public boolean canUse() {
            return active().filter(a -> switch (a.order()) {
                case TRAVEL -> false;
                case FOLLOW_PLAYER, DEFEND_ALLY -> {
                    Vec3 center = center(mob, a);
                    yield center != null && mob.position().distanceTo(center) > 5;
                }
                default -> true;
            }).isPresent();
        }

        @Override
        public boolean canContinueToUse() {
            return active().filter(a -> switch (a.order()) {
                case TRAVEL -> false;
                case FOLLOW_PLAYER, DEFEND_ALLY -> {
                    Vec3 center = center(mob, a);
                    yield center != null && mob.position().distanceTo(center) > 3;
                }
                default -> true;
            }).isPresent();
        }

        @Override public void start() { repath = 0; }

        @Override public void stop() { mob.getNavigation().stop(); }

        @Override
        public void tick() {
            Optional<ScenarioAssignment> order = current(mob);
            if (order.isEmpty()) return;
            ScenarioAssignment a = order.get();
            Vec3 center = center(mob, a);
            CompoundTag route = a.order() == ScenarioOrder.GUARD_AREA ? patrol(mob, a) : new CompoundTag();
            long[] points = route.getLongArray("Points");
            if (points.length >= 2) {
                int index = Math.floorMod(route.getInt("Index"), points.length);
                center = Vec3.atBottomCenterOf(patrolPoint(mob, route, points[index]));
                if (mob.position().distanceToSqr(center) <= 9) {
                    long now = mob.level().getGameTime();
                    if (route.getLong("WaitUntil") == 0) route.putLong("WaitUntil", now + 40);
                    else if (now >= route.getLong("WaitUntil")) {
                        route.putInt("Index", (index + 1) % points.length);
                        route.putLong("WaitUntil", 0);
                        route.putInt("OffsetX", mob.getRandom().nextInt(3) - 1);
                        route.putInt("OffsetZ", mob.getRandom().nextInt(3) - 1);
                        repath = 0;
                        center = Vec3.atBottomCenterOf(patrolPoint(mob, route, points[(index + 1) % points.length]));
                    }
                    mob.getPersistentData().put(PATROL, route);
                } else if (route.getLong("WaitUntil") != 0) {
                    route.putLong("WaitUntil", 0);
                    mob.getPersistentData().put(PATROL, route);
                }
            }
            if (center == null) {
                mob.getNavigation().stop();
                return;
            }
            double stay = switch (a.order()) {
                case GUARD_AREA -> 3;
                case WAIT, RETREAT -> 1.5;
                default -> 3;
            };
            if (mob.position().distanceTo(center) <= stay) {
                mob.getNavigation().stop();
                return;
            }
            if (repath-- > 0) return;
            if (!Budget.path((ServerLevel) mob.level())) { repath = 1; return; }
            repath = 15;
            double speed = a.order() == ScenarioOrder.RETREAT ? 1.25 : mob.position().distanceTo(center) > 12 ? 1.15 : 1.0;
            HumanTactics.walk(mob, center, speed);
        }
    }
}
