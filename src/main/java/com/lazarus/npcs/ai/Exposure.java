package com.lazarus.npcs.ai;

import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Counts threats with a clear ray to an actor; results are reused for ten ticks. */
public final class Exposure {
    private record Entry(long tick, int signature, int count) {}
    private static final WeakHashMap<Mob, Entry> CACHE = new WeakHashMap<>();

    private Exposure() {}

    public static boolean visible(LivingEntity threat, Vec3 head) {
        return threat.level().clip(new ClipContext(threat.getEyePosition(), head,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, threat)).getType() != HitResult.Type.BLOCK;
    }

    public static int count(Mob actor, List<? extends LivingEntity> threats) {
        int signature = 1;
        for (LivingEntity threat : threats) {
            signature = 31 * signature + threat.getUUID().hashCode();
            signature = 31 * signature + threat.blockPosition().hashCode();
        }
        long tick = actor.level().getGameTime();
        Entry cached = CACHE.get(actor);
        if (cached != null && tick >= cached.tick && tick - cached.tick < 10 && cached.signature == signature)
            return cached.count;
        int count = 0;
        for (LivingEntity threat : threats)
            if (threat.isAlive() && threat.level() == actor.level() && visible(threat, actor.getEyePosition())) count++;
        CACHE.put(actor, new Entry(tick, signature, count));
        return count;
    }
}
