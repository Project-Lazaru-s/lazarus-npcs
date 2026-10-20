package com.lazarus.npcs.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Distance bands for optional AI work; ordinary orders can still run outside FULL. */
public final class Lod {
    public enum Band { FULL, BASIC, FAR }

    private Lod() {}

    public static Band band(Mob npc) {
        if (!(npc.level() instanceof ServerLevel level)) return Band.FAR;
        double nearest = level.players().stream().mapToDouble(player -> player.distanceToSqr(npc)).min()
                .orElse(Double.POSITIVE_INFINITY);
        return nearest <= 64 * 64 ? Band.FULL : nearest <= 128 * 128 ? Band.BASIC : Band.FAR;
    }
}
