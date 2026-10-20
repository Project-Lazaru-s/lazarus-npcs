package com.lazarus.npcs;

import net.minecraftforge.common.ForgeConfigSpec;

final class NpcConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue VOICE_VOLUME;
    static final ForgeConfigSpec.IntValue DIALOGUE_COOLDOWN;
    static final ForgeConfigSpec.BooleanValue MUTE_VILLAGERS;
    static final ForgeConfigSpec.IntValue RIVAL_REPUTATION_PERCENT;
    static final ForgeConfigSpec.BooleanValue SCAVENGING_ENABLED;
    static final ForgeConfigSpec.IntValue SCAVENGING_MAX_PACK_STACKS;
    static final ForgeConfigSpec.IntValue SCAVENGING_INTERVAL_SECONDS;
    static final ForgeConfigSpec.IntValue SCAVENGING_RADIUS;

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
        builder.push("scavenging");
        SCAVENGING_ENABLED = builder.comment("Allow unassigned human NPCs to take a small amount from untouched structure loot.")
                .define("enabled", true);
        SCAVENGING_MAX_PACK_STACKS = builder.defineInRange("maxPackStacks", 4, 1, 8);
        SCAVENGING_INTERVAL_SECONDS = builder.defineInRange("intervalSeconds", 90, 60, 300);
        SCAVENGING_RADIUS = builder.defineInRange("radius", 28, 8, 32);
        builder.pop();
        builder.push("factions");
        RIVAL_REPUTATION_PERCENT = builder.comment("Opposite reputation change for confirmed Rovers/Wardens political actions, as a percentage.")
                .defineInRange("rivalReputationPercent", 25, 0, 100);
        builder.pop();
        SPEC = builder.build();
    }

    private NpcConfig() {}
}
