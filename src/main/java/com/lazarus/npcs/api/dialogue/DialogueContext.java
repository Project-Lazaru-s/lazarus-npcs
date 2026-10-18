package com.lazarus.npcs.api.dialogue;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Everything a dialogue provider may look at. Built on the server thread for one player and one NPC.
 *
 * @param encounterId the NPC's current scenario encounter, if it has one
 * @param taskId      the task instance the dialogue is about ({@code TASK_*} triggers), otherwise null
 */
public record DialogueContext(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger,
                              @Nullable UUID encounterId, @Nullable UUID taskId) {}
