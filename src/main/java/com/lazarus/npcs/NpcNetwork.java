package com.lazarus.npcs;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class NpcNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(LazarusNpcs.ID, "ui"), () -> "3", "3"::equals, "3"::equals);

    private NpcNetwork() {}

    static void register() {
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void send(String action, UUID npc) { CHANNEL.sendToServer(new Action(action, npc)); }

    static void journal(ServerPlayer player) { sendView(player, "journal", null); }

    static void openNpc(ServerPlayer player, LivingEntity npc) { sendView(player, "npc", npc); }

    static void openEncounter(ServerPlayer player, LivingEntity npc) { sendView(player, "encounter", npc); }

    static void openSurrender(ServerPlayer player, LivingEntity npc) { sendView(player, "surrender", npc); }

    private static void sendView(ServerPlayer player, String page, LivingEntity npc) {
        int[] reputation = new int[NpcIdentity.FACTIONS.length];
        for (int i = 0; i < reputation.length; i++)
            reputation[i] = NpcReputation.get(player, NpcIdentity.FACTIONS[i]);
        boolean encounter = "encounter".equals(page) || "surrender".equals(page);
        String kind = encounter ? HumanEncounters.kind(npc) : "";
        String contract = npc == null ? NpcContracts.playerSummary(player)
                : encounter || !NpcContracts.canGuard(npc) ? "" : NpcContracts.npcStatus(player, npc);
        View view = new View(page, npc == null ? new UUID(0, 0) : npc.getUUID(),
                npc == null ? "" : encounter ? HumanEncounters.label(kind) : NpcIdentity.name(npc),
                npc == null ? "" : encounter ? kind : NpcIdentity.role(npc),
                npc == null ? "" : encounter ? "independent" : NpcIdentity.faction(npc),
                npc == null ? NpcTasks.summary(player) : encounter ? "" : NpcTasks.preview(player, npc),
                npc == null ? "" : encounter ? HumanEncounters.demand(kind) : NpcServices.summary(player, npc),
                contract, NpcStories.summary(player), reputation);
        CHANNEL.sendTo(view, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private record Action(String kind, UUID npc) {
        static void encode(Action packet, FriendlyByteBuf buf) {
            buf.writeUtf(packet.kind, 24);
            buf.writeUUID(packet.npc);
        }
        static Action decode(FriendlyByteBuf buf) { return new Action(buf.readUtf(24), buf.readUUID()); }
        static void handle(Action packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player == null) return;
                if ("journal".equals(packet.kind)) { journal(player); return; }
                if ("abandon".equals(packet.kind)) { NpcTasks.abandon(player); journal(player); return; }
                if ("releaseall".equals(packet.kind)) { NpcContracts.releaseAll(player); journal(player); return; }
                var entity = player.serverLevel().getEntity(packet.npc);
                if (!(entity instanceof LivingEntity npc) || !npc.isAlive() || npc.distanceToSqr(player) > 64
                        || !player.hasLineOfSight(npc)) return;
                if ("tribute".equals(packet.kind)) {
                    HumanEncounters.pay(player, npc);
                    return;
                }
                if (npc instanceof net.minecraft.world.entity.Mob prisoner && ("strip".equals(packet.kind) || "letgo".equals(packet.kind))) {
                    if ("strip".equals(packet.kind)) HumanSurrender.strip(player, prisoner);
                    else HumanSurrender.letGo(player, prisoner);
                    return;
                }
                if ("dismiss".equals(packet.kind)) {
                    NpcContracts.dismiss(player, npc);
                    openNpc(player, npc);
                    return;
                }
                if (NpcIdentity.role(npc) == null) return;
                String faction = NpcIdentity.faction(npc);
                if (NpcReputation.get(player, faction) <= -20) return;
                switch (packet.kind) {
                    case "trade" -> LazarusNpcs.trade(player, npc);
                    case "repair", "stabilize", "escort", "scout" -> {
                        NpcServices.use(player, packet.kind, npc.getUUID());
                        openNpc(player, npc);
                    }
                    case "task" -> {
                        NpcTasks.interact(player, npc, NpcIdentity.role(npc));
                        openNpc(player, npc);
                    }
                    case "open" -> openNpc(player, npc);
                    case "guard:6", "guard:10", "guard:14" -> {
                        if (npc instanceof net.minecraft.world.entity.Mob guard)
                            NpcContracts.hireGuard(player, guard, Integer.parseInt(packet.kind.substring(6)));
                        openNpc(player, npc);
                    }
                    default -> { }
                }
            });
            context.get().setPacketHandled(true);
        }
    }

    record View(String page, UUID npc, String name, String role, String faction,
                String task, String service, String contract, String stories, int[] reputation) {
        static void encode(View view, FriendlyByteBuf buf) {
            buf.writeUtf(view.page, 12);
            buf.writeUUID(view.npc);
            buf.writeUtf(view.name, 64);
            buf.writeUtf(view.role, 16);
            buf.writeUtf(view.faction, 16);
            buf.writeUtf(view.task, 400);
            buf.writeUtf(view.service, 160);
            buf.writeUtf(view.contract, 400);
            buf.writeUtf(view.stories, 800);
            for (int score : view.reputation) buf.writeVarInt(score);
        }
        static View decode(FriendlyByteBuf buf) {
            String page = buf.readUtf(12);
            UUID npc = buf.readUUID();
            String name = buf.readUtf(64);
            String role = buf.readUtf(16);
            String faction = buf.readUtf(16);
            String task = buf.readUtf(400);
            String service = buf.readUtf(160);
            String contract = buf.readUtf(400);
            String stories = buf.readUtf(800);
            int[] reputation = new int[NpcIdentity.FACTIONS.length];
            for (int i = 0; i < reputation.length; i++) reputation[i] = buf.readVarInt();
            return new View(page, npc, name, role, faction, task, service, contract, stories, reputation);
        }
        static void handle(View view, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> NpcClient.open(view)));
            context.get().setPacketHandled(true);
        }
    }
}
