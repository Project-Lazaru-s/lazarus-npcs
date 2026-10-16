package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

final class NpcIdentity {
    static final String[] FACTIONS = {"haven", "rovers", "wardens", "military"};
    private static final String KEY = "LazarusNpcsIdentity";
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
        var path = ForgeRegistries.ENTITY_TYPES.getKey(npc.getType()).getPath();
        int variant;
        try { variant = Integer.parseInt(path.substring("survivor_".length())); }
        catch (NumberFormatException exception) { return "independent"; }
        return factionForVariant(variant);
    }

    static String factionForVariant(int variant) {
        if (variant >= 1 && variant <= 3) return "haven";
        if (variant <= 6 && variant >= 4) return "rovers";
        if (variant <= 8 && variant >= 7) return "wardens";
        return "independent";
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
        return switch (faction) {
            case "haven" -> "Haven";
            case "rovers" -> "Rovers";
            case "wardens" -> "Wardens";
            case "military" -> "Military";
            default -> "Independent";
        };
    }
}
