package com.lazarus.npcs.api.ai;

import net.minecraft.core.BlockPos;

/** A daytime destination and its relative frequency. */
public record Activity(Kind kind, BlockPos at, int weight) {
    public enum Kind { WORK, IDLE, SOCIAL, LOOKOUT, PATROL, EAT }
    public Activity {
        if (kind == null || at == null || weight < 1 || weight > 100)
            throw new IllegalArgumentException("Invalid NPC activity");
    }
}
