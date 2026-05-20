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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import java.util.List;

@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardFollowEvent {

    // ── HELPERS ────────────────────────────────────────────────────────────

    private static boolean isGuard(AbstractEntityCitizen c) {
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
        citizen.setGlowingTag(false);
    }

    private static boolean isFollowing(AbstractEntityCitizen c, Player player) {
        String uuid = c.getPersistentData().getString("FollowTarget");
        return !uuid.isEmpty() && uuid.equals(player.getUUID().toString());
    }

    /** Returns all guards currently following this player within 128 blocks. */
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

    // ── ENTITY INTERACT ────────────────────────────────────────────────────
    // Right-click on a guard           → enlist / dismiss
    // Right-click on a monster         → Focus Fire (if player has followers)
    // Shift + right-click on anything  → group command

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

        // Focus Fire — right-click on a monster while having followers
        if (event.getTarget() instanceof LivingEntity target
                && !(target instanceof AbstractEntityCitizen)
                && !(target instanceof Player)) {
            List<AbstractEntityCitizen> followers = getFollowers(player);
            if (!followers.isEmpty()) {
                String targetUuid = target.getUUID().toString();
                for (AbstractEntityCitizen guard : followers) {
                    guard.getPersistentData().putString("PriorityTarget", targetUuid);
                    // Wipe hold ground so focus fire takes precedence
                    guard.getPersistentData().putBoolean("IsHoldingGround", false);
                }
                player.sendSystemMessage(Component.literal(
                        "§6[Frontiers] Focus Fire sur " + target.getName().getString()
                                + " ! (" + followers.size() + " gardes)"));
                return;
            }
        }

        // Individual enlist / dismiss
        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen) || !isGuard(citizen)) return;

        if (isFollowing(citizen, player)) {
            dismissGuard(citizen);
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] " + citizen.getName().getString() + " quitte le régiment."));
        } else {
            enlistGuard(citizen, player);
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] " + citizen.getName().getString() + " a rejoint le régiment !"));
        }
    }

    // ── RIGHT-CLICK BLOCK ─────────────────────────────────────────────────
    // Shift + right-click on ground  → Hold Ground at those coordinates
    // (non-shift handled by RightClickItem / EntityInteract)

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        if (!event.getEntity().isShiftKeyDown()) return;

        Player player = event.getEntity();
        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (followers.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§e[Frontiers] Aucun garde en suivi à poster."));
            return;
        }

        BlockHitResult hit = event.getHitVec();
        double hx = hit.getBlockPos().getX() + 0.5;
        double hy = hit.getBlockPos().getY();
        double hz = hit.getBlockPos().getZ() + 0.5;

        for (AbstractEntityCitizen guard : followers) {
            guard.getPersistentData().putBoolean("IsHoldingGround", true);
            guard.getPersistentData().putDouble("HoldX", hx);
            guard.getPersistentData().putDouble("HoldY", hy);
            guard.getPersistentData().putDouble("HoldZ", hz);
            guard.getPersistentData().remove("PriorityTarget");
        }
        player.sendSystemMessage(Component.literal(
                "§b[Frontiers] Tenir la position ! " + followers.size()
                        + " garde(s) postés en (" + (int) hx + ", " + (int) hy + ", " + (int) hz + ")."));

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    // ── RIGHT-CLICK ITEM (air / no entity hit) ─────────────────────────────
    // Shift + air  → group command  (muster / dismiss)
    // No shift     → cancel all tactical orders (hold ground + focus fire)
    //               so guards return to normal follow mode

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        Player player = event.getEntity();

        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
            return;
        }

        // Plain right-click in air — cancel tactical orders, back to follow
        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (!followers.isEmpty()) {
            for (AbstractEntityCitizen guard : followers) {
                guard.getPersistentData().putBoolean("IsHoldingGround", false);
                guard.getPersistentData().remove("PriorityTarget");
            }
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] Retour en formation — " + followers.size() + " gardes."));
        }
    }

    // ── GROUP COMMAND (shift) ──────────────────────────────────────────────

    private static void handleGroupCommand(Player player) {
        List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));

        int followingCount = 0;
        for (AbstractEntityCitizen c : wide) {
            if (isGuard(c) && isFollowing(c, player)) followingCount++;
        }

        if (followingCount > 0) {
            int dismissed = 0;
            for (AbstractEntityCitizen c : wide) {
                if (isGuard(c) && isFollowing(c, player)) {
                    dismissGuard(c);
                    dismissed++;
                }
            }
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
            if (recruited > 0) {
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] Appel général — " + recruited + " gardes ont rejoint le régiment !"));
            } else {
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Aucun garde dans un rayon de 30 blocs."));
            }
        }
    }

    // ── ENTITY JOIN ────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        citizen.goalSelector.addGoal(0, new FollowCommanderGoal(citizen));
    }

    // ── PLAYER TICK — HasFollowers NBT pour isFoil() ──────────────────────

    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
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
