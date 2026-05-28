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
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import java.util.List;

/**
 * Contrôles de la Bannière de Campagne (schéma GDD) :
 *
 *  Clic droit sur garde             → Enrôler / Renvoyer ce garde
 *  Clic droit sur mob hostile       → Focus Fire sur cette cible
 *  Shift + Clic droit sur bloc      → Poste de garde (Hold Ground toggle)
 *  Shift + Clic droit dans l'air    → Rassembler (0 suiveurs) / Dissoudre (>0 suiveurs)
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardFollowEvent {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Events");

    // ── HELPERS ────────────────────────────────────────────────────────────

    public static boolean isGuard(AbstractEntityCitizen c) {
        return c.getCitizenData() != null
                && c.getCitizenData().getJob() != null
                && c.getCitizenData().getJob().isGuard();
    }

    private static void enlistGuard(AbstractEntityCitizen citizen, Player player) {
        citizen.getPersistentData().putString("FollowTarget", player.getUUID().toString());
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(true);
    }

    public static void dismissGuard(AbstractEntityCitizen citizen) {
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

    // ── PLAYER TICK — particules Hold Ground + HasFollowers NBT ──────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player = event.player;
        ServerLevel level = (ServerLevel) player.level();
        String playerUuid = player.getUUID().toString();

        if (player.tickCount % 20 != 0) return;

        List<AbstractEntityCitizen> followers = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));

        boolean hasFollowers = false;

        for (AbstractEntityCitizen c : followers) {
            if (!isGuard(c)) continue;
            if (!playerUuid.equals(c.getPersistentData().getString("FollowTarget"))) continue;
            hasFollowers = true;
            if (c.getPersistentData().getBoolean("IsHoldingGround")) {
                double hx = c.getPersistentData().getDouble("HoldX");
                double hy = c.getPersistentData().getDouble("HoldY");
                double hz = c.getPersistentData().getDouble("HoldZ");
                spawnHoldGroundParticles(level, hx, hy, hz);
                break;
            }
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof CampaignBannerItem) {
                stack.getOrCreateTag().putBoolean("HasFollowers", hasFollowers);
            }
        }
    }

    // ── CLIC DROIT SUR ENTITÉ ─────────────────────────────────────────────
    // • Clic droit sur garde  → Enrôler / Renvoyer
    // • Clic droit sur mob hostile → Focus Fire
    // • Shift + clic droit (entité quelconque) → Commande de groupe

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();

        // Shift + clic droit → commande de groupe (peu importe la cible)
        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
            return;
        }

        // Clic droit sur garde → enrôler / renvoyer
        if (event.getTarget() instanceof AbstractEntityCitizen citizen && isGuard(citizen)) {
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
            return;
        }

        // Clic droit sur mob hostile (non-garde, non-joueur) → Focus Fire
        if (event.getTarget() instanceof LivingEntity target
                && !(target instanceof AbstractEntityCitizen)
                && !(target instanceof Player)
                && target.isAlive()) {

            List<AbstractEntityCitizen> followers = getFollowers(player);
            if (followers.isEmpty()) return;

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

    // ── CLIC DROIT SUR BLOC ───────────────────────────────────────────────
    // Shift + Clic droit sur bloc → Poster les gardes (Hold Ground)
    // Clic droit normal sur bloc  → Rappeler les gardes (annuler Hold Ground)

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        Player player = event.getEntity();
        List<AbstractEntityCitizen> followers = getFollowers(player);

        if (player.isShiftKeyDown()) {
            // Shift + clic droit → poster les gardes
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);

            if (followers.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Aucun garde à poster — enrôlez des gardes d'abord."));
                return;
            }

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

        } else {
            // Clic droit normal → rappeler (annuler Hold Ground) si au moins un garde est posté
            boolean anyHolding = followers.stream()
                    .anyMatch(c -> c.getPersistentData().getBoolean("IsHoldingGround"));
            if (!anyHolding) return;

            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);

            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
            }
            LOG.info("[CF:Event] HOLD_GROUND rappel gardes={}", followers.size());
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Retour en formation — " + followers.size() + " garde(s) rappelé(s)."));
        }
    }

    // ── SHIFT + CLIC DROIT DANS L'AIR ─────────────────────────────────────
    // RightClickItem se déclenche seulement quand le raycasting ne touche rien.
    // Guard/Entity est déjà traité par EntityInteract ; ce handler couvre le vide.

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
        }
    }

    // ── COMMANDE DE GROUPE — Shift + Clic droit ───────────────────────────
    // 0 suiveurs → recruter dans 30 blocs
    // >0 suiveurs → dissoudre dans 64 blocs (GDD)

    private static void handleGroupCommand(Player player) {
        List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(64.0D));

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
