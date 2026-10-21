package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcReputation {
    private static final String DATA_KEY = "LazarusNpcsReputation";
    private static final long RECOVERY_TICKS = 24_000L;

    private NpcReputation() {}

    static int get(ServerPlayer player, String faction) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        migrate(data);
        long now = player.serverLevel().getGameTime();
        long last = data.getLong("LastRecoveryTick");
        if (!data.contains("LastRecoveryTick") || last > now) {
            data.putLong("LastRecoveryTick", now);
        } else if (now - last >= RECOVERY_TICKS) {
            long days = (now - last) / RECOVERY_TICKS;
            int[] before = new int[NpcIdentity.FACTIONS.length];
            for (int i = 0; i < before.length; i++) {
                String name = NpcIdentity.FACTIONS[i];
                int score = before[i] = data.getInt(name);
                int political = data.getInt("Political_" + name);
                data.putInt(name, recover(score, political, days));
            }
            data.putLong("LastRecoveryTick", last + days * RECOVERY_TICKS);
            player.getPersistentData().put(DATA_KEY, data);
            for (int i = 0; i < before.length; i++)
                NpcHostility.standingChanged(player, NpcIdentity.FACTIONS[i], before[i], data.getInt(NpcIdentity.FACTIONS[i]));
        }
        player.getPersistentData().put(DATA_KEY, data);
        return data.getInt(faction);
    }

    static void migrate(CompoundTag data) {
        if (!data.getBoolean("FactionMigration")) {
            int previous = data.getInt("survivor");
            for (String name : new String[]{"haven", "rovers", "wardens"})
                if (!data.contains(name)) data.putInt(name, previous);
            data.putBoolean("FactionMigration", true);
        }
    }

    static int adjust(ServerPlayer player, String faction, int amount) { return adjust(player, faction, amount, null); }

    /** Negotiation skill Boa fama (Puffish Skills tag in the modpack): reputation gains are half again as large. */
    static final String TAG_GOOD_NAME = "lazarus_diplomata";

    static int withSkill(ServerPlayer player, int amount) {
        return amount > 0 && player.getTags().contains(TAG_GOOD_NAME) ? amount + Math.max(1, amount / 2) : amount;
    }

    /** {@code reason} identifies API callers in the ReputationChanged event; null for Lazarus NPCs' own rules. */
    static int adjust(ServerPlayer player, String faction, int amount, net.minecraft.resources.ResourceLocation reason) {
        return adjust(player, faction, amount, reason, false);
    }

    static int adjustPolitical(ServerPlayer player, String faction, int amount,
                               net.minecraft.resources.ResourceLocation reason) {
        return adjust(player, faction, amount, reason, true);
    }

    private static int adjust(ServerPlayer player, String faction, int amount,
                              net.minecraft.resources.ResourceLocation reason, boolean political) {
        if ("independent".equals(faction)) return 0;
        amount = withSkill(player, amount);
        int current = get(player, faction);
        int updated = Math.max(-100, Math.min(100, current + amount));
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        data.putInt(faction, updated);
        java.util.Map<String, int[]> rivalChanges = new java.util.LinkedHashMap<>();
        var definition = com.lazarus.npcs.api.NpcFactions.get(faction);
        if (political && definition != null && !definition.rivals().isEmpty() && updated != current) {
            data.putInt("Political_" + faction, clamp(data.getInt("Political_" + faction) + updated - current));
            for (String rival : definition.rivals()) {
                if (com.lazarus.npcs.api.NpcFactions.get(rival) == null) continue;
                String carryKey = "RivalCarry_" + faction + "_" + rival;
                // Existing two-faction worlds stored the carry without a rival suffix.
                if (!data.contains(carryKey) && data.contains("RivalCarry_" + faction))
                    data.putInt(carryKey, data.getInt("RivalCarry_" + faction));
                int carry = data.getInt(carryKey) + (updated - current) * NpcConfig.RIVAL_REPUTATION_PERCENT.get();
                int rivalDelta = rivalDelta(carry);
                data.putInt(carryKey, carry % 100);
                int before = data.getInt(rival);
                int after = clamp(before + rivalDelta);
                data.putInt(rival, after);
                data.putInt("Political_" + rival, clamp(data.getInt("Political_" + rival) + after - before));
                rivalChanges.put(rival, new int[] {before, after});
            }
        }
        player.getPersistentData().put(DATA_KEY, data);
        NpcHostility.standingChanged(player, faction, current, updated);
        rivalChanges.forEach((rival, change) -> {
            if (change[0] != change[1]) NpcHostility.standingChanged(player, rival, change[0], change[1]);
        });
        if (updated != current)
            ApiBackend.post(new com.lazarus.npcs.api.event.NpcApiEvents.ReputationChanged(player, faction, current, updated, reason));
        rivalChanges.forEach((rival, change) -> {
            if (change[0] != change[1]) ApiBackend.post(new com.lazarus.npcs.api.event.NpcApiEvents.ReputationChanged(
                    player, rival, change[0], change[1], reason));
        });
        return updated;
    }

    static int rivalDelta(int carry) { return -(carry / 100); }

    private static int clamp(int value) { return Math.max(-100, Math.min(100, value)); }

    static int recover(int score, int political, long days) {
        int recoverable = score - political;
        if (recoverable >= 0) return score;
        return (int) Math.max(-100L, Math.min(100L,
                political + Math.min(0L, recoverable + days * 5L)));
    }

    private static String friendlyFaction(LivingEntity npc) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType());
        if (id == null || !"apocalypsenow".equals(id.getNamespace())) return null;
        return NpcIdentity.faction(npc);
    }

    private static ServerPlayer attacker(Entity source) {
        return source instanceof ServerPlayer player ? player : null;
    }

    @SubscribeEvent
    public static void damaged(LivingDamageEvent event) {
        if (event.getAmount() <= 0 || event.getEntity().level().isClientSide()) return;
        String faction = friendlyFaction(event.getEntity());
        ServerPlayer player = attacker(event.getSource().getEntity());
        if (faction == null || player == null || player == event.getEntity()) return;
        // One penalty per NPC per minute, so a burst of gunfire costs the same as a single blow.
        CompoundTag hits = event.getEntity().getPersistentData().getCompound("LazarusNpcsHitPenalty");
        long now = event.getEntity().level().getGameTime();
        String key = player.getUUID().toString();
        if (!penaltyDue(hits.contains(key) ? hits.getLong(key) : null, now)) return;
        hits.putLong(key, now);
        event.getEntity().getPersistentData().put("LazarusNpcsHitPenalty", hits);
        adjust(player, faction, -5);
    }

    static boolean penaltyDue(Long lastPenalty, long now) {
        return lastPenalty == null || now < lastPenalty || now - lastPenalty >= 1_200L;
    }

    /** Operator command support: sets a standing directly, with the usual enemy messages. */
    static void set(ServerPlayer player, String faction, int value) {
        adjust(player, faction, Math.max(-100, Math.min(100, value)) - get(player, faction));
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        data.remove("Political_" + faction);
        data.remove("RivalCarry_" + faction);
        if (com.lazarus.npcs.api.NpcFactions.get(faction) != null)
            for (String rival : com.lazarus.npcs.api.NpcFactions.get(faction).rivals())
                data.remove("RivalCarry_" + faction + "_" + rival);
        player.getPersistentData().put(DATA_KEY, data);
    }

    @SubscribeEvent
    public static void killed(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        String faction = friendlyFaction(event.getEntity());
        ServerPlayer player = attacker(event.getSource().getEntity());
        if (faction != null && player != null && player != event.getEntity()) adjust(player, faction, -20);
    }

    @SubscribeEvent
    public static void clonePlayer(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        CompoundTag oldData = event.getOriginal().getPersistentData();
        if (oldData.contains(DATA_KEY)) {
            event.getEntity().getPersistentData().put(DATA_KEY, oldData.getCompound(DATA_KEY).copy());
        }
    }
}
