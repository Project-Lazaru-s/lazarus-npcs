package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * Logs every death or removal of a human NPC with its cause, and remembers recent deaths so a task
 * whose giver died can say so in the journal, even for players who were offline at the time.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcDeaths {
    /** Deaths are kept as long as a task can last; older entries are dropped on the next death. */
    static final long KEEP_TICKS = 48_000L;
    private static Store cached;

    private NpcDeaths() {}

    static boolean tracked(Entity entity) {
        return entity instanceof LivingEntity living
                && (NpcIdentity.role(living) != null || HumanEncounters.kind(living) != null);
    }

    static String describe(LivingEntity npc) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType());
        String name = NpcIdentity.role(npc) != null ? NpcIdentity.name(npc) : null;
        return (name == null ? "" : name + " ") + "(" + id + ") at " + npc.getBlockX() + " " + npc.getBlockY() + " "
                + npc.getBlockZ() + " in " + npc.level().dimension().location();
    }

    /** Short, human cause: who or what killed the NPC. */
    static String cause(DamageSource source) {
        Entity killer = source.getEntity();
        if (killer instanceof Player player) return "killed by " + player.getGameProfile().getName();
        if (killer instanceof LivingEntity living) {
            if (NpcIdentity.role(living) != null) return "killed by " + NpcIdentity.name(living);
            return "killed by " + readable(ForgeRegistries.ENTITY_TYPES.getKey(living.getType()));
        }
        return "died (" + source.getMsgId() + ")";
    }

    /** "apocalypsenow:runner_42" -> "a runner", "minecraft:zombie" -> "a zombie". */
    static String readable(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return "something";
        String name = id.getPath().replaceAll("_?\\d+$", "").replace('_', ' ');
        return ("aeiou".indexOf(name.isEmpty() ? 'x' : name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(LivingDeathEvent event) {
        LivingEntity npc = event.getEntity();
        if (npc.level().isClientSide() || event.isCanceled() || !tracked(npc)) return;
        String cause = cause(event.getSource());
        LazarusNpcs.LOGGER.info("[npc] {} {}", describe(npc), cause);
        if (NpcIdentity.role(npc) == null) return; // only friendly NPCs give tasks
        MinecraftServer server = npc.getServer();
        long now = server.overworld().getGameTime();
        Store store = store(server);
        store.deaths.getAllKeys().removeIf(key -> now - store.deaths.getCompound(key).getLong("Time") > KEEP_TICKS);
        CompoundTag record = new CompoundTag();
        record.putString("Name", NpcIdentity.name(npc));
        record.putString("Cause", cause);
        record.putLong("Time", now);
        store.deaths.put(npc.getUUID().toString(), record);
        store.setDirty();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (npc.getUUID().equals(NpcTasks.giverId(player)))
                player.sendSystemMessage(Component.literal(NpcIdentity.name(npc) + ", who gave you your task, was "
                        + cause + ". " + NpcTasks.turnInHint(player)).withStyle(style -> style.withColor(0xD46868)));
    }

    /** Removals that are not deaths or ordinary chunk unloads: despawns, discards, dimension changes. */
    @SubscribeEvent
    public static void removed(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide() || !tracked(entity)) return;
        var reason = entity.getRemovalReason();
        if (reason == null || reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) return;
        LazarusNpcs.LOGGER.info("[npc] {} removed: {}", describe((LivingEntity) entity), reason);
    }

    /** The recorded death of an NPC, or null when it is alive or the record has expired. */
    static CompoundTag death(MinecraftServer server, UUID npc) {
        CompoundTag record = store(server).deaths.getCompound(npc.toString());
        if (record.isEmpty() || server.overworld().getGameTime() - record.getLong("Time") > KEEP_TICKS) return null;
        return record;
    }

    static final class Store extends SavedData {
        final CompoundTag deaths;

        Store(CompoundTag deaths) { this.deaths = deaths; }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.put("Deaths", deaths);
            return tag;
        }
    }

    static Store store(MinecraftServer server) {
        if (cached == null)
            cached = server.overworld().getDataStorage().computeIfAbsent(
                    tag -> new Store(tag.getCompound("Deaths")), () -> new Store(new CompoundTag()), "lazarusnpcs_deaths");
        return cached;
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { cached = null; }
}
