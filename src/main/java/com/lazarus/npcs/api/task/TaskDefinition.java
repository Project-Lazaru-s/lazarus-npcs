package com.lazarus.npcs.api.task;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An encounter task type. Rewards are shown before the player accepts and paid exactly once on completion;
 * items that do not fit in the inventory are dropped at the player's feet.
 *
 * @param id               namespaced, e.g. {@code lazarusevents:rescue_survivor}
 * @param title            journal and offer title
 * @param description      one or two sentences shown in the offer and the journal
 * @param objective        what has to be done
 * @param deadlineTicks    world ticks after acceptance, 1 200 to 168 000
 * @param onPlayerDeath    {@link DeathPolicy#FAIL} or {@link DeathPolicy#PERSIST}
 * @param failIfGiverDies  fail when the giving NPC dies (protect and escort tasks)
 * @param turnIn           when true, a finished objective waits in {@link TaskState#READY} until the player
 *                         reports to the giver
 * @param rewardItems      paid on completion (max 6 stacks)
 * @param rewardFaction    haven, rovers, wardens or military, or null for none
 * @param rewardReputation 0 to 10
 */
public record TaskDefinition(ResourceLocation id, Component title, Component description, TaskObjective objective,
                             long deadlineTicks, DeathPolicy onPlayerDeath, boolean failIfGiverDies, boolean turnIn,
                             List<ItemStack> rewardItems, @Nullable String rewardFaction, int rewardReputation) {
    public enum DeathPolicy { FAIL, PERSIST }
}
