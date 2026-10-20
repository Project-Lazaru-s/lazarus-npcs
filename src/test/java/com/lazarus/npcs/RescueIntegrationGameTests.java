package com.lazarus.npcs;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * End to end with the real Lazarus Events 0.4.0 JAR: its "rescue" scenario places a wounded survivor, and the whole
 * flow runs through the public API (order, dialogue, task offer, hand-in, follow, escort, reward, cancel).
 */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class RescueIntegrationGameTests {
    private RescueIntegrationGameTests() {}

    private static Method method(String name, Class<?>... types) throws ReflectiveOperationException {
        Method method = Class.forName("com.lazarus.events.RescueEncounter").getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /** Runs Lazarus Events' own placement and returns the survivor it created. */
    private static Mob place(GameTestHelper h, UUID encounter) {
        try {
            boolean placed = (boolean) method("place", ServerLevel.class, BlockPos.class, int.class, UUID.class)
                    .invoke(null, h.getLevel(), h.absolutePos(new BlockPos(10, 2, 16)), 0, encounter);
            h.assertTrue(placed, "Lazarus Events placed the rescue");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        var actors = h.getLevel().getEntitiesOfClass(Mob.class, new AABB(h.absolutePos(new BlockPos(10, 2, 16))).inflate(12),
                mob -> mob.getTags().contains("lazarusevents:" + encounter));
        // The scenario's infected are real; freeze them so the flow under test does not depend on a fight.
        actors.stream().filter(mob -> NpcIdentity.role(mob) == null).forEach(mob -> mob.setNoAi(true));
        return actors.stream().filter(mob -> NpcIdentity.role(mob) != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("no rescue survivor"));
    }

    private static void choose(ServerPlayer player, Mob npc, int option) {
        var session = NpcDialogues.session(player);
        if (session == null) throw new IllegalStateException("no dialogue open");
        NpcDialogues.choose(player, npc.getUUID(), session.token(), option);
    }

    private static ItemStack item(String id, int count) {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)), count);
    }

    @GameTest(template = "arena", batch = "rescue_full", timeoutTicks = 900)
    public static void rescueFromPleaToSafety(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        Mob survivor = place(h, encounter);
        // This test covers the plea and the escort; a walker that rolled a spot inside the flare scenery would keep
        // the survivor targeting it forever, so the encounter's infected are cleared first.
        h.getLevel().getEntitiesOfClass(Mob.class, survivor.getBoundingBox().inflate(32),
                mob -> mob != survivor && mob.getTags().contains("lazarusevents:" + encounter)).forEach(Mob::discard);
        h.assertTrue(NpcApi.assignment(survivor).map(a -> a.order() == ScenarioOrder.WAIT
                && a.owner().toString().equals("lazarusevents:rescue")).orElse(false), "wounded survivor waits");
        h.assertTrue(survivor.getHealth() < survivor.getMaxHealth() * 0.5f, "wounded");
        ServerPlayer player = NpcGameTests.player(h, 12, 16);
        String faction = NpcIdentity.faction(survivor);
        int before = NpcReputation.get(player, faction);

        h.assertTrue(NpcDialogues.open(player, survivor, com.lazarus.npcs.api.dialogue.DialogueTrigger.INTERACT, null), "plea shown");
        h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusevents:rescue_plea"), "Events dialogue");
        choose(player, survivor, 0); // "I'll help you" -> task offer
        h.assertTrue(NpcDialogues.session(player).dialogue().toString().equals("lazarusnpcs:task_offer"), "offer shown");
        choose(player, survivor, 0); // Accept
        h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.ACTIVE).orElse(false), "accepted");

        // Keep the safe point on the test platform (the real one is 40 blocks back along the player's route).
        CompoundTag data = player.getPersistentData().getCompound(EncounterTasks.KEY);
        h.assertTrue(data.getCompound("Open").contains("Dest"), "safe point chosen by Events");
        data.getCompound("Open").putLong("Dest", h.absolutePos(new BlockPos(20, 2, 16)).asLong()); // near the centre: the arena's east chunk may not tick

        player.getInventory().add(item("apocalypsenow:bandage", 2));
        h.assertTrue(NpcDialogues.open(player, survivor, com.lazarus.npcs.api.dialogue.DialogueTrigger.INTERACT, null), "hand-in shown");
        choose(player, survivor, 0); // Hand it over
        h.assertTrue(NpcApi.assignment(survivor).map(a -> a.order() == ScenarioOrder.FOLLOW_PLAYER).orElse(false),
                "Events switched the survivor to follow after the bandages");

        var safe = h.absoluteVec(new net.minecraft.world.phys.Vec3(20.5, 2, 16.5));
        player.teleportTo(safe.x, safe.y, safe.z);
        h.succeedWhen(() -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.COMPLETED).orElse(false),
                    "escort completes, survivor at " + survivor.position() + " target " + survivor.getTarget()
                            + " order " + NpcApi.assignment(survivor).map(a -> a.order()).orElse(null) + " alive " + survivor.isAlive()
                            + " journal " + EncounterTasks.journal(player).replace('\n', '|'));
            h.assertTrue(NpcReputation.get(player, faction) == before + 5, "Events paid +5 " + faction);
            h.assertTrue(NpcApi.assignment(survivor).map(a -> a.order() == ScenarioOrder.WAIT).orElse(false), "rests at the safe point");
            h.getLevel().getServer().getPlayerList().remove(player);
            h.killAllEntities();
        });
    }

    @GameTest(template = "arena", batch = "rescue_cancel", timeoutTicks = 40)
    public static void cancelledRescueFailsTheTaskWithoutReward(GameTestHelper h) {
        UUID encounter = UUID.randomUUID();
        Mob survivor = place(h, encounter);
        ServerPlayer player = NpcGameTests.player(h, 12, 16);
        NpcDialogues.open(player, survivor, com.lazarus.npcs.api.dialogue.DialogueTrigger.INTERACT, null);
        choose(player, survivor, 0);
        choose(player, survivor, 0);
        try {
            Class<?> support = Class.forName("com.lazarus.events.ApiSupport");
            var fail = support.getDeclaredMethod("failTasks", ServerLevel.class, UUID.class, ResourceLocation.class);
            var cancelled = support.getDeclaredField("CANCELLED");
            fail.setAccessible(true);
            cancelled.setAccessible(true);
            fail.invoke(null, h.getLevel(), encounter, cancelled.get(null));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        survivor.discard();
        h.runAtTickTime(3, () -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.FAILED).orElse(false), "failed on cancel");
            h.assertTrue(player.getInventory().countItem(item("apocalypsenow:canned_food", 1).getItem()) == 0, "no reward");
            h.getLevel().getServer().getPlayerList().remove(player);
            h.killAllEntities();
            h.succeed();
        });
    }

    @GameTest(template = "arena", batch = "rescue_death", timeoutTicks = 40)
    public static void survivorDeathFailsTheRescue(GameTestHelper h) {
        Mob survivor = place(h, UUID.randomUUID());
        ServerPlayer player = NpcGameTests.player(h, 12, 16);
        NpcDialogues.open(player, survivor, com.lazarus.npcs.api.dialogue.DialogueTrigger.INTERACT, null);
        choose(player, survivor, 0);
        choose(player, survivor, 0);
        survivor.kill();
        h.runAtTickTime(3, () -> {
            h.assertTrue(NpcApi.encounterTask(player).map(t -> t.state() == TaskState.FAILED).orElse(false), "failed: survivor died");
            h.getLevel().getServer().getPlayerList().remove(player);
            h.killAllEntities();
            h.succeed();
        });
    }
}
