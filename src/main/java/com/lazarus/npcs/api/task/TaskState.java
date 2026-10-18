package com.lazarus.npcs.api.task;

/** Lifecycle of an encounter task instance. Only OFFERED, ACTIVE and READY can still change. */
public enum TaskState {
    /** Offered to the player, waiting for Accept or Decline (expires after two minutes). */
    OFFERED,
    /** Accepted; the objective is being tracked. */
    ACTIVE,
    /** Objective done; the player must report back to the giver (definitions with {@code turnIn}). */
    READY,
    /** Finished successfully; rewards were paid exactly once. */
    COMPLETED,
    /** Failed by policy (giver died, player died) or by the integrator. */
    FAILED,
    /** Deadline passed. */
    EXPIRED,
    /** Declined or abandoned by the player. */
    ABANDONED;

    public boolean open() { return this == OFFERED || this == ACTIVE || this == READY; }
}
