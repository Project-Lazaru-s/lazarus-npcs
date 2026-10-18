package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** The public API driven the way an integrating mod would, on real Apocalypse Now NPCs. */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class ApiGameTests {
    /** The owner namespace must be a loaded mod; the test mod is lazarusnpcs itself. */
    private static final ResourceLocation OWNER = new ResourceLocation(LazarusNpcs.ID, "test_scenario");
    private static final List<Event> EVENTS = new ArrayList<>();
    private static final AtomicInteger CHOSEN = new AtomicInteger();
    private static boolean registered;

    private ApiGameTests() {}

    private static void setup() {
        EVENTS.clear();
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.ScenarioStarted e) -> EVENTS.add(e));
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.ScenarioEnded e) -> EVENTS.add(e));
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.TaskStateChanged e) -> EVENTS.add(e));
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.TaskProgress e) -> EVENTS.add(e));
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.ReputationChanged e) -> EVENTS.add(e));
        MinecraftForge.EVENT_BUS.addListener((NpcApiEvents.DialogueChoice e) -> EVENTS.add(e));
        NpcApi.registerDialogue(new DialogueDefinition(new ResourceLocation("testmod", "greeting"), Set.of(DialogueTrigger.INTERACT), 10,
                ctx -> NpcApi.assignment(ctx.npc()).filter(a -> a.owner().equals(OWNER)).isEmpty() ? Optional.empty()
                        : Optional.of(new DialoguePage(Component.literal("Psst. Over here."),
                        List.of(new DialogueOption("count", Component.literal("Count me in"), c -> CHOSEN.incrementAndGet()),
                                new DialogueOption("leave", Component.literal("Leave"), c -> {}))))));
        NpcApi.registerTask(new TaskDefinition(new ResourceLocation("testmod", "bandages"), Component.literal("Patch them up"),
                Component.literal("Bring two bandages."), TaskObjectives.deliver(List.of(new ItemStack(Items.PAPER, 2))),
                12_000, TaskDefinition.DeathPolicy.PERSIST, false, false, List.of(new ItemStack(Items.IRON_INGOT)), "haven", 2));
        NpcApi.registerTask(new TaskDefinition(new ResourceLocation("testmod", "protect"), Component.literal("Stay with them"),
                Component.literal("Keep them alive."), TaskObjectives.protect(6_000),
                12_000, TaskDefinition.DeathPolicy.FAIL, true, false, List.of(), null, 0));
        NpcApi.registerTask(new TaskDefinition(new ResourceLocation("testmod", "hunt"), Component.literal("Clear the street"),
                Component.literal("Kill the marked walkers, then walk to the van."),
                TaskObjectives.sequence(TaskObjectives.kill(1, (victim, encounter) -> victim.getTags().contains("testmod:" + encounter),
                        Component.literal("Marked walkers")), TaskObjectives.reach(4)),
                12_000, TaskDefinition.DeathPolicy.PERSIST, false, false, List.of(), null, 0));
    }

    private static <T extends Event> List<T> events(Class<T> type) {
        return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static GlobalPos at(GameTestHelper h, int x, int z) {
        return GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(x, 2, z)));
    }

    // ---- Identity and reputation

    @GameTest(template = "arena", batch = "api_identity", timeoutTicks = 20)
    public static void identityForHumansOnly(GameTestHelper h) {
        setup();
        Mob survivor = npc(h, "survivor_4", 6, 6), looter = npc(h, "looter_2", 10, 6);
        var zombie = h.spawn(EntityType.ZOMBIE, 14, 2, 6);
        var view = NpcApi.identity(survivor).orElseThrow();
        h.assertTrue(view.role().equals("survivor") && view.faction().equals("rovers") && view.name().equals(NpcIdentity.name(survivor))
                && view.interactive(), "survivor identity " + view);
        var hostile = NpcApi.identity(looter).orElseThrow();
        h.assertTrue(hostile.role().equals("looter") && hostile.faction().equals("hostile"), "looter identity " + hostile);
        h.assertTrue(NpcApi.identity(zombie).isEmpty(), "infected are not NPCs");
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "api_reputation", timeoutTicks = 20)
    public static void reputationIsLimitedAndPaidOncePerEncounter(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 8, 8);
        UUID encounter = UUID.randomUUID();
        ResourceLocation reason = new ResourceLocation("testmod", "saved_survivor");
        h.assertTrue(NpcApi.adjustReputation(player, "haven", 11, reason, encounter).status() == ApiResult.Status.LIMIT, "over the limit");
        h.assertTrue(NpcApi.adjustReputation(player, "pirates", 1, reason, encounter).status() == ApiResult.Status.INVALID_CONTEXT, "unknown faction");
        ApiResult first = NpcApi.adjustReputation(player, "haven", 5, reason, encounter);
        h.assertTrue(first.ok() && first.value() == 5, "paid: " + first);
        h.assertTrue(NpcApi.adjustReputation(player, "haven", 5, reason, encounter).status() == ApiResult.Status.DUPLICATE, "paid once");
        h.assertTrue(NpcApi.adjustReputation(player, "haven", 5, reason, UUID.randomUUID()).value() == 10, "another encounter pays again");
        var changes = events(NpcApiEvents.ReputationChanged.class);
        h.assertTrue(changes.size() == 2 && reason.equals(changes.get(0).getReason()), "two ReputationChanged events with the reason");
        finish(h, player);
    }

    // ---- Scenario orders

    @GameTest(template = "arena", batch = "api_scenario_walk", timeoutTicks = 500)
    public static void guardOrderWalksToPostAndExpires(GameTestHelper h) {
        setup();
        Mob soldier = npc(h, "military_01", 4, 16);
        ScenarioAssignment order = ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.GUARD_AREA, at(h, 18, 16), 4, 400);
        ApiResult result = NpcApi.assign(soldier, order);
        h.assertTrue(result.ok(), "assigned: " + result);
        h.assertTrue(events(NpcApiEvents.ScenarioStarted.class).size() == 1, "started event");
        h.succeedWhen(() -> {
            h.assertTrue(soldier.position().distanceTo(Vec3.atBottomCenterOf(order.anchor().pos())) <= 3.5, "reached the post; at "
                    + soldier.position() + " anchor " + order.anchor().pos() + " running "
                    + soldier.goalSelector.getRunningGoals().map(g -> g.getGoal().getClass().getSimpleName() + "@" + g.getPriority()).toList()
                    + " target " + soldier.getTarget() + " nav done " + soldier.getNavigation().isDone());
            h.assertTrue(NpcApi.assignment(soldier).isPresent(), "order still live");
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "api_scenario_expiry", timeoutTicks = 120)
    public static void expiredOrderIsCleaned(GameTestHelper h) {
        setup();
        Mob survivor = npc(h, "survivor_1", 8, 8);
        h.assertTrue(NpcApi.assign(survivor, ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.WAIT, at(h, 8, 8), 2, 40)).ok(), "assigned");
        h.runAtTickTime(80, () -> {
            h.assertTrue(!survivor.getPersistentData().contains(NpcScenarios.KEY), "expired order removed from the entity");
            h.assertTrue(events(NpcApiEvents.ScenarioEnded.class).stream()
                    .anyMatch(e -> e.getReason() == NpcApiEvents.ScenarioEnded.Reason.EXPIRED), "ended EXPIRED");
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "api_scenario_precedence", timeoutTicks = 20)
    public static void ordersRespectContractsSurrenderAndPriority(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob escort = npc(h, "military_01", 12, 10), looter = npc(h, "looter_1", 14, 14), survivor = npc(h, "survivor_2", 6, 6);
        NpcGameTests.hireEscort(player, escort);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        h.assertTrue(NpcApi.assign(escort, ScenarioAssignment.at(OWNER, first, ScenarioOrder.WAIT, at(h, 12, 10), 2, 200))
                .status() == ApiResult.Status.BUSY, "contracted NPC refused");
        HumanSurrender.surrender(looter, "looter", player);
        h.assertTrue(NpcApi.assign(looter, ScenarioAssignment.at(OWNER, first, ScenarioOrder.WAIT, at(h, 14, 14), 2, 200))
                .status() == ApiResult.Status.BUSY, "surrendered NPC refused");
        h.assertTrue(NpcApi.assign(survivor, ScenarioAssignment.at(new ResourceLocation("ghostmod", "x"), first, ScenarioOrder.WAIT,
                at(h, 6, 6), 2, 200)).status() == ApiResult.Status.INVALID_CONTEXT, "owner mod must be loaded");
        h.assertTrue(NpcApi.assign(survivor, ScenarioAssignment.at(OWNER, first, ScenarioOrder.FOLLOW_PLAYER, at(h, 6, 6), 2, 200))
                .status() == ApiResult.Status.INVALID_CONTEXT, "follow needs a target");
        h.assertTrue(NpcApi.assign(survivor, new ScenarioAssignment(OWNER, first, ScenarioOrder.WAIT, at(h, 6, 6), null, 2, 200, 3)).ok(), "first");
        h.assertTrue(NpcApi.assign(survivor, new ScenarioAssignment(OWNER, second, ScenarioOrder.WAIT, at(h, 6, 6), null, 2, 200, 3))
                .status() == ApiResult.Status.BUSY, "same priority from another encounter refused");
        h.assertTrue(!NpcApi.clearAssignment(survivor, OWNER, second), "only the owner encounter can clear");
        h.assertTrue(NpcApi.assign(survivor, new ScenarioAssignment(OWNER, second, ScenarioOrder.GUARD_AREA, at(h, 6, 6), null, 4, 200, 5)).ok(),
                "higher priority replaces");
        h.assertTrue(events(NpcApiEvents.ScenarioEnded.class).stream()
                .anyMatch(e -> e.getReason() == NpcApiEvents.ScenarioEnded.Reason.REPLACED), "REPLACED event");
        h.assertTrue(NpcApi.clearAssignment(survivor, OWNER, second) && NpcApi.assignment(survivor).isEmpty(), "owner clears");
        NpcContracts.end(h.getLevel().getServer(), NpcContracts.contract(escort), null);
        finish(h, player);
    }

    @GameTest(template = "arena", batch = "api_scenario_reload", timeoutTicks = 30)
    public static void orderSurvivesReloadAndDropsWithoutOwner(GameTestHelper h) {
        setup();
        Mob survivor = npc(h, "survivor_3", 8, 8);
        h.assertTrue(NpcApi.assign(survivor, ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.WAIT, at(h, 8, 8), 2, 2_000)).ok(), "assigned");
        Mob reloaded = reload(h, survivor);
        h.assertTrue(NpcApi.assignment(reloaded).isPresent(), "order kept through save and load");
        h.assertTrue(reloaded.goalSelector.getAvailableGoals().stream().filter(g -> g.getGoal() instanceof NpcScenarios.ScenarioGoal).count() == 1,
                "exactly one scenario goal after reload");
        CompoundTag orphan = reloaded.getPersistentData().getCompound(NpcScenarios.KEY).copy();
        orphan.putString("Owner", "ghostmod:gone");
        Mob ghost = npc(h, "survivor_3", 12, 12);
        ghost.getPersistentData().put(NpcScenarios.KEY, orphan);
        Mob ghostReloaded = reload(h, ghost);
        h.assertTrue(!ghostReloaded.getPersistentData().contains(NpcScenarios.KEY), "order of a removed mod is dropped on load");
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "api_scenario_follow", timeoutTicks = 700)
    public static void followOrderReachesThePlayer(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 26, 26);
        Mob survivor = npc(h, "survivor_1", 4, 4);
        h.assertTrue(NpcApi.assign(survivor, ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.FOLLOW_PLAYER, at(h, 4, 4), 4, 2_000)
                .withTarget(player.getUUID())).ok(), "assigned");
        h.succeedWhen(() -> {
            h.assertTrue(survivor.distanceTo(player) <= 6, "follows, distance " + survivor.distanceTo(player));
            remove(h, player);
            h.killAllEntities();
        });
    }

    // ---- Dialogue

    @GameTest(template = "arena", batch = "api_dialogue", timeoutTicks = 20)
    public static void dialogueChoiceRunsOnceAndRejectsForgeries(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_1", 12, 10);
        h.assertTrue(!NpcDialogues.open(player, survivor, DialogueTrigger.INTERACT, null), "no scenario: normal screen");
        NpcApi.assign(survivor, ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.WAIT, at(h, 12, 10), 2, 400));
        h.assertTrue(NpcDialogues.open(player, survivor, DialogueTrigger.INTERACT, null), "scenario dialogue shown");
        var session = NpcDialogues.session(player);
        int before = CHOSEN.get();
        NpcDialogues.choose(player, survivor.getUUID(), session.token() + 1, 0);
        h.assertTrue(CHOSEN.get() == before && NpcDialogues.session(player) != null, "wrong token ignored");
        NpcDialogues.choose(player, survivor.getUUID(), session.token(), 0);
        NpcDialogues.choose(player, survivor.getUUID(), session.token(), 0);
        h.assertTrue(CHOSEN.get() == before + 1, "consequence ran exactly once");
        h.assertTrue(events(NpcApiEvents.DialogueChoice.class).size() == 1, "one DialogueChoice event");
        NpcDialogues.open(player, survivor, DialogueTrigger.INTERACT, null);
        long token = NpcDialogues.session(player).token();
        Vec3 far = h.absoluteVec(new Vec3(10.5, 2, 30.5));
        player.teleportTo(far.x, far.y, far.z);
        NpcDialogues.choose(player, survivor.getUUID(), token, 0);
        h.assertTrue(CHOSEN.get() == before + 1, "choice from 20 blocks away refused");
        h.assertTrue(NpcApi.registerDialogue(new DialogueDefinition(new ResourceLocation("testmod", "greeting"),
                Set.of(DialogueTrigger.INTERACT), 1, ctx -> Optional.empty())).status() == ApiResult.Status.DUPLICATE, "duplicate id refused");
        finish(h, player);
    }

    // ---- Encounter tasks

    @GameTest(template = "arena", batch = "api_task_deliver", timeoutTicks = 40)
    public static void deliverTaskPaysOnceAndCoexistsWithNormalTask(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_2", 12, 10), other = npc(h, "survivor_1", 8, 12);
        NpcTasks.interact(player, other, "survivor"); // a normal task stays untouched
        UUID encounter = UUID.randomUUID();
        ApiResult offer = NpcApi.offerTask(player, survivor, new ResourceLocation("testmod", "bandages"), encounter, null);
        h.assertTrue(offer.ok() && NpcDialogues.session(player) != null, "offer shown: " + offer);
        NpcDialogues.choose(player, survivor.getUUID(), NpcDialogues.session(player).token(), 0); // Accept
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.ACTIVE).orElse(false), "accepted");
        h.assertTrue(!NpcTasks.summary(player).equals("No active task"), "normal task still active");
        h.assertTrue(NpcApi.offerTask(player, survivor, new ResourceLocation("testmod", "bandages"), UUID.randomUUID(), null)
                .status() == ApiResult.Status.BUSY, "one encounter task at a time");
        h.assertTrue(!NpcDialogues.open(player, survivor, DialogueTrigger.INTERACT, null), "nothing to hand over yet");
        player.getInventory().add(new ItemStack(Items.PAPER, 2));
        h.assertTrue(NpcDialogues.open(player, survivor, DialogueTrigger.INTERACT, null), "hand-over offered");
        long token = NpcDialogues.session(player).token();
        NpcDialogues.choose(player, survivor.getUUID(), token, 0);
        NpcDialogues.choose(player, survivor.getUUID(), token, 0);
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "completed");
        h.assertTrue(count(player, Items.IRON_INGOT) == 1 && count(player, Items.PAPER) == 0, "items taken and reward paid once");
        h.assertTrue(NpcReputation.get(player, "haven") == 2, "reputation reward");
        h.assertTrue(NpcApi.finishTask(player, offer.id(), NpcApi.TASK_COMPLETED).status() == ApiResult.Status.EXPIRED, "cannot finish twice");
        h.assertTrue(events(NpcApiEvents.TaskStateChanged.class).stream().map(NpcApiEvents.TaskStateChanged::getTo).toList()
                .equals(List.of(TaskState.OFFERED, TaskState.ACTIVE, TaskState.COMPLETED)), "state events in order");
        finish(h, player);
    }

    @GameTest(template = "arena", batch = "api_task_two_players", timeoutTicks = 20)
    public static void twoPlayersCannotTakeTheSameNpcTask(GameTestHelper h) {
        setup();
        ServerPlayer alice = player(h, 10, 10), bob = player(h, 12, 12);
        Mob survivor = npc(h, "survivor_2", 11, 11);
        UUID encounter = UUID.randomUUID();
        h.assertTrue(NpcApi.offerTask(alice, survivor, new ResourceLocation("testmod", "bandages"), encounter, null).ok(), "alice offered");
        h.assertTrue(NpcApi.offerTask(bob, survivor, new ResourceLocation("testmod", "bandages"), encounter, null)
                .status() == ApiResult.Status.BUSY, "bob refused for the same NPC and encounter");
        remove(h, bob);
        finish(h, alice);
    }

    @GameTest(template = "arena", batch = "api_task_giver_death", timeoutTicks = 20)
    public static void protectTaskFailsWhenGiverDies(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_1", 12, 10);
        NpcApi.offerTask(player, survivor, new ResourceLocation("testmod", "protect"), UUID.randomUUID(), null);
        NpcDialogues.choose(player, survivor.getUUID(), NpcDialogues.session(player).token(), 0);
        survivor.kill();
        h.runAtTickTime(3, () -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.FAILED).orElse(false), "failed");
            h.assertTrue(events(NpcApiEvents.TaskStateChanged.class).stream().anyMatch(e -> e.getReason().equals("giver_died")), "reason giver_died");
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "api_task_sequence", timeoutTicks = 60)
    public static void killThenReachSequence(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_1", 12, 10);
        UUID encounter = UUID.randomUUID();
        NpcApi.offerTask(player, survivor, new ResourceLocation("testmod", "hunt"), encounter, at(h, 26, 26));
        NpcDialogues.choose(player, survivor.getUUID(), NpcDialogues.session(player).token(), 0);
        Mob stray = npc(h, "walker_1", 16, 16), marked = npc(h, "walker_2", 18, 18);
        stray.setNoAi(true);
        marked.setNoAi(true);
        marked.addTag("testmod:" + encounter);
        stray.hurt(h.getLevel().damageSources().playerAttack(player), 1_000);
        h.assertTrue(events(NpcApiEvents.TaskProgress.class).isEmpty(), "unmarked kill does not count");
        marked.hurt(h.getLevel().damageSources().playerAttack(player), 1_000);
        h.assertTrue(events(NpcApiEvents.TaskProgress.class).stream().anyMatch(e -> e.getKey().equals("stage") && e.getValue() == 1),
                "stage advanced to the walk");
        Vec3 van = h.absoluteVec(new Vec3(26.5, 2, 26.5));
        player.teleportTo(van.x, van.y, van.z);
        h.succeedWhen(() -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "completed at the van");
            remove(h, player);
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "api_task_missing", timeoutTicks = 20)
    public static void taskFromRemovedModIsHarmless(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        CompoundTag task = new CompoundTag();
        UUID id = UUID.randomUUID();
        task.putUUID("Id", id);
        task.putString("Task", "ghostmod:gone");
        task.putUUID("Encounter", UUID.randomUUID());
        task.putUUID("Giver", UUID.randomUUID());
        task.putString("GiverName", "Nobody");
        task.putLong("Pos", 0);
        task.putString("State", "ACTIVE");
        task.putLong("Until", h.getLevel().getGameTime() + 1_000);
        CompoundTag data = new CompoundTag();
        data.put("Open", task);
        player.getPersistentData().put(EncounterTasks.KEY, data);
        EncounterTasks.evaluate(player, EncounterTasks.open(player));
        h.assertTrue(EncounterTasks.journal(player).contains("Unavailable"), "journal shows unavailable");
        h.assertTrue(NpcApi.finishTask(player, id, NpcApi.TASK_COMPLETED).status() == ApiResult.Status.MISSING_DEFINITION, "never pays");
        EncounterTasks.abandon(player);
        h.assertTrue(!EncounterTasks.hasOpen(player), "can be abandoned");
        finish(h, player);
    }

    // ---- Helpers

    private static Mob reload(GameTestHelper h, Mob mob) {
        CompoundTag saved = new CompoundTag();
        mob.save(saved);
        mob.discard();
        Entity copy = EntityType.loadEntityRecursive(saved, h.getLevel(), entity -> entity);
        h.assertTrue(copy instanceof Mob && h.getLevel().addFreshEntity(copy), "reloaded");
        return (Mob) copy;
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(item)) total += player.getInventory().getItem(slot).getCount();
        return total;
    }

    private static Mob npc(GameTestHelper h, String path, int x, int z) { return NpcGameTests.npc(h, path, x, z); }

    private static ServerPlayer player(GameTestHelper h, int x, int z) { return NpcGameTests.player(h, x, z); }

    private static void remove(GameTestHelper h, ServerPlayer player) { h.getLevel().getServer().getPlayerList().remove(player); }

    private static void finish(GameTestHelper h, ServerPlayer player) {
        remove(h, player);
        h.killAllEntities();
        h.succeed();
    }
}
