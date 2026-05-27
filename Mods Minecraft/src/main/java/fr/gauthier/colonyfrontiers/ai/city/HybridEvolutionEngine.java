package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Moteur d'évolution hybride (GDD Module 1 — Anti-Lag).
 *
 * Chunks non chargés :
 *   applyOfflineProgress() — calcul purement mathématique basé sur le temps écoulé.
 *   Zéro CPU, zéro entité. Détermine combien de bâtiments auraient été construits
 *   depuis la dernière mise à jour et avance buildIndex en conséquence.
 *
 * Chunks chargés (joueur à proximité) :
 *   triggerOnlineCatchup() — rattrapage instantané des bâtiments complétés hors-ligne,
 *   puis activation du Builder natif MineColonies sur le prochain chantier actif.
 *
 * Tiers de développement :
 *   Tier 1 : bâtiments 0–2   du buildOrder
 *   Tier 2 : bâtiments 3–5
 *   Tier 3 : bâtiments 6–8
 *   Tier 4 : bâtiments 9–11+ (cité complète)
 */
public class HybridEvolutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Evolution");

    /**
     * Ticks (temps in-game) nécessaires pour construire un bâtiment hors-ligne.
     * 1 jour in-game = 24000 ticks. Un bâtiment prend ~2 jours.
     */
    private static final long TICKS_PER_OFFLINE_BUILD = 24000L * 2;

    /**
     * Calcule le progrès offline depuis la dernière mise à jour et
     * avance buildIndex sans aucune logique de construction réelle.
     * Appelé lors du spawn initial et lors du chargement d'une cité depuis NBT.
     */
    public static void applyOfflineProgress(ServerLevel level, AiCityData data, IColony colony) {
        long now     = level.getGameTime();
        long elapsed = now - data.lastEvolutionTick;
        if (elapsed <= 0) return;

        List<String> buildOrder = data.archetype.buildOrder;
        int maxIndex = buildOrder.size();

        // Combien de bâtiments auraient été construits pendant ce temps
        int buildsCompleted = (int) (elapsed / TICKS_PER_OFFLINE_BUILD);
        if (buildsCompleted <= 0) {
            data.lastEvolutionTick = now;
            return;
        }

        int oldIndex = data.buildIndex;
        data.buildIndex = Math.min(data.buildIndex + buildsCompleted, maxIndex);
        data.currentTier = computeTier(data.buildIndex, maxIndex);
        data.lastEvolutionTick = now;

        LOG.info("[CF:Evolution] OFFLINE colonyId={} builds+{} index {}→{} tier={}",
                data.colonyId, buildsCompleted, oldIndex, data.buildIndex, data.currentTier);

        // Pour une cité au Tier 1-4 initial, on applique le tier de départ en forcant l'index
        int targetIndex = tierToMinBuildIndex(data.initialTier, maxIndex);
        if (data.buildIndex < targetIndex) {
            data.buildIndex = targetIndex;
            data.currentTier = data.initialTier;
            LOG.info("[CF:Evolution] tier initial forcé colonyId={} index={} tier={}",
                    data.colonyId, data.buildIndex, data.currentTier);
        }
    }

    /**
     * Déclenché quand un joueur charge les chunks autour d'une cité.
     * 1. Rattrapage instantané des bâtiments complétés hors-ligne
     *    (via applyOfflineProgress).
     * 2. Demande au Builder MineColonies de construire le prochain bâtiment
     *    du buildOrder si la colonie a un Builder disponible.
     */
    public static void triggerOnlineCatchup(ServerLevel level, AiCityData data, IColony colony) {
        // Rattrapage mathématique d'abord
        applyOfflineProgress(level, data, colony);

        List<String> buildOrder = data.archetype.buildOrder;
        if (data.buildIndex >= buildOrder.size()) {
            LOG.debug("[CF:Evolution] ONLINE colonyId={} — développement max atteint", data.colonyId);
            return;
        }

        // Prochain bâtiment à construire
        String nextBuildingId = buildOrder.get(data.buildIndex);
        LOG.info("[CF:Evolution] ONLINE colonyId={} — prochain bâtiment: {}",
                data.colonyId, nextBuildingId);

        // Demander une upgrade/construction au bâtiment existant le plus bas niveau
        // via l'API MineColonies. On cherche le bâtiment du type dans la colonie
        // et on demande son upgrade s'il est déjà présent, sinon on signale via le log
        // que le Builder doit le créer (la pose physique nécessite le système de blueprint
        // de Structurize, qui est géré côté MineColonies internalement).
        requestNextBuild(colony, nextBuildingId, data, level);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    /**
     * Demande la construction ou l'upgrade du prochain bâtiment dans la colonie.
     * Si le bâtiment existe déjà à niveau < 5, demande une upgrade.
     * Avance buildIndex une fois la requête émise.
     */
    private static void requestNextBuild(IColony colony, String buildingTypeId,
                                          AiCityData data, ServerLevel level) {
        try {
            // Cherche un bâtiment de ce type dans la colonie
            IBuilding existing = colony.getServerBuildingManager()
                    .getBuildings().values().stream()
                    .filter(b -> {
                        try {
                            String typeKey = b.getBuildingRegistryEntry().getKey().getPath();
                            return typeKey.equals(buildingTypeId);
                        } catch (Exception ex) { return false; }
                    })
                    .filter(b -> b.getBuildingLevel() < b.getMaxBuildingLevel())
                    .findFirst().orElse(null);

            if (existing != null) {
                // Upgrade le bâtiment existant
                existing.requestUpgrade(null, existing.getPosition());
                LOG.info("[CF:Evolution] upgrade demandée building={} level={}→{}",
                        buildingTypeId, existing.getBuildingLevel(),
                        existing.getBuildingLevel() + 1);
            } else {
                // Le bâtiment n'existe pas encore dans la colonie —
                // MineColonies doit le placer. On log pour visibilité ;
                // la création physique est déléguée au système de blueprint interne.
                LOG.info("[CF:Evolution] construction requise building={} colonyId={}",
                        buildingTypeId, colony.getID());
            }

            // Avance l'index dans tous les cas (évite les boucles infinies sur bâtiments max)
            data.buildIndex++;
            data.currentTier = computeTier(data.buildIndex, data.archetype.buildOrder.size());
            data.lastEvolutionTick = level.getGameTime();

        } catch (Exception e) {
            LOG.error("[CF:Evolution] erreur requestNextBuild building={}: {}",
                    buildingTypeId, e.getMessage());
        }
    }

    private static int computeTier(int buildIndex, int maxIndex) {
        if (maxIndex <= 0) return 1;
        float progress = (float) buildIndex / maxIndex;
        if (progress >= 0.75f) return 4;
        if (progress >= 0.50f) return 3;
        if (progress >= 0.25f) return 2;
        return 1;
    }

    private static int tierToMinBuildIndex(int tier, int maxIndex) {
        return switch (tier) {
            case 4 -> (int)(maxIndex * 0.75f);
            case 3 -> (int)(maxIndex * 0.50f);
            case 2 -> (int)(maxIndex * 0.25f);
            default -> 0;
        };
    }
}
