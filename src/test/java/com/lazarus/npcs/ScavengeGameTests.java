package com.lazarus.npcs;

import com.lazarus.npcs.ai.Pack;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises the unopened LootTable guard and the death-drop path with real block entities. */
@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class ScavengeGameTests {
    private ScavengeGameTests() {}

    @GameTest(template = "arena", batch = "scavenge_survivor", timeoutTicks = 30)
    public static void survivorTakesOnlyUntouchedLoot(GameTestHelper h) {
        verify(h, "survivor_1");
    }

    @GameTest(template = "arena", batch = "scavenge_looter", timeoutTicks = 30)
    public static void looterAlsoScavenges(GameTestHelper h) {
        verify(h, "looter_1");
    }

    private static void verify(GameTestHelper h, String type) {
        BlockPos chest = h.absolutePos(new BlockPos(16, 2, 16));
        BlockPos ordinary = h.absolutePos(new BlockPos(19, 2, 16));
        h.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        h.getLevel().setBlock(ordinary, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity loot = (ChestBlockEntity) h.getLevel().getBlockEntity(chest);
        loot.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 1L);
        Mob actor = NpcGameTests.npc(h, type, 12, 16);
        h.assertTrue(NpcAiHooks.canScavenge(actor), type + " is an eligible scavenger");
        try {
            Class<?> goalType = Class.forName("com.lazarus.npcs.ai.ScavengeGoal");
            Constructor<?> constructor = goalType.getDeclaredConstructor(Mob.class);
            constructor.setAccessible(true);
            Object goal = constructor.newInstance(actor);
            Method find = goalType.getDeclaredMethod("find");
            find.setAccessible(true);
            h.assertTrue(chest.equals(find.invoke(goal)), "only the untouched chest is selected");
            Method take = goalType.getDeclaredMethod("take", net.minecraft.server.level.ServerLevel.class, BlockPos.class);
            take.setAccessible(true);
            take.invoke(goal, h.getLevel(), chest);
            h.assertTrue(!loot.saveWithoutMetadata().contains("LootTable"), "loot generated exactly once");
            h.assertTrue(Pack.items(actor).size() >= 1 && Pack.items(actor).size() <= 2, "one or two stacks taken");
            int stored = 0;
            for (int slot = 0; slot < loot.getContainerSize(); slot++) if (!loot.getItem(slot).isEmpty()) stored++;
            h.assertTrue(stored > 0, "rest of the loot stays in the chest");
            h.assertTrue(find.invoke(goal) == null, "ordinary and already-opened chests are ignored");
            actor.kill();
            h.assertTrue(!h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(actor.blockPosition()).inflate(4)).isEmpty(),
                    "pack drops on death");
            h.killAllEntities();
            h.succeed();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
