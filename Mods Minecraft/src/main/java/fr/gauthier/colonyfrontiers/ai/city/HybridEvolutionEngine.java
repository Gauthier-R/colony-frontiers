package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Moteur d'évolution hybride (GDD Module 1).
 *
 * OFFLINE (applyOfflineProgress) :
 *   Calcul mathématique pur — avance buildIndex selon le temps écoulé.
 *   Aucune entité, aucun placement. Applique aussi le tier initial forcé.
 *   Appelé à la matérialisation et à chaque catchup.
 *
 * ONLINE (triggerOnlineCatchup) :
 *   Appelé quand un joueur charge les chunks de la cité.
 *   1. Rattrapage offline.
 *   2. Pour chaque bâtiment du buildOrder jusqu'au buildIndex :
 *      - Si absent → place le bloc hut via AiBlueprintPlacer → MineColonies
 *        détecte le bloc et assigne automatiquement un Builder.
 *      - Si présent mais niveau < target → requestUpgrade().
 *
 * RÈGLE ANTI-LAG :
 *   triggerOnlineCatchup ne place qu'UN bâtiment par appel.
 *   L'EventHandler l'appelle une fois toutes les 20 ticks max.
 */
public class HybridEvolutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Evolution");

    /** 2 jours in-game (48000 ticks) par bâtiment en mode offline. */
    private static final long TICKS_PER_BUILD = 48_000L;

    // ── OFFLINE ───────────────────────────────────────────────────────────

    public static void applyOfflineProgress(ServerLevel level, AiCityData data, IColony colony) {
        long now     = level.getGameTime();
        long elapsed = now - data.lastEvolutionTick;

        List<String> order  = data.archetype.buildOrder;
        int          maxIdx = order.size();

        int targetIdx = tierToMinIndex(data.initialTier, maxIdx);
        int gained    = (elapsed > 0) ? (int) Math.min(elapsed / TICKS_PER_BUILD,
                                                         maxIdx - data.buildIndex) : 0;
        int newIdx = Math.max(data.buildIndex + gained, targetIdx);
        newIdx = Math.min(newIdx, maxIdx);

        if (newIdx > data.buildIndex) {
            LOG.info("[CF:Evolution] OFFLINE colonyId={} builds+{} idx {}→{} tier={}",
                    data.colonyId, newIdx - data.buildIndex,
                    data.buildIndex, newIdx, computeTier(newIdx, maxIdx));
            CfLogger.log("OFFLINE_PROGRESS colonyId={} builds+{} idx={}→{} tier={}",
                    data.colonyId, newIdx - data.buildIndex,
                    data.buildIndex, newIdx, computeTier(newIdx, maxIdx));
        }

        data.buildIndex        = newIdx;
        data.currentTier       = computeTier(newIdx, maxIdx);
        data.lastEvolutionTick = now;
    }

    // ── ONLINE ────────────────────────────────────────────────────────────

    /**
     * Déclenche le rattrapage + place UN bâtiment si nécessaire.
     * Retourne true si un bâtiment a été placé/upgradé (pour réinitialiser le cooldown).
     */
    public static boolean triggerOnlineCatchup(ServerLevel level, AiCityData data,
                                                IColony colony) {
        applyOfflineProgress(level, data, colony);

        List<String> order = data.archetype.buildOrder;
        if (data.buildIndex <= 0 || order.isEmpty()) return false;

        // Place les bâtiments jusqu'au buildIndex (un par appel max)
        for (int i = 0; i < data.buildIndex && i < order.size(); i++) {
            String buildingId = order.get(i);
            IBuilding existing = findBuildingByType(colony, buildingId);

            int targetLevel = computeTargetLevel(i, data.buildIndex, order.size());

            if (existing == null) {
                // Bâtiment absent — place le bloc hut physique
                boolean placed = AiBlueprintPlacer.placeBuilding(
                        level, data.center, buildingId, i, colony);
                if (placed) {
                    LOG.info("[CF:Evolution] ONLINE PLACE colonyId={} building={} slot={}",
                            data.colonyId, buildingId, i);
                    return true; // Un seul par appel
                }
            } else if (existing.getBuildingLevel() < targetLevel
                    && existing.getBuildingLevel() < existing.getMaxBuildingLevel()) {
                // Bâtiment existant à un niveau insuffisant — demander upgrade
                try {
                    existing.requestUpgrade(null, existing.getPosition());
                    LOG.info("[CF:Evolution] ONLINE UPGRADE colonyId={} building={} level→{}",
                            data.colonyId, buildingId, existing.getBuildingLevel() + 1);
                    CfLogger.log("ONLINE_UPGRADE colonyId={} building={} newLevel={}",
                            data.colonyId, buildingId, existing.getBuildingLevel() + 1);
                    return true;
                } catch (Exception e) {
                    LOG.warn("[CF:Evolution] requestUpgrade échoué {}: {}", buildingId, e.getMessage());
                }
            }
        }
        return false;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    /**
     * Niveau cible d'un bâtiment selon sa position dans le buildOrder.
     * Les bâtiments placés tôt (< 25%) sont éligibles au niveau max.
     * Les bâtiments récents (> 75% du buildIndex actuel) commencent au niveau 1.
     */
    private static int computeTargetLevel(int slotIndex, int buildIndex, int maxIndex) {
        if (buildIndex <= 0) return 1;
        float relativeAge = 1.0f - ((float) slotIndex / buildIndex);
        if (relativeAge > 0.75f) return 5;
        if (relativeAge > 0.50f) return 3;
        if (relativeAge > 0.25f) return 2;
        return 1;
    }

    @SuppressWarnings("unchecked")
    static IBuilding findBuildingByType(IColony colony, String buildingTypeId) {
        try {
            // Réflexion pour éviter les erreurs compile sur des méthodes non confirmées
            Object manager = null;
            for (String methodName : new String[]{
                    "getServerBuildingManager", "getBuildingManager", "getBuildingDataManager"}) {
                try {
                    manager = colony.getClass().getMethod(methodName).invoke(colony);
                    if (manager != null) break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (manager == null) return null;

            Map<?, ?> buildings = (Map<?, ?>) manager.getClass()
                    .getMethod("getBuildings").invoke(manager);
            for (Object b : buildings.values()) {
                if (!(b instanceof IBuilding building)) continue;
                try {
                    String key = building.getBuildingRegistryEntry().getKey().getPath();
                    if (key.equals(buildingTypeId)) return building;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOG.debug("[CF:Evolution] findBuildingByType: {}", e.getMessage());
        }
        return null;
    }

    static int computeTier(int buildIndex, int maxIndex) {
        if (maxIndex <= 0) return 1;
        float p = (float) buildIndex / maxIndex;
        if (p >= 0.75f) return 4;
        if (p >= 0.50f) return 3;
        if (p >= 0.25f) return 2;
        return 1;
    }

    private static int tierToMinIndex(int tier, int maxIndex) {
        return switch (tier) {
            case 4 -> (int)(maxIndex * 0.75f);
            case 3 -> (int)(maxIndex * 0.50f);
            case 2 -> (int)(maxIndex * 0.25f);
            default -> 0;
        };
    }
}
