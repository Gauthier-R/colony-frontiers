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
 *   Calcul mathématique — avance buildIndex selon ticks écoulés + tier initial forcé.
 *   N'affecte PAS physicalBuildIndex.
 *
 * ONLINE (triggerOnlineCatchup) :
 *   Appelé depuis onServerTick (une fois par cooldown).
 *   Place physiquement UN bâtiment du buildOrder si physicalBuildIndex < buildIndex.
 *   Le buildOrder commence à l'index 1 (index 0 = townhall, déjà posé par AiCitySpawner).
 *
 * Règle fondamentale :
 *   townhall (index 0) est toujours sauté par triggerOnlineCatchup.
 *   Il est posé une seule fois par AiCitySpawner.materializeColony.
 *   On ne passe jamais par findBuildingByType pour le townhall.
 */
public class HybridEvolutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Evolution");

    /** 2 jours in-game par bâtiment en mode offline. */
    private static final long TICKS_PER_BUILD = 48_000L;

    // ── OFFLINE ───────────────────────────────────────────────────────────

    public static void applyOfflineProgress(ServerLevel level, AiCityData data, IColony colony) {
        long now     = level.getGameTime();
        long elapsed = now - data.lastEvolutionTick;

        List<String> order  = data.archetype.buildOrder;
        int          maxIdx = order.size();

        // Tier initial : force un buildIndex minimum
        int targetIdx = tierToMinIndex(data.initialTier, maxIdx);

        int gained = (elapsed > 0) ? (int) Math.min(elapsed / TICKS_PER_BUILD,
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
     * Place UN bâtiment physique si physicalBuildIndex < buildIndex.
     * Retourne true si un bâtiment a été traité (pour déclencher le cooldown).
     */
    public static boolean triggerOnlineCatchup(ServerLevel level, AiCityData data,
                                                IColony colony) {
        // Rattrapage du temps écoulé depuis la dernière visite
        applyOfflineProgress(level, data, colony);

        List<String> order = data.archetype.buildOrder;

        // L'index 0 est toujours "townhall" — déjà posé, on commence à 1
        if (data.physicalBuildIndex < 1) data.physicalBuildIndex = 1;

        if (data.physicalBuildIndex >= data.buildIndex
                || data.physicalBuildIndex >= order.size()) {
            return false; // Tout est à jour
        }

        String buildingId = order.get(data.physicalBuildIndex);

        // Skip sécurité : ne jamais re-poser le townhall
        if (buildingId.equals("townhall")) {
            data.physicalBuildIndex++;
            return true;
        }

        // Vérifie si le bâtiment existe déjà en monde (bloc hut présent)
        IBuilding existing = findBuildingByType(colony, buildingId);

        if (existing != null) {
            // Bloc déjà posé — vérifier si upgrade nécessaire
            int targetLevel = computeTargetLevel(data.physicalBuildIndex,
                    data.buildIndex, order.size());
            if (existing.getBuildingLevel() < targetLevel
                    && existing.getBuildingLevel() < existing.getMaxBuildingLevel()) {
                try {
                    existing.requestUpgrade(null, existing.getPosition());
                    LOG.info("[CF:Evolution] UPGRADE colonyId={} building={} level→{}",
                            data.colonyId, buildingId, existing.getBuildingLevel() + 1);
                    CfLogger.log("ONLINE_UPGRADE colonyId={} building={} newLevel={}",
                            data.colonyId, buildingId, existing.getBuildingLevel() + 1);
                } catch (Exception e) {
                    LOG.warn("[CF:Evolution] requestUpgrade échoué {}: {}", buildingId, e.getMessage());
                }
            }
            data.physicalBuildIndex++;
            return true;
        }

        // Bâtiment absent — le placer physiquement
        boolean placed = AiBlueprintPlacer.placeBuilding(
                level, data.center, buildingId, data.physicalBuildIndex, colony);
        if (placed) {
            data.physicalBuildIndex++;
            LOG.info("[CF:Evolution] PLACE colonyId={} building={} slot={}",
                    data.colonyId, buildingId, data.physicalBuildIndex - 1);
        }
        return placed;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

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
            Object manager = null;
            for (String m : new String[]{
                    "getServerBuildingManager", "getBuildingManager"}) {
                try {
                    manager = colony.getClass().getMethod(m).invoke(colony);
                    if (manager != null) break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (manager == null) return null;

            Map<?, ?> buildings = (Map<?, ?>) manager.getClass()
                    .getMethod("getBuildings").invoke(manager);
            for (Object b : buildings.values()) {
                if (!(b instanceof IBuilding building)) continue;
                try {
                    Object entry = building.getClass()
                            .getMethod("getBuildingRegistryEntry").invoke(building);
                    Object rl    = entry.getClass().getMethod("getKey").invoke(entry);
                    String path  = (String) rl.getClass().getMethod("getPath").invoke(rl);
                    if (path.equals(buildingTypeId)) return building;
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
