package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A badly hurt, outmatched looter or rogue soldier may give up. It is rolled once per NPC, only when a
 * player's side is fighting it, and a surrendered NPC never drops loot or XP afterwards.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class HumanSurrender {
    static final long DURATION = 1_200L;
    private static final long FLEE_LIMIT = 1_200L;

    private HumanSurrender() {}

    static boolean surrendered(LivingEntity mob) { return HumanEncounters.data(mob).getBoolean("Surrendered"); }

    /** True while the NPC is still holding still with its hands up. */
    static boolean holding(LivingEntity mob) {
        return surrendered(mob) && HumanEncounters.data(mob).getLong("SurrenderUntil") > mob.level().getGameTime();
    }

    static boolean canResolve(LivingEntity mob, ServerPlayer player) {
        CompoundTag root = HumanEncounters.data(mob);
        return holding(mob) && root.hasUUID("SurrenderTo") && player.getUUID().equals(root.getUUID("SurrenderTo"));
    }

    static double chance(String kind, float healthFraction, int allies, int opponents, int losses) {
        double base = switch (kind == null ? "" : kind) {
            case "looter" -> 0.45;
            case "soldier" -> 0.2; // disciplined; they hold out longer
            default -> 0;           // cannibals never give up
        };
        if (base == 0 || healthFraction > 0.35f || allies > opponents * 2) return 0;
        double chance = opponents >= allies ? base : base * 0.5;
        if (healthFraction <= 0.2f) chance += 0.15;
        if (losses > 0) chance += 0.1;
        return Math.min(0.8, chance);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void hurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        String kind = HumanEncounters.kind(mob);
        if (!"looter".equals(kind) && !"soldier".equals(kind) || surrendered(mob)) return;
        ServerPlayer player = playerSide(event.getSource().getEntity());
        CompoundTag root = HumanEncounters.data(mob);
        float after = mob.getHealth() - event.getAmount();
        if (player == null || root.getBoolean("SurrenderRolled") || after <= 0 || after > mob.getMaxHealth() * 0.35f) return;
        root.putBoolean("SurrenderRolled", true);
        HumanEncounters.putData(mob, root);
        int allies = (int) HumanEncounters.group(mob, kind, 16).stream().filter(other -> !surrendered(other)).count();
        int opponents = (int) mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(16),
                entity -> entity.isAlive() && (entity instanceof Player p && !p.isSpectator() && !p.isCreative()
                        || "military".equals(NpcIdentity.role(entity)) || NpcContracts.contract(entity) != null)).size();
        double chance = chance(kind, after / mob.getMaxHealth(), allies, opponents, root.getInt("Losses"));
        if (mob.getRandom().nextDouble() < chance) {
            event.setAmount(Math.min(event.getAmount(), Math.max(0, mob.getHealth() - 1)));
            surrender(mob, kind, player);
        }
    }

    static void surrender(Mob mob, String kind, ServerPlayer player) {
        long now = mob.level().getGameTime();
        CompoundTag root = HumanEncounters.data(mob);
        root.putBoolean("Surrendered", true);
        root.putBoolean("SurrenderRolled", true);
        root.putLong("SurrenderUntil", now + DURATION);
        root.putUUID("SurrenderTo", player.getUUID());
        HumanEncounters.putData(mob, root);
        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // weapon thrown down, not dropped
        mob.setCustomName(Component.literal("Surrendered " + ("soldier".equals(kind) ? "soldier" : "looter")));
        mob.setCustomNameVisible(true);
        for (Mob ally : HumanEncounters.group(mob, kind, 16)) {
            if (ally == mob) continue;
            CompoundTag allyRoot = HumanEncounters.data(ally);
            allyRoot.putInt("Losses", allyRoot.getInt("Losses") + 1); // a surrender hurts group morale
            HumanEncounters.putData(ally, allyRoot);
        }
        for (Player nearby : mob.level().players())
            if (nearby.distanceToSqr(mob) < 24 * 24)
                nearby.sendSystemMessage(Component.literal("A " + ("soldier".equals(kind) ? "rogue soldier" : "looter")
                        + " throws down their weapon and surrenders to " + player.getGameProfile().getName()
                        + ". They will wait one minute.").withStyle(style -> style.withColor(0xD4C068)));
    }

    /** The capturer takes a modest, fixed bundle (less than a lucky kill) and lets them go. */
    static void strip(ServerPlayer player, Mob mob) {
        if (!canResolve(mob, player) || mob.distanceToSqr(player) > 64) return;
        String kind = HumanEncounters.kind(mob);
        give(player, "apocalypsenow:canned_food", 1);
        if ("soldier".equals(kind)) give(player, "minecraft:iron_ingot", 2);
        else if (mob.getRandom().nextBoolean()) give(player, "apocalypsenow:bandage", 1);
        player.sendSystemMessage(Component.literal("You take what they carry and send them away."));
        release(mob);
    }

    static void letGo(ServerPlayer player, Mob mob) {
        if (!canResolve(mob, player) || mob.distanceToSqr(player) > 64) return;
        player.sendSystemMessage(Component.literal("You let them go. They will not come back for you."));
        release(mob);
    }

    private static void release(Mob mob) {
        CompoundTag root = HumanEncounters.data(mob);
        root.putLong("SurrenderUntil", mob.level().getGameTime());
        HumanEncounters.putData(mob, root);
    }

    /** Called by HumanTactics every 10 ticks. Returns true when it handled the NPC. */
    static boolean tick(Mob mob, HumanTactics.OrderGoal order, long now) {
        if (!surrendered(mob)) return false;
        if (mob.getTarget() != null) mob.setTarget(null);
        CompoundTag root = HumanEncounters.data(mob);
        long until = root.getLong("SurrenderUntil");
        if (now < until) {
            order.hold(20);
            return true;
        }
        Player nearest = mob.level().getNearestPlayer(mob, 32);
        if (nearest == null && now - until > 100 || now - until > FLEE_LIMIT) {
            mob.discard(); // walked off; nothing is left behind to farm
            return true;
        }
        if (nearest != null) order.moveAway(nearest.position(), 14, 1.1, 30);
        return true;
    }

    @SubscribeEvent
    public static void target(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Mob mob && !mob.level().isClientSide() && event.getNewTarget() != null
                && surrendered(mob)) event.setNewTarget(null);
    }

    @SubscribeEvent
    public static void drops(LivingDropsEvent event) {
        if (surrendered(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void experience(LivingExperienceDropEvent event) {
        if (surrendered(event.getEntity())) event.setDroppedExperience(0);
    }

    /**
     * Apocalypse Now spawns human_flesh straight into the world when a human dies, outside the drop event,
     * so items appearing at a prisoner's death spot on the same tick are refused as well.
     */
    @SubscribeEvent
    public static void itemJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (lastDeathTime < 0 || !(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item)
                || event.getLevel().isClientSide() || event.getLevel() != lastDeathLevel) return;
        if (event.getLevel().getGameTime() - lastDeathTime <= 1 && item.position().distanceToSqr(lastDeathPos) < 9)
            event.setCanceled(true);
    }

    private static long lastDeathTime = -1;
    private static net.minecraft.world.level.Level lastDeathLevel;
    private static net.minecraft.world.phys.Vec3 lastDeathPos;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void markDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !surrendered(event.getEntity())) return;
        lastDeathTime = event.getEntity().level().getGameTime();
        lastDeathLevel = event.getEntity().level();
        lastDeathPos = event.getEntity().position();
    }

    @SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        lastDeathTime = -1;
        lastDeathLevel = null;
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !surrendered(event.getEntity())) return;
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            NpcReputation.adjust(killer, "wardens", -3);
            killer.sendSystemMessage(Component.literal("Killing a prisoner yields nothing. The Wardens will hear of it (-3)."));
        }
    }

    private static ServerPlayer playerSide(net.minecraft.world.entity.Entity source) {
        if (source instanceof ServerPlayer player) return player;
        if (source instanceof LivingEntity npc) {
            NpcContracts.Contract c = NpcContracts.contract(npc);
            if (c != null && npc.getServer() != null) return npc.getServer().getPlayerList().getPlayer(c.owner);
        }
        return null;
    }

    private static void give(ServerPlayer player, String id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null) return;
        ItemStack stack = new ItemStack(item, count);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }
}
