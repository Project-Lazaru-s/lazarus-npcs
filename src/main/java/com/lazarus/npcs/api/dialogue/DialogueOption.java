package com.lazarus.npcs.api.dialogue;

import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * One answer the player can pick.
 *
 * @param id     stable id within the page (lowercase, max 32 characters); the client only ever sends this back
 *               by position, and the server checks the option still exists before running it
 * @param label  button text (keep under about 30 characters)
 * @param action consequence, run once on the server thread after full revalidation. It may call other API
 *               methods (offer a task, assign an order, adjust reputation, open another dialogue). Charging
 *               items must be all-or-nothing: check first, then take.
 */
public record DialogueOption(String id, Component label, Consumer<DialogueContext> action) {}
