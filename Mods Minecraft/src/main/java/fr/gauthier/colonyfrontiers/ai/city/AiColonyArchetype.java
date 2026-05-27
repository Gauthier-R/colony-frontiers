package fr.gauthier.colonyfrontiers.ai.city;

import java.util.List;
import java.util.Random;

/**
 * Archétype comportemental secret d'une cité IA (GDD Module 1).
 *
 * Caché au joueur — déductible uniquement par l'observation ou le dialogue.
 * Dicte l'ordre de priorité de construction (build tree) et l'agressivité par défaut.
 */
public enum AiColonyArchetype {

    /**
     * Priorité : baraquements → tours de garde → remparts.
     * Agressivité : haute. Lance des raids proactifs.
     */
    MILITARISTIC(List.of(
            "barracks", "guardtower", "barrackstower",
            "combatacademy", "archery",
            "townhall", "residence", "builder",
            "warehouse", "farmer", "cook"
    ), 0.75f),

    /**
     * Priorité : entrepôt → marché → livreurs.
     * Agressivité : faible. Préfère les tributs aux raids.
     */
    COMMERCIAL(List.of(
            "warehouse", "deliveryman", "townhall",
            "residence", "builder", "farmer",
            "cook", "sawmill", "blacksmith",
            "guardtower", "barracks"
    ), 0.20f),

    /**
     * Priorité équilibrée + tendance à attaquer les ressources du joueur.
     * Agressivité : maximale, cible en priorité Warehouse et Town Hall ennemis.
     */
    HOSTILE(List.of(
            "townhall", "barracks", "guardtower",
            "warehouse", "builder", "residence",
            "farmer", "barrackstower", "combatacademy",
            "archery", "cook"
    ), 0.95f),

    ;

    /** Ordre de construction prioritaire (IDs MineColonies). */
    public final List<String> buildOrder;

    /** Probabilité de base de lancer une attaque lors d'un cycle de décision. */
    public final float baseAggressiveness;

    AiColonyArchetype(List<String> buildOrder, float baseAggressiveness) {
        this.buildOrder          = buildOrder;
        this.baseAggressiveness  = baseAggressiveness;
    }

    /** Tire un archétype aléatoire pour une nouvelle cité. */
    public static AiColonyArchetype random(Random rng) {
        AiColonyArchetype[] values = values();
        return values[rng.nextInt(values.length)];
    }
}
