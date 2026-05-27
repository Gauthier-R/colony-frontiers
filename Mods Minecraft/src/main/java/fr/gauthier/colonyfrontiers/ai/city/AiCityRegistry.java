package fr.gauthier.colonyfrontiers.ai.city;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registre persistant de toutes les cités IA (GDD Module 1).
 *
 * Stocké via SavedData dans le niveau Overworld sous la clé "colonyfrontiers_cities".
 * Persiste à travers les redémarrages serveur.
 *
 * Usage :
 *   AiCityRegistry reg = AiCityRegistry.get(serverLevel);
 *   reg.addCity(data);
 *   reg.setDirty();
 */
public class AiCityRegistry extends SavedData {

    private static final Logger LOG       = LoggerFactory.getLogger("ColonyFrontiers/Registry");
    private static final String DATA_NAME = "colonyfrontiers_cities";

    /** Intervalle entre deux tentatives de spawn dynamique (en ticks). 20 min réelles. */
    public static final int SPAWN_INTERVAL_TICKS = 20 * 60 * 20;

    private final List<AiCityData> cities     = new ArrayList<>();
    private int                    spawnTimer = SPAWN_INTERVAL_TICKS;
    /** Vrai si la génération initiale au premier chargement a déjà eu lieu. */
    private boolean                worldGenDone = false;

    // ── ACCÈS SINGLETON PAR NIVEAU ────────────────────────────────────────

    public static AiCityRegistry get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                AiCityRegistry::load,
                AiCityRegistry::new,
                DATA_NAME);
    }

    // ── API PUBLIQUE ──────────────────────────────────────────────────────

    public List<AiCityData> getCities() {
        return Collections.unmodifiableList(cities);
    }

    public void addCity(AiCityData data) {
        cities.add(data);
        setDirty();
        LOG.info("[CF:Registry] cité ajoutée center={} archetype={} tier={}",
                data.center, data.archetype, data.currentTier);
    }

    public void removeCity(AiCityData data) {
        cities.remove(data);
        setDirty();
    }

    public boolean isWorldGenDone() { return worldGenDone; }
    public void markWorldGenDone()  { worldGenDone = true; setDirty(); }

    public int getSpawnTimer()                { return spawnTimer; }
    public void setSpawnTimer(int t)          { spawnTimer = t; setDirty(); }
    public void decrementSpawnTimer()         { spawnTimer = Math.max(0, spawnTimer - 1); setDirty(); }

    /**
     * Retourne la cité dont le Town Hall est le plus proche de pos,
     * ou null si aucune cité n'existe.
     */
    public AiCityData getClosest(BlockPos pos) {
        AiCityData best = null;
        double bestDist = Double.MAX_VALUE;
        for (AiCityData d : cities) {
            double dist = d.center.distSqr(pos);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best;
    }

    /**
     * Vérifie qu'aucune cité existante n'est à moins de minDistBlocks du point donné.
     */
    public boolean isFarEnoughFromAll(BlockPos pos, int minDistBlocks) {
        int minSq = minDistBlocks * minDistBlocks;
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

    // ── NBT ──────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (AiCityData d : cities) list.add(d.save());
        tag.put("cities", list);
        tag.putInt("spawnTimer",   spawnTimer);
        tag.putBoolean("worldGenDone", worldGenDone);
        return tag;
    }

    private static AiCityRegistry load(CompoundTag tag) {
        AiCityRegistry reg = new AiCityRegistry();
        ListTag list = tag.getList("cities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            reg.cities.add(AiCityData.load(list.getCompound(i)));
        }
        reg.spawnTimer   = tag.getInt("spawnTimer");
        reg.worldGenDone = tag.getBoolean("worldGenDone");
        LOG.info("[CF:Registry] chargé — {} cités", reg.cities.size());
        return reg;
    }
}
