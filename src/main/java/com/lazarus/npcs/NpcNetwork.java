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
            new ResourceLocation(LazarusNpcs.ID, "ui"), () -> "9", "9"::equals, "9"::equals);
    /** Longest JSON text component sent for dialogue lines and labels. */
    static final int MAX_COMPONENT_JSON = 2_048;

    private NpcNetwork() {}

    static void register() {
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, DialogueView.class, DialogueView::encode, DialogueView::decode, DialogueView::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, DialogueChoice.class, DialogueChoice::encode, DialogueChoice::decode, DialogueChoice::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, Skin.class, Skin::encode, Skin::decode, Skin::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** To one player, or to everyone tracking the NPC when player is null. */
    static void sendSkin(LivingEntity npc, String key, ServerPlayer player) {
        Skin skin = new Skin(npc.getId(), key);
        if (player != null) CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), skin);
        else CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> npc), skin);
    }

    /** Server to client: profession skin key ("rovers_guard"), empty to restore the original texture. */
    record Skin(int entity, String key) {
        static void encode(Skin skin, FriendlyByteBuf buf) {
            buf.writeVarInt(skin.entity);
            buf.writeUtf(skin.key, 48);
        }

        static Skin decode(FriendlyByteBuf buf) {
            return new Skin(buf.readVarInt(), buf.readUtf(48));
        }

        static void handle(Skin skin, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> ProfessionSkins.received(skin.entity, skin.key));
            context.get().setPacketHandled(true);
        }
    }

    static void send(String action, UUID npc) { CHANNEL.sendToServer(new Action(action, npc)); }

    static void choose(UUID npc, long token, int index) { CHANNEL.sendToServer(new DialogueChoice(npc, token, index)); }

    static void sendDialogue(ServerPlayer player, LivingEntity npc, String lineJson, java.util.List<String> labelsJson, long token) {
        String name = NpcIdentity.role(npc) != null ? NpcIdentity.name(npc)
                : HumanEncounters.kind(npc) != null ? HumanEncounters.label(HumanEncounters.kind(npc)) : npc.getName().getString();
        String faction = NpcIdentity.role(npc) != null ? NpcIdentity.faction(npc) : "hostile";
        CHANNEL.sendTo(new DialogueView(npc.getUUID(), name, faction, lineJson, labelsJson, token, false),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    static void closeDialogue(ServerPlayer player) {
        CHANNEL.sendTo(new DialogueView(new UUID(0, 0), "", "", "", java.util.List.of(), 0, true),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

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
                npc == null ? "" : encounter ? HumanEncounters.title(npc, kind) : NpcIdentity.name(npc),
                npc == null ? "" : encounter ? kind : NpcIdentity.role(npc),
                npc == null || encounter ? "" : NpcProfessions.get(npc),
                npc != null && NpcProfessions.base(npc) != null,
                npc == null ? "" : encounter ? "independent" : NpcIdentity.faction(npc),
                npc == null ? NpcTasks.summary(player) : encounter ? "" : NpcTasks.preview(player, npc),
                npc == null ? NpcTasks.location(player) : "",
                npc == null ? "" : "surrender".equals(page) && npc instanceof net.minecraft.world.entity.Mob prisoner
                        ? String.join(",", HumanSurrender.choices(player, prisoner))
                        : encounter ? HumanEncounters.demand(kind) : NpcServices.summary(player, npc),
                contract, NpcStories.summary(player), npc == null ? EncounterTasks.journal(player) : "", reputation);
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
                if ("encabandon".equals(packet.kind)) { EncounterTasks.abandon(player); journal(player); return; }
                var entity = player.serverLevel().getEntity(packet.npc);
                if (!(entity instanceof LivingEntity npc) || !npc.isAlive() || npc.distanceToSqr(player) > 64
                        || !player.hasLineOfSight(npc)) return;
                if ("tribute".equals(packet.kind)) {
                    HumanEncounters.pay(player, npc);
                    return;
                }
                if (npc instanceof net.minecraft.world.entity.Mob prisoner && java.util.Set.of("strip", "letgo", "interrogate", "refuge")
                        .contains(packet.kind)) {
                    switch (packet.kind) {
                        case "strip" -> HumanSurrender.strip(player, prisoner);
                        case "letgo" -> HumanSurrender.letGo(player, prisoner);
                        case "interrogate" -> HumanSurrender.interrogate(player, prisoner);
                        default -> HumanSurrender.sendAway(player, prisoner);
                    }
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
                        if (NpcProfessions.base(npc) != null && !"leader".equals(NpcProfessions.get(npc))) return;
                        if (NpcProfessions.base(npc) != null) {
                            NpcDialogues.open(player, npc, com.lazarus.npcs.api.dialogue.DialogueTrigger.INTERACT, null);
                            return;
                        }
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

    record View(String page, UUID npc, String name, String role, String profession, boolean baseMember, String faction,
                String task, String taskLocation, String service, String contract, String stories, String encounter,
                int[] reputation) {
        static void encode(View view, FriendlyByteBuf buf) {
            buf.writeUtf(view.page, 12);
            buf.writeUUID(view.npc);
            buf.writeUtf(view.name, 64);
            buf.writeUtf(view.role, 16);
            buf.writeUtf(view.profession, 32);
            buf.writeBoolean(view.baseMember);
            buf.writeUtf(view.faction, 16);
            buf.writeUtf(view.task, 400);
            buf.writeUtf(view.taskLocation, 200);
            buf.writeUtf(view.service, 160);
            buf.writeUtf(view.contract, 400);
            buf.writeUtf(view.stories, 800);
            buf.writeUtf(view.encounter, 1000);
            for (int score : view.reputation) buf.writeVarInt(score);
        }
        static View decode(FriendlyByteBuf buf) {
            String page = buf.readUtf(12);
            UUID npc = buf.readUUID();
            String name = buf.readUtf(64);
            String role = buf.readUtf(16);
            String profession = buf.readUtf(32);
            boolean baseMember = buf.readBoolean();
            String faction = buf.readUtf(16);
            String task = buf.readUtf(400);
            String taskLocation = buf.readUtf(200);
            String service = buf.readUtf(160);
            String contract = buf.readUtf(400);
            String stories = buf.readUtf(800);
            String encounter = buf.readUtf(1000);
            int[] reputation = new int[NpcIdentity.FACTIONS.length];
            for (int i = 0; i < reputation.length; i++) reputation[i] = buf.readVarInt();
            return new View(page, npc, name, role, profession, baseMember, faction, task, taskLocation, service, contract, stories, encounter, reputation);
        }
        static void handle(View view, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> NpcClient.open(view)));
            context.get().setPacketHandled(true);
        }
    }

    /** Server to client: a dialogue page, or {@code close} to dismiss the dialogue screen. */
    record DialogueView(UUID npc, String name, String faction, String lineJson, java.util.List<String> labelsJson,
                        long token, boolean close) {
        static void encode(DialogueView view, FriendlyByteBuf buf) {
            buf.writeUUID(view.npc);
            buf.writeUtf(view.name, 64);
            buf.writeUtf(view.faction, 16);
            buf.writeUtf(view.lineJson, MAX_COMPONENT_JSON);
            buf.writeVarInt(view.labelsJson.size());
            for (String label : view.labelsJson) buf.writeUtf(label, MAX_COMPONENT_JSON);
            buf.writeLong(view.token);
            buf.writeBoolean(view.close);
        }

        static DialogueView decode(FriendlyByteBuf buf) {
            UUID npc = buf.readUUID();
            String name = buf.readUtf(64);
            String faction = buf.readUtf(16);
            String line = buf.readUtf(MAX_COMPONENT_JSON);
            int count = buf.readVarInt();
            if (count < 0 || count > com.lazarus.npcs.api.dialogue.DialoguePage.MAX_OPTIONS)
                throw new IllegalArgumentException("too many dialogue options: " + count);
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) labels.add(buf.readUtf(MAX_COMPONENT_JSON));
            return new DialogueView(npc, name, faction, line, labels, buf.readLong(), buf.readBoolean());
        }

        static void handle(DialogueView view, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NpcClient.dialogue(view)));
            context.get().setPacketHandled(true);
        }
    }

    /** Client to server: the picked option by position (-1 for Back) and the session token. */
    record DialogueChoice(UUID npc, long token, int index) {
        static void encode(DialogueChoice choice, FriendlyByteBuf buf) {
            buf.writeUUID(choice.npc);
            buf.writeLong(choice.token);
            buf.writeVarInt(choice.index + 1);
        }

        static DialogueChoice decode(FriendlyByteBuf buf) {
            return new DialogueChoice(buf.readUUID(), buf.readLong(), buf.readVarInt() - 1);
        }

        static void handle(DialogueChoice choice, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                ServerPlayer player = context.get().getSender();
                if (player != null && choice.index >= -1 && choice.index < com.lazarus.npcs.api.dialogue.DialoguePage.MAX_OPTIONS)
                    NpcDialogues.choose(player, choice.npc, choice.token, choice.index);
            });
            context.get().setPacketHandled(true);
        }
    }
}
