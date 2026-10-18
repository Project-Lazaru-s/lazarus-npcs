package com.lazarus.npcs.api;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A temporary order owned by one encounter.
 *
 * @param owner         id of the integrating mod and scenario, e.g. {@code lazarusevents:rescue}; its namespace
 *                      must be a loaded mod, otherwise the order is dropped on the next check
 * @param encounterId   the integrator's encounter id; only the same owner and encounter can replace or clear it
 * @param order         behaviour
 * @param anchor        dimension and position the order refers to (post, waiting spot, safe point)
 * @param target        entity to follow or defend; required for {@link ScenarioOrder#FOLLOW_PLAYER} and
 *                      {@link ScenarioOrder#DEFEND_ALLY}
 * @param radius        1 to {@link #MAX_RADIUS} blocks
 * @param durationTicks 20 to {@link #MAX_DURATION} ticks of world time
 * @param priority      0 to {@link #MAX_PRIORITY}; a higher priority from another encounter can replace a lower one
 */
public record ScenarioAssignment(ResourceLocation owner, UUID encounterId, ScenarioOrder order, GlobalPos anchor,
                                 @Nullable UUID target, int radius, long durationTicks, int priority) {
    public static final int MAX_RADIUS = 32;
    public static final long MAX_DURATION = 168_000L;
    public static final int MAX_PRIORITY = 9;

    /** Shortcut for orders without a target. */
    public static ScenarioAssignment at(ResourceLocation owner, UUID encounterId, ScenarioOrder order, GlobalPos anchor,
                                        int radius, long durationTicks) {
        return new ScenarioAssignment(owner, encounterId, order, anchor, null, radius, durationTicks, 0);
    }

    public ScenarioAssignment withTarget(UUID newTarget) {
        return new ScenarioAssignment(owner, encounterId, order, anchor, newTarget, radius, durationTicks, priority);
    }
}
