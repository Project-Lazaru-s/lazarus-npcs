package com.lazarus.npcs.ai;

import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;

/** Transient firing cues for movement AI; gun ammunition and damage remain with NpcGuns. */
public final class AiFireState {
    private static final WeakHashMap<Mob, State> STATES = new WeakHashMap<>();
    private static final class State { int bursts; long reloadUntil; }

    private AiFireState() {}

    public static void burst(Mob actor) { STATES.computeIfAbsent(actor, ignored -> new State()).bursts++; }
    public static void reload(Mob actor, long until) {
        STATES.computeIfAbsent(actor, ignored -> new State()).reloadUntil = until;
    }
    public static int bursts(Mob actor) {
        State state = STATES.get(actor);
        return state == null ? 0 : state.bursts;
    }
    public static boolean reloading(Mob actor) {
        State state = STATES.get(actor);
        return state != null && actor.level().getGameTime() < state.reloadUntil;
    }
}
