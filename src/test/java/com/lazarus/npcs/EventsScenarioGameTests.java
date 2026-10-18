package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** "Última defesa" and "Falso sinal de rádio" from the real Lazarus Events JAR, driven like a player would. */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class EventsScenarioGameTests {
    private EventsScenarioGameTests() {}

    private static List<Mob> place(GameTestHelper h, String type, UUID encounter) {
        try {
            Method place = Class.forName("com.lazarus.events." + type).getDeclaredMethod("place",
                    ServerLevel.class, BlockPos.class, int.class, UUID.class);
            place.setAccessible(true);
            h.assertTrue((boolean) place.invoke(null, h.getLevel(), h.absolutePos(new BlockPos(16, 2, 16)), 0, encounter),
                    type + " placed");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return h.getLevel().getEntitiesOfClass(Mob.class, new AABB(h.absolutePos(new BlockPos(16, 2, 16))).inflate(14),
                mob -> mob.getTags().contains("lazarusevents:" + encounter) && NpcApi.identity(mob).isPresent());
    }

    private static void choose(ServerPlayer player, Mob npc, int option) {
        var session = NpcDialogues.session(player);
        if (session == null) throw new IllegalStateException("no dialogue open");
        NpcDialogues.choose(player, npc.getUUID(), session.token(), option);
    }

    private static Item item(String id) { return ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)); }

    private static void done(GameTestHelper h, ServerPlayer player) {
        h.getLevel().getServer().getPlayerList().remove(player);
        h.killAllEntities();
    }

    // ---- Última defesa

    @GameTest(template = "arena", batch = "events_lastdefense_hold", timeoutTicks = 200)
    public static void defendersHoldAndRewardScalesWithSurvivors(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> defenders = place(h, "LastDefenseEncounter", encounter);
        h.assertTrue(defenders.size() == 3 && defenders.stream().allMatch(d -> NpcApi.assignment(d)
                .map(a -> a.order() == ScenarioOrder.GUARD_AREA && a.owner().toString().equals("lazarusevents:lastdefense")).orElse(false)),
                "three defenders guard the barricade");
        String faction = NpcIdentity.faction(defenders.get(0));
        h.assertTrue(defenders.stream().allMatch(d -> NpcIdentity.faction(d).equals(faction)), "one faction");
        ServerPlayer player = NpcGameTests.player(h, 16, 20);
        Mob talker = defenders.stream().min((a, b) -> Double.compare(a.distanceTo(player), b.distanceTo(player))).orElseThrow();
        h.assertTrue(NpcDialogues.open(player, talker, DialogueTrigger.INTERACT, null), "call for help shown");
        h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusevents:lastdefense_call"), "Events dialogue");
        choose(player, talker, 0); // I'll hold with you
        choose(player, talker, 0); // Accept
        var task = NpcApi.encounterTask(player).orElseThrow();
        h.assertTrue(task.state() == TaskState.ACTIVE, "holding");
        int before = NpcReputation.get(player, faction);
        h.runAtTickTime(60, () -> {
            h.assertTrue(player.getPersistentData().getCompound(EncounterTasks.KEY).getCompound("Open").getCompound("Counters")
                    .getInt("held") >= 2, "seconds count while the player stays near");
            NpcApi.progressTask(player, task.instanceId(), "held", 117); // skip ahead to the last seconds
        });
        h.succeedWhen(() -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "line held");
            h.assertTrue(NpcReputation.get(player, faction) == before + 6, "+2 per standing defender");
            h.assertTrue(player.getInventory().countItem(item("apocalypsenow:canned_food")) == 3, "supplies paid once");
            done(h, player);
        });
    }

    @GameTest(template = "arena", batch = "events_lastdefense_overrun", timeoutTicks = 40)
    public static void barricadeFallsWhenAllDefendersDie(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> defenders = place(h, "LastDefenseEncounter", encounter);
        ServerPlayer player = NpcGameTests.player(h, 16, 20);
        NpcDialogues.open(player, defenders.get(0), DialogueTrigger.INTERACT, null);
        choose(player, defenders.get(0), 0);
        choose(player, defenders.get(0), 0);
        defenders.forEach(Mob::kill);
        h.runAtTickTime(3, () -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.FAILED).orElse(false), "overrun");
            h.assertTrue(player.getInventory().countItem(item("apocalypsenow:canned_food")) == 0, "no reward");
            done(h, player);
            h.succeed();
        });
    }

    // ---- Falso sinal de rádio

    @GameTest(template = "arena", batch = "events_falseradio_pay", timeoutTicks = 20)
    public static void payingTheAmbushBuysPassageOnlyWithEnoughFood(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> looters = place(h, "FalseRadioEncounter", encounter);
        h.assertTrue(looters.size() == 3 && looters.stream().allMatch(l -> "looter".equals(NpcApi.identity(l).get().role())),
                "three looters guard the bait");
        Mob looter = looters.get(0);
        ServerPlayer player = NpcGameTests.player(h, (int) (looter.getX() - h.absolutePos(BlockPos.ZERO).getX()) + 2,
                (int) (looter.getZ() - h.absolutePos(BlockPos.ZERO).getZ()));
        h.assertTrue(NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null), "demand shown");
        h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusevents:falseradio_demand")
                && NpcDialogues.session(player).options().equals(List.of("pay", "bluff", "fight", "leave")), "four answers");
        choose(player, looter, 0); // pay without food
        long now = h.getLevel().getGameTime();
        h.assertTrue(HumanEncounters.state(looter, player).getLong("PeaceUntil") <= now, "no food, no passage");
        player.getInventory().add(new ItemStack(item("apocalypsenow:canned_food"), 3));
        NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null);
        choose(player, looter, 0);
        h.assertTrue(player.getInventory().countItem(item("apocalypsenow:canned_food")) == 0, "three cans taken");
        h.assertTrue(looters.stream().allMatch(l -> HumanEncounters.state(l, player).getLong("PeaceUntil") > now),
                "the whole group lets the player pass");
        done(h, player);
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_falseradio_fight", timeoutTicks = 80)
    public static void fightingForTheCargoCompletesWhenTheLootersAreGone(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> looters = place(h, "FalseRadioEncounter", encounter);
        Mob looter = looters.get(0);
        ServerPlayer player = NpcGameTests.player(h, (int) (looter.getX() - h.absolutePos(BlockPos.ZERO).getX()) + 2,
                (int) (looter.getZ() - h.absolutePos(BlockPos.ZERO).getZ()));
        int wardens = NpcReputation.get(player, "wardens");
        NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null);
        choose(player, looter, 2); // The cargo is mine
        choose(player, looter, 0); // Accept
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.ACTIVE).orElse(false), "cargo task active");
        h.assertTrue(!NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null), "no more talking once fighting");
        looters.forEach(l -> l.hurt(h.getLevel().damageSources().playerAttack(player), 1_000));
        h.succeedWhen(() -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "cargo recovered");
            h.assertTrue(NpcReputation.get(player, "wardens") == wardens + 2, "Wardens approve");
            done(h, player);
        });
    }

    @GameTest(template = "arena", batch = "events_falseradio_bluff", timeoutTicks = 20)
    public static void bluffWorksAtMostOncePerPlayer(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> looters = place(h, "FalseRadioEncounter", encounter);
        Mob looter = looters.get(0);
        ServerPlayer player = NpcGameTests.player(h, (int) (looter.getX() - h.absolutePos(BlockPos.ZERO).getX()) + 2,
                (int) (looter.getZ() - h.absolutePos(BlockPos.ZERO).getZ()));
        NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null);
        choose(player, looter, 1); // bluff
        boolean believed = HumanEncounters.state(looter, player).getLong("PeaceUntil") > h.getLevel().getGameTime();
        h.assertTrue(!believed || looters.stream().allMatch(l -> NpcApi.assignment(l).map(a -> a.order() == ScenarioOrder.RETREAT).orElse(false)),
                "a believed bluff sends the group away");
        if (NpcDialogues.open(player, looter, DialogueTrigger.INTERACT, null))
            h.assertTrue(!NpcDialogues.session(player).options().contains("bluff"), "no second bluff");
        done(h, player);
        h.succeed();
    }

    private static Object call(String type, String name, Class<?>[] types, Object... args) {
        try {
            Method method = Class.forName("com.lazarus.events." + type).getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "events_wave_size", timeoutTicks = 20)
    public static void wavesAreBigger(GameTestHelper h) {
        Class<?>[] types = {int.class, long.class};
        h.assertTrue((int) call("LastDefenseEncounter", "waveSize", types, 1, 0L) == 6
                && (int) call("LastDefenseEncounter", "waveSize", types, 2, 0L) == 9
                && (int) call("LastDefenseEncounter", "waveSize", types, 3, 0L) == 12, "6, 9 and 12 infected");
        h.assertTrue((int) call("LastDefenseEncounter", "waveSize", types, 3, 25L) == 14
                && (int) call("LastDefenseEncounter", "waveSize", types, 3, 400L) == 16, "+1 per ten days, at most +4");
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_falseradio_confront", timeoutTicks = 400)
    public static void looterWalksUpAndMakesTheDemand(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> looters = place(h, "FalseRadioEncounter", encounter);
        BlockPos cargo = h.absolutePos(new BlockPos(16, 2, 16));
        ServerPlayer player = NpcGameTests.player(h, 16, 29); // 13 blocks from the cargo
        Class<?>[] types = {ServerLevel.class, UUID.class, BlockPos.class};
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % 20 == 0) call("FalseRadioEncounter", "confront", types, h.getLevel(), encounter, cargo);
        });
        h.runAtTickTime(25, () -> h.assertTrue(looters.stream().anyMatch(l -> HumanEncounters.state(l, player).getLong("PeaceUntil")
                > h.getLevel().getGameTime()), "the group holds fire while one of them walks up"));
        h.succeedWhen(() -> {
            var session = NpcDialogues.session(player);
            h.assertTrue(session != null && session.dialogue().toString().equals("lazarusevents:falseradio_demand"),
                    "the demand opened by itself");
            Mob speaker = looters.stream().filter(l -> l.getUUID().equals(session.npc())).findFirst().orElseThrow();
            h.assertTrue(speaker.distanceTo(player) <= 5, "made in person");
            h.assertTrue(looters.stream().allMatch(l -> l.getTags().contains("lazarusevents:confronted:" + player.getUUID())),
                    "confronted once");
            done(h, player);
        });
    }

    // ---- Military base

    @SuppressWarnings("unchecked")
    private static List<Mob> base(GameTestHelper h, UUID encounter) {
        Object placed = call("LazarusEvents", "place", new Class<?>[]{ServerLevel.class, BlockPos.class, String.class, int.class,
                boolean.class, UUID.class}, h.getLevel(), h.absolutePos(new BlockPos(6, 2, 6)), "military", 0, true, encounter);
        h.assertTrue((boolean) placed, "outpost placed");
        return h.getLevel().getEntitiesOfClass(Mob.class, new AABB(h.absolutePos(new BlockPos(6, 2, 6))).inflate(28),
                mob -> mob.getTags().contains("lazarusevents:" + encounter) && NpcApi.identity(mob).isPresent());
    }

    @GameTest(template = "arena", batch = "events_military_base", timeoutTicks = 60)
    public static void outpostHoldsGatesAndPaysThePerimeter(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> staff = base(h, encounter);
        h.assertTrue(staff.size() == 4, "four stationed: " + staff.size());
        h.assertTrue(staff.stream().allMatch(m -> NpcApi.assignment(m).map(a -> a.owner().toString().equals("lazarusevents:military_base")
                && a.order() == ("medic".equals(NpcIdentity.role(m)) ? ScenarioOrder.WAIT : ScenarioOrder.GUARD_AREA)).orElse(false)),
                "soldiers guard, the medic waits");
        Mob soldier = staff.stream().filter(m -> "military".equals(NpcIdentity.role(m))).findFirst().orElseThrow();
        BlockPos at = soldier.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
        ServerPlayer player = NpcGameTests.player(h, at.getX() + 2, at.getZ());

        NpcReputation.adjust(player, "military", -30);
        h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).options().equals(List.of("leave")), "turned away at -30");
        NpcDialogues.choose(player, soldier.getUUID(), NpcDialogues.session(player).token(), 0);
        NpcReputation.adjust(player, "military", 30);
        h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).dialogue().toString().equals("lazarusevents:military_gate")
                && NpcDialogues.session(player).options().contains("perimeter"), "restricted but can earn its way in");
        choose(player, soldier, 0); // perimeter
        choose(player, soldier, 0); // Accept
        var task = NpcApi.encounterTask(player).orElseThrow();
        h.assertTrue(task.state() == TaskState.ACTIVE, "perimeter task active");
        NpcApi.progressTask(player, task.instanceId(), "kills", 12);
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.READY).orElse(false), "report back");
        h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).dialogue().toString().equals("lazarusnpcs:task_giver"), "report dialogue");
        choose(player, soldier, 0); // Report
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "paid");
        h.assertTrue(NpcReputation.get(player, "military") == 3, "+3 Military");
        ItemStack ammo = java.util.stream.IntStream.range(0, 36).mapToObj(i -> player.getInventory().getItem(i))
                .filter(stack -> "tacz:ammo".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))).findFirst().orElse(ItemStack.EMPTY);
        h.assertTrue(ammo.getCount() == 30 && ammo.getOrCreateTag().getString("AmmoId").equals("tacz:556x45"), "30 rounds of 5.56");
        done(h, player);
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_passage_api", timeoutTicks = 20)
    public static void passageOnlyForTollTakers(GameTestHelper h) {
        ServerPlayer player = NpcGameTests.player(h, 10, 10);
        Mob cannibal = NpcGameTests.npc(h, "cannibal", 12, 10), soldier = NpcGameTests.npc(h, "soldiers", 14, 14);
        ResourceLocation reason = new ResourceLocation("testmod", "deal");
        h.assertTrue(NpcApi.grantPassage(player, cannibal, 200, reason).status() == ApiResult.Status.UNSUPPORTED_NPC, "cannibals never");
        h.assertTrue(NpcApi.grantPassage(player, soldier, 7_000, reason).status() == ApiResult.Status.LIMIT, "at most 6000 ticks");
        h.assertTrue(NpcApi.grantPassage(player, soldier, 200, reason).ok()
                && HumanEncounters.state(soldier, player).getLong("PeaceUntil") > h.getLevel().getGameTime(), "soldier passage");
        done(h, player);
        h.succeed();
    }
}
