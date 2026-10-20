package com.lazarus.npcs;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcIdentityView;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ai.Routine;
import com.lazarus.npcs.ai.RoutineGoal;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskView;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Implementation behind {@link NpcApi}: validation and threading here, behaviour in the feature classes. */
final class ApiBackend implements NpcApi.Backend {
    static final int MAX_REPUTATION_PER_CALL = 10;
    private static final String GRANTS = "LazarusNpcsApiGrants";
    private static int depth;

    /** Posts an API event; nested posts deeper than four are dropped so handlers cannot loop forever. */
    static void post(Event event) {
        if (depth >= 4) {
            LazarusNpcs.LOGGER.warn("[api] dropped nested {} (event handlers are re-triggering each other)",
                    event.getClass().getSimpleName());
            return;
        }
        depth++;
        try {
            MinecraftForge.EVENT_BUS.post(event);
        } finally {
            depth--;
        }
    }

    static boolean serverThread(Entity entity) {
        return entity != null && !entity.level().isClientSide() && entity.getServer() != null && entity.getServer().isSameThread();
    }

    static ApiResult wrongThread() {
        return ApiResult.fail(ApiResult.Status.WRONG_THREAD, "call this on the logical server thread");
    }

    @Override
    public Optional<NpcIdentityView> identity(LivingEntity npc) {
        if (!serverThread(npc)) return Optional.empty();
        String role = NpcIdentity.role(npc);
        if (role != null)
            return Optional.of(new NpcIdentityView(npc.getId(), npc.getUUID(), NpcIdentity.name(npc), role,
                    NpcIdentity.faction(npc), npc.isAlive() && !HumanSurrender.surrendered(npc)));
        String kind = HumanEncounters.kind(npc);
        if (kind == null) return Optional.empty();
        return Optional.of(new NpcIdentityView(npc.getId(), npc.getUUID(), HumanEncounters.label(kind).replaceAll("s$", ""),
                kind, "hostile", npc.isAlive() && !"cannibal".equals(kind)));
    }

    @Override
    public ApiResult assignFaction(Mob npc, String faction) {
        return serverThread(npc) ? NpcIdentity.assignFaction(npc, faction) : wrongThread();
    }

    @Override
    public Optional<ResourceLocation> profession(LivingEntity npc) {
        if (!serverThread(npc)) return Optional.empty();
        String id = NpcProfessions.get(npc);
        return id.isEmpty() ? Optional.empty() : Optional.of(new ResourceLocation(LazarusNpcs.ID, id));
    }

    @Override
    public ApiResult assignProfession(Mob npc, ResourceLocation profession, UUID baseId) {
        return serverThread(npc) ? NpcProfessions.assign(npc, profession, baseId) : wrongThread();
    }

    @Override
    public ApiResult setPatrolRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints) {
        return serverThread(npc) ? NpcScenarios.setPatrolRoute(npc, owner, encounterId, waypoints) : wrongThread();
    }

    @Override
    public ApiResult setTravelRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints) {
        return serverThread(npc) ? NpcScenarios.setTravelRoute(npc, owner, encounterId, waypoints) : wrongThread();
    }

    @Override
    public ApiResult setRoutine(Mob npc, Routine routine) {
        if (!serverThread(npc)) return wrongThread();
        if (!NpcScenarios.supported(npc))
            return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "human NPC required");
        if (routine == null || routine.activities().stream().anyMatch(activity ->
                !npc.level().isLoaded(activity.at()) || activity.at().distSqr(npc.blockPosition()) > 64 * 64))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "routine points must be loaded and nearby");
        RoutineGoal.assign(npc, routine);
        return ApiResult.ok("routine assigned");
    }

    @Override
    public ApiResult assign(Mob npc, ScenarioAssignment assignment) {
        return serverThread(npc) ? NpcScenarios.assign(npc, assignment) : wrongThread();
    }

    @Override
    public boolean clearAssignment(Mob npc, ResourceLocation owner, UUID encounterId) {
        return serverThread(npc) && NpcScenarios.clear(npc, owner, encounterId);
    }

    @Override
    public Optional<ScenarioAssignment> assignment(LivingEntity npc) {
        return serverThread(npc) ? NpcScenarios.current(npc) : Optional.empty();
    }

    @Override
    public ApiResult armScenarioActor(Mob npc, ResourceLocation owner, UUID encounterId) {
        if (!serverThread(npc)) return wrongThread();
        if (owner == null || encounterId == null || !NpcScenarios.current(npc)
                .filter(a -> owner.equals(a.owner()) && encounterId.equals(a.encounterId())).isPresent())
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "actor is not assigned to this scenario");
        String faction = NpcIdentity.faction(npc);
        if (NpcFactions.get(faction) == null || !NpcFactions.get(faction).professions())
            return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "professional faction survivor required");
        return NpcGuns.armScenario(npc) ? ApiResult.ok("scenario actor armed")
                : ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "no compatible faction weapon is loaded");
    }

    @Override
    public int reputation(ServerPlayer player, String faction) {
        return serverThread(player) ? NpcReputation.get(player, faction) : 0;
    }

    @Override
    public ApiResult adjustReputation(ServerPlayer player, String faction, int delta, ResourceLocation reason, UUID encounterId) {
        return adjustReputation(player, faction, delta, reason, encounterId, false);
    }

    @Override
    public ApiResult adjustPoliticalReputation(ServerPlayer player, String faction, int delta,
                                               ResourceLocation reason, UUID actionId) {
        return adjustReputation(player, faction, delta, reason, actionId, true);
    }

    @Override
    public boolean areRivals(String first, String second) {
        return FactionRelations.rivals(first, second);
    }

    private ApiResult adjustReputation(ServerPlayer player, String faction, int delta, ResourceLocation reason,
                                       UUID encounterId, boolean political) {
        if (!serverThread(player)) return wrongThread();
        if ("independent".equals(faction) || !Arrays.asList(NpcIdentity.FACTIONS).contains(faction))
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "unknown faction " + faction);
        if (reason == null) return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "a reason id is required");
        if (Math.abs(delta) > MAX_REPUTATION_PER_CALL)
            return ApiResult.fail(ApiResult.Status.LIMIT, "at most " + MAX_REPUTATION_PER_CALL + " points per call");
        if (encounterId != null) {
            CompoundTag grants = player.getPersistentData().getCompound(GRANTS);
            String key = reason + "|" + encounterId;
            if (grants.contains(key)) return ApiResult.fail(ApiResult.Status.DUPLICATE, "already granted for this encounter");
            grants.putLong(key, player.serverLevel().getGameTime());
            pruneGrants(grants, player.serverLevel().getGameTime());
            player.getPersistentData().put(GRANTS, grants);
        }
        int updated = political ? NpcReputation.adjustPolitical(player, faction, delta, reason)
                : NpcReputation.adjust(player, faction, delta, reason);
        LazarusNpcs.LOGGER.info("[api] reputation {} {} {} by {} (encounter {}) -> {}", player.getGameProfile().getName(),
                faction, delta, reason, encounterId, updated);
        return ApiResult.ok("reputation changed", updated, null);
    }

    /** Grants older than a week of world time cannot be repeated by any live encounter; drop them. */
    private static void pruneGrants(CompoundTag grants, long now) {
        grants.getAllKeys().removeIf(key -> now - grants.getLong(key) > 168_000L);
    }

    @Override
    public ApiResult grantPassage(ServerPlayer player, LivingEntity hostile, long ticks, ResourceLocation reason) {
        if (!serverThread(player)) return wrongThread();
        String kind = HumanEncounters.kind(hostile);
        if (!"looter".equals(kind) && !"soldier".equals(kind))
            return ApiResult.fail(ApiResult.Status.UNSUPPORTED_NPC, "only looters and rogue soldiers grant passage");
        if (!hostile.isAlive() || hostile.level() != player.level() || reason == null)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "living NPC in the player's dimension and a reason are required");
        if (ticks < 20 || ticks > 6_000) return ApiResult.fail(ApiResult.Status.LIMIT, "passage lasts 20-6000 ticks");
        HumanEncounters.grantPassage(player, hostile, kind, ticks);
        LazarusNpcs.LOGGER.info("[api] passage {} for {} ticks by {}", player.getGameProfile().getName(), ticks, reason);
        return ApiResult.ok("passage granted");
    }

    @Override
    public ApiResult registerDialogue(DialogueDefinition definition) { return NpcDialogues.register(definition); }

    @Override
    public ApiResult registerTask(TaskDefinition definition) { return EncounterTasks.register(definition); }

    @Override
    public ApiResult openDialogue(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger) {
        if (!serverThread(player)) return wrongThread();
        return NpcDialogues.open(player, npc, trigger, null) ? ApiResult.ok("dialogue opened")
                : ApiResult.fail(ApiResult.Status.MISSING_DEFINITION, "no dialogue answers this trigger here");
    }

    @Override
    public ApiResult openNpcMenu(ServerPlayer player, LivingEntity npc) {
        if (!serverThread(player)) return wrongThread();
        if (npc == null || NpcIdentity.role(npc) == null || !npc.isAlive() || npc.level() != player.level()
                || npc.distanceToSqr(player) > 64 || NpcReputation.get(player, NpcIdentity.faction(npc)) <= -20)
            return ApiResult.fail(ApiResult.Status.INVALID_CONTEXT, "NPC menu is unavailable");
        NpcNetwork.openNpc(player, npc);
        return ApiResult.ok("NPC menu opened");
    }

    @Override
    public ApiResult offerTask(ServerPlayer player, LivingEntity giver, ResourceLocation taskId, UUID encounterId, GlobalPos destination) {
        return serverThread(player) ? EncounterTasks.offer(player, giver, taskId, encounterId, destination) : wrongThread();
    }

    @Override
    public ApiResult progressTask(ServerPlayer player, UUID instanceId, String key, int amount) {
        return serverThread(player) ? EncounterTasks.progress(player, instanceId, key, amount) : wrongThread();
    }

    @Override
    public ApiResult finishTask(ServerPlayer player, UUID instanceId, ResourceLocation outcome) {
        return serverThread(player) ? EncounterTasks.finish(player, instanceId, outcome) : wrongThread();
    }

    @Override
    public Optional<TaskView> encounterTask(ServerPlayer player) {
        return serverThread(player) ? EncounterTasks.view(player) : Optional.empty();
    }
}
