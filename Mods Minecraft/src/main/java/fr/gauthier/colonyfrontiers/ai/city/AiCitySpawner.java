package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Génération et placement de cités IA (GDD Module 1).
 *
 * Responsabilités :
 *  1. Trouver une position valide (>1500 blocs de toute colonie, terrain plat).
 *  2. Créer la colonie MineColonies sous-jacente.
 *  3. Enregistrer l'AiCityData dans l'AiCityRegistry.
 *  4. Confier l'évolution initiale à HybridEvolutionEngine.
 */
public class AiCitySpawner {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Spawner");

    private static final int MIN_COLONY_DIST   = 1500;  // blocs
    private static final int FLATNESS_RADIUS   = 8;     // blocs XZ vérifiés
    private static final int MAX_HEIGHT_DELTA  = 3;     // variation de Y tolérée
    private static final int MAX_SEARCH_TRIES  = 64;    // tentatives de placement
    private static final int SEARCH_RANGE      = 3000;  // rayon de recherche autour de l'origine
    private static final int WORLD_GEN_CITIES  = 5;     // cités pré-générées au premier chargement

    // ── GÉNÉRATION INITIALE DU MONDE ─────────────────────────────────────

    /**
     * Appelé une seule fois lors du premier chargement du monde.
     * Génère WORLD_GEN_CITIES cités avec des tiers aléatoires (1–4).
     */
    public static void generateInitialCities(ServerLevel level, AiCityRegistry registry, Random rng) {
        LOG.info("[CF:Spawner] Génération initiale — {} cités demandées", WORLD_GEN_CITIES);
        int spawned = 0;
        for (int attempt = 0; attempt < WORLD_GEN_CITIES * 4 && spawned < WORLD_GEN_CITIES; attempt++) {
            int tier = 1 + rng.nextInt(4); // 1–4
            if (trySpawnCity(level, registry, rng, tier)) spawned++;
        }
        LOG.info("[CF:Spawner] Génération initiale terminée — {} cités créées", spawned);
        registry.markWorldGenDone();
    }

    // ── SPAWN DYNAMIQUE ───────────────────────────────────────────────────

    /**
     * Tentative de spawn d'une nouvelle cité Tier 1 pendant le gameplay.
     * Appelé par AiCityEventHandler lors du tick du spawn timer.
     */
    public static void trySpawnDynamicCity(ServerLevel level, AiCityRegistry registry, Random rng) {
        LOG.info("[CF:Spawner] Tentative de spawn dynamique...");
        trySpawnCity(level, registry, rng, 1);
    }

    // ── LOGIQUE CENTRALE ──────────────────────────────────────────────────

    private static boolean trySpawnCity(ServerLevel level, AiCityRegistry registry,
                                        Random rng, int tier) {
        for (int i = 0; i < MAX_SEARCH_TRIES; i++) {
            int x = (rng.nextInt(SEARCH_RANGE * 2) - SEARCH_RANGE);
            int z = (rng.nextInt(SEARCH_RANGE * 2) - SEARCH_RANGE);
            BlockPos candidate = findSurfacePos(level, x, z);
            if (candidate == null) continue;

            // Distance à toutes les colonies MineColonies existantes
            if (!isFarEnoughFromAllColonies(level, candidate)) continue;

            // Distance aux cités IA déjà enregistrées
            if (!registry.isFarEnoughFromAll(candidate, MIN_COLONY_DIST)) continue;

            // Vérification de la planéité du terrain
            if (!isFlatEnough(level, candidate)) continue;

            // Tout est bon — créer la cité
            return spawnCityAt(level, registry, rng, candidate, tier);
        }
        LOG.warn("[CF:Spawner] Impossible de trouver une position valide après {} essais",
                MAX_SEARCH_TRIES);
        return false;
    }

    // ── CRÉATION DE LA COLONIE ────────────────────────────────────────────

    private static boolean spawnCityAt(ServerLevel level, AiCityRegistry registry,
                                       Random rng, BlockPos pos, int tier) {
        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();

        // Sécurité finale — MineColonies vérifie aussi la distance
        if (!mgr.isFarEnoughFromColonies(level, pos)) {
            LOG.warn("[CF:Spawner] MineColonies refuse le placement en {}", pos);
            return false;
        }

        // Détermine style et archétype
        String structurePack = BiomeStyleMapper.getStyleFor(level, pos);
        AiColonyArchetype archetype = AiColonyArchetype.random(rng);

        // Créer la colonie MineColonies (owner=null = colonie sans joueur)
        IColony colony;
        try {
            colony = mgr.createColony(level, pos, null,
                    "IA_" + archetype.name() + "_" + pos.getX() + "_" + pos.getZ(),
                    structurePack);
        } catch (Exception e) {
            LOG.error("[CF:Spawner] Erreur lors de la création de la colonie en {}: {}", pos, e.getMessage());
            return false;
        }

        if (colony == null) {
            LOG.warn("[CF:Spawner] createColony a retourné null en {}", pos);
            return false;
        }

        colony.setStructurePack(structurePack);

        // Enregistrer la cité
        AiCityData data = new AiCityData(pos, archetype, tier);
        data.colonyId          = colony.getID();
        data.lastEvolutionTick = level.getGameTime();
        registry.addCity(data);

        // Évolution initiale selon le tier
        HybridEvolutionEngine.applyOfflineProgress(level, data, colony);

        LOG.info("[CF:Spawner] CITÉ CRÉÉE colonyId={} pos={} tier={} archetype={} style={}",
                colony.getID(), pos, tier, archetype, structurePack);
        return true;
    }

    // ── HELPERS TERRAIN ───────────────────────────────────────────────────

    /**
     * Trouve la position de surface solide à (x, z).
     * Retourne null si la colonne est toute en liquide ou en air.
     */
    static BlockPos findSurfacePos(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (y <= level.getMinBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos.below());
        if (state.liquid() || state.isAir()) return null;
        return pos;
    }

    /**
     * Vérifie que le terrain est suffisamment plat dans un carré de FLATNESS_RADIUS blocs.
     * La variation maximale de hauteur autorisée est MAX_HEIGHT_DELTA.
     */
    static boolean isFlatEnough(ServerLevel level, BlockPos center) {
        int baseY = center.getY();
        for (int dx = -FLATNESS_RADIUS; dx <= FLATNESS_RADIUS; dx += 2) {
            for (int dz = -FLATNESS_RADIUS; dz <= FLATNESS_RADIUS; dz += 2) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                        center.getX() + dx, center.getZ() + dz);
                if (Math.abs(y - baseY) > MAX_HEIGHT_DELTA) return false;
            }
        }
        return true;
    }

    /**
     * Vérifie la distance minimale par rapport à toutes les colonies MineColonies existantes.
     */
    static boolean isFarEnoughFromAllColonies(ServerLevel level, BlockPos pos) {
        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();
        // isFarEnoughFromColonies utilise en interne la distance configurée dans MineColonies,
        // qui est typiquement 512 blocs. On vérifie également notre propre seuil plus strict.
        if (!mgr.isFarEnoughFromColonies(level, pos)) return false;

        // Vérification supplémentaire avec MIN_COLONY_DIST
        IColony closest = mgr.getClosestColony(level, pos);
        if (closest == null) return true;
        return Math.sqrt(closest.getCenter().distSqr(pos)) >= MIN_COLONY_DIST;
    }
}
