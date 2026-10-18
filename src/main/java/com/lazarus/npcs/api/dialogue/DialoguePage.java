package com.lazarus.npcs.api.dialogue;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * What the NPC says and the answers offered. Use translatable components for text that should be translated.
 *
 * @param line    the NPC's line (max {@link #MAX_LINE} characters once resolved)
 * @param options 1 to {@link #MAX_OPTIONS} answers; the screen always adds its own "Back" button
 */
public record DialoguePage(Component line, List<DialogueOption> options) {
    public static final int MAX_OPTIONS = 4;
    public static final int MAX_LINE = 300;
}
