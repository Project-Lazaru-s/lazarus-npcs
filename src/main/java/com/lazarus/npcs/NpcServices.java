package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcServices {
    private static final String PLAYER_DATA = "LazarusNpcsServices";
    private static final long REPAIR_COOLDOWN = 6_000L;
    private static final long MEDIC_COOLDOWN = 2_400L;
    private static final long SCOUT_COOLDOWN = 3_600L;

    private NpcServices() {}

    static void hint(ServerPlayer player) {
        CompoundTag state = data(player);
        if (state.getBoolean("HintSeen")) return;
        state.putBoolean("HintSeen", true);
        player.getPersistentData().put(PLAYER_DATA, state);
        MutableComponent hint = Component.literal("NPC services: [show nearby options]");
        hint.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/lazarusnpcs services")));
        player.sendSystemMessage(hint);
    }

    static String summary(ServerPlayer player, LivingEntity npc) {
        String npcRole = role(npc);
        if (npcRole == null) return "";
        if (NpcReputation.get(player, NpcIdentity.faction(npc)) <= -20) return "Unavailable: this group refuses to help.";
        CompoundTag state = data(player);
        long now = time(player);
        String cooldownKey = "survivor".equals(npcRole) ? "RepairAt"
                : "medic".equals(npcRole) ? "MedicAt" : "hunter".equals(npcRole) ? "ScoutAt" : null;
        long cooldown = "survivor".equals(npcRole) ? REPAIR_COOLDOWN
                : "hunter".equals(npcRole) ? SCOUT_COOLDOWN : MEDIC_COOLDOWN;
        if (cooldownKey != null && state.contains(cooldownKey) && now >= state.getLong(cooldownKey)
                && now - state.getLong(cooldownKey) < cooldown)
            return "Available again in about " + ((cooldown - (now - state.getLong(cooldownKey)) + 1199) / 1200) + " min.";
        if ("survivor".equals(npcRole)) {
            if (!player.getMainHandItem().isDamageableItem() || !player.getMainHandItem().isDamaged())
                return "Hold a damaged item in your main hand.";
            return has(player, "minecraft:iron_ingot", 3) ? "Ready" : "Need 3 iron ingots.";
        }
        if ("medic".equals(npcRole)) {
            if (player.getHealth() >= player.getMaxHealth() && !player.hasEffect(MobEffects.POISON)
                    && !player.hasEffect(MobEffects.WEAKNESS)) return "No treatment needed.";
            return has(player, "apocalypsenow:bandage", 2) && has(player, "apocalypsenow:canned_water", 1)
                    ? "Ready" : "Need 2 bandages and 1 canned water.";
        }
        if ("hunter".equals(npcRole))
            return has(player, "apocalypsenow:canned_food", 1) ? "Ready" : "Need 1 canned food.";
        return NpcContracts.escortStatus(player, npc);
    }

    static void show(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(6.0), npc -> role(npc) != null && npc.isAlive()
                        && npc.distanceToSqr(player) <= 36.0 && player.hasLineOfSight(npc));
        if (nearby.isEmpty()) {
            say(player, "No survivor, hunter, medic, or military NPC is within six blocks.");
            return;
        }

        say(player, "Available NPC services (click an option while standing near the NPC):");
        if (nearby.stream().anyMatch(npc -> "survivor".equals(role(npc)))) {
            option(player, "repair", "Survivor repair: 3 iron ingots; restore 25% durability of held item.");
        }
        if (nearby.stream().anyMatch(npc -> "medic".equals(role(npc)) || "stabilize".equals(NpcProfessions.baseService(npc)))) {
            option(player, "stabilize", "Medic stabilization: 2 bandages + 1 canned water; heal 4 hearts and clear poison/weakness.");
        }
        if (nearby.stream().anyMatch(npc -> "military".equals(role(npc)))) {
            option(player, "escort", "Military escort: 3 canned food + 2 iron ingots; soldier follows for 5 minutes.");
            option(player, "guard", "Guard duty (military or Wardens): open the NPC screen, Guard tab, to pick the area.");
        }
        if (nearby.stream().anyMatch(npc -> "hunter".equals(role(npc)))) {
            option(player, "scout", "Hunter scouting: 1 canned food; nearby infected and untouched supply caches.");
        }
    }

    static void use(ServerPlayer player, String service) {
        use(player, service, null);
    }

    static void use(ServerPlayer player, String service, java.util.UUID selectedNpc) {
        String requiredRole = switch (service) {
            case "repair" -> "survivor";
            case "stabilize" -> "medic";
            case "escort" -> "military";
            case "scout" -> "hunter";
            default -> null;
        };
        if (requiredRole == null) {
            say(player, "Unknown service. Use /lazarusnpcs services to see nearby options.");
            return;
        }

        LivingEntity npc = player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(6.0), entity -> entity.isAlive()
                                && (requiredRole.equals(role(entity)) || service.equals(NpcProfessions.baseService(entity)))
                                && entity.distanceToSqr(player) <= 36.0
                                && player.hasLineOfSight(entity)
                                && (NpcProfessions.base(entity) == null || service.equals(NpcProfessions.baseService(entity)))
                                && (selectedNpc == null || entity.getUUID().equals(selectedNpc)))
                .stream().min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
        if (npc == null) {
            say(player, "Move within six blocks of a " + requiredRole + " NPC first.");
            return;
        }
        if (NpcProfessions.base(npc) != null && !service.equals(NpcProfessions.baseService(npc))) {
            say(player, "This base member does not offer that service.");
            return;
        }
        if (NpcReputation.get(player, NpcIdentity.faction(npc)) <= -20) {
            say(player, "This group refuses to help after your attacks.");
            return;
        }

        switch (service) {
            case "repair" -> repair(player, npc);
            case "stabilize" -> stabilize(player, npc);
            case "escort" -> {
                if (npc instanceof Mob soldier) NpcContracts.hireEscort(player, soldier);
            }
            case "scout" -> scout(player, npc);
            default -> throw new IllegalStateException("Unhandled service " + service);
        }
    }

    private static void repair(ServerPlayer player, LivingEntity npc) {
        ItemStack held = player.getMainHandItem();
        if (!held.isDamageableItem() || !held.isDamaged()) {
            say(player, "Hold a damaged tool or weapon in your main hand.");
            return;
        }
        if (!cooldownReady(player, "RepairAt", REPAIR_COOLDOWN)) return;
        if (!has(player, "minecraft:iron_ingot", 3)) {
            say(player, "Repair costs 3 iron ingots.");
            return;
        }

        consume(player, "minecraft:iron_ingot", 3);
        int restored = Math.max(1, (int) Math.ceil(held.getMaxDamage() * 0.25));
        held.setDamageValue(Math.max(0, held.getDamageValue() - restored));
        stamp(player, "RepairAt");
        player.inventoryMenu.broadcastChanges();
        say(player, "Item repaired by up to 25% of its maximum durability.");
        NpcDialogue.action(player, npc, "service");
    }

    private static void stabilize(ServerPlayer player, LivingEntity npc) {
        boolean needsHelp = player.getHealth() < player.getMaxHealth()
                || player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WEAKNESS);
        if (!needsHelp) {
            say(player, "You do not need stabilization right now.");
            return;
        }
        if (!cooldownReady(player, "MedicAt", MEDIC_COOLDOWN)) return;
        if (!has(player, "apocalypsenow:bandage", 2) || !has(player, "apocalypsenow:canned_water", 1)) {
            say(player, "Stabilization costs 2 bandages and 1 canned water.");
            return;
        }

        consume(player, "apocalypsenow:bandage", 2);
        consume(player, "apocalypsenow:canned_water", 1);
        player.heal(8.0f);
        player.removeEffect(MobEffects.POISON);
        player.removeEffect(MobEffects.WEAKNESS);
        stamp(player, "MedicAt");
        player.inventoryMenu.broadcastChanges();
        say(player, "The medic stabilized you: 4 hearts restored, poison and weakness cleared.");
        NpcDialogue.action(player, npc, "service");
    }

    private static void scout(ServerPlayer player, LivingEntity npc) {
        if (!cooldownReady(player, "ScoutAt", SCOUT_COOLDOWN)) return;
        if (!has(player, "apocalypsenow:canned_food", 1)) {
            say(player, "Scouting costs 1 canned food.");
            return;
        }
        consume(player, "apocalypsenow:canned_food", 1);
        stamp(player, "ScoutAt");
        player.inventoryMenu.broadcastChanges();
        ServerLevel level = player.serverLevel();
        List<LivingEntity> infected = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(96), entity -> entity.isAlive() && entity != player && isInfected(entity));
        LivingEntity threat = infected.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
        if (threat == null) {
            say(player, "The hunter found no infected signs within roughly 96 blocks.");
        } else {
            int distance = Math.max(16, (int) (Math.round(Math.sqrt(threat.distanceToSqr(player)) / 16) * 16));
            say(player, "The hunter spotted infected signs about " + distance + " blocks "
                    + direction(threat.getX() - player.getX(), threat.getZ() - player.getZ())
                    + " (" + (infected.size() >= 10 ? "many" : infected.size() >= 4 ? "several" : "a few") + " around).");
        }
        BlockPos cache = untouchedCache(level, player.blockPosition());
        if (cache == null) {
            say(player, "No untouched supply cache stands out within about 48 blocks.");
        } else {
            long guards = infected.stream().filter(entity -> entity.distanceToSqr(cache.getX() + 0.5, cache.getY(), cache.getZ() + 0.5) < 144).count();
            int distance = Math.max(8, (int) (Math.round(Math.sqrt(cache.distSqr(player.blockPosition())) / 8) * 8));
            say(player, "Untouched supplies about " + distance + " blocks "
                    + direction(cache.getX() - player.getX(), cache.getZ() - player.getZ())
                    + (guards == 0 ? ", and it looks quiet." : guards < 3 ? ", watched by a few infected." : ", but infected are crowding it."));
        }
        NpcDialogue.action(player, npc, "service");
    }

    /** Nearest unopened loot container in already-loaded chunks; never loads or generates terrain. */
    static BlockPos untouchedCache(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity block : chunk.getBlockEntities().values()) {
                    if (!(block instanceof RandomizableContainerBlockEntity)) continue;
                    BlockPos pos = block.getBlockPos();
                    if (pos.distSqr(origin) > 48 * 48 || best != null && pos.distSqr(origin) >= best.distSqr(origin)) continue;
                    if (block.saveWithoutMetadata().contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)) best = pos;
                }
            }
        }
        return best;
    }

    static String direction(double dx, double dz) {
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
    }

    static boolean isInfected(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.Zombie) return true;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null) return false;
        return "undead_revamp2".equals(id.getNamespace()) || "hordes".equals(id.getNamespace())
                || "apocalypsenow".equals(id.getNamespace()) &&
                    (id.getPath().startsWith("walker_") || id.getPath().startsWith("runner_")
                            || id.getPath().startsWith("crawler_") || id.getPath().startsWith("horde_"));
    }

    private static void option(ServerPlayer player, String id, String label) {
        MutableComponent choice = Component.literal("[" + label + "]");
        choice.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/lazarusnpcs service " + id)));
        player.sendSystemMessage(choice);
    }

    private static boolean cooldownReady(ServerPlayer player, String key, long cooldown) {
        CompoundTag state = data(player);
        long last = state.getLong(key);
        long now = time(player);
        if (state.contains(key) && now >= last && now - last < cooldown) {
            long minutes = (cooldown - (now - last) + 1199) / 1200;
            say(player, "This service will be available again in about " + minutes + " minutes.");
            return false;
        }
        return true;
    }

    private static void stamp(ServerPlayer player, String key) {
        CompoundTag state = data(player);
        state.putLong(key, time(player));
        player.getPersistentData().put(PLAYER_DATA, state);
    }

    private static boolean has(ServerPlayer player, String id, int count) {
        Item item = item(id);
        if (item == null) {
            LazarusNpcs.LOGGER.error("Unknown NPC service item {}", id);
            return false;
        }
        int available = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) available += stack.getCount();
        }
        return available >= count;
    }

    private static void consume(ServerPlayer player, String id, int count) {
        Item item = item(id);
        int remaining = count;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(stack.getCount(), remaining);
            stack.shrink(taken);
            remaining -= taken;
        }
        player.getInventory().setChanged();
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && ForgeRegistries.ITEMS.containsKey(key) ? ForgeRegistries.ITEMS.getValue(key) : null;
    }

    private static String role(LivingEntity npc) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType());
        return id != null && "apocalypsenow".equals(id.getNamespace()) ? LazarusNpcs.role(id.getPath()) : null;
    }

    private static CompoundTag data(ServerPlayer player) {
        return player.getPersistentData().getCompound(PLAYER_DATA);
    }

    private static long time(ServerPlayer player) {
        return player.serverLevel().getServer().overworld().getGameTime();
    }

    private static void say(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
