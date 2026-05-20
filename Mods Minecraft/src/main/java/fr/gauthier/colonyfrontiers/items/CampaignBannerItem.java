package fr.gauthier.colonyfrontiers.items;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CampaignBannerItem extends Item {

    public CampaignBannerItem(Properties properties) {
        super(properties);
    }

    /** Effet d'enchantement (brillance violette) quand au moins un garde suit. */
    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.getBoolean("HasFollowers")) return true;
        return super.isFoil(stack);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @Nullable Level level,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        tooltip.add(Component.literal("").withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal("◆ Contrôle du régiment")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        tooltip.add(Component.literal(" Clic droit ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("sur un Garde → Enrôler / Renvoyer")
                        .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal(" Shift + Clic droit ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("(vide) → Rassembler tous (30 blocs) / Dissoudre")
                        .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal("").withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal("◆ Ordres tactiques")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

        tooltip.add(Component.literal(" Clic gauche ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("sur un ennemi → Focus Fire sur cette cible")
                        .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal(" Shift + Clic gauche ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("sur un bloc → Poste de garde (toggle)")
                        .withStyle(ChatFormatting.WHITE)));

        tooltip.add(Component.literal("").withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal("◆ Notes")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        tooltip.add(Component.literal(" Les gardes postés sont indiqués par un anneau")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(" de flammes bleues au sol.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(" Un garde affamé (faim = 0) ne peut pas combattre.")
                .withStyle(ChatFormatting.GRAY));

        super.appendHoverText(stack, level, tooltip, flag);
    }
}
