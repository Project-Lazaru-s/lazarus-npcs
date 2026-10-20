package com.lazarus.npcs.ai;

import com.lazarus.npcs.LazarusNpcs;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ai.Routine;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Thirty-second shared alarm; any attacked resident wakes the base. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class BaseAlert {
    private record Alarm(long until, UUID threat) {}
    private static final Map<UUID, Alarm> ALARMS = new HashMap<>();
    private BaseAlert() {}

    public static boolean active(Mob actor) {
        Routine routine = RoutineGoal.routine(actor);
        if (routine == null) return false;
        Alarm alarm = ALARMS.get(routine.baseId());
        if (alarm == null) return false;
        if (alarm.until <= actor.level().getGameTime()) { ALARMS.remove(routine.baseId()); return false; }
        return true;
    }

    public static void raise(Mob actor, LivingEntity threat) {
        Routine routine = RoutineGoal.routine(actor);
        if (routine == null || threat == null || !threat.isAlive()) return;
        boolean newAlarm = !active(actor);
        ALARMS.put(routine.baseId(), new Alarm(actor.level().getGameTime() + 600, threat.getUUID()));
        if (newAlarm) actor.level().getEntitiesOfClass(Mob.class, actor.getBoundingBox().inflate(36),
                other -> RoutineGoal.sameBase(actor, other) && NpcApi.profession(other)
                        .map(id -> id.getPath().equals("leader")).orElse(false))
                .stream().findFirst().ifPresent(leader -> Callouts.say(leader, "Alarm! Defend the post!"));
    }

    public static LivingEntity threat(Mob actor) {
        Routine routine = RoutineGoal.routine(actor);
        if (routine == null || !active(actor) || !(actor.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
        Entity entity = level.getEntity(ALARMS.get(routine.baseId()).threat);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    public static void watch(Mob actor) {
        if (actor.isNoAi() || actor.tickCount % 40 != 0 || active(actor)) return;
        actor.level().getEntitiesOfClass(Mob.class, actor.getBoundingBox().inflate(12),
                mob -> mob != actor && mob.isAlive() && !RoutineGoal.sameBase(actor, mob)
                        && (mob instanceof Monster || mob.getTarget() != null
                        && RoutineGoal.sameBase(actor, mob.getTarget()) || NpcApi.identity(mob)
                        .map(id -> "hostile".equals(id.faction())).orElse(false)))
                .stream().findFirst().ifPresent(threat -> raise(actor, threat));
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Mob actor) || actor.level().isClientSide()) return;
        Entity source = event.getSource().getEntity();
        if (source instanceof LivingEntity attacker && !RoutineGoal.sameBase(actor, attacker)) raise(actor, attacker);
    }

    @SubscribeEvent
    public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob actor) || actor.level().isClientSide()
                || actor.tickCount % 20 != 0 || RoutineGoal.routine(actor) == null) return;
        watch(actor);
        if (!active(actor)) return;
        LivingEntity enemy = threat(actor);
        if (RoutineGoal.routine(actor).night() == com.lazarus.npcs.api.ai.NightDuty.WATCH) {
            if (enemy != null && actor.distanceToSqr(enemy) <= 28 * 28) actor.setTarget(enemy);
        } else if (actor.getTarget() != null) actor.setTarget(null);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { ALARMS.clear(); }
}
