package fr.gauthier.colonyfrontiers;


import fr.gauthier.colonyfrontiers.init.ModItems;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(ColonyFrontiers.MODID)
public class ColonyFrontiers {
    public static final String MODID = "colonyfrontiers";
    private static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public ColonyFrontiers() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        CfLogger.init(FMLPaths.GAMEDIR.get());
        ModItems.ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::buildCreativeModeTab);

        LOGGER.info("Colony Frontiers Initialized");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Common setup logic (registries, compatibility hooks, etc.) goes here.
    }

    private void buildCreativeModeTab(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.CAMPAIGN_BANNER);
        }
    }
}
