package fr.gauthier.colonyfrontiers.ai.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Mappe le biome d'une position sur un style de structure MineColonies.
 *
 * getStyleFor() retourne null si le biome est hostile à la construction
 * (océan, lac, glace, nether, end) — le spawner doit alors rejeter cette position.
 */
public class BiomeStyleMapper {

    /**
     * Retourne le nom du structure pack adapté au biome, ou null si le biome
     * est invalide pour une construction (eau, glace, nether...).
     */
    public static String getStyleFor(ServerLevel level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        ResourceLocation key = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getKey(holder.value());

        if (key == null) return null;
        String path = key.getPath();

        // ── Biomes rejetés (retourne null → position invalide) ─────────
        if (path.contains("ocean"))     return null;
        if (path.contains("river"))     return null;
        if (path.contains("frozen"))    return null;
        if (path.contains("ice"))       return null;
        if (path.contains("deep_cold")) return null;
        if (path.contains("nether"))    return null;
        if (path.contains("end"))       return null;
        if (path.contains("basalt"))    return null;
        if (path.contains("crimson"))   return null;
        if (path.contains("warped"))    return null;
        if (path.contains("soul"))      return null;

        // ── Biomes acceptés avec style correspondant ──────────────────
        // Désert / Savane / Badlands
        if (path.contains("desert") || path.contains("badlands")
                || path.contains("savanna") || path.contains("eroded")) {
            return "shire";
        }
        // Taïga (froide mais pas gelée)
        if (path.contains("taiga") || path.contains("snowy_slopes")
                || path.contains("jagged_peaks") || path.contains("stony_peaks")) {
            return "nordic";
        }
        // Forêt sombre
        if (path.contains("dark_forest")) {
            return "darkoak";
        }
        // Marais / Mangrove
        if (path.contains("swamp") || path.contains("mangrove")) {
            return "caledonia";
        }
        // Forêt, plaine, jungle, montagne — style universel
        return "caledonia";
    }
}
