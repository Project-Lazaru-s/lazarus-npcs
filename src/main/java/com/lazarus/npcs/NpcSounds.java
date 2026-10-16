package com.lazarus.npcs;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcSounds {
    static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, LazarusNpcs.ID);
    private static final RegistryObject<SoundEvent> SURVIVOR = SOUNDS.register("survivor_greeting",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(LazarusNpcs.ID, "survivor_greeting")));
    private static final RegistryObject<SoundEvent> MILITARY = SOUNDS.register("military_greeting",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(LazarusNpcs.ID, "military_greeting")));

    private NpcSounds() {}

    static void greeting(ServerPlayer player, LivingEntity npc) {
        SoundEvent voice = "military".equals(NpcIdentity.faction(npc)) ? MILITARY.get() : SURVIVOR.get();
        float volume = NpcConfig.VOICE_VOLUME.get().floatValue();
        if (volume > 0) npc.level().playSound(null, npc.blockPosition(), voice, SoundSource.NEUTRAL, volume, 1f);
    }

    @SubscribeEvent
    static void muteVillagerVoice(PlayLevelSoundEvent event) {
        if (!NpcConfig.MUTE_VILLAGERS.get()) return;
        if (event.getSound() == null) return;
        ResourceLocation soundId = ForgeRegistries.SOUND_EVENTS.getKey(event.getSound().value());
        if (soundId == null || !"minecraft".equals(soundId.getNamespace())
                || !soundId.getPath().startsWith("entity.villager.")) return;

        if (event instanceof PlayLevelSoundEvent.AtEntity atEntity) {
            if (atEntity.getEntity() instanceof LivingEntity npc && NpcIdentity.role(npc) != null)
                event.setCanceled(true);
        } else if (event instanceof PlayLevelSoundEvent.AtPosition atPosition) {
            // Apocalypse Now plays these vocalizations at coordinates, not on the NPC entity.
            Vec3 pos = atPosition.getPosition();
            AABB source = new AABB(pos.x - 0.75, pos.y - 1.5, pos.z - 0.75,
                    pos.x + 0.75, pos.y + 1.5, pos.z + 0.75);
            if (!event.getLevel().getEntitiesOfClass(LivingEntity.class, source,
                    npc -> NpcIdentity.role(npc) != null).isEmpty()) event.setCanceled(true);
        }
    }
}
