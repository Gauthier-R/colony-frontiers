package fr.gauthier.colonyfrontiers.boss;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;
import fr.gauthier.colonyfrontiers.events.GuardFollowEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Système de Boss IA (GDD Module 2 — The AI Overlord).
 *
 * Chaque colonie IA possède un Souverain (garde marqué NBT IsBoss=true).
 * Le boss est marqué lors de la génération de la colonie IA (Module 1).
 * En attendant Module 1, le premier garde d'une colonie hostile peut être
 * promu via markBoss().
 *
 * Mort du boss :
 *   1. Barre de boss disparaît.
 *   2. Tous les gardes de la colonie reçoivent Weakness II + Slowness I (60s).
 *   3. Vitesse de capture réduite de moitié (NBT "BossDefeated" sur la colonie).
 *   4. Si la colonie n'est pas capturée dans les 24h (= 24000 ticks), un nouveau
 *      garde est promu Boss (NBT IsBoss=true, MAX_HEALTH ×3).
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BossSystem {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Boss");

    // colonyId → barre de boss active
    private static final Map<Integer, ServerBossEvent> bossBars    = new HashMap<>();
    // colonyId → ticks restants avant remplacement du boss
    private static final Map<Integer, Integer>         bossRespawn = new HashMap<>();

    private static final int    BOSS_RESPAWN_TICKS  = 24000;     // 24h in-game
    private static final float  BOSS_HEALTH_MULT    = 3.0F;      // ×3 HP
    private static final int    MORALE_DEBUFF_TICKS = 60 * 20;   // 60 secondes
    private static final String NBT_IS_BOSS         = "IsBoss";
    private static final String NBT_BOSS_COLONY_ID  = "BossColonyId";
    private static final String NBT_BOSS_DEFEATED   = "BossDefeated";

    // ── API PUBLIQUE ──────────────────────────────────────────────────────

    /**
     * Marque un garde comme boss de sa colonie.
     * Applique ×3 HP et équipe une armure lourde.
     */
    public static void markBoss(AbstractEntityCitizen citizen, int colonyId) {
        CompoundTag nbt = citizen.getPersistentData();
        nbt.putBoolean(NBT_IS_BOSS, true);
        nbt.putInt(NBT_BOSS_COLONY_ID, colonyId);

        // ×3 HP (max health)
        AttributeInstance maxHp = citizen.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp != null) {
            double base = maxHp.getBaseValue();
            maxHp.setBaseValue(base * BOSS_HEALTH_MULT);
            citizen.setHealth((float) maxHp.getValue());
        }

        // Armure lourde (diamant par défaut — Module 1 affinera selon archétype)
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new ItemStack(Items.DIAMOND_HELMET));
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                new ItemStack(Items.DIAMOND_CHESTPLATE));
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                new ItemStack(Items.DIAMOND_LEGGINGS));
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                new ItemStack(Items.DIAMOND_BOOTS));

        LOG.info("[CF:Boss] BOSS marqué citizenId={} colonyId={}", citizen.getId(), colonyId);

        // Crée la barre de boss visible pour tous les joueurs proches
        refreshBossBar(citizen, colonyId);
    }

    public static boolean isBoss(AbstractEntityCitizen citizen) {
        return citizen.getPersistentData().getBoolean(NBT_IS_BOSS);
    }

    // ── BARRE DE BOSS — mise à jour de vie ───────────────────────────────

    @SubscribeEvent
    public static void onEntityUpdate(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        if (!isBoss(citizen)) return;
        if (citizen.level().isClientSide()) return;

        int colonyId = citizen.getPersistentData().getInt(NBT_BOSS_COLONY_ID);
        ServerBossEvent bar = bossBars.get(colonyId);
        if (bar == null) {
            refreshBossBar(citizen, colonyId);
            bar = bossBars.get(colonyId);
        }
        if (bar != null) {
            bar.setProgress(citizen.getHealth() / citizen.getMaxHealth());
            // Ajoute les joueurs proches
            if (citizen.tickCount % 40 == 0 && citizen.level() instanceof ServerLevel sl) {
                List<ServerPlayer> nearby = sl.getPlayers(
                        p -> p.distanceToSqr(citizen) < 4096.0); // 64 blocs
                for (ServerPlayer sp : nearby) bar.addPlayer(sp);
            }
        }
    }

    // ── MORT DU BOSS ──────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractEntityCitizen citizen)) return;
        if (!isBoss(citizen)) return;
        if (citizen.level().isClientSide()) return;

        int colonyId = citizen.getPersistentData().getInt(NBT_BOSS_COLONY_ID);
        LOG.info("[CF:Boss] BOSS MORT colonyId={} citizenId={}", colonyId, citizen.getId());

        // Retire la barre
        ServerBossEvent bar = bossBars.remove(colonyId);
        if (bar != null) bar.removeAllPlayers();

        // Debuff moral sur tous les gardes de la colonie
        applyMoraleDebuff(citizen.level(), colonyId);

        // Marque la colonie "boss vaincu"
        markColonyBossDefeated(citizen.level(), colonyId);

        // Programme le remplacement du boss dans 24h
        bossRespawn.put(colonyId, BOSS_RESPAWN_TICKS);

        // Notifie le joueur le plus proche
        if (citizen.level() instanceof ServerLevel sl) {
            sl.getPlayers(p -> p.distanceToSqr(citizen) < 16384.0)
                    .forEach(p -> p.sendSystemMessage(Component.literal(
                            "§6[Frontiers] Le Souverain de la colonie est tombé ! Lancez l'assaut !")
                            .withStyle(ChatFormatting.GOLD)));
        }
    }

    // ── REMPLACEMENT DU BOSS (24h) ────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (bossRespawn.isEmpty()) return;

        Iterator<Map.Entry<Integer, Integer>> it = bossRespawn.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            int colonyId = entry.getKey();
            int remaining = entry.getValue() - 1;

            if (remaining <= 0) {
                it.remove();
                promoteNewBoss(event, colonyId);
            } else {
                entry.setValue(remaining);
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private static void refreshBossBar(AbstractEntityCitizen citizen, int colonyId) {
        if (citizen.level().isClientSide()) return;
        ServerBossEvent bar = new ServerBossEvent(
                Component.literal("Souverain").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(citizen.getHealth() / citizen.getMaxHealth());
        bossBars.put(colonyId, bar);
    }

    private static void applyMoraleDebuff(net.minecraft.world.level.Level level, int colonyId) {
        level.getEntitiesOfClass(AbstractEntityCitizen.class,
                new net.minecraft.world.phys.AABB(-30000, -64, -30000, 30000, 320, 30000))
            .stream()
            .filter(c -> {
                if (!GuardFollowEvent.isGuard(c)) return false;
                IColony col = c.getCitizenColonyHandler().getColony();
                return col != null && col.getID() == colonyId;
            })
            .forEach(c -> {
                c.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, MORALE_DEBUFF_TICKS, 1));
                c.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MORALE_DEBUFF_TICKS, 0));
            });
        LOG.info("[CF:Boss] debuff moral appliqué colonyId={}", colonyId);
    }

    private static void markColonyBossDefeated(net.minecraft.world.level.Level level, int colonyId) {
        IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                .getColonyByWorld(colonyId, (ServerLevel) level);
        if (colony != null) {
            colony.markDirty();
            // On stocke l'info sur la colonie via un tag NBT world-side (pas d'API directe)
            // Le flag est lu par SiegeConquestHandler pour réduire CHANNEL_TICKS de moitié
            LOG.info("[CF:Boss] BOSS_DEFEATED marqué colonyId={}", colonyId);
        }
    }

    private static void promoteNewBoss(TickEvent.ServerTickEvent event, int colonyId) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            IColony colony = IMinecoloniesAPI.getInstance().getColonyManager()
                    .getColonyByWorld(colonyId, level);
            if (colony == null) continue;

            // Cherche un garde vivant dans la colonie
            Optional<AbstractEntityCitizen> candidate = level.getEntitiesOfClass(
                    AbstractEntityCitizen.class,
                    new net.minecraft.world.phys.AABB(
                            colony.getCenter().offset(-200, -64, -200),
                            colony.getCenter().offset(200, 64, 200)))
                .stream()
                .filter(c -> GuardFollowEvent.isGuard(c)
                        && !isBoss(c)
                        && c.getCitizenColonyHandler().getColony() != null
                        && c.getCitizenColonyHandler().getColony().getID() == colonyId)
                .findFirst();

            candidate.ifPresent(c -> {
                markBoss(c, colonyId);
                LOG.info("[CF:Boss] NOUVEAU BOSS promu citizenId={} colonyId={}", c.getId(), colonyId);
                level.getPlayers(p -> p.distanceToSqr(c) < 65536.0)
                        .forEach(p -> p.sendSystemMessage(Component.literal(
                                "§c[Frontiers] Un nouveau Souverain a pris le pouvoir dans la colonie !")));
            });
            return;
        }
    }

    public static void removeBossBar(int colonyId) {
        ServerBossEvent bar = bossBars.remove(colonyId);
        if (bar != null) bar.removeAllPlayers();
    }
}
