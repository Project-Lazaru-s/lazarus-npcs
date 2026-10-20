package com.lazarus.npcs.api;

import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskView;
import com.lazarus.npcs.api.ai.Routine;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Public entry point of Lazarus NPCs for other mods. Version {@value #API_VERSION}.
 *
 * <p><b>Threads.</b> Registration may run during mod loading ({@code FMLCommonSetupEvent}). Every other method
 * that reads or changes the world must run on the logical server thread; otherwise it returns
 * {@link ApiResult.Status#WRONG_THREAD} (queries return empty).</p>
 *
 * <p><b>Compatibility.</b> Methods are only added within an API version; existing signatures and result statuses
 * keep their meaning. Depend on {@code lazarusnpcs} with a minimum version in {@code mods.toml} and compile with
 * {@code compileOnly}; do not embed these classes.</p>
 *
 * <p><b>Order precedence</b>, highest first: death or removal and surrender; immediate combat and self-defence;
 * a player's escort or guard contract; scenario orders; normal routine. A scenario never takes a contracted NPC
 * and never makes a hostile NPC negotiate.</p>
 */
public final class NpcApi {
    public static final int API_VERSION = 7;
    /** Outcome for {@link #finishTask}: complete and pay the rewards. */
    public static final ResourceLocation TASK_COMPLETED = new ResourceLocation("lazarusnpcs", "completed");
    /** Outcome for {@link #finishTask}: fail without rewards. Any other outcome id also fails and is reported as-is. */
    public static final ResourceLocation TASK_FAILED = new ResourceLocation("lazarusnpcs", "failed");

    private static Backend backend;

    private NpcApi() {}

    /** Identity of a human NPC, or empty for anything else. */
    public static Optional<NpcIdentityView> identity(LivingEntity npc) { return backend().identity(npc); }

    /** Assigns a registered faction to a survivor, independently of its Apocalypse Now texture variant. */
    public static ApiResult assignFaction(Mob npc, String faction) { return backend().assignFaction(npc, faction); }

    /** Stable vocation id for professional-faction survivors; empty for other NPCs. */
    public static Optional<ResourceLocation> profession(LivingEntity npc) { return backend().profession(npc); }

    /** Assigns a base vocation, or an expedition vocation with a null base id. Server thread only. */
    public static ApiResult assignProfession(Mob npc, ResourceLocation profession, @Nullable UUID baseId) {
        return backend().assignProfession(npc, profession, baseId);
    }

    /** Sets up to eight waypoints around an existing GUARD_AREA assignment; combat and contracts still win. */
    public static ApiResult setPatrolRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints) {
        return backend().setPatrolRoute(npc, owner, encounterId, waypoints);
    }

    /** Sets a finite 4-8 point route for a TRAVEL actor. Route progress is saved with the NPC. */
    public static ApiResult setTravelRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints) {
        return backend().setTravelRoute(npc, owner, encounterId, waypoints);
    }

    /** Installs a transient base routine; the owner should resend it on chunk load and renewal. */
    public static ApiResult setRoutine(Mob npc, Routine routine) { return backend().setRoutine(npc, routine); }

    /** Gives the NPC a temporary scenario order. See {@link ScenarioAssignment} for limits. */
    public static ApiResult assign(Mob npc, ScenarioAssignment assignment) { return backend().assign(npc, assignment); }

    /** Removes the order only when it belongs to this owner and encounter. */
    public static boolean clearAssignment(Mob npc, ResourceLocation owner, UUID encounterId) {
        return backend().clearAssignment(npc, owner, encounterId);
    }

    /** The NPC's current, unexpired order. */
    public static Optional<ScenarioAssignment> assignment(LivingEntity npc) { return backend().assignment(npc); }

    /** Arms a professional-faction actor already assigned to this scenario, using its faction's arsenal. */
    public static ApiResult armScenarioActor(Mob npc, ResourceLocation owner, UUID encounterId) {
        return backend().armScenarioActor(npc, owner, encounterId);
    }

    /** Current standing (-100 to 100) with a faction registered in {@link NpcFactions}. */
    public static int reputation(ServerPlayer player, String faction) { return backend().reputation(player, faction); }

    /**
     * Changes a standing for an identified reason, through the normal rules (enemy threshold, contracts, events).
     * At most 10 points per call. With an {@code encounterId}, the same reason pays only once per player and
     * encounter ({@link ApiResult.Status#DUPLICATE} afterwards).
     */
    public static ApiResult adjustReputation(ServerPlayer player, String faction, int delta, ResourceLocation reason,
                                             @Nullable UUID encounterId) {
        return backend().adjustReputation(player, faction, delta, reason, encounterId);
    }

    /** A confirmed faction action also changes each registered rival's standing by an accumulated opposite percentage. */
    public static ApiResult adjustPoliticalReputation(ServerPlayer player, String faction, int delta,
                                                       ResourceLocation reason, @Nullable UUID actionId) {
        return backend().adjustPoliticalReputation(player, faction, delta, reason, actionId);
    }

    /** Whether two known factions are political rivals. All other pairs are neutral. */
    public static boolean areRivals(String first, String second) { return backend().areRivals(first, second); }

    /**
     * Makes a looter or rogue soldier group (members within 16 blocks of {@code hostile}) leave this player alone
     * for 20 to 6 000 ticks, exactly like paying their toll. Cannibals never accept it. The group's own warning
     * and negotiation keep working for everyone else.
     */
    public static ApiResult grantPassage(ServerPlayer player, LivingEntity hostile, long ticks, ResourceLocation reason) {
        return backend().grantPassage(player, hostile, ticks, reason);
    }

    /** Registers a dialogue. Duplicate ids return {@link ApiResult.Status#DUPLICATE}. */
    public static ApiResult registerDialogue(DialogueDefinition definition) { return backend().registerDialogue(definition); }

    /** Registers an encounter task type. Duplicate ids return {@link ApiResult.Status#DUPLICATE}. */
    public static ApiResult registerTask(TaskDefinition definition) { return backend().registerTask(definition); }

    /** Shows the best dialogue for this trigger, if any provider answers. */
    public static ApiResult openDialogue(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger) {
        return backend().openDialogue(player, npc, trigger);
    }

    /** Opens the ordinary NPC interface (trade, services and tasks) after an integrator dialogue. */
    public static ApiResult openNpcMenu(ServerPlayer player, LivingEntity npc) {
        return backend().openNpcMenu(player, npc);
    }

    /**
     * Offers an encounter task: the player sees title, description and rewards with Accept and Decline.
     * A player has at most one open encounter task besides their normal NPC task; a second offer returns
     * {@link ApiResult.Status#BUSY} until the first is finished or abandoned from the journal.
     *
     * @param destination where reach and escort objectives point, or null
     * @return OK with the new instance id
     */
    public static ApiResult offerTask(ServerPlayer player, LivingEntity giver, ResourceLocation taskId, UUID encounterId,
                                      @Nullable GlobalPos destination) {
        return backend().offerTask(player, giver, taskId, encounterId, destination);
    }

    /** Adds to a task counter (1 to 1000) for objectives driven by the integrator. */
    public static ApiResult progressTask(ServerPlayer player, UUID instanceId, String key, int amount) {
        return backend().progressTask(player, instanceId, key, amount);
    }

    /** Ends an active task with {@link #TASK_COMPLETED} (pays rewards once) or a failure outcome. */
    public static ApiResult finishTask(ServerPlayer player, UUID instanceId, ResourceLocation outcome) {
        return backend().finishTask(player, instanceId, outcome);
    }

    /** The player's open or most recently finished encounter task. */
    public static Optional<TaskView> encounterTask(ServerPlayer player) { return backend().encounterTask(player); }

    /** Implemented by Lazarus NPCs. Not for other mods. */
    public interface Backend {
        Optional<NpcIdentityView> identity(LivingEntity npc);
        ApiResult assignFaction(Mob npc, String faction);
        Optional<ResourceLocation> profession(LivingEntity npc);
        ApiResult assignProfession(Mob npc, ResourceLocation profession, @Nullable UUID baseId);
        ApiResult setPatrolRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints);
        ApiResult setTravelRoute(Mob npc, ResourceLocation owner, UUID encounterId, List<BlockPos> waypoints);
        ApiResult setRoutine(Mob npc, Routine routine);
        ApiResult assign(Mob npc, ScenarioAssignment assignment);
        boolean clearAssignment(Mob npc, ResourceLocation owner, UUID encounterId);
        Optional<ScenarioAssignment> assignment(LivingEntity npc);
        ApiResult armScenarioActor(Mob npc, ResourceLocation owner, UUID encounterId);
        int reputation(ServerPlayer player, String faction);
        ApiResult adjustReputation(ServerPlayer player, String faction, int delta, ResourceLocation reason, @Nullable UUID encounterId);
        ApiResult adjustPoliticalReputation(ServerPlayer player, String faction, int delta, ResourceLocation reason,
                                            @Nullable UUID actionId);
        boolean areRivals(String first, String second);
        ApiResult grantPassage(ServerPlayer player, LivingEntity hostile, long ticks, ResourceLocation reason);
        ApiResult registerDialogue(DialogueDefinition definition);
        ApiResult registerTask(TaskDefinition definition);
        ApiResult openDialogue(ServerPlayer player, LivingEntity npc, DialogueTrigger trigger);
        ApiResult openNpcMenu(ServerPlayer player, LivingEntity npc);
        ApiResult offerTask(ServerPlayer player, LivingEntity giver, ResourceLocation taskId, UUID encounterId, @Nullable GlobalPos destination);
        ApiResult progressTask(ServerPlayer player, UUID instanceId, String key, int amount);
        ApiResult finishTask(ServerPlayer player, UUID instanceId, ResourceLocation outcome);
        Optional<TaskView> encounterTask(ServerPlayer player);
    }

    /** Called once by Lazarus NPCs during construction. */
    public static void install(Backend implementation) {
        if (backend != null) throw new IllegalStateException("Lazarus NPCs API backend already installed");
        backend = implementation;
    }

    private static Backend backend() {
        if (backend == null) throw new IllegalStateException("Lazarus NPCs is not loaded");
        return backend;
    }
}
