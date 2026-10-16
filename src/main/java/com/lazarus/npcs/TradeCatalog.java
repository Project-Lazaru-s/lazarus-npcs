package com.lazarus.npcs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

final class TradeCatalog {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("lazarus-npcs-trades.json");
    private static final java.lang.reflect.Type TYPE = new TypeToken<Map<String, List<TradeDef>>>() {}.getType();
    private static Map<String, List<TradeDef>> definitions = Map.of();

    private TradeCatalog() {}

    static void load() {
        try {
            if (!Files.exists(FILE)) {
                try (InputStream input = TradeCatalog.class.getResourceAsStream("/default_trades.json")) {
                    if (input == null) throw new IOException("Bundled trades are missing");
                    Files.copy(input, FILE);
                }
            }
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                Map<String, List<TradeDef>> parsed = GSON.fromJson(reader, TYPE);
                if (parsed == null || !parsed.keySet().containsAll(List.of("survivor", "medic", "military")))
                    throw new IOException("Trade file must contain survivor, medic, and military lists");
                Map<String, List<TradeDef>> merged = new java.util.HashMap<>(parsed);
                try (InputStream input = TradeCatalog.class.getResourceAsStream("/default_trades.json")) {
                    if (input != null) {
                        Map<String, List<TradeDef>> defaults = GSON.fromJson(
                                new InputStreamReader(input, StandardCharsets.UTF_8), TYPE);
                        for (String faction : new String[]{"haven", "rovers", "wardens", "hunter"})
                            merged.putIfAbsent(faction, defaults.getOrDefault(faction, List.of()));
                    }
                }
                definitions = merged;
            }
        } catch (Exception error) {
            LazarusNpcs.LOGGER.error("Cannot load Lazarus NPC trades from {}", FILE, error);
            try (InputStream input = TradeCatalog.class.getResourceAsStream("/default_trades.json")) {
                if (input == null) throw new IOException("Bundled trades are missing");
                definitions = GSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), TYPE);
            } catch (Exception fallbackError) {
                LazarusNpcs.LOGGER.error("Cannot load bundled Lazarus NPC trades", fallbackError);
                definitions = Map.of();
            }
        }
    }

    static CatalogOffers create(String role, String faction, UUID npcId, int rotation) {
        MerchantOffers offers = new MerchantOffers();
        List<Integer> treatments = new ArrayList<>();
        List<TradeDef> base = definitions.getOrDefault(role, List.of());
        List<TradeDef> selected = new ArrayList<>(base.subList(0, Math.min(2, base.size())));
        List<TradeDef> rotating = new ArrayList<>(base.subList(Math.min(2, base.size()), base.size()));
        long seed = npcId.getMostSignificantBits() ^ npcId.getLeastSignificantBits()
                ^ (rotation * 0x9E3779B97F4A7C15L);
        Collections.shuffle(rotating, new Random(seed));
        int limit = "military".equals(role) ? 9 : 7;
        if ("survivor".equals(role)) {
            List<TradeDef> factionOffers = new ArrayList<>(definitions.getOrDefault(faction, List.of()));
            Collections.shuffle(factionOffers, new Random(seed ^ 0x5DEECE66DL));
            selected.addAll(factionOffers.subList(0, Math.min(2, factionOffers.size())));
        }
        selected.addAll(rotating.subList(0, Math.min(rotating.size(), Math.max(0, limit - selected.size()))));
        for (TradeDef definition : selected) {
            try {
                definition.validate();
                ItemStack cost = stack(definition.cost, definition.count);
                ItemStack result = stack(definition.result, definition.resultCount);
                if (definition.resultNbt != null) {
                    try { result.setTag(TagParser.parseTag(definition.resultNbt)); }
                    catch (Exception error) { throw new IllegalArgumentException("Invalid result NBT for " + definition.result, error); }
                }
                if (definition.treatment > 0) {
                    result.setHoverName(Component.translatable("lazarusnpcs.treatment_offer", definition.treatment));
                }
                ItemStack second = definition.secondCost == null ? ItemStack.EMPTY
                        : stack(definition.secondCost, definition.secondCount);
                offers.add(new MerchantOffer(cost, second, result, definition.uses, 0, 0f));
                treatments.add(definition.treatment);
            } catch (RuntimeException error) {
                LazarusNpcs.LOGGER.warn("Skipping invalid {} trade: {}", role, error.getMessage());
            }
        }
        return new CatalogOffers(offers, treatments.stream().mapToInt(Integer::intValue).toArray());
    }

    private static ItemStack stack(String id, int count) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !ForgeRegistries.ITEMS.containsKey(key)) throw new IllegalArgumentException("Unknown item " + id);
        Item item = ForgeRegistries.ITEMS.getValue(key);
        if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown item " + id);
        ItemStack stack = new ItemStack(item, count);
        if (count > stack.getMaxStackSize()) throw new IllegalArgumentException("Excessive stack of " + id);
        return stack;
    }

    record CatalogOffers(MerchantOffers offers, int[] treatments) {}

    static final class TradeDef {
        String cost;
        int count;
        String secondCost;
        int secondCount;
        String result;
        String resultNbt;
        int resultCount;
        int uses;
        int treatment;

        void validate() {
            if (cost == null || result == null || count < 1 || resultCount < 1 || uses < 1 || uses > 16
                    || (secondCost != null && secondCount < 1) || treatment < 0 || treatment > 10)
                throw new IllegalArgumentException("Invalid quantity or missing item id");
        }
    }
}
