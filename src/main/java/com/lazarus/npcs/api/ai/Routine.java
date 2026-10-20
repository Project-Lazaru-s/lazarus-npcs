package com.lazarus.npcs.api.ai;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Transient base schedule. The owner mod resends it after a chunk load or order renewal. */
public record Routine(UUID baseId, List<Activity> activities, NightDuty night,
                      @Nullable BlockPos bed, BlockPos shelter, BlockPos defensePost) {
    public Routine {
        if (baseId == null || activities == null || activities.isEmpty() || activities.size() > 8
                || night == null || shelter == null || defensePost == null ||
                (night == NightDuty.SLEEP && bed == null))
            throw new IllegalArgumentException("Invalid NPC routine");
        activities = List.copyOf(activities);
    }
}
