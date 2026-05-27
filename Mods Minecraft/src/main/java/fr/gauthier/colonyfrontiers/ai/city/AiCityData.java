package fr.gauthier.colonyfrontiers.ai.city;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * État persistant d'une cité IA (GDD Module 1).
 *
 * Sérialisé en NBT dans AiCityRegistry (SavedData).
 * Un AiCityData existe pour chaque cité générée, qu'elle soit chargée ou non.
 */
public class AiCityData {

    // ── Identifiants ─────────────────────────────────────────────────────
    /** ID de la colonie MineColonies sous-jacente. -1 si pas encore créée. */
    public int colonyId = -1;
    /** Position du Town Hall (centre de la cité). */
    public BlockPos center;
    /** Archétype secret de la cité. */
    public AiColonyArchetype archetype;
    /** Tier initial (1–4) tiré à la génération du monde. */
    public int initialTier;

    // ── Évolution ────────────────────────────────────────────────────────
    /** Tier actuel de développement (1–4). Évolue via HybridEvolutionEngine. */
    public int currentTier;
    /** Index dans buildOrder de l'archétype — prochain bâtiment à construire. */
    public int buildIndex = 0;
    /** Timestamp serveur (en ticks) de la dernière mise à jour d'évolution. */
    public long lastEvolutionTick = 0L;

    // ── Conquête ─────────────────────────────────────────────────────────
    /** Vrai si le boss de la cité a été vaincu mais la cité pas encore capturée. */
    public boolean bossDefeated = false;
    /** Ticks restants avant remplacement du boss. 0 = pas en attente. */
    public int bossRespawnTicks = 0;
    /** Vrai si la cité a été vassalisée par le joueur. */
    public boolean vassalized = false;

    // ── Constructeurs ─────────────────────────────────────────────────────

    public AiCityData(BlockPos center, AiColonyArchetype archetype, int tier) {
        this.center      = center;
        this.archetype   = archetype;
        this.initialTier = tier;
        this.currentTier = tier;
    }

    private AiCityData() {}

    // ── NBT ──────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("colonyId",          colonyId);
        tag.putInt("centerX",           center.getX());
        tag.putInt("centerY",           center.getY());
        tag.putInt("centerZ",           center.getZ());
        tag.putString("archetype",      archetype.name());
        tag.putInt("initialTier",       initialTier);
        tag.putInt("currentTier",       currentTier);
        tag.putInt("buildIndex",        buildIndex);
        tag.putLong("lastEvolutionTick",lastEvolutionTick);
        tag.putBoolean("bossDefeated",  bossDefeated);
        tag.putInt("bossRespawnTicks",  bossRespawnTicks);
        tag.putBoolean("vassalized",    vassalized);
        return tag;
    }

    public static AiCityData load(CompoundTag tag) {
        AiCityData d = new AiCityData();
        d.colonyId          = tag.getInt("colonyId");
        d.center            = new BlockPos(tag.getInt("centerX"),
                                           tag.getInt("centerY"),
                                           tag.getInt("centerZ"));
        d.archetype         = AiColonyArchetype.valueOf(tag.getString("archetype"));
        d.initialTier       = tag.getInt("initialTier");
        d.currentTier       = tag.getInt("currentTier");
        d.buildIndex        = tag.getInt("buildIndex");
        d.lastEvolutionTick = tag.getLong("lastEvolutionTick");
        d.bossDefeated      = tag.getBoolean("bossDefeated");
        d.bossRespawnTicks  = tag.getInt("bossRespawnTicks");
        d.vassalized        = tag.getBoolean("vassalized");
        return d;
    }
}
