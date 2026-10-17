package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcTasks {
    private static final String DATA_KEY = "LazarusNpcsTasks";
    private static final String ACTIVE = "Active";
    private static final long EXPIRE_TICKS = 48_000L;
    private static final long COOLDOWN_TICKS = 6_000L;
    private static final int PATROL_RADIUS = 64;

    private NpcTasks() {}

    static void interact(ServerPlayer player, LivingEntity npc, String role) {
        String faction = NpcIdentity.faction(npc);
        long now = time(player);
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        CompoundTag active = active(data, now);
        if (!active.isEmpty()) {
            String activeRole = active.getString("Role");
            if (!activeRole.equals(role) || (active.contains("Faction") && !active.getString("Faction").equals(faction))) {
                say(player, "You have an active task for a " + activeRole + ". Use /lazarusnpcs task.");
                save(player, data);
                return;
            }
            if (!ready(active)) {
                say(player, description(active) + " Use /lazarusnpcs task for progress.");
                save(player, data);
                return;
            }

            String kind = active.getString("Kind");
            if (!consumeRequiredItems(player, kind)) {
                say(player, "You need the requested supplies in your inventory.");
                save(player, data);
                return;
            }
            giveReward(player, kind);
            int gained = reputationReward(kind, faction);
            String credited = active.contains("Faction") ? active.getString("Faction") : faction;
            int reputation = NpcReputation.adjust(player, credited, gained);
            NpcReputation.taskRivalry(player, credited);
            data.remove(ACTIVE);
            data.putLong("Completed_" + faction, now);
            save(player, data);
            say(player, "Task complete. Received " + rewardDescription(kind) + "."
                    + (gained == 0 ? "" : " Reputation +" + gained + " (now " + reputation + ")."));
            NpcDialogue.action(player, npc, "complete");
            return;
        }

        String cooldownKey = data.contains("Completed_" + faction) ? "Completed_" + faction : "Completed_" + role;
        long completed = data.getLong(cooldownKey);
        if (data.contains(cooldownKey) && now - completed < COOLDOWN_TICKS && now >= completed) {
            long minutes = (COOLDOWN_TICKS - (now - completed) + 1199) / 1200;
            say(player, "This group has no new task for you yet. Try again in about " + minutes + " minutes.");
            save(player, data);
            return;
        }

        String kind = nextKind(player, npc, data);
        data.putInt("Assigned_" + faction, data.getInt("Assigned_" + faction) + 1);
        CompoundTag task = new CompoundTag();
        task.putString("Kind", kind);
        task.putString("Role", role);
        task.putString("Faction", faction);
        task.putLong("StartedAt", now);
        task.putInt("Progress", 0);
        task.putString("Dimension", npc.level().dimension().location().toString());
        task.putInt("X", npc.blockPosition().getX());
        task.putInt("Y", npc.blockPosition().getY());
        task.putInt("Z", npc.blockPosition().getZ());
        task.putString("Giver", NpcIdentity.name(npc));
        task.putUUID("GiverId", npc.getUUID());
        data.put(ACTIVE, task);
        save(player, data);
        say(player, "New encounter: " + description(task) + " Sneak and interact with any " + role
                + " NPC to turn it in. Expires after 40 minutes of active play.");
        NpcDialogue.action(player, npc, "task");
    }

    static void status(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        CompoundTag task = active(data, time(player));
        save(player, data);
        say(player, task.isEmpty() ? "No active NPC task. Sneak and interact with a friendly NPC to get one."
                : description(task) + " " + location(player).replace("\n", ", ") + ".");
    }

    static String summary(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        CompoundTag task = active(data, time(player));
        save(player, data);
        if (task.isEmpty()) return "No active task";
        long remaining = Math.max(0, (EXPIRE_TICKS - (time(player) - task.getLong("StartedAt"))) / 1200);
        String owner = task.contains("Faction") ? NpcIdentity.label(task.getString("Faction")) : task.getString("Role");
        return owner + ": " + objective(task) + ". Reward: " + rewardDescription(task.getString("Kind"))
                + reputationLabel(task.getString("Kind"), task.getString("Faction"))
                + ". " + remaining + "m left";
    }

    /** Where the active task was taken, for the journal: giver, coordinates, and distance and direction from here. */
    static String location(ServerPlayer player) {
        CompoundTag task = player.getPersistentData().getCompound(DATA_KEY).getCompound(ACTIVE);
        if (task.isEmpty() || !task.contains("X")) return "";
        int x = task.getInt("X"), y = task.getInt("Y"), z = task.getInt("Z");
        String dimension = task.getString("Dimension");
        String giver = task.getString("Giver");
        String owner = task.contains("Faction") && !"independent".equals(task.getString("Faction"))
                ? " (" + NpcIdentity.label(task.getString("Faction")) + ")" : "";
        String first = (giver.isEmpty() ? "Task giver" : giver + owner) + ": " + x + ", " + y + ", " + z;
        CompoundTag death = task.hasUUID("GiverId") ? NpcDeaths.death(player.getServer(), task.getUUID("GiverId")) : null;
        if (death != null) {
            String cause = death.getString("Cause");
            return first + "\n" + Character.toUpperCase(cause.charAt(0)) + cause.substring(1);
        }
        if (!dimension.equals(player.level().dimension().location().toString()))
            return first + "\nIn " + dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
        double dx = x + 0.5 - player.getX(), dz = z + 0.5 - player.getZ();
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        return first + "\n" + (distance < 8 ? "You are here" : distance + " blocks " + NpcServices.direction(dx, dz));
    }

    static java.util.UUID giverId(ServerPlayer player) {
        CompoundTag task = player.getPersistentData().getCompound(DATA_KEY).getCompound(ACTIVE);
        return task.hasUUID("GiverId") ? task.getUUID("GiverId") : null;
    }

    /** Who else accepts the active task, for the death notice. */
    static String turnInHint(ServerPlayer player) {
        CompoundTag task = player.getPersistentData().getCompound(DATA_KEY).getCompound(ACTIVE);
        String faction = task.getString("Faction");
        String group = faction.isEmpty() || "independent".equals(faction) || "military".equals(faction)
                ? "" : NpcIdentity.label(faction) + " ";
        String role = "independent".equals(faction) ? "independent survivor" : task.getString("Role");
        return "The task can still be turned in to any other " + group + role + ".";
    }

    static String preview(ServerPlayer player, LivingEntity npc) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        if (!active(data, time(player)).isEmpty()) {
            save(player, data);
            return summary(player);
        }
        String role = NpcIdentity.role(npc);
        String faction = NpcIdentity.faction(npc);
        String key = data.contains("Completed_" + faction) ? "Completed_" + faction : "Completed_" + role;
        if (data.contains(key) && time(player) >= data.getLong(key)
                && time(player) - data.getLong(key) < COOLDOWN_TICKS)
            return "This group has no task available yet.";
        CompoundTag offered = new CompoundTag();
        offered.putString("Kind", nextKind(player, npc, data));
        String kind = offered.getString("Kind");
        return "Objective: " + objective(offered) + ". Reward: " + rewardDescription(kind)
                + reputationLabel(kind, faction) + ".";
    }

    static void abandon(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        if (!data.contains(ACTIVE)) {
            say(player, "No active NPC task.");
            return;
        }
        data.remove(ACTIVE);
        save(player, data);
        say(player, "NPC task abandoned.");
    }

    static void resetForTesting(ServerPlayer player) {
        player.getPersistentData().remove(DATA_KEY);
        say(player, "NPC tasks and their cooldowns reset for testing.");
    }

    private static boolean ready(CompoundTag task) {
        String kind = task.getString("Kind");
        return killGoal(kind) == 0 || task.getInt("Progress") >= killGoal(kind);
    }

    private static String nextKind(ServerPlayer player, LivingEntity npc, CompoundTag data) {
        String faction = NpcIdentity.faction(npc);
        String[] choices = switch (NpcIdentity.role(npc)) {
            case "medic" -> new String[]{"medical", "sanitation", "antiseptic"};
            case "military" -> new String[]{"perimeter", "radio", "armory", "sweep"};
            case "hunter" -> new String[]{"trail", "provisions", "salvage"};
            default -> switch (faction) {
                case "haven" -> new String[]{"food", "water", "cloth", "watch"};
                case "rovers" -> new String[]{"food", "water", "parts", "salvage"};
                case "wardens" -> new String[]{"water", "cloth", "watch", "salvage"};
                default -> new String[]{"food", "water", "cloth", "parts"};
            };
        };
        int offset = (int) (npc.getUUID().getLeastSignificantBits()
                ^ player.getUUID().getMostSignificantBits());
        return choices[Math.floorMod(offset + data.getInt("Assigned_" + faction), choices.length)];
    }

    private static int killGoal(String kind) {
        return switch (kind) {
            case "watch" -> 3;
            case "trail" -> 4;
            case "perimeter" -> 5;
            case "sweep" -> 8;
            default -> 0;
        };
    }

    private static int reputationReward(String kind, String faction) {
        if ("independent".equals(faction)) return 0;
        return killGoal(kind) > 0 || "medical".equals(kind) || "antiseptic".equals(kind) ? 4 : 3;
    }

    private static String reputationLabel(String kind, String faction) {
        int gained = reputationReward(kind, faction);
        return gained == 0 ? "" : ", +" + gained + " reputation";
    }

    private static String objective(CompoundTag task) {
        String kind = task.getString("Kind");
        return switch (kind) {
            case "food" -> "Bring 3 canned food";
            case "water" -> "Bring 3 canned water";
            case "cloth" -> "Bring 5 cloth";
            case "parts" -> "Bring 5 copper ingots";
            case "salvage" -> "Bring 1 broken radio";
            case "medical" -> "Bring 2 bandages";
            case "sanitation" -> "Bring 3 clean rags";
            case "antiseptic" -> "Bring 2 alcohol";
            case "radio" -> "Bring 1 broken military radio";
            case "armory" -> "Bring 8 iron ingots";
            case "provisions" -> "Bring 3 cooked beef";
            case "watch", "trail", "perimeter", "sweep" -> "Kill infected near NPC: "
                    + task.getInt("Progress") + "/" + killGoal(kind);
            default -> "Unknown task";
        };
    }

    private static String description(CompoundTag task) {
        String location = killGoal(task.getString("Kind")) > 0
                ? " Within 64 blocks of " + task.getInt("X") + ", " + task.getInt("Y")
                    + ", " + task.getInt("Z") + "." : ".";
        String kind = task.getString("Kind");
        return "Objective: " + objective(task) + location + " Reward: " + rewardDescription(kind)
                + reputationLabel(kind, task.getString("Faction")) + ".";
    }

    private static String rewardDescription(String kind) {
        return switch (kind) {
            case "cloth", "watch" -> "2 canned food + 1 bandage";
            case "parts" -> "1 survival knife + 1 canned water";
            case "salvage" -> "2 canned food + 2 bandages";
            case "medical", "sanitation" -> "1 medical kit";
            case "antiseptic" -> "2 bandages + 1 morphine";
            case "perimeter" -> "2 canned food + 1 bandage";
            case "radio" -> "16x 9mm ammo + 1 canned food";
            case "armory" -> "24x 9mm ammo + 2 canned food";
            case "sweep" -> "16x 7.62 ammo + 2 bandages";
            case "trail" -> "1 bushcraft knife + 2 bandages";
            case "provisions" -> "2 canned food + 1 canned water";
            default -> "2 bandages";
        };
    }

    private static boolean consumeRequiredItems(ServerPlayer player, String kind) {
        String id = switch (kind) {
            case "food" -> "apocalypsenow:canned_food";
            case "water" -> "apocalypsenow:canned_water";
            case "medical" -> "apocalypsenow:bandage";
            case "cloth" -> "apocalypsenow:cloth";
            case "parts", "armory" -> "minecraft:" + ("armory".equals(kind) ? "iron_ingot" : "copper_ingot");
            case "salvage" -> "apocalypsenow:broken_radio";
            case "sanitation" -> "apocalypsenow:cleanrags";
            case "antiseptic" -> "apocalypsenow:alcohol";
            case "radio" -> "apocalypsenow:broken_military_radio";
            case "provisions" -> "minecraft:cooked_beef";
            default -> null;
        };
        if (id == null) return true;
        Item item = item(id);
        if (item == null) return false;
        int required = switch (kind) {
            case "cloth", "parts" -> 5;
            case "armory" -> 8;
            case "salvage", "radio" -> 1;
            case "medical", "antiseptic" -> 2;
            default -> 3;
        };
        int available = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) available += stack.getCount();
        }
        if (available < required) return false;
        int remaining = required;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(stack.getCount(), remaining);
            stack.shrink(taken);
            remaining -= taken;
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static void giveReward(ServerPlayer player, String kind) {
        switch (kind) {
            case "medical", "sanitation" -> give(player, "apocalypsenow:medicalkit", 1);
            case "cloth", "watch", "perimeter" -> {
                give(player, "apocalypsenow:canned_food", 2);
                give(player, "apocalypsenow:bandage", 1);
            }
            case "parts" -> {
                give(player, "apocalypsenow:cheap_survival_knife", 1);
                give(player, "apocalypsenow:canned_water", 1);
            }
            case "salvage" -> {
                give(player, "apocalypsenow:canned_food", 2);
                give(player, "apocalypsenow:bandage", 2);
            }
            case "antiseptic" -> {
                give(player, "apocalypsenow:bandage", 2);
                give(player, "apocalypsenow:morphine", 1);
            }
            case "radio", "armory" -> {
                giveAmmo(player, "tacz:9mm", "radio".equals(kind) ? 16 : 24);
                give(player, "apocalypsenow:canned_food", "radio".equals(kind) ? 1 : 2);
            }
            case "sweep" -> {
                giveAmmo(player, "tacz:762x39", 16);
                give(player, "apocalypsenow:bandage", 2);
            }
            case "trail" -> {
                give(player, "apocalypsenow:bushcraft_knife", 1);
                give(player, "apocalypsenow:bandage", 2);
            }
            case "provisions" -> {
                give(player, "apocalypsenow:canned_food", 2);
                give(player, "apocalypsenow:canned_water", 1);
            }
            default -> give(player, "apocalypsenow:bandage", 2);
        }
    }

    private static void giveAmmo(ServerPlayer player, String ammoId, int count) {
        Item item = item("tacz:ammo");
        if (item == null) {
            LazarusNpcs.LOGGER.error("Missing TaCZ ammunition for NPC task reward");
            return;
        }
        ItemStack reward = new ItemStack(item, count);
        reward.getOrCreateTag().putString("AmmoId", ammoId);
        if (!player.addItem(reward) && !reward.isEmpty()) player.drop(reward, false);
    }

    private static void give(ServerPlayer player, String id, int count) {
        Item item = item(id);
        if (item == null) {
            LazarusNpcs.LOGGER.error("Missing NPC task reward item {}", id);
            return;
        }
        ItemStack reward = new ItemStack(item, count);
        if (!player.addItem(reward) && !reward.isEmpty()) player.drop(reward, false);
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key != null && ForgeRegistries.ITEMS.containsKey(key) ? ForgeRegistries.ITEMS.getValue(key) : null;
    }

    private static CompoundTag active(CompoundTag data, long now) {
        CompoundTag task = data.getCompound(ACTIVE);
        if (!task.isEmpty() && (now < task.getLong("StartedAt") || now - task.getLong("StartedAt") >= EXPIRE_TICKS)) {
            data.remove(ACTIVE);
            return new CompoundTag();
        }
        return task;
    }

    private static void save(ServerPlayer player, CompoundTag data) {
        player.getPersistentData().put(DATA_KEY, data);
    }

    private static long time(ServerPlayer player) {
        return player.serverLevel().getServer().overworld().getGameTime();
    }

    private static void say(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    private static boolean zombie(LivingEntity target) {
        if (target instanceof Zombie) return true;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (id == null) return false;
        if ("undead_revamp2".equals(id.getNamespace())) return true;
        if ("apocalypsenow".equals(id.getNamespace())) {
            return id.getPath().startsWith("walker_") || id.getPath().startsWith("runner_")
                    || id.getPath().startsWith("crawler_");
        }
        return id.getPath().contains("zombie") || id.getPath().contains("infected");
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof ServerPlayer) && event.getSource().getDirectEntity() instanceof Projectile projectile)
            attacker = projectile.getOwner();
        if (!(attacker instanceof ServerPlayer)) attacker = event.getEntity().getKillCredit();
        if (!(attacker instanceof ServerPlayer player) || !zombie(event.getEntity())) return;

        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        CompoundTag task = active(data, time(player));
        if (killGoal(task.getString("Kind")) == 0) {
            save(player, data);
            return;
        }
        if (!event.getEntity().level().dimension().location().toString().equals(task.getString("Dimension"))) return;
        double x = event.getEntity().getX() - (task.getInt("X") + 0.5);
        double y = event.getEntity().getY() - task.getInt("Y");
        double z = event.getEntity().getZ() - (task.getInt("Z") + 0.5);
        if (x * x + y * y + z * z > PATROL_RADIUS * PATROL_RADIUS) return;
        int progress = task.getInt("Progress");
        int goal = killGoal(task.getString("Kind"));
        if (progress < goal) {
            task.putInt("Progress", ++progress);
            data.put(ACTIVE, task);
            save(player, data);
            player.displayClientMessage(Component.literal("Infected cleared: " + progress + "/" + goal), true);
        }
    }

    @SubscribeEvent
    public static void clonePlayer(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        CompoundTag old = event.getOriginal().getPersistentData();
        if (!old.contains(DATA_KEY)) return;
        CompoundTag tasks = old.getCompound(DATA_KEY).copy();
        tasks.remove(ACTIVE);
        event.getEntity().getPersistentData().put(DATA_KEY, tasks);
    }
}
