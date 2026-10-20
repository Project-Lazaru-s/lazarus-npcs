package com.lazarus.npcs.mixin;

import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

/**
 * Modern Mayhem 2.0.1 registers every packet with consumerNetworkThread and its handlers never enqueue work, so the
 * backpack and curio keybinds open menus on the Netty thread while the server tick broadcasts the same menu
 * (ConcurrentModificationException in AbstractContainerMenu.triggerSlotListeners, crash 2026-10-19). Registering
 * them with consumerMainThread runs each handler on the server (or client) main thread instead.
 * Pseudo + require 0: without Modern Mayhem, or after it fixes this itself, nothing is applied.
 */
@Pseudo
@Mixin(targets = "net.tkg.ModernMayhem.server.registry.PacketsRegistryMM$PacketType", remap = false)
abstract class ModernMayhemPacketThreadMixin {
    @Redirect(method = "register", require = 0, remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;consumerNetworkThread(Ljava/util/function/BiConsumer;)Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SimpleChannel.MessageBuilder lazarusnpcs$mainThread(SimpleChannel.MessageBuilder builder, BiConsumer handler) {
        return builder.consumerMainThread(handler);
    }
}
