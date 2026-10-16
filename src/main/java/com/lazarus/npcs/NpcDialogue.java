package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcDialogue {
    private static final String KEY = "LazarusNpcsDialogue";

    private NpcDialogue() {}

    @SubscribeEvent
    static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity npc
                && NpcIdentity.role(npc) != null) NpcIdentity.name(npc);
    }

    static void greet(ServerPlayer player, LivingEntity npc) {
        if ("hunter".equals(NpcIdentity.role(npc))) {
            if (say(player, npc, new String[] {
                    "Fresh tracks lead east. Keep your eyes open.",
                    "I know the roads outside the city. Need directions?",
                    "The infected leave signs if you know where to look.",
                    "Bring food and I can scout the area for you.",
                    "I travel alone. Fewer footsteps to give me away.",
                    "If the birds go quiet, find cover."
            })) NpcSounds.greeting(player, npc);
            return;
        }
        String[] lines = switch (NpcIdentity.faction(npc)) {
            case "haven" -> new String[] {
                    "We keep each other alive out here.",
                    "Need supplies? Our people might help.",
                    "The shelter is holding, but we could use another pair of hands.",
                    "If you find medicine, bring it back to us.",
                    "There's room by the fire if you make it through tonight.",
                    "Check the side streets. We spotted movement on the main road."
            };
            case "rovers" -> new String[] {
                    "Found anything worth trading?",
                    "Travel light. The roads aren't safe.",
                    "Good gear changes hands fast out here.",
                    "I've seen worse roads, but not by much.",
                    "Keep an eye on your pack in crowded places.",
                    "If you've got spare parts, I know someone who'll take them."
            };
            case "wardens" -> new String[] {
                    "State your business and keep your hands visible.",
                    "Our patrols don't tolerate trouble.",
                    "Stay clear of the barricade after dark.",
                    "We've had reports of infected nearby.",
                    "If you need a safe route, ask before heading out.",
                    "Don't mistake a quiet street for a safe one."
            };
            case "military" -> new String[] {
                    "Stay behind the line. We'll cover this area.",
                    "Keep moving and watch the rooftops.",
                    "Report any large group of infected you see.",
                    "The perimeter won't hold without supplies.",
                    "Keep your weapon down until you need it.",
                    "We lost contact with the next patrol. Stay alert."
            };
            default -> new String[] {
                    "Roads are rough. Watch your back.",
                    "I don't belong to any camp.",
                    "I sleep wherever the doors still lock.",
                    "You headed into town? Take the long way around.",
                    "I don't stay in one place for long.",
                    "Not everyone out here wants a fight."
            };
        };
        if (say(player, npc, lines)) NpcSounds.greeting(player, npc);
    }

    static void action(ServerPlayer player, LivingEntity npc, String action) {
        CompoundTag data = npc.getPersistentData().getCompound(KEY);
        long now = npc.level().getGameTime();
        if (data.contains("LastAction") && now >= data.getLong("LastAction")
                && now - data.getLong("LastAction") < 80) return;
        String[] lines = switch (action) {
            case "trade" -> new String[] {
                    "Fair deal. Stay sharp out there.",
                    "That'll do. Put it to good use.",
                    "We both got something we needed.",
                    "Come back if you find anything else worth trading."
            };
            case "complete" -> new String[] {
                    "You came through. We won't forget it.",
                    "That's one less thing we have to worry about.",
                    "Good work. The camp will hear about this.",
                    "You kept your word. That matters out here."
            };
            case "service" -> new String[] {
                    "All set. Take care out there.",
                    "I did what I could with what we have.",
                    "You're ready. Don't waste the second chance.",
                    "That should hold for now."
            };
            default -> new String[] {
                    "Come back alive. We need the help.",
                    "Take it slow and check every corner.",
                    "If things turn bad, get yourself out first.",
                    "We'll be here when you get back."
            };
        };
        data.putLong("LastAction", now);
        send(player, npc, nextLine(data, npc, lines));
        npc.getPersistentData().put(KEY, data);
    }

    private static boolean say(ServerPlayer player, LivingEntity npc, String[] lines) {
        CompoundTag data = npc.getPersistentData().getCompound(KEY);
        long now = npc.level().getGameTime();
        if (data.contains("LastLine") && now >= data.getLong("LastLine")
                && now - data.getLong("LastLine") < NpcConfig.DIALOGUE_COOLDOWN.get()) return false;
        data.putLong("LastLine", now);
        send(player, npc, nextLine(data, npc, lines));
        npc.getPersistentData().put(KEY, data);
        return true;
    }

    private static String nextLine(CompoundTag data, LivingEntity npc, String[] lines) {
        int sequence = data.getInt("LineSequence");
        int index = Math.floorMod((int) npc.getUUID().getLeastSignificantBits() + sequence, lines.length);
        data.putInt("LineSequence", sequence + 1);
        return lines[index];
    }

    private static void send(ServerPlayer player, LivingEntity npc, String line) {
        int color = switch (NpcIdentity.faction(npc)) {
            case "haven" -> 0x8FCB91;
            case "rovers" -> 0xE0AE67;
            case "wardens" -> 0x84B5D6;
            case "military" -> 0xB5C48B;
            default -> 0xD8C6AA;
        };
        player.sendSystemMessage(Component.literal(NpcIdentity.name(npc))
                .withStyle(style -> style.withColor(color).withBold(true))
                .append(Component.literal(": " + line).withStyle(style -> style.withColor(0xEEE7DB))));
    }
}
