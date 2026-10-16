package com.lazarus.npcs;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.Map;

/**
 * Some NPCs spawn with a TaCZ firearm from their faction's arsenal. The roll happens once per NPC
 * (stored on it), guns drop rarely and always with an empty magazine, and NPC bullets are inaccurate
 * and deal reduced damage to players.
 */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcGuns {
    static final String KEY = "LazarusNpcsGun";
    static final float DROP_CHANCE = 0.12f;
    static final float PLAYER_DAMAGE = 0.5f;

    /** Chance to be armed, arsenal ids with weights, and aim error in degrees. */
    record Arsenal(float chance, float spread, Map<String, Integer> guns) {}

    static final Map<String, Arsenal> ARSENALS = Map.of(
            "military", new Arsenal(0.25f, 1.5f, Map.of("m4a1", 4, "m16a4", 3, "hk_mp5a5", 2, "scar_l", 1, "m9a4", 2, "m870", 1)),
            "medic", new Arsenal(0.12f, 2.0f, Map.of("m9a4", 3, "glock_17", 2)),
            "soldier", new Arsenal(0.20f, 2.5f, Map.of("ak47", 4, "type_81", 2, "sks_tactical", 2, "m1911", 2, "uzi", 2, "rpk", 1)),
            "looter", new Arsenal(0.10f, 4.0f, Map.of("glock_17", 3, "m1911", 2, "db_short", 3, "cz75", 1, "uzi", 1, "taurus943", 1)),
            "hunter", new Arsenal(0.30f, 1.0f, Map.of("m700", 3, "kar98", 2, "springfield1873", 2, "db_long", 2, "m870", 1)),
            "haven", new Arsenal(0.06f, 3.5f, Map.of("glock_17", 3, "m1911", 2, "db_short", 1)),
            "rovers", new Arsenal(0.10f, 3.0f, Map.of("m870", 2, "db_long", 2, "taurus943", 1, "rhino357", 1, "sks_tactical", 1)),
            "wardens", new Arsenal(0.14f, 2.5f, Map.of("m1014", 2, "hk_mp5a5", 2, "ump45", 1, "p320", 2, "m700", 1)),
            "independent", new Arsenal(0.06f, 3.5f, Map.of("glock_17", 2, "db_short", 1, "m1911", 1)));

    private NpcGuns() {}

    /** Which arsenal an NPC draws from, or null for NPCs that never carry guns (cannibals, infected). */
    static String arsenal(LivingEntity npc) {
        String kind = HumanEncounters.kind(npc);
        if ("looter".equals(kind) || "soldier".equals(kind)) return kind;
        if (kind != null) return null;
        String role = NpcIdentity.role(npc);
        if (role == null) return null;
        return switch (role) {
            case "military", "medic", "hunter" -> role;
            default -> NpcIdentity.faction(npc);
        };
    }

    static String pick(Map<String, Integer> guns, int roll) {
        int total = guns.values().stream().mapToInt(Integer::intValue).sum();
        int at = Math.floorMod(roll, total);
        for (var entry : new java.util.TreeMap<>(guns).entrySet()) {
            at -= entry.getValue();
            if (at < 0) return entry.getKey();
        }
        throw new IllegalStateException();
    }

    static boolean armed(LivingEntity npc) { return IGun.getIGunOrNull(npc.getMainHandItem()) != null; }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        String arsenalName = arsenal(mob);
        if (arsenalName == null) return;
        if (!mob.getPersistentData().contains(KEY)) roll(mob, ARSENALS.get(arsenalName));
        attach(mob, ARSENALS.get(arsenalName).spread());
    }

    /** Adds the firing goal to an armed NPC (once per entity instance). */
    static void attach(Mob mob, float spread) {
        if (!armed(mob)) return;
        for (WrappedGoal goal : mob.goalSelector.getAvailableGoals()) if (goal.getGoal() instanceof GunGoal) return;
        mob.goalSelector.addGoal(-1, new GunGoal(mob, spread));
        var followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() < 24) followRange.setBaseValue(24);
    }

    /** Rolled once: the result (gun id or "none") is stored, so reloading a chunk never re-rolls. */
    static void roll(Mob mob, Arsenal arsenal) {
        RandomSource random = mob.getRandom();
        String gun = random.nextFloat() < arsenal.chance() ? pick(arsenal.guns(), random.nextInt(1 << 16)) : "none";
        mob.getPersistentData().putString(KEY, gun);
        if ("none".equals(gun)) return;
        ResourceLocation id = new ResourceLocation("tacz", gun);
        var index = TimelessAPI.getCommonGunIndex(id);
        if (index.isEmpty()) {
            LazarusNpcs.LOGGER.warn("NPC arsenal gun {} is not in the loaded gun packs; NPC stays unarmed", id);
            mob.getPersistentData().putString(KEY, "none");
            return;
        }
        ItemStack stack = GunItemBuilder.create().setId(id).setAmmoCount(magazine(index.get()))
                .setAmmoInBarrel(true).build();
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
        mob.setDropChance(EquipmentSlot.MAINHAND, DROP_CHANCE);
    }

    private static int magazine(CommonGunIndex index) { return Math.max(1, index.getGunData().getAmmoAmount()); }

    static double range(ItemStack gun) {
        IGun iGun = IGun.getIGunOrNull(gun);
        String type = iGun == null ? "" : TimelessAPI.getCommonGunIndex(iGun.getGunId(gun)).map(CommonGunIndex::getType).orElse("");
        return switch (type) {
            case "rifle", "sniper", "mg" -> 28;
            case "smg" -> 20;
            default -> 16; // pistols and shotguns
        };
    }

    /** Dropped guns never carry the NPC's loaded magazine. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void drops(LivingDropsEvent event) {
        if (arsenal(event.getEntity()) == null) return;
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun == null) continue;
            gun.setCurrentAmmoCount(stack, 0);
            gun.setBulletInBarrel(stack, false);
        }
    }

    /** NPC bullets: no friendly fire, and half damage against players they are allowed to shoot. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void bulletHit(LivingHurtEvent event) {
        var direct = event.getSource().getDirectEntity();
        if (direct == null || !(event.getSource().getEntity() instanceof Mob shooter) || direct == shooter) return;
        var directId = ForgeRegistries.ENTITY_TYPES.getKey(direct.getType());
        if (directId == null || !"tacz".equals(directId.getNamespace()) || arsenal(shooter) == null) return;
        LivingEntity victim = event.getEntity();
        if (sameSide(shooter, victim)) {
            event.setCanceled(true);
            return;
        }
        if (victim instanceof ServerPlayer player) {
            if (NpcIdentity.role(shooter) != null && !"hunter".equals(NpcIdentity.role(shooter))
                    && !NpcHostility.hostileTo(shooter, player)) event.setCanceled(true);
            else event.setAmount(event.getAmount() * PLAYER_DAMAGE);
        }
    }

    static boolean sameSide(LivingEntity shooter, LivingEntity victim) {
        String kind = HumanEncounters.kind(shooter);
        if (kind != null) return kind.equals(HumanEncounters.kind(victim));
        String role = NpcIdentity.role(shooter), victimRole = NpcIdentity.role(victim);
        if (role == null || victimRole == null) return false;
        return "hunter".equals(role) ? "hunter".equals(victimRole) : NpcIdentity.faction(shooter).equals(NpcIdentity.faction(victim));
    }

    /** Stands and fires in short, inaccurate bursts at a visible target; melee takes over up close. */
    static final class GunGoal extends Goal {
        private final Mob mob;
        private final float spread;
        private int burst, pause, seen;

        GunGoal(Mob mob, float spread) {
            this.mob = mob;
            this.spread = spread;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        private boolean ready() {
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isAlive() || !armed(mob) || HumanSurrender.surrendered(mob)) return false;
            double distance = mob.distanceTo(target);
            return distance > 3.5 && distance <= range(mob.getMainHandItem());
        }

        @Override public boolean canUse() { return ready() && mob.getSensing().hasLineOfSight(mob.getTarget()); }

        @Override
        public boolean canContinueToUse() {
            if (!ready()) return false;
            seen = mob.getSensing().hasLineOfSight(mob.getTarget()) ? 0 : seen + 1;
            return seen < 40; // give up after two seconds without a line of fire
        }

        @Override
        public void start() {
            seen = 0;
            pause = 10 + mob.getRandom().nextInt(10); // a moment to raise the weapon
            burst = 0;
            IGunOperator operator = IGunOperator.fromLivingEntity(mob);
            operator.draw(mob::getMainHandItem);
            operator.aim(true);
        }

        @Override
        public void stop() {
            IGunOperator.fromLivingEntity(mob).aim(false);
            mob.getNavigation().stop();
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void tick() {
            LivingEntity target = mob.getTarget();
            if (target == null) return;
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(target, 30, 30);
            if (pause > 0 || seen > 0) {
                pause--;
                return;
            }
            IGunOperator operator = IGunOperator.fromLivingEntity(mob);
            ItemStack stack = mob.getMainHandItem();
            Vec3 from = mob.getEyePosition(), to = target.getEyePosition().subtract(0, 0.35, 0);
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            float error = spread * (1 + (float) mob.distanceTo(target) / 32f);
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f + (float) mob.getRandom().nextGaussian() * error;
            float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG)
                    + (float) mob.getRandom().nextGaussian() * error;
            ShootResult result = operator.shoot(() -> pitch, () -> yaw);
            switch (result) {
                case SUCCESS -> {
                    if (burst <= 0) burst = automatic(stack) ? 3 + mob.getRandom().nextInt(3) : 1 + mob.getRandom().nextInt(2);
                    if (--burst <= 0) pause = 20 + mob.getRandom().nextInt(25);
                }
                case NO_AMMO -> reload(stack);
                case NOT_DRAW -> operator.draw(mob::getMainHandItem);
                case NEED_BOLT -> operator.bolt();
                default -> { }
            }
        }

        private boolean automatic(ItemStack stack) {
            IGun gun = IGun.getIGunOrNull(stack);
            return gun != null && gun.getFireMode(stack) == com.tacz.guns.api.item.gun.FireMode.AUTO;
        }

        /** NPCs carry no ammo items; a reload is a pause, then a full magazine. */
        private void reload(ItemStack stack) {
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun == null) return;
            TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).ifPresent(index -> gun.setCurrentAmmoCount(stack, magazine(index)));
            gun.setBulletInBarrel(stack, true);
            pause = 50;
        }
    }
}
