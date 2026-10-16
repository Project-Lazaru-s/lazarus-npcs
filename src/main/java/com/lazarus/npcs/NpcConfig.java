package com.lazarus.npcs;

import net.minecraftforge.common.ForgeConfigSpec;

final class NpcConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue VOICE_VOLUME;
    static final ForgeConfigSpec.IntValue DIALOGUE_COOLDOWN;
    static final ForgeConfigSpec.BooleanValue MUTE_VILLAGERS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("npc_audio");
        VOICE_VOLUME = builder.comment("Positional NPC voice volume, from silent to full volume.")
                .defineInRange("voiceVolume", 0.55, 0.0, 1.0);
        DIALOGUE_COOLDOWN = builder.comment("Minimum ticks between an NPC's greeting lines.")
                .defineInRange("greetingCooldownTicks", 200, 40, 2_400);
        MUTE_VILLAGERS = builder.comment("Suppress villager vocalizations from Apocalypse Now survivors and soldiers only.")
                .define("muteVillagerVoices", true);
        builder.pop();
        SPEC = builder.build();
    }

    private NpcConfig() {}
}
