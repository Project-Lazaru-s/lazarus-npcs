package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcPerf;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/** Per-level, per-tick limits for expensive NPC decisions. Called on the server thread. */
public final class Budget {
    private static final WeakHashMap<ServerLevel, State> STATES = new WeakHashMap<>();
    private static final int COVER = 4, PATH = 6, CONTAINER = 2;

    private Budget() {}

    private static final class State {
        long tick = Long.MIN_VALUE;
        int cover, path, container;
    }

    private static boolean permit(ServerLevel level, int kind, int limit) {
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        long now = level.getGameTime();
        if (state.tick != now) {
            state.tick = now;
            state.cover = state.path = state.container = 0;
        }
        int used = switch (kind) {
            case 0 -> ++state.cover;
            case 1 -> ++state.path;
            default -> ++state.container;
        };
        if (used <= limit) return true;
        NpcPerf.record("AI budget denied " + switch (kind) {
            case 0 -> "cover";
            case 1 -> "path";
            default -> "container";
        }, 0);
        return false;
    }

    public static boolean cover(ServerLevel level) { return permit(level, 0, COVER); }
    public static boolean path(ServerLevel level) { return permit(level, 1, PATH); }
    public static boolean container(ServerLevel level) { return permit(level, 2, CONTAINER); }
}
