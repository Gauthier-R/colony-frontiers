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

    // ═══════════════════════════════════════════════════════════════
    // HELPERS : Vérification stricte du rôle de garde
    // ═══════════════════════════════════════════════════════════════

    private static boolean isGuard(AbstractEntityCitizen c) {
        return c.getCitizenData() != null && c.getCitizenData().getJob() != null
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

    // ═══════════════════════════════════════════════════════════════
    // ENTITY INTERACT : Clic individuel ou Shift+Clic groupe
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!(event.getItemStack().getItem() instanceof CampaignBannerItem)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (event.getLevel().isClientSide()) return;

        // SHIFT+CLIC → Commande de groupe
        if (player.isShiftKeyDown()) {
            handleGroupCommand(player);
            return;
        }

        // CLIC NORMAL → Recrutement / Renvoi individuel
        if (event.getTarget() instanceof AbstractEntityCitizen citizen && isGuard(citizen)) {
            String uuid = citizen.getPersistentData().getString("FollowTarget");
            if (uuid != null && !uuid.isEmpty() && uuid.equals(player.getUUID().toString())) {
                dismissGuard(citizen);
                player.sendSystemMessage(Component.literal(
                        "§c[Frontiers] " + citizen.getName().getString() + " quitte le régiment."));
            } else {
                enlistGuard(citizen, player);
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] " + citizen.getName().getString() + " a rejoint le régiment !"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RIGHT CLICK ITEM : Shift+Clic dans le vide
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (!event.getLevel().isClientSide() && event.getItemStack().getItem() instanceof CampaignBannerItem
                && player.isShiftKeyDown()) {
            handleGroupCommand(player);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GROUP COMMAND : Appel Général / Dissolution
    // ═══════════════════════════════════════════════════════════════

    private static void handleGroupCommand(Player player) {
        java.util.List<AbstractEntityCitizen> wide = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(64.0D));

        // Comptage des gardes déjà en suivi
        int followingCount = 0;
        for (AbstractEntityCitizen c : wide) {
            if (!isGuard(c)) continue;
            String uuid = c.getPersistentData().getString("FollowTarget");
            if (uuid != null && uuid.equals(player.getUUID().toString())) followingCount++;
        }

        if (followingCount > 0) {
            // DISSOLUTION : Retire tous les gardes en suivi (rayon 64)
            int count = 0;
            for (AbstractEntityCitizen c : wide) {
                if (!isGuard(c)) continue;
                String uuid = c.getPersistentData().getString("FollowTarget");
                if (uuid != null && uuid.equals(player.getUUID().toString())) {
                    dismissGuard(c);
                    count++;
                }
            }
            player.sendSystemMessage(Component.literal(
                    "§c[Frontiers] Régiment dissous ! " + count + " gardes retournent à leurs postes."));
        } else {
            // APPEL GÉNÉRAL : Recrute tous les gardes dans un rayon de 30
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
                        "§a[Frontiers] Appel général ! " + recruited + " gardes ont rejoint le régiment !"));
            } else {
                player.sendSystemMessage(Component.literal(
                        "§e[Frontiers] Aucun garde trouvé dans un rayon de 30 blocs."));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ENTITY JOIN : Injection du Goal sur tous les citoyens
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof AbstractEntityCitizen citizen) {
            citizen.goalSelector.addGoal(0, new FollowCommanderGoal(citizen));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYER TICK : Cache NBT pour le isFoil de la bannière
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || event.player.level().isClientSide())
            return;

        Player player = event.player;
        if (player.tickCount % 20 != 0) return;

        boolean hasFollowers = false;
        java.util.List<AbstractEntityCitizen> nearby = player.level().getEntitiesOfClass(
                AbstractEntityCitizen.class, player.getBoundingBox().inflate(64.0D));

        for (AbstractEntityCitizen c : nearby) {
            if (!isGuard(c)) continue;
            String uuid = c.getPersistentData().getString("FollowTarget");
            if (uuid != null && uuid.equals(player.getUUID().toString())) {
                hasFollowers = true;
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
}