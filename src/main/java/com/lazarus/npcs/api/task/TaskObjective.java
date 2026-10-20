package com.lazarus.npcs.api.task;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * Tracks one goal. Implementations must be stateless: all state lives in the context counters, so it is saved
 * and survives restarts. Built-in objectives are in {@link TaskObjectives}; integrators may implement their own.
 */
public interface TaskObjective {
    /** Whether the goal is met now. Checked once per second while the task is active. */
    boolean complete(TaskContext context);

    /** Short progress line for the journal, e.g. "Bandages: 0/2". */
    Component progress(TaskContext context);

    /** Once per second while active, for time- or position-based goals. */
    default void tick(TaskContext context) {}

    /** The task owner killed {@code victim}. */
    default void killed(TaskContext context, LivingEntity victim) {}

    /** Whether talking to the giver now hands something in (shows a "Hand over" option). */
    default boolean canDeliver(TaskContext context) { return false; }

    /**
     * Hand in at the giver. Must be all-or-nothing: check everything, then take. Return false (and change
     * nothing) when the player cannot hand in.
     */
    default boolean deliver(TaskContext context) { return false; }

    /** Final check and optional consumption before paying a READY task at its giver. Defaults to no extra check. */
    default boolean turnIn(TaskContext context) { return true; }
}
