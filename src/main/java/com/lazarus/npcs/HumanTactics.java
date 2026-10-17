package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Group tactics layered on top of the Apocalypse Now goals. Movement goes through a priority-0
 * goal so it can interrupt the original melee goal; target choice stays gated by HumanEncounters.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class HumanTactics {
    private static final Map<Mob, OrderGoal> ORDERS = new WeakHashMap<>();
    private static final Map<UUID, Long> LAST_CALLOUT = new HashMap<>();
    private static final String[] SIGNS = {
            "You hear footsteps behind you.", "A twig snaps somewhere close.",
            "Something shifts in the dark.", "You feel watched."};

    private HumanTactics() {}

    static boolean isHunter(LivingEntity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && "apocalypsenow".equals(id.getNamespace()) && "hunter".equals(id.getPath());
    }

    static boolean isLookout(LivingEntity mob) {
        return "lookout".equals(HumanEncounters.data(mob).getString("Role"));
    }

    static boolean broken(LivingEntity mob) { return HumanEncounters.data(mob).getBoolean("Broken"); }

    static boolean insidePost(Mob mob, Player player, double radius) {
        CompoundTag root = HumanEncounters.data(mob);
        if (!root.contains("PostX")) return true;
        return player.distanceToSqr(post(root)) <= radius * radius;
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || ORDERS.containsKey(mob)) return;
        String kind = HumanEncounters.kind(mob);
        boolean hunter = isHunter(mob);
        if (kind == null && !hunter) return;
        OrderGoal order = new OrderGoal(mob);
        mob.goalSelector.addGoal(0, order);
        ORDERS.put(mob, order);
        // The original hunter only targets walkers, runners 34-55/57 and crawlers.
        if (hunter) mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, LivingEntity.class,
                10, true, false, NpcServices::isInfected));
        if ("soldier".equals(kind)) {
            // Apocalypse Now leaves the vanilla 8.5% per slot; spec-ops gear should be a rare find.
            for (EquipmentSlot slot : EquipmentSlot.values())
                if (slot != EquipmentSlot.MAINHAND || !NpcGuns.armed(mob)) // guns keep NpcGuns.DROP_CHANCE
                    mob.setDropChance(slot, slot.getType() == EquipmentSlot.Type.ARMOR ? 0.03f : 0.05f);
        }
    }

    @SubscribeEvent
    public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide() || mob.tickCount % 10 != 0) return;
        OrderGoal order = ORDERS.get(mob);
        if (order == null) return;
        long now = mob.level().getGameTime();
        long start = System.nanoTime();
        String kind = HumanEncounters.kind(mob);
        if (!HumanSurrender.tick(mob, order, now) && !retreat(mob, kind, order, now) && kind != null) {
            switch (kind) {
                case "looter" -> looter(mob, order, now);
                case "soldier" -> soldier(mob, order, now);
                case "cannibal" -> cannibal(mob, order, now);
                default -> { }
            }
        }
        NpcPerf.record("human tactics", System.nanoTime() - start);
    }

    static int tracked() { return ORDERS.size(); }

    /** Hunters step in when an infected hurts a nearby player. */
    @SubscribeEvent
    public static void assist(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getSource().getEntity() instanceof LivingEntity attacker) || !NpcServices.isInfected(attacker)) return;
        long now = player.serverLevel().getGameTime();
        for (Mob hunter : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(20),
                HumanTactics::isHunter)) {
            LivingEntity current = hunter.getTarget();
            if ((current == null || !current.isAlive()) && !HumanEncounters.retreating(hunter, now)) hunter.setTarget(attacker);
        }
    }

    private static boolean retreat(Mob mob, String kind, OrderGoal order, long now) {
        boolean daylight = HumanEncounters.daylightCannibal(mob, kind);
        boolean scared = broken(mob) && mob.level().getNearestPlayer(mob, 12) != null;
        if (!HumanEncounters.retreating(mob, now) && !daylight && !scared) return false;
        if (mob.getTarget() != null) mob.setTarget(null);
        mob.setShiftKeyDown(false);
        Player nearest = mob.level().getNearestPlayer(mob, 20);
        if (nearest != null) order.moveAway(nearest.position(), 12, 1.15, 30);
        return true;
    }

    // ---- Looters: a lookout who spots and signals, fighters who close in, and group morale.

    private static void looter(Mob mob, OrderGoal order, long now) {
        CompoundTag root = HumanEncounters.data(mob);
        List<Mob> group = HumanEncounters.group(mob, "looter", 16);
        if (!root.contains("Role") && group.size() >= 2) {
            boolean lookout = group.stream().noneMatch(HumanTactics::isLookout);
            root.putString("Role", lookout ? "lookout" : "fighter");
            if (lookout && mob.getOffhandItem().isEmpty()) {
                mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SPYGLASS));
                mob.setDropChance(EquipmentSlot.OFFHAND, 0f);
            }
        }
        root.putInt("GroupSize", Math.max(root.getInt("GroupSize"), group.size()));
        HumanEncounters.putData(mob, root);

        if (group.stream().anyMatch(member -> member.getTarget() instanceof Player)
                && lostAdvantage(root.getInt("GroupSize"), root.getInt("Losses"), health(group), maxHealth(group))) {
            rout(mob, group, now);
            return;
        }
        scan(mob, isLookout(mob) ? 24 : 16, now);
        if (isLookout(mob)) lookout(mob, order, now);
    }

    static boolean lostAdvantage(int groupSize, int losses, float health, float maxHealth) {
        return losses * 2 >= Math.max(2, groupSize) || health < maxHealth * 0.4f;
    }

    private static float health(List<Mob> group) {
        float total = 0;
        for (Mob member : group) total += member.getHealth();
        return total;
    }

    private static float maxHealth(List<Mob> group) {
        float total = 0;
        for (Mob member : group) total += member.getMaxHealth();
        return total;
    }

    private static void rout(Mob mob, List<Mob> group, long now) {
        for (Mob member : group) {
            CompoundTag root = HumanEncounters.data(member);
            root.putLong("RetreatUntil", now + 600);
            root.putBoolean("Broken", true);
            HumanEncounters.putData(member, root);
            member.setTarget(null);
        }
        for (Player player : mob.level().players())
            if (player.distanceToSqr(mob) < 32 * 32)
                player.sendSystemMessage(Component.literal("The looters lose their nerve and scatter!")
                        .withStyle(style -> style.withColor(0xD48968)));
    }

    private static void lookout(Mob mob, OrderGoal order, long now) {
        ServerPlayer watched = null;
        for (Player candidate : mob.level().players()) {
            if (!(candidate instanceof ServerPlayer player) || !valid(mob, player, 24)) continue;
            CompoundTag state = HumanEncounters.state(mob, player);
            if (!state.contains("WarnedAt") || state.getBoolean("Provoked") || state.getLong("PeaceUntil") > now
                    || HumanEncounters.warningExpired(state.getLong("WarnedAt"), now)) continue;
            if (watched == null || player.distanceToSqr(mob) < watched.distanceToSqr(mob)) watched = player;
        }
        if (watched == null) return;
        mob.getLookControl().setLookAt(watched, 30, 30);
        if (mob.distanceToSqr(watched) < 64) order.moveAway(watched.position(), 10, 1.1, 30);
        CompoundTag state = HumanEncounters.state(mob, watched);
        if (!state.getBoolean("Signalled") && now - state.getLong("WarnedAt") >= 200) {
            state.putBoolean("Signalled", true);
            HumanEncounters.putState(mob, watched, state);
            mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.NOTE_BLOCK_FLUTE.value(),
                    SoundSource.HOSTILE, 1.2f, 1.8f);
            watched.sendSystemMessage(Component.literal("The lookout gives the signal. The looters move in!")
                    .withStyle(style -> style.withColor(0xD48968)));
        }
    }

    /**
     * Offers each eligible player to the target event in turn. Vanilla targeting only ever tries the
     * nearest player, so without this a paid-up player standing closer would shield everyone else.
     */
    private static void scan(Mob mob, double radius, long now) {
        if (mob.getTarget() != null) return;
        List<ServerPlayer> players = mob.level().players().stream()
                .filter(candidate -> candidate instanceof ServerPlayer)
                .map(candidate -> (ServerPlayer) candidate)
                .filter(player -> valid(mob, player, radius) && mob.hasLineOfSight(player)
                        && HumanEncounters.state(mob, player).getLong("PeaceUntil") <= now)
                .sorted(Comparator.comparingDouble((ServerPlayer player) -> player.distanceToSqr(mob)))
                .toList();
        for (ServerPlayer player : players) {
            mob.setTarget(player);
            if (mob.getTarget() != null) return;
        }
    }

    // ---- Rogue soldiers: hold a checkpoint, rally together, use cover against ranged weapons.

    private static void soldier(Mob mob, OrderGoal order, long now) {
        CompoundTag root = HumanEncounters.data(mob);
        if (!root.contains("PostX")) {
            root.putDouble("PostX", mob.getX());
            root.putDouble("PostY", mob.getY());
            root.putDouble("PostZ", mob.getZ());
            HumanEncounters.putData(mob, root);
        }
        Vec3 post = post(root);
        if (mob.getTarget() instanceof ServerPlayer player) {
            double leash = HumanEncounters.state(mob, player).getBoolean("Provoked") ? 40 : 24;
            if (player.distanceToSqr(post) > leash * leash) {
                mob.setTarget(null);
                order.move(post, 1.0, 100);
                return;
            }
            List<Mob> squad = HumanEncounters.group(mob, "soldier", 16);
            boolean rallied = false;
            for (Mob ally : squad) {
                if (ally == mob || ally.getTarget() != null || HumanEncounters.retreating(ally, now)) continue;
                Stealth.reveal(ally, player, "rally");
                ally.setTarget(player);
                rallied |= ally.getTarget() == player;
            }
            if (rallied) callout(player, "Rogue soldier: \"Contact! On me!\"", now);
            cover(mob, order, player, squad, now);
            return;
        }
        if (mob.getTarget() != null) return;
        scan(mob, 20, now);
        if (mob.getTarget() == null && mob.position().distanceToSqr(post) > 25) order.move(post, 0.9, 100);
    }

    private static Vec3 post(CompoundTag root) {
        return new Vec3(root.getDouble("PostX"), root.getDouble("PostY"), root.getDouble("PostZ"));
    }

    private static void cover(Mob mob, OrderGoal order, ServerPlayer player, List<Mob> squad, long now) {
        CompoundTag root = HumanEncounters.data(mob);
        if (now < root.getLong("CoverReady") || mob.distanceToSqr(player) < 36) return;
        int side = (mob.getUUID().getLeastSignificantBits() & 1) == 0 ? 1 : -1;
        boolean ranged = ranged(player.getMainHandItem()) || ranged(player.getOffhandItem());
        Vec3 dest = null;
        String line = null;
        if (ranged && mob.hasLineOfSight(player)) {
            dest = coverPosition(mob, player, side);
            line = "Rogue soldier: \"Get to cover!\"";
        }
        if (dest == null && mob.distanceToSqr(player) > 64
                && squad.stream().filter(ally -> ally.getTarget() == player).count() >= 2) {
            Vec3 toPlayer = horizontal(player.position().subtract(mob.position()));
            dest = player.position().add(-toPlayer.z * side * 5, 0, toPlayer.x * side * 5);
            line = "Rogue soldier: \"Flank them!\"";
        }
        if (dest == null) return;
        order.move(dest, 1.25, ranged ? 50 : 40);
        root.putLong("CoverReady", now + (ranged ? 100 : 80));
        HumanEncounters.putData(mob, root);
        callout(player, line, now);
    }

    private static Vec3 coverPosition(Mob mob, ServerPlayer player, int side) {
        Level level = mob.level();
        Vec3 eye = player.getEyePosition();
        Vec3 toPlayer = horizontal(player.position().subtract(mob.position()));
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 3; radius <= 7; radius += 2) {
            for (int step = 0; step < 16; step++) {
                double angle = step * Math.PI / 8;
                double dx = Math.cos(angle), dz = Math.sin(angle);
                if (dx * toPlayer.x + dz * toPlayer.z < -0.3) continue;
                BlockPos pos = standable(level, BlockPos.containing(mob.getX() + dx * radius, mob.getY(), mob.getZ() + dz * radius));
                if (pos == null) continue;
                Vec3 feet = Vec3.atBottomCenterOf(pos);
                Vec3 head = feet.add(0, mob.getEyeHeight(), 0);
                if (level.clip(new ClipContext(head, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob))
                        .getType() != HitResult.Type.BLOCK) continue;
                double cross = toPlayer.x * dz - toPlayer.z * dx;
                double score = feet.distanceToSqr(player.position()) - side * cross * 8;
                if (score < bestScore) {
                    bestScore = score;
                    best = feet;
                }
            }
        }
        return best;
    }

    private static BlockPos standable(Level level, BlockPos pos) {
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos at = pos.above(dy);
            if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                    && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
                    && !level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()) return at;
        }
        return null;
    }

    private static boolean ranged(ItemStack stack) {
        if (stack.getItem() instanceof ProjectileWeaponItem) return true;
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "tacz".equals(id.getNamespace()) && id.getPath().contains("gun");
    }

    private static void callout(ServerPlayer player, String line, long now) {
        long last = LAST_CALLOUT.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now >= last && now - last < 100) return;
        LAST_CALLOUT.put(player.getUUID(), now);
        player.displayClientMessage(Component.literal(line).withStyle(style -> style.withColor(0xD48968)), true);
    }

    // ---- Cannibals: stalk one isolated player at night, give signs, then ambush.

    private static void cannibal(Mob mob, OrderGoal order, long now) {
        CompoundTag root = HumanEncounters.data(mob);
        ServerPlayer prey = root.hasUUID("Prey") && mob.level().getPlayerByUUID(root.getUUID("Prey")) instanceof ServerPlayer known
                && valid(mob, known, 40) && (Stealth.aware(mob, known) || HumanEncounters.state(mob, known).getBoolean("Ambush"))
                ? known : null;
        if (prey == null) {
            prey = choosePrey(mob);
            if (prey == null) {
                mob.setShiftKeyDown(false);
                return;
            }
            root.putUUID("Prey", prey.getUUID());
            root.putLong("StalkStart", now);
            root.putInt("Signs", 0);
            root.putLong("NextSign", now + 100 + mob.getRandom().nextInt(100));
            HumanEncounters.putData(mob, root);
        }
        CompoundTag state = HumanEncounters.state(mob, prey);
        if (state.getBoolean("Provoked")) {
            mob.setShiftKeyDown(false);
            return;
        }
        if (state.getBoolean("Ambush")) {
            if (now - state.getLong("WarnedAt") <= 1200 || mob.getTarget() == prey) {
                mob.setShiftKeyDown(false);
                if (mob.getTarget() == null && now - state.getLong("WarnedAt") >= 40) {
                    Stealth.reveal(mob, prey, "ambush");
                    mob.setTarget(prey);
                }
                return;
            }
            state.remove("Ambush");
            state.remove("WarnedAt");
            HumanEncounters.putState(mob, prey, state);
            root.putLong("StalkStart", now);
            root.putInt("Signs", 0);
            HumanEncounters.putData(mob, root);
        }

        mob.setShiftKeyDown(true);
        double distance = mob.distanceTo(prey);
        boolean watched = watched(prey, mob);
        if (watched) order.moveAway(prey.position(), 8, 1.0, 30);
        else if (distance > 12) {
            Vec3 look = horizontal(prey.getViewVector(1f));
            order.move(prey.position().subtract(look.x * 10, 0, look.z * 10), 0.8, 30);
        } else order.hold(20);
        mob.getLookControl().setLookAt(prey, 10, 10);

        if (now >= root.getLong("NextSign") && distance <= 24) {
            int sign = root.getInt("Signs");
            SoundEvent sound = sign % 2 == 0 ? SoundEvents.GRASS_STEP : SoundEvents.WOOD_BREAK;
            mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), sound, SoundSource.HOSTILE, 0.9f, 1.3f);
            prey.displayClientMessage(Component.literal(SIGNS[sign % SIGNS.length])
                    .withStyle(style -> style.withColor(0x9A9A9A).withItalic(true)), true);
            root.putInt("Signs", sign + 1);
            root.putLong("NextSign", now + 120 + mob.getRandom().nextInt(120));
            HumanEncounters.putData(mob, root);
        }
        long stalked = now - root.getLong("StalkStart");
        if (readyToAmbush(root.getInt("Signs"), stalked, distance, watched, isolated(prey))) {
            HumanEncounters.warnGroup(mob, "cannibal", prey, now);
            state = HumanEncounters.state(mob, prey);
            state.putLong("WarnedAt", now);
            state.putBoolean("Ambush", true);
            HumanEncounters.putState(mob, prey, state);
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 80, 1));
            mob.setShiftKeyDown(false);
            order.cancel();
        }
    }

    static boolean readyToAmbush(int signs, long stalked, double distance, boolean watched, boolean isolated) {
        return signs >= 2 && stalked >= 400 && distance <= 14 && !watched && (isolated || stalked >= 1200);
    }

    private static ServerPlayer choosePrey(Mob mob) {
        ServerPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player candidate : mob.level().players()) {
            if (!(candidate instanceof ServerPlayer player) || !valid(mob, player, 32) || !Stealth.aware(mob, player)) continue;
            double score = mob.distanceTo(player) + 16 * companions(player);
            if (score < bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private static int companions(ServerPlayer player) {
        int count = 0;
        for (Player other : player.level().players())
            if (other != player && other.isAlive() && !other.isSpectator() && other.distanceToSqr(player) < 144) count++;
        return count;
    }

    private static boolean isolated(ServerPlayer player) { return companions(player) == 0; }

    private static boolean watched(ServerPlayer player, Mob mob) {
        Vec3 toMob = mob.getEyePosition().subtract(player.getEyePosition()).normalize();
        return player.getViewVector(1f).dot(toMob) > 0.8 && player.hasLineOfSight(mob);
    }

    private static boolean valid(Mob mob, ServerPlayer player, double radius) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator() && player.level() == mob.level()
                && player.distanceToSqr(mob) <= radius * radius;
    }

    /**
     * Paths are capped at the mob's follow range (16 for these NPCs), so a far destination is reached in
     * steps toward it instead of failing silently and leaving the NPC standing still.
     */
    static void walk(Mob mob, Vec3 destination, double speed) {
        if (mob.position().distanceTo(destination) > 12 && mob instanceof PathfinderMob pathfinder) {
            Vec3 step = DefaultRandomPos.getPosTowards(pathfinder, 10, 7, destination, Math.PI / 2);
            if (step != null) {
                mob.getNavigation().moveTo(step.x, step.y, step.z, speed);
                return;
            }
        }
        mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
    }

    private static Vec3 horizontal(Vec3 vector) {
        Vec3 flat = new Vec3(vector.x, 0, vector.z);
        return flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
    }

    /** One movement order at a time; while active it outranks the original melee and stroll goals. */
    static final class OrderGoal extends Goal {
        private final Mob mob;
        private Vec3 destination;
        private double speed;
        private long until;

        OrderGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        void move(Vec3 destination, double speed, int ticks) {
            this.destination = destination;
            this.speed = speed;
            this.until = mob.level().getGameTime() + ticks;
            walk(mob, destination, speed);
        }

        void moveAway(Vec3 from, double distance, double speed, int ticks) {
            Vec3 away = mob instanceof PathfinderMob pathfinder
                    ? DefaultRandomPos.getPosAway(pathfinder, (int) distance + 4, 7, from) : null;
            if (away == null) {
                Vec3 direction = horizontal(mob.position().subtract(from));
                away = mob.position().add(direction.scale(distance));
            }
            move(away, speed, ticks);
        }

        void hold(int ticks) {
            destination = null;
            until = mob.level().getGameTime() + ticks;
        }

        void cancel() {
            destination = null;
            until = 0;
        }

        @Override
        public boolean canUse() { return mob.level().getGameTime() < until; }

        @Override
        public boolean canContinueToUse() {
            return canUse() && (destination == null || mob.position().distanceToSqr(destination) > 2.25);
        }

        @Override
        public void tick() {
            if (destination == null) mob.getNavigation().stop();
            else if (mob.getNavigation().isDone()) walk(mob, destination, speed);
        }

        @Override
        public void stop() {
            until = 0;
            destination = null;
        }
    }
}
