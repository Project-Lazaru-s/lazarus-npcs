package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** In-world checks on a flat 32x32 arena with the real Apocalypse Now entities and mock server players. */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class NpcGameTests {
    private NpcGameTests() {}

    @GameTest(template = "arena", batch = "npc_hazard_paths")
    public static void humansRouteAroundSpikeBarrierAndWire(GameTestHelper h) {
        Mob guard = npc(h, "military_01", 4, 4);
        Mob looter = npc(h, "looter_1", 5, 4);
        Mob walker = npc(h, "walker_1", 6, 4);
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        for (String id : new String[] {"spike_barrier", "barbed_wire"}) {
            BlockPos obstacle = h.absolutePos(new BlockPos(id.equals("spike_barrier") ? 12 : 17, FLOOR, 12));
            h.getLevel().setBlock(obstacle, ForgeRegistries.BLOCKS.getValue(
                    new ResourceLocation("apocalypsenow", id)).defaultBlockState(), 3);
            PathNavigationRegion region = new PathNavigationRegion(h.getLevel(), obstacle.offset(-3, -2, -3),
                    obstacle.offset(3, 3, 3));
            for (Mob human : new Mob[] {guard, looter}) {
                evaluator.prepare(region, human);
                h.assertTrue(evaluator.getBlockPathType(region, obstacle.getX(), obstacle.getY(),
                        obstacle.getZ(), human) == BlockPathTypes.BLOCKED, id + " blocks human route");
                h.assertTrue(evaluator.getBlockPathType(region, obstacle.getX() + 1, obstacle.getY(),
                        obstacle.getZ(), human) == BlockPathTypes.DANGER_OTHER, id + " warns adjacent route");
                evaluator.done();
            }
            evaluator.prepare(region, walker);
            h.assertTrue(evaluator.getBlockPathType(region, obstacle.getX() + 1, obstacle.getY(),
                    obstacle.getZ(), walker) != BlockPathTypes.DANGER_OTHER,
                    id + " does not change zombie navigation");
            evaluator.done();
        }
        h.succeed();
    }

    // ---- Escort

    @GameTest(template = "arena", batch = "escort_follow", timeoutTicks = 900)
    public static void escortFollowsWithoutCrowding(GameTestHelper h) {
        ServerPlayer owner = player(h, 24, 24);
        Mob soldier = npc(h, "military_01", 4, 4);
        hireEscort(owner, soldier);
        h.assertTrue(soldier.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof MeleeAttackGoal),
                "military received an attack goal without KubeJS");
        long startTime = h.getLevel().getGameTime();
        h.succeedWhen(() -> {
            double distance = soldier.distanceTo(owner);
            h.assertTrue(distance >= 1.5 && distance <= 7.5, "escort should settle a few steps away, was " + distance
                    + "; game time advanced " + (h.getLevel().getGameTime() - startTime) + ", follow range "
                    + soldier.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)
                    + ", running goals " + soldier.goalSelector.getRunningGoals().map(g -> g.getGoal().getClass().getSimpleName()).toList()
                    + ", contract " + (NpcContracts.contract(soldier) != null) + ", target " + soldier.getTarget()
                    + ", nav done " + soldier.getNavigation().isDone());
            h.assertTrue(NpcContracts.contract(soldier) != null, "contract still active");
            NpcContracts.end(h.getLevel().getServer(), NpcContracts.contract(soldier), null);
            remove(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "gun_skill_lock", timeoutTicks = 20)
    public static void gunSmithNeedsTheArmeiroBranchOfTheGun(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        ResourceLocation glock = new ResourceLocation("tacz", "gun/glock_17"), awp = new ResourceLocation("tacz", "gun/ai_awp");
        ResourceLocation ammo = new ResourceLocation("tacz", "ammo/308");
        var recipes = h.getLevel().getRecipeManager();
        h.assertTrue(recipes.byKey(glock).isPresent() && recipes.byKey(awp).isPresent() && recipes.byKey(ammo).isPresent(),
                "TaCZ gun smith recipes loaded");
        h.assertFalse(GunSkillLock.allowed(player, glock), "pistol locked without the branch");
        h.assertTrue(GunSkillLock.allowed(player, ammo), "ammo is never locked");
        player.addTag("lazarus_arma_pistola");
        h.assertTrue(GunSkillLock.allowed(player, glock), "Pistolas unlocks the Glock");
        h.assertFalse(GunSkillLock.allowed(player, awp), "Pistolas does not unlock sniper rifles");
        player.addTag("lazarus_arma_precisao");
        h.assertTrue(GunSkillLock.allowed(player, awp), "Rifles de precisão unlocks the AWP");
        ResourceLocation silencer = new ResourceLocation("tacz", "attachments/muzzle_silencer_mirage");
        ResourceLocation scope = new ResourceLocation("tacz", "attachments/scope_acog_ta31");
        h.assertTrue(recipes.byKey(silencer).isPresent() && recipes.byKey(scope).isPresent(), "TaCZ attachment recipes loaded");
        h.assertFalse(GunSkillLock.allowed(player, silencer), "silencer locked without Bocais");
        player.addTag("lazarus_acessorio_bocal");
        h.assertTrue(GunSkillLock.allowed(player, silencer), "Bocais unlocks silencers");
        h.assertFalse(GunSkillLock.allowed(player, scope), "Bocais does not unlock scopes");
        finish(h, player);
    }

    @GameTest(template = "arena", batch = "escort_expiry", timeoutTicks = 120)
    public static void escortEndsWhenTimeRunsOut(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 6, 6);
        hireEscort(owner, soldier);
        NpcContracts.contract(soldier).until = NpcContracts.now(h.getLevel().getServer()) + 5;
        h.runAtTickTime(60, () -> {
            h.assertTrue(NpcContracts.contract(soldier) == null, "expired escort removed");
            h.assertTrue(NpcContracts.store(h.getLevel().getServer()).escortOf(owner.getUUID()) == null, "owner freed");
            finish(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_npc_death", timeoutTicks = 60)
    public static void escortEndsWhenNpcDies(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 6, 6);
        hireEscort(owner, soldier);
        soldier.kill();
        h.runAtTickTime(5, () -> {
            h.assertTrue(NpcContracts.store(h.getLevel().getServer()).escortOf(owner.getUUID()) == null, "dead escort released");
            finish(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_owner_death", timeoutTicks = 60)
    public static void escortEndsWhenOwnerDies(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 6, 6);
        hireEscort(owner, soldier);
        owner.hurt(h.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        h.runAtTickTime(5, () -> {
            h.assertTrue(NpcContracts.contract(soldier) == null, "owner death ends the escort");
            finish(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_dimension", timeoutTicks = 80)
    public static void escortEndsOnDimensionChange(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 6, 6);
        hireEscort(owner, soldier);
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "nether exists");
        owner.teleportTo(nether, 0.5, 70, 0.5, 0, 0);
        h.runAtTickTime(10, () -> {
            h.assertTrue(NpcContracts.contract(soldier) == null, "dimension change ends the escort");
            finish(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_defend", timeoutTicks = 120)
    public static void escortDefendsOwner(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 7, 7);
        Mob walker = npc(h, "walker_1", 13, 13);
        walker.setNoAi(true);
        hireEscort(owner, soldier);
        h.runAtTickTime(70, () -> { // after the 60-tick login invulnerability
            soldier.setTarget(null);
            h.assertTrue(owner.hurt(h.getLevel().damageSources().mobAttack(walker), 1), "owner took the hit");
            h.assertTrue(soldier.getTarget() == walker, "escort targets whoever hurt the owner");
            finish(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_stuck", timeoutTicks = 1300)
    public static void stuckEscortGivesUp(GameTestHelper h) {
        ServerPlayer owner = player(h, 28, 28);
        box(h, 3, 3);
        Mob soldier = npc(h, "military_01", 3, 3);
        hireEscort(owner, soldier);
        h.succeedWhen(() -> {
            h.assertTrue(NpcContracts.contract(soldier) == null, "boxed-in escort still trying");
            remove(h, owner);
        });
    }

    @GameTest(template = "arena", batch = "escort_offline", timeoutTicks = 120)
    public static void escortWaitsWhileOwnerOffline(GameTestHelper h) {
        ServerPlayer owner = player(h, 20, 20);
        Mob soldier = npc(h, "military_01", 6, 6);
        hireEscort(owner, soldier);
        remove(h, owner);
        h.runAtTickTime(100, () -> {
            h.assertTrue(NpcContracts.contract(soldier) != null, "escort kept for a reconnect");
            NpcContracts.end(h.getLevel().getServer(), NpcContracts.contract(soldier), null);
            h.succeed();
        });
    }

    // ---- Base guard

    @GameTest(template = "arena", batch = "guard_area", timeoutTicks = 600)
    public static void guardHoldsPostAndOnlyFightsInsideArea(GameTestHelper h) {
        ServerPlayer owner = player(h, 16, 16);
        Mob guard = npc(h, "military_01", 11, 16);
        NpcReputation.adjust(owner, "military", 20);
        give(owner, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(owner, guard, 6);
        NpcContracts.Contract c = NpcContracts.contract(guard);
        h.assertTrue(c != null && c.radius == 6, "guard hired");
        Mob outside = npc(h, "walker_1", 16, 30); // 14 blocks out, off the line of fire toward the inside threat
        outside.setNoAi(true);
        h.runAtTickTime(200, () -> {
            h.assertTrue(c.arrived, "guard walked to the post; distance " + guard.position().distanceTo(c.post())
                    + ", running " + guard.goalSelector.getRunningGoals().map(g -> g.getGoal().getClass().getSimpleName()).toList());
            h.assertTrue(guard.getTarget() == null, "threat 14 blocks out is ignored");
            Mob inside = npc(h, "walker_1", 20, 16);
            inside.setNoAi(true);
            h.succeedWhen(() -> {
                h.assertTrue(guard.getTarget() == inside || !inside.isAlive(), "threat inside the area is engaged");
                h.assertTrue(guard.position().distanceTo(c.post()) <= c.radius + 8, "guard stays near the post");
                h.assertTrue(outside.isAlive(), "threat outside the area left alone; killed by " + outside.getLastHurtByMob()
                        + " via " + (outside.getLastDamageSource() == null ? "?" : outside.getLastDamageSource().getMsgId())
                        + ", guard holds " + guard.getMainHandItem() + ", outside at " + outside.position() + " removed " + outside.getRemovalReason());
                NpcContracts.end(h.getLevel().getServer(), c, null);
                remove(h, owner);
            });
        });
    }

    @GameTest(template = "arena", batch = "guard_two_players", timeoutTicks = 60)
    public static void guardLimitsAndOwnership(GameTestHelper h) {
        ServerPlayer alice = player(h, 16, 16);
        ServerPlayer bob = player(h, 18, 16);
        NpcReputation.adjust(alice, "military", 30);
        NpcReputation.adjust(bob, "military", 30);
        Mob first = npc(h, "military_01", 12, 12), second = npc(h, "military_02", 12, 20), third = npc(h, "military_01", 20, 12);
        for (Mob guard : new Mob[]{first, second, third}) {
            give(alice, NpcContracts.GUARD_COST);
            NpcContracts.hireGuard(alice, guard, 10);
        }
        var store = NpcContracts.store(h.getLevel().getServer());
        h.assertTrue(store.guards(alice.getUUID()) == NpcContracts.GUARDS_PER_PLAYER, "per-player cap");
        h.assertTrue(NpcContracts.contract(third) == null, "third guard refused");
        long until = NpcContracts.contract(first).until;
        give(bob, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(bob, first, 14);
        h.assertTrue(NpcContracts.contract(first).owner.equals(alice.getUUID()) && NpcContracts.contract(first).until == until
                && NpcContracts.contract(first).radius == 10, "another player cannot renew or change the guard");
        NpcContracts.dismiss(bob, first);
        h.assertTrue(NpcContracts.contract(first) != null, "another player cannot dismiss the guard");
        give(alice, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(alice, first, 14);
        h.assertTrue(NpcContracts.contract(first).until == until + NpcContracts.GUARD_DURATION
                && NpcContracts.contract(first).radius == 14, "owner renews and changes the area");
        NpcContracts.dismiss(alice, first);
        NpcContracts.dismiss(alice, second);
        h.assertTrue(store.guards(alice.getUUID()) == 0, "owner dismisses");
        remove(h, bob);
        finish(h, alice);
    }

    @GameTest(template = "arena", batch = "guard_unload", timeoutTicks = 120)
    public static void guardSurvivesUnloadAndExpiresWhileUnloaded(GameTestHelper h) {
        ServerPlayer owner = player(h, 16, 16);
        Mob guard = npc(h, "military_01", 14, 16);
        NpcReputation.adjust(owner, "military", 20);
        give(owner, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(owner, guard, 10);
        var server = h.getLevel().getServer();
        NpcContracts.Contract c = NpcContracts.contract(guard);
        guard.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.runAtTickTime(40, () -> {
            h.assertTrue(NpcContracts.store(server).byNpc.containsKey(c.npc), "unloaded guard keeps its contract");
            c.until = NpcContracts.now(server) + 1;
            h.runAfterDelay(40, () -> {
                h.assertTrue(!NpcContracts.store(server).byNpc.containsKey(c.npc), "contract expires while unloaded");
                finish(h, owner);
            });
        });
    }

    @GameTest(template = "arena", batch = "guard_refund", timeoutTicks = 120)
    public static void unreachablePostRefunds(GameTestHelper h) {
        ServerPlayer owner = player(h, 20, 20);
        box(h, 4, 4);
        Mob guard = npc(h, "military_01", 4, 4);
        NpcReputation.adjust(owner, "military", 20);
        give(owner, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(owner, guard, 10);
        NpcContracts.Contract c = NpcContracts.contract(guard);
        h.assertTrue(c != null, "guard hired");
        c.hiredAt -= 1_190;
        h.runAtTickTime(60, () -> {
            h.assertTrue(NpcContracts.contract(guard) == null, "unreachable post cancels the contract");
            h.assertTrue(NpcContracts.has(owner, NpcContracts.GUARD_COST), "payment returned");
            finish(h, owner);
        });
    }

    // ---- Surrender

    @GameTest(template = "arena", batch = "surrender_loot", timeoutTicks = 80)
    public static void surrenderedNpcStopsFightingAndDropsNothing(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob looter = npc(h, "looter_1", 12, 12);
        int wardens = NpcReputation.get(player, "wardens");
        HumanSurrender.surrender(looter, "looter", player);
        looter.setTarget(player);
        h.assertTrue(looter.getTarget() == null, "surrendered NPC cannot take a target");
        h.assertTrue(looter.getMainHandItem().isEmpty(), "weapon thrown down");
        looter.hurt(h.getLevel().damageSources().playerAttack(player), 1_000);
        h.runAtTickTime(30, () -> { // late enough to catch Apocalypse Now's delayed flesh spawn
            // Only items that appeared after the kill; other tests may leave older drops nearby.
            var items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(looter.blockPosition()).inflate(1.5),
                    item -> item.getAge() <= 32);
            h.assertTrue(items.isEmpty(), "no loot from a killed prisoner: " + items.stream().map(i -> ForgeRegistries.ITEMS.getKey(i.getItem().getItem())
                    + " age " + i.getAge() + " at " + i.position().distanceTo(looter.position())).toList());
            h.assertTrue(NpcReputation.get(player, "wardens") == wardens - 3, "Wardens trust lost");
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "surrender_release", timeoutTicks = 400)
    public static void strippedPrisonerLeavesTheWorld(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob soldier = npc(h, "soldiers", 12, 12);
        HumanSurrender.surrender(soldier, "soldier", player);
        h.assertTrue(HumanSurrender.canResolve(soldier, player), "capturer can resolve");
        ServerPlayer other = player(h, 11, 12);
        h.assertTrue(!HumanSurrender.canResolve(soldier, other), "another player cannot");
        remove(h, other);
        HumanSurrender.strip(player, soldier);
        h.assertTrue(count(player, "minecraft:iron_ingot") == 2, "fixed supplies handed over");
        HumanSurrender.strip(player, soldier);
        h.assertTrue(count(player, "minecraft:iron_ingot") == 2, "cannot strip twice");
        Vec3 far = h.absoluteVec(new Vec3(80, FLOOR, 80));
        player.teleportTo(far.x, far.y, far.z);
        h.succeedWhen(() -> {
            h.assertTrue(soldier.isRemoved(), "released prisoner walks off and is removed");
            remove(h, player);
        });
    }

    // ---- Encounter fairness with two players (0.8 fix) and routine cost

    @GameTest(template = "arena", batch = "two_player_passage", timeoutTicks = 100)
    public static void paidPlayerDoesNotShieldTheOther(GameTestHelper h) {
        Mob soldier = npc(h, "soldiers", 16, 16);
        ServerPlayer payer = player(h, 18, 16);
        ServerPlayer other = player(h, 16, 26);
        HumanEncounters.warnGroup(soldier, "soldier", payer, h.getLevel().getGameTime());
        give(payer, new String[][]{{"apocalypsenow:canned_food", "2"}, {"minecraft:iron_ingot", "2"}});
        HumanEncounters.pay(payer, soldier);
        h.assertTrue(HumanEncounters.state(soldier, payer).getLong("PeaceUntil") > h.getLevel().getGameTime(), "payer at peace");
        h.succeedWhen(() -> {
            h.assertTrue(HumanEncounters.state(soldier, other).contains("WarnedAt"), "second player still warned; target "
                    + soldier.getTarget() + ", tracked " + HumanTactics.tracked() + ", los " + soldier.hasLineOfSight(other)
                    + ", data " + HumanEncounters.data(soldier));
            remove(h, payer);
            remove(h, other);
        });
    }

    @GameTest(template = "arena", batch = "perf", timeoutTicks = 320)
    public static void routineCostStaysSmall(GameTestHelper h) {
        NpcPerf.reset();
        ServerPlayer player = player(h, 16, 16);
        for (int i = 0; i < 12; i++) npc(h, "looter_" + (1 + i % 5), 2 + i * 2, 4);
        for (int i = 0; i < 8; i++) npc(h, i % 2 == 0 ? "soldiers" : "soldiers_02", 2 + i * 3, 28);
        for (int i = 0; i < 4; i++) npc(h, "cannibal", 28, 6 + i * 5);
        NpcReputation.adjust(player, "military", 30);
        for (int i = 0; i < 2; i++) {
            Mob guard = npc(h, "military_01", 10 + i * 10, 16);
            give(player, NpcContracts.GUARD_COST);
            NpcContracts.hireGuard(player, guard, 14);
        }
        h.runAtTickTime(300, () -> {
            String report = NpcPerf.report();
            LazarusNpcs.LOGGER.info("[perf] 24 hostile humans + 2 guards, 300 ticks:\n{}", report);
            h.assertTrue(averageMicros("human tactics") < 500, "tactics average too high: " + report);
            h.assertTrue(NpcPerf.runs("contracts") <= 16, "contract sweep runs once per second");
            for (NpcContracts.Contract c : new java.util.ArrayList<>(NpcContracts.store(h.getLevel().getServer()).byNpc.values()))
                NpcContracts.end(h.getLevel().getServer(), c, null);
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "escort_reconnect", timeoutTicks = 900)
    public static void escortResumesAfterReconnect(GameTestHelper h) {
        ServerPlayer owner = player(h, 6, 6);
        Mob soldier = npc(h, "military_01", 4, 4);
        hireEscort(owner, soldier);
        var profile = owner.getGameProfile();
        remove(h, owner);
        h.runAtTickTime(40, () -> {
            h.assertTrue(NpcContracts.contract(soldier) != null, "contract kept while offline");
            ServerPlayer back = player(h, profile, 26, 26);
            h.succeedWhen(() -> {
                double distance = soldier.distanceTo(back);
                h.assertTrue(distance <= 7.5, "escort resumes following after reconnect, distance " + distance);
                NpcContracts.end(h.getLevel().getServer(), NpcContracts.contract(soldier), null);
                remove(h, back);
            });
        });
    }

    @GameTest(template = "arena", batch = "legacy_world", timeoutTicks = 40)
    public static void legacyEscortTagIsCleared(GameTestHelper h) {
        net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putString("id", "apocalypsenow:military_01");
        net.minecraft.nbt.CompoundTag forge = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag escort = new net.minecraft.nbt.CompoundTag();
        escort.putUUID("Owner", java.util.UUID.randomUUID());
        escort.putLong("Until", Long.MAX_VALUE);
        forge.put("LazarusNpcsEscort", escort);
        nbt.put("ForgeData", forge);
        Vec3 at = h.absoluteVec(new Vec3(8.5, FLOOR, 8.5));
        Entity loaded = EntityType.loadEntityRecursive(nbt, h.getLevel(), entity -> {
            entity.moveTo(at.x, at.y, at.z, 0, 0);
            return entity;
        });
        h.assertTrue(loaded != null && h.getLevel().addFreshEntity(loaded), "0.7/0.8 soldier loaded");
        h.runAtTickTime(5, () -> {
            h.assertTrue(!loaded.getPersistentData().contains("LazarusNpcsEscort"), "old escort tag removed on load");
            h.assertTrue(NpcContracts.contract(loaded) == null, "no phantom contract");
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "saved_data", timeoutTicks = 20)
    public static void contractsSurviveSaveAndReload(GameTestHelper h) {
        ServerPlayer owner = player(h, 16, 16);
        Mob guard = npc(h, "military_01", 14, 16);
        NpcReputation.adjust(owner, "military", 20);
        give(owner, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(owner, guard, 14);
        var store = NpcContracts.store(h.getLevel().getServer());
        var copy = NpcContracts.Store.load(store.save(new net.minecraft.nbt.CompoundTag()));
        NpcContracts.Contract original = store.byNpc.get(guard.getUUID()), reloaded = copy.byNpc.get(guard.getUUID());
        h.assertTrue(reloaded != null && reloaded.owner.equals(original.owner) && reloaded.until == original.until
                && reloaded.radius == 14 && reloaded.dim.equals(original.dim) && reloaded.post().equals(original.post()),
                "guard contract round-trips through world save");
        NpcContracts.end(h.getLevel().getServer(), original, null);
        finish(h, owner);
    }

    // ---- Retaliation and faction hostility

    @GameTest(template = "arena", batch = "hostility_retaliate", timeoutTicks = 80)
    public static void militaryFightBackAndAlertTheirOwn(GameTestHelper h) {
        ServerPlayer player = player(h, 16, 16);
        Mob hit = npc(h, "military_01", 18, 16), mate = npc(h, "military_02", 20, 20), haven = npc(h, "survivor_1", 14, 20);
        hit.hurt(h.getLevel().damageSources().playerAttack(player), 1);
        h.runAtTickTime(3, () -> {
            h.assertTrue(hit.getTarget() == player, "attacked soldier fights back");
            h.assertTrue(mate.getTarget() == player, "nearby military join in");
            h.assertTrue(haven.getTarget() != player, "other factions stay out of it");
            h.assertTrue(NpcReputation.get(player, "military") == -5, "one hit costs 5 military trust");
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "hostility_burst", timeoutTicks = 40)
    public static void killingWithManyHitsCostsTwentyFive(GameTestHelper h) {
        ServerPlayer player = player(h, 16, 16);
        Mob soldier = npc(h, "military_01", 18, 16);
        for (int shot = 0; shot < 8; shot++) {
            soldier.invulnerableTime = 0;
            soldier.hurt(h.getLevel().damageSources().playerAttack(player), 1);
        }
        soldier.invulnerableTime = 0;
        soldier.hurt(h.getLevel().damageSources().playerAttack(player), 1_000);
        h.runAtTickTime(3, () -> {
            h.assertTrue(NpcReputation.get(player, "military") == -25, "8 hits and a kill cost 25, got "
                    + NpcReputation.get(player, "military"));
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "hostility_expiry", timeoutTicks = 100)
    public static void grudgeEndsWhenNotAnEnemy(GameTestHelper h) {
        ServerPlayer player = player(h, 16, 16);
        Mob soldier = npc(h, "military_01", 18, 16);
        soldier.hurt(h.getLevel().damageSources().playerAttack(player), 1);
        h.runAtTickTime(3, () -> {
            h.assertTrue(soldier.getTarget() == player, "soldier fights back");
            soldier.getPersistentData().getCompound("LazarusNpcsAggro").putLong(player.getUUID().toString(), h.getLevel().getGameTime());
            h.runAfterDelay(40, () -> {
                h.assertTrue(soldier.getTarget() != player, "soldier lets it go after the grudge at -5 standing");
                finish(h, player);
            });
        });
    }

    @GameTest(template = "arena", batch = "hostility_enemy", timeoutTicks = 100)
    public static void enemyStandingIsAttackedOnSightAndLosesContracts(GameTestHelper h) {
        ServerPlayer player = player(h, 16, 16);
        Mob guard = npc(h, "military_01", 12, 16), patrol = npc(h, "military_02", 24, 16), haven = npc(h, "survivor_2", 16, 22);
        NpcReputation.adjust(player, "military", 20);
        give(player, NpcContracts.GUARD_COST);
        NpcContracts.hireGuard(player, guard, 10);
        h.assertTrue(NpcContracts.contract(guard) != null, "guard hired");
        NpcReputation.adjust(player, "military", -70);
        h.assertTrue(NpcContracts.contract(guard) == null, "becoming an enemy ends that faction's contracts");
        h.succeedWhen(() -> {
            h.assertTrue(patrol.getTarget() == player && guard.getTarget() == player, "military attack an enemy on sight");
            h.assertTrue(haven.getTarget() != player, "Haven is not involved");
            remove(h, player);
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "hostility_friendly_fire", timeoutTicks = 60)
    public static void oneStrayHitIsForgivenTheSecondIsNot(GameTestHelper h) {
        ServerPlayer owner = player(h, 10, 10);
        Mob soldier = npc(h, "military_01", 12, 10);
        hireEscort(owner, soldier);
        soldier.hurt(h.getLevel().damageSources().playerAttack(owner), 1);
        h.assertTrue(NpcContracts.contract(soldier) != null && soldier.getTarget() != owner, "first hit forgiven");
        soldier.invulnerableTime = 0;
        soldier.hurt(h.getLevel().damageSources().playerAttack(owner), 1);
        h.assertTrue(NpcContracts.contract(soldier) == null, "second hit ends the contract");
        h.assertTrue(soldier.getTarget() == owner, "and the escort fights back; target " + soldier.getTarget()
                + ", aggro " + soldier.getPersistentData().getCompound("LazarusNpcsAggro") + ", now " + h.getLevel().getGameTime()
                + ", hurtTime " + soldier.hurtTime + ", health " + soldier.getHealth());
        finish(h, owner);
    }

    @GameTest(template = "arena", batch = "hostility_creative", timeoutTicks = 80)
    public static void creativePlayersAreIgnored(GameTestHelper h) {
        ServerPlayer player = player(h, 16, 16);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        Mob soldier = npc(h, "military_01", 19, 16);
        NpcReputation.adjust(player, "military", -80);
        h.runAtTickTime(60, () -> {
            h.assertTrue(soldier.getTarget() != player, "creative player not targeted");
            finish(h, player);
        });
    }

    // ---- Firearms

    private static Mob armed(GameTestHelper h, String path, int x, int z, String gun) {
        Mob npc = npc(h, path, x, z);
        npc.getPersistentData().remove(NpcGuns.KEY);
        NpcGuns.roll(npc, new NpcGuns.Arsenal(1f, 0f, java.util.Map.of(gun, 1)));
        NpcGuns.attach(npc, 0.5f);
        h.assertTrue(NpcGuns.armed(npc), path + " carries " + gun + " (" + npc.getPersistentData().getString(NpcGuns.KEY) + ")");
        return npc;
    }

    @GameTest(template = "arena", batch = "guns_fire", timeoutTicks = 400)
    public static void armedSoldierShootsInfected(GameTestHelper h) {
        h.getLevel().getServer().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, h.getLevel().getServer());
        Mob soldier = armed(h, "military_01", 4, 16, "m4a1");
        var fire = soldier.goalSelector.getAvailableGoals().stream()
                .map(goal -> goal.getGoal()).filter(goal -> goal instanceof NpcGuns.FireControlGoal).findFirst().orElseThrow();
        h.assertTrue(fire.getFlags().contains(net.minecraft.world.entity.ai.goal.Goal.Flag.LOOK)
                && !fire.getFlags().contains(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE),
                "fire control leaves movement to navigation");
        Mob walker = npc(h, "walker_1", 12, 16);
        walker.setNoAi(true);
        float start = walker.getHealth();
        soldier.setTarget(walker);
        h.succeedWhen(() -> {
            h.assertTrue(walker.getHealth() < start || !walker.isAlive(), "walker 8 blocks away was shot");
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "guns_drop", timeoutTicks = 40)
    public static void droppedGunIsEmpty(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob looter = armed(h, "looter_1", 12, 12, "glock_17");
        looter.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2f); // force the rare drop
        looter.hurt(h.getLevel().damageSources().playerAttack(player), 1_000);
        h.runAtTickTime(3, () -> {
            var guns = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(looter.blockPosition()).inflate(4),
                    item -> com.tacz.guns.api.item.IGun.getIGunOrNull(item.getItem()) != null);
            h.assertTrue(guns.size() == 1, "the gun dropped");
            var stack = guns.get(0).getItem();
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            h.assertTrue(gun.getCurrentAmmoCount(stack) == 0 && !gun.hasBulletInBarrel(stack), "dropped with an empty magazine");
            finish(h, player);
        });
    }

    @GameTest(template = "arena", batch = "guns_roll", timeoutTicks = 20)
    public static void rollIsStoredAndCannibalsStayUnarmed(GameTestHelper h) {
        Mob cannibal = npc(h, "cannibal", 6, 6);
        h.assertTrue(!NpcGuns.armed(cannibal) && !cannibal.getPersistentData().contains(NpcGuns.KEY), "cannibals never roll");
        Mob soldier = npc(h, "soldiers", 10, 10);
        String rolled = soldier.getPersistentData().getString(NpcGuns.KEY);
        h.assertTrue(!rolled.isEmpty(), "soldier rolled on spawn: " + rolled);
        h.assertTrue("none".equals(rolled) != NpcGuns.armed(soldier), "stored roll matches the hand");
        NpcGuns.join(new net.minecraftforge.event.entity.EntityJoinLevelEvent(soldier, h.getLevel()));
        h.assertTrue(rolled.equals(soldier.getPersistentData().getString(NpcGuns.KEY)), "a reload never re-rolls");
        h.killAllEntities();
        h.succeed();
    }

    @GameTest(template = "arena", batch = "guns_surrender", timeoutTicks = 20)
    public static void surrenderDisarmsGunner(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob looter = armed(h, "looter_1", 14, 14, "m1911");
        HumanSurrender.surrender(looter, "looter", player);
        h.assertTrue(!NpcGuns.armed(looter), "surrendered gunner drops nothing and holds no gun");
        finish(h, player);
    }

    // ---- Journal

    @GameTest(template = "arena", batch = "journal_location", timeoutTicks = 20)
    public static void acceptedTaskRecordsGiverLocation(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_1", 12, 10);
        h.assertTrue(NpcTasks.location(player).isEmpty(), "no location before a task");
        NpcTasks.interact(player, survivor, "survivor");
        var pos = survivor.blockPosition();
        String here = NpcTasks.location(player);
        h.assertTrue(here.startsWith(NpcIdentity.name(survivor) + " (Haven): " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                && here.endsWith("You are here"), "giver and coordinates recorded: " + here);
        Vec3 away = h.absoluteVec(new Vec3(10.5, FLOOR, 10.5)).add(0, 0, -60);
        player.teleportTo(away.x, away.y, away.z);
        String far = NpcTasks.location(player);
        h.assertTrue(far.endsWith("blocks south") && far.contains("\n6"), "distance and direction from the player: " + far);
        NpcTasks.abandon(player);
        h.assertTrue(NpcTasks.location(player).isEmpty(), "cleared with the task");
        finish(h, player);
    }

    @GameTest(template = "arena", batch = "journal_giver_death", timeoutTicks = 20)
    public static void journalReportsWhenTheTaskGiverDies(GameTestHelper h) {
        ServerPlayer player = player(h, 10, 10);
        Mob survivor = npc(h, "survivor_2", 12, 10);
        Mob walker = npc(h, "walker_3", 14, 10);
        walker.setNoAi(true);
        NpcTasks.interact(player, survivor, "survivor");
        h.assertTrue(survivor.getUUID().equals(NpcTasks.giverId(player)), "giver id stored");
        survivor.hurt(h.getLevel().damageSources().mobAttack(walker), 1_000);
        h.runAtTickTime(2, () -> {
            h.assertTrue(!survivor.isAlive(), "giver died");
            String journal = NpcTasks.location(player);
            h.assertTrue(journal.endsWith("\nKilled by a walker"), "journal reports the death: " + journal);
            h.assertTrue(NpcDeaths.death(h.getLevel().getServer(), survivor.getUUID()) != null, "death recorded for offline players");
            h.assertTrue(NpcTasks.turnInHint(player).endsWith("any other Haven survivor."), NpcTasks.turnInHint(player));
            NpcTasks.abandon(player);
            finish(h, player);
        });
    }

    // ---- Helpers

    private static double averageMicros(String routine) {
        String line = java.util.Arrays.stream(NpcPerf.report().split("\n")).filter(l -> l.startsWith(routine + ":"))
                .findFirst().orElse("");
        int at = line.indexOf("avg ");
        return at < 0 ? 0 : Double.parseDouble(line.substring(at + 4, line.indexOf(" us", at)));
    }

    private static int players;
    /** Relative y=1 is the arena's stone layer, so standing height is 2. */
    private static final int FLOOR = 2;

    /**
     * Like GameTestHelper.makeMockServerPlayerInLevel, but with a live embedded Netty channel: Forge 47's
     * login hooks dereference the channel, which the vanilla mock leaves null.
     */
    static ServerPlayer player(GameTestHelper h, int x, int z) {
        return player(h, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "tester" + ++players), x, z);
    }

    private static ServerPlayer player(GameTestHelper h, com.mojang.authlib.GameProfile profile, int x, int z) {
        var server = h.getLevel().getServer();
        // Deterministic world: no natural spawns wandering in, always daytime.
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
        h.getLevel().setDayTime(6_000);
        ServerPlayer player = new ServerPlayer(server, h.getLevel(), profile);
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(new Vec3(x + 0.5, FLOOR, z + 0.5));
        player.teleportTo(h.getLevel(), at.x, at.y, at.z, 0, 0);
        return player;
    }

    @SuppressWarnings("unchecked")
    static Mob npc(GameTestHelper h, String path, int x, int z) {
        EntityType<Mob> type = (EntityType<Mob>) ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("apocalypsenow", path));
        return h.spawn(type, x, FLOOR, z);
    }

    static void hireEscort(ServerPlayer owner, Mob soldier) {
        give(owner, NpcContracts.ESCORT_COST);
        NpcContracts.hireEscort(owner, soldier);
        if (NpcContracts.contract(soldier) == null)
            throw new net.minecraft.gametest.framework.GameTestAssertException("escort was not hired; has cost: "
                    + NpcContracts.has(owner, NpcContracts.ESCORT_COST) + ", goals: " + soldier.goalSelector.getAvailableGoals().size());
    }

    private static void give(ServerPlayer player, String[][] items) {
        for (String[] entry : items) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry[0]));
            player.getInventory().add(new ItemStack(item, Integer.parseInt(entry[1])));
        }
    }

    private static int count(ServerPlayer player, String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        int total = 0;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(item)) total += player.getInventory().getItem(slot).getCount();
        return total;
    }

    /** Glass cell, two blocks high, around one column of the arena. */
    private static void box(GameTestHelper h, int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = FLOOR; y <= FLOOR + 2; y++)
                    if (dx != 0 || dz != 0 || y == FLOOR + 2) h.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.GLASS);
    }

    private static void remove(GameTestHelper h, ServerPlayer player) {
        h.getLevel().getServer().getPlayerList().remove(player);
    }

    private static void finish(GameTestHelper h, ServerPlayer player) {
        remove(h, player);
        h.killAllEntities();
        h.succeed();
    }
}
