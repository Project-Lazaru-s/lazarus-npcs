package com.lazarus.npcs.ai;

import com.lazarus.npcs.LazarusNpcs;
import com.lazarus.npcs.NpcAiHooks;
import com.lazarus.npcs.NpcPerf;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opportunistic search of untouched structure loot, with bounded scanning and exclusive reservations. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class ScavengeGoal extends Goal {
    private record Claim(UUID actor, long until) {}
    private static final Map<GlobalPos, Claim> CLAIMS = new HashMap<>();
    private final Mob actor;
    private BlockPos target;
    private long nextAttempt, searchUntil;
    private int repath;

    private ScavengeGoal(Mob actor) { this.actor = actor; setFlags(EnumSet.of(Flag.MOVE)); }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob actor)
                || !NpcAiHooks.canScavenge(actor)) return;
        for (WrappedGoal goal : actor.goalSelector.getAvailableGoals())
            if (goal.getGoal() instanceof ScavengeGoal) return;
        actor.goalSelector.addGoal(1, new ScavengeGoal(actor));
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { CLAIMS.clear(); }

    private boolean eligible() {
        if (!(actor.level() instanceof ServerLevel level) || !NpcAiHooks.scavengingEnabled()
                || !NpcAiHooks.canScavenge(actor) || Pack.items(actor).size() >= NpcAiHooks.packLimit()
                || Lod.band(actor) != Lod.Band.FULL) return false;
        return true;
    }

    @Override public boolean canUse() {
        if (!eligible() || actor.level().getGameTime() < nextAttempt) return false;
        long start = System.nanoTime();
        try { target = find(); }
        finally { NpcPerf.record("scavenge search", System.nanoTime() - start); }
        // A miss retries soon (a budget denial or a passing zombie is temporary); a find waits the full interval.
        nextAttempt = actor.level().getGameTime() + (target == null ? 200 + actor.getRandom().nextInt(100)
                : NpcAiHooks.scavengeInterval() + actor.getRandom().nextInt(20 * 30));
        return target != null;
    }

    @Override public boolean canContinueToUse() {
        return target != null && eligible() && actor.level().getGameTime() < searchUntil
                && untouched((ServerLevel) actor.level(), target);
    }

    @Override public void start() { searchUntil = actor.level().getGameTime() + 500; repath = 0; }

    @Override public void stop() {
        if (target != null) {
            GlobalPos key = GlobalPos.of(actor.level().dimension(), target);
            Claim claim = CLAIMS.get(key);
            if (claim != null && claim.actor().equals(actor.getUUID())) CLAIMS.remove(key);
        }
        target = null;
        actor.getNavigation().stop();
    }

    @Override public void tick() {
        if (target == null || !(actor.level() instanceof ServerLevel level)) return;
        if (actor.distanceToSqr(target.getX() + .5, target.getY() + .5, target.getZ() + .5) < 9) {
            actor.getNavigation().stop();
            actor.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
            if (searchUntil - level.getGameTime() > 100) searchUntil = level.getGameTime() + 100;
            if (level.getGameTime() >= searchUntil - 2) { take(level, target); stop(); }
            return;
        }
        if (repath-- > 0 || !Budget.path(level)) return;
        repath = 20;
        actor.getNavigation().moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, 0.9);
    }

    private BlockPos find() {
        ServerLevel level = (ServerLevel) actor.level();
        if (!Budget.container(level)) return null;
        int radius = NpcAiHooks.scavengeRadius();
        BlockPos origin = actor.blockPosition(), best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int cx = (origin.getX() - radius) >> 4; cx <= (origin.getX() + radius) >> 4; cx++)
            for (int cz = (origin.getZ() - radius) >> 4; cz <= (origin.getZ() + radius) >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity block : chunk.getBlockEntities().values()) {
                    if (!(block instanceof RandomizableContainerBlockEntity)) continue;
                    BlockPos pos = block.getBlockPos();
                    double distance = pos.distSqr(origin);
                    if (distance >= bestDistance || distance > radius * radius || !untouched(level, pos)) continue;
                    best = pos;
                    bestDistance = distance;
                }
            }
        if (best != null) CLAIMS.put(GlobalPos.of(level.dimension(), best),
                new Claim(actor.getUUID(), level.getGameTime() + 520));
        return best;
    }

    private boolean untouched(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.above())) return false;
        BlockEntity block = level.getBlockEntity(pos);
        if (!(block instanceof RandomizableContainerBlockEntity container)
                || !block.saveWithoutMetadata().contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)
                || block.getPersistentData().getBoolean("LazarusNpcNoScavenge")) return false;
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        Claim claim = CLAIMS.get(key);
        if (claim != null && !claim.actor().equals(actor.getUUID()) && claim.until() > level.getGameTime()) return false;
        // Never at the container a player is using, or near a player's home; watching from nearby is fine.
        if (level.players().stream().anyMatch(player -> player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 4 * 4
                || player.getRespawnPosition() != null && player.getRespawnPosition().distSqr(pos) < 16 * 16)) return false;
        if (level.getSharedSpawnPos().distSqr(pos) < 16 * 16) return false;
        if (level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(6), NpcAiHooks::infected).size() > 0)
            return false;
        return true;
    }

    private void take(ServerLevel level, BlockPos pos) {
        if (!untouched(level, pos) || !(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container))
            return;
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.4f, 1f);
        // First access unpacks the LootTable exactly once; subsequent NPCs see no LootTable and leave it alone.
        container.getItem(0);
        int remaining = Math.min(1 + actor.getRandom().nextInt(2), NpcAiHooks.packLimit() - Pack.items(actor).size());
        int first = actor.getRandom().nextInt(container.getContainerSize());
        for (int n = 0; n < container.getContainerSize() && remaining > 0; n++) {
            int slot = (first + n) % container.getContainerSize();
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            Pack.add(actor, stack.copy());
            container.setItem(slot, ItemStack.EMPTY);
            remaining--;
        }
        container.setChanged();
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 0);
    }
}
