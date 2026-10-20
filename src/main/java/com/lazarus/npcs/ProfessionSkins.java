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
 * Rovers and Wardens with an assigned profession wear their own skin. The server sends "faction_profession" when a
 * player starts tracking such an NPC or the profession changes; the client keeps it by entity id for the renderer.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class ProfessionSkins {
    private static final Map<Integer, ResourceLocation> CLIENT = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();

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

    /** Server: "rovers_guard" style key, empty when the NPC has no explicitly assigned profession. */
    static String key(LivingEntity npc) {
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
