package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Placement des cités IA — deux phases distinctes :
 *
 * Phase 1 — RÉSERVATION (evaluateRegion)
 *   Déclenchée dès qu'un chunk d'une région inconnue est chargé.
 *   Choisit une position XZ déterministe dans la région (graine = world seed × région).
 *   Aucune lecture de heightmap, aucun chunk ne doit être chargé.
 *   → Résultat immédiat, zéro risque de NullPointerException.
 *
 * Phase 2 — MATÉRIALISATION (materializeColony)
 *   Déclenchée quand un joueur entre dans le rayon de MATERIALIZE_RADIUS blocs du site.
 *   À ce moment les chunks sont chargés → on peut lire le terrain.
 *   Cherche la vraie surface, vérifie la planéité, crée la colonie MineColonies.
 *   La colonie est créée sans joueur propriétaire réel (player=null → UUID nil).
 */
public class AiCitySpawner {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Spawner");

    /** Distance minimum entre deux cités IA (blocs). */
    private static final int MIN_CITY_DIST     = 600;
    /** Rayon de recherche de surface autour du centre réservé lors de la matérialisation. */
    private static final int SURFACE_SEARCH_R  = 64;
    /** Variation de hauteur maximale tolérée pour la planéité (blocs). */
    private static final int MAX_HEIGHT_DELTA  = 4;
    /** Rayon de vérification de planéité (blocs). */
    private static final int FLATNESS_RADIUS   = 8;

    // ── PHASE 1 : RÉSERVATION ─────────────────────────────────────────────

    /**
     * Évalue une région et réserve éventuellement un site.
     * Ne lit JAMAIS le terrain — peut être appelée sur n'importe quel chunk.
     */
    public static void evaluateRegion(ServerLevel level, AiCityRegistry registry,
                                       int regionX, int regionZ) {
        // Graine déterministe — même résultat sur tout serveur avec la même world seed
        long seed = level.getSeed()
                ^ ((long) regionX * 0x9E3779B97F4A7C15L)
                ^ ((long) regionZ * 0x6C62272E07BB0142L);
        Random rng = new Random(seed);

        registry.markRegionChecked(regionX, regionZ);

        // Roll de spawn
        if (rng.nextFloat() > AiCityRegistry.SPAWN_CHANCE) {
            LOG.debug("[CF:Spawner] région ({},{}) — tirage négatif", regionX, regionZ);
            CfLogger.log("REGION_SKIP ({},{}) seed={}", regionX, regionZ, seed);
            return;
        }

        // Position XZ dans la moitié centrale de la région (évite les bords)
        int quarter = AiCityRegistry.REGION_SIZE / 4;
        int half    = AiCityRegistry.REGION_SIZE / 2;
        int baseX   = regionX * AiCityRegistry.REGION_SIZE;
        int baseZ   = regionZ * AiCityRegistry.REGION_SIZE;
        int x = baseX + quarter + rng.nextInt(half);
        int z = baseZ + quarter + rng.nextInt(half);

        // Y=64 provisoire — sera mis à jour lors de la matérialisation
        BlockPos candidate = new BlockPos(x, 64, z);

        // Distance aux cités déjà réservées/créées (comparaison XZ uniquement, Y=64 partout)
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

    /**
     * Crée la colonie MineColonies pour un site réservé.
     * Les chunks autour du site doivent être chargés (appelé quand un joueur est à proximité).
     */
    public static void materializeColony(ServerLevel level, AiCityData data,
                                          AiCityRegistry registry) {
        // Cherche la vraie surface autour du centre XZ réservé
        BlockPos surface = findBestSurface(level, data.center.getX(), data.center.getZ(),
                SURFACE_SEARCH_R);

        if (surface == null) {
            LOG.warn("[CF:Spawner] matérialisation impossible en ({},{}) — pas de surface valide",
                    data.center.getX(), data.center.getZ());
            CfLogger.log("MATERIALIZE_NO_SURFACE pos=({},{})", data.center.getX(), data.center.getZ());
            // On garde le site réservé pour une tentative ultérieure
            return;
        }

        // Mise à jour du centre avec les vraies coordonnées de surface
        data.center = surface;

        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();
        if (!mgr.isFarEnoughFromColonies(level, surface)) {
            LOG.warn("[CF:Spawner] matérialisation refusée par MC en {} — site supprimé", surface);
            CfLogger.log("MATERIALIZE_MC_REFUSED pos={}", surface);
            registry.removeCity(data);
            return;
        }

        String stylePack  = BiomeStyleMapper.getStyleFor(level, surface);
        String colonyName = "IA_" + data.archetype.name()
                + "_" + surface.getX() + "_" + surface.getZ();

        IColony colony;
        try {
            // player=null → MineColonies assigne UUID nil (0,0) comme propriétaire.
            // Aucun joueur réel n'est propriétaire de la colonie.
            colony = mgr.createColony(level, surface, null, colonyName, stylePack);
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
        data.colonyId          = colony.getID();
        data.lastEvolutionTick = level.getGameTime();
        registry.setDirty();

        HybridEvolutionEngine.applyOfflineProgress(level, data, colony);

        LOG.info("[CF:Spawner] CITÉ CRÉÉE colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), surface, data.currentTier, data.archetype, stylePack);
        CfLogger.log("CITY_CREATED colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), surface, data.currentTier, data.archetype, stylePack);
    }

    // ── HELPERS TERRAIN ───────────────────────────────────────────────────

    /**
     * Cherche la meilleure surface dans un carré de searchRadius autour de (cx, cz).
     * Teste d'abord le centre, puis des anneaux concentriques.
     * Retourne null seulement si aucune position valide dans tout le carré.
     */
    static BlockPos findBestSurface(ServerLevel level, int cx, int cz, int searchRadius) {
        // Centre d'abord
        BlockPos center = findSurfacePos(level, cx, cz);
        if (center != null && isFlatEnough(level, center)) return center;

        // Spirale en anneaux de 4 blocs
        for (int r = 4; r <= searchRadius; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                BlockPos p = findSurfacePos(level, cx + dx, cz - r);
                if (p != null && isFlatEnough(level, p)) return p;
                p = findSurfacePos(level, cx + dx, cz + r);
                if (p != null && isFlatEnough(level, p)) return p;
            }
            for (int dz = -r + 4; dz <= r - 4; dz += 4) {
                BlockPos p = findSurfacePos(level, cx - r, cz + dz);
                if (p != null && isFlatEnough(level, p)) return p;
                p = findSurfacePos(level, cx + r, cz + dz);
                if (p != null && isFlatEnough(level, p)) return p;
            }
        }
        return null;
    }

    private static BlockPos findSurfacePos(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        // getHeight retourne minBuildHeight si le chunk n'est pas chargé ou vide
        if (y <= level.getMinBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(pos.below());
        // Rejette eau, lave, air (falaises, vide)
        if (below.liquid() || below.isAir()) return null;
        return pos;
    }

    private static boolean isFlatEnough(ServerLevel level, BlockPos center) {
        int baseY = center.getY();
        for (int dx = -FLATNESS_RADIUS; dx <= FLATNESS_RADIUS; dx += 2) {
            for (int dz = -FLATNESS_RADIUS; dz <= FLATNESS_RADIUS; dz += 2) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        center.getX() + dx, center.getZ() + dz);
                if (y <= level.getMinBuildHeight()) return false; // chunk non chargé
                if (Math.abs(y - baseY) > MAX_HEIGHT_DELTA) return false;
            }
        }
        return true;
    }
}
