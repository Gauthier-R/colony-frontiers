package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.boss.BossSystem;
import fr.gauthier.colonyfrontiers.events.GuardFollowEvent;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

/**
 * Orchestrateur principal du Module 1.
 *
 * WorldLoad     → log de l'état du registre.
 * ChunkLoad     → évaluation de région (spawn potentiel) + matérialisation des sites.
 * ServerTick    → boss respawn timer.
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AiCityEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/CityEvents");

    /** Rayon en blocs pour déclencher la matérialisation d'un site réservé. */
    private static final int MATERIALIZE_RADIUS = 300;

    // ── CHARGEMENT DU MONDE ───────────────────────────────────────────────

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        AiCityRegistry registry = AiCityRegistry.get(level);
        int cityCount   = registry.getCities().size();
        long matCount   = registry.getCities().stream().filter(d -> d.colonyId != -1).count();

        LOG.info("[CF:CityEvents] Monde chargé — {} cités ({} matérialisées)", cityCount, matCount);
        CfLogger.log("WORLD_LOAD cities={} materialized={}", cityCount, matCount);
    }

    // ── CHARGEMENT DE CHUNK ───────────────────────────────────────────────
    // Deux responsabilités :
    //   A) Évaluer la région du chunk si elle n'a jamais été vérifiée
    //      → peut réserver un nouveau site (spawn IA)
    //   B) Matérialiser les sites réservés proches (colonyId == -1)
    //      → crée la colonie MineColonies réelle

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        ChunkPos chunkPos   = event.getChunk().getPos();
        BlockPos chunkCenter = chunkPos.getMiddleBlockPosition(64);

        AiCityRegistry registry = AiCityRegistry.get(level);

        // ── A) Évaluation de région ───────────────────────────────────────
        int rX = AiCityRegistry.regionX(chunkCenter);
        int rZ = AiCityRegistry.regionZ(chunkCenter);

        if (!registry.isRegionChecked(rX, rZ)) {
            AiCitySpawner.evaluateRegion(level, registry, rX, rZ);
        }

        // ── B) Matérialisation des sites proches ──────────────────────────
        // On ne matérialise que si un joueur est dans le rayon (on n'utilise
        // plus de joueur comme propriétaire, mais on attend qu'il soit là pour
        // que les chunks soient bien chargés et le spawn sûr).
        List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class,
                new net.minecraft.world.phys.AABB(
                        chunkCenter.offset(-MATERIALIZE_RADIUS, -64, -MATERIALIZE_RADIUS),
                        chunkCenter.offset( MATERIALIZE_RADIUS,  64,  MATERIALIZE_RADIUS)));
        if (nearbyPlayers.isEmpty()) return;

        for (AiCityData data : registry.getCities()) {
            if (data.colonyId != -1) continue; // déjà matérialisée
            if (data.center.distSqr(chunkCenter) > (long) MATERIALIZE_RADIUS * MATERIALIZE_RADIUS) continue;

            AiCitySpawner.materializeColony(level, data, registry);
        }

        // ── C) Catchup hybride pour cités déjà matérialisées ─────────────
        for (AiCityData data : registry.getCities()) {
            if (data.colonyId == -1) continue;
            if (data.center.distSqr(chunkCenter) > (long) MATERIALIZE_RADIUS * MATERIALIZE_RADIUS) continue;

            IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                    .getColonyByWorld(data.colonyId, level);
            if (colony == null) continue;

            HybridEvolutionEngine.triggerOnlineCatchup(level, data, colony);
            registry.setDirty();
        }
    }

    // ── SERVER TICK — boss respawn ────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        ServerLevel overworld = event.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) return;

        AiCityRegistry registry = AiCityRegistry.get(overworld);

        for (AiCityData data : registry.getCities()) {
            if (data.bossRespawnTicks <= 0) continue;
            if (!isPlayerNearby(overworld, data.center, 512)) continue;

            data.bossRespawnTicks--;
            if (data.bossRespawnTicks == 0) {
                IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                        .getColonyByWorld(data.colonyId, overworld);
                if (colony != null) promoteNewBossForCity(overworld, data);
            }
            registry.setDirty();
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private static void promoteNewBossForCity(ServerLevel level, AiCityData data) {
        level.getEntitiesOfClass(AbstractEntityCitizen.class,
                new net.minecraft.world.phys.AABB(
                        data.center.offset(-200, -64, -200),
                        data.center.offset( 200,  64,  200)))
            .stream()
            .filter(c -> GuardFollowEvent.isGuard(c)
                    && !BossSystem.isBoss(c)
                    && c.getCitizenColonyHandler().getColony() != null
                    && c.getCitizenColonyHandler().getColony().getID() == data.colonyId)
            .findFirst()
            .ifPresent(c -> {
                BossSystem.markBoss(c, data.colonyId);
                data.bossDefeated = false;
                LOG.info("[CF:CityEvents] nouveau boss promu colonyId={} citizenId={}",
                        data.colonyId, c.getId());
                CfLogger.log("BOSS_PROMOTED colonyId={} citizenId={}", data.colonyId, c.getId());
            });
    }

    private static boolean isPlayerNearby(ServerLevel level, BlockPos pos, int radius) {
        return !level.getEntitiesOfClass(Player.class,
                new net.minecraft.world.phys.AABB(
                        pos.offset(-radius, -64, -radius),
                        pos.offset( radius,  64,  radius)))
                .isEmpty();
    }
}
