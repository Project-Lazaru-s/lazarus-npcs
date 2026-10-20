package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcFactions;
import net.minecraft.world.entity.Mob;

final class NpcIdentity {
    static final String[] FACTIONS = NpcFactions.all().stream().map(NpcFactions.Definition::id).toArray(String[]::new);
    private static final String KEY = "LazarusNpcsIdentity";
    private static final String FACTION_OVERRIDE = "LazarusNpcsFaction";
    private static final String[] FIRST = {"Alex", "Morgan", "Sam", "Riley", "Jordan", "Casey", "Taylor", "Avery", "Robin", "Drew", "Jamie", "Quinn"};
    private static final String[] LAST = {"Cole", "Reed", "Vega", "Cross", "Shaw", "Vale", "Brooks", "Stone", "Hayes", "Price"};

    private NpcIdentity() {}

    static String role(LivingEntity npc) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType());
        return id != null && "apocalypsenow".equals(id.getNamespace()) ? LazarusNpcs.role(id.getPath()) : null;
    }

    static String faction(LivingEntity npc) {
        String role = role(npc);
        if (role == null) return null;
        if ("hunter".equals(role)) return "independent";
        if (!"survivor".equals(role)) return "military";
        String assigned = npc.getPersistentData().getString(FACTION_OVERRIDE);
        if (NpcFactions.get(assigned) != null) return assigned;
        var path = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType()).getPath();
        int variant;
        try { variant = Integer.parseInt(path.substring("survivor_".length())); }
        catch (NumberFormatException exception) { return "independent"; }
        return factionForVariant(variant);
    }

    static String factionForVariant(int variant) {
        return NpcFactions.forVariant(variant);
    }

    static ApiResult assignFaction(Mob npc, String faction) {
        if (!"survivor".equals(role(npc)) || NpcFactions.get(faction) == null)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "known faction survivor required");
        if (NpcProfessions.base(npc) != null && !faction.equals(faction(npc)))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "base resident cannot change faction");
        npc.getPersistentData().putString(FACTION_OVERRIDE, faction);
        ProfessionSkins.broadcast(npc);
        return ApiResult.ok("faction assigned");
    }

    static String name(LivingEntity npc) {
        if (npc.hasCustomName()) return npc.getCustomName().getString();
        CompoundTag state = npc.getPersistentData().getCompound(KEY);
        if (!state.contains("Name")) {
            long seed = npc.getUUID().getMostSignificantBits() ^ npc.getUUID().getLeastSignificantBits();
            String name = FIRST[Math.floorMod(seed, FIRST.length)] + " "
                    + LAST[Math.floorMod(seed >>> 16, LAST.length)];
            state.putString("Name", name);
            state.putString("Faction", faction(npc));
            npc.getPersistentData().put(KEY, state);
            // A Minecraft custom name makes many mobs persistent and would accumulate NPCs indefinitely.
        }
        return state.getString("Name");
    }

    static String label(String faction) {
        NpcFactions.Definition definition = NpcFactions.get(faction);
        return definition == null ? "Independent" : definition.label();
    }
}
