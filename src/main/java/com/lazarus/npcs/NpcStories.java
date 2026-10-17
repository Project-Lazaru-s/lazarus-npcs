package com.lazarus.npcs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/** Five bounded, persistent story encounters. Notes can be added to existing loot tables. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
final class NpcStories {
    private static final String DATA = "LazarusStories";
    private static final long DEADLINE = 12_000L;
    private static final Story[] STORIES = {
            new Story("clinic", "The Last Clinic", "The clinic's last medic left a marked emergency kit outside the evacuation route. A second note says not everyone boarded the truck.",
                    "The clinic ran out of medicine before it ran out of patients. One medic stayed behind with the remaining bandages.",
                    "marbledsfirstaid:bandages", "apocalypsenow:canned_water", "haven"),
            new Story("convoy", "Broken Convoy", "The convoy's final log mentions a vehicle failure and a fallback cache. No further radio check-in was recorded.",
                    "The soldiers abandoned the vehicle to draw infected away from the road. Their spare kit remained at the fallback point.",
                    "apocalypsenow:army_helmet", "minecraft:iron_ingot", "military"),
            new Story("market", "The Market Ledger", "A shopkeeper split the last food shipment and hid one crate. The ledger lists a rendezvous that never happened.",
                    "The family left with what they could carry. This crate was meant for anyone who reached the rendezvous later.",
                    "apocalypsenow:canned_food", "apocalypsenow:canned_water", "rovers"),
            new Story("ranger", "Ranger's Trail", "A hunter marked a supply cache along an old trail. The final mark warns that the route is no longer quiet.",
                    "The hunter used this place between trips into the city. Fresh tracks suggest someone still follows the route.",
                    "apocalypsenow:canned_food", "minecraft:arrow", "independent"),
            new Story("shelter", "Shelter Twelve", "Shelter Twelve sent a request for water and bandages. The reply never arrived, but the shelter's cache was marked.",
                    "The shelter doors were shut after the last supply run. The people inside left a record for anyone who found their cache.",
                    "legendarysurvivaloverhaul:bandage", "apocalypsenow:canned_food", "wardens")
    };

    private record Story(String id, String title, String clue, String ending,
                         String reward, String supply, String faction) {}

    private NpcStories() {}

    static String summary(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA);
        StringBuilder result = new StringBuilder();
        for (Story story : STORIES) {
            CompoundTag state = data.getCompound(story.id);
            if (!state.contains("Stage")) continue;
            if (!result.isEmpty()) result.append('\n');
            int stage = state.getInt("Stage");
            result.append(story.title).append(" | ");
            if (stage == 3) result.append("Completed");
            else if (stage == 2) result.append("Site found");
            else result.append("Search near X ").append(Math.round(state.getInt("X") / 25f) * 25)
                    .append(", Z ").append(Math.round(state.getInt("Z") / 25f) * 25);
        }
        return result.isEmpty() ? "No stories discovered. Read notes found while scavenging." : result.toString();
    }

    static ItemStack note(String id, boolean ending) {
        Story story = find(id);
        if (story == null) return ItemStack.EMPTY;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", ending ? story.title + " - Record" : story.title);
        tag.putString("author", "Project Lazarus");
        tag.putString(ending ? "LazarusStoryEnd" : "LazarusStory", id);
        ListTag pages = new ListTag();
        pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(ending
                ? story.ending
                : story.clue + " Open this note to record an approximate location in your journal."))));
        tag.put("pages", pages);
        return book;
    }

    private static Story find(String id) {
        for (Story story : STORIES) if (story.id.equals(id)) return story;
        return null;
    }

    @SubscribeEvent
    static void read(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getItemStack().is(Items.WRITTEN_BOOK)) return;
        CompoundTag tag = event.getItemStack().getTag();
        if (tag == null) return;
        if (tag.contains("LazarusStoryEnd")) complete(player, tag.getString("LazarusStoryEnd"));
        else if (tag.contains("LazarusStory")) start(player, tag.getString("LazarusStory"));
    }

    static boolean start(ServerPlayer player, String id) {
        Story story = find(id);
        if (story == null || player.level() != player.server.overworld()) return false;
        CompoundTag data = player.getPersistentData().getCompound(DATA);
        if (data.getCompound(id).contains("Stage")) return false;
        ServerLevel level = player.serverLevel();
        BlockPos site = existingCache(player);
        boolean created = site == null;
        if (created) site = safeSite(player);
        if (site == null) {
            player.displayClientMessage(Component.literal("No suitable site nearby. Read the note again elsewhere."), true);
            return false;
        }
        // Never replace an existing block or a player's container.
        if (created) {
            if (!level.getBlockState(site).isAir()) return false;
            level.setBlock(site, Blocks.CHEST.defaultBlockState(), 3);
        }
        if (!(level.getBlockEntity(site) instanceof ChestBlockEntity chest)) {
            if (created) level.removeBlock(site, false);
            return false;
        }
        if (created) chest.setCustomName(Component.literal(story.title));
        if (!addToChest(chest, note(id, true))) return false;
        chest.setChanged();
        CompoundTag state = new CompoundTag();
        state.putInt("Stage", 1);
        state.putInt("X", site.getX());
        state.putInt("Y", site.getY());
        state.putInt("Z", site.getZ());
        state.putLong("FoundAt", level.getGameTime());
        state.putLong("FoundDayTime", level.getDayTime());
        data.put(id, state);
        player.getPersistentData().put(DATA, data);
        player.displayClientMessage(Component.literal("Journal updated: " + story.title + ". Search near X "
                + Math.round(site.getX() / 25f) * 25 + ", Z " + Math.round(site.getZ() / 25f) * 25 + "."), false);
        return true;
    }

    private static BlockPos safeSite(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        var random = level.random;
        var look = player.getViewVector(1);
        for (int attempt = 0; attempt < 80; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2, distance = 72 + random.nextInt(41);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (look.x * Math.cos(angle) + look.z * Math.sin(angle) > 0.25) continue;
            BlockPos ground = new BlockPos(x, 64, z);
            if (!level.hasChunkAt(ground)) continue;
            BlockPos site = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ground);
            if (!level.getBlockState(site).isAir() || !level.getBlockState(site.above()).isAir()) continue;
            var below = level.getBlockState(site.below());
            if (!(below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT) || below.is(Blocks.STONE)
                    || below.is(Blocks.GRAVEL) || below.is(Blocks.COARSE_DIRT) || below.is(Blocks.SAND))) continue;
            boolean occupied = false;
            for (ServerPlayer other : level.players()) {
                if (other.blockPosition().distSqr(site) < 48 * 48) { occupied = true; break; }
            }
            if (occupied) continue;
            for (int dx = -3; dx <= 3 && !occupied; dx++) for (int dz = -3; dz <= 3; dz++) {
                BlockPos near = site.offset(dx, 0, dz);
                if (!level.hasChunkAt(near) || level.getBlockEntity(near) != null
                        || level.getBlockEntity(near.below()) != null) { occupied = true; break; }
            }
            if (occupied) continue;
            return site;
        }
        return null;
    }

    private static BlockPos existingCache(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        var look = player.getViewVector(1);
        for (int cx = (origin.getX() >> 4) - 7; cx <= (origin.getX() >> 4) + 7; cx++)
            for (int cz = (origin.getZ() >> 4) - 7; cz <= (origin.getZ() >> 4) + 7; cz++) {
                BlockPos probe = new BlockPos(cx << 4, 64, cz << 4);
                if (!level.hasChunkAt(probe)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof ChestBlockEntity chest)
                            || !chest.saveWithoutMetadata().contains("LootTable")) continue;
                    BlockPos pos = entry.getKey();
                    double distance = origin.distSqr(pos);
                    if (distance < 72 * 72 || distance > 112 * 112) continue;
                    double dx = pos.getX() - player.getX(), dz = pos.getZ() - player.getZ();
                    if (look.x * dx + look.z * dz > .25 * Math.sqrt(dx * dx + dz * dz)) continue;
                    boolean occupied = false;
                    for (ServerPlayer other : level.players())
                        if (other.blockPosition().distSqr(pos) < 48 * 48) { occupied = true; break; }
                    if (!occupied) for (int slot = 0; slot < chest.getContainerSize(); slot++)
                        if (chest.getItem(slot).isEmpty()) return pos;
                }
            }
        return null;
    }

    private static boolean addToChest(ChestBlockEntity chest, ItemStack stack) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (!chest.getItem(slot).isEmpty()) continue;
            chest.setItem(slot, stack);
            chest.setChanged();
            return true;
        }
        return false;
    }

    @SubscribeEvent
    static void approach(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || player.tickCount % 40 != 0 || player.level() != player.server.overworld()) return;
        CompoundTag data = player.getPersistentData().getCompound(DATA);
        for (Story story : STORIES) {
            CompoundTag state = data.getCompound(story.id);
            int stage = state.getInt("Stage");
            if (stage != 1 && stage != 2) continue;
            BlockPos site = new BlockPos(state.getInt("X"), state.getInt("Y"), state.getInt("Z"));
            if (stage == 2) {
                if (player.blockPosition().distSqr(site) <= 5 * 5) complete(player, story.id);
                continue;
            }
            if (player.blockPosition().distSqr(site) > 48 * 48) continue;
            long elapsed = Math.max(player.serverLevel().getGameTime() - state.getLong("FoundAt"),
                    player.serverLevel().getDayTime() - state.getLong("FoundDayTime"));
            boolean early = elapsed <= DEADLINE;
            state.putInt("Stage", 2);
            state.putBoolean("Early", early);
            data.put(story.id, state);
            player.getPersistentData().put(DATA, data);
            if (player.serverLevel().getBlockEntity(site) instanceof ChestBlockEntity chest) {
                addToChest(chest, item(story.reward, early ? 2 : 1));
                addToChest(chest, item(story.supply, early ? 4 : 2));
            }
            if (early) {
                String role = switch (story.id) {
                    case "clinic" -> "military_medic";
                    case "convoy" -> "military_01";
                    case "ranger" -> "hunter";
                    default -> "survivor_1";
                };
                spawn(player.serverLevel(), site, "apocalypsenow:" + role, story.title + " survivor", 1);
            } else {
                spawn(player.serverLevel(), site, "apocalypsenow:walker_1", null, 2);
            }
            player.displayClientMessage(Component.literal(early
                    ? "You reached " + story.title + " in time. Someone is still here; search the cache."
                    : "You reached " + story.title + " too late. Infected found the site first."), false);
        }
    }

    private static ItemStack item(String id, int count) {
        var item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    private static void spawn(ServerLevel level, BlockPos site, String id, String name, int count) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null) return;
        for (int i = 0; i < count; i++) {
            BlockPos pos = site.offset(i == 0 ? 2 : -2, 0, 1);
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
            var entity = type.create(level);
            if (entity == null) return;
            entity.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                    level.random.nextFloat() * 360, 0);
            if (entity instanceof Mob mob) mob.setPersistenceRequired();
            if (name != null) entity.setCustomName(Component.literal(name));
            level.addFreshEntity(entity);
        }
    }

    private static void complete(ServerPlayer player, String id) {
        Story story = find(id);
        if (story == null) return;
        CompoundTag data = player.getPersistentData().getCompound(DATA);
        CompoundTag state = data.getCompound(id);
        if (state.getInt("Stage") != 2 || player.blockPosition().distSqr(new BlockPos(
                state.getInt("X"), state.getInt("Y"), state.getInt("Z"))) > 32 * 32) return;
        state.putInt("Stage", 3);
        data.put(id, state);
        player.getPersistentData().put(DATA, data);
        if (state.getBoolean("Early") && !"independent".equals(story.faction))
            NpcReputation.adjust(player, story.faction, 2);
        player.displayClientMessage(Component.literal("Story completed: " + story.title
                + (state.getBoolean("Early") ? " (arrived in time)" : " (arrived too late)")), false);
    }

    @SubscribeEvent
    static void keepOnDeath(PlayerEvent.Clone event) {
        if (event.isWasDeath()) event.getEntity().getPersistentData().put(DATA,
                event.getOriginal().getPersistentData().getCompound(DATA).copy());
    }
}
