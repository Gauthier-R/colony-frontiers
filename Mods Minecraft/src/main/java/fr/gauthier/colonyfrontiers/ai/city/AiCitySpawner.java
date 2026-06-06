package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Placement des cités IA — deux phases.
 *
 * Phase 1 (evaluateRegion) : réservation XZ instantanée, sans lecture terrain.
 * Phase 2 (materializeColony) : surface valide + biome sain + création colonie MC.
 *
 * Rejet de biome : océan, rivière, glace, nether → null → site rejeté ou déplacé.
 * Planéité : écart Y max 4 blocs sur rayon 8.
 */
public class AiCitySpawner {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Spawner");

    private static final int MIN_CITY_DIST    = 600;
    private static final int SURFACE_SEARCH_R = 96;
    private static final int MAX_HEIGHT_DELTA = 4;
    private static final int FLATNESS_RADIUS  = 8;

    // ── PHASE 1 : RÉSERVATION ─────────────────────────────────────────────

    public static void evaluateRegion(ServerLevel level, AiCityRegistry registry,
                                       int regionX, int regionZ) {
        long seed = level.getSeed()
                ^ ((long) regionX * 0x9E3779B97F4A7C15L)
                ^ ((long) regionZ * 0x6C62272E07BB0142L);
        Random rng = new Random(seed);

        registry.markRegionChecked(regionX, regionZ);

        if (rng.nextFloat() > AiCityRegistry.SPAWN_CHANCE) {
            LOG.debug("[CF:Spawner] région ({},{}) — tirage négatif", regionX, regionZ);
            CfLogger.log("REGION_SKIP ({},{})", regionX, regionZ);
            return;
        }

        int quarter = AiCityRegistry.REGION_SIZE / 4;
        int half    = AiCityRegistry.REGION_SIZE / 2;
        int x = regionX * AiCityRegistry.REGION_SIZE + quarter + rng.nextInt(half);
        int z = regionZ * AiCityRegistry.REGION_SIZE + quarter + rng.nextInt(half);

        BlockPos candidate = new BlockPos(x, 64, z);

        if (!registry.isFarEnoughFromAll(candidate, MIN_CITY_DIST)) {
            LOG.debug("[CF:Spawner] région ({},{}) — trop proche d'un site existant", regionX, regionZ);
            CfLogger.log("REGION_TOO_CLOSE ({},{})", regionX, regionZ);
            return;
        }

        AiColonyArchetype archetype = AiColonyArchetype.random(rng);
        int tier = 1 + rng.nextInt(4);

        AiCityData data = new AiCityData(candidate, archetype, tier);
        data.colonyId          = -1;
        data.lastEvolutionTick = level.getGameTime();
        registry.addCity(data);

        LOG.info("[CF:Spawner] SITE RÉSERVÉ pos=({},{}) tier={} archetype={} région=({},{})",
                x, z, tier, archetype, regionX, regionZ);
        CfLogger.log("SITE_RESERVED pos=({},{}) tier={} archetype={} region=({},{})",
                x, z, tier, archetype, regionX, regionZ);
    }

    // ── PHASE 2 : MATÉRIALISATION ─────────────────────────────────────────

    public static void materializeColony(ServerLevel level, AiCityData data,
                                          ServerPlayer player, AiCityRegistry registry) {
        // Cherche une surface valide (biome sain + terrain plat)
        BlockPos surface = findValidSurface(level, data.center.getX(), data.center.getZ(),
                SURFACE_SEARCH_R);

        if (surface == null) {
            LOG.warn("[CF:Spawner] aucune surface valide autour de ({},{}) — site supprimé",
                    data.center.getX(), data.center.getZ());
            CfLogger.log("MATERIALIZE_NO_SURFACE ({},{}) — removed",
                    data.center.getX(), data.center.getZ());
            registry.removeCity(data);
            return;
        }

        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();
        if (!mgr.isFarEnoughFromColonies(level, surface)) {
            LOG.warn("[CF:Spawner] MC refuse le placement en {} — site supprimé", surface);
            CfLogger.log("MATERIALIZE_MC_REFUSED pos={}", surface);
            registry.removeCity(data);
            return;
        }

        String stylePack = BiomeStyleMapper.getStyleFor(level, surface);
        if (stylePack == null) {
            // Ne devrait pas arriver (findValidSurface filtre déjà), sécurité
            stylePack = "caledonia";
        }

        String colonyName = "IA_" + data.archetype.name()
                + "_" + surface.getX() + "_" + surface.getZ();

        IColony colony;
        try {
            colony = mgr.createColony(level, surface, player, colonyName, stylePack);
        } catch (Exception e) {
            LOG.error("[CF:Spawner] createColony échoué en {}: {}", surface, e.getMessage());
            CfLogger.log("MATERIALIZE_ERROR pos={} err={}", surface, e.getMessage());
            return;
        }

        if (colony == null) {
            LOG.warn("[CF:Spawner] createColony retourné null en {}", surface);
            CfLogger.log("MATERIALIZE_NULL pos={}", surface);
            return;
        }

        colony.setStructurePack(stylePack);

        // Retire le joueur déclencheur — la colonie ne lui appartient pas
        try {
            colony.getPermissions().setPlayerRank(
                    player.getUUID(),
                    colony.getPermissions().getRankHostile(),
                    level);
        } catch (Exception e) {
            LOG.warn("[CF:Spawner] setPlayerRank hostile échoué (non bloquant): {}", e.getMessage());
        }

        data.center            = surface;
        data.colonyId          = colony.getID();
        // physicalBuildIndex = 1 : le TH (index 0) sera compté comme posé
        data.physicalBuildIndex = 1;
        data.lastEvolutionTick = level.getGameTime();
        registry.setDirty();

        // Place le bloc Town Hall à EXACTEMENT la position déclarée à createColony.
        // MineColonies associe la colonie au bloc via ses coordonnées — ils doivent coïncider.
        AiBlueprintPlacer.placeTownHall(level, surface, colony);

        // Applique l'évolution correspondant au tier initial (calcul offline)
        HybridEvolutionEngine.applyOfflineProgress(level, data, colony);

        // Spawne les citoyens initiaux (builder en priorité)
        spawnInitialCitizens(level, colony, data);

        LOG.info("[CF:Spawner] CITÉ CRÉÉE colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), surface, data.currentTier, data.archetype, stylePack);
        CfLogger.log("CITY_CREATED colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), surface, data.currentTier, data.archetype, stylePack);
    }

    // ── SPAWN CITOYENS INITIAUX ───────────────────────────────────────────

    private static void spawnInitialCitizens(ServerLevel level, IColony colony, AiCityData data) {
        try {
            // Tier 1 : 2 citoyens, Tier 2 : 4, Tier 3 : 6, Tier 4 : 8
            int count = data.currentTier * 2;
            for (int i = 0; i < count; i++) {
                colony.getCitizenManager().spawnOrCreateCitizen();
            }
            LOG.info("[CF:Spawner] {} citoyens spawnés colonyId={}", count, colony.getID());
            CfLogger.log("SPAWN_CITIZENS count={} colonyId={}", count, colony.getID());
        } catch (Exception e) {
            LOG.warn("[CF:Spawner] spawn citoyens échoué: {}", e.getMessage());
        }
    }

    // ── RECHERCHE DE SURFACE VALIDE ────────────────────────────────────────
    // Biome sain (pas océan/glace) + terrain plat + bloc solide en dessous.
    // Cherche en spirale depuis le centre.

    static BlockPos findValidSurface(ServerLevel level, int cx, int cz, int searchRadius) {
        // Centre d'abord
        BlockPos c = tryPos(level, cx, cz);
        if (c != null) return c;

        for (int r = 4; r <= searchRadius; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                BlockPos p = tryPos(level, cx + dx, cz - r);
                if (p != null) return p;
                p = tryPos(level, cx + dx, cz + r);
                if (p != null) return p;
            }
            for (int dz = -r + 4; dz <= r - 4; dz += 4) {
                BlockPos p = tryPos(level, cx - r, cz + dz);
                if (p != null) return p;
                p = tryPos(level, cx + r, cz + dz);
                if (p != null) return p;
            }
        }
        return null;
    }

    private static BlockPos tryPos(ServerLevel level, int x, int z) {
        // Vérifie biome avant de lire la heightmap (plus léger)
        BlockPos probe = new BlockPos(x, 64, z);
        String style = BiomeStyleMapper.getStyleFor(level, probe);
        if (style == null) return null; // biome rejeté

        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (y <= level.getMinBuildHeight()) return null;

        BlockPos pos = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(pos.below());
        if (below.liquid() || below.isAir()) return null;

        if (!isFlatEnough(level, pos)) return null;
        return pos;
    }

    static boolean isFlatEnough(ServerLevel level, BlockPos center) {
        int baseY = center.getY();
        for (int dx = -FLATNESS_RADIUS; dx <= FLATNESS_RADIUS; dx += 2) {
            for (int dz = -FLATNESS_RADIUS; dz <= FLATNESS_RADIUS; dz += 2) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        center.getX() + dx, center.getZ() + dz);
                if (y <= level.getMinBuildHeight()) return false;
                if (Math.abs(y - baseY) > MAX_HEIGHT_DELTA) return false;
            }
        }
        return true;
    }
}
