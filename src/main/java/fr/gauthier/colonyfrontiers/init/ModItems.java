package fr.gauthier.colonyfrontiers.init;

import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.items.CampaignBannerItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ColonyFrontiers.MODID);

    public static final RegistryObject<Item> CAMPAIGN_BANNER = ITEMS.register(
            "campaign_banner",
            () -> new CampaignBannerItem(new Item.Properties()
                    .stacksTo(1))
    );
}
