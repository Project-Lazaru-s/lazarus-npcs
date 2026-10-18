package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adds a short, server-authoritative warning and a way past human raiders. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class HumanEncounters {
    static final String KEY = "LazarusHumanEncounter";
    static final int CHECKPOINT_RADIUS = 12;
    private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();

    private HumanEncounters() {}

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { LAST_WARNING.clear(); }

    static String kind(LivingEntity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null || !"apocalypsenow".equals(id.getNamespace())) return null;
        String path = id.getPath();
        if (path.startsWith("looter_")) return "looter";
        if (path.equals("soldiers") || path.equals("soldiers_02")) return "soldier";
        if (path.equals("cannibal")) return "cannibal";
        return null;
    }

    static String label(String kind) {
        return switch (kind) {
            case "looter" -> "Looters";
            case "soldier" -> "Rogue patrol";
            default -> "Cannibal";
        };
    }

    static String demand(String kind) {
        return "soldier".equals(kind) ? "2 canned food and 2 iron ingots" : "2 canned food";
    }

    static CompoundTag data(LivingEntity mob) { return mob.getPersistentData().getCompound(KEY); }

    static CompoundTag state(LivingEntity mob, ServerPlayer player) {
        return data(mob).getCompound(player.getUUID().toString());
    }

    static void putState(LivingEntity mob, ServerPlayer player, CompoundTag state) {
        CompoundTag root = data(mob);
        root.put(player.getUUID().toString(), state);
        mob.getPersistentData().put(KEY, root);
    }

    static void putData(LivingEntity mob, CompoundTag root) { mob.getPersistentData().put(KEY, root); }

    static List<Mob> group(LivingEntity mob, String kind, double radius) {
        List<Mob> group = new java.util.ArrayList<>(mob.level().getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(radius),
                other -> other != mob && other.isAlive() && kind.equals(kind(other))));
        if (mob instanceof Mob self && self.isAlive()) group.add(self); // the query can miss the caller near chunk borders
        return group;
    }

    static boolean canNegotiate(LivingEntity npc, ServerPlayer player) {
        String kind = kind(npc);
        if (!"looter".equals(kind) && !"soldier".equals(kind)) return false;
        CompoundTag state = state(npc, player);
        return state.contains("WarnedAt") && !state.getBoolean("Provoked")
                && state.getLong("PeaceUntil") <= npc.level().getGameTime() && npc.isAlive();
    }

    static void pay(ServerPlayer player, LivingEntity npc) {
        if (!canNegotiate(npc, player) || npc.distanceToSqr(player) > 64) return;
        String kind = kind(npc);
        if (!has(player, "apocalypsenow:canned_food", 2)
                || "soldier".equals(kind) && !has(player, "minecraft:iron_ingot", 2)) {
            player.sendSystemMessage(Component.literal("You need " + demand(kind) + " to pass safely."));
            return;
        }
        take(player, "apocalypsenow:canned_food", 2);
        if ("soldier".equals(kind)) take(player, "minecraft:iron_ingot", 2);
        grantPassage(player, npc, kind, 6_000);
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal("The " + label(kind).toLowerCase()
                + " take the supplies and let you pass for five minutes."));
    }

    /** Peace for this player with every member of the NPC's group within 16 blocks, as after paying. */
    static void grantPassage(ServerPlayer player, LivingEntity npc, String kind, long ticks) {
        long until = player.serverLevel().getGameTime() + ticks;
        for (Mob ally : group(npc, kind, 16)) {
            CompoundTag state = state(ally, player);
            state.putLong("PeaceUntil", until);
            state.putBoolean("Provoked", false);
            if (!state.contains("WarnedAt")) state.putLong("WarnedAt", player.serverLevel().getGameTime());
            putState(ally, player, state);
            if (ally.getTarget() == player) ally.setTarget(null);
        }
    }

    /** Warns the whole group at once, so every member shares the same negotiation window. */
    static void warnGroup(Mob mob, String kind, ServerPlayer player, long now) {
        for (Mob ally : group(mob, kind, 16)) {
            CompoundTag state = state(ally, player);
            if (state.contains("WarnedAt") && !warningExpired(state.getLong("WarnedAt"), now)) continue;
            state.putLong("WarnedAt", now);
            state.remove("Signalled");
            putState(ally, player, state);
        }
        long last = LAST_WARNING.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now >= last && now - last <= 100) return;
        LAST_WARNING.put(player.getUUID(), now);
        String warning = switch (kind) {
            case "cannibal" -> "Something is following you. Get away now!";
            case "soldier" -> "Rogue checkpoint: \"Halt! Pay the toll or turn back.\" Approach with empty hands to negotiate; crossing their line starts a fight.";
            default -> (HumanTactics.isLookout(mob) ? "A looter lookout spots you and whistles. " : "")
                    + "Looters demand supplies. Approach with empty hands to negotiate, or leave the area.";
        };
        player.sendSystemMessage(Component.literal(warning).withStyle(style -> style.withColor(0xD48968)));
    }

    @SubscribeEvent
    public static void target(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if (!(event.getNewTarget() instanceof ServerPlayer player)) return;
        long now = mob.level().getGameTime();
        if (HumanTactics.isHunter(mob)) {
            if (retreating(mob, now)) event.setNewTarget(null);
            return;
        }
        String kind = kind(mob);
        if (kind == null) return;
        if (retreating(mob, now) || daylightCannibal(mob, kind) || HumanTactics.broken(mob)) {
            event.setNewTarget(null);
            return;
        }
        CompoundTag state = state(mob, player);
        if (state.getLong("PeaceUntil") > now) {
            event.setNewTarget(null);
            return;
        }
        if (state.getBoolean("Provoked")) return;
        if ("cannibal".equals(kind)) {
            // Cannibals only commit after their stalk ends in an ambush (see HumanTactics).
            if (!state.getBoolean("Ambush") || now - state.getLong("WarnedAt") < 40) event.setNewTarget(null);
            return;
        }
        // A new approach after a long gap (or after paid passage ends) gets a fresh warning.
        if (state.contains("WarnedAt") && mob.getTarget() != player
                && warningExpired(state.getLong("WarnedAt"), now)) {
            state.remove("WarnedAt");
            putState(mob, player, state);
        }
        if (!state.contains("WarnedAt")) {
            warnGroup(mob, kind, player, now);
            state = state(mob, player);
        }
        if (now - state.getLong("WarnedAt") < 200 || HumanTactics.isLookout(mob)
                || "soldier".equals(kind) && !HumanTactics.insidePost(mob, player, CHECKPOINT_RADIUS))
            event.setNewTarget(null);
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        String kind = kind(mob);
        if (kind == null && !HumanTactics.isHunter(mob)) return;
        if (mob.getHealth() - event.getAmount() <= mob.getMaxHealth() * 0.25f) {
            CompoundTag root = data(mob);
            root.putLong("RetreatUntil", mob.level().getGameTime() + 200);
            putData(mob, root);
            mob.setTarget(null);
        }
        if (kind == null || !(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        for (Mob ally : group(mob, kind, 12)) {
            CompoundTag state = state(ally, player);
            state.putBoolean("Provoked", true);
            state.remove("PeaceUntil");
            putState(ally, player, state);
            Stealth.reveal(ally, player, "ally hurt");
            ally.setTarget(player);
        }
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        if (!"looter".equals(kind(mob))) return;
        int loss = HumanTactics.isLookout(mob) ? 2 : 1;
        for (Mob ally : group(mob, "looter", 16)) {
            if (ally == mob) continue;
            CompoundTag root = data(ally);
            root.putInt("Losses", root.getInt("Losses") + loss);
            putData(ally, root);
        }
    }

    static boolean retreating(Mob mob, long now) { return data(mob).getLong("RetreatUntil") > now; }

    static boolean warningExpired(long warnedAt, long now) {
        return now < warnedAt || now - warnedAt > 6_000;
    }

    static boolean daylightCannibal(Mob mob, String kind) {
        return "cannibal".equals(kind) && mob.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && mob.level().getDayTime() % 24000 < 12000 && mob.level().canSeeSky(mob.blockPosition());
    }

    private static boolean has(ServerPlayer player, String id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null) return false;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total >= count;
    }

    private static void take(ServerPlayer player, String id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        for (int slot = 0; slot < 36 && count > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int amount = Math.min(count, stack.getCount());
            stack.shrink(amount);
            count -= amount;
        }
        player.getInventory().setChanged();
    }
}
