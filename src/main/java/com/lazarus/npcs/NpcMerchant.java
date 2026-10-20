package com.lazarus.npcs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.annotation.Nullable;
import java.util.OptionalInt;

final class NpcMerchant implements Merchant {
    private static final String DATA_KEY = "LazarusNpcs";
    private static final long RESTOCK_TICKS = 48_000L;
    private static final int CATALOG_VERSION = 3;
    private final LivingEntity npc;
    private final String role;
    private MerchantOffers offers;
    private int[] treatments;
    private Player tradingPlayer;

    NpcMerchant(LivingEntity npc, String role) {
        this.npc = npc;
        this.role = role;
    }

    void refresh() {
        CompoundTag state = npc.getPersistentData().getCompound(DATA_KEY);
        if (offers == null) {
            if (state.contains("Offers") && state.getInt("CatalogVersion") >= 2) {
                offers = new MerchantOffers(state.getCompound("Offers"));
                treatments = state.getIntArray("Treatments");
            } else {
                TradeCatalog.CatalogOffers generated = TradeCatalog.create(role, NpcIdentity.faction(npc), NpcProfessions.get(npc),
                        npc.getUUID(), state.getInt("OfferRotation"));
                offers = generated.offers();
                treatments = generated.treatments();
                state.putInt("CatalogVersion", CATALOG_VERSION);
                state.putLong("RestockedAt", npc.level().getGameTime());
                save(state);
            }
        }
        long now = npc.level().getGameTime();
        if (now - state.getLong("RestockedAt") >= RESTOCK_TICKS) {
            int rotation = state.getInt("OfferRotation") + 1;
            TradeCatalog.CatalogOffers generated = TradeCatalog.create(role, NpcIdentity.faction(npc), NpcProfessions.get(npc),
                    npc.getUUID(), rotation);
            offers = generated.offers();
            treatments = generated.treatments();
            state.putInt("OfferRotation", rotation);
            state.putInt("CatalogVersion", CATALOG_VERSION);
            state.putLong("RestockedAt", now);
            save(state);
        }
    }

    boolean canTradeWith(Player player) {
        return npc.isAlive() && !npc.isRemoved() && npc.level() == player.level()
                && npc.distanceToSqr(player) <= 64.0;
    }

    void openTradeScreen(ServerPlayer player, Component title) {
        OptionalInt menuId = player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new SafeMerchantMenu(id, inventory, this), title));
        if (menuId.isPresent() && !getOffers().isEmpty()) {
            player.sendMerchantOffers(menuId.getAsInt(), getOffers(), 1,
                    getVillagerXp(), showProgressBar(), canRestock());
        }
    }

    void playTradeSound() {
        npc.level().playSound(null, npc.blockPosition(), getNotifyTradeSound(), SoundSource.NEUTRAL, 1f, 1f);
    }

    private void save(CompoundTag state) {
        // Discounts belong to the current player, so never save them on the shared NPC.
        CompoundTag savedOffers = offers.createTag();
        var recipes = savedOffers.getList("Recipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < recipes.size(); i++) recipes.getCompound(i).remove("specialPrice");
        state.put("Offers", savedOffers);
        state.putIntArray("Treatments", treatments);
        npc.getPersistentData().put(DATA_KEY, state);
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        tradingPlayer = player;
        if (offers != null) {
            int trust = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    ? NpcReputation.get(serverPlayer, NpcIdentity.faction(npc)) : 0;
            int discount = trust >= 30 ? 2 : trust >= 10 ? 1 : 0;
            for (MerchantOffer offer : offers) {
                offer.setSpecialPriceDiff(-Math.min(discount, offer.getBaseCostA().getCount() - 1));
            }
        }
    }

    @Override
    @Nullable
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        refresh();
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers newOffers) {
        offers = newOffers;
        save(npc.getPersistentData().getCompound(DATA_KEY));
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        int index = offers.indexOf(offer);
        if (index >= 0 && index < treatments.length && treatments[index] > 0 && tradingPlayer != null) {
            tradingPlayer.heal(treatments[index]);
            tradingPlayer.displayClientMessage(Component.translatable("lazarusnpcs.treated"), true);
        }
        if (tradingPlayer instanceof net.minecraft.server.level.ServerPlayer player) {
            NpcReputation.adjust(player, NpcIdentity.faction(npc), 1);
            NpcDialogue.action(player, npc, "trade");
        }
        save(npc.getPersistentData().getCompound(DATA_KEY));
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {}

    @Override
    public int getVillagerXp() { return 0; }

    @Override
    public void overrideXp(int xp) {}

    @Override
    public boolean showProgressBar() { return false; }

    @Override
    public SoundEvent getNotifyTradeSound() { return SoundEvents.ITEM_PICKUP; }

    @Override
    public boolean isClientSide() { return false; }
}
