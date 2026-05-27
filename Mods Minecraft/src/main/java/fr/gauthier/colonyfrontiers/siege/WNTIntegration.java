package fr.gauthier.colonyfrontiers.siege;

import com.minecolonies.api.colony.IColony;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Point d'intégration unique avec War 'N Taxes (minecolonytax).
 *
 * Cette classe est un stub compilable. Les appels WNT réels seront ajoutés
 * ici dès que l'API du JAR minecolonytax-4.0.1 sera inspectée / fournie.
 * Toute la logique de siège/conquête appelle WNTIntegration pour rester
 * découplée et ne jamais crasher si WNT n'est pas présent.
 */
public class WNTIntegration {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/WNT");

    private static final boolean WNT_PRESENT = detectWNT();

    private static boolean detectWNT() {
        try {
            Class.forName("minecolonytax.api.ColonyTaxAPI");
            LOG.info("[CF:WNT] minecolonytax détecté — intégration active.");
            return true;
        } catch (ClassNotFoundException e) {
            LOG.warn("[CF:WNT] minecolonytax non trouvé — vassalisation sans tributs WNT.");
            return false;
        }
    }

    /**
     * Enregistre un tribut périodique de la colonie vaincue vers le joueur vainqueur.
     * À compléter avec l'API WNT réelle.
     */
    public static void registerTribute(ServerPlayer victor, IColony vassal) {
        if (!WNT_PRESENT) {
            LOG.info("[CF:WNT] stub registerTribute — colonyId={} victor={}",
                    vassal.getID(), victor.getName().getString());
            return;
        }
        // TODO: remplacer par appels API WNT une fois l'API inspectée
        // Exemple typique : ColonyTaxAPI.addTribute(vassal, victor, amount, interval)
        LOG.info("[CF:WNT] registerTribute appelé — à implémenter avec API WNT réelle.");
    }
}
