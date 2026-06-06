package fr.gauthier.colonyfrontiers.ai.city;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registre persistant de toutes les cités IA.
 *
 * Stocké via SavedData sous la clé "colonyfrontiers_cities".
 *
 * Système de régions (comme les villages vanilla) :
 *   Le monde est découpé en régions carrées de REGION_SIZE × REGION_SIZE blocs.
 *   Chaque région est identifiée par (regionX, regionZ) = (blockX / REGION_SIZE, blockZ / REGION_SIZE).
 *   Quand un joueur charge des chunks dans une région non encore vérifiée,
 *   AiCitySpawner tente (avec SPAWN_CHANCE de probabilité) de placer une cité.
 *   La région est ensuite marquée "vérifiée" pour ne plus être tentée.
 *   → Résultat : distribution infinie et homogène, identique aux villages vanilla.
 */
public class AiCityRegistry extends SavedData {

    private static final Logger LOG       = LoggerFactory.getLogger("ColonyFrontiers/Registry");
    private static final String DATA_NAME = "colonyfrontiers_cities";

    /** Taille d'une région en blocs. Une cité max par région. */
    public static final int REGION_SIZE = 600;

    /** Probabilité qu'une région contienne une cité (0.0–1.0). */
    public static final float SPAWN_CHANCE = 0.85f;

    private final List<AiCityData>  cities         = new ArrayList<>();
    /** Régions déjà évaluées — stocké comme paires long (regionX << 32 | regionZ). */
    private final Set<Long>         checkedRegions = new HashSet<>();

    // ── ACCÈS SINGLETON ──────────────────────────────────────────────────

    public static AiCityRegistry get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                AiCityRegistry::load,
                AiCityRegistry::new,
                DATA_NAME);
    }

    // ── RÉGIONS ───────────────────────────────────────────────────────────

    public static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int regionX(BlockPos pos) {
        return Math.floorDiv(pos.getX(), REGION_SIZE);
    }

    public static int regionZ(BlockPos pos) {
        return Math.floorDiv(pos.getZ(), REGION_SIZE);
    }

    public boolean isRegionChecked(int regionX, int regionZ) {
        return checkedRegions.contains(regionKey(regionX, regionZ));
    }

    public void markRegionChecked(int regionX, int regionZ) {
        checkedRegions.add(regionKey(regionX, regionZ));
        setDirty();
    }

    // ── CITÉS ─────────────────────────────────────────────────────────────

    public List<AiCityData> getCities() {
        return Collections.unmodifiableList(cities);
    }

    public void addCity(AiCityData data) {
        cities.add(data);
        setDirty();
        LOG.info("[CF:Registry] cité enregistrée center={} archetype={} tier={} colonyId={}",
                data.center, data.archetype, data.currentTier, data.colonyId);
    }

    public void removeCity(AiCityData data) {
        cities.remove(data);
        setDirty();
    }

    public boolean isFarEnoughFromAll(BlockPos pos, int minDistBlocks) {
        long minSq = (long) minDistBlocks * minDistBlocks;
        for (AiCityData d : cities) {
            if (d.center.distSqr(pos) < minSq) return false;
        }
        return true;
    }

    public AiCityData getByColonyId(int colonyId) {
        for (AiCityData d : cities) {
            if (d.colonyId == colonyId) return d;
        }
        return null;
    }

    public AiCityData getClosest(BlockPos pos) {
        AiCityData best = null;
        double bestDist = Double.MAX_VALUE;
        for (AiCityData d : cities) {
            double dist = d.center.distSqr(pos);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best;
    }

    // ── NBT ──────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (AiCityData d : cities) list.add(d.save());
        tag.put("cities", list);

        long[] regions = new long[checkedRegions.size()];
        int i = 0;
        for (long r : checkedRegions) regions[i++] = r;
        tag.putLongArray("checkedRegions", regions);
        return tag;
    }

    private static AiCityRegistry load(CompoundTag tag) {
        AiCityRegistry reg = new AiCityRegistry();
        ListTag list = tag.getList("cities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            reg.cities.add(AiCityData.load(list.getCompound(i)));
        }
        for (long r : tag.getLongArray("checkedRegions")) reg.checkedRegions.add(r);
        LOG.info("[CF:Registry] chargé — {} cités, {} régions vérifiées",
                reg.cities.size(), reg.checkedRegions.size());
        return reg;
    }
}
