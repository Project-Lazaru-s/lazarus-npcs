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
import com.lazarus.npcs.ai.CoverFinder;
import com.lazarus.npcs.ai.Exposure;
import com.lazarus.npcs.ai.Squads;
import com.lazarus.npcs.ai.BaseAlert;
import com.lazarus.npcs.ai.CombatMoveGoal;
import com.lazarus.npcs.ai.RoutineGoal;
import com.lazarus.npcs.api.ai.Activity;
import com.lazarus.npcs.api.ai.NightDuty;
import com.lazarus.npcs.api.ai.Routine;
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

    private static Routine routine(GameTestHelper h, UUID base) {
        BlockPos work = h.absolutePos(new BlockPos(9, 2, 9));
        BlockPos shelter = h.absolutePos(new BlockPos(11, 2, 9));
        return new Routine(base, List.of(new Activity(Activity.Kind.WORK, work, 4),
                new Activity(Activity.Kind.SOCIAL, shelter, 2)), NightDuty.SHELTER,
                null, shelter, h.absolutePos(new BlockPos(13, 2, 9)));
    }

    @GameTest(template = "arena", batch = "ai_routine_api", timeoutTicks = 30)
    public static void routineIsTransientAndCanBeRestored(GameTestHelper h) {
        Mob resident = npc(h, "survivor_1", 9, 9);
        Routine schedule = routine(h, UUID.randomUUID());
        h.assertTrue(NpcApi.setRoutine(resident, schedule).ok(), "routine assigned");
        h.assertTrue(schedule.equals(RoutineGoal.routine(resident)), "schedule stored");
        Mob loaded = reload(h, resident);
        h.assertTrue(RoutineGoal.routine(loaded) == null, "transient schedule cleared on load");
        h.assertTrue(NpcApi.setRoutine(loaded, schedule).ok(), "schedule resent");
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "ai_base_alert", timeoutTicks = 630)
    public static void alertReachesResidentsAndExpires(GameTestHelper h) {
        UUID base = UUID.randomUUID();
        Mob first = npc(h, "survivor_1", 8, 8);
        Mob second = npc(h, "survivor_2", 10, 8);
        Mob zombie = h.spawn(EntityType.ZOMBIE, 16, 2, 8);
        zombie.setNoAi(true);
        h.assertTrue(NpcApi.setRoutine(first, routine(h, base)).ok()
                && NpcApi.setRoutine(second, routine(h, base)).ok(), "both residents assigned");
        BaseAlert.raise(first, zombie);
        h.assertTrue(BaseAlert.active(first) && BaseAlert.active(second), "shared alarm");
        zombie.discard(); // the immediate threat leaves; the alarm should then time out
        first.setNoAi(true);
        second.setNoAi(true);
        h.runAfterDelay(610, () -> {
            h.assertTrue(!BaseAlert.active(first) && !BaseAlert.active(second), "alarm expired: "
                    + BaseAlert.active(first) + "/" + BaseAlert.active(second) + " at " + h.getLevel().getGameTime());
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "zzzz_ai_night", timeoutTicks = 420)
    public static void bedWatchAndShelterReactToAlarm(GameTestHelper h) {
        long daytime = h.getLevel().getDayTime();
        java.util.concurrent.atomic.AtomicBoolean nightClock = new java.util.concurrent.atomic.AtomicBoolean(true);
        h.onEachTick(() -> { if (nightClock.get()) h.getLevel().setDayTime(14000); });
        h.getLevel().setDayTime(14000);
        ServerPlayer observer = player(h, 18, 18); // keeps nearby AI in the FULL distance band
        BlockPos bed = h.absolutePos(new BlockPos(10, 2, 10));
        var foot = net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
        h.getLevel().setBlock(bed, foot, 3);
        h.getLevel().setBlock(bed.east(), foot.setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        Mob resident = npc(h, "survivor_1", 8, 10);
        Mob guard = npc(h, "survivor_2", 16, 10);
        UUID base = UUID.randomUUID();
        BlockPos shelter = h.absolutePos(new BlockPos(8, 2, 14));
        BlockPos defense = h.absolutePos(new BlockPos(17, 2, 10));
        var activities = List.of(new Activity(Activity.Kind.WORK, h.absolutePos(new BlockPos(8, 2, 10)), 1));
        h.assertTrue(NpcApi.setRoutine(resident, new Routine(base, activities, NightDuty.SLEEP,
                bed, shelter, defense)).ok(), "sleep routine assigned");
        h.assertTrue(NpcApi.setRoutine(guard, new Routine(base, activities, NightDuty.WATCH,
                null, shelter, defense)).ok(), "watch routine assigned");
        h.runAfterDelay(200, () -> {
            h.assertTrue(resident.isSleeping(), "civilian lies down near the bed at night: pos="
                    + resident.blockPosition() + " pose=" + resident.getPose() + " target=" + resident.getTarget()
                    + " nav=" + resident.getNavigation().isDone() + " lod="
                    + com.lazarus.npcs.ai.Lod.band(resident) + " day=" + h.getLevel().getDayTime()
                    + " busy=" + NpcAiHooks.busy(resident) + " stand="
                    + com.lazarus.npcs.ai.PointPicker.standable(resident, bed.west())
                    + " goals=" + resident.goalSelector.getAvailableGoals().stream()
                    .filter(goal -> goal.getGoal() instanceof RoutineGoal)
                    .map(goal -> goal.isRunning()).toList());
            h.assertTrue(!guard.isSleeping() && guard.blockPosition().distSqr(defense) <= 16,
                    "guard remains at the defense post");
            nightClock.set(false);
            h.getLevel().setDayTime(daytime);
            Mob zombie = h.spawn(EntityType.ZOMBIE, 10, 2, 15);
            zombie.setNoAi(true);
            BaseAlert.raise(resident, zombie);
            zombie.discard(); // isolate the routine response; combat correctly takes precedence if the threat stays
        });
        h.runAfterDelay(360, () -> {
            h.assertTrue(!resident.isSleeping() && resident.blockPosition().distSqr(shelter) <= 16,
                    "alarm wakes civilian and sends them to shelter");
            h.getLevel().setDayTime(daytime);
            remove(h, observer);
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "ai_combat_move", timeoutTicks = 60)
    public static void unarmedHostileUsesCombatMoveAndCanStrike(GameTestHelper h) {
        Mob looter = npc(h, "looter_1", 8, 8);
        Mob zombie = h.spawn(EntityType.ZOMBIE, 11, 2, 8);
        zombie.setNoAi(true);
        looter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        looter.setTarget(zombie);
        h.assertTrue(looter.goalSelector.getAvailableGoals().stream()
                .anyMatch(goal -> goal.getGoal() instanceof CombatMoveGoal), "combat goal installed");
        float health = zombie.getHealth();
        h.succeedWhen(() -> {
            h.assertTrue(zombie.getHealth() < health, "melee rush struck target");
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "ai_tactical_3v3", timeoutTicks = 330)
    public static void armedSquadsFlankAndBoundAroundCover(GameTestHelper h) {
        ServerPlayer observer = player(h, 16, 26);
        for (int y = 2; y <= 3; y++) {
            h.getLevel().setBlock(h.absolutePos(new BlockPos(12, y, 9)),
                    net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
            h.getLevel().setBlock(h.absolutePos(new BlockPos(20, y, 13)),
                    net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        Mob[] rovers = new Mob[3], wardens = new Mob[3];
        UUID roversId = UUID.randomUUID(), wardensId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            rovers[i] = npc(h, "survivor_4", 8, 8 + i * 2);
            wardens[i] = npc(h, "survivor_7", 24, 8 + i * 2);
            for (Mob actor : List.of(rovers[i], wardens[i])) {
                UUID side = actor == rovers[i] ? roversId : wardensId;
                h.assertTrue(NpcApi.assign(actor, ScenarioAssignment.at(OWNER, side,
                        ScenarioOrder.GUARD_AREA, at(h, 16, 10), 30, 600)).ok(), "squad assigned");
                actor.getPersistentData().remove(NpcGuns.KEY);
                NpcGuns.roll(actor, new NpcGuns.Arsenal(1f, 1f, java.util.Map.of("m4a1", 1)));
                NpcGuns.attach(actor, 1f);
                h.assertTrue(NpcGuns.armed(actor), "actor armed");
                actor.setInvulnerable(true);
            }
        }
        for (int i = 0; i < 3; i++) {
            rovers[i].setTarget(wardens[i]);
            wardens[i].setTarget(rovers[i]);
        }
        Mob[] actors = {rovers[0], rovers[1], rovers[2], wardens[0], wardens[1], wardens[2]};
        BlockPos[] last = java.util.Arrays.stream(actors).map(Mob::blockPosition).toArray(BlockPos[]::new);
        int[] moves = new int[actors.length];
        java.util.concurrent.atomic.AtomicBoolean flanked = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean bounded = new java.util.concurrent.atomic.AtomicBoolean();
        h.onEachTick(() -> {
            for (int i = 0; i < actors.length; i++) {
                Mob actor = actors[i];
                CombatMoveGoal goal = actor.goalSelector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal())
                        .filter(CombatMoveGoal.class::isInstance).map(CombatMoveGoal.class::cast).findFirst().orElseThrow();
                if (goal.state() == CombatMoveGoal.State.FLANK) flanked.set(true);
                if (goal.state() == CombatMoveGoal.State.BOUND) bounded.set(true);
                if (actor.tickCount % 20 == 0 && !actor.blockPosition().equals(last[i])) {
                    moves[i]++;
                    last[i] = actor.blockPosition();
                }
            }
        });
        h.runAfterDelay(240, () -> {
            h.assertTrue(flanked.get() && bounded.get(), "squad roles entered flank and bound states");
            h.assertTrue(java.util.Arrays.stream(moves).filter(count -> count >= 2).count() >= 3,
                    "at least half the squad changed position twice: " + java.util.Arrays.toString(moves));
            remove(h, observer);
            h.killAllEntities();
            h.succeed();
        });
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

    @GameTest(template = "arena", batch = "api_faction_assignment", timeoutTicks = 20)
    public static void explicitFactionOverridesSurvivorVariant(GameTestHelper h) {
        setup();
        Mob survivor = npc(h, "survivor_4", 6, 6);
        h.assertTrue(NpcApi.assignFaction(survivor, "wardens").ok(), "registered faction assignment");
        h.assertTrue(NpcApi.identity(survivor).orElseThrow().faction().equals("wardens"), "identity follows assignment");
        java.util.UUID base = java.util.UUID.randomUUID();
        h.assertTrue(NpcApi.assignProfession(survivor, new ResourceLocation("lazarusnpcs", "guard"), base).ok(),
                "profession follows assigned faction");
        h.assertTrue(!NpcApi.assignFaction(survivor, "rovers").ok(), "base membership prevents faction swap");
        h.assertTrue(!NpcApi.assignFaction(survivor, "missing").ok(), "unknown faction rejected");
        h.killAllEntities();
        h.succeed();
    }

    /** Every Modern Mayhem packet must be handled on the main thread (backpack keybind crash of 2026-10-19). */
    @GameTest(template = "arena", batch = "compat_modern_mayhem", timeoutTicks = 20)
    public static void modernMayhemPacketsRunOnMainThread(GameTestHelper h) {
        try {
            Class<?> registry = Class.forName("net.tkg.ModernMayhem.server.registry.PacketsRegistryMM");
            java.lang.reflect.Field channelField = registry.getDeclaredField("channel");
            channelField.setAccessible(true);
            Object channel = channelField.get(null);
            java.lang.reflect.Field codecField = net.minecraftforge.network.simple.SimpleChannel.class.getDeclaredField("indexedCodec");
            codecField.setAccessible(true);
            Object codec = codecField.get(channel);
            java.lang.reflect.Field typesField = codec.getClass().getDeclaredField("types");
            typesField.setAccessible(true);
            java.util.Map<?, ?> types = (java.util.Map<?, ?>) typesField.get(codec);
            h.assertTrue(types.keySet().stream().anyMatch(type -> ((Class<?>) type).getSimpleName().equals("OpenBackpackKeyPacket")),
                    "backpack key packet registered: " + types.keySet());
            for (var entry : types.entrySet()) {
                java.lang.reflect.Field consumer = entry.getValue().getClass().getDeclaredField("messageConsumer");
                consumer.setAccessible(true);
                String wrapper = consumer.get(entry.getValue()).getClass().getName();
                // consumerMainThread wraps the handler in a SimpleChannel$MessageBuilder lambda that enqueues the work.
                h.assertTrue(wrapper.startsWith("net.minecraftforge.network.simple.SimpleChannel$MessageBuilder"),
                        ((Class<?>) entry.getKey()).getSimpleName() + " handled on the network thread by " + wrapper);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.succeed();
    }

    @GameTest(template = "arena", batch = "api_professions", timeoutTicks = 20)
    public static void professionLoadoutsUseModArmor(GameTestHelper h) {
        var slots = new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET};
        // Every listed id must be a real armor item for its slot: Modern Mayhem first, Apocalypse Now as fallback.
        NpcProfessions.LOADOUTS.forEach((key, loadout) -> {
            for (int i = 0; i < 4; i++) for (String id : loadout[i]) {
                h.assertTrue(!NpcProfessions.piece(new String[] {id}, slots[i]).isEmpty(), key + " " + slots[i] + " " + id);
            }
        });
        UUID base = UUID.randomUUID();
        Mob warden = npc(h, "survivor_7", 6, 6);
        h.assertTrue(NpcApi.assignProfession(warden, new ResourceLocation(LazarusNpcs.ID, "guard"), base).ok(), "warden guard");
        for (var slot : slots) {
            var worn = warden.getItemBySlot(slot);
            h.assertTrue(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(worn.getItem()).getNamespace().equals("mm"),
                    "warden guard wears Modern Mayhem kevlar in " + slot + ": " + worn);
        }
        h.assertTrue(NpcApi.assignProfession(warden, new ResourceLocation(LazarusNpcs.ID, "trader"), base).ok(), "now a trader");
        for (var slot : slots) h.assertTrue(warden.getItemBySlot(slot).isEmpty(), "trader has no armor in " + slot);

        Mob rover = npc(h, "survivor_4", 10, 6);
        h.assertTrue(NpcApi.assignProfession(rover, new ResourceLocation(LazarusNpcs.ID, "leader"), base).ok(), "rover leader");
        h.assertTrue(rover.getItemBySlot(slots[0]).is(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("apocalypsenow", "cowboy_hat_helmet"))), "rover leader hat");
        h.assertTrue(ProfessionSkins.key(rover).equals("rovers_leader"), "skin key " + ProfessionSkins.key(rover));
        h.assertTrue(ProfessionSkins.key(npc(h, "survivor_5", 12, 6)).isEmpty(), "ambient rovers keep their own skin");

        // A 1.8/1.9 resident keeps its profession but swaps the dyed leather vest for the loadout.
        Mob veteran = npc(h, "survivor_8", 14, 6);
        h.assertTrue(NpcApi.assignProfession(veteran, new ResourceLocation(LazarusNpcs.ID, "leader"), base).ok(), "veteran leader");
        net.minecraft.world.item.ItemStack vest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_CHESTPLATE);
        vest.getOrCreateTag().putBoolean("LazarusProfessionOutfit", true);
        veteran.setItemSlot(slots[1], vest);
        veteran.getPersistentData().getCompound("LazarusNpcsProfession").putInt("Version", 1);
        h.assertTrue(NpcApi.assignProfession(veteran, new ResourceLocation(LazarusNpcs.ID, "leader"), base).ok(), "re-assigned");
        h.assertTrue(veteran.getItemBySlot(slots[1]).is(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("apocalypsenow", "officer_beret_chestplate"))), "vest migrated: " + veteran.getItemBySlot(slots[1]));
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "api_professions", timeoutTicks = 20)
    public static void professionPersistsAndPatrolIsScoped(GameTestHelper h) {
        Mob rover = npc(h, "survivor_4", 6, 6);
        UUID base = UUID.randomUUID();
        ResourceLocation guard = new ResourceLocation(LazarusNpcs.ID, "guard");
        h.assertTrue(NpcApi.profession(rover).orElseThrow().getPath().equals("trader"), "ambient profession");
        h.assertTrue(NpcApi.assignProfession(rover, guard, base).ok(), "base guard assigned");
        h.assertTrue(NpcApi.profession(rover).orElseThrow().equals(guard), "profession returned");
        h.assertTrue(base.equals(NpcProfessions.base(rover)) && !NpcProfessions.canTrade(rover), "base membership and stock");
        h.assertTrue(rover.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).hasTag(), "outfit equipped");
        h.assertTrue(!NpcApi.assignProfession(rover, guard, UUID.randomUUID()).ok(), "cannot steal resident");
        BlockPos anchor = h.absolutePos(new BlockPos(8, 2, 8));
        h.assertTrue(NpcApi.assign(rover, new ScenarioAssignment(OWNER, base, ScenarioOrder.GUARD_AREA,
                GlobalPos.of(h.getLevel().dimension(), anchor), null, 9, 1200, 4)).ok(), "guard order");
        h.assertTrue(NpcApi.setPatrolRoute(rover, OWNER, base,
                List.of(anchor, anchor.east(3), anchor.south(3))).ok(), "route accepted");
        h.assertTrue(!NpcApi.setPatrolRoute(rover, OWNER, UUID.randomUUID(), List.of(anchor, anchor.east(3))).ok(),
                "other encounter cannot replace route");
        h.assertTrue(rover.getPersistentData().getCompound("LazarusNpcsPatrol").getLongArray("Points").length == 3,
                "route saved on entity");
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

    @GameTest(template = "arena", batch = "api_political_reputation", timeoutTicks = 20)
    public static void politicalChangeAffectsOnlyTheRivalAndPaysOnce(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 8, 8);
        UUID action = UUID.randomUUID();
        ResourceLocation reason = new ResourceLocation("testmod", "rover_mission");
        h.assertTrue(NpcApi.areRivals("rovers", "wardens") && !NpcApi.areRivals("haven", "wardens"), "relation map");
        h.assertTrue(NpcApi.adjustPoliticalReputation(player, "rovers", 8, reason, action).ok(), "political action accepted");
        h.assertTrue(NpcApi.reputation(player, "rovers") == 8 && NpcApi.reputation(player, "wardens") == -2,
                "both standings change in one action");
        h.assertTrue(NpcApi.adjustPoliticalReputation(player, "rovers", 8, reason, action).status()
                == ApiResult.Status.DUPLICATE, "same action cannot pay twice");
        h.assertTrue(NpcApi.adjustReputation(player, "rovers", 4, new ResourceLocation("testmod", "trade"), null).ok(),
                "ordinary trade accepted");
        h.assertTrue(NpcApi.reputation(player, "rovers") == 12 && NpcApi.reputation(player, "wardens") == -2,
                "ordinary reputation has no rival effect");
        finish(h, player);
    }

    // ---- Scenario orders

    @GameTest(template = "arena", batch = "ai_cover_reservations", timeoutTicks = 40)
    public static void wallCoverIsSharedWithoutCrowding(GameTestHelper h) {
        ServerPlayer threat = player(h, 25, 16);
        Mob first = npc(h, "military_01", 10, 14), second = npc(h, "military_02", 10, 18);
        first.setNoAi(true);
        second.setNoAi(true);
        for (int z = 9; z <= 23; z++) for (int y = 2; y <= 4; y++)
            h.getLevel().setBlock(h.absolutePos(new BlockPos(16, y, z)),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        var doctrine = HumanTactics.doctrine(first);
        Vec3 one = CoverFinder.find(first, List.of(threat), List.of(first, second), doctrine);
        Vec3 two = CoverFinder.find(second, List.of(threat), List.of(first, second), doctrine);
        h.assertTrue(one != null && two != null && !one.equals(two), "two NPCs reserve separate cover");
        h.assertTrue(!Exposure.visible(threat, one.add(0, first.getEyeHeight(), 0))
                && !Exposure.visible(threat, two.add(0, second.getEyeHeight(), 0)), "wall blocks both lines of fire");
        finish(h, threat);
    }

    @GameTest(template = "arena", batch = "ai_squad_roles", timeoutTicks = 40)
    public static void scenarioSquadsUseOwnerAndEncounter(GameTestHelper h) {
        Mob a = npc(h, "survivor_4", 8, 8), b = npc(h, "survivor_5", 10, 8),
                c = npc(h, "survivor_6", 12, 8), outsider = npc(h, "survivor_7", 14, 8);
        UUID group = UUID.randomUUID();
        for (Mob actor : List.of(a, b, c))
            h.assertTrue(NpcApi.assign(actor, ScenarioAssignment.at(OWNER, group, ScenarioOrder.WAIT,
                    at(h, 10, 8), 8, 200)).ok(), "assigned squad actor");
        h.assertTrue(NpcApi.assign(outsider, ScenarioAssignment.at(OWNER, UUID.randomUUID(), ScenarioOrder.WAIT,
                at(h, 14, 8), 8, 200)).ok(), "assigned outsider");
        List<Mob> members = Squads.scenario(a);
        h.assertTrue(members.size() == 3 && members.containsAll(List.of(a, b, c)) && !members.contains(outsider),
                "only actors with matching owner and encounter join");
        long flankers = members.stream().filter(actor -> Squads.role(actor, members,
                com.lazarus.npcs.api.NpcFactions.get("rovers").doctrine(), h.getLevel().getGameTime())
                == Squads.Role.FLANK).count();
        h.assertTrue(flankers == 1, "Rovers doctrine assigns one flank role in a trio");
        h.killAllEntities();
        h.succeed();
    }

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

    @GameTest(template = "arena", batch = "api_patrol_round", timeoutTicks = 1200)
    public static void guardCompletesFourPatrolPoints(GameTestHelper h) {
        Mob guard = npc(h, "military_01", 8, 8);
        UUID encounter = UUID.randomUUID();
        List<BlockPos> points = List.of(new BlockPos(8, 2, 8), new BlockPos(20, 2, 8),
                new BlockPos(20, 2, 20), new BlockPos(8, 2, 20)).stream().map(h::absolutePos).toList();
        h.assertTrue(NpcApi.assign(guard, ScenarioAssignment.at(OWNER, encounter, ScenarioOrder.GUARD_AREA,
                GlobalPos.of(h.getLevel().dimension(), points.get(0)), 18, 1190)).ok(), "patrol order assigned");
        h.assertTrue(NpcApi.setPatrolRoute(guard, OWNER, encounter, points).ok(), "four waypoints assigned");
        boolean[] reached = new boolean[4];
        h.succeedWhen(() -> {
            CompoundTag route = guard.getPersistentData().getCompound("LazarusNpcsPatrol");
            int index = route.getInt("Index");
            if (index >= 0 && index < reached.length
                    && guard.position().distanceTo(Vec3.atBottomCenterOf(points.get(index))) <= 3.5) reached[index] = true;
            h.assertTrue(guard.isAlive() && NpcApi.assignment(guard).isPresent(), "patrol remains active");
            h.assertTrue(reached[0] && reached[1] && reached[2] && reached[3],
                    "guard has not visited all four posts; index=" + index + " position=" + guard.position());
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

    @GameTest(template = "arena", batch = "api_task_giver_death", timeoutTicks = 40)
    public static void protectTaskFailsWhenGiverDies(GameTestHelper h) {
        setup();
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_1", 12, 10);
        NpcApi.offerTask(player, survivor, new ResourceLocation("testmod", "protect"), UUID.randomUUID(), null);
        NpcDialogues.choose(player, survivor.getUUID(), NpcDialogues.session(player).token(), 0);
        survivor.kill();
        h.succeedWhen(() -> {
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
