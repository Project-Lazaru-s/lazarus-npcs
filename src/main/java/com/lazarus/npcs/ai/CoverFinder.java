package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcPerf;
import com.lazarus.npcs.api.ai.Doctrine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Finds reachable-looking cover near the actor without loading chunks or stepping onto hazards. */
public final class CoverFinder {
    private record Candidate(BlockPos pos, double score) {}
    private record Entry(long tick, BlockPos actor, int threats, Vec3 destination) {}
    private static final WeakHashMap<Mob, Entry> CACHE = new WeakHashMap<>();

    private CoverFinder() {}

    public static Vec3 find(Mob actor, List<? extends LivingEntity> threats, List<? extends LivingEntity> allies,
                            Doctrine doctrine) {
        if (threats == null || threats.isEmpty() || doctrine == null || Lod.band(actor) != Lod.Band.FULL) return null;
        long now = actor.level().getGameTime();
        int signature = signature(threats, doctrine);
        Entry cached = CACHE.get(actor);
        if (cached != null && now >= cached.tick && now - cached.tick < 60 && cached.threats == signature
                && cached.actor.distSqr(actor.blockPosition()) <= 16) {
            if (cached.destination == null) return null;
            BlockPos pos = BlockPos.containing(cached.destination);
            if (PointPicker.reserve(actor, pos, 70)) return cached.destination;
        }
        if (!Budget.cover((net.minecraft.server.level.ServerLevel) actor.level())) return null;
        long start = System.nanoTime();
        List<Candidate> candidates = new ArrayList<>();
        for (int radius = 3; radius <= 9; radius += 2) {
            for (int step = 0; step < 16; step++) {
                double angle = step * Math.PI / 8;
                BlockPos target = BlockPos.containing(actor.getX() + Math.cos(angle) * radius,
                        actor.getY(), actor.getZ() + Math.sin(angle) * radius);
                BlockPos pos = standableNearby(actor, target);
                if (pos == null || !PointPicker.available(actor, pos)) continue;
                Vec3 feet = Vec3.atBottomCenterOf(pos);
                Vec3 head = feet.add(0, actor.getEyeHeight(), 0);
                int exposed = 0, covered = 0;
                double rangePenalty = 0;
                for (LivingEntity threat : threats) {
                    if (threat == null || !threat.isAlive() || threat.level() != actor.level()) continue;
                    if (Exposure.visible(threat, head)) exposed++;
                    else covered++;
                    rangePenalty += Math.abs(feet.distanceTo(threat.position()) - doctrine.preferredRange());
                }
                if (covered == 0) continue;
                double crowding = 0;
                if (allies != null) for (LivingEntity ally : allies)
                    if (ally != actor && ally.isAlive() && ally.distanceToSqr(feet) < 9) crowding += 20;
                double score = exposed * 100 + rangePenalty * 2 + feet.distanceTo(actor.position()) * 0.5 + crowding;
                candidates.add(new Candidate(pos, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        Vec3 best = null;
        for (Candidate candidate : candidates)
            if (PointPicker.reserve(actor, candidate.pos(), 70)) {
                best = Vec3.atBottomCenterOf(candidate.pos());
                break;
            }
        CACHE.put(actor, new Entry(now, actor.blockPosition(), signature, best));
        NpcPerf.record("AI cover search", System.nanoTime() - start);
        return best;
    }

    private static BlockPos standableNearby(Mob actor, BlockPos target) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos pos = target.above(dy);
            if (PointPicker.standable(actor, pos)) return pos;
        }
        return null;
    }

    private static int signature(List<? extends LivingEntity> threats, Doctrine doctrine) {
        int signature = doctrine.hashCode();
        for (LivingEntity threat : threats) {
            if (threat == null) continue;
            signature = 31 * signature + threat.getUUID().hashCode();
            signature = 31 * signature + threat.blockPosition().hashCode();
        }
        return signature;
    }
}
