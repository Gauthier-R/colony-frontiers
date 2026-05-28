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
 * Placement des cités IA (GDD Module 1).
 *
 * Distribution infinie par grille de régions :
 *   Quand un joueur entre dans une région de REGION_SIZE blocs non encore évaluée,
 *   on choisit un point aléatoire dans cette région et on tente d'y placer une cité.
 *   La région est marquée "évaluée" qu'il y ait une cité ou non.
 *   → Identique au comportement des villages vanilla : distribution infinie et homogène.
 *
 * Propriété :
 *   Les colonies IA ne sont la propriété d'aucun joueur.
 *   Elles sont créées via un UUID fixe réservé [CF-AI] qui ne correspond à aucun compte.
 *   MineColonies stocke cet UUID comme "owner" mais aucun joueur ne le reçoit jamais.
 */
public class AiCitySpawner {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Spawner");

    private static final int   FLATNESS_RADIUS  = 8;   // blocs XZ vérifiés autour du centre
    private static final int   MAX_HEIGHT_DELTA = 3;   // variation Y tolérée
    private static final int   MAX_SEARCH_TRIES = 32;  // tentatives dans la région
    private static final int   MIN_CITY_DIST    = 800; // distance min entre deux cités IA

    /**
     * Évalue une région : choisit un point et tente d'y placer une cité.
     * Appelé par AiCityEventHandler quand un joueur entre dans une région non vérifiée.
     *
     * @param regionX  coordonnée de région X (blockX / REGION_SIZE)
     * @param regionZ  coordonnée de région Z (blockZ / REGION_SIZE)
     */
    public static void evaluateRegion(ServerLevel level, AiCityRegistry registry,
                                       int regionX, int regionZ) {
        // Graine déterministe : même région = même résultat sur tout serveur avec même world seed
        long seed = level.getSeed() ^ ((long) regionX * 0x9E3779B97F4A7C15L)
                                    ^ ((long) regionZ * 0x6C62272E07BB0142L);
        Random rng = new Random(seed);

        // Marque la région comme évaluée immédiatement pour éviter les doubles tentatives
        registry.markRegionChecked(regionX, regionZ);

        if (rng.nextFloat() > AiCityRegistry.SPAWN_CHANCE) {
            LOG.debug("[CF:Spawner] région ({},{}) — pas de cité (tirage négatif)", regionX, regionZ);
            CfLogger.log("REGION_SKIP ({},{}) seed={}", regionX, regionZ, seed);
            return;
        }

        // Cherche une position valide dans la région
        int baseX = regionX * AiCityRegistry.REGION_SIZE;
        int baseZ = regionZ * AiCityRegistry.REGION_SIZE;

        for (int attempt = 0; attempt < MAX_SEARCH_TRIES; attempt++) {
            int x = baseX + rng.nextInt(AiCityRegistry.REGION_SIZE);
            int z = baseZ + rng.nextInt(AiCityRegistry.REGION_SIZE);

            BlockPos candidate = findSurfacePos(level, x, z);
            if (candidate == null) continue;
            if (!registry.isFarEnoughFromAll(candidate, MIN_CITY_DIST)) continue;
            if (!isFlatEnough(level, candidate)) continue;

            // Position valide — enregistre la cité sans créer encore la colonie MC
            // (la colonie sera créée quand un joueur sera physiquement à portée)
            AiColonyArchetype archetype = AiColonyArchetype.random(rng);
            int tier = 1 + rng.nextInt(4);

            AiCityData data = new AiCityData(candidate, archetype, tier);
            data.colonyId          = -1; // colonie MC pas encore créée
            data.lastEvolutionTick = level.getGameTime();
            registry.addCity(data);

            LOG.info("[CF:Spawner] SITE RÉSERVÉ pos={} tier={} archetype={} région=({},{})",
                    candidate, tier, archetype, regionX, regionZ);
            CfLogger.log("SITE_RESERVED pos={} tier={} archetype={} region=({},{})",
                    candidate, tier, archetype, regionX, regionZ);
            return;
        }

        LOG.warn("[CF:Spawner] région ({},{}) — aucune position valide après {} essais",
                regionX, regionZ, MAX_SEARCH_TRIES);
        CfLogger.log("REGION_NO_VALID_POS ({},{}) after {} tries", regionX, regionZ, MAX_SEARCH_TRIES);
    }

    /**
     * Crée la colonie MineColonies pour un site réservé (colonyId == -1).
     * Utilise un UUID propriétaire fixe [CF-AI] — aucun joueur réel n'est propriétaire.
     * Appelé quand un joueur entre dans le rayon de matérialisation.
     */
    public static void materializeColony(ServerLevel level, AiCityData data,
                                          AiCityRegistry registry) {
        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();

        if (!mgr.isFarEnoughFromColonies(level, data.center)) {
            LOG.warn("[CF:Spawner] matérialisation refusée par MC en {} — site supprimé", data.center);
            CfLogger.log("MATERIALIZE_REFUSED pos={} — removed", data.center);
            registry.removeCity(data);
            return;
        }

        String stylePack = BiomeStyleMapper.getStyleFor(level, data.center);
        String colonyName = "IA_" + data.archetype.name()
                + "_" + data.center.getX() + "_" + data.center.getZ();

        // Crée la colonie sans joueur propriétaire réel.
        // MineColonies accepte null comme player dans createColony — l'owner UUID
        // sera UUID(0,0) (nil UUID) ce qui ne correspond à aucun compte joueur.
        IColony colony;
        try {
            colony = mgr.createColony(level, data.center, null, colonyName, stylePack);
        } catch (Exception e) {
            LOG.error("[CF:Spawner] createColony échoué en {}: {}", data.center, e.getMessage());
            CfLogger.log("MATERIALIZE_ERROR pos={} error={}", data.center, e.getMessage());
            return;
        }

        if (colony == null) {
            LOG.warn("[CF:Spawner] createColony retourné null en {}", data.center);
            return;
        }

        colony.setStructurePack(stylePack);
        data.colonyId = colony.getID();
        data.lastEvolutionTick = level.getGameTime();
        registry.setDirty();

        // Applique l'évolution initiale basée sur le tier
        HybridEvolutionEngine.applyOfflineProgress(level, data, colony);

        LOG.info("[CF:Spawner] CITÉ CRÉÉE colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), data.center, data.currentTier, data.archetype, stylePack);
        CfLogger.log("CITY_CREATED colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), data.center, data.currentTier, data.archetype, stylePack);
    }

    // ── HELPERS TERRAIN ───────────────────────────────────────────────────

    static BlockPos findSurfacePos(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (y <= level.getMinBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(pos.below());
        if (below.liquid() || below.isAir()) return null;
        return pos;
    }

    static boolean isFlatEnough(ServerLevel level, BlockPos center) {
        int baseY = center.getY();
        for (int dx = -FLATNESS_RADIUS; dx <= FLATNESS_RADIUS; dx += 2) {
            for (int dz = -FLATNESS_RADIUS; dz <= FLATNESS_RADIUS; dz += 2) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        center.getX() + dx, center.getZ() + dz);
                if (Math.abs(y - baseY) > MAX_HEIGHT_DELTA) return false;
            }
        }
        return true;
    }
}
