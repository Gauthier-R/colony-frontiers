package fr.gauthier.colonyfrontiers.ai.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Mappe le biome à la position de spawn sur un style de structure MineColonies (GDD Module 1).
 *
 * Les noms de packs correspondent aux structure packs officiels de MineColonies 1.20.1.
 * Fallback : "caledonia" (style neutre universel).
 */
public class BiomeStyleMapper {

    /**
     * Retourne le nom du structure pack MineColonies adapté au biome à pos.
     */
    public static String getStyleFor(ServerLevel level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        ResourceLocation biomeKey = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getKey(biomeHolder.value());

        if (biomeKey == null) return "caledonia";
        String path = biomeKey.getPath();

        // Désert / Badlands
        if (path.contains("desert") || path.contains("badlands") || path.contains("savanna")) {
            return "shire"; // style grès clair — le plus proche disponible
        }
        // Taïga / Enneigé
        if (path.contains("taiga") || path.contains("snowy") || path.contains("frozen")
                || path.contains("ice") || path.contains("cold")) {
            return "nordic";
        }
        // Jungle
        if (path.contains("jungle")) {
            return "caledonia";
        }
        // Marais / Mangrove
        if (path.contains("swamp") || path.contains("mangrove")) {
            return "caledonia";
        }
        // Nether (au cas où)
        if (path.contains("nether") || path.contains("basalt") || path.contains("crimson")
                || path.contains("warped") || path.contains("soul")) {
            return "caledonia";
        }
        // Océan / Côte
        if (path.contains("ocean") || path.contains("beach") || path.contains("river")) {
            return "caledonia";
        }
        // Forêt sombre
        if (path.contains("dark_forest")) {
            return "darkoak";
        }
        // Forêt standard / plaine (fallback général)
        return "caledonia";
    }
}
