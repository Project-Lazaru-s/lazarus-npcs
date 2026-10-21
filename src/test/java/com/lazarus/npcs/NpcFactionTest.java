package com.lazarus.npcs;

import com.lazarus.npcs.api.NpcFactions;
import net.minecraft.nbt.CompoundTag;

public final class NpcFactionTest {
    public static void main(String[] args) {
        for (int i = 1; i <= 10; i++) {
            String expected = i <= 3 ? "haven" : i <= 6 ? "rovers" : i <= 8 ? "wardens" : "independent";
            assert expected.equals(NpcIdentity.factionForVariant(i)) : "survivor_" + i;
        }
        assert "independent".equals(NpcIdentity.factionForVariant(0));
        assert NpcFactions.all().stream().map(NpcFactions.Definition::id).toList()
                .equals(java.util.List.of("haven", "rovers", "wardens", "military"));
        assert NpcFactions.get("rovers").variant(3) == 4;
        assert NpcFactions.get("wardens").variant(2) == 7;
        assert NpcFactions.get("unknown") == null;
        assert NpcFactions.get("rovers").doctrine().flankShare() == 0.34;
        assert NpcFactions.get("wardens").doctrine().bounding();
        assert NpcFactions.get("haven").professions() && NpcFactions.get("haven").rivals().isEmpty();
        CompoundTag oldReputation = new CompoundTag();
        oldReputation.putInt("survivor", 17);
        oldReputation.putInt("military", 9);
        NpcReputation.migrate(oldReputation);
        for (String faction : new String[]{"haven", "rovers", "wardens"})
            assert oldReputation.getInt(faction) == 17 : faction;
        oldReputation.putInt("rovers", 4);
        NpcReputation.migrate(oldReputation);
        assert oldReputation.getInt("rovers") == 4;
        assert oldReputation.getInt("military") == 9;
        assert FactionRelations.rivals("rovers", "wardens");
        assert FactionRelations.rivals("wardens", "rovers");
        assert !FactionRelations.rivals("haven", "rovers");
        assert !FactionRelations.rivals("military", "wardens");
        assert !FactionRelations.rivals("rovers", "rovers");
        assert !HumanEncounters.warningExpired(1_000, 1_200);
        assert NpcReputation.rivalDelta(75) == 0;
        assert NpcReputation.rivalDelta(100) == -1;
        assert NpcReputation.rivalDelta(-100) == 1;
        assert NpcReputation.recover(-20, 0, 2) == -10;
        assert NpcReputation.recover(-20, -20, 20) == -20 : "political standing persists";
        assert NpcReputation.recover(-30, -20, 2) == -20 : "only the ordinary penalty recovers";
        assert HumanEncounters.warningExpired(1_000, 7_001);
        assert HumanEncounters.warningExpired(9_000, 100);
        assert "east".equals(NpcServices.direction(10, 0)) && "north".equals(NpcServices.direction(0, -10));
        assert "north-east".equals(NpcServices.direction(10, -10)) && "south-west".equals(NpcServices.direction(-10, 10));
        assert !HumanTactics.lostAdvantage(4, 1, 60, 80);
        assert HumanTactics.lostAdvantage(4, 2, 60, 80);
        assert HumanTactics.lostAdvantage(3, 0, 20, 60);
        assert !HumanTactics.lostAdvantage(1, 0, 20, 20);
        assert !HumanTactics.readyToAmbush(1, 2_000, 8, false, true);
        assert !HumanTactics.readyToAmbush(3, 600, 8, false, false);
        assert HumanTactics.readyToAmbush(3, 600, 8, false, true);
        assert HumanTactics.readyToAmbush(3, 1_300, 8, false, false);
        assert !HumanTactics.readyToAmbush(3, 1_300, 8, true, true);
        assert HumanSurrender.chance("cannibal", 0.1f, 1, 3, 2) == 0 : "cannibals never surrender";
        assert HumanSurrender.chance("looter", 0.5f, 1, 3, 0) == 0 : "healthy NPCs do not surrender";
        assert HumanSurrender.chance("looter", 0.3f, 5, 2, 0) == 0 : "a group with 2:1 odds fights on";
        assert HumanSurrender.chance("looter", 0.3f, 2, 2, 0) == 0.45;
        assert HumanSurrender.chance("looter", 0.3f, 3, 2, 0) == 0.225 : "not outnumbered halves the chance";
        assert HumanSurrender.chance("soldier", 0.3f, 2, 2, 0) < HumanSurrender.chance("looter", 0.3f, 2, 2, 0);
        assert HumanSurrender.chance("looter", 0.1f, 1, 4, 3) <= 0.8 : "never guaranteed";
        // Negotiation skills: Intimidação adds 15% only when a surrender is possible at all, still capped at 80%
        assert Math.abs(HumanSurrender.chance("looter", 0.3f, 2, 2, 0, true) - 0.6) < 1e-9;
        assert HumanSurrender.chance("cannibal", 0.1f, 1, 3, 2, true) == 0 : "cannibals still never surrender";
        assert HumanSurrender.chance("looter", 0.5f, 1, 3, 0, true) == 0 : "healthy NPCs still do not surrender";
        assert HumanSurrender.chance("looter", 0.1f, 1, 4, 3, true) == 0.8 : "never guaranteed";
        // Lábia -1, Pechincha -10% (at least 1)
        assert NpcMerchant.skillDiscount(12, false, false) == 0;
        assert NpcMerchant.skillDiscount(12, true, false) == 1;
        assert NpcMerchant.skillDiscount(12, true, true) == 2 && NpcMerchant.skillDiscount(30, true, true) == 4;
        // S6: every TaCZ gun type has an Armeiro branch; unknown types fall under Fuzis
        for (String type : new String[] {"pistol", "smg", "shotgun", "rifle", "sniper", "mg", "rpg"})
            assert GunSkillLock.BRANCHES.containsKey(type) : type;
        assert GunSkillLock.branch("pistol")[0].equals("lazarus_arma_pistola");
        assert GunSkillLock.branch("flamethrower") == GunSkillLock.branch("rifle");
        for (String type : new String[] {"grip", "stock", "scope", "laser", "muzzle", "extended_mag"})
            assert GunSkillLock.ATTACHMENTS.containsKey(type) : type;
        assert GunSkillLock.attachmentBranch("none") == GunSkillLock.attachmentBranch("scope");
        assert NpcReputation.penaltyDue(null, 50) && !NpcReputation.penaltyDue(100L, 900) && NpcReputation.penaltyDue(100L, 1_300);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Random random = new java.util.Random(7);
        var soldier = NpcGuns.ARSENALS.get("soldier");
        for (int i = 0; i < 12_000; i++) counts.merge(NpcGuns.pick(soldier.guns(), random.nextInt(1 << 16)), 1, Integer::sum);
        assert counts.get("ak47") > counts.get("uzi") * 1.6 && counts.get("rpk") < counts.get("uzi") : counts;
        assert counts.keySet().equals(soldier.guns().keySet()) : "every listed gun can appear";
        assert !NpcGuns.ARSENALS.containsKey("cannibal") : "cannibals never carry guns";
        for (var arsenal : NpcGuns.ARSENALS.values()) assert arsenal.chance() > 0 && arsenal.chance() <= 0.3f : "keep armed spawns uncommon";
        assert "3 canned food and 2 iron ingots".equals(NpcContracts.costText(NpcContracts.ESCORT_COST));
    }
}
