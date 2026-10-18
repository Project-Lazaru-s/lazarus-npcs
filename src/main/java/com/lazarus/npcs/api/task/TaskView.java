package com.lazarus.npcs.api.task;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Read-only snapshot of a player's encounter task. */
public record TaskView(UUID instanceId, ResourceLocation taskId, UUID encounterId, UUID giverId, TaskState state,
                       long acceptedAt, long deadline, boolean definitionLoaded) {}
