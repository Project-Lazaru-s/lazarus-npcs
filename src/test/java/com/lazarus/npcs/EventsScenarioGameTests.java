package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.ai.RoutineGoal;
import com.lazarus.npcs.api.ai.NightDuty;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/** "Última defesa" and "Falso sinal de rádio" from the real Lazarus Events JAR, driven like a player would. */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class EventsScenarioGameTests {
    private EventsScenarioGameTests() {}

    @GameTest(template = "arena", batch = "events_base_professions", timeoutTicks = 30)
    public static void baseRosterAndSuccession(GameTestHelper h) {
        loadedArena(h, () -> rosterAndSuccession(h));
    }

    private static void rosterAndSuccession(GameTestHelper h) {
        try {
            Class<?> dataType = Class.forName("com.lazarus.events.FactionBaseData");
            Class<?> baseType = Class.forName("com.lazarus.events.FactionBaseData$Base");
            Class<?> basesType = Class.forName("com.lazarus.events.FactionBases");
            var constructor = dataType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object data = constructor.newInstance();
            Method add = dataType.getDeclaredMethod("add", String.class, BlockPos.class, int.class, String.class);
            add.setAccessible(true);
            Object base = add.invoke(data, "rovers", h.absolutePos(new BlockPos(16, 2, 16)), 0, "test");
            Method staff = basesType.getDeclaredMethod("staff", ServerLevel.class, dataType, baseType);
            staff.setAccessible(true);
            staff.invoke(null, h.getLevel(), data, base);
            Field roster = baseType.getDeclaredField("staff");
            roster.setAccessible(true);
            @SuppressWarnings("unchecked") List<UUID> ids = (List<UUID>) roster.get(base);
            h.assertTrue(ids.size() == 10 && ids.stream().distinct().count() == 10, "ten unique residents");
            String[] expected = {"leader", "guard", "guard", "armorer", "trader", "guard", "trader", "medic",
                    "expedition_soldier", "expedition_soldier"};
            for (int i = 0; i < expected.length; i++) {
                var actor = h.getLevel().getEntity(ids.get(i));
                h.assertTrue(actor instanceof Mob mob && NpcApi.profession(mob).orElseThrow().getPath().equals(expected[i]),
                        "slot " + i + " profession " + expected[i] + ": " + actor
                                + (actor instanceof Mob mob ? " has " + NpcApi.profession(mob) : "")
                                + " ticking " + h.getLevel().isPositionEntityTicking(h.absolutePos(new BlockPos(23, 2, 22)))
                                + " near " + h.getLevel().getEntitiesOfClass(Mob.class,
                                        new AABB(h.absolutePos(new BlockPos(23, 2, 22))).inflate(4)));
                h.assertTrue(actor instanceof Mob mob && RoutineGoal.routine(mob) != null,
                        "base resident " + i + " received a routine");
            }
            h.assertTrue(RoutineGoal.routine((Mob) h.getLevel().getEntity(ids.get(0))).night() == NightDuty.SLEEP,
                    "leader uses a real bed");
            h.assertTrue(RoutineGoal.routine((Mob) h.getLevel().getEntity(ids.get(1))).night() == NightDuty.WATCH,
                    "guard remains on watch");
            UUID guard = ids.get(1);
            ids.set(0, new UUID(0, 0));
            Method promote = basesType.getDeclaredMethod("promote", ServerLevel.class, baseType);
            promote.setAccessible(true);
            promote.invoke(null, h.getLevel(), base);
            h.assertTrue(ids.get(0).equals(guard) && ids.get(1).equals(new UUID(0, 0)), "guard takes leader slot");
            h.assertTrue(NpcApi.profession((Mob) h.getLevel().getEntity(guard)).orElseThrow().getPath().equals("leader"),
                    "successor has leader profession");
            h.assertTrue(RoutineGoal.routine((Mob) h.getLevel().getEntity(guard)).night() == NightDuty.SLEEP,
                    "successor receives leader routine");
            Field ready = baseType.getDeclaredField("leaderReadyAt");
            ready.setAccessible(true);
            h.assertTrue(ready.getLong(base) == h.getLevel().getGameTime() + 24_000L, "one-day transition saved");
            h.killAllEntities();
            h.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "events_expedition_profession", timeoutTicks = 20)
    public static void expeditionSoldiersStayOutsideBaseRoster(GameTestHelper h) {
        try {
            Class<?> dataType = Class.forName("com.lazarus.events.FactionExpeditionData");
            Class<?> expeditionType = Class.forName("com.lazarus.events.FactionExpeditions");
            var constructor = dataType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object data = constructor.newInstance();
            Method spawn = expeditionType.getDeclaredMethod("spawn", ServerLevel.class, dataType, BlockPos.class, List.class);
            spawn.setAccessible(true);
            BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
            h.assertTrue((boolean) spawn.invoke(null, h.getLevel(), data, center,
                    List.of(center, center.north(4), center.north(8), center.north(12))),
                    "expedition spawned");
            Field members = dataType.getDeclaredField("members");
            members.setAccessible(true);
            @SuppressWarnings("unchecked") List<UUID> ids = (List<UUID>) members.get(data);
            h.assertTrue(ids.size() == 3, "three expedition members");
            for (UUID id : ids) {
                var actor = h.getLevel().getEntity(id);
                h.assertTrue(actor instanceof Mob mob && NpcApi.profession(mob).orElseThrow().getPath().equals("expedition_soldier"),
                        "temporary actor profession");
                h.assertTrue(actor instanceof Mob mob && NpcApi.assignment(mob).orElseThrow().order() == ScenarioOrder.TRAVEL,
                        "member has travel order");
            }
            h.killAllEntities();
            h.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "events_expedition_retirement", timeoutTicks = 20)
    public static void unloadedRouteRetiresEncounter(GameTestHelper h) {
        try {
            Class<?> type = Class.forName("com.lazarus.events.FactionExpeditionData");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object data = constructor.newInstance();
            Field active = type.getDeclaredField("activeId"), route = type.getDeclaredField("route");
            Field retired = type.getDeclaredField("retired");
            active.setAccessible(true); route.setAccessible(true); retired.setAccessible(true);
            UUID id = UUID.randomUUID();
            active.set(data, id);
            route.set(data, new long[] {h.absolutePos(new BlockPos(10, 2, 10)).asLong()});
            Method retire = type.getDeclaredMethod("retire");
            retire.setAccessible(true);
            retire.invoke(data);
            h.assertTrue(active.get(data) == null && ((long[]) route.get(data)).length == 0,
                    "active unloaded expedition cleared");
            h.assertTrue(((List<?>) retired.get(data)).contains(id), "retired id remembered for unloaded actors");
            Method save = type.getDeclaredMethod("save", net.minecraft.nbt.CompoundTag.class);
            Method load = type.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class);
            save.setAccessible(true); load.setAccessible(true);
            Object reloaded = load.invoke(null, save.invoke(data, new net.minecraft.nbt.CompoundTag()));
            h.assertTrue(((List<?>) retired.get(reloaded)).contains(id), "retired id survives a save reload");
            h.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "zz_faction_base_terrain", timeoutTicks = 30)
    public static void factionBaseAcceptsMossSlabsAndOneBlockDip(GameTestHelper h) {
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        flatten(h, center, 12, 11);
        level.setBlock(center.offset(2, 0, 0), Blocks.MOSS_CARPET.defaultBlockState(), 3);
        level.setBlock(center.offset(3, 0, 0), Blocks.STONE_SLAB.defaultBlockState(), 3);
        level.setBlock(center.offset(-2, -2, 0), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(center.offset(-2, -1, 0), Blocks.AIR.defaultBlockState(), 3);
        try {
            Class<?> type = Class.forName("com.lazarus.events.FactionBases");
            Method check = type.getDeclaredMethod("footprintProblem", ServerLevel.class, BlockPos.class, String.class);
            check.setAccessible(true);
            String result = (String) check.invoke(null, level, center, "wardens");
            h.assertTrue(result == null, "moss, slab and one-block dip are safe: " + result);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.succeed();
    }

    /**
     * Force-loads the arena and its border, then runs body once those chunks tick entities: actors spawned at the
     * edge of a freshly placed arena are otherwise stored but invisible to getEntity.
     */
    static void loadedArena(GameTestHelper h, Runnable body) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        for (int cx = (origin.getX() - 16) >> 4; cx <= (origin.getX() + 48) >> 4; cx++)
            for (int cz = (origin.getZ() - 16) >> 4; cz <= (origin.getZ() + 48) >> 4; cz++)
                h.getLevel().setChunkForced(cx, cz, true);
        h.runAfterDelay(20, body);
    }

    @GameTest(template = "arena", batch = "zz_faction_base_terrain", timeoutTicks = 30)
    public static void factionBaseFellsNaturalTrees(GameTestHelper h) {
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        flatten(h, center, 12, 11);
        BlockPos trunk = center.offset(3, 0, 3);
        for (int y = 0; y < 12; y++) level.setBlock(trunk.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            if (x != 0 || z != 0) level.setBlock(trunk.offset(x, 10, z), Blocks.OAK_LEAVES.defaultBlockState(), 3);
        try {
            Object site = survey(center, level);
            h.assertTrue(site(site, "problem") == null, "a lone tree is felled, not a blocker: " + site(site, "problem"));
            Method terraform = Class.forName("com.lazarus.events.FactionBases").getDeclaredMethod("terraform",
                    ServerLevel.class, site.getClass(), String.class);
            terraform.setAccessible(true);
            terraform.invoke(null, level, site, "wardens");
            for (int y = 0; y < 12; y++)
                h.assertTrue(level.getBlockState(trunk.above(y)).isAir(), "trunk removed at height " + y);
            level.setBlock(center.offset(-3, 0, 2), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            String built = (String) site(survey(center, level), "problem");
            h.assertTrue(built != null && built.startsWith("obstacle"), "placed planks still reject: " + built);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.succeed();
    }

    /** Skirmish fighters are expedition soldiers, so they wear that profession's skin and armor. */
    @GameTest(template = "arena", batch = "events_skirmish_profession", timeoutTicks = 40)
    public static void skirmishFightersAreExpeditionSoldiers(GameTestHelper h) {
        loadedArena(h, () -> {
            try {
                // The real start() also needs open sky, which the test arena lacks; spawn() is what equips fighters.
                Class<?> conflictType = Class.forName("com.lazarus.events.FactionConflictData$Conflict");
                var constructor = conflictType.getDeclaredConstructor(UUID.class, BlockPos.class, long.class, boolean.class);
                constructor.setAccessible(true);
                BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
                Object fight = constructor.newInstance(UUID.randomUUID(), center, h.getLevel().getGameTime(), true);
                Method spawn = Class.forName("com.lazarus.events.FactionSkirmish").getDeclaredMethod("spawn",
                        ServerLevel.class, BlockPos.class, String.class, conflictType);
                spawn.setAccessible(true);
                for (String type : new String[] {"apocalypsenow:survivor_4", "apocalypsenow:survivor_7"}) {
                    Mob mob = (Mob) spawn.invoke(null, h.getLevel(), center.west(4), type, fight);
                    h.assertTrue(mob != null && NpcApi.profession(mob).map(p -> p.getPath().equals("expedition_soldier")).orElse(false),
                            type + " fighter is an expedition soldier: " + (mob == null ? null : NpcApi.profession(mob)));
                    h.assertTrue(!mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(),
                            type + " fighter wears a helmet");
                    h.assertTrue(ProfessionSkins.key(mob).endsWith("_expedition_soldier"), "skin key " + ProfessionSkins.key(mob));
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "events_skirmish_save_migration", timeoutTicks = 20)
    public static void legacySkirmishSaveBecomesGenericSides(GameTestHelper h) {
        try {
            net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
            net.minecraft.nbt.CompoundTag active = new net.minecraft.nbt.CompoundTag();
            active.putUUID("Id", UUID.randomUUID());
            active.putLong("Pos", h.absolutePos(new BlockPos(16, 2, 16)).asLong());
            active.put("Rovers", new net.minecraft.nbt.ListTag());
            active.put("Wardens", new net.minecraft.nbt.ListTag());
            root.put("Active", active);
            Class<?> dataClass = Class.forName("com.lazarus.events.FactionConflictData");
            Method load = dataClass.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class);
            load.setAccessible(true);
            Object data = load.invoke(null, root);
            Method save = dataClass.getDeclaredMethod("save", net.minecraft.nbt.CompoundTag.class);
            save.setAccessible(true);
            net.minecraft.nbt.CompoundTag saved = (net.minecraft.nbt.CompoundTag) save.invoke(data,
                    new net.minecraft.nbt.CompoundTag());
            h.assertTrue(saved.getInt("DataVersion") == 2, "new save version");
            h.assertTrue(saved.getCompound("Active").getString("FirstFaction").equals("rovers")
                    && saved.getCompound("Active").getString("SecondFaction").equals("wardens"),
                    "legacy combat sides preserved");
            h.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "events_unknown_faction_save", timeoutTicks = 20)
    public static void unavailableFactionBaseSurvivesSave(GameTestHelper h) {
        try {
            net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
            net.minecraft.nbt.CompoundTag unknown = new net.minecraft.nbt.CompoundTag();
            unknown.putUUID("Id", UUID.randomUUID());
            unknown.putString("Faction", "future_faction");
            unknown.putLong("Pos", h.absolutePos(new BlockPos(16, 2, 16)).asLong());
            net.minecraft.nbt.ListTag bases = new net.minecraft.nbt.ListTag();
            bases.add(unknown);
            root.put("Bases", bases);
            Class<?> dataClass = Class.forName("com.lazarus.events.FactionBaseData");
            Method load = dataClass.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class);
            load.setAccessible(true);
            Object data = load.invoke(null, root);
            Method save = dataClass.getDeclaredMethod("save", net.minecraft.nbt.CompoundTag.class);
            save.setAccessible(true);
            net.minecraft.nbt.CompoundTag saved = (net.minecraft.nbt.CompoundTag) save.invoke(data,
                    new net.minecraft.nbt.CompoundTag());
            h.assertTrue(saved.getList("Bases", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 1,
                    "unavailable base preserved");
            h.assertTrue(saved.getList("Bases", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
                    .getString("Faction").equals("future_faction"), "faction id retained");
            h.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "arena", batch = "events_expedition_lead", timeoutTicks = 40)
    public static void expeditionSoldierPointsToUnseenOutpost(GameTestHelper h) {
        loadedArena(h, () -> expeditionLead(h));
    }

    /** Uses the world's real expedition and base records (the dialogue reads them), restoring them afterwards. */
    private static void expeditionLead(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Object expeditions = null, bases = null, base = null, existing = null;
        try {
            Class<?> expeditionData = Class.forName("com.lazarus.events.FactionExpeditionData");
            Class<?> baseData = Class.forName("com.lazarus.events.FactionBaseData");
            Method getExpeditions = expeditionData.getDeclaredMethod("get", ServerLevel.class);
            getExpeditions.setAccessible(true);
            expeditions = getExpeditions.invoke(null, level);
            Field active = expeditionData.getDeclaredField("activeId");
            active.setAccessible(true);
            h.assertTrue(active.get(expeditions) == null, "no expedition already running");
            Method spawn = Class.forName("com.lazarus.events.FactionExpeditions").getDeclaredMethod("spawn",
                    ServerLevel.class, expeditionData, BlockPos.class, List.class);
            spawn.setAccessible(true);
            BlockPos center = h.absolutePos(new BlockPos(12, 2, 16));
            h.assertTrue((boolean) spawn.invoke(null, level, expeditions, center,
                    List.of(center, center.north(4), center.north(8), center.north(12))),
                    "expedition spawned");
            Field members = expeditionData.getDeclaredField("members");
            members.setAccessible(true);
            @SuppressWarnings("unchecked") List<UUID> ids = (List<UUID>) members.get(expeditions);
            Mob soldier = (Mob) level.getEntity(ids.get(0));
            String faction = NpcApi.identity(soldier).orElseThrow().faction();
            ServerPlayer player = NpcGameTests.player(h, 14, 16);

            Method getBases = baseData.getDeclaredMethod("get", ServerLevel.class);
            getBases.setAccessible(true);
            bases = getBases.invoke(null, level);
            Method byFaction = baseData.getDeclaredMethod("faction", String.class);
            byFaction.setAccessible(true);
            // A base left in the persistent test world by natural generation is set aside for the test.
            existing = byFaction.invoke(bases, faction);
            Field baseList = baseData.getDeclaredField("bases");
            baseList.setAccessible(true);
            if (existing != null) ((List<?>) baseList.get(bases)).remove(existing);
            h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null), "soldier talks");
            h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusevents:expedition_lead"),
                    "lead dialogue without a base: " + NpcDialogues.session(player).dialogue());

            // A distant, unloaded outpost: the base tick skips it, the soldier can point to it.
            Method add = baseData.getDeclaredMethod("add", String.class, BlockPos.class, int.class, String.class);
            add.setAccessible(true);
            BlockPos outpost = center.offset(600, 0, -300);
            base = add.invoke(bases, faction, outpost, 0, "test");
            h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null), "soldier talks again");
            choose(player, soldier, 0); // Mark it in my journal
            h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusnpcs:task_offer"), "lead offered");
            choose(player, soldier, 0); // Accept
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.ACTIVE).orElse(false), "lead accepted");
            String journal = EncounterTasks.journal(player);
            h.assertTrue(journal.contains("Destination: " + outpost.getX() + ", " + outpost.getY() + ", " + outpost.getZ()),
                    "journal points to the outpost: " + journal);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        } finally {
            try {
                if (bases != null) {
                    Field list = bases.getClass().getDeclaredField("bases");
                    list.setAccessible(true);
                    @SuppressWarnings("unchecked") List<Object> records = (List<Object>) list.get(bases);
                    if (base != null) records.remove(base);
                    if (existing != null) records.add(existing);
                }
                if (expeditions != null) {
                    Field active = expeditions.getClass().getDeclaredField("activeId");
                    active.setAccessible(true);
                    active.set(expeditions, null);
                    Field members = expeditions.getClass().getDeclaredField("members");
                    members.setAccessible(true);
                    ((List<?>) members.get(expeditions)).clear();
                }
            } catch (ReflectiveOperationException ignored) {
                // Cleanup only.
            }
        }
        h.killAllEntities();
        h.succeed();
    }

    /** Flat stone footprint around center with clear air above, for the terrain survey tests. */
    private static void flatten(GameTestHelper h, BlockPos center, int rx, int rz) {
        for (int x = -rx; x <= rx; x++) for (int z = -rz; z <= rz; z++) {
            BlockPos ground = center.offset(x, -1, z);
            h.getLevel().setBlock(ground, Blocks.STONE.defaultBlockState(), 3);
            for (int y = 1; y <= 10; y++) h.getLevel().setBlock(ground.above(y), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static Object survey(BlockPos hint, ServerLevel level) throws ReflectiveOperationException {
        Method survey = Class.forName("com.lazarus.events.FactionBases")
                .getDeclaredMethod("survey", ServerLevel.class, BlockPos.class, String.class);
        survey.setAccessible(true);
        return survey.invoke(null, level, hint, "wardens");
    }

    private static Object site(Object site, String accessor) throws ReflectiveOperationException {
        Method method = site.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(site);
    }

    @GameTest(template = "arena", batch = "zz_faction_base_terrain", timeoutTicks = 30)
    public static void factionBaseClearsPlantsAndLevelsSlopes(GameTestHelper h) {
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        flatten(h, center, 12, 11);
        level.setBlock(center.offset(1, 0, 1), Blocks.POPPY.defaultBlockState(), 3);
        level.setBlock(center.offset(2, 0, -2), Blocks.OAK_SAPLING.defaultBlockState(), 3);
        level.setBlock(center.offset(-1, 0, 2), Blocks.GRASS.defaultBlockState(), 3);
        net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, Blocks.ROSE_BUSH.defaultBlockState(),
                center.offset(-3, 0, -3), 3);
        level.setBlock(center.offset(0, 0, -4), Blocks.OAK_LEAVES.defaultBlockState(), 3);
        level.setBlock(center.offset(5, 0, 4), Blocks.DIRT.defaultBlockState(), 3);   // +2 rise
        level.setBlock(center.offset(5, 1, 4), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        level.setBlock(center.offset(-5, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState(), 3); // +1 rise
        level.setBlock(center.offset(-4, -1, 3), Blocks.AIR.defaultBlockState(), 3);  // -2 dip
        level.setBlock(center.offset(-4, -2, 3), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(center.offset(-4, -3, 3), Blocks.STONE.defaultBlockState(), 3);
        try {
            Object site = survey(center, level);
            h.assertTrue(site(site, "problem") == null, "vegetation and gentle slopes are accepted: " + site(site, "problem"));
            h.assertTrue((int) site(site, "floor") == center.getY() - 1, "floor is the median surface");
            Method terraform = Class.forName("com.lazarus.events.FactionBases").getDeclaredMethod("terraform",
                    ServerLevel.class, site.getClass(), String.class);
            terraform.setAccessible(true);
            terraform.invoke(null, level, site, "wardens");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        for (BlockPos cleared : List.of(center.offset(1, 0, 1), center.offset(2, 0, -2), center.offset(-1, 0, 2),
                center.offset(-3, 0, -3), center.offset(-3, 1, -3), center.offset(0, 0, -4),
                center.offset(5, 0, 4), center.offset(5, 1, 4), center.offset(-5, 0, 0)))
            h.assertTrue(level.getBlockState(cleared).isAir(), "cleared " + cleared.toShortString());
        h.assertTrue(level.getBlockState(center.offset(-4, -1, 3)).is(Blocks.DIRT)
                && level.getBlockState(center.offset(-4, -2, 3)).is(Blocks.DIRT), "dip filled");
        h.assertTrue(level.getBlockState(center.offset(-4, -3, 3)).is(Blocks.STONE), "fill stops on solid ground");
        h.succeed();
    }

    @GameTest(template = "arena", batch = "zz_faction_base_terrain", timeoutTicks = 30)
    public static void factionBaseRejectsTreesAndCliffs(GameTestHelper h) {
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        try {
            flatten(h, center, 12, 11);
            for (int y = 0; y < 4; y++) level.setBlock(center.offset(3, y, 3), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            String tree = (String) site(survey(center, level), "problem");
            h.assertTrue(tree != null && tree.startsWith("obstacle") && tree.contains("oak_planks"), "built pillar rejects: " + tree);
            flatten(h, center, 12, 11);
            for (int y = 0; y < 4; y++) level.setBlock(center.offset(3, y, 3), Blocks.STONE.defaultBlockState(), 3);
            String cliff = (String) site(survey(center, level), "problem");
            h.assertTrue(cliff != null && cliff.startsWith("too steep"), "four-block rise rejects: " + cliff);
            flatten(h, center, 12, 11);
            level.setBlock(center.offset(-2, 0, 1), Blocks.CHEST.defaultBlockState(), 3);
            String chest = (String) site(survey(center, level), "problem");
            h.assertTrue(chest != null && chest.startsWith("obstacle"), "containers are never cleared: " + chest);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.succeed();
    }

    @GameTest(template = "arena", batch = "zz_faction_base_terrain", timeoutTicks = 30)
    public static void factionBaseSearchMovesAwayFromObstacle(GameTestHelper h) {
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        flatten(h, center, 15, 14);
        for (int y = 0; y < 4; y++) level.setBlock(center.offset(9, y, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        try {
            Method search = Class.forName("com.lazarus.events.FactionBases").getDeclaredMethod("search",
                    ServerLevel.class, BlockPos.class, int.class, int.class, String.class, boolean.class);
            search.setAccessible(true);
            Object result = search.invoke(null, level, center, 4, 4, "rovers", false);
            Object best = site(result, "best");
            h.assertTrue(best != null, "a nearby anchor is found: " + result);
            BlockPos anchor = (BlockPos) site(best, "anchor");
            h.assertTrue(anchor.getX() == center.getX() - 4 && anchor.getY() == center.getY(),
                    "anchor shifted west of the trunk: " + anchor.toShortString());
            @SuppressWarnings("unchecked") java.util.Map<String, Integer> reasons =
                    (java.util.Map<String, Integer>) site(result, "reasons");
            h.assertTrue(reasons.getOrDefault("obstacle", 0) == 6, "six anchors overlap the trunk: " + reasons);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.succeed();
    }

    /** Builds a real base of the current model on a flat arena and checks posts, stock, patrols and the depot. */
    private static void placesBase(GameTestHelper h, String faction, int rx, int rz) {
        loadedArena(h, () -> placeBase(h, faction, rx, rz));
    }

    private static void placeBase(GameTestHelper h, String faction, int rx, int rz) {
        boolean refuge = faction.equals("haven"); // no rivals: six residents, one patrol, no theft missions
        BlockPos center = h.absolutePos(new BlockPos(16, 2, 16));
        ServerLevel level = h.getLevel();
        flatten(h, center, rx, rz);
        try {
            Class<?> dataType = Class.forName("com.lazarus.events.FactionBaseData");
            Class<?> basesType = Class.forName("com.lazarus.events.FactionBases");
            var constructor = dataType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object data = constructor.newInstance();
            Method survey = basesType.getDeclaredMethod("survey", ServerLevel.class, BlockPos.class, String.class);
            survey.setAccessible(true);
            Object site = survey.invoke(null, level, center, faction);
            h.assertTrue(site(site, "problem") == null, faction + " site accepted: " + site(site, "problem"));
            Method create = basesType.getDeclaredMethod("create", ServerLevel.class, dataType, site.getClass(),
                    String.class, String.class);
            create.setAccessible(true);
            Object base = create.invoke(null, level, data, site, faction, "test");
            h.assertTrue(base != null, faction + " template placed");
            Field roster = base.getClass().getDeclaredField("staff");
            roster.setAccessible(true);
            @SuppressWarnings("unchecked") List<UUID> ids = (List<UUID>) roster.get(base);
            h.assertTrue(ids.size() == (refuge ? 6 : 10) && ids.stream().allMatch(id -> h.getLevel().getEntity(id) instanceof Mob),
                    faction + " garrison spawned: " + ids.stream().map(id -> String.valueOf(h.getLevel().getEntity(id))).toList());
            for (int slot = 1; slot <= (refuge ? 1 : 2); slot++) {
                Mob guard = (Mob) h.getLevel().getEntity(ids.get(slot));
                h.assertTrue(guard.getPersistentData().contains("LazarusNpcsPatrol"), faction + " guard " + slot + " has a patrol route");
            }
            for (UUID id : ids) {
                BlockPos feet = h.getLevel().getEntity(id).blockPosition();
                h.assertTrue(level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty(),
                        faction + " resident not inside a block at " + feet.toShortString() + ": "
                                + level.getBlockState(feet) + " / " + level.getBlockState(feet.above())
                                + " (" + NpcProfessions.assigned(h.getLevel().getEntity(id) instanceof Mob m ? m : null) + ")");
            }
            Method destination = Class.forName("com.lazarus.events.FactionMissions").getDeclaredMethod("destination",
                    ServerLevel.class, base.getClass(), boolean.class);
            destination.setAccessible(true);
            net.minecraft.core.GlobalPos depot = (net.minecraft.core.GlobalPos) destination.invoke(null, level, base, true);
            h.assertTrue(level.getBlockState(depot.pos()).is(Blocks.BARREL), faction + " depot barrel at " + depot.pos().toShortString());
            h.assertTrue(level.getBlockEntity(depot.pos()) instanceof net.minecraft.world.Container container
                    && !container.getItem(0).isEmpty(), faction + " depot stocked");
            if (refuge) {
                havenServices(h, ids);
                h.killAllEntities();
                h.succeed();
                return;
            }

            // The journal lists the rival depot as the task destination.
            Mob leader = (Mob) h.getLevel().getEntity(ids.get(0));
            ServerPlayer player = NpcGameTests.player(h, (int) (leader.getX() - h.absolutePos(BlockPos.ZERO).getX()) + 1,
                    (int) (leader.getZ() - h.absolutePos(BlockPos.ZERO).getZ()));
            Field baseId = base.getClass().getDeclaredField("id");
            baseId.setAccessible(true);
            var offered = NpcApi.offerTask(player, leader, new ResourceLocation("lazarusevents", faction + "_theft"),
                    (UUID) baseId.get(base), depot);
            h.assertTrue(offered.ok(), "theft offered: " + offered.message());
            String journal = EncounterTasks.journal(player);
            h.assertTrue(journal.contains("Destination: " + depot.pos().getX() + ", " + depot.pos().getY() + ", "
                    + depot.pos().getZ()), "journal shows the depot: " + journal);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "zz_faction_base_layout", timeoutTicks = 40)
    public static void roversCampPlacesWorkingLayout(GameTestHelper h) { placesBase(h, "rovers", 11, 10); }

    @GameTest(template = "arena", batch = "zz_faction_base_layout", timeoutTicks = 40)
    public static void havenRefugePlacesWorkingLayout(GameTestHelper h) { placesBase(h, "haven", 10, 9); }

    /** Refuge services: Haven residents wear their profession, the trader discounts for friends, Haven takes guard duty. */
    private static void havenServices(GameTestHelper h, List<UUID> ids) {
        Mob leader = (Mob) h.getLevel().getEntity(ids.get(0)), guard = (Mob) h.getLevel().getEntity(ids.get(1));
        Mob trader = (Mob) h.getLevel().getEntity(ids.get(3)), medic = (Mob) h.getLevel().getEntity(ids.get(4));
        h.assertTrue("haven".equals(NpcIdentity.faction(leader)) && "leader".equals(NpcProfessions.assigned(leader))
                && "trader".equals(NpcProfessions.assigned(trader)) && "stabilize".equals(NpcProfessions.baseService(medic)),
                "Haven professions and the medic's service");
        h.assertTrue(!guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty(), "Haven guard wears a vest");
        BlockPos at = trader.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
        ServerPlayer player = NpcGameTests.player(h, at.getX() + 1, at.getZ());
        NpcMerchant merchant = new NpcMerchant(trader, "survivor");
        merchant.applyDiscount(player);
        h.assertTrue(!merchant.getOffers().isEmpty() && merchant.getOffers().stream().allMatch(o -> o.getSpecialPriceDiff() == 0),
                "no discount for strangers");
        NpcReputation.adjust(player, "haven", 10);
        merchant.applyDiscount(player);
        h.assertTrue(merchant.getOffers().stream().allMatch(o -> o.getSpecialPriceDiff() < 0), "15% off for friends of the Haven");
        Mob loose = NpcGameTests.npc(h, "survivor_1", 4, 4);
        h.assertTrue(NpcContracts.canGuard(loose) && !NpcContracts.canGuard(guard), "loose Haven survivors take guard duty, residents stay home");
        h.getLevel().getServer().getPlayerList().remove(player);
    }

    @GameTest(template = "arena", batch = "zz_faction_base_layout", timeoutTicks = 40)
    public static void wardensFortPlacesWorkingLayout(GameTestHelper h) { placesBase(h, "wardens", 12, 11); }

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
                "three looters guard the bait; found " + looters.size() + " " + looters.stream()
                        .map(l -> l.getType().toString() + " at " + l.blockPosition()).toList()
                        + "; all tagged: " + h.getLevel().getEntitiesOfClass(Mob.class,
                                new AABB(h.absolutePos(new BlockPos(16, 2, 16))).inflate(32),
                                mob -> mob.getTags().contains("lazarusevents:" + encounter)).stream()
                                .map(mob -> mob.getType() + " at " + mob.blockPosition()).toList());
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

    // ---- Military collapse (Haven/Military V1, phase 1)

    @GameTest(template = "arena", batch = "events_military_collapse", timeoutTicks = 20)
    public static void checkpointsFadeWithWorldAge(GameTestHelper h) {
        Class<?>[] types = {int.class, int.class, int.class, int.class};
        double[] early = (double[]) call("MilitaryCollapse", "weights", types, 5, 10, 21, 36);
        double[] active = (double[]) call("MilitaryCollapse", "weights", types, 15, 10, 21, 36);
        double[] decline = (double[]) call("MilitaryCollapse", "weights", types, 25, 10, 21, 36);
        double[] collapse = (double[]) call("MilitaryCollapse", "weights", types, 40, 10, 21, 36);
        h.assertTrue(early[0] == 0 && early[1] == 0, "nothing military before day 10");
        h.assertTrue(active[0] == 1 && active[1] == 0, "only active checkpoints until day 21");
        h.assertTrue(decline[0] == 0.5 && decline[1] > 0, "half the active ones, abandoned appear");
        h.assertTrue(collapse[0] < decline[0] && collapse[1] > decline[1], "abandoned dominate later");
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_military_abandoned", timeoutTicks = 40)
    public static void abandonedCheckpointHasLogAndNoSoldiers(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        Object placed = call("LazarusEvents", "place", new Class<?>[]{ServerLevel.class, BlockPos.class, String.class, int.class,
                boolean.class, UUID.class}, h.getLevel(), h.absolutePos(new BlockPos(6, 2, 6)), "abandoned", 0, true, encounter);
        h.assertTrue((boolean) placed, "abandoned checkpoint placed");
        AABB area = new AABB(h.absolutePos(new BlockPos(6, 2, 6))).inflate(28);
        List<Mob> actors = h.getLevel().getEntitiesOfClass(Mob.class, area, mob -> mob.getTags().contains("lazarusevents:" + encounter));
        h.assertTrue(actors.stream().noneMatch(mob -> NpcApi.identity(mob).isPresent()), "no soldiers left");
        h.assertTrue(actors.size() >= 2 && actors.stream().allMatch(mob -> mob instanceof net.minecraft.world.entity.monster.Zombie
                && !mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()), "infected soldiers in uniform: " + actors.size());
        boolean log = false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ)))
            if (h.getLevel().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern
                    && lectern.getBook().is(net.minecraft.world.item.Items.WRITTEN_BOOK)) log = true;
        h.assertTrue(log, "a duty log lies on a lectern");
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_military_supply", timeoutTicks = 60)
    public static void supplyDropNeedsStandingFoodAndCooldown(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> staff = base(h, encounter);
        Mob soldier = staff.stream().filter(m -> "military".equals(NpcIdentity.role(m))).findFirst().orElseThrow();
        BlockPos at = soldier.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
        ServerPlayer player = NpcGameTests.player(h, at.getX() + 2, at.getZ());
        Item flare = item("dyairdrop:flaregun0"), food = item("apocalypsenow:canned_food");
        Class<?>[] types = {ServerPlayer.class, UUID.class};
        call("MilitaryBaseEncounter", "requestDrop", types, player, encounter);
        h.assertTrue(player.getInventory().countItem(flare) == 0, "no drop for strangers");
        h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null)
                && !NpcDialogues.session(player).options().contains("supply"), "no supply option below Friendly");
        NpcReputation.adjust(player, "military", 15);
        h.assertTrue(NpcDialogues.open(player, soldier, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).options().contains("supply"), "supply option at Friendly");
        call("MilitaryBaseEncounter", "requestDrop", types, player, encounter);
        h.assertTrue(player.getInventory().countItem(flare) == 0, "no drop without food");
        player.getInventory().add(new ItemStack(food, 4));
        call("MilitaryBaseEncounter", "requestDrop", types, player, encounter);
        h.assertTrue(player.getInventory().countItem(flare) == 1, "flare gun handed over");
        h.assertTrue(player.getInventory().countItem(food) == 1, "three canned food taken");
        h.assertTrue(NpcReputation.get(player, "military") == 11, "-4 Military: " + NpcReputation.get(player, "military"));
        player.getInventory().add(new ItemStack(food, 3));
        call("MilitaryBaseEncounter", "requestDrop", types, player, encounter);
        h.assertTrue(player.getInventory().countItem(flare) == 1 && player.getInventory().countItem(food) == 4,
                "cooldown: nothing traded twice");
        done(h, player);
        h.succeed();
    }

    // ---- Vaccine project (Haven/Military V1, phase 2)

    @GameTest(template = "arena", batch = "events_vaccine_task", timeoutTicks = 60)
    public static void vaccineDeliveriesPayAndEveryThirdGivesSerum(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        List<Mob> staff = base(h, encounter);
        Mob medic = staff.stream().filter(m -> "medic".equals(NpcIdentity.role(m))).findFirst().orElseThrow();
        BlockPos at = medic.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
        ServerPlayer player = NpcGameTests.player(h, at.getX() + 2, at.getZ());
        Item sample = item("lazarusevents:infected_sample"), serum = item("lazarusevents:experimental_serum");
        h.assertTrue(sample != null && serum != null && sample != net.minecraft.world.item.Items.AIR, "vaccine items registered");
        h.assertTrue(NpcDialogues.open(player, medic, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).options().contains("vaccine"), "the medic talks about the vaccine");
        choose(player, medic, NpcDialogues.session(player).options().indexOf("vaccine"));
        choose(player, medic, 0); // Accept
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.ACTIVE
                && t.taskId().toString().equals("lazarusevents:military_vaccine")).orElse(false), "vaccine task active");
        player.getInventory().add(new ItemStack(sample, 3));
        h.assertTrue(NpcDialogues.open(player, medic, DialogueTrigger.INTERACT, null), "hand-over offered");
        choose(player, medic, 0); // Deliver
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.READY).orElse(false), "samples handed over");
        h.assertTrue(NpcDialogues.open(player, medic, DialogueTrigger.INTERACT, null)
                && NpcDialogues.session(player).dialogue().toString().equals("lazarusnpcs:task_giver"), "report dialogue");
        choose(player, medic, 0); // Report
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false), "delivered");
        h.assertTrue(player.getInventory().countItem(sample) == 0 && NpcReputation.get(player, "military") == 3, "samples taken, +3 Military");
        h.assertTrue(player.getInventory().countItem(item("apocalypsenow:antibiotics")) == 1, "medical reward");
        h.assertTrue(player.getInventory().countItem(serum) == 0, "no serum on the first delivery");
        Class<?>[] types = {ServerPlayer.class};
        h.assertTrue(!(boolean) call("VaccineProject", "completed", types, player), "second delivery: no serum");
        h.assertTrue((boolean) call("VaccineProject", "completed", types, player)
                && player.getInventory().countItem(serum) == 1, "third delivery: serum");
        done(h, player);
        h.succeed();
    }

    @GameTest(template = "arena", batch = "events_vaccine_serum", timeoutTicks = 20)
    public static void serumGrantsADayOfImmunityAndOnlySpecialsDropSamples(GameTestHelper h) {
        ServerPlayer player = NpcGameTests.player(h, 10, 10);
        call("VaccineProject$Serum", "inject", new Class<?>[]{net.minecraft.world.entity.LivingEntity.class}, player);
        h.assertTrue((boolean) call("VaccineProject", "immune", new Class<?>[]{net.minecraft.world.entity.LivingEntity.class}, player),
                "immune after the serum");
        h.assertTrue(player.getPersistentData().getLong("LazarusEventsSerumImmunity") - h.getLevel().getGameTime() == 24_000,
                "for one day");
        h.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION), "nausea side effect");
        var zombie = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 12, 2, 12);
        h.assertTrue(!(boolean) call("VaccineProject", "special", new Class<?>[]{net.minecraft.world.entity.LivingEntity.class}, zombie),
                "ordinary zombies drop no samples");
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
