package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escort and base-guard contracts. Contracts live in overworld SavedData (not on the entity), so they
 * survive chunk unloads and relogs, expire on world time while unloaded, and are capped globally.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcContracts {
    static final long ESCORT_DURATION = 6_000L;
    static final long GUARD_DURATION = 24_000L;      // one in-game day, about 20 minutes
    static final long GUARD_MAX_REMAINING = 72_000L; // renewals stack up to three days
    static final int[] GUARD_RADII = {6, 10, 14};
    static final int GUARDS_PER_PLAYER = 2;
    static final int GUARDS_PER_SERVER = 12;
    static final int GUARD_REPUTATION = 10;
    static final double GUARD_HIRE_DISTANCE = 32;
    static final String[][] ESCORT_COST = {{"apocalypsenow:canned_food", "3"}, {"minecraft:iron_ingot", "2"}};
    static final String[][] GUARD_COST = {{"apocalypsenow:canned_food", "4"}, {"apocalypsenow:canned_water", "2"},
            {"minecraft:iron_ingot", "3"}};
    private static final long ARRIVAL_LIMIT = 1_200L;
    private static final int STUCK_WARNING = 400, STUCK_LIMIT = 900, FAR_LIMIT = 600, MISSING_LIMIT = 600;
    private static Store cached;

    private NpcContracts() {}

    // ---- Data

    static final class Contract {
        String type, ownerName, npcName, dim, faction = "";
        UUID npc, owner;
        long until, hiredAt;
        double x, y, z;
        int radius;
        boolean arrived;
        transient int stuck, far, missing;
        transient double lastDistance = Double.MAX_VALUE;
        transient boolean following, warned;

        boolean guard() { return "guard".equals(type); }
        Vec3 post() { return new Vec3(x, y, z); }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", type);
            tag.putUUID("Npc", npc);
            tag.putUUID("Owner", owner);
            tag.putString("OwnerName", ownerName);
            tag.putString("NpcName", npcName);
            tag.putString("Dim", dim);
            tag.putString("Faction", faction);
            tag.putLong("Until", until);
            tag.putLong("HiredAt", hiredAt);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putInt("Radius", radius);
            tag.putBoolean("Arrived", arrived);
            return tag;
        }

        static Contract load(CompoundTag tag) {
            Contract c = new Contract();
            c.type = tag.getString("Type");
            c.npc = tag.getUUID("Npc");
            c.owner = tag.getUUID("Owner");
            c.ownerName = tag.getString("OwnerName");
            c.npcName = tag.getString("NpcName");
            c.dim = tag.getString("Dim");
            c.faction = tag.getString("Faction");
            c.until = tag.getLong("Until");
            c.hiredAt = tag.getLong("HiredAt");
            c.x = tag.getDouble("X");
            c.y = tag.getDouble("Y");
            c.z = tag.getDouble("Z");
            c.radius = tag.getInt("Radius");
            c.arrived = tag.getBoolean("Arrived");
            return c;
        }
    }

    static final class Store extends SavedData {
        final Map<UUID, Contract> byNpc = new LinkedHashMap<>();
        final Map<UUID, ListTag> refunds = new LinkedHashMap<>();

        static Store load(CompoundTag tag) {
            Store store = new Store();
            for (Tag entry : tag.getList("Contracts", Tag.TAG_COMPOUND)) {
                Contract c = Contract.load((CompoundTag) entry);
                store.byNpc.put(c.npc, c);
            }
            CompoundTag refunds = tag.getCompound("Refunds");
            for (String key : refunds.getAllKeys())
                store.refunds.put(UUID.fromString(key), refunds.getList(key, Tag.TAG_COMPOUND));
            return store;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Contract c : byNpc.values()) list.add(c.save());
            tag.put("Contracts", list);
            CompoundTag refunds = new CompoundTag();
            this.refunds.forEach((owner, items) -> refunds.put(owner.toString(), items));
            tag.put("Refunds", refunds);
            return tag;
        }

        long guards(UUID owner) {
            return byNpc.values().stream().filter(c -> c.guard() && (owner == null || owner.equals(c.owner))).count();
        }

        Contract escortOf(UUID owner) {
            return byNpc.values().stream().filter(c -> !c.guard() && owner.equals(c.owner)).findFirst().orElse(null);
        }
    }

    static Store store(MinecraftServer server) {
        if (cached == null)
            cached = server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, "lazarusnpcs_contracts");
        return cached;
    }

    static Contract contract(Entity npc) {
        MinecraftServer server = npc.getServer();
        return server == null || npc.level().isClientSide() ? null : store(server).byNpc.get(npc.getUUID());
    }

    static long now(MinecraftServer server) { return server.overworld().getGameTime(); }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { cached = null; }

    // ---- Hiring

    static boolean canGuard(LivingEntity npc) {
        if (NpcProfessions.base(npc) != null) return false;
        String role = NpcIdentity.role(npc);
        String faction = NpcIdentity.faction(npc);
        return "military".equals(role) || "survivor".equals(role) && ("wardens".equals(faction) || "haven".equals(faction));
    }

    static void hireEscort(ServerPlayer player, Mob npc) {
        MinecraftServer server = player.getServer();
        Store store = store(server);
        Contract current = store.byNpc.get(npc.getUUID());
        if (current != null) {
            say(player, current.owner.equals(player.getUUID())
                    ? "This soldier is already under contract with you." : "This soldier is working for " + current.ownerName + ".");
            return;
        }
        Contract escort = store.escortOf(player.getUUID());
        if (escort != null) {
            say(player, "You already have an active escort (" + escort.npcName + ").");
            return;
        }
        if (!pay(player, ESCORT_COST)) {
            say(player, "Escort costs " + costText(ESCORT_COST) + ".");
            return;
        }
        Contract c = create("escort", player, npc, ESCORT_DURATION);
        c.x = npc.getX();
        c.y = npc.getY();
        c.z = npc.getZ();
        store.byNpc.put(c.npc, c);
        store.setDirty();
        say(player, "Escort hired for five minutes. The soldier keeps a few steps behind you, defends you and ends the contract if it loses you.");
        NpcDialogue.action(player, npc, "service");
    }

    static void hireGuard(ServerPlayer player, Mob npc, int radius) {
        if (!canGuard(npc)) {
            say(player, "Only military NPCs and Wardens or Haven survivors take guard duty.");
            return;
        }
        boolean allowedRadius = false;
        for (int option : GUARD_RADII) allowedRadius |= option == radius;
        if (!allowedRadius) return;
        String faction = NpcIdentity.faction(npc);
        if (NpcReputation.get(player, faction) < GUARD_REPUTATION) {
            say(player, "Guard duty needs " + GUARD_REPUTATION + " trust with " + NpcIdentity.label(faction) + ".");
            return;
        }
        MinecraftServer server = player.getServer();
        Store store = store(server);
        long now = now(server);
        Contract current = store.byNpc.get(npc.getUUID());
        if (current != null && current.guard()) {
            if (!current.owner.equals(player.getUUID())) {
                say(player, "Only " + current.ownerName + " can renew or change this guard.");
                return;
            }
            if (current.until - now + GUARD_DURATION > GUARD_MAX_REMAINING) {
                say(player, "This guard is already paid for the maximum of three days.");
                return;
            }
            if (!pay(player, GUARD_COST)) {
                say(player, "Renewal costs " + costText(GUARD_COST) + ".");
                return;
            }
            current.until += GUARD_DURATION;
            current.radius = radius;
            store.setDirty();
            say(player, npcName(npc) + " stays on duty for another day. Area: " + radius + " blocks.");
            return;
        }
        if (current != null && !current.owner.equals(player.getUUID())) {
            say(player, "This NPC is working for " + current.ownerName + ".");
            return;
        }
        if (store.guards(player.getUUID()) >= GUARDS_PER_PLAYER) {
            say(player, "You already have " + GUARDS_PER_PLAYER + " guards on duty.");
            return;
        }
        if (store.guards(null) >= GUARDS_PER_SERVER) {
            say(player, "No more guards are available on this server right now (" + GUARDS_PER_SERVER + " on duty).");
            return;
        }
        if (npc.level() != player.level() || npc.distanceToSqr(player) > GUARD_HIRE_DISTANCE * GUARD_HIRE_DISTANCE) {
            say(player, "Stand at the post you want guarded, within " + (int) GUARD_HIRE_DISTANCE + " blocks of the NPC.");
            return;
        }
        if (!pay(player, GUARD_COST)) {
            say(player, "Guard duty costs " + costText(GUARD_COST) + " per day.");
            return;
        }
        if (current != null) store.byNpc.remove(current.npc); // an escort becomes a guard
        Contract c = create("guard", player, npc, GUARD_DURATION);
        c.x = player.getBlockX() + 0.5;
        c.y = player.getY();
        c.z = player.getBlockZ() + 0.5;
        c.radius = radius;
        store.byNpc.put(c.npc, c);
        store.setDirty();
        say(player, npcName(npc) + " will guard a " + radius + "-block area around this spot for one day.");
        NpcDialogue.action(player, npc, "service");
    }

    static void dismiss(ServerPlayer player, LivingEntity npc) {
        Store store = store(player.getServer());
        Contract c = store.byNpc.get(npc.getUUID());
        if (c == null) return;
        if (!c.owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
            say(player, "Only " + c.ownerName + " can dismiss this NPC.");
            return;
        }
        end(player.getServer(), c, "Contract with " + c.npcName + " ended. No refund for unused time.");
    }

    /** Ends every contract the player holds, wherever the NPCs are. */
    static void releaseAll(ServerPlayer player) {
        Store store = store(player.getServer());
        List<Contract> mine = store.byNpc.values().stream().filter(c -> c.owner.equals(player.getUUID())).toList();
        for (Contract c : mine) end(player.getServer(), c, null);
        say(player, mine.isEmpty() ? "You have no active contracts." : "Released " + mine.size() + " contract(s).");
    }

    /** A player who becomes a faction's enemy loses every escort and guard of that faction. */
    static void endForFaction(ServerPlayer player, String faction) {
        for (Contract c : new ArrayList<>(store(player.getServer()).byNpc.values()))
            if (c.owner.equals(player.getUUID()) && faction.equals(c.faction))
                end(player.getServer(), c, c.npcName + " will no longer work for you.");
    }

    private static Contract create(String type, ServerPlayer player, Mob npc, long duration) {
        Contract c = new Contract();
        c.type = type;
        c.npc = npc.getUUID();
        c.owner = player.getUUID();
        c.ownerName = player.getGameProfile().getName();
        c.npcName = npcName(npc);
        c.dim = npc.level().dimension().location().toString();
        c.faction = NpcIdentity.faction(npc);
        c.hiredAt = now(player.getServer());
        c.until = c.hiredAt + duration;
        return c;
    }

    static void end(MinecraftServer server, Contract c, String message) {
        Store store = store(server);
        if (store.byNpc.remove(c.npc) == null) return;
        store.setDirty();
        Entity npc = level(server, c) == null ? null : level(server, c).getEntity(c.npc);
        if (npc instanceof Mob mob) mob.getNavigation().stop();
        ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
        if (owner != null && message != null) say(owner, message);
    }

    private static void refund(MinecraftServer server, Contract c) {
        ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
        ListTag items = new ListTag();
        for (String[] cost : c.guard() ? GUARD_COST : ESCORT_COST) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(cost[0]));
            if (item != null) items.add(new ItemStack(item, Integer.parseInt(cost[1])).save(new CompoundTag()));
        }
        if (owner != null) give(owner, items);
        else {
            store(server).refunds.put(c.owner, items);
            store(server).setDirty();
        }
    }

    private static void give(ServerPlayer player, ListTag items) {
        for (Tag tag : items) {
            ItemStack stack = ItemStack.of((CompoundTag) tag);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    // ---- Status text

    static String npcStatus(ServerPlayer player, LivingEntity npc) {
        Contract c = contract(npc);
        long now = now(player.getServer());
        String cost = "Cost: " + costText(GUARD_COST) + " per day (max 3). Needs " + GUARD_REPUTATION + " trust.";
        if (c == null) return "Available. Your guards: " + store(player.getServer()).guards(player.getUUID()) + "/"
                + GUARDS_PER_PLAYER + ". " + cost;
        if (!c.owner.equals(player.getUUID())) return "Under contract with " + c.ownerName + ".";
        String left = minutes(c.until - now);
        if (!c.guard()) return "Escorting you, " + left + " left. Hiring guard duty here converts the escort.";
        return (c.arrived ? "On duty" : "Walking to the post") + ", " + left + " left, area " + c.radius
                + " blocks, post " + (int) Math.floor(c.x) + " " + (int) Math.floor(c.y) + " " + (int) Math.floor(c.z) + ".";
    }

    static String playerSummary(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        long now = now(server);
        StringBuilder text = new StringBuilder();
        for (Contract c : store(server).byNpc.values()) {
            if (!c.owner.equals(player.getUUID())) continue;
            ServerLevel level = level(server, c);
            boolean loaded = level != null && level.getEntity(c.npc) != null;
            if (text.length() > 0) text.append(" | ");
            text.append(c.guard() ? "Guard " : "Escort ").append(c.npcName).append(": ").append(minutes(c.until - now))
                    .append(c.guard() ? ", area " + c.radius + " at " + (int) Math.floor(c.x) + " " + (int) Math.floor(c.z) : "")
                    .append(loaded ? "" : " (out of range)");
        }
        String result = text.length() == 0 ? "No active escorts or guards." : text.toString();
        return result.length() > 390 ? result.substring(0, 387) + "..." : result;
    }

    static String escortStatus(ServerPlayer player, LivingEntity npc) {
        Contract c = contract(npc);
        if (c != null && !c.owner.equals(player.getUUID())) return "This soldier is working for " + c.ownerName + ".";
        if (c != null) return (c.guard() ? "Guarding for you" : "Escorting you") + ", " + minutes(c.until - now(player.getServer())) + " left.";
        if (store(player.getServer()).escortOf(player.getUUID()) != null) return "You already have an escort.";
        return has(player, ESCORT_COST) ? "Ready" : "Need " + costText(ESCORT_COST) + ".";
    }

    private static String minutes(long ticks) { return Math.max(1, (ticks + 1199) / 1200) + " min"; }

    static String costText(String[][] cost) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < cost.length; i++) {
            if (i > 0) text.append(i == cost.length - 1 ? " and " : ", ");
            String id = cost[i][0].substring(cost[i][0].indexOf(':') + 1).replace('_', ' ');
            text.append(cost[i][1]).append(' ').append(id).append(id.endsWith("ingot") ? "s" : "");
        }
        return text.toString();
    }

    // ---- Server-side lifecycle (bounded by the number of contracts, never by world size)

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (now(server) % 20 != 0) return;
        long start = System.nanoTime();
        Store store = store(server);
        long now = now(server);
        for (Contract c : new ArrayList<>(store.byNpc.values())) {
            if (now >= c.until) {
                end(server, c, c.guard() ? c.npcName + " finished guard duty and goes back to their own business."
                        : "Your escort " + c.npcName + " has ended.");
                continue;
            }
            ServerLevel level = level(server, c);
            Entity entity = level == null ? null : level.getEntity(c.npc);
            if (c.guard()) {
                if (entity instanceof Mob mob && mob.isAlive()) guardTick(server, c, mob, now);
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
            if (owner == null) continue; // the escort waits for a reconnect until the contract runs out
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                c.missing += 20;
                if (c.missing >= MISSING_LIMIT) end(server, c, "You lost contact with your escort. The contract is over.");
                continue;
            }
            c.missing = 0;
            c.x = mob.getX();
            c.y = mob.getY();
            c.z = mob.getZ();
            escortTick(server, c, mob, owner);
        }
        NpcPerf.record("contracts", System.nanoTime() - start);
    }

    private static void escortTick(MinecraftServer server, Contract c, Mob mob, ServerPlayer owner) {
        if (owner.level() != mob.level()) return; // handled by the dimension event
        double distance = mob.distanceTo(owner);
        LivingEntity target = mob.getTarget();
        if (target != null) {
            if (target.distanceTo(owner) > 16 && !(target instanceof Mob attacker && attacker.getTarget() == owner))
                mob.setTarget(null); // do not chase threats away from the client
            c.stuck = 0;
            c.lastDistance = distance;
            return;
        }
        c.far = distance > 64 ? c.far + 20 : 0;
        c.stuck = distance > 10 && distance >= c.lastDistance - 0.5 ? c.stuck + 20 : 0;
        c.lastDistance = distance;
        if (c.far >= FAR_LIMIT || c.stuck >= STUCK_LIMIT) {
            end(server, c, "Your escort " + c.npcName + " could not keep up and ended the contract.");
            return;
        }
        if (c.stuck >= STUCK_WARNING && !c.warned) {
            c.warned = true;
            owner.displayClientMessage(Component.literal(c.npcName + " cannot find a way to you."), true);
        }
        if (c.stuck == 0) c.warned = false;
    }

    private static void guardTick(MinecraftServer server, Contract c, Mob mob, long now) {
        Vec3 post = c.post();
        double fromPost = mob.position().distanceTo(post);
        if (!c.arrived) {
            if (fromPost <= 3) {
                c.arrived = true;
                store(server).setDirty();
                ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
                if (owner != null) say(owner, c.npcName + " reached the post and is on duty.");
            } else if (now - c.hiredAt > ARRIVAL_LIMIT) {
                refund(server, c);
                end(server, c, c.npcName + " could not reach the post. Payment returned.");
                return;
            }
        }
        LivingEntity target = mob.getTarget();
        if (target != null) {
            if (!target.isAlive() || target.position().distanceTo(post) > c.radius + 6 || fromPost > c.radius + 8)
                mob.setTarget(null);
            return;
        }
        if (!c.arrived) return;
        long start = System.nanoTime();
        LivingEntity threat = mob.level().getEntitiesOfClass(LivingEntity.class,
                        new AABB(post, post).inflate(c.radius + 2, 6, c.radius + 2),
                        entity -> entity.isAlive() && isThreat(entity) && entity.position().distanceTo(post) <= c.radius + 2)
                .stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(post))).orElse(null);
        NpcPerf.record("guard scan", System.nanoTime() - start);
        if (threat != null) mob.setTarget(threat);
    }

    static boolean isThreat(LivingEntity entity) {
        if (entity instanceof Player || entity instanceof Creeper || HumanSurrender.surrendered(entity)) return false;
        if (NpcIdentity.role(entity) != null) return false;
        return NpcServices.isInfected(entity) || HumanEncounters.kind(entity) != null || entity instanceof Enemy;
    }

    private static ServerLevel level(MinecraftServer server, Contract c) {
        for (ServerLevel level : server.getAllLevels())
            if (level.dimension().location().toString().equals(c.dim)) return level;
        return null;
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        String role = NpcIdentity.role(mob);
        if (role == null || "hunter".equals(role)) return;
        mob.getPersistentData().remove("LazarusNpcsEscort"); // 0.7.x escort tag; contracts now live in SavedData
        for (WrappedGoal goal : mob.goalSelector.getAvailableGoals())
            if (goal.getGoal() instanceof ContractGoal) return;
        // Apocalypse Now military have target goals but no attack goal; same priority the profile script uses.
        if (("military".equals(role) || "medic".equals(role)) && mob instanceof PathfinderMob pathfinder
                && mob.goalSelector.getAvailableGoals().stream().noneMatch(goal -> goal.getGoal() instanceof MeleeAttackGoal))
            mob.goalSelector.addGoal(0, new MeleeAttackGoal(pathfinder, 1.2, false));
        if ("military".equals(role) || "survivor".equals(role)) mob.goalSelector.addGoal(0, new ContractGoal(mob));
    }

    @SubscribeEvent
    public static void despawn(MobSpawnEvent.AllowDespawn event) {
        if (contract(event.getEntity()) != null) event.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public static void target(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || event.getNewTarget() == null) return;
        Contract c = contract(mob);
        if (c == null) return;
        LivingEntity target = event.getNewTarget();
        if (target instanceof Player) return; // NpcHostility decides about players
        if (c.guard()) {
            if (target.position().distanceTo(c.post()) > c.radius + 6) event.setNewTarget(null);
            return;
        }
        ServerPlayer owner = mob.getServer().getPlayerList().getPlayer(c.owner);
        if (owner != null && owner.level() == mob.level() && target.distanceTo(owner) > 16
                && !(target instanceof Mob attacker && attacker.getTarget() == owner)) event.setNewTarget(null);
    }

    /** Escorts defend the client and join the client's fights against hostile mobs. */
    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        Entity source = event.getSource().getEntity();
        if (event.getEntity() instanceof ServerPlayer owner && source instanceof LivingEntity attacker
                && !(attacker instanceof Player)) defend(owner, attacker, 24);
        else if (source instanceof ServerPlayer owner && event.getEntity() != owner
                && !(event.getEntity() instanceof Player) && isThreat(event.getEntity())) defend(owner, event.getEntity(), 16);
    }

    private static void defend(ServerPlayer owner, LivingEntity threat, double range) {
        Contract c = store(owner.getServer()).escortOf(owner.getUUID());
        if (c == null || contract(threat) != null) return;
        // A stray bullet from a friendly NPC is not an attack.
        if (NpcIdentity.role(threat) != null && !NpcHostility.hostileTo(threat, owner)) return;
        if (owner.serverLevel().getEntity(c.npc) instanceof Mob escort && escort != threat && escort.isAlive()
                && escort.distanceTo(owner) <= range && (escort.getTarget() == null || !escort.getTarget().isAlive()))
            escort.setTarget(threat);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        MinecraftServer server = entity.getServer();
        if (entity instanceof ServerPlayer player) {
            Contract escort = store(server).escortOf(player.getUUID());
            if (escort != null) end(server, escort, "Your escort contract ended with your death.");
            return;
        }
        Contract c = contract(entity);
        if (c != null) end(server, c, c.npcName + " was killed. The contract is over.");
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Contract escort = store(player.getServer()).escortOf(player.getUUID());
        if (escort != null) end(player.getServer(), escort, "Your escort cannot follow you there. The contract is over.");
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Store store = store(player.getServer());
        CompoundTag legacy = player.getPersistentData().getCompound("LazarusNpcsServices");
        legacy.remove("EscortUntil");
        legacy.remove("EscortNpc");
        ListTag refund = store.refunds.remove(player.getUUID());
        if (refund != null) {
            give(player, refund);
            store.setDirty();
            say(player, "A guard who never reached the post returned your payment.");
        }
        if (store.byNpc.values().stream().anyMatch(c -> c.owner.equals(player.getUUID())))
            say(player, "Contracts: " + playerSummary(player));
    }

    // ---- Movement: follow without crowding, or hold the post

    static final class ContractGoal extends Goal {
        private final Mob mob;
        private int repath;
        private boolean backingOff;

        ContractGoal(Mob mob) {
            this.mob = mob;
            setFlags(java.util.EnumSet.of(Goal.Flag.MOVE));
        }

        @Override public boolean canUse() { return wanted(false); }
        @Override public boolean canContinueToUse() { return wanted(true); }

        private boolean wanted(boolean running) {
            Contract c = contract(mob);
            if (c == null || mob.getTarget() != null) return false;
            if (c.guard()) return true;
            ServerPlayer owner = mob.getServer().getPlayerList().getPlayer(c.owner);
            if (owner == null || owner.level() != mob.level()) return true; // wait in place
            double distance = mob.distanceTo(owner);
            if (distance < 2.0 || backingOff && distance < 3.0) return true;
            return c.following && running ? distance > 3.5 : distance > 6.0;
        }

        @Override
        public void start() { repath = 0; }

        @Override
        public void stop() {
            Contract c = contract(mob);
            if (c != null) c.following = false;
            backingOff = false;
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            Contract c = contract(mob);
            if (c == null) return;
            if (c.guard()) {
                Vec3 post = c.post();
                if (mob.position().distanceToSqr(post) > 6.25) {
                    if (repath-- <= 0) {
                        repath = 20;
                        HumanTactics.walk(mob, post, 1.0);
                    }
                } else mob.getNavigation().stop();
                return;
            }
            ServerPlayer owner = mob.getServer().getPlayerList().getPlayer(c.owner);
            if (owner == null || owner.level() != mob.level()) {
                mob.getNavigation().stop();
                return;
            }
            mob.getLookControl().setLookAt(owner, 10, mob.getMaxHeadXRot());
            double distance = mob.distanceTo(owner);
            if (distance < 2.0 && !backingOff && mob instanceof PathfinderMob pathfinder) {
                backingOff = true; // step out of the client's way instead of pushing them
                Vec3 away = DefaultRandomPos.getPosAway(pathfinder, 4, 2, owner.position());
                if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
                return;
            }
            if (backingOff) {
                if (distance >= 3.0 || mob.getNavigation().isDone()) backingOff = false;
                return;
            }
            c.following = true;
            if (repath-- <= 0) {
                repath = 10;
                if (distance > 12) HumanTactics.walk(mob, owner.position(), 1.2);
                else mob.getNavigation().moveTo(owner, 1.0);
            }
        }
    }

    // ---- Items

    static boolean has(ServerPlayer player, String[][] cost) {
        for (String[] entry : cost) if (count(player, entry[0]) < Integer.parseInt(entry[1])) return false;
        return true;
    }

    private static boolean pay(ServerPlayer player, String[][] cost) {
        if (!has(player, cost)) return false;
        for (String[] entry : cost) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry[0]));
            int remaining = Integer.parseInt(entry[1]);
            for (int slot = 0; slot < 36 && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.is(item)) continue;
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static int count(ServerPlayer player, String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null) return 0;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static String npcName(LivingEntity npc) { return NpcIdentity.name(npc); }

    private static void say(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal(message)); }
}
