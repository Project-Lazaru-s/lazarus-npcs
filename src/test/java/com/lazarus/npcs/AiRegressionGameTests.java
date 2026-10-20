package com.lazarus.npcs;

import com.lazarus.npcs.ai.Pack;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Regressions reported from in-game testing of the tactical AI (NPCs 1.13.1). */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class AiRegressionGameTests {
    private static final ResourceLocation OWNER = new ResourceLocation(LazarusNpcs.ID, "ai_regression");

    private AiRegressionGameTests() {}

    private static GlobalPos at(GameTestHelper h, int x, int z) {
        return GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(x, 2, z)));
    }

    private static void arm(Mob actor) {
        actor.getPersistentData().remove(NpcGuns.KEY);
        NpcGuns.roll(actor, new NpcGuns.Arsenal(1f, 1f, java.util.Map.of("m4a1", 1)));
        NpcGuns.attach(actor, 1f);
    }

    /** An expedition walks its route back and forth instead of freezing at the end of it. */
    @GameTest(template = "arena", batch = "ai_travel_loop", timeoutTicks = 900)
    public static void expeditionKeepsWalkingAfterRouteEnd(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            UUID encounter = UUID.randomUUID();
            List<BlockPos> route = List.of(8, 12, 16, 20).stream()
                    .map(x -> h.absolutePos(new BlockPos(x, 2, 16))).toList();
            Mob[] actors = {NpcGameTests.npc(h, "survivor_4", 7, 15), NpcGameTests.npc(h, "survivor_4", 7, 17)};
            for (Mob actor : actors) {
                h.assertTrue(NpcApi.assign(actor, new ScenarioAssignment(OWNER, encounter, ScenarioOrder.TRAVEL,
                        GlobalPos.of(h.getLevel().dimension(), route.get(0)), null, 14, 2000, 3)).ok(), "travel order");
                h.assertTrue(NpcApi.setTravelRoute(actor, OWNER, encounter, route).ok(), "route");
            }
            Mob scout = Arrays.stream(actors).min(java.util.Comparator.comparing(Mob::getUUID)).orElseThrow();
            boolean[] reachedEnd = {false};
            h.succeedWhen(() -> {
                if (scout.getPersistentData().getCompound("LazarusNpcsTravel").getInt("Index") == 3) reachedEnd[0] = true;
                h.assertTrue(reachedEnd[0], "scout reached the last waypoint");
                h.assertTrue(scout.getPersistentData().getCompound("LazarusNpcsTravel").getInt("Index") <= 1,
                        "scout turned around and walked the route back");
                h.killAllEntities();
            });
        });
    }

    /** A member that cannot rejoin (stuck after a fight) delays a halt but never stops the squad for good. */
    @GameTest(template = "arena", batch = "ai_travel_straggler", timeoutTicks = 900)
    public static void stragglerDoesNotFreezeSquad(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            UUID encounter = UUID.randomUUID();
            List<BlockPos> route = List.of(8, 12, 16, 20).stream()
                    .map(x -> h.absolutePos(new BlockPos(x, 2, 8))).toList();
            Mob[] actors = {NpcGameTests.npc(h, "survivor_4", 7, 7), NpcGameTests.npc(h, "survivor_4", 7, 9)};
            for (Mob actor : actors) {
                h.assertTrue(NpcApi.assign(actor, new ScenarioAssignment(OWNER, encounter, ScenarioOrder.TRAVEL,
                        GlobalPos.of(h.getLevel().dimension(), route.get(0)), null, 14, 2000, 3)).ok(), "travel order");
                h.assertTrue(NpcApi.setTravelRoute(actor, OWNER, encounter, route).ok(), "route");
            }
            Mob scout = Arrays.stream(actors).min(java.util.Comparator.comparing(Mob::getUUID)).orElseThrow();
            Mob straggler = scout == actors[0] ? actors[1] : actors[0];
            straggler.setNoAi(true);
            straggler.teleportTo(straggler.getX(), straggler.getY(), straggler.getZ() + 16);
            h.succeedWhen(() -> {
                h.assertTrue(scout.getPersistentData().getCompound("LazarusNpcsTravel").getInt("Index") >= 3,
                        "scout passed the halt despite the straggler");
                h.killAllEntities();
            });
        });
    }

    /** Travel fights are centered on the squad, so contact far from the route start is still answered. */
    @GameTest(template = "arena", batch = "ai_travel_target", timeoutTicks = 40)
    public static void travellerKeepsTargetFarFromRouteStart(GameTestHelper h) {
        Mob actor = NpcGameTests.npc(h, "survivor_4", 20, 20);
        UUID encounter = UUID.randomUUID();
        BlockPos far = h.absolutePos(new BlockPos(2, 2, 2)).offset(-60, 0, 0);
        h.assertTrue(NpcApi.assign(actor, new ScenarioAssignment(OWNER, encounter, ScenarioOrder.TRAVEL,
                GlobalPos.of(h.getLevel().dimension(), far), null, 14, 1000, 3)).ok(), "travel order");
        Mob zombie = h.spawn(EntityType.HUSK, 24, 2, 20);
        zombie.setNoAi(true);
        actor.setTarget(zombie);
        h.runAfterDelay(30, () -> {
            h.assertTrue(actor.getTarget() == zombie, "target kept although the route began 60 blocks away");
            h.killAllEntities();
            h.succeed();
        });
    }

    /** A gunman whose target is behind a wall walks around it instead of standing still. */
    @GameTest(template = "arena", batch = "ai_combat_unseen", timeoutTicks = 400)
    public static void gunmanApproachesHiddenTarget(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            for (int z = 12; z <= 20; z++) for (int y = 2; y <= 4; y++)
                h.setBlock(new BlockPos(16, y, z), Blocks.STONE);
            var observer = NpcGameTests.player(h, 4, 4);
            Mob actor = NpcGameTests.npc(h, "survivor_4", 12, 16);
            arm(actor);
            actor.setInvulnerable(true);
            Mob zombie = h.spawn(EntityType.HUSK, 20, 2, 16);
            zombie.setNoAi(true);
            zombie.setInvulnerable(true);
            actor.setTarget(zombie);
            h.succeedWhen(() -> {
                h.assertTrue(actor.getTarget() == zombie, "target kept while approaching");
                h.assertTrue(actor.getSensing().hasLineOfSight(zombie), "gunman regained a line of fire");
                h.getLevel().getServer().getPlayerList().remove(observer);
                h.killAllEntities();
            });
        });
    }

    /** A target sealed out of reach and sight is dropped after a while instead of freezing the actor. */
    @GameTest(template = "arena", batch = "ai_combat_giveup", timeoutTicks = 400)
    public static void unreachableHiddenTargetIsDropped(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            for (int x = 19; x <= 23; x++) for (int z = 14; z <= 18; z++) for (int y = 2; y <= 5; y++)
                if (x == 19 || x == 23 || z == 14 || z == 18 || y == 5) h.setBlock(new BlockPos(x, y, z), Blocks.BEDROCK);
            var observer = NpcGameTests.player(h, 4, 4);
            Mob actor = NpcGameTests.npc(h, "survivor_4", 12, 16);
            arm(actor);
            Mob zombie = h.spawn(EntityType.HUSK, 21, 2, 16);
            zombie.setNoAi(true);
            actor.setTarget(zombie);
            h.succeedWhen(() -> {
                h.assertTrue(actor.getTarget() == null, "hidden target dropped");
                h.getLevel().getServer().getPlayerList().remove(observer);
                h.killAllEntities();
            });
        });
    }

    /** At arm's length a gunman still fights (point-blank fire or a weapon strike). */
    @GameTest(template = "arena", batch = "ai_combat_close", timeoutTicks = 160)
    public static void gunmanFightsAtPointBlank(GameTestHelper h) {
        var observer = NpcGameTests.player(h, 4, 4);
        Mob actor = NpcGameTests.npc(h, "survivor_4", 14, 16);
        arm(actor);
        actor.setInvulnerable(true);
        Mob zombie = h.spawn(EntityType.HUSK, 16, 2, 16);
        zombie.setNoAi(true);
        float health = zombie.getHealth();
        actor.setTarget(zombie);
        h.succeedWhen(() -> {
            h.assertTrue(zombie.getHealth() < health || !zombie.isAlive(), "close target was hurt");
            h.getLevel().getServer().getPlayerList().remove(observer);
            h.killAllEntities();
        });
    }

    /** Locked on a rival, a fighter ignores a zombie that is still far away. */
    @GameTest(template = "arena", batch = "ai_skirmish_focus", timeoutTicks = 20)
    public static void rivalFocusSurvivesDistantZombie(GameTestHelper h) {
        Mob rover = NpcGameTests.npc(h, "survivor_4", 8, 16), warden = NpcGameTests.npc(h, "survivor_7", 24, 16);
        UUID fight = UUID.randomUUID();
        for (Mob actor : List.of(rover, warden))
            h.assertTrue(NpcApi.assign(actor, ScenarioAssignment.at(OWNER, fight, ScenarioOrder.GUARD_AREA,
                    at(h, 16, 16), 30, 600)).ok(), "skirmish order");
        h.assertTrue(NpcScenarios.rival(rover, warden), "rovers and wardens are rivals");
        Mob zombie = h.spawn(EntityType.HUSK, 8, 2, 26);
        zombie.setNoAi(true);
        rover.setTarget(warden);
        rover.setTarget(zombie);
        h.assertTrue(rover.getTarget() == warden, "distant zombie did not steal the target");
        zombie.teleportTo(rover.getX() + 2, rover.getY(), rover.getZ());
        rover.setTarget(zombie);
        h.assertTrue(rover.getTarget() == zombie, "a zombie within striking distance takes over");
        h.killAllEntities();
        h.succeed();
    }

    /** Human NPCs climb a ladder to reach a platform; the path goes up the rungs. */
    @GameTest(template = "arena", batch = "ai_ladder", timeoutTicks = 400)
    public static void npcClimbsLadder(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            for (int y = 2; y <= 5; y++) {
                h.setBlock(new BlockPos(16, y, 16), Blocks.STONE);
                h.setBlock(new BlockPos(15, y, 16), Blocks.LADDER.defaultBlockState()
                        .setValue(LadderBlock.FACING, Direction.WEST));
            }
            for (int x = 16; x <= 19; x++) for (int z = 14; z <= 18; z++) h.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            Mob actor = NpcGameTests.npc(h, "survivor_4", 11, 16);
            // Only pathfinding is under test: wandering goals would compete with the navigation order.
            actor.goalSelector.removeAllGoals(goal -> !(goal instanceof net.minecraft.world.entity.ai.goal.FloatGoal));
            BlockPos top = h.absolutePos(new BlockPos(18, 6, 16));
            h.succeedWhen(() -> {
                if (actor.tickCount % 10 == 0 && actor.getNavigation().isDone())
                    actor.getNavigation().moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 1.0);
                // Reaching platform height proves the climb; stepping off the top rung is left to ordinary walking.
                h.assertTrue(actor.getY() >= top.getY() - 0.1 && actor.blockPosition().distSqr(top) <= 16,
                        "NPC climbed to the platform: " + actor.blockPosition() + " vs " + top);
                h.killAllEntities();
            });
        });
    }

    /** Deserters wear their own skins: a stable one of three per NPC, shipped in the jar; looters keep theirs. */
    @GameTest(template = "arena", batch = "ai_deserter_skins", timeoutTicks = 20)
    public static void desertersAndMilitaryWearTheirOwnSkins(GameTestHelper h) {
        Mob deserter = NpcGameTests.npc(h, "soldiers", 6, 6), other = NpcGameTests.npc(h, "soldiers_02", 8, 6);
        Mob looter = NpcGameTests.npc(h, "looter_1", 10, 6);
        String key = ProfessionSkins.key(deserter);
        h.assertTrue(key.matches("deserter_[1-3]") && key.equals(ProfessionSkins.key(deserter))
                && ProfessionSkins.key(other).startsWith("deserter_"), "deserter skin key: " + key);
        h.assertTrue(ProfessionSkins.key(looter).isEmpty(), "looters keep the Apocalypse Now look");
        Mob soldier = NpcGameTests.npc(h, "military_01", 12, 6), medic = NpcGameTests.npc(h, "military_medic", 14, 6);
        Mob cultist = NpcGameTests.npc(h, "cannibal", 16, 6);
        h.assertTrue(ProfessionSkins.key(cultist).matches("hollow_[1-3]"), "Hollow skin: " + ProfessionSkins.key(cultist));
        h.assertTrue(ProfessionSkins.key(soldier).matches("military_[1-3]") && ProfessionSkins.key(medic).equals("military_medic"),
                "military skins: " + ProfessionSkins.key(soldier) + ", " + ProfessionSkins.key(medic));
        List<String> files = new java.util.ArrayList<>(List.of("military_1", "military_2", "military_3", "military_medic"));
        for (int i = 1; i <= ProfessionSkins.DESERTER_SKINS; i++) files.add("deserter_" + i);
        for (int i = 1; i <= ProfessionSkins.HOLLOW_SKINS; i++) files.add("hollow_" + i);
        for (String file : files)
            h.assertTrue(ProfessionSkins.class.getResource("/assets/lazarusnpcs/textures/entity/profession/" + file + ".png") != null,
                    file + ".png is packaged");
        h.killAllEntities();
        h.succeed();
    }

    /** The goal itself (not just its search) walks a loose survivor to an untouched chest and takes from it. */
    @GameTest(template = "arena", batch = "ai_scavenge_goal", timeoutTicks = 600)
    public static void scavengerWalksToChest(GameTestHelper h) {
        EventsScenarioGameTests.loadedArena(h, () -> {
            BlockPos chest = new BlockPos(20, 2, 16);
            h.setBlock(chest, Blocks.CHEST);
            ((ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(chest)))
                    .setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 7L);
            var observer = NpcGameTests.player(h, 10, 26);
            Mob actor = NpcGameTests.npc(h, "survivor_1", 10, 16);
            h.assertTrue(NpcAiHooks.canScavenge(actor), "eligible scavenger");
            h.succeedWhen(() -> {
                h.assertTrue(!Pack.items(actor).isEmpty(), "survivor took something from the chest");
                h.getLevel().getServer().getPlayerList().remove(observer);
                h.killAllEntities();
            });
        });
    }
}
