package com.lazarus.npcs.ai;

import com.lazarus.npcs.LazarusNpcs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Small NBT-backed scavenger pack; drops its contents once on death. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class Pack {
    private static final String KEY = "LazarusNpcsScavengePack";

    private Pack() {}

    public static List<ItemStack> items(Mob actor) {
        List<ItemStack> result = new ArrayList<>();
        ListTag saved = actor.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        for (Tag tag : saved) {
            ItemStack stack = ItemStack.of((CompoundTag) tag);
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    public static void add(Mob actor, ItemStack stack) {
        if (stack.isEmpty()) return;
        ListTag saved = actor.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        saved.add(stack.save(new CompoundTag()));
        actor.getPersistentData().put(KEY, saved);
    }

    @SubscribeEvent
    public static void drops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof Mob actor)) return;
        List<ItemStack> contents = items(actor);
        actor.getPersistentData().remove(KEY);
        for (ItemStack stack : contents)
            event.getDrops().add(new ItemEntity(actor.level(), actor.getX(), actor.getY(), actor.getZ(), stack));
    }
}
