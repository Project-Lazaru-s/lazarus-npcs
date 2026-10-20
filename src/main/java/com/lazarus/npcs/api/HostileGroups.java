package com.lazarus.npcs.api;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Names of the hostile groups in the world's lore (API v6). Looter gangs hold 512 x 512 block territories chosen
 * from the world seed, so the same streets always belong to the same gang; cannibals all belong to one cult.
 */
public final class HostileGroups {
    public static final List<String> GANGS = List.of("Rust Dogs", "Lantern Boys", "Crowbar Saints", "Red Tape",
            "Gutter Kings", "Static", "Tin Men", "Black Pine");
    public static final String CULT = "The Hollow";
    public static final int TERRITORY = 512;

    private HostileGroups() {}

    public static String gang(long seed, int x, int z) {
        long h = seed ^ Math.floorDiv(x, TERRITORY) * 0x9E3779B97F4A7C15L ^ Math.floorDiv(z, TERRITORY) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return GANGS.get((int) Math.floorMod(h, (long) GANGS.size()));
    }

    public static String gang(ServerLevel level, BlockPos pos) { return gang(level.getSeed(), pos.getX(), pos.getZ()); }
}
