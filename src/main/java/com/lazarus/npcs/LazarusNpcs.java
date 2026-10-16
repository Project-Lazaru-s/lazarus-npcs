package com.lazarus.npcs;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod(LazarusNpcs.ID)
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class LazarusNpcs {
    public static final String ID = "lazarusnpcs";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, NpcMerchant> MERCHANTS = new HashMap<>();

    public LazarusNpcs() {
        TradeCatalog.load();
        NpcNetwork.register();
        NpcSounds.SOUNDS.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                NpcConfig.SPEC, "lazarus-npcs.toml");
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().getMainHandItem().isEmpty()) return;
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity npc) || !npc.isAlive()) return;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (id == null || !"apocalypsenow".equals(id.getNamespace())) return;
        if (event.getEntity() instanceof ServerPlayer player && HumanSurrender.holding(npc)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            if (HumanSurrender.canResolve(npc, player)) NpcNetwork.openSurrender(player, npc);
            else player.displayClientMessage(Component.literal("They surrendered to someone else."), true);
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && HumanEncounters.canNegotiate(npc, player)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            NpcNetwork.openEncounter(player, npc);
            return;
        }
        String role = role(id.getPath());
        if (role == null) return;

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (npc instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == player) {
            player.displayClientMessage(Component.literal("They are fighting you, not talking."), true);
            return;
        }
        NpcIdentity.name(npc);
        String faction = NpcIdentity.faction(npc);
        if (NpcReputation.get(player, faction) <= -20) {
            player.displayClientMessage(Component.translatable("lazarusnpcs.refused"), true);
            return;
        }

        if (player.isShiftKeyDown()) {
            NpcTasks.interact(player, npc, role);
            return;
        }
        NpcDialogue.greet(player, npc);
        NpcNetwork.openNpc(player, npc);
    }

    static void trade(ServerPlayer player, LivingEntity npc) {
        String role = NpcIdentity.role(npc);
        if (role == null || !npc.isAlive() || npc.level() != player.level()
                || npc.distanceToSqr(player) > 64 || NpcReputation.get(player, NpcIdentity.faction(npc)) <= -20) return;
        NpcMerchant merchant = MERCHANTS.computeIfAbsent(npc.getUUID(), ignored -> new NpcMerchant(npc, role));
        Player tradingPlayer = merchant.getTradingPlayer();
        if (tradingPlayer != null && tradingPlayer != player) {
            player.displayClientMessage(Component.translatable("lazarusnpcs.busy"), true);
            return;
        }
        merchant.refresh();
        merchant.setTradingPlayer(player);
        merchant.openTradeScreen(player, Component.literal(NpcIdentity.name(npc) + " / " + NpcIdentity.label(NpcIdentity.faction(npc))));
    }

    static String role(String path) {
        if (path.startsWith("survivor_")) return "survivor";
        if (path.equals("hunter")) return "hunter";
        if (path.equals("military_medic")) return "medic";
        if (path.equals("military_01") || path.equals("military_02")) return "military";
        return null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        for (NpcMerchant merchant : MERCHANTS.values()) {
            if (merchant.getTradingPlayer() == player && !merchant.canTradeWith(player)) {
                player.closeContainer();
                merchant.setTradingPlayer(null);
            }
        }
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        NpcMerchant merchant = MERCHANTS.remove(event.getEntity().getUUID());
        if (merchant != null && merchant.getTradingPlayer() instanceof ServerPlayer player) player.closeContainer();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        MERCHANTS.clear();
    }
}
