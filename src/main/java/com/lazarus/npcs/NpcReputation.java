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
                if (score < 0) data.putInt(name, (int) Math.min(0L, score + days * 5L));
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

    /** {@code reason} identifies API callers in the ReputationChanged event; null for Lazarus NPCs' own rules. */
    static int adjust(ServerPlayer player, String faction, int amount, net.minecraft.resources.ResourceLocation reason) {
        if ("independent".equals(faction)) return 0;
        int current = get(player, faction);
        int updated = Math.max(-100, Math.min(100, current + amount));
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        data.putInt(faction, updated);
        player.getPersistentData().put(DATA_KEY, data);
        NpcHostility.standingChanged(player, faction, current, updated);
        if (updated != current)
            ApiBackend.post(new com.lazarus.npcs.api.event.NpcApiEvents.ReputationChanged(player, faction, current, updated, reason));
        return updated;
    }

    static void taskRivalry(ServerPlayer player, String faction) {
        if ("rovers".equals(faction)) adjust(player, "wardens", -1);
        if ("wardens".equals(faction)) adjust(player, "rovers", -1);
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
