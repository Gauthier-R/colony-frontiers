package fr.gauthier.colonyfrontiers.items;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class CampaignBannerItem extends Item {

    public CampaignBannerItem(Properties properties) {
        super(properties);
    }

    /**
     * Active dynamiquement l'effet de surbrillance violette si au moins
     * un garde suit le commandant (cache NBT géré par PlayerTickEvent).
     */
    @Override
    public boolean isFoil(@NotNull ItemStack stack) {
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.getBoolean("HasFollowers")) {
                return true;
            }
        }
        return super.isFoil(stack);
    }

    @Override
    @SuppressWarnings("null")
    public @NotNull InteractionResultHolder<ItemStack> use(
            @NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            player.sendSystemMessage(Component.literal(
                    "§e[Aide] Clic-droit sur un garde pour le rallier. Shift+Clic pour un Appel Général / Licenciement."));
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}