package fr.gauthier.colonyfrontiers.events;

import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.ai.FollowCommanderGoal;
import fr.gauthier.colonyfrontiers.items.CampaignBannerItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des interactions bannière de campagne.
 *
 * Fix double-fire Forge (RightClickBlock + RightClickItem sur le même clic) :
 * Quand RightClickBlock traite une commande, on enregistre l'UUID du joueur dans
 * blockHandledThisTick. RightClickItem ignore ces joueurs pendant ce tick serveur.
 * Le set est vidé au début de chaque ServerTick.
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardFollowEvent {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Events");

    /** Joueurs pour lesquels RightClickBlock a déjà géré l'événement ce tick. */
    private static final Set<UUID> blockHandledThisTick = new HashSet<>();

    // ── HELPERS ────────────────────────────────────────────────────────────

    static boolean isGuard(AbstractEntityCitizen c) {
        return c.getCitizenData() != null
                && c.getCitizenData().getJob() != null
                && c.getCitizenData().getJob().isGuard();
    }

    private static void enlistGuard(AbstractEntityCitizen citizen, Player player) {
        citizen.getPersistentData().putString("FollowTarget", player.getUUID().toString());
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(true);
    }

    private static void dismissGuard(AbstractEntityCitizen citizen) {
        citizen.getPersistentData().remove("FollowTarget");
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.getPersistentData().remove("PriorityTarget");
        citizen.getPersistentData().putBoolean("IsHoldingGround", false);
        citizen.setGlowingTag(false);
    }

    private static boolean isFollowing(AbstractEntityCitizen c, Player player) {
        String uuid = c.getPersistentData().getString("FollowTarget");
        return !uuid.isEmpty() && uuid.equals(player.getUUID().toString());
    }

    /** Gardes en suivi du joueur, dans un rayon de 128 blocs. */
    private static List<AbstractEntityCitizen> getFollowers(Player player) {
        String playerUuid = player.getUUID().toString();
        return player.level().getEntitiesOfClass(
                        AbstractEntityCitizen.class,
                        player.getBoundingBox().inflate(128.0D))
                .stream()
                .filter(c -> isGuard(c) && playerUuid.equals(
                        c.getPersistentData().getString("FollowTarget")))
                .toList();
    }

    // ── SERVER TICK — vidage du set anti-double-fire ───────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            blockHandledThisTick.clear();
        }
    }

    // ── INTERACT ENTITÉ ────────────────────────────────────────────────────
    // Clic simple sur garde       → enrôler / renvoyer
    // Clic simple sur monstre     → Focus Fire (si followers)
    // Shift + clic sur quoi       → commande de groupe

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();

        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
            return;
        }

        // Focus Fire — clic sur une entité hostile vivante
        if (event.getTarget() instanceof LivingEntity target
                && !(target instanceof AbstractEntityCitizen)
                && !(target instanceof Player)) {
            List<AbstractEntityCitizen> followers = getFollowers(player);
            if (!followers.isEmpty()) {
                String targetUuid = target.getUUID().toString();
                for (AbstractEntityCitizen guard : followers) {
                    guard.getPersistentData().putString("PriorityTarget", targetUuid);
                    guard.getPersistentData().putBoolean("IsHoldingGround", false);
                }
                LOG.info("[CF:Event] FOCUS_FIRE target={} uuid={} guards={}",
                        target.getName().getString(), targetUuid, followers.size());
                player.sendSystemMessage(Component.literal(
                        "§6[Frontiers] Focus Fire sur " + target.getName().getString()
                                + " ! (" + followers.size() + " gardes)"));
                return;
            }
        }

        // Enrôler / renvoyer individuel
        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen) || !isGuard(citizen)) return;

        if (isFollowing(citizen, player)) {
            dismissGuard(citizen);
            LOG.info("[CF:Event] DISMISS guard={} id={}", citizen.getName().getString(), citizen.getId());
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] " + citizen.getName().getString() + " quitte le régiment."));
        } else {
            enlistGuard(citizen, player);
            LOG.info("[CF:Event] ENLIST guard={} id={}", citizen.getName().getString(), citizen.getId());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] " + citizen.getName().getString() + " a rejoint le régiment !"));
        }
    }

    // ── RIGHT-CLICK BLOC ──────────────────────────────────────────────────
    // Shift + clic sur bloc → Hold Ground aux coordonnées du bloc
    // Clic simple sur bloc  → cancel ordres tactiques (retour suivi)

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        Player player = event.getEntity();
        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (followers.isEmpty()) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        // Marquer que ce joueur a été traité par RightClickBlock ce tick
        blockHandledThisTick.add(player.getUUID());

        if (player.isShiftKeyDown()) {
            // HOLD GROUND
            BlockHitResult hit = event.getHitVec();
            // Centre du bloc en X/Z, surface supérieure en Y
            double hx = hit.getBlockPos().getX() + 0.5;
            double hy = hit.getBlockPos().getY() + 1.0;
            double hz = hit.getBlockPos().getZ() + 0.5;

            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", true);
                guard.getPersistentData().putDouble("HoldX", hx);
                guard.getPersistentData().putDouble("HoldY", hy);
                guard.getPersistentData().putDouble("HoldZ", hz);
                guard.getPersistentData().remove("PriorityTarget");
                guard.getNavigation().stop();
            }
            LOG.info("[CF:Event] HOLD_GROUND pos=({},{},{}) guards={}", (int)hx, (int)hy, (int)hz, followers.size());
            player.sendSystemMessage(Component.literal(
                    "§b[Frontiers] Tenir la position ! " + followers.size()
                            + " garde(s) postés en ("
                            + (int) hx + ", " + (int) hy + ", " + (int) hz + ")."));
        } else {
            // Clic simple sur un bloc → annuler les ordres tactiques
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
                guard.getPersistentData().remove("PriorityTarget");
            }
            LOG.info("[CF:Event] CANCEL_ORDERS guards={}", followers.size());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Ordres annulés — retour en formation (" + followers.size() + " gardes)."));
        }
    }

    // ── RIGHT-CLICK AIR ───────────────────────────────────────────────────
    // Shift + air → commande de groupe
    // Clic simple + air → annuler ordres tactiques (retour suivi)
    // Ignoré si RightClickBlock a déjà traité ce même clic ce tick.

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        Player player = event.getEntity();

        // Anti double-fire : Forge fire RightClickItem après RightClickBlock
        if (blockHandledThisTick.contains(player.getUUID())) return;

        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
            return;
        }

        // Clic simple dans l'air → annuler ordres tactiques
        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (!followers.isEmpty()) {
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
                guard.getPersistentData().remove("PriorityTarget");
            }
            LOG.info("[CF:Event] CANCEL_ORDERS (air) guards={}", followers.size());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Retour en formation — " + followers.size() + " gardes."));
        }
    }

    // ── COMMANDE DE GROUPE (shift) ─────────────────────────────────────────

    private static void handleGroupCommand(Player player) {
        List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));

        int followingCount = (int) wide.stream()
                .filter(c -> isGuard(c) && isFollowing(c, player))
                .count();

        if (followingCount > 0) {
            int dismissed = 0;
            for (AbstractEntityCitizen c : wide) {
                if (isGuard(c) && isFollowing(c, player)) {
                    dismissGuard(c);
                    dismissed++;
                }
            }
            LOG.info("[CF:Event] DISSOLVE dismissed={}", dismissed);
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] Régiment dissous — " + dismissed + " gardes retournent à leurs postes."));
        } else {
            List<AbstractEntityCitizen> close = player.level().getEntitiesOfClass(
                    AbstractEntityCitizen.class, player.getBoundingBox().inflate(30.0D));
            int recruited = 0;
            for (AbstractEntityCitizen c : close) {
                if (!isGuard(c)) continue;
                enlistGuard(c, player);
                recruited++;
            }
            LOG.info("[CF:Event] MUSTER recruited={}", recruited);
            if (recruited > 0) {
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] Appel général — " + recruited + " gardes ont rejoint le régiment !"));
            } else {
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Aucun garde dans un rayon de 30 blocs."));
            }
        }
    }

    // ── ENTITY JOIN — injection du Goal ───────────────────────────────────

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        citizen.goalSelector.addGoal(0, new FollowCommanderGoal(citizen));
    }

    // ── PLAYER TICK — HasFollowers NBT pour isFoil() ──────────────────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;
        if (event.player.tickCount % 20 != 0) return;

        Player player = event.player;
        String playerUuid = player.getUUID().toString();

        boolean hasFollowers = player.level()
                .getEntitiesOfClass(AbstractEntityCitizen.class,
                        player.getBoundingBox().inflate(128.0D))
                .stream()
                .filter(GuardFollowEvent::isGuard)
                .anyMatch(c -> playerUuid.equals(c.getPersistentData().getString("FollowTarget")));

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof CampaignBannerItem) {
                stack.getOrCreateTag().putBoolean("HasFollowers", hasFollowers);
            }
        }
    }
}
