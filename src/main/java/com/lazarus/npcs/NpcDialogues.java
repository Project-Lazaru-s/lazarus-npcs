package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Registered dialogues with choices. One open conversation per player; a choice is accepted once, only with the
 * right token, within a minute, near and in sight of a living NPC, and only if the option is still offered.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcDialogues {
    static final double MAX_DISTANCE = 8;
    private static final long SESSION_TICKS = 1_200L;
    private static final Map<ResourceLocation, DialogueDefinition> REGISTRY = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    record Session(UUID npc, ResourceLocation dialogue, DialogueTrigger trigger, UUID taskId, List<String> options,
                   long token, long createdAt) {}

    private record Selection(DialogueDefinition definition, DialoguePage page) {}

    private NpcDialogues() {}

    static ApiResult register(DialogueDefinition definition) {
        if (definition == null || definition.id() == null || definition.provider() == null
                || definition.triggers() == null || definition.triggers().isEmpty())
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "id, triggers and provider are required");
        if (REGISTRY.putIfAbsent(definition.id(), definition) != null)
            return ApiResult.fail(ApiResult.Status.DUPLICATE, "dialogue " + definition.id() + " is already registered");
        return ApiResult.ok("dialogue registered");
    }

    static DialogueContext context(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger, UUID taskId) {
        UUID encounter = NpcScenarios.current(npc).map(ScenarioAssignment::encounterId).orElse(null);
        return new DialogueContext(player, npc, trigger, encounter, taskId);
    }

    private static Optional<Selection> select(DialogueContext context) {
        List<DialogueDefinition> candidates = REGISTRY.values().stream()
                .filter(definition -> definition.triggers().contains(context.trigger()))
                .sorted(Comparator.comparingInt(DialogueDefinition::priority).reversed()).toList();
        for (DialogueDefinition definition : candidates) {
            Optional<DialoguePage> page = ask(definition, context);
            if (page.isPresent()) return Optional.of(new Selection(definition, page.get()));
        }
        return Optional.empty();
    }

    /** Runs a provider defensively: a broken or oversized page is skipped and logged, never shown. */
    private static Optional<DialoguePage> ask(DialogueDefinition definition, DialogueContext context) {
        try {
            Optional<DialoguePage> page = definition.provider().apply(context);
            if (page == null || page.isEmpty()) return Optional.empty();
            List<DialogueOption> options = page.get().options();
            if (page.get().line() == null || options == null || options.isEmpty() || options.size() > DialoguePage.MAX_OPTIONS) {
                LazarusNpcs.LOGGER.warn("[api] dialogue {} returned an invalid page (1-{} options required)", definition.id(), DialoguePage.MAX_OPTIONS);
                return Optional.empty();
            }
            var ids = new HashSet<String>();
            for (DialogueOption option : options)
                if (option == null || option.id() == null || option.id().isEmpty() || option.id().length() > 32
                        || option.label() == null || option.action() == null || !ids.add(option.id())) {
                    LazarusNpcs.LOGGER.warn("[api] dialogue {} has a malformed or duplicate option", definition.id());
                    return Optional.empty();
                }
            return page;
        } catch (RuntimeException failure) {
            LazarusNpcs.LOGGER.error("[api] dialogue {} failed to build a page", definition.id(), failure);
            return Optional.empty();
        }
    }

    static boolean reachable(ServerPlayer player, LivingEntity npc) {
        return npc.isAlive() && npc.level() == player.level() && npc.distanceTo(player) <= MAX_DISTANCE && player.hasLineOfSight(npc);
    }

    /** Shows the best page for this trigger; false (nothing shown) when no provider answers. */
    static boolean open(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger, UUID taskId) {
        if (!reachable(player, npc) || npc instanceof Mob mob && mob.getTarget() == player) return false;
        Optional<Selection> selection = select(context(player, npc, trigger, taskId));
        if (selection.isEmpty()) return false;
        DialoguePage page = selection.get().page();
        long token = ThreadLocalRandom.current().nextLong();
        SESSIONS.put(player.getUUID(), new Session(npc.getUUID(), selection.get().definition().id(), trigger, taskId,
                page.options().stream().map(DialogueOption::id).toList(), token, player.serverLevel().getGameTime()));
        // Components travel as JSON so translatable keys resolve in the client's language.
        Component line = page.line();
        if (line.getString().length() > DialoguePage.MAX_LINE)
            line = Component.literal(line.getString().substring(0, DialoguePage.MAX_LINE - 3) + "...");
        List<String> labels = new ArrayList<>();
        for (DialogueOption option : page.options()) labels.add(json(option.label(), 40));
        NpcNetwork.sendDialogue(player, npc, json(line, DialoguePage.MAX_LINE), labels, token);
        return true;
    }

    /** JSON for the wire; falls back to truncated plain text when the styled form would exceed the packet limit. */
    private static String json(Component component, int maxChars) {
        String json = Component.Serializer.toJson(component);
        if (json.length() <= NpcNetwork.MAX_COMPONENT_JSON) return json;
        String text = component.getString();
        return Component.Serializer.toJson(Component.literal(text.length() > maxChars ? text.substring(0, maxChars) : text));
    }

    /** Handles the client's pick: index -1 is "Back". */
    static void choose(ServerPlayer player, UUID npcId, long token, int index) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.token() != token || !session.npc().equals(npcId)) return; // stale or forged
        SESSIONS.remove(player.getUUID()); // consumed before anything runs: a repeated packet does nothing
        Entity entity = player.serverLevel().getEntity(npcId);
        if (!(entity instanceof LivingEntity npc)) {
            NpcNetwork.closeDialogue(player);
            return;
        }
        if (index < 0) {
            NpcNetwork.closeDialogue(player);
            if (NpcIdentity.role(npc) != null && reachable(player, npc)) NpcNetwork.openNpc(player, npc);
            return;
        }
        if (index >= session.options().size() || player.serverLevel().getGameTime() - session.createdAt() > SESSION_TICKS
                || !reachable(player, npc)) {
            player.displayClientMessage(Component.literal("That conversation is over."), true);
            NpcNetwork.closeDialogue(player);
            return;
        }
        DialogueDefinition definition = REGISTRY.get(session.dialogue());
        DialogueContext context = context(player, npc, session.trigger(), session.taskId());
        String optionId = session.options().get(index);
        Optional<DialogueOption> option = definition == null ? Optional.empty() : ask(definition, context)
                .flatMap(page -> page.options().stream().filter(o -> o.id().equals(optionId)).findFirst());
        if (option.isEmpty()) {
            player.displayClientMessage(Component.literal("That option is no longer available."), true);
            NpcNetwork.closeDialogue(player);
            return;
        }
        try {
            option.get().action().accept(context);
        } catch (RuntimeException failure) {
            LazarusNpcs.LOGGER.error("[api] dialogue {} option {} failed", definition.id(), optionId, failure);
        }
        ApiBackend.post(new NpcApiEvents.DialogueChoice(player, npc, definition.id(), optionId, context.encounterId()));
        if (!SESSIONS.containsKey(player.getUUID())) NpcNetwork.closeDialogue(player); // the action opened nothing new
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { SESSIONS.remove(event.getEntity().getUUID()); }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { SESSIONS.clear(); }

    /** Test hook: the open session for a player, if any. */
    static Session session(ServerPlayer player) { return SESSIONS.get(player.getUUID()); }
}
