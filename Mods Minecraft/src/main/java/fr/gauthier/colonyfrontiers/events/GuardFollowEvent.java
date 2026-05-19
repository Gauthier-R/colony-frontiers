package fr.gauthier.colonyfrontiers.events;

import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.ai.FollowCommanderGoal;
import fr.gauthier.colonyfrontiers.items.CampaignBannerItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

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

    // ── INDIVIDUAL INTERACTION (right-click on guard) ──────────────────────

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
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] " + citizen.getName().getString() + " quitte le régiment."));
        } else {
            enlistGuard(citizen, player);
            player.sendSystemMessage(Component.literal(
                    "§a[Frontiers] " + citizen.getName().getString() + " a rejoint le régiment !"));
        }
    }

    // ── SHIFT+RIGHT-CLICK IN AIR ───────────────────────────────────────────

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;
        if (!event.getEntity().isShiftKeyDown()) return;
        handleGroupCommand(event.getEntity());
    }

    // ── GROUP COMMAND ──────────────────────────────────────────────────────

    private static void handleGroupCommand(Player player) {
        // Count followers in a wide radius (128 so combat-leashed guards are included)
        java.util.List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(128.0D));

        int followingCount = 0;
        for (AbstractEntityCitizen c : wide) {
            if (isGuard(c) && isFollowing(c, player)) followingCount++;
        }

        if (followingCount > 0) {
            // Dissolution — dismiss all followers found in that wide radius
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
            // Muster — recruit all guards within 30 blocks
            java.util.List<AbstractEntityCitizen> close = player.level().getEntitiesOfClass(
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

    // ── ENTITY JOIN — inject Goal into every citizen on spawn ─────────────
    // Goal.canUse() checks isGuard() each tick, so non-guards idle-out immediately.

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        citizen.goalSelector.addGoal(0, new FollowCommanderGoal(citizen));
    }

    // ── PLAYER TICK — maintain HasFollowers NBT for isFoil() ──────────────
    // 128-block scan radius covers guards that are leashed out to 30 blocks
    // plus teleport safety threshold (45 blocks). Once per second is sufficient.

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
