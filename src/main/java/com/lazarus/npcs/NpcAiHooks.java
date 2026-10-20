package com.lazarus.npcs;

import net.minecraft.world.entity.Mob;
import com.lazarus.npcs.ai.RoutineGoal;

/** Internal bridge for the AI package; scenario and contract internals stay package private. */
public final class NpcAiHooks {
    private NpcAiHooks() {}

    public static boolean busy(Mob mob) {
        return HumanSurrender.surrendered(mob) || NpcContracts.contract(mob) != null;
    }

    public static boolean canScavenge(Mob mob) {
        if (busy(mob) || mob.getTarget() != null || NpcScenarios.current(mob).isPresent()
                || NpcProfessions.base(mob) != null || RoutineGoal.routine(mob) != null) return false;
        String role = NpcIdentity.role(mob);
        String kind = HumanEncounters.kind(mob);
        return "survivor".equals(role) || "hunter".equals(role)
                || "looter".equals(kind) || "soldier".equals(kind);
    }

    /** Effective firing range of the held gun, or 0 when unarmed. */
    public static double gunRange(Mob mob) { return NpcGuns.armed(mob) ? NpcGuns.range(mob.getMainHandItem()) : 0; }

    /** Lazarus human NPCs path over ladders; zombies and other mobs keep vanilla pathing. */
    public static boolean climbsLadders(Mob mob) { return NpcScenarios.supported(mob); }

    public static boolean infected(net.minecraft.world.entity.LivingEntity entity) {
        return NpcServices.isInfected(entity);
    }

    public static boolean scavengingEnabled() { return NpcConfig.SCAVENGING_ENABLED.get(); }
    public static int scavengeRadius() { return NpcConfig.SCAVENGING_RADIUS.get(); }
    public static int scavengeInterval() { return NpcConfig.SCAVENGING_INTERVAL_SECONDS.get() * 20; }
    public static int packLimit() { return NpcConfig.SCAVENGING_MAX_PACK_STACKS.get(); }

    public static boolean peacefulScavenger(Mob mob) {
        return "survivor".equals(NpcIdentity.role(mob)) || "hunter".equals(NpcIdentity.role(mob));
    }
}
