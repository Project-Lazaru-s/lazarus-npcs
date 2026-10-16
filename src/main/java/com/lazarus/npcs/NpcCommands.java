package com.lazarus.npcs;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class NpcCommands {
    private static final Map<String, String> TYPES = types();

    private NpcCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lazarusnpcs")
                .then(Commands.literal("reputation")
                        .executes(context -> reputation(context.getSource()))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(NpcIdentity.FACTIONS, builder))
                                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-100, 100))
                                                .executes(context -> {
                                                    String faction = StringArgumentType.getString(context, "faction");
                                                    if (!java.util.Arrays.asList(NpcIdentity.FACTIONS).contains(faction)) {
                                                        context.getSource().sendFailure(Component.literal("Unknown faction: " + faction));
                                                        return 0;
                                                    }
                                                    NpcReputation.set(context.getSource().getPlayerOrException(), faction,
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value"));
                                                    return reputation(context.getSource());
                                                })))))
                .then(Commands.literal("task")
                        .executes(context -> { NpcTasks.status(context.getSource().getPlayerOrException()); return 1; })
                        .then(Commands.literal("abandon")
                                .executes(context -> { NpcTasks.abandon(context.getSource().getPlayerOrException()); return 1; }))
                        .then(Commands.literal("reset")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> { NpcTasks.resetForTesting(context.getSource().getPlayerOrException()); return 1; })))
                .then(Commands.literal("contracts")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            context.getSource().sendSuccess(() -> Component.literal(NpcContracts.playerSummary(player)), false);
                            return 1;
                        })
                        .then(Commands.literal("release")
                                .executes(context -> { NpcContracts.releaseAll(context.getSource().getPlayerOrException()); return 1; })))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            var store = NpcContracts.store(context.getSource().getServer());
                            String text = "Tracked hostile/hunter NPCs: " + HumanTactics.tracked() + ", contracts: "
                                    + store.byNpc.size() + " (guards " + store.guards(null) + "/" + NpcContracts.GUARDS_PER_SERVER
                                    + ")\n" + NpcPerf.report();
                            context.getSource().sendSuccess(() -> Component.literal(text), false);
                            return 1;
                        })
                        .then(Commands.literal("reset")
                                .executes(context -> { NpcPerf.reset(); return 1; })))
                .then(Commands.literal("services")
                        .executes(context -> { NpcServices.show(context.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("service")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        java.util.List.of("repair", "stabilize", "escort", "scout"), builder))
                                .executes(context -> {
                                    NpcServices.use(context.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(context, "name"));
                                    return 1;
                                })))
                .then(Commands.literal("spawn")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TYPES.keySet(), builder))
                                .executes(context -> spawn(context, defaultPosition(context.getSource())))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> spawn(context, BlockPos.containing(Vec3Argument.getVec3(context, "pos"))))))));
    }

    private static int reputation(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StringBuilder summary = new StringBuilder("NPC reputation");
        for (String faction : NpcIdentity.FACTIONS)
            summary.append(" | ").append(NpcIdentity.label(faction)).append(": ")
                    .append(NpcReputation.get(player, faction)).append("/100");
        source.sendSuccess(() -> Component.literal(summary.toString()), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        String choice = StringArgumentType.getString(context, "type").toLowerCase(java.util.Locale.ROOT);
        String path = TYPES.get(choice);
        if (path == null) {
            source.sendFailure(Component.literal("Unknown NPC type: " + choice + ". Use Tab to see the available types."));
            return 0;
        }

        ResourceLocation id = new ResourceLocation("apocalypsenow", path);
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            source.sendFailure(Component.literal("NPC type is missing from Apocalypse Now: " + id));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Entity entity = type.spawn(level, pos, MobSpawnType.COMMAND);
        if (entity == null) {
            source.sendFailure(Component.literal("Could not spawn " + id + " at " + pos.toShortString()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawned " + id + " at " + pos.toShortString()), true);
        return 1;
    }

    private static BlockPos defaultPosition(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.blockPosition().relative(player.getDirection(), 3);
        }
        return BlockPos.containing(source.getPosition());
    }

    private static Map<String, String> types() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("survivor", "survivor_1");
        for (int i = 1; i <= 10; i++) result.put("survivor_" + i, "survivor_" + i);
        result.put("military", "military_01");
        result.put("military_01", "military_01");
        result.put("military_02", "military_02");
        result.put("medic", "military_medic");
        result.put("military_medic", "military_medic");
        result.put("soldier", "soldiers");
        result.put("soldiers", "soldiers");
        result.put("soldiers_02", "soldiers_02");
        result.put("looter", "looter_1");
        for (int i = 1; i <= 5; i++) result.put("looter_" + i, "looter_" + i);
        result.put("hunter", "hunter");
        result.put("cannibal", "cannibal");
        return Map.copyOf(result);
    }
}
