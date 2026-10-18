package com.lazarus.npcs.api.dialogue;

/**
 * When a dialogue may be shown. Lazarus NPCs itself opens {@link #INTERACT} (empty-hand interaction) and
 * {@link #TASK_OFFER} (when a task is offered); the others are opened by the integrator through
 * {@code NpcApi.openDialogue}.
 */
public enum DialogueTrigger { GREETING, INTERACT, TASK_OFFER, TASK_PROGRESS, TASK_COMPLETE, THREAT, FAREWELL }
