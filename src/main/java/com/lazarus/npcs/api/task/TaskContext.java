package com.lazarus.npcs.api.task;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/** What an objective can read and change while evaluating one task instance on the server thread. */
public interface TaskContext {
    ServerPlayer player();

    UUID instanceId();

    UUID encounterId();

    /** The NPC that gave the task, when it is loaded in the player's dimension. */
    Optional<LivingEntity> giver();

    /** Destination passed to {@code offerTask}, if any. */
    Optional<GlobalPos> destination();

    /** World ticks since the player accepted. */
    long elapsedTicks();

    int counter(String key);

    /** Adds to a named counter (kept in the instance, saved with the player). Returns the new value. */
    int addCounter(String key, int amount);
}
