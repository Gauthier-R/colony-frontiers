package fr.gauthier.colonyfrontiers.logistics;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.events.GuardFollowEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Logistique de campagne (GDD Module 2).
 *
 * Chaque garde enrôlé consomme des provisions depuis l'inventaire du joueur
 * (tout item comestible valant ≥ 2 nutrition compte comme 1 ration).
 * Intervalle : 1 ration / garde toutes les 5 minutes réelles (6000 ticks).
 *
 * Si les provisions s'épuisent, chaque garde recruté reçoit un effet Weakness I
 * permanent (rafraîchi toutes les 30 secondes) jusqu'à réapprovisionnement.
 *
 * Réapprovisionnement : le joueur remet de la nourriture dans son inventaire.
 * Le malus Weakness est levé dès la prochaine ration consommée avec succès.
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CampaignLogisticsHandler {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Logistics");

    private static final int    RATION_INTERVAL    = 6000;  // ticks entre chaque consommation de ration
    private static final int    MIN_NUTRITION      = 2;     // nutrition minimale pour compter comme ration
    private static final int    WEAKNESS_DURATION  = 620;   // ticks (rafraîchi à 600 pour être permanent)
    private static final String NBT_OUT_OF_SUPPLY  = "OutOfSupply";
    private static final String NBT_RATION_TIMER   = "RationTimer";

    // ── PLAYER TICK ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide()) return;

        Player player = event.player;

        List<AbstractEntityCitizen> followers = getFollowers(player);
        if (followers.isEmpty()) {
            // Plus de gardes — réinitialise les compteurs
            player.getPersistentData().putInt(NBT_RATION_TIMER, 0);
            player.getPersistentData().putBoolean(NBT_OUT_OF_SUPPLY, false);
            return;
        }

        // Rafraîchir le malus si manque de provisions
        if (player.getPersistentData().getBoolean(NBT_OUT_OF_SUPPLY)) {
            if (player.tickCount % 600 == 0) {
                applyWeaknessToAll(followers);
            }
        }

        // Décompte de la ration
        int timer = player.getPersistentData().getInt(NBT_RATION_TIMER) + 1;
        player.getPersistentData().putInt(NBT_RATION_TIMER, timer);

        if (timer < RATION_INTERVAL) return;

        // Réinitialise le timer
        player.getPersistentData().putInt(NBT_RATION_TIMER, 0);

        // Consomme autant de rations que de gardes
        int needed = followers.size();
        int consumed = consumeRations(player, needed);

        if (consumed >= needed) {
            // Réapprovisionnement réussi
            if (player.getPersistentData().getBoolean(NBT_OUT_OF_SUPPLY)) {
                player.getPersistentData().putBoolean(NBT_OUT_OF_SUPPLY, false);
                removeWeaknessFromAll(followers);
                player.sendSystemMessage(Component.literal(
                        "§a[Frontiers] Le régiment est réapprovisionné — malus levé."));
                LOG.info("[CF:Logistics] réappro ok gardes={}", followers.size());
            } else {
                LOG.debug("[CF:Logistics] rations consommées={}/{}", consumed, needed);
            }
        } else {
            // Provisions insuffisantes
            if (!player.getPersistentData().getBoolean(NBT_OUT_OF_SUPPLY)) {
                player.getPersistentData().putBoolean(NBT_OUT_OF_SUPPLY, true);
                player.sendSystemMessage(Component.literal(
                        "§c[Frontiers] Provisions épuisées ! Vos gardes souffrent de Faiblesse."));
                LOG.warn("[CF:Logistics] OUT_OF_SUPPLY gardes={}", followers.size());
            }
            applyWeaknessToAll(followers);
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private static List<AbstractEntityCitizen> getFollowers(Player player) {
        String playerUuid = player.getUUID().toString();
        return player.level().getEntitiesOfClass(
                        AbstractEntityCitizen.class,
                        player.getBoundingBox().inflate(128.0D))
                .stream()
                .filter(c -> GuardFollowEvent.isGuard(c)
                        && playerUuid.equals(c.getPersistentData().getString("FollowTarget")))
                .toList();
    }

    /**
     * Consomme jusqu'à {@code needed} rations depuis l'inventaire du joueur.
     * Un item comestible avec nutrition ≥ MIN_NUTRITION compte comme 1 ration.
     * Retourne le nombre effectivement consommé.
     */
    private static int consumeRations(Player player, int needed) {
        int consumed = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && consumed < needed; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.getItem().getFoodProperties(stack, player);
            if (food == null || food.getNutrition() < MIN_NUTRITION) continue;
            stack.shrink(1);
            consumed++;
        }
        return consumed;
    }

    private static void applyWeaknessToAll(List<AbstractEntityCitizen> guards) {
        for (AbstractEntityCitizen guard : guards) {
            guard.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_DURATION, 0, false, true));
        }
    }

    private static void removeWeaknessFromAll(List<AbstractEntityCitizen> guards) {
        for (AbstractEntityCitizen guard : guards) {
            guard.removeEffect(MobEffects.WEAKNESS);
        }
    }

    public static boolean isOutOfSupply(Player player) {
        return player.getPersistentData().getBoolean(NBT_OUT_OF_SUPPLY);
    }
}
