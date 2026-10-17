package com.lazarus.npcs;

import com.lazarus.stealth.Perception.State;
import com.lazarus.stealth.StealthApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.WeakHashMap;

/** Lazarus Stealth hooks: who may be hostile, who talks to whom, and what NPCs say as their awareness changes. */
final class StealthBridge {
    private static final Map<Mob, Long> LAST_LINE = new WeakHashMap<>();
    private static final long LINE_COOLDOWN = 160;

    private StealthBridge() {}

    static void init() {
        StealthApi.setHostility(StealthBridge::mayBeHostile);
        StealthApi.setAllies(StealthBridge::allies);
        StealthApi.addListener(StealthBridge::speak);
        LazarusNpcs.LOGGER.info("[lazarusnpcs] Lazarus Stealth integration enabled");
    }

    static boolean aware(Mob mob, ServerPlayer player) {
        return StealthApi.aware(mob, player);
    }

    static void reveal(Mob mob, ServerPlayer player, String reason) {
        StealthApi.reveal(mob, player, reason);
    }

    /**
     * Looters, rogue soldiers and cannibals may always turn on a player (unless paid off or surrendered);
     * faction NPCs only while hostileTo says so. Hunters, friends and hired NPCs never sneak-detect anyone.
     */
    static boolean mayBeHostile(Mob mob, Player candidate) {
        if (!(candidate instanceof ServerPlayer player) || HumanSurrender.surrendered(mob)) return false;
        String kind = HumanEncounters.kind(mob);
        if (kind != null) return HumanEncounters.state(mob, player).getLong("PeaceUntil") <= mob.level().getGameTime();
        String role = NpcIdentity.role(mob);
        if (role == null || "hunter".equals(role)) return false;
        NpcContracts.Contract contract = NpcContracts.contract(mob);
        if (contract != null && player.getUUID().equals(contract.owner)) return false;
        return NpcHostility.hostileTo(mob, player);
    }

    /** Same encounter group (looters, soldiers, cannibals) or same faction. */
    static boolean allies(Mob a, Mob b) {
        String kind = HumanEncounters.kind(a);
        if (kind != null) return kind.equals(HumanEncounters.kind(b));
        String role = NpcIdentity.role(a), other = NpcIdentity.role(b);
        if (role == null || other == null || "hunter".equals(role) || "hunter".equals(other)) return false;
        return NpcIdentity.faction(a).equals(NpcIdentity.faction(b));
    }

    // Cannibals stalk in silence; everyone else talks, which tells the player how close they are to being found.
    private static void speak(Mob mob, ServerPlayer player, State from, State to, String reason) {
        if ("cannibal".equals(HumanEncounters.kind(mob)) || mob.distanceToSqr(player) > 32 * 32) return;
        String line = line(from, to, reason);
        if (line == null) return;
        long now = mob.level().getGameTime();
        Long last = LAST_LINE.get(mob);
        if (last != null && now - last < LINE_COOLDOWN) return;
        LAST_LINE.put(mob, now);
        String kind = HumanEncounters.kind(mob);
        String speaker = kind != null ? HumanEncounters.label(kind) : NpcIdentity.name(mob);
        player.displayClientMessage(Component.literal(speaker + ": \"" + line + "\"")
                .withStyle(style -> style.withColor(0xD4B483).withItalic(true)), true);
    }

    static String line(State from, State to, String reason) {
        if (reason.startsWith("callout") || reason.equals("hurt") || reason.startsWith("ally") || reason.equals("rally")
                || reason.equals("ambush")) return null;
        return switch (to) {
            case SUSPICIOUS -> from == State.UNAWARE ? "Hm? Something moved over there." : null;
            case INVESTIGATING -> from.ordinal() < State.INVESTIGATING.ordinal()
                    ? (reason.startsWith("heard") ? "Did you hear that?" : "I'm going to take a look.")
                    : null;
            case ALERTED -> reason.equals("lost target") ? "Lost them. Spread out, check the corners!" : "That was close. Eyes open!";
            case PURSUING -> "There! Over there!";
            case UNAWARE -> from == State.PURSUING ? "They're gone." : "Must have been nothing.";
        };
    }
}
