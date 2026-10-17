package com.lazarus.npcs;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.fml.ModList;

/**
 * Optional Lazarus Stealth integration. Without Lazarus Stealth every call is a no-op and NPCs keep their
 * original senses; StealthBridge (which references the Stealth API) is only loaded when the mod is present.
 */
final class Stealth {
    private static Boolean loaded;

    private Stealth() {}

    static boolean loaded() {
        if (loaded == null) loaded = ModList.get().isLoaded("lazarusstealth");
        return loaded;
    }

    static void init() {
        if (loaded()) StealthBridge.init();
    }

    /** Has this NPC noticed the player (and still remembers them)? Always true without Lazarus Stealth. */
    static boolean aware(Mob mob, ServerPlayer player) {
        return !loaded() || StealthBridge.aware(mob, player);
    }

    /** Shared intelligence before a deliberate setTarget (retaliation, rally, ambush), so the stealth gate lets it through. */
    static void reveal(Mob mob, ServerPlayer player, String reason) {
        if (loaded()) StealthBridge.reveal(mob, player, reason);
    }
}
