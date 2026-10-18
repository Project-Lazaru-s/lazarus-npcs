package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskContext;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.api.task.TaskView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encounter tasks from other mods. Stored on the player apart from the normal NPC task ({@code LazarusNpcsTasks}),
 * so one of each can run at once. State changes are saved before rewards are paid, so a repeat never pays twice.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class EncounterTasks {
    static final String KEY = "LazarusNpcsEncounterTasks";
    static final int VERSION = 1;
    static final long OFFER_TICKS = 2_400L;
    static final double OFFER_DISTANCE = 16;
    private static final Map<ResourceLocation, TaskDefinition> REGISTRY = new ConcurrentHashMap<>();
    private static final ResourceLocation OFFER_DIALOGUE = new ResourceLocation(LazarusNpcs.ID, "task_offer");
    private static final ResourceLocation GIVER_DIALOGUE = new ResourceLocation(LazarusNpcs.ID, "task_giver");

    private EncounterTasks() {}

    /** Registers the built-in offer and hand-in dialogues. Called once from the mod constructor. */
    static void init() {
        NpcDialogues.register(new DialogueDefinition(OFFER_DIALOGUE, Set.of(DialogueTrigger.TASK_OFFER), 100, EncounterTasks::offerPage));
        NpcDialogues.register(new DialogueDefinition(GIVER_DIALOGUE,
                Set.of(DialogueTrigger.INTERACT, DialogueTrigger.TASK_PROGRESS), 100, EncounterTasks::giverPage));
    }

    // ---- Registry

    static ApiResult register(TaskDefinition d) {
        if (d == null || d.id() == null || d.title() == null || d.description() == null || d.objective() == null
                || d.onPlayerDeath() == null || d.rewardItems() == null)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "id, title, description, objective, death policy and reward list are required");
        if (d.deadlineTicks() < 1_200 || d.deadlineTicks() > 168_000)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "deadline must be 1200-168000 ticks");
        if (d.rewardItems().size() > 6 || d.rewardReputation() < 0 || d.rewardReputation() > 10)
            return ApiResult.fail(ApiResult.Status.LIMIT, "at most 6 reward stacks and 0-10 reputation");
        if (d.rewardFaction() != null && ("independent".equals(d.rewardFaction())
                || !Arrays.asList(NpcIdentity.FACTIONS).contains(d.rewardFaction())))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "unknown reward faction " + d.rewardFaction());
        if (REGISTRY.putIfAbsent(d.id(), d) != null)
            return ApiResult.fail(ApiResult.Status.DUPLICATE, "task " + d.id() + " is already registered");
        return ApiResult.ok("task registered");
    }

    // ---- Storage

    private static CompoundTag data(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(KEY);
        data.putInt("Version", VERSION);
        return data;
    }

    private static void save(ServerPlayer player, CompoundTag data) { player.getPersistentData().put(KEY, data); }

    /** The open instance (offered, active or ready), or an empty tag. */
    static CompoundTag open(ServerPlayer player) { return data(player).getCompound("Open"); }

    private static TaskState state(CompoundTag task) { return TaskState.valueOf(task.getString("State")); }

    private static TaskDefinition definition(CompoundTag task) { return REGISTRY.get(new ResourceLocation(task.getString("Task"))); }

    private static UUID uuid(CompoundTag task, String key) { return task.hasUUID(key) ? task.getUUID(key) : new UUID(0, 0); }

    /** Moves the open instance to a new state, saves, then posts the event. Finished instances move to "Last". */
    private static void transition(ServerPlayer player, CompoundTag task, TaskState to, String reason) {
        TaskState from = state(task);
        task.putString("State", to.name());
        task.putString("Reason", reason);
        CompoundTag data = data(player);
        if (to.open()) data.put("Open", task);
        else {
            data.remove("Open");
            data.put("Last", task);
        }
        save(player, data);
        ApiBackend.post(new NpcApiEvents.TaskStateChanged(player, task.getUUID("Id"), new ResourceLocation(task.getString("Task")),
                uuid(task, "Encounter"), from, to, reason));
    }

    // ---- API operations

    static ApiResult offer(ServerPlayer player, LivingEntity giver, ResourceLocation taskId, UUID encounterId, GlobalPos destination) {
        TaskDefinition definition = taskId == null ? null : REGISTRY.get(taskId);
        if (definition == null) return ApiResult.fail(ApiResult.Status.MISSING_DEFINITION, "no task " + taskId);
        if (encounterId == null) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "encounterId is required");
        if (!NpcScenarios.supported(giver)) return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "giver is not a Lazarus human NPC");
        if (!player.isAlive() || !giver.isAlive() || giver.level() != player.level() || giver.distanceTo(player) > OFFER_DISTANCE)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "player and giver must be alive and within 16 blocks");
        long now = player.serverLevel().getGameTime();
        CompoundTag current = open(player);
        if (!current.isEmpty()) {
            if (state(current) == TaskState.OFFERED) transition(player, current, TaskState.EXPIRED, "replaced_offer");
            else return ApiResult.fail(ApiResult.Status.BUSY, "the player already has an encounter task; abandon it from the journal first");
        }
        for (ServerPlayer other : player.getServer().getPlayerList().getPlayers()) {
            CompoundTag theirs = open(other);
            if (other != player && !theirs.isEmpty() && giver.getUUID().equals(uuid(theirs, "Giver"))
                    && encounterId.equals(uuid(theirs, "Encounter")))
                return ApiResult.fail(ApiResult.Status.BUSY, "this NPC's task is already taken by " + other.getGameProfile().getName());
        }
        CompoundTag task = new CompoundTag();
        UUID id = UUID.randomUUID();
        task.putUUID("Id", id);
        task.putString("Task", taskId.toString());
        task.putUUID("Encounter", encounterId);
        task.putUUID("Giver", giver.getUUID());
        task.putString("GiverName", NpcApi.identity(giver).map(view -> view.name()).orElse("Someone"));
        task.putString("Dim", giver.level().dimension().location().toString());
        task.putLong("Pos", giver.blockPosition().asLong());
        if (destination != null) {
            task.putString("DestDim", destination.dimension().location().toString());
            task.putLong("Dest", destination.pos().asLong());
        }
        task.putString("State", TaskState.OFFERED.name());
        task.putLong("Created", now);
        task.putLong("Until", now + OFFER_TICKS);
        task.put("Counters", new CompoundTag());
        CompoundTag data = data(player);
        data.put("Open", task);
        save(player, data);
        ApiBackend.post(new NpcApiEvents.TaskStateChanged(player, id, taskId, encounterId, null, TaskState.OFFERED, "offered"));
        NpcDialogues.open(player, giver, DialogueTrigger.TASK_OFFER, id);
        return ApiResult.ok("task offered", 0, id);
    }

    static ApiResult progress(ServerPlayer player, UUID instanceId, String key, int amount) {
        if (key == null || key.isEmpty() || key.length() > 32 || amount < 1 || amount > 1_000)
            return ApiResult.fail(ApiResult.Status.LIMIT, "key 1-32 characters, amount 1-1000");
        CompoundTag task = open(player);
        if (task.isEmpty() || !task.getUUID("Id").equals(instanceId))
            return ApiResult.fail(ApiResult.Status.EXPIRED, "no open task with that id");
        if (state(task) != TaskState.ACTIVE) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "task is " + state(task));
        if (definition(task) == null) return ApiResult.fail(ApiResult.Status.MISSING_DEFINITION, "task provider is not loaded");
        int value = new Ctx(player, task).addCounter(key, amount);
        evaluate(player, task);
        return ApiResult.ok("progress recorded", value, instanceId);
    }

    static ApiResult finish(ServerPlayer player, UUID instanceId, ResourceLocation outcome) {
        CompoundTag task = open(player);
        if (task.isEmpty() || !task.getUUID("Id").equals(instanceId))
            return ApiResult.fail(ApiResult.Status.EXPIRED, "no open task with that id");
        if (state(task) == TaskState.OFFERED) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "task was not accepted");
        if (NpcApi.TASK_COMPLETED.equals(outcome)) {
            if (definition(task) == null) return ApiResult.fail(ApiResult.Status.MISSING_DEFINITION, "task provider is not loaded");
            complete(player, task, "completed");
        } else transition(player, task, TaskState.FAILED, String.valueOf(outcome));
        return ApiResult.ok("task finished", 0, instanceId);
    }

    static Optional<TaskView> view(ServerPlayer player) {
        CompoundTag data = data(player);
        CompoundTag task = data.contains("Open") ? data.getCompound("Open") : data.getCompound("Last");
        if (task.isEmpty()) return Optional.empty();
        return Optional.of(new TaskView(task.getUUID("Id"), new ResourceLocation(task.getString("Task")), uuid(task, "Encounter"),
                uuid(task, "Giver"), state(task), task.getLong("Accepted"), task.getLong("Until"), definition(task) != null));
    }

    // ---- Player choices (built-in dialogues and journal)

    private static void accept(ServerPlayer player, UUID id) {
        CompoundTag task = open(player);
        if (task.isEmpty() || !task.getUUID("Id").equals(id) || state(task) != TaskState.OFFERED) return;
        TaskDefinition definition = definition(task);
        if (definition == null) return;
        long now = player.serverLevel().getGameTime();
        task.putLong("Accepted", now);
        task.putLong("Until", now + definition.deadlineTicks());
        transition(player, task, TaskState.ACTIVE, "accepted");
        player.sendSystemMessage(Component.literal("Encounter task accepted: ").append(definition.title())
                .append(Component.literal(". Details in the journal.")));
    }

    private static void decline(ServerPlayer player, UUID id) {
        CompoundTag task = open(player);
        if (!task.isEmpty() && task.getUUID("Id").equals(id) && state(task) == TaskState.OFFERED)
            transition(player, task, TaskState.ABANDONED, "declined");
    }

    static void abandon(ServerPlayer player) {
        CompoundTag task = open(player);
        if (task.isEmpty()) return;
        transition(player, task, TaskState.ABANDONED, "abandoned");
        player.sendSystemMessage(Component.literal("Encounter task abandoned."));
    }

    private static void complete(ServerPlayer player, CompoundTag task, String reason) {
        TaskDefinition definition = definition(task);
        transition(player, task, TaskState.COMPLETED, reason); // saved first: rewards below can never repeat
        for (ItemStack reward : definition.rewardItems()) {
            ItemStack stack = reward.copy();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false); // full inventory: at the feet
        }
        player.inventoryMenu.broadcastChanges();
        if (definition.rewardFaction() != null && definition.rewardReputation() > 0)
            NpcReputation.adjust(player, definition.rewardFaction(), definition.rewardReputation(), definition.id());
        player.sendSystemMessage(Component.literal("Encounter task complete: ").append(definition.title())
                .append(Component.literal(". Received " + rewardText(definition) + "."))
                .withStyle(style -> style.withColor(0x8FCB91)));
    }

    // ---- Evaluation

    /** Checks deadlines, the giver, and the objective. Safe to call any time; does nothing for finished tasks. */
    static void evaluate(ServerPlayer player, CompoundTag task) {
        if (task.isEmpty()) return;
        TaskState state = state(task);
        long now = player.serverLevel().getGameTime();
        if (state == TaskState.OFFERED) {
            if (now > task.getLong("Until")) transition(player, task, TaskState.EXPIRED, "offer_expired");
            return;
        }
        TaskDefinition definition = definition(task);
        if (definition == null) return; // provider missing: shown as unavailable, can be abandoned, never pays
        if (now > task.getLong("Until")) {
            transition(player, task, TaskState.EXPIRED, "expired");
            player.sendSystemMessage(Component.literal("Encounter task expired: ").append(definition.title()));
            return;
        }
        if (definition.failIfGiverDies() && NpcDeaths.death(player.getServer(), uuid(task, "Giver")) != null) {
            fail(player, task, definition, "giver_died");
            return;
        }
        if (state != TaskState.ACTIVE) return;
        Ctx context = new Ctx(player, task);
        try {
            definition.objective().tick(context);
            if (!definition.objective().complete(context)) {
                save(player, withOpen(player, task));
                return;
            }
        } catch (RuntimeException failure) {
            LazarusNpcs.LOGGER.error("[api] objective of {} failed", definition.id(), failure);
            return;
        }
        if (definition.turnIn()) {
            transition(player, task, TaskState.READY, "objective_done");
            player.sendSystemMessage(Component.literal("Report back to " + task.getString("GiverName") + "."));
        } else complete(player, task, "completed");
    }

    private static CompoundTag withOpen(ServerPlayer player, CompoundTag task) {
        CompoundTag data = data(player);
        data.put("Open", task);
        return data;
    }

    private static void fail(ServerPlayer player, CompoundTag task, TaskDefinition definition, String reason) {
        transition(player, task, TaskState.FAILED, reason);
        player.sendSystemMessage(Component.literal("Encounter task failed: ").append(definition.title())
                .append(Component.literal("giver_died".equals(reason) ? " (" + task.getString("GiverName") + " died)." : "."))
                .withStyle(style -> style.withColor(0xD46868)));
    }

    /** Once per second for every online player with an encounter task (server tick, not player tick). */
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 13) return;
        long start = System.nanoTime();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers())
            if (player.getPersistentData().contains(KEY)) evaluate(player, open(player));
        NpcPerf.record("encounter tasks", System.nanoTime() - start);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        Entity killer = event.getSource().getEntity();
        if (killer == null && event.getSource().getDirectEntity() instanceof Projectile projectile) killer = projectile.getOwner();
        if (killer instanceof ServerPlayer player && player.getPersistentData().contains(KEY)) {
            CompoundTag task = open(player);
            TaskDefinition definition = task.isEmpty() ? null : definition(task);
            if (definition != null && state(task) == TaskState.ACTIVE) {
                definition.objective().killed(new Ctx(player, task), victim);
                evaluate(player, task);
            }
        }
        if (!NpcScenarios.supported(victim)) return;
        for (ServerPlayer player : victim.getServer().getPlayerList().getPlayers()) {
            CompoundTag task = open(player);
            if (task.isEmpty() || !victim.getUUID().equals(uuid(task, "Giver")) || !state(task).open()) continue;
            TaskDefinition definition = definition(task);
            if (state(task) == TaskState.OFFERED) transition(player, task, TaskState.EXPIRED, "giver_died");
            else if (definition != null && definition.failIfGiverDies()) fail(player, task, definition, "giver_died");
        }
    }

    /** Player death: Forge does not copy mod data, so carry it over and apply each task's policy. */
    @SubscribeEvent
    public static void clone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag old = event.getOriginal().getPersistentData().getCompound(KEY);
        if (old.isEmpty()) return;
        player.getPersistentData().put(KEY, old.copy());
        CompoundTag task = open(player);
        if (task.isEmpty()) return;
        TaskDefinition definition = definition(task);
        if (state(task) == TaskState.OFFERED) transition(player, task, TaskState.EXPIRED, "player_died");
        else if (definition != null && definition.onPlayerDeath() == TaskDefinition.DeathPolicy.FAIL)
            fail(player, task, definition, "player_died");
    }

    // ---- Built-in dialogues

    private static Optional<DialoguePage> offerPage(DialogueContext c) {
        CompoundTag task = open(c.player());
        if (task.isEmpty() || state(task) != TaskState.OFFERED || !task.getUUID("Id").equals(c.taskId())
                || !c.npc().getUUID().equals(uuid(task, "Giver"))) return Optional.empty();
        return Optional.ofNullable(definition(task)).map(d -> offer(c, task, d));
    }

    private static DialoguePage offer(DialogueContext c, CompoundTag task, TaskDefinition d) {
        UUID id = task.getUUID("Id");
        Component line = d.title().copy().append(Component.literal(": ")).append(d.description())
                .append(Component.literal(" Reward: " + rewardText(d) + "."));
        return new DialoguePage(line, List.of(
                new DialogueOption("accept", Component.literal("Accept"), ctx -> accept(ctx.player(), id)),
                new DialogueOption("decline", Component.literal("Decline"), ctx -> decline(ctx.player(), id))));
    }

    private static Optional<DialoguePage> giverPage(DialogueContext c) {
        CompoundTag task = open(c.player());
        if (task.isEmpty() || !c.npc().getUUID().equals(uuid(task, "Giver"))) return Optional.empty();
        TaskDefinition d = definition(task);
        if (d == null) return Optional.empty();
        UUID id = task.getUUID("Id");
        return switch (state(task)) {
            case OFFERED -> Optional.of(offer(c, task, d));
            case READY -> Optional.of(new DialoguePage(Component.literal("You made it back. Tell me everything."), List.of(
                    new DialogueOption("report", Component.literal("Report"), ctx -> {
                        CompoundTag t = open(ctx.player());
                        if (!t.isEmpty() && t.getUUID("Id").equals(id) && state(t) == TaskState.READY) complete(ctx.player(), t, "completed");
                    }),
                    new DialogueOption("later", Component.literal("Later"), ctx -> {}))));
            case ACTIVE -> d.objective().canDeliver(new Ctx(c.player(), task))
                    ? Optional.of(new DialoguePage(Component.literal("Did you bring what I asked for?"), List.of(
                            new DialogueOption("deliver", Component.literal("Hand it over"), ctx -> {
                                CompoundTag t = open(ctx.player());
                                if (t.isEmpty() || !t.getUUID("Id").equals(id) || state(t) != TaskState.ACTIVE) return;
                                if (d.objective().deliver(new Ctx(ctx.player(), t))) {
                                    ctx.player().inventoryMenu.broadcastChanges();
                                    evaluate(ctx.player(), t);
                                }
                            }),
                            new DialogueOption("not_yet", Component.literal("Not yet"), ctx -> {}))))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    // ---- Journal

    /** Lines for the journal's Encounters page; empty when the player never had an encounter task. */
    static String journal(ServerPlayer player) {
        CompoundTag data = data(player);
        CompoundTag task = data.contains("Open") ? data.getCompound("Open") : data.getCompound("Last");
        if (task.isEmpty()) return "";
        TaskDefinition d = definition(task);
        TaskState state = state(task);
        StringBuilder text = new StringBuilder();
        text.append(d == null ? task.getString("Task") : d.title().getString()).append(" (").append(label(state, task)).append(")\n");
        if (d == null) text.append("Unavailable: the mod that added this task is not loaded. You can abandon it.\n");
        else {
            text.append(d.description().getString()).append('\n');
            if (state == TaskState.ACTIVE) text.append(d.objective().progress(new Ctx(player, task)).getString()).append('\n');
        }
        BlockPos giver = BlockPos.of(task.getLong("Pos"));
        text.append(task.getString("GiverName")).append(": ").append(giver.getX()).append(", ").append(giver.getY())
                .append(", ").append(giver.getZ());
        if (task.contains("Dest")) {
            BlockPos dest = BlockPos.of(task.getLong("Dest"));
            text.append(" | Destination: ").append(dest.getX()).append(", ").append(dest.getY()).append(", ").append(dest.getZ());
        }
        text.append('\n');
        if (state.open() && state != TaskState.OFFERED)
            text.append(Math.max(0, (task.getLong("Until") - player.serverLevel().getGameTime() + 1199) / 1200)).append(" min left. ");
        if (d != null) text.append("Reward: ").append(rewardText(d)).append('.');
        String result = text.toString();
        return result.length() > 900 ? result.substring(0, 897) + "..." : result;
    }

    static boolean hasOpen(ServerPlayer player) { return !open(player).isEmpty(); }

    private static String label(TaskState state, CompoundTag task) {
        return switch (state) {
            case OFFERED -> "offered";
            case ACTIVE -> "active";
            case READY -> "report back";
            case COMPLETED -> "completed";
            case FAILED -> "giver_died".equals(task.getString("Reason")) ? "failed: " + task.getString("GiverName") + " died" : "failed";
            case EXPIRED -> "expired";
            case ABANDONED -> "abandoned";
        };
    }

    static String rewardText(TaskDefinition d) {
        StringBuilder text = new StringBuilder();
        for (ItemStack stack : d.rewardItems()) {
            if (text.length() > 0) text.append(", ");
            text.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        }
        if (d.rewardFaction() != null && d.rewardReputation() > 0) {
            if (text.length() > 0) text.append(", ");
            text.append('+').append(d.rewardReputation()).append(' ').append(NpcIdentity.label(d.rewardFaction()));
        }
        return text.length() == 0 ? "nothing" : text.toString();
    }

    // ---- Objective context over the saved instance

    private static final class Ctx implements TaskContext {
        private final ServerPlayer player;
        private final CompoundTag task;

        Ctx(ServerPlayer player, CompoundTag task) {
            this.player = player;
            this.task = task;
        }

        @Override public ServerPlayer player() { return player; }
        @Override public UUID instanceId() { return task.getUUID("Id"); }
        @Override public UUID encounterId() { return uuid(task, "Encounter"); }

        @Override
        public Optional<LivingEntity> giver() {
            Entity entity = player.serverLevel().getEntity(uuid(task, "Giver"));
            return entity instanceof LivingEntity living && living.isAlive() ? Optional.of(living) : Optional.empty();
        }

        @Override
        public Optional<GlobalPos> destination() {
            if (!task.contains("Dest")) return Optional.empty();
            var dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(task.getString("DestDim")));
            return Optional.of(GlobalPos.of(dim, BlockPos.of(task.getLong("Dest"))));
        }

        @Override public long elapsedTicks() { return player.serverLevel().getGameTime() - task.getLong("Accepted"); }

        @Override public int counter(String key) { return task.getCompound("Counters").getInt(key); }

        @Override
        public int addCounter(String key, int amount) {
            CompoundTag counters = task.getCompound("Counters");
            int value = counters.getInt(key) + amount;
            counters.putInt(key, value);
            task.put("Counters", counters);
            save(player, withOpen(player, task));
            ApiBackend.post(new NpcApiEvents.TaskProgress(player, instanceId(), new ResourceLocation(task.getString("Task")),
                    encounterId(), key, value));
            return value;
        }
    }
}
