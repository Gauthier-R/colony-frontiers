package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.boss.BossSystem;
import fr.gauthier.colonyfrontiers.events.GuardFollowEvent;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
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
 * Hooks :
 *   WorldLoad  → génération initiale des cités si première ouverture du monde.
 *   ChunkLoad  → déclenchement du catchup hybride quand un joueur arrive près d'une cité.
 *   ServerTick → timer de spawn dynamique + tick bossRespawn des cités existantes.
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AiCityEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/CityEvents");

    /** Rayon en blocs autour d'un joueur dans lequel on déclencherait un catchup. */
    private static final int CATCHUP_TRIGGER_RADIUS = 256;

    // ── CHARGEMENT DU MONDE ───────────────────────────────────────────────

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // On opère uniquement sur l'Overworld
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        AiCityRegistry registry = AiCityRegistry.get(level);

        if (!registry.isWorldGenDone()) {
            LOG.info("[CF:CityEvents] Premier chargement — génération initiale des cités IA");
            AiCitySpawner.generateInitialCities(level, registry, new Random(level.getSeed()));
        } else {
            LOG.info("[CF:CityEvents] Monde chargé — {} cités IA enregistrées",
                    registry.getCities().size());
        }
    }

    // ── CHARGEMENT DE CHUNK ───────────────────────────────────────────────

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        ChunkPos chunkPos = event.getChunk().getPos();
        BlockPos centerBlock = chunkPos.getMiddleBlockPosition(64);

        AiCityRegistry registry = AiCityRegistry.get(level);

        for (AiCityData data : registry.getCities()) {
            // Déclenchement si le chunk chargé est dans le rayon de catchup
            if (data.center.distSqr(centerBlock) > (long) CATCHUP_TRIGGER_RADIUS * CATCHUP_TRIGGER_RADIUS) continue;

            IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                    .getColonyByWorld(data.colonyId, level);
            if (colony == null) continue;

            HybridEvolutionEngine.triggerOnlineCatchup(level, data, colony);
            registry.setDirty();

            // Boss respawn tick en ligne
            if (data.bossRespawnTicks > 0) {
                data.bossRespawnTicks = Math.max(0, data.bossRespawnTicks - 1);
                if (data.bossRespawnTicks == 0) {
                    promoteNewBossForCity(level, data, colony);
                }
                registry.setDirty();
            }
        }
    }

    // ── SERVER TICK ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // On récupère l'Overworld via le serveur
        ServerLevel overworld = event.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) return;

        AiCityRegistry registry = AiCityRegistry.get(overworld);

        // Timer de spawn dynamique
        registry.decrementSpawnTimer();
        if (registry.getSpawnTimer() <= 0) {
            registry.setSpawnTimer(AiCityRegistry.SPAWN_INTERVAL_TICKS);
            AiCitySpawner.trySpawnDynamicCity(overworld, registry,
                    new Random(overworld.getGameTime()));
        }

        // Tick bossRespawn pour les cités dont les chunks ne sont pas forcément chargés
        // (on ne tick ici que si au moins un joueur est dans un rayon de 512 blocs)
        for (AiCityData data : registry.getCities()) {
            if (data.bossRespawnTicks <= 0) continue;
            if (!isPlayerNearby(overworld, data.center, 512)) continue;

            data.bossRespawnTicks--;
            if (data.bossRespawnTicks == 0) {
                IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                        .getColonyByWorld(data.colonyId, overworld);
                if (colony != null) promoteNewBossForCity(overworld, data, colony);
            }
            registry.setDirty();
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private static void promoteNewBossForCity(ServerLevel level, AiCityData data, IColony colony) {
        // Cherche le premier garde vivant de la colonie (non-boss)
        level.getEntitiesOfClass(AbstractEntityCitizen.class,
                new net.minecraft.world.phys.AABB(
                        data.center.offset(-200, -64, -200),
                        data.center.offset(200, 64, 200)))
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
            });
    }

    private static boolean isPlayerNearby(ServerLevel level, BlockPos pos, int radius) {
        List<Player> players = level.getEntitiesOfClass(Player.class,
                new net.minecraft.world.phys.AABB(
                        pos.offset(-radius, -64, -radius),
                        pos.offset(radius, 64, radius)));
        return !players.isEmpty();
    }
}
