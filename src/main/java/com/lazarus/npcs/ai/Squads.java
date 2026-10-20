package com.lazarus.npcs.ai;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ai.Doctrine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Mob;

/** Deterministic membership and combat roles for scenario actors and nearby hostile groups. */
public final class Squads {
    public enum Role { LEADER, FLANK, ADVANCE, SUPPRESS }

    private Squads() {}

    public static List<Mob> scenario(Mob actor) {
        ScenarioAssignment assignment = NpcApi.assignment(actor).orElse(null);
        if (assignment == null) return List.of();
        // Both sides of a skirmish share one encounter id, so a squad is also limited to the actor's faction.
        String faction = NpcApi.identity(actor).map(identity -> identity.faction()).orElse("");
        List<Mob> result = actor.level().getEntitiesOfClass(Mob.class, actor.getBoundingBox().inflate(48),
                other -> other.isAlive() && NpcApi.identity(other).map(identity -> identity.faction())
                        .orElse("").equals(faction) && NpcApi.assignment(other).filter(current ->
                        current.owner().equals(assignment.owner())
                                && current.encounterId().equals(assignment.encounterId())).isPresent());
        if (actor.isAlive() && !result.contains(actor)) result.add(actor);
        return sorted(result);
    }

    /** The caller supplies its existing kind-scoped nearby group from HumanEncounters.group. */
    public static List<Mob> hostile(Mob actor, List<Mob> nearby) {
        List<Mob> result = new ArrayList<>();
        for (Mob other : nearby)
            if (other.isAlive() && other.level() == actor.level() && !result.contains(other)) result.add(other);
        if (actor.isAlive() && !result.contains(actor)) result.add(actor);
        return sorted(result);
    }

    private static List<Mob> sorted(List<Mob> members) {
        members.sort(Comparator.comparing(Mob::getUUID));
        return List.copyOf(members);
    }

    public static Role role(Mob actor, List<Mob> members, Doctrine doctrine, long gameTime) {
        int index = members.indexOf(actor);
        if (index < 0 || doctrine == null) return Role.SUPPRESS;
        if (index == 0) return Role.LEADER;
        int flankers = Math.min(members.size() - 1, (int) Math.round(members.size() * doctrine.flankShare()));
        if (doctrine.flankShare() > 0 && members.size() > 1) flankers = Math.max(1, flankers);
        if (index <= flankers) return Role.FLANK;
        if (doctrine.bounding()) return (index + gameTime / 60) % 2 == 0 ? Role.ADVANCE : Role.SUPPRESS;
        return doctrine.stance() == Doctrine.Stance.MOBILE ? Role.ADVANCE : Role.SUPPRESS;
    }
}
