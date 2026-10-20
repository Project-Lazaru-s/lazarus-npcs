package com.lazarus.npcs;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(LazarusNpcs.ID)
@PrefixGameTestTemplate(false)
public final class TravelGameTests {
    private TravelGameTests() {}

    @GameTest(template = "arena", batch = "travel_formation", timeoutTicks = 600)
    public static void expeditionResumesAfterContact(GameTestHelper h) {
        ResourceLocation owner = new ResourceLocation(LazarusNpcs.ID, "travel_test");
        UUID encounter = UUID.randomUUID();
        List<BlockPos> route = List.of(10, 15, 20, 25).stream()
                .map(x -> h.absolutePos(new BlockPos(x, 2, 16))).toList();
        Mob[] actors = {NpcGameTests.npc(h, "survivor_4", 5, 15),
                NpcGameTests.npc(h, "survivor_4", 5, 16), NpcGameTests.npc(h, "survivor_4", 5, 17)};
        for (Mob actor : actors) {
            h.assertTrue(NpcApi.assign(actor, new ScenarioAssignment(owner, encounter, ScenarioOrder.TRAVEL,
                    GlobalPos.of(h.getLevel().dimension(), route.get(0)), null, 16, 1000, 3)).ok(), "travel order");
            h.assertTrue(NpcApi.setTravelRoute(actor, owner, encounter, route).ok(), "four point route");
        }
        Mob scout = Arrays.stream(actors).min(java.util.Comparator.comparing(Mob::getUUID)).orElseThrow();
        Mob threat = h.spawn(EntityType.ZOMBIE, 24, 2, 24);
        threat.setNoAi(true);
        h.runAfterDelay(80, () -> scout.setTarget(threat));
        h.runAfterDelay(120, () -> { // contact ends: the threat is gone (fights near the waypoint are now answered)
            threat.discard();
            scout.setTarget(null);
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getGameTime() > 140, "contact has ended");
            h.assertTrue(scout.getPersistentData().getCompound("LazarusNpcsTravel").getInt("Index") >= 2,
                    "scout resumed the route after contact: " + scout.getPersistentData().getCompound("LazarusNpcsTravel")
                            + " at " + h.relativePos(scout.blockPosition()) + " target " + scout.getTarget());
            h.assertTrue(Arrays.stream(actors).allMatch(actor -> actor.distanceToSqr(scout) < 18 * 18),
                    "formation stays near the scout");
            h.killAllEntities();
        });
    }
}
