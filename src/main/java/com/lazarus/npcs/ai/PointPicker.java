package com.lazarus.npcs.ai;

import com.lazarus.npcs.NpcHazards;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.phys.AABB;

/** Shared short-lived reservations for cover, work, shelter and other NPC destinations. */
public final class PointPicker {
    private record Reservation(UUID actor, long expires) {}
    private static final WeakHashMap<ServerLevel, Map<BlockPos, Reservation>> RESERVED = new WeakHashMap<>();

    private PointPicker() {}

    public static boolean standable(Mob actor, BlockPos pos) {
        if (!(actor.level() instanceof ServerLevel level) || !level.isLoaded(pos)) return false;
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()
                && NpcHazards.pathType(level, pos, actor, BlockPathTypes.WALKABLE) == BlockPathTypes.WALKABLE;
    }

    public static boolean available(Mob actor, BlockPos pos) {
        if (!(actor.level() instanceof ServerLevel level) || !standable(actor, pos)) return false;
        Reservation reservation = RESERVED.computeIfAbsent(level, ignored -> new HashMap<>()).get(pos);
        return reservation == null || reservation.expires <= level.getGameTime()
                || reservation.actor.equals(actor.getUUID());
    }

    public static boolean reserve(Mob actor, BlockPos pos, int ticks) {
        if (!(actor.level() instanceof ServerLevel level) || ticks < 1 || !available(actor, pos)) return false;
        if (!level.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(0.4, 0, 0.4),
                other -> other != actor && other.isAlive()).isEmpty()) return false;
        Map<BlockPos, Reservation> positions = RESERVED.computeIfAbsent(level, ignored -> new HashMap<>());
        long now = level.getGameTime();
        positions.entrySet().removeIf(entry -> entry.getValue().expires <= now
                || entry.getValue().actor.equals(actor.getUUID()));
        positions.put(pos.immutable(), new Reservation(actor.getUUID(), now + ticks));
        return true;
    }

    public static Optional<BlockPos> pick(Mob actor, List<BlockPos> candidates, int ticks) {
        return candidates.stream().filter(pos -> pos != null && available(actor, pos))
                .sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(actor.blockPosition())))
                .filter(pos -> reserve(actor, pos, ticks)).findFirst();
    }

    public static void release(Mob actor) {
        if (!(actor.level() instanceof ServerLevel level)) return;
        Map<BlockPos, Reservation> positions = RESERVED.get(level);
        if (positions != null) positions.entrySet().removeIf(entry -> entry.getValue().actor.equals(actor.getUUID()));
    }
}
