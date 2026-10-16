package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Friendly NPCs fight back when attacked, and a faction turns on a player whose standing sinks to
 * ENEMY or below. Apocalypse Now military and medics have no retaliation or player targeting at all;
 * survivors only have a plain hurt-by-target goal.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcHostility {
    static final int ENEMY = -40;
    static final long AGGRO_TICKS = 1_200L;
    private static final long FRIENDLY_FIRE_WINDOW = 600L;
    private static final String KEY = "LazarusNpcsAggro";

    private NpcHostility() {}

    static boolean isEnemyStanding(int reputation) { return reputation <= ENEMY; }

    static boolean enemy(ServerPlayer player, String faction) {
        return faction != null && !"independent".equals(faction) && isEnemyStanding(NpcReputation.get(player, faction));
    }

    /** True when this NPC should fight this player: recently attacked by them, or their faction's enemy. */
    static boolean hostileTo(LivingEntity npc, Player candidate) {
        if (!(candidate instanceof ServerPlayer player) || player.isCreative() || player.isSpectator() || !player.isAlive())
            return false;
        if (aggroUntil(npc, player) > npc.level().getGameTime()) return true;
        return NpcIdentity.role(npc) != null && enemy(player, NpcIdentity.faction(npc));
    }

    static long aggroUntil(LivingEntity npc, Player player) {
        return npc.getPersistentData().getCompound(KEY).getLong(player.getUUID().toString());
    }

    private static void aggro(LivingEntity npc, Player player, long until) {
        CompoundTag tag = npc.getPersistentData().getCompound(KEY);
        tag.putLong(player.getUUID().toString(), until);
        npc.getPersistentData().put(KEY, tag);
    }

    /** Called from NpcReputation whenever a standing changes, so the player always knows where they stand. */
    static void standingChanged(ServerPlayer player, String faction, int before, int after) {
        if (isEnemyStanding(after) && !isEnemyStanding(before)) {
            player.sendSystemMessage(Component.literal(NpcIdentity.label(faction)
                    + " now treat you as an enemy and will attack on sight.").withStyle(style -> style.withColor(0xD46868)));
            NpcContracts.endForFaction(player, faction);
        } else if (!isEnemyStanding(after) && isEnemyStanding(before)) {
            player.sendSystemMessage(Component.literal(NpcIdentity.label(faction)
                    + " no longer shoot on sight, but they still will not deal with you.").withStyle(style -> style.withColor(0xD4A185)));
        }
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        String role = NpcIdentity.role(mob);
        if (role == null || "hunter".equals(role)) return; // hunters are independent and keep their own retaliation
        mob.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(mob, Player.class, 10, true, false,
                target -> target instanceof Player player && hostileTo(mob, player)));
    }

    /** A hit makes the NPC and its faction mates within 16 blocks fight back for a minute. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void hurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Mob npc) || npc.level().isClientSide()
                || !(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        String role = NpcIdentity.role(npc);
        if (role == null || "hunter".equals(role) || player.isCreative()) return;
        long now = npc.level().getGameTime();
        NpcContracts.Contract contract = NpcContracts.contract(npc);
        if (contract != null && contract.owner.equals(player.getUUID())) {
            CompoundTag tag = npc.getPersistentData().getCompound(KEY);
            if (now - tag.getLong("FriendlyFire") > FRIENDLY_FIRE_WINDOW) {
                tag.putLong("FriendlyFire", now);
                npc.getPersistentData().put(KEY, tag);
                player.displayClientMessage(Component.literal(contract.npcName + ": \"Watch your fire!\""), true);
                return; // one stray hit is forgiven
            }
            NpcContracts.end(player.getServer(), contract, contract.npcName + " turns on you. The contract is over.");
        }
        String faction = NpcIdentity.faction(npc);
        java.util.List<Mob> allies = new java.util.ArrayList<>(npc.level().getEntitiesOfClass(Mob.class, npc.getBoundingBox().inflate(16),
                other -> other != npc && other.isAlive() && NpcIdentity.role(other) != null && !"hunter".equals(NpcIdentity.role(other))
                        && faction.equals(NpcIdentity.faction(other))));
        allies.add(npc); // the section query can miss the victim itself near unloaded chunk borders
        for (Mob ally : allies) {
            NpcContracts.Contract allyContract = NpcContracts.contract(ally);
            if (allyContract != null && allyContract.owner.equals(player.getUUID()) && ally != npc) continue;
            aggro(ally, player, now + AGGRO_TICKS);
            if (ally.getTarget() == null || !(ally.getTarget() instanceof Player)) ally.setTarget(player);
        }
    }

    /** Last word on player targets for faction NPCs: only hostile players may be targeted. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void target(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob npc) || npc.level().isClientSide()
                || !(event.getNewTarget() instanceof Player player)) return;
        String role = NpcIdentity.role(npc);
        if (role == null || "hunter".equals(role)) return;
        if (!hostileTo(npc, player)) event.setNewTarget(null);
    }

    /** Drops a player target once the grudge has passed and the player is not a faction enemy. */
    @SubscribeEvent
    public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob npc) || npc.level().isClientSide() || npc.tickCount % 20 != 5
                || !(npc.getTarget() instanceof Player player)) return;
        String role = NpcIdentity.role(npc);
        if (role != null && !"hunter".equals(role) && (!hostileTo(npc, player) || npc.distanceToSqr(player) > 48 * 48))
            npc.setTarget(null);
    }
}
