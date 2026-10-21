package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import com.lazarus.npcs.api.event.NpcApiEvents;
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

    /** Negotiation skill Intimidação (Puffish Skills tag in the modpack): +15% to make looters and soldiers give up. */
    static final String TAG_INTIMIDATE = "lazarus_intimidar";

    static double chance(String kind, float healthFraction, int allies, int opponents, int losses, boolean intimidating) {
        double chance = chance(kind, healthFraction, allies, opponents, losses);
        return chance > 0 && intimidating ? Math.min(0.8, chance + 0.15) : chance;
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
        double chance = chance(kind, after / mob.getMaxHealth(), allies, opponents, root.getInt("Losses"),
                player.getTags().contains(TAG_INTIMIDATE));
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
        NpcReputation.adjust(player, "haven", 1); // the Haven hears that you spared a life
        release(mob);
    }

    /** Extra choices offered by other mods (interrogate, send to the refuge); interrogation happens once. */
    static java.util.Set<String> choices(ServerPlayer player, Mob mob) {
        String kind = HumanEncounters.kind(mob);
        if (kind == null || !canResolve(mob, player)) return java.util.Set.of();
        NpcApiEvents.PrisonerChoices event = new NpcApiEvents.PrisonerChoices(player, mob, kind);
        ApiBackend.post(event);
        java.util.Set<String> choices = new java.util.LinkedHashSet<>(event.getChoices());
        if (HumanEncounters.data(mob).getBoolean("Interrogated")) choices.remove(NpcApiEvents.PrisonerChoices.INTERROGATE);
        return choices;
    }

    static void interrogate(ServerPlayer player, Mob mob) {
        if (mob.distanceToSqr(player) > 64 || !choices(player, mob).contains(NpcApiEvents.PrisonerChoices.INTERROGATE)) return;
        CompoundTag root = HumanEncounters.data(mob);
        root.putBoolean("Interrogated", true);
        HumanEncounters.putData(mob, root);
        ApiBackend.post(new NpcApiEvents.PrisonerInterrogated(player, mob, HumanEncounters.kind(mob)));
    }

    /** The prisoner leaves for a new life elsewhere (another mod takes them in); they vanish from here. */
    static void sendAway(ServerPlayer player, Mob mob) {
        if (mob.distanceToSqr(player) > 64 || !choices(player, mob).contains(NpcApiEvents.PrisonerChoices.REFUGE)) return;
        ApiBackend.post(new NpcApiEvents.PrisonerSentAway(player, mob, HumanEncounters.kind(mob)));
        release(mob);
        mob.discard();
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
     * Apocalypse Now spawns human_flesh straight into the world when a human dies, outside the drop event and
     * sometimes a few ticks later. Apocalypse Now items appearing near a prisoner's death within two seconds are
     * refused as well.
     */
    @SubscribeEvent
    public static void itemJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (PRISONER_DEATHS.isEmpty() || !(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item)
                || event.getLevel().isClientSide()) return;
        var id = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
        if (id == null || !"apocalypsenow".equals(id.getNamespace())) return;
        long now = event.getLevel().getGameTime();
        PRISONER_DEATHS.removeIf(death -> now - death.time() > 40 || now < death.time());
        for (PrisonerDeath death : PRISONER_DEATHS)
            if (death.level() == event.getLevel() && item.position().distanceToSqr(death.pos()) < 16) {
                event.setCanceled(true);
                return;
            }
    }

    private record PrisonerDeath(net.minecraft.world.level.Level level, net.minecraft.world.phys.Vec3 pos, long time) {}

    private static final java.util.List<PrisonerDeath> PRISONER_DEATHS = new java.util.ArrayList<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void markDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !surrendered(event.getEntity())) return;
        PRISONER_DEATHS.add(new PrisonerDeath(event.getEntity().level(), event.getEntity().position(),
                event.getEntity().level().getGameTime()));
    }

    @SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) { PRISONER_DEATHS.clear(); }

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
