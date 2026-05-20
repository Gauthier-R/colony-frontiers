package fr.gauthier.colonyfrontiers.events;

import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.ai.FollowCommanderGoal;
import fr.gauthier.colonyfrontiers.items.CampaignBannerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
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
 * Contrôles de la Bannière de Campagne :
 *
 *  Clic droit sur garde          → Enrôler / Renvoyer ce garde
 *  Shift + Clic droit            → Rassembler tous les gardes proches (30 blocs)
 *                                   ou dissoudre tous les gardes sous contrôle
 *  Clic gauche sur mob hostile   → Focus Fire : tous les gardes attaquent cette cible
 *  Shift + Clic gauche sur bloc  → Poste de garde : les gardes défendent ce point
 *                                   (refaire pour annuler)
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardFollowEvent {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Events");

    /** UUID des joueurs dont RightClickBlock a déjà géré le clic ce tick. */
    private static final Set<UUID> blockHandledThisTick = new HashSet<>();

    // ── HELPERS ────────────────────────────────────────────────────────────

    static boolean isGuard(AbstractEntityCitizen c) {
        return c.getCitizenData() != null
                && c.getCitizenData().getJob() != null
                && c.getCitizenData().getJob().isGuard();
    }

    private static boolean hasBannerInHand(Player player) {
        return player.getMainHandItem().getItem() instanceof CampaignBannerItem
                || player.getOffhandItem().getItem() instanceof CampaignBannerItem;
    }

    private static void enlistGuard(AbstractEntityCitizen citizen, Player player) {
        citizen.getPersistentData().putString("FollowTarget", player.getUUID().toString());
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(true);
    }

    static void dismissGuard(AbstractEntityCitizen citizen) {
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

    // ── PARTICULES HOLD GROUND ────────────────────────────────────────────
    // Anneau de particules d'âme bleues (soul_fire_flame) au sol,
    // rendu chaque seconde sur le serveur via sendParticles.

    private static void spawnHoldGroundParticles(ServerLevel level, double hx, double hy, double hz) {
        int steps = 16;
        double radius = 3.0;
        for (int i = 0; i < steps; i++) {
            double angle = (2 * Math.PI / steps) * i;
            double px = hx + Math.cos(angle) * radius;
            double pz = hz + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    px, hy + 0.05, pz, 1, 0.0, 0.05, 0.0, 0.0);
        }
    }

    // ── SERVER TICK ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        blockHandledThisTick.clear();
    }

    // ── PLAYER TICK — particules Hold Ground + HasFollowers NBT ──────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player = event.player;
        ServerLevel level = (ServerLevel) player.level();
        String playerUuid = player.getUUID().toString();

        // Particules Hold Ground — 1× par seconde
        if (player.tickCount % 20 == 0) {
            List<AbstractEntityCitizen> followers = player.level().getEntitiesOfClass(
                    AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));
            for (AbstractEntityCitizen c : followers) {
                if (!isGuard(c)) continue;
                if (!playerUuid.equals(c.getPersistentData().getString("FollowTarget"))) continue;
                if (!c.getPersistentData().getBoolean("IsHoldingGround")) continue;
                double hx = c.getPersistentData().getDouble("HoldX");
                double hy = c.getPersistentData().getDouble("HoldY");
                double hz = c.getPersistentData().getDouble("HoldZ");
                spawnHoldGroundParticles(level, hx, hy, hz);
                break; // un seul anneau par point (tous les gardes sur le même point)
            }
        }

        // HasFollowers NBT pour isFoil() — 1× par seconde
        if (player.tickCount % 20 == 0) {
            boolean hasFollowers = player.level()
                    .getEntitiesOfClass(AbstractEntityCitizen.class,
                            player.getBoundingBox().inflate(128.0D))
                    .stream()
                    .filter(GuardFollowEvent::isGuard)
                    .anyMatch(c -> playerUuid.equals(
                            c.getPersistentData().getString("FollowTarget")));

            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.getItem() instanceof CampaignBannerItem) {
                    stack.getOrCreateTag().putBoolean("HasFollowers", hasFollowers);
                }
            }
        }
    }

    // ── CLIC GAUCHE SUR ENTITÉ — Focus Fire ──────────────────────────────
    // Clic gauche sur mob hostile avec bannière en main → Focus Fire
    // Clic gauche sur mob hostile en shift → Poste de garde (toggle)
    //   mais sur une entité ça n'a pas de sens → on garde uniquement Focus Fire ici.

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (!hasBannerInHand(player)) return;
        if (player.level().isClientSide()) return;

        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (target instanceof AbstractEntityCitizen || target instanceof Player) return;
        if (!target.isAlive()) return;

        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (followers.isEmpty()) return;

        event.setCanceled(true); // empêche le dégât direct du joueur

        if (player.isShiftKeyDown()) {
            // Shift + clic gauche sur mob → annuler Focus Fire
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().remove("PriorityTarget");
            }
            LOG.info("[CF:Event] CANCEL_FOCUS guards={}", followers.size());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Focus Fire annulé — " + followers.size() + " gardes."));
        } else {
            // Clic gauche sur mob → Focus Fire
            String targetUuid = target.getUUID().toString();
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putString("PriorityTarget", targetUuid);
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
            }
            LOG.info("[CF:Event] FOCUS_FIRE cible={} uuid={} gardes={}",
                    target.getName().getString(), targetUuid, followers.size());
            player.sendSystemMessage(Component.literal(
                    "§6[Frontiers] Focus Fire → " + target.getName().getString()
                            + " ! (" + followers.size() + " gardes)"));
        }
    }

    // ── CLIC DROIT SUR GARDE — enrôler / renvoyer ─────────────────────────

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

        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen) || !isGuard(citizen)) return;

        if (isFollowing(citizen, player)) {
            dismissGuard(citizen);
            LOG.info("[CF:Event] DISMISS garde={} id={}", citizen.getName().getString(), citizen.getId());
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] " + citizen.getName().getString() + " quitte le régiment."));
        } else {
            enlistGuard(citizen, player);
            LOG.info("[CF:Event] ENLIST garde={} id={}", citizen.getName().getString(), citizen.getId());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] " + citizen.getName().getString() + " rejoint le régiment !"));
        }
    }

    // ── SHIFT + CLIC GAUCHE SUR BLOC — Hold Ground toggle ────────────────
    // Intercepté via LeftClickBlock.

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!hasBannerInHand(event.getEntity())) return;
        if (!event.getEntity().isShiftKeyDown()) return;

        Player player = event.getEntity();
        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (followers.isEmpty()) return;

        event.setCanceled(true);

        // Toggle : si au moins un garde est déjà en Hold Ground → annuler
        boolean anyHolding = followers.stream()
                .anyMatch(c -> c.getPersistentData().getBoolean("IsHoldingGround"));

        if (anyHolding) {
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
            }
            LOG.info("[CF:Event] HOLD_GROUND annulé gardes={}", followers.size());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Poste annulé — retour en formation (" + followers.size() + " gardes)."));
        } else {
            BlockPos pos = event.getPos();
            double hx = pos.getX() + 0.5;
            double hy = pos.getY() + 1.0;
            double hz = pos.getZ() + 0.5;

            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", true);
                guard.getPersistentData().putDouble("HoldX", hx);
                guard.getPersistentData().putDouble("HoldY", hy);
                guard.getPersistentData().putDouble("HoldZ", hz);
                guard.getPersistentData().remove("PriorityTarget");
                guard.getNavigation().stop();
            }
            LOG.info("[CF:Event] HOLD_GROUND pos=({},{},{}) gardes={}", (int)hx, (int)hy, (int)hz, followers.size());
            player.sendSystemMessage(Component.literal(
                    "§b[Frontiers] Tenir la position ! " + followers.size()
                            + " garde(s) postés en (" + (int)hx + ", " + (int)hy + ", " + (int)hz + ")."));
        }
    }

    // ── CLIC DROIT SUR BLOC — anti double-fire uniquement ─────────────────
    // Plus de commande ici : tout est sur clic gauche (Hold Ground) ou clic droit entité.
    // On bloque juste le double-fire Forge.

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        blockHandledThisTick.add(event.getEntity().getUUID());
        // Pas d'action : on laisse passer pour ne pas bloquer l'interaction avec les blocs normaux
    }

    // ── SHIFT + CLIC DROIT DANS L'AIR ─────────────────────────────────────
    // Ignoré si RightClickBlock a déjà traité ce tick.

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        if (blockHandledThisTick.contains(event.getEntity().getUUID())) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
        }
    }

    // ── COMMANDE DE GROUPE — Shift + Clic droit ───────────────────────────

    private static void handleGroupCommand(Player player) {
        List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));

        long followingCount = wide.stream()
                .filter(c -> isGuard(c) && isFollowing(c, player))
                .count();

        if (followingCount > 0) {
            int dismissed = 0;
            for (AbstractEntityCitizen c : wide) {
                if (isGuard(c) && isFollowing(c, player)) { dismissGuard(c); dismissed++; }
            }
            LOG.info("[CF:Event] DISSOLVE renvoyés={}", dismissed);
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
            LOG.info("[CF:Event] RASSEMBLEMENT recrutés={}", recruited);
            if (recruited > 0)
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] Appel général — " + recruited + " gardes ont rejoint le régiment !"));
            else
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Aucun garde dans un rayon de 30 blocs."));
        }
    }

    // ── ENTITY JOIN ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        citizen.goalSelector.addGoal(0, new FollowCommanderGoal(citizen));
    }
}
