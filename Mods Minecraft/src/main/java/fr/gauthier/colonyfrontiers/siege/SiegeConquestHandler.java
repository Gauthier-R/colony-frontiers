package fr.gauthier.colonyfrontiers.siege;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.events.GuardFollowEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Gestion du siège et de la conquête (GDD Module 2).
 *
 * Victoire : canaliser un Clic droit avec la Bannière de Campagne sur le bloc
 *            central du Town Hall pendant 60 secondes ininterrompues.
 *            → Vassalisation (tributs WNT) + message de victoire.
 *
 * Défaite  : mort du joueur pendant le siège OU perte totale du régiment.
 *            → Trêve forcée (cooldown NBT) + malus bonheur (deuil dans la colonie du joueur).
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SiegeConquestHandler {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Siege");

    private static final int    CHANNEL_TICKS    = 60 * 20;  // 60 secondes
    private static final int    TRUCE_TICKS      = 24000 * 3; // 3 jours in-game
    private static final double TOWNHALL_RADIUS  = 4.0;       // distance max au center

    private static final Map<UUID, ChannelState> channels = new HashMap<>();

    private static class ChannelState {
        final int    colonyId;
        final BlockPos townHallPos;
        int ticksRemaining = CHANNEL_TICKS;
        ServerBossEvent bossBar;
        BlockPos startPos;

        ChannelState(int colonyId, BlockPos townHallPos, ServerPlayer player) {
            this.colonyId    = colonyId;
            this.townHallPos = townHallPos;
            this.startPos    = player.blockPosition();
            this.bossBar     = new ServerBossEvent(
                    Component.literal("Conquête en cours...").withStyle(ChatFormatting.GOLD),
                    BossEvent.BossBarColor.YELLOW,
                    BossEvent.BossBarOverlay.NOTCHED_20);
            this.bossBar.setProgress(1.0F);
            this.bossBar.addPlayer(player);
        }
    }

    // ── CLIC DROIT SUR BLOC — démarrer/continuer le canal ────────────────

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        Player player = event.getEntity();
        if (!(player.getMainHandItem().getItem() instanceof
                fr.gauthier.colonyfrontiers.items.CampaignBannerItem)) return;
        if (player.isShiftKeyDown()) return; // shift = hold ground, pas siège

        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos clicked  = event.getPos();

        IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                .getColonyByPosFromWorld(level, clicked);
        if (colony == null) return;

        // Le Town Hall est à colony.getCenter() — on accepte un rayon de TOWNHALL_RADIUS
        if (Math.sqrt(colony.getCenter().distSqr(clicked)) > TOWNHALL_RADIUS) return;

        UUID playerUuid = player.getUUID();
        if (!channels.containsKey(playerUuid)) {
            // Vérifie que le joueur a au moins un garde enrôlé
            long followers = level.getEntitiesOfClass(AbstractEntityCitizen.class,
                    player.getBoundingBox().inflate(128.0D))
                    .stream()
                    .filter(c -> GuardFollowEvent.isGuard(c)
                            && playerUuid.toString().equals(
                                    c.getPersistentData().getString("FollowTarget")))
                    .count();
            if (followers == 0) {
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Vous devez avoir des gardes enrôlés pour capturer une colonie."));
                return;
            }
            channels.put(playerUuid, new ChannelState(colony.getID(), colony.getCenter(),
                    (ServerPlayer) player));
            LOG.info("[CF:Siege] CANAL démarré playerUuid={} colonyId={}", playerUuid, colony.getID());
            player.sendSystemMessage(Component.literal(
                    "§6[Frontiers] Capture en cours... tenez la position !"));
        }
    }

    // ── SERVER TICK — avancer le canal et vérifier les interruptions ─────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (channels.isEmpty()) return;

        Iterator<Map.Entry<UUID, ChannelState>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ChannelState> entry = it.next();
            UUID         playerUuid = entry.getKey();
            ChannelState state      = entry.getValue();

            // Cherche le joueur sur n'importe quel monde du serveur
            ServerPlayer sp = null;
            for (ServerLevel lvl : event.getServer().getAllLevels()) {
                sp = lvl.getServer().getPlayerList().getPlayer(playerUuid);
                if (sp != null) break;
            }

            if (sp == null || !sp.isAlive()) {
                cancelChannel(playerUuid, state, sp, "joueur absent/mort");
                it.remove();
                continue;
            }

            // Interruption si le joueur bouge de plus de 3 blocs depuis le début
            if (sp.blockPosition().distSqr(state.startPos) > 9) {
                cancelChannel(playerUuid, state, sp, "joueur a bougé");
                it.remove();
                continue;
            }

            // Interruption si le Town Hall n'est plus dans la même colonie
            IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                    .getColonyByWorld(state.colonyId, sp.serverLevel());
            if (colony == null) {
                cancelChannel(playerUuid, state, sp, "colonie introuvable");
                it.remove();
                continue;
            }

            state.ticksRemaining--;
            float progress = (float) state.ticksRemaining / CHANNEL_TICKS;
            state.bossBar.setProgress(progress);

            if (state.ticksRemaining <= 0) {
                // VICTOIRE
                state.bossBar.removeAllPlayers();
                onConquestVictory(sp, colony);
                it.remove();
            }
        }
    }

    // ── MORT DU JOUEUR — défaite ──────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        UUID playerUuid = player.getUUID();

        // Annuler un siège en cours
        ChannelState state = channels.remove(playerUuid);
        if (state != null) {
            state.bossBar.removeAllPlayers();
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] Le siège a échoué — vous êtes mort."));
        }

        // Vérifier si le joueur avait des gardes enrôlés → défaite de campagne
        long followers = player.level().getEntitiesOfClass(AbstractEntityCitizen.class,
                player.getBoundingBox().inflate(128.0D))
                .stream()
                .filter(c -> GuardFollowEvent.isGuard(c)
                        && playerUuid.toString().equals(
                                c.getPersistentData().getString("FollowTarget")))
                .count();

        if (followers > 0 || state != null) {
            onCampaignDefeat((Player) player);
        }
    }

    // ── WIPE DU RÉGIMENT ─────────────────────────────────────────────────
    // Appelé par BossSystem quand le dernier garde est tué.

    public static void onRegimentWiped(Player player) {
        UUID playerUuid = player.getUUID();
        ChannelState state = channels.remove(playerUuid);
        if (state != null) state.bossBar.removeAllPlayers();
        onCampaignDefeat(player);
    }

    // ── RÉSULTATS ─────────────────────────────────────────────────────────

    private static void onConquestVictory(ServerPlayer player, IColony colony) {
        LOG.info("[CF:Siege] VICTOIRE playerUuid={} colonyId={}", player.getUUID(), colony.getID());

        player.sendSystemMessage(Component.literal(
                "§a§l[Frontiers] VICTOIRE ! " + colony.getName()
                        + " est maintenant votre vassal !").withStyle(ChatFormatting.GOLD));

        // Intégration WNT — stub (complète avec l'API WNT réelle)
        WNTIntegration.registerTribute(player, colony);
    }

    private static void onCampaignDefeat(Player player) {
        LOG.info("[CF:Siege] DÉFAITE playerUuid={}", player.getUUID());

        // Cooldown de trêve
        player.getPersistentData().putInt("TruceCooldown", TRUCE_TICKS);

        // Dissoudre le régiment
        player.level().getEntitiesOfClass(AbstractEntityCitizen.class,
                player.getBoundingBox().inflate(128.0D))
                .forEach(c -> {
                    if (GuardFollowEvent.isGuard(c)
                            && player.getUUID().toString().equals(
                                    c.getPersistentData().getString("FollowTarget"))) {
                        GuardFollowEvent.dismissGuard(c);
                    }
                });

        // Deuil dans la colonie du joueur
        IColony homeColony = IMinecoloniesAPI.getInstance().getColonyManager()
                .getIColonyByOwner(player.level(), player);
        if (homeColony != null) {
            for (ICitizenData citizen : homeColony.getCitizenManager().getCitizens()) {
                homeColony.getCitizenManager().updateCitizenMourn(citizen, true);
            }
        }

        player.sendSystemMessage(Component.literal(
                "§c§l[Frontiers] DÉFAITE — votre armée est dispersée. Trêve forcée imposée."));
    }

    // ── TICK TRÊVE ────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;
        Player player = event.player;

        int truce = player.getPersistentData().getInt("TruceCooldown");
        if (truce > 0) {
            player.getPersistentData().putInt("TruceCooldown", truce - 1);
            if (truce == 1) {
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] La trêve est terminée. Vous pouvez reprendre campagne."));
            }
        }
    }

    // ── HELPER ───────────────────────────────────────────────────────────

    private static void cancelChannel(UUID playerUuid, ChannelState state,
                                      ServerPlayer sp, String reason) {
        state.bossBar.removeAllPlayers();
        if (sp != null) {
            sp.sendSystemMessage(Component.literal(
                    "§c[Frontiers] Capture annulée (" + reason + ")."));
        }
        LOG.info("[CF:Siege] CANAL annulé playerUuid={} raison={}", playerUuid, reason);
    }

    public static boolean isUnderTruce(Player player) {
        return player.getPersistentData().getInt("TruceCooldown") > 0;
    }
}
