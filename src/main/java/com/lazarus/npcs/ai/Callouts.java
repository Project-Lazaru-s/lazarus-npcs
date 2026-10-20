package com.lazarus.npcs.ai;

import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Short local tactical speech, rate limited per actor. */
public final class Callouts {
    private static final WeakHashMap<Mob, Long> LAST = new WeakHashMap<>();
    private Callouts() {}

    public static void say(Mob actor, String line) {
        if (!(actor.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now - LAST.getOrDefault(actor, -1000L) < 200) return;
        LAST.put(actor, now);
        level.players().stream().filter(player -> player.distanceToSqr(actor) < 28 * 28)
                .forEach(player -> player.sendSystemMessage(Component.literal("[" + actor.getDisplayName().getString()
                        + "] " + line)));
    }
}
