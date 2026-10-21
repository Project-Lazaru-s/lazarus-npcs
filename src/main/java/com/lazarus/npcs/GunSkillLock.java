package com.lazarus.npcs;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.resource.index.CommonGunIndex;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Armeiro skill tree of the Project Lazarus modpack (Puffish Skills): every TaCZ gun and attachment is built at the
 * gun smith table only after the branch of its type is learned. The nodes give player tags; GunSmithTableMenuMixin
 * asks here before the table takes any material. Ammo stays free; guns and attachments found or looted stay usable.
 */
public final class GunSkillLock {
    /** TaCZ gun type -> {player tag, node name, what the player is told he cannot build}. */
    static final Map<String, String[]> BRANCHES = Map.of(
            "pistol", new String[] {"lazarus_arma_pistola", "Pistolas", "pistolas"},
            "smg", new String[] {"lazarus_arma_smg", "Submetralhadoras", "submetralhadoras"},
            "shotgun", new String[] {"lazarus_arma_espingarda", "Espingardas", "espingardas"},
            "rifle", new String[] {"lazarus_arma_fuzil", "Fuzis", "fuzis"},
            "sniper", new String[] {"lazarus_arma_precisao", "Rifles de precisão", "rifles de precisão"},
            "mg", new String[] {"lazarus_arma_metralhadora", "Metralhadoras", "metralhadoras"},
            "rpg", new String[] {"lazarus_arma_lancador", "Lançadores", "lançadores"});

    /** TaCZ attachment type (AttachmentType name, lower case) -> {player tag, node name, what cannot be built}. */
    static final Map<String, String[]> ATTACHMENTS = Map.of(
            "grip", new String[] {"lazarus_acessorio_empunhadura", "Empunhaduras", "empunhaduras"},
            "stock", new String[] {"lazarus_acessorio_coronha", "Coronhas", "coronhas"},
            "scope", new String[] {"lazarus_acessorio_mira", "Miras", "miras"},
            "laser", new String[] {"lazarus_acessorio_laser", "Lasers", "lasers"},
            "muzzle", new String[] {"lazarus_acessorio_bocal", "Bocais", "bocais (supressores, freios e baionetas)"},
            "extended_mag", new String[] {"lazarus_acessorio_carregador", "Carregadores", "carregadores e munição especial"});

    private GunSkillLock() {}

    /** Branch for a gun type; types from other gun packs fall under Fuzis so no gun is ever free. */
    static String[] branch(String type) {
        return BRANCHES.getOrDefault(type, BRANCHES.get("rifle"));
    }

    /** Branch for an attachment type; unknown ones fall under Miras so no attachment is ever free. */
    static String[] attachmentBranch(String type) {
        return ATTACHMENTS.getOrDefault(type, ATTACHMENTS.get("scope"));
    }

    /** True when the player may craft this gun smith recipe; tells the player why not otherwise. */
    public static boolean allowed(Player player, ResourceLocation recipeId) {
        if (player.isCreative() || player.level().isClientSide()) return true;
        var recipe = player.level().getRecipeManager().byKey(recipeId).orElse(null);
        if (!(recipe instanceof GunSmithTableRecipe gunRecipe)) return true;
        ItemStack output = gunRecipe.getOutput();
        String[] branch;
        IGun gun = IGun.getIGunOrNull(output);
        IAttachment attachment = IAttachment.getIAttachmentOrNull(output);
        if (gun != null) {
            branch = branch(TimelessAPI.getCommonGunIndex(gun.getGunId(output)).map(CommonGunIndex::getType).orElse(""));
        } else if (attachment != null) {
            branch = attachmentBranch(TimelessAPI.getCommonAttachmentIndex(attachment.getAttachmentId(output))
                    .map(index -> index.getType().name().toLowerCase(java.util.Locale.ROOT)).orElse(""));
        } else {
            return true; // ammo, blocks
        }
        if (player.getTags().contains(branch[0])) return true;
        player.displayClientMessage(Component.literal("Você ainda não sabe montar " + branch[2] + " (Armeiro: " + branch[1] + ")."), true);
        player.playNotifySound(SoundEvents.VILLAGER_NO, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1f);
        return false;
    }
}
