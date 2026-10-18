package com.lazarus.npcs.api.event;

import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Events posted on {@code MinecraftForge.EVENT_BUS}, on the server thread, after the change is confirmed.
 * All are observational (not cancelable). Handlers may call the API again; nesting deeper than four
 * posts is dropped and logged, so a handler cannot loop forever.
 */
public final class NpcApiEvents {
    private NpcApiEvents() {}

    /** Base for scenario events: the NPC and the assignment it had. */
    public abstract static class Scenario extends Event {
        private final LivingEntity npc;
        private final ScenarioAssignment assignment;

        protected Scenario(LivingEntity npc, ScenarioAssignment assignment) {
            this.npc = npc;
            this.assignment = assignment;
        }

        public LivingEntity getNpc() { return npc; }
        public ScenarioAssignment getAssignment() { return assignment; }
    }

    /** An order was assigned (also when it replaced an older one). */
    public static final class ScenarioStarted extends Scenario {
        public ScenarioStarted(LivingEntity npc, ScenarioAssignment assignment) { super(npc, assignment); }
    }

    /** An order ended. */
    public static final class ScenarioEnded extends Scenario {
        public enum Reason { CLEARED, EXPIRED, REPLACED, DIED, REMOVED, OWNER_MISSING }

        private final Reason reason;

        public ScenarioEnded(LivingEntity npc, ScenarioAssignment assignment, Reason reason) {
            super(npc, assignment);
            this.reason = reason;
        }

        public Reason getReason() { return reason; }
    }

    /** An NPC under an order was hurt. */
    public static final class ScenarioActorHurt extends Scenario {
        private final DamageSource source;
        private final float amount;

        public ScenarioActorHurt(LivingEntity npc, ScenarioAssignment assignment, DamageSource source, float amount) {
            super(npc, assignment);
            this.source = source;
            this.amount = amount;
        }

        public DamageSource getSource() { return source; }
        public float getAmount() { return amount; }
    }

    /** An NPC under an order died; a {@link ScenarioEnded} with {@code DIED} follows. */
    public static final class ScenarioActorDied extends Scenario {
        private final DamageSource source;

        public ScenarioActorDied(LivingEntity npc, ScenarioAssignment assignment, DamageSource source) {
            super(npc, assignment);
            this.source = source;
        }

        public DamageSource getSource() { return source; }
    }

    /** A player picked a dialogue option and its consequence ran. */
    public static final class DialogueChoice extends Event {
        private final ServerPlayer player;
        private final LivingEntity npc;
        private final ResourceLocation dialogueId;
        private final String optionId;
        private final @Nullable UUID encounterId;

        public DialogueChoice(ServerPlayer player, LivingEntity npc, ResourceLocation dialogueId, String optionId,
                              @Nullable UUID encounterId) {
            this.player = player;
            this.npc = npc;
            this.dialogueId = dialogueId;
            this.optionId = optionId;
            this.encounterId = encounterId;
        }

        public ServerPlayer getPlayer() { return player; }
        public LivingEntity getNpc() { return npc; }
        public ResourceLocation getDialogueId() { return dialogueId; }
        public String getOptionId() { return optionId; }
        public @Nullable UUID getEncounterId() { return encounterId; }
    }

    /** An encounter task changed state. */
    public static final class TaskStateChanged extends Event {
        private final ServerPlayer player;
        private final UUID instanceId;
        private final ResourceLocation taskId;
        private final UUID encounterId;
        private final @Nullable TaskState from;
        private final TaskState to;
        private final String reason;

        public TaskStateChanged(ServerPlayer player, UUID instanceId, ResourceLocation taskId, UUID encounterId,
                                @Nullable TaskState from, TaskState to, String reason) {
            this.player = player;
            this.instanceId = instanceId;
            this.taskId = taskId;
            this.encounterId = encounterId;
            this.from = from;
            this.to = to;
            this.reason = reason;
        }

        public ServerPlayer getPlayer() { return player; }
        public UUID getInstanceId() { return instanceId; }
        public ResourceLocation getTaskId() { return taskId; }
        public UUID getEncounterId() { return encounterId; }
        public @Nullable TaskState getFrom() { return from; }
        public TaskState getTo() { return to; }
        /** offered, accepted, declined, abandoned, completed, giver_died, player_died, expired, or the integrator's outcome. */
        public String getReason() { return reason; }
    }

    /** A task counter changed (kills, delivered, stage, or a custom key). */
    public static final class TaskProgress extends Event {
        private final ServerPlayer player;
        private final UUID instanceId;
        private final ResourceLocation taskId;
        private final UUID encounterId;
        private final String key;
        private final int value;

        public TaskProgress(ServerPlayer player, UUID instanceId, ResourceLocation taskId, UUID encounterId, String key, int value) {
            this.player = player;
            this.instanceId = instanceId;
            this.taskId = taskId;
            this.encounterId = encounterId;
            this.key = key;
            this.value = value;
        }

        public ServerPlayer getPlayer() { return player; }
        public UUID getInstanceId() { return instanceId; }
        public ResourceLocation getTaskId() { return taskId; }
        public UUID getEncounterId() { return encounterId; }
        public String getKey() { return key; }
        public int getValue() { return value; }
    }

    /** A faction standing changed, from any source. {@code reason} is null for Lazarus NPCs' own rules. */
    public static final class ReputationChanged extends Event {
        private final ServerPlayer player;
        private final String faction;
        private final int before;
        private final int after;
        private final @Nullable ResourceLocation reason;

        public ReputationChanged(ServerPlayer player, String faction, int before, int after, @Nullable ResourceLocation reason) {
            this.player = player;
            this.faction = faction;
            this.before = before;
            this.after = after;
            this.reason = reason;
        }

        public ServerPlayer getPlayer() { return player; }
        public String getFaction() { return faction; }
        public int getBefore() { return before; }
        public int getAfter() { return after; }
        public @Nullable ResourceLocation getReason() { return reason; }
    }
}
