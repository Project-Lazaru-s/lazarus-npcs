package com.lazarus.npcs;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rovers, Wardens and Haven residents with an assigned profession wear their own skin; every deserter (rogue
 * soldier), Hollow cultist (cannibal) and military soldier wears one of their group's skins, and military medics
 * wear the medic skin. The server sends the key when a player starts tracking such an NPC or the
 * profession changes; the client keeps it by entity id for the renderer.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class ProfessionSkins {
    private static final Map<Integer, ResourceLocation> CLIENT = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();
    static final int DESERTER_SKINS = 3, MILITARY_SKINS = 3, HOLLOW_SKINS = 3;

    private ProfessionSkins() {}

    /** Client: the profession skin for this entity, or null to keep the Apocalypse Now texture. */
    public static ResourceLocation texture(Entity entity) {
        return CLIENT.isEmpty() ? null : CLIENT.get(entity.getId());
    }

    static void received(int entityId, String key) {
        if (key.isEmpty()) CLIENT.remove(entityId);
        else CLIENT.put(entityId, TEXTURES.computeIfAbsent(key,
                k -> new ResourceLocation(LazarusNpcs.ID, "textures/entity/profession/" + k + ".png")));
    }

    /** Server: "rovers_guard" style key, "deserter_N" / "military_N" / "military_medic", empty for everyone else. */
    static String key(LivingEntity npc) {
        // The same soldier always wears the same skin of their group.
        int pick = Math.floorMod(npc.getUUID().hashCode(), 3);
        if ("soldier".equals(HumanEncounters.kind(npc))) return "deserter_" + (1 + pick % DESERTER_SKINS);
        if ("cannibal".equals(HumanEncounters.kind(npc))) return "hollow_" + (1 + pick % HOLLOW_SKINS);
        String role = NpcIdentity.role(npc);
        if ("military".equals(role)) return "military_" + (1 + pick % MILITARY_SKINS);
        if ("medic".equals(role)) return "military_medic";
        String profession = NpcProfessions.assigned(npc);
        return profession.isEmpty() ? "" : NpcIdentity.faction(npc) + "_" + profession;
    }

    static void broadcast(LivingEntity npc) {
        if (!npc.level().isClientSide) NpcNetwork.sendSkin(npc, key(npc), null);
    }

    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof LivingEntity npc) {
            String key = key(npc);
            if (!key.isEmpty()) NpcNetwork.sendSkin(npc, key, player);
        }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) CLIENT.remove(event.getEntity().getId());
    }
}
