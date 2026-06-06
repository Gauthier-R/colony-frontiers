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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Orchestrateur Module 1.
 *
 * RÈGLE FONDAMENTALE ANTI-DEADLOCK :
 *   onChunkLoad ne fait JAMAIS d'appels lourds (createColony, getHeight sur chunks
 *   non chargés, etc.). Il se contente d'enregistrer dans une file.
 *   Toutes les opérations coûteuses se font dans onServerTick, une par tick.
 *
 *   Sans cette règle : createColony charge des chunks → onChunkLoad → createColony
 *   → boucle infinie sur le thread serveur → freeze total.
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AiCityEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/CityEvents");

    /** File des régions à évaluer (coordonnées de région, pas de bloc). */
    private static final Queue<long[]> pendingRegions      = new ArrayDeque<>();
    /** File des sites à matérialiser : (centre du site, UUID du joueur déclencheur). */
    private static final Queue<long[]> pendingMaterialize  = new ArrayDeque<>();
    /** File des cités à rattraper (online catchup — place les bâtiments manquants). */
    private static final Queue<Integer> pendingCatchup     = new ArrayDeque<>();

    /** Rayon en blocs pour déclencher la matérialisation et le catchup. */
    private static final int MATERIALIZE_RADIUS = 300;
    /**
     * Intervalle en ticks entre deux matérialisations.
     * 200 ticks = 10 secondes — laisse le serveur respirer entre deux createColony.
     */
    private static final int MATERIALIZE_COOLDOWN = 200;
    /** Intervalle entre deux placements de bâtiments (une construction visible à la fois). */
    private static final int CATCHUP_COOLDOWN     = 40; // 2 secondes
    private static int materializeCooldown = 0;
    private static int catchupCooldown     = 0;

    // ── WORLD LOAD ────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        pendingRegions.clear();
        pendingMaterialize.clear();
        pendingCatchup.clear();
        materializeCooldown = 0;
        catchupCooldown     = 0;

        AiCityRegistry registry = AiCityRegistry.get(level);
        long total = registry.getCities().size();
        long mat   = registry.getCities().stream().filter(d -> d.colonyId != -1).count();

        LOG.info("[CF:CityEvents] Monde chargé — {} cités ({} matérialisées)", total, mat);
        CfLogger.log("WORLD_LOAD cities={} materialized={}", total, mat);

        // Pré-scan 7×7 régions autour du spawn pour garantir des villages proches
        BlockPos spawn = level.getSharedSpawnPos();
        int spawnRX = AiCityRegistry.regionX(spawn);
        int spawnRZ = AiCityRegistry.regionZ(spawn);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int rx = spawnRX + dx, rz = spawnRZ + dz;
                if (!registry.isRegionChecked(rx, rz)) {
                    registry.markRegionChecked(rx, rz);
                    pendingRegions.offer(new long[]{ rx, rz });
                }
            }
        }
        LOG.info("[CF:CityEvents] Pré-scan spawn: {} régions en file", pendingRegions.size());
    }

    // ── CHUNK LOAD — UNIQUEMENT des ajouts en file, RIEN de coûteux ──────

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        ChunkPos cp = event.getChunk().getPos();
        BlockPos chunkCenter = cp.getMiddleBlockPosition(64);

        AiCityRegistry registry = AiCityRegistry.get(level);

        // Évaluation de région — mise en file seulement
        int rX = AiCityRegistry.regionX(chunkCenter);
        int rZ = AiCityRegistry.regionZ(chunkCenter);
        if (!registry.isRegionChecked(rX, rZ)) {
            // Marque immédiatement pour éviter les doublons dans la file
            registry.markRegionChecked(rX, rZ);
            pendingRegions.offer(new long[]{ rX, rZ });
        }

        // Matérialisation — mise en file seulement si un joueur est proche
        List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class,
                new net.minecraft.world.phys.AABB(
                        chunkCenter.offset(-MATERIALIZE_RADIUS, -64, -MATERIALIZE_RADIUS),
                        chunkCenter.offset( MATERIALIZE_RADIUS,  64,  MATERIALIZE_RADIUS)));
        if (nearbyPlayers.isEmpty()) return;

        // Stocke le UUID du joueur le plus proche pour le passer à createColony
        Player closest = nearbyPlayers.get(0);
        long playerUuidMost  = closest.getUUID().getMostSignificantBits();
        long playerUuidLeast = closest.getUUID().getLeastSignificantBits();

        for (AiCityData data : registry.getCities()) {
            if (data.colonyId != -1) continue;
            if (data.center.distSqr(chunkCenter) > (long) MATERIALIZE_RADIUS * MATERIALIZE_RADIUS) continue;
            // Clé unique = coordonnées du centre packed
            long key = ((long) data.center.getX() << 32) | (data.center.getZ() & 0xFFFFFFFFL);
            boolean alreadyQueued = pendingMaterialize.stream()
                    .anyMatch(e -> e[0] == key);
            if (!alreadyQueued) {
                pendingMaterialize.offer(new long[]{ key, playerUuidMost, playerUuidLeast });
            }
        }

        // Catchup online — mise en file pour les cités matérialisées à portée
        for (AiCityData data : registry.getCities()) {
            if (data.colonyId == -1) continue;
            if (data.center.distSqr(chunkCenter) > (long) MATERIALIZE_RADIUS * MATERIALIZE_RADIUS) continue;
            if (!pendingCatchup.contains(data.colonyId)) {
                pendingCatchup.offer(data.colonyId);
            }
        }
    }

    // ── PLAYER TICK — vérifie région autour du joueur toutes les 5s ─────────
    // Complément à onChunkLoad : capte les régions que le joueur survole vite.

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;
        if (event.player.tickCount % 40 != 0) return; // toutes les 2s

        if (!(event.player.level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;

        AiCityRegistry registry = AiCityRegistry.get(level);
        BlockPos pos = event.player.blockPosition();
        int rX = AiCityRegistry.regionX(pos);
        int rZ = AiCityRegistry.regionZ(pos);

        // Vérifie la région courante et les 8 régions adjacentes
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int rx = rX + dx, rz = rZ + dz;
                if (!registry.isRegionChecked(rx, rz)) {
                    registry.markRegionChecked(rx, rz);
                    pendingRegions.offer(new long[]{ rx, rz });
                }
            }
        }
    }

    // ── SERVER TICK — traitement des files, UNE opération par tick ────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        ServerLevel overworld = event.getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) return;

        AiCityRegistry registry = AiCityRegistry.get(overworld);

        // Traite une évaluation de région par tick
        if (!pendingRegions.isEmpty()) {
            long[] region = pendingRegions.poll();
            try {
                AiCitySpawner.evaluateRegion(overworld, registry, (int) region[0], (int) region[1]);
            } catch (Exception e) {
                LOG.error("[CF:CityEvents] evaluateRegion échoué: {}", e.getMessage());
                CfLogger.log("EVALUATE_ERROR region=({},{}) err={}", region[0], region[1], e.getMessage());
            }
            return; // Une seule opération par tick
        }

        // Traite une matérialisation si le cooldown est écoulé
        if (materializeCooldown > 0) {
            materializeCooldown--;
            return;
        }

        if (!pendingMaterialize.isEmpty()) {
            long[] entry = pendingMaterialize.poll();
            long key             = entry[0];
            java.util.UUID playerUuid = new java.util.UUID(entry[1], entry[2]);

            // Retrouve le AiCityData via la clé (x << 32 | z)
            AiCityData data = null;
            for (AiCityData d : registry.getCities()) {
                if (d.colonyId != -1) continue;
                long dk = ((long) d.center.getX() << 32) | (d.center.getZ() & 0xFFFFFFFFL);
                if (dk == key) { data = d; break; }
            }

            if (data != null) {
                // Retrouve le joueur déclencheur — doit encore être connecté
                net.minecraft.server.level.ServerPlayer player =
                        overworld.getServer().getPlayerList().getPlayer(playerUuid);
                if (player == null) {
                    // Joueur déconnecté — remet en file avec le premier joueur dispo
                    List<net.minecraft.server.level.ServerPlayer> online =
                            overworld.getServer().getPlayerList().getPlayers();
                    if (!online.isEmpty()) {
                        player = online.get(0);
                    }
                }
                if (player != null) {
                    try {
                        AiCitySpawner.materializeColony(overworld, data, player, registry);
                    } catch (Exception e) {
                        LOG.error("[CF:CityEvents] materializeColony échoué: {}", e.getMessage());
                        CfLogger.log("MATERIALIZE_EXCEPTION pos={} err={}",
                                data.center, e.getMessage());
                    }
                } else {
                    LOG.debug("[CF:CityEvents] aucun joueur connecté pour matérialiser {}", data.center);
                }
                materializeCooldown = MATERIALIZE_COOLDOWN;
            }
            return;
        }

        // Catchup online — place UN bâtiment par cooldown
        if (catchupCooldown > 0) {
            catchupCooldown--;
        } else if (!pendingCatchup.isEmpty()) {
            int colonyId = pendingCatchup.poll();
            AiCityData data = registry.getByColonyId(colonyId);
            if (data != null) {
                IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                        .getColonyByWorld(colonyId, overworld);
                if (colony != null) {
                    try {
                        boolean acted = HybridEvolutionEngine.triggerOnlineCatchup(
                                overworld, data, colony);
                        if (acted) {
                            registry.setDirty();
                            catchupCooldown = CATCHUP_COOLDOWN;
                        }
                    } catch (Exception e) {
                        LOG.error("[CF:CityEvents] catchup échoué colonyId={}: {}", colonyId, e.getMessage());
                        CfLogger.log("CATCHUP_ERROR colonyId={} err={}", colonyId, e.getMessage());
                    }
                }
            }
        }

        // Boss respawn timer
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
                LOG.info("[CF:CityEvents] boss promu colonyId={} citizenId={}", data.colonyId, c.getId());
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
