package com.lazarus.npcs.api.dialogue;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A registered dialogue. When a trigger fires, definitions listing that trigger are asked in descending
 * priority; the first provider that returns a page is shown. Returning empty means "not for this
 * player/NPC/situation" (wrong encounter, faction, reputation, task state...).
 *
 * @param id       namespaced with the registering mod, e.g. {@code lazarusevents:rescue_plea}
 * @param triggers when this dialogue may be considered
 * @param priority higher is asked first; built-in task dialogues use 100
 * @param provider builds the page on the server thread; must be cheap and side-effect free
 */
public record DialogueDefinition(ResourceLocation id, Set<DialogueTrigger> triggers, int priority,
                                 Function<DialogueContext, Optional<DialoguePage>> provider) {}
