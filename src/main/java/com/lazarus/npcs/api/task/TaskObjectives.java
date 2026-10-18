package com.lazarus.npcs.api.task;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Built-in objectives. Each keeps its state in context counters: {@code delivered}, {@code kills}, {@code stage}
 * or the key you pass to {@link #counter}. Do not combine two objectives using the same counter in one task.
 */
public final class TaskObjectives {
    private TaskObjectives() {}

    /** Hand the items to the giver (talk to them). Item NBT is ignored; matching is by item. */
    public static TaskObjective deliver(List<ItemStack> items) {
        List<ItemStack> wanted = items.stream().map(ItemStack::copy).toList();
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) { return c.counter("delivered") > 0; }

            @Override public Component progress(TaskContext c) {
                if (complete(c)) return Component.literal("Supplies delivered");
                StringBuilder text = new StringBuilder("Bring to the giver: ");
                for (int i = 0; i < wanted.size(); i++) {
                    ItemStack stack = wanted.get(i);
                    if (i > 0) text.append(", ");
                    text.append(Math.min(count(c, stack.getItem()), stack.getCount())).append('/').append(stack.getCount())
                            .append(' ').append(stack.getHoverName().getString());
                }
                return Component.literal(text.toString());
            }

            @Override public boolean canDeliver(TaskContext c) {
                return !complete(c) && wanted.stream().allMatch(stack -> count(c, stack.getItem()) >= stack.getCount());
            }

            @Override public boolean deliver(TaskContext c) {
                if (!canDeliver(c)) return false;
                for (ItemStack stack : wanted) take(c, stack.getItem(), stack.getCount());
                c.addCounter("delivered", 1);
                return true;
            }
        };
    }

    /** The player reaches the task destination. */
    public static TaskObjective reach(int radius) {
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) {
                return c.destination().filter(d -> d.dimension() == c.player().level().dimension()
                        && c.player().blockPosition().closerThan(d.pos(), radius)).isPresent();
            }

            @Override public Component progress(TaskContext c) {
                return Component.literal(c.destination().map(d -> "Reach " + d.pos().getX() + ", " + d.pos().getY()
                        + ", " + d.pos().getZ()).orElse("Reach the destination"));
            }
        };
    }

    /** Keep the giver alive for the given time (pair with {@code failIfGiverDies}). */
    public static TaskObjective protect(long ticks) {
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) { return c.elapsedTicks() >= ticks && c.giver().isPresent(); }

            @Override public Component progress(TaskContext c) {
                long left = Math.max(0, ticks - c.elapsedTicks());
                return Component.literal("Keep them alive: " + (left + 1199) / 1200 + " min left");
            }
        };
    }

    /** Bring the giver to the destination (they must be loaded and within the radius). */
    public static TaskObjective escort(int radius) {
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) {
                return c.destination().isPresent() && c.giver().filter(g -> g.level().dimension() == c.destination().get().dimension()
                        && g.blockPosition().closerThan(c.destination().get().pos(), radius)).isPresent();
            }

            @Override public Component progress(TaskContext c) {
                return Component.literal(c.destination().map(d -> "Escort them to " + d.pos().getX() + ", " + d.pos().getY()
                        + ", " + d.pos().getZ()).orElse("Escort them to safety"));
            }
        };
    }

    /** Kill {@code count} entities accepted by the filter (it receives the victim and the task's encounter id). */
    public static TaskObjective kill(int count, BiPredicate<LivingEntity, UUID> filter, Component what) {
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) { return c.counter("kills") >= count; }

            @Override public void killed(TaskContext c, LivingEntity victim) {
                if (!complete(c) && filter.test(victim, c.encounterId())) c.addCounter("kills", 1);
            }

            @Override public Component progress(TaskContext c) {
                return Component.literal(what.getString() + ": " + Math.min(count, c.counter("kills")) + "/" + count);
            }
        };
    }

    /** Advanced only through {@code NpcApi.progressTask(player, instance, key, amount)}. */
    public static TaskObjective counter(String key, int target, Component what) {
        return new TaskObjective() {
            @Override public boolean complete(TaskContext c) { return c.counter(key) >= target; }

            @Override public Component progress(TaskContext c) {
                return Component.literal(what.getString() + ": " + Math.min(target, c.counter(key)) + "/" + target);
            }
        };
    }

    /**
     * Steps done in order. The {@code stage} counter holds the current step (0-based) and fires a progress
     * event when it advances, so the integrator can react (e.g. make a rescued NPC start following).
     */
    public static TaskObjective sequence(TaskObjective... steps) {
        List<TaskObjective> list = List.of(steps);
        return new TaskObjective() {
            private TaskObjective current(TaskContext c) { return list.get(Math.min(c.counter("stage"), list.size() - 1)); }

            private void advance(TaskContext c) {
                while (c.counter("stage") < list.size() && list.get(c.counter("stage")).complete(c)) c.addCounter("stage", 1);
            }

            @Override public boolean complete(TaskContext c) {
                advance(c);
                return c.counter("stage") >= list.size();
            }

            @Override public void tick(TaskContext c) {
                if (c.counter("stage") < list.size()) current(c).tick(c);
            }

            @Override public void killed(TaskContext c, LivingEntity victim) {
                if (c.counter("stage") < list.size()) current(c).killed(c, victim);
            }

            @Override public boolean canDeliver(TaskContext c) {
                return c.counter("stage") < list.size() && current(c).canDeliver(c);
            }

            @Override public boolean deliver(TaskContext c) {
                boolean delivered = c.counter("stage") < list.size() && current(c).deliver(c);
                if (delivered) advance(c);
                return delivered;
            }

            @Override public Component progress(TaskContext c) {
                int stage = Math.min(c.counter("stage"), list.size() - 1);
                return Component.literal("Step " + (stage + 1) + "/" + list.size() + ": ")
                        .append(list.get(stage).progress(c));
            }
        };
    }

    private static int count(TaskContext c, Item item) {
        int total = 0;
        var inventory = c.player().getInventory();
        for (int slot = 0; slot < 36; slot++) if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        return total;
    }

    private static void take(TaskContext c, Item item, int count) {
        var inventory = c.player().getInventory();
        for (int slot = 0; slot < 36 && count > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(count, stack.getCount());
            stack.shrink(taken);
            count -= taken;
        }
        inventory.setChanged();
    }
}
