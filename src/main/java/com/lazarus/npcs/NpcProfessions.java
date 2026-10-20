package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcFactions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent vocation, separate from the Apocalypse Now entity variant and faction. */
final class NpcProfessions {
    private static final String KEY = "LazarusNpcsProfession";
    /** 2: modded armor loadouts replace the dyed leather vest of version 1. */
    private static final int VERSION = 2;
    private static final String OUTFIT = "LazarusProfessionOutfit";
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Armor per faction and profession, slot order head/chest/legs/feet; each slot lists alternatives and the first
     * registered armor item of that slot wins, so Modern Mayhem pieces fall back to Apocalypse Now ones. Rovers wear
     * salvaged, mismatched gear; Wardens wear matching kit; Haven guards a light civilian vest. Professions without a loadout are told apart by skin.
     */
    static final Map<String, String[][]> LOADOUTS = Map.ofEntries(
            Map.entry("rovers_leader", new String[][] {
                    {"apocalypsenow:cowboy_hat_helmet"},
                    {"apocalypsenow:bvd_assault_vest_chestplate"},
                    {},
                    {"apocalypsenow:construction_boots"}}),
            Map.entry("rovers_guard", new String[][] {
                    {"apocalypsenow:black_motorcycle_helmet_helmet"},
                    {"apocalypsenow:rusty_chestplate"},
                    {},
                    {"apocalypsenow:rusty_boots"}}),
            Map.entry("rovers_expedition_soldier", new String[][] {
                    {"mm:tan_combat_helmet", "apocalypsenow:mo4_mud_camo_helmet"},
                    {"mm:tan_kevlar_chestplate", "apocalypsenow:desertcamo_6b_chestplate"},
                    {},
                    {"mm:tan_kevlar_boots", "apocalypsenow:brazilian_army_boots"}}),
            Map.entry("wardens_leader", new String[][] {
                    {"apocalypsenow:officer_helmet"},
                    {"apocalypsenow:officer_beret_chestplate"},
                    {},
                    {"apocalypsenow:police_boots"}}),
            Map.entry("wardens_guard", new String[][] {
                    {"mm:black_combat_helmet", "apocalypsenow:swat_helmet"},
                    {"mm:black_kevlar_chestplate", "apocalypsenow:swat_chestplate"},
                    {"mm:black_kevlar_leggings", "apocalypsenow:swat_leggings"},
                    {"mm:black_kevlar_boots", "apocalypsenow:swat_boots"}}),
            Map.entry("wardens_expedition_soldier", new String[][] {
                    {"mm:green_ssh68_helmet", "apocalypsenow:un_armor_helmet"},
                    {"mm:green_kevlar_chestplate", "apocalypsenow:military_bulletprooft_vest_chestplate"},
                    {},
                    {"mm:green_kevlar_boots", "apocalypsenow:military_urban_boots"}}),
            Map.entry("haven_guard", new String[][] {
                    {"apocalypsenow:hunting_helmet"},
                    {"apocalypsenow:civilian_body_vest_chestplate"},
                    {},
                    {}}));
    private static final Map<String, String> LABELS = Map.of(
            "leader", "Leader", "guard", "Guard", "armorer", "Armorer",
            "trader", "Trader", "medic", "Medic", "expedition_soldier", "Expedition soldier");
    private static final Set<String> BASE = Set.of("leader", "guard", "armorer", "trader", "medic");

    private NpcProfessions() {}

    static String get(LivingEntity npc) {
        if (!"survivor".equals(NpcIdentity.role(npc))) return "";
        String faction = NpcIdentity.faction(npc);
        if (NpcFactions.get(faction) == null || !NpcFactions.get(faction).professions()) return "";
        String stored = npc.getPersistentData().getCompound(KEY).getString("Id");
        return LABELS.containsKey(stored) ? stored : "trader";
    }

    /** The explicitly stored profession, or "" for ambient Rovers/Wardens and every other NPC. */
    static String assigned(LivingEntity npc) {
        if (!"survivor".equals(NpcIdentity.role(npc))) return "";
        String faction = NpcIdentity.faction(npc);
        if (NpcFactions.get(faction) == null || !NpcFactions.get(faction).professions()) return "";
        String stored = npc.getPersistentData().getCompound(KEY).getString("Id");
        return LABELS.containsKey(stored) ? stored : "";
    }

    static String label(LivingEntity npc) { return LABELS.getOrDefault(get(npc), ""); }

    static UUID base(LivingEntity npc) {
        CompoundTag state = npc.getPersistentData().getCompound(KEY);
        return state.hasUUID("Base") ? state.getUUID("Base") : null;
    }

    /** The service a base member still offers: repair (armorer) or stabilize (medic); null for the rest. */
    static String baseService(LivingEntity npc) {
        return switch (get(npc)) {
            case "armorer" -> "repair";
            case "medic" -> "stabilize";
            default -> null;
        };
    }

    static boolean canTrade(LivingEntity npc) {
        String profession = get(npc);
        return profession.isEmpty() || "trader".equals(profession) || "armorer".equals(profession);
    }

    static ApiResult assign(Mob npc, ResourceLocation profession, UUID baseId) {
        String faction = NpcIdentity.faction(npc);
        if (!"survivor".equals(NpcIdentity.role(npc)) || NpcFactions.get(faction) == null
                || !NpcFactions.get(faction).professions())
            return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "professional faction survivor required");
        if (profession == null || !LazarusNpcs.ID.equals(profession.getNamespace())
                || !LABELS.containsKey(profession.getPath()))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "unknown profession");
        String id = profession.getPath();
        if (BASE.contains(id) && baseId == null)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "base profession requires base id");
        CompoundTag existing = npc.getPersistentData().getCompound(KEY);
        if (existing.hasUUID("Base") && !existing.getUUID("Base").equals(baseId))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "NPC belongs to another base");
        if (id.equals(existing.getString("Id")) && (baseId == null || baseId.equals(existing.getUUID("Base")))) {
            if (existing.getInt("Version") >= VERSION) return ApiResult.ok("profession unchanged");
            existing.putInt("Version", VERSION); // 1.8/1.9 residents trade the dyed leather vest for their loadout
            outfit(npc, id);
            return ApiResult.ok("profession outfit updated");
        }
        CompoundTag state = new CompoundTag();
        state.putInt("Version", VERSION);
        state.putString("Id", id);
        if (baseId != null) state.putUUID("Base", baseId);
        npc.getPersistentData().put(KEY, state);
        outfit(npc, id);
        ProfessionSkins.broadcast(npc);
        return ApiResult.ok("profession assigned");
    }

    private static void outfit(Mob npc, String profession) {
        String[][] loadout = LOADOUTS.getOrDefault(NpcIdentity.faction(npc) + "_" + profession, new String[4][0]);
        for (int i = 0; i < ARMOR.length; i++) {
            EquipmentSlot slot = ARMOR[i];
            ItemStack existing = npc.getItemBySlot(slot);
            // Never replace armor the NPC got elsewhere; only our own previous outfit pieces are swapped.
            if (!existing.isEmpty() && !(existing.hasTag() && existing.getTag().getBoolean(OUTFIT))) continue;
            ItemStack piece = piece(loadout[i], slot);
            if (!piece.isEmpty()) piece.getOrCreateTag().putBoolean(OUTFIT, true);
            npc.setItemSlot(slot, piece);
            npc.setDropChance(slot, 0); // outfits are not loot: killing residents must not farm armor
        }
    }

    /** First registered armor item for the slot, or empty. */
    static ItemStack piece(String[] candidates, EquipmentSlot slot) {
        for (String id : candidates) {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item instanceof ArmorItem armor && armor.getEquipmentSlot() == slot) return new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }
}
