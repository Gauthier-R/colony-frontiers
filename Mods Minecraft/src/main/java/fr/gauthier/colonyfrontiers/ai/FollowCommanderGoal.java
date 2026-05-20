package fr.gauthier.colonyfrontiers.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Suivi militaire pour gardes MineColonies — Phase 2.
 *
 * RÈGLE FONDAMENTALE : la SM MineColonies reste figée EN PERMANENCE pendant
 * toute la durée du suivi. On ne l'appelle JAMAIS via restoreSM() en cours de
 * tick. Cela évite que GUARD_GUARD / GUARD_PATROL / HOME reprennent.
 *
 * validateNavigation() annule chaque tick tout chemin qui dévie vers la maison
 * du garde ou sa tour : protection contre les appels directs à navigation.moveTo()
 * hors du système de Goals (colony tick, building tick).
 *
 * PRIORITÉS dans tick() :
 *  ① Manger de force si sat == 0 (ne peut pas combattre)
 *  ② Téléportation de sécurité (> 45 blocs)
 *  ③ Forced Retreat
 *  ④ Priority Target (Focus Fire) — interrompt l'eating si sat > 0
 *  ⑤ Leash check (> 30 blocs)
 *  ⑥ Combat standard — interrompt l'eating si sat > 0
 *  ⑦ Hold Ground (coordonnées statiques)
 *  ⑧ Eating volontaire (sat ≤ 3)
 *  ⑨ Suivi / zone de confort
 */
public class FollowCommanderGoal extends Goal {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Goal");

    private enum Mode { NONE, FOLLOW, COMFORT, COMBAT, PRIORITY, HOLD_GROUND, RETREAT, TELEPORT, EATING }
    private Mode currentMode = Mode.NONE;
    private int  logThrottle = 0;

    private final AbstractEntityCitizen citizen;
    private Player commander;

    private int     pathRecalcDelay  = 0;
    private int     attackCooldown   = 0;
    private int     eatCooldown      = 0;  // timer indépendant pour tryEatFood()
    private int     scanCooldown     = 0;
    private int     hungerTicks      = 0;
    private double  lastSaturation   = -1.0D;
    private int     originalTickRate = 1;
    private boolean isEating         = false;

    // ── CONSTANTES ─────────────────────────────────────────────────────────
    private static final int    SLOWED_TICK_RATE  = 999_999;
    private static final int    SCAN_INTERVAL     = 4;
    private static final int    METABOLISM_FACTOR = 5;        // ×5 baisse de faim plus lente
    private static final double EATING_START      = 3.0D;     // commence à manger sous ce seuil
    private static final double EATING_STOP       = 6.0D;     // arrête de manger au-dessus
    private static final double COMFORT_NEAR_SQ   = 36.0D;    // 6²
    private static final double COMFORT_FAR_SQ    = 225.0D;   // 15²
    private static final double LEASH_SQ          = 900.0D;   // 30²
    private static final double RETREAT_END_SQ    = 100.0D;   // 10²
    private static final double TELEPORT_SQ       = 2025.0D;  // 45²
    private static final double PRIORITY_MAX_SQ   = 2500.0D;  // 50²
    private static final double MELEE_RANGE_SQ    = 6.25D;    // 2.5²
    private static final double HOLD_ARRIVE_2D_SQ = 9.0D;     // 3² XZ uniquement
    private static final double HOLD_COMBAT_SQ    = 400.0D;   // 20²
    private static final int    ATTACK_RATE_TICKS = 20;
    // Si le chemin de navigation pointe à plus de 20 blocs de notre cible → annuler
    private static final double NAV_DEVIATION_SQ  = 400.0D;

    public FollowCommanderGoal(AbstractEntityCitizen citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── LIFECYCLE ──────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (citizen.level().isClientSide()) return false;
        String uuid = citizen.getPersistentData().getString("FollowTarget");
        if (uuid.isEmpty()) return false;
        ICitizenData data = citizen.getCitizenData();
        if (data == null || data.getJob() == null || !data.getJob().isGuard()) return false;
        try {
            Player p = citizen.level().getPlayerByUUID(UUID.fromString(uuid));
            if (p != null && p.isAlive()) { this.commander = p; return true; }
        } catch (IllegalArgumentException ignored) {}
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (commander == null || !commander.isAlive()) return false;
        String uuid = citizen.getPersistentData().getString("FollowTarget");
        return !uuid.isEmpty() && uuid.equals(commander.getUUID().toString());
    }

    @Override
    public void start() {
        citizen.getNavigation().stop();
        citizen.clearRestriction();
        pathRecalcDelay = 0;
        attackCooldown  = 0;
        scanCooldown    = 0;
        lastSaturation  = -1.0D;
        hungerTicks     = 0;
        isEating        = false;
        currentMode     = Mode.NONE;
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        eatCooldown = 0;
        captureAndFreezeSM();

        boolean hasWeapon = !citizen.getMainHandItem().isEmpty();
        LOG.info("[CF:Guard#{}:{}] ENLIST weapon={} job={}",
                citizen.getId(), citizen.getName().getString(),
                hasWeapon ? citizen.getMainHandItem().getDisplayName().getString() : "AUCUNE",
                citizen.getCitizenData() != null
                        ? citizen.getCitizenData().getJob().getJobRegistryEntry().getKey() : "?");
        if (!hasWeapon)
            LOG.warn("[CF:Guard#{}:{}] Pas d'arme — combat à mains nues.",
                    citizen.getId(), citizen.getName().getString());
    }

    @Override
    public void stop() {
        logTransition(Mode.NONE, "RENVOYÉ");
        commander    = null;
        isEating     = false;
        citizen.getNavigation().stop();
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(false);
        restoreSM(); // seul endroit où on restaure la SM
    }

    // ── SM MANAGEMENT ─────────────────────────────────────────────────────

    private void captureAndFreezeSM() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm == null) return;
            originalTickRate = sm.getTickRate();
            sm.setTickRate(SLOWED_TICK_RATE);
            sm.setCurrentDelay(SLOWED_TICK_RATE);
        } catch (Exception ignored) {}
    }

    /** Ré-appliqué CHAQUE tick : résiste aux resets du colony/building tick. */
    private void enforceSMFreeze() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm == null) return;
            if (sm.getTickRate() != SLOWED_TICK_RATE) sm.setTickRate(SLOWED_TICK_RATE);
            sm.setCurrentDelay(SLOWED_TICK_RATE);
        } catch (Exception ignored) {}
    }

    private void restoreSM() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm == null) return;
            sm.setTickRate(Math.max(1, originalTickRate));
            sm.setCurrentDelay(0);
        } catch (Exception ignored) {}
    }

    // ── VALIDATION NAVIGATION ─────────────────────────────────────────────
    // Vérifie que le chemin actuel pointe vers notre cible et non vers
    // la maison/tour du garde (injection directe de navigation par MineColonies
    // depuis colony tick hors système de Goals).

    private void validateNavigation() {
        if (citizen.getNavigation().isDone()) return;
        var targetPos = citizen.getNavigation().getTargetPos();
        if (targetPos == null) return;

        boolean holdGround = citizen.getPersistentData().getBoolean("IsHoldingGround");
        double tgtX, tgtZ;
        if (holdGround) {
            tgtX = citizen.getPersistentData().getDouble("HoldX");
            tgtZ = citizen.getPersistentData().getDouble("HoldZ");
        } else if (commander != null) {
            tgtX = commander.getX();
            tgtZ = commander.getZ();
        } else {
            return;
        }

        double dx = targetPos.getX() - tgtX;
        double dz = targetPos.getZ() - tgtZ;
        if (dx * dx + dz * dz > NAV_DEVIATION_SQ) {
            citizen.getNavigation().stop();
            pathRecalcDelay = 0;
            LOG.debug("[CF:Guard#{}] chemin déviant annulé (cible=({},{}), nav cible=({},{}))",
                    citizen.getId(), (int) tgtX, (int) tgtZ, targetPos.getX(), targetPos.getZ());
        }
    }

    // ── MÉTABOLISME ×5 ────────────────────────────────────────────────────
    // Intercepte 4/5 des baisses de saturation → baisse ×5 plus lente.
    // Pas de plancher artificiel : la saturation peut atteindre 0 naturellement.

    private void manageMetabolism() {
        ICitizenData data = citizen.getCitizenData();
        if (data == null) return;
        try {
            double current = data.getSaturation();
            if (lastSaturation < 0.0D) { lastSaturation = current; return; }
            if (current < lastSaturation) {
                if (++hungerTicks < METABOLISM_FACTOR) {
                    data.setSaturation(lastSaturation); // restaure
                } else {
                    hungerTicks    = 0;
                    lastSaturation = current; // 1/5 baisses passent
                }
            } else if (current > lastSaturation) {
                lastSaturation = current; // a mangé
                hungerTicks    = 0;
            }
        } catch (Exception ignored) {}
    }

    private double getSaturationSafe() {
        try {
            ICitizenData data = citizen.getCitizenData();
            return data != null ? data.getSaturation() : (double) ICitizenData.MAX_SATURATION;
        } catch (Exception e) {
            return (double) ICitizenData.MAX_SATURATION;
        }
    }

    /**
     * Consomme manuellement un item comestible dans l'inventaire du garde
     * et applique la saturation résultante via ICitizenData.setSaturation().
     * Appelé uniquement quand sat == 0 car la SM est figée et ne peut pas
     * traiter NEEDS_FOOD. Retourne true si le garde a mangé quelque chose.
     */
    private boolean tryEatFood() {
        ICitizenData data = citizen.getCitizenData();
        if (data == null) return false;
        try {
            IItemHandler inv = citizen.getItemHandlerCitizen();
            if (inv == null) return false;
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                FoodProperties food = stack.getItem().getFoodProperties(stack, citizen);
                if (food == null) continue;
                // Consomme un exemplaire
                inv.extractItem(slot, 1, false);
                double newSat = Math.min(ICitizenData.MAX_SATURATION,
                        data.getSaturation() + food.getNutrition());
                data.setSaturation(newSat);
                lastSaturation = newSat;
                hungerTicks    = 0;
                LOG.info("[CF:Guard#{}:{}] MANGER {} sat: 0 → {}",
                        citizen.getId(), citizen.getName().getString(),
                        stack.getDisplayName().getString(),
                        String.format("%.1f", newSat));
                return true;
            }
        } catch (Exception e) {
            LOG.debug("[CF:Guard#{}] tryEatFood erreur: {}", citizen.getId(), e.getMessage());
        }
        return false;
    }

    // ── COMBAT ────────────────────────────────────────────────────────────

    private void clearCombatMemory() {
        citizen.setTarget(null);
        try {
            citizen.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
            citizen.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.PATH);
        } catch (Exception ignored) {}
    }

    private void performAttack(LivingEntity target) {
        if (citizen.distanceToSqr(target) > MELEE_RANGE_SQ) return;
        if (--attackCooldown > 0) return;

        float damage = 4.0F;
        try {
            var weaponStack = citizen.getItemBySlot(EquipmentSlot.MAINHAND);
            if (!weaponStack.isEmpty()) {
                var mods = weaponStack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                        .get(Attributes.ATTACK_DAMAGE);
                if (!mods.isEmpty()) {
                    damage = 0.0F;
                    for (AttributeModifier mod : mods) damage += (float) mod.getAmount();
                    damage = Math.max(1.0F, damage);
                }
            }
        } catch (Exception e) {
            LOG.debug("[CF:Guard#{}] lecture dégâts arme échouée, fallback 4.0: {}",
                    citizen.getId(), e.getMessage());
        }

        target.hurt(citizen.damageSources().mobAttack(citizen), damage);
        citizen.swing(InteractionHand.MAIN_HAND);
        attackCooldown = ATTACK_RATE_TICKS;
        LOG.debug("[CF:Guard#{}:{}] ATTAQUE cible={} dégâts={}",
                citizen.getId(), citizen.getName().getString(),
                target.getName().getString(), damage);
    }

    // ── PRIORITY TARGET ────────────────────────────────────────────────────

    private LivingEntity getPriorityTarget() {
        String uuidStr = citizen.getPersistentData().getString("PriorityTarget");
        if (uuidStr.isEmpty()) return null;
        try {
            UUID uuid = UUID.fromString(uuidStr);
            if (citizen.level() instanceof ServerLevel sl) {
                Entity e = sl.getEntity(uuid);
                if (e instanceof LivingEntity le && le.isAlive()) return le;
            }
        } catch (IllegalArgumentException ignored) {}
        citizen.getPersistentData().remove("PriorityTarget");
        clearCombatMemory();
        LOG.info("[CF:Guard#{}:{}] PriorityTarget effacé (mort/despawn)",
                citizen.getId(), citizen.getName().getString());
        return null;
    }

    // ── SCAN MENACES — centré commandant ──────────────────────────────────

    private void scanForThreats() {
        if (commander == null) return;
        try {
            LivingEntity atk = commander.getLastHurtByMob();
            if (atk != null && atk.isAlive() && commander.distanceToSqr(atk) <= LEASH_SQ) {
                citizen.setTarget(atk); return;
            }
            atk = citizen.getLastHurtByMob();
            if (atk != null && atk.isAlive() && commander.distanceToSqr(atk) <= LEASH_SQ) {
                citizen.setTarget(atk); return;
            }
        } catch (Exception ignored) {}
        if (--scanCooldown > 0) return;
        scanCooldown = SCAN_INTERVAL;
        if (citizen.getTarget() != null && citizen.getTarget().isAlive()) return;
        List<Monster> threats = citizen.level().getEntitiesOfClass(
                Monster.class,
                commander.getBoundingBox().inflate(30.0D),
                m -> m.isAlive()
                        && commander.distanceToSqr(m) <= LEASH_SQ
                        && (citizen.distanceToSqr(m) <= 64.0D
                         || commander.distanceToSqr(m) <= 64.0D
                         || citizen.hasLineOfSight(m)));
        if (!threats.isEmpty())
            threats.stream().min(Comparator.comparingDouble(commander::distanceToSqr))
                   .ifPresent(citizen::setTarget);
    }

    // ── SCAN MENACES — centré point de défense ────────────────────────────

    private void scanHoldGroundThreats(double hx, double hy, double hz) {
        if (--scanCooldown > 0) return;
        scanCooldown = SCAN_INTERVAL;
        if (citizen.getTarget() != null && citizen.getTarget().isAlive()) return;
        List<Monster> threats = citizen.level().getEntitiesOfClass(
                Monster.class,
                new AABB(hx - 20, hy - 4, hz - 20, hx + 20, hy + 8, hz + 20),
                m -> m.isAlive() && m.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ);
        if (!threats.isEmpty())
            threats.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(hx, hy, hz)))
                   .ifPresent(citizen::setTarget);
    }

    // ── HOLD GROUND ───────────────────────────────────────────────────────

    private void tickHoldGround() {
        double hx = citizen.getPersistentData().getDouble("HoldX");
        double hy = citizen.getPersistentData().getDouble("HoldY");
        double hz = citizen.getPersistentData().getDouble("HoldZ");

        scanHoldGroundThreats(hx, hy, hz);
        LivingEntity target = citizen.getTarget();

        if (target != null && target.isAlive()) {
            if (target.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ) {
                enforceSMFreeze();
                citizen.clearRestriction();
                citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(target, 1.30D);
                    pathRecalcDelay = 8;
                }
                performAttack(target);
                logMode(Mode.COMBAT, String.format("HoldGround dist=%.1f",
                        Math.sqrt(target.distanceToSqr(hx, hy, hz))));
                return;
            }
            clearCombatMemory();
        }

        enforceSMFreeze();
        double dx = citizen.getX() - hx;
        double dz = citizen.getZ() - hz;
        double dist2DSq = dx * dx + dz * dz;

        if (dist2DSq > HOLD_ARRIVE_2D_SQ) {
            if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(hx, hy, hz, 1.15D);
                pathRecalcDelay = 10;
            }
            logMode(Mode.HOLD_GROUND, String.format("mouvement dist2D=%.1f", Math.sqrt(dist2DSq)));
        } else {
            if (--pathRecalcDelay <= 0) {
                if (citizen.getRandom().nextFloat() < 0.4F) {
                    double px = hx + (citizen.getRandom().nextDouble() - 0.5) * 10.0;
                    double pz = hz + (citizen.getRandom().nextDouble() - 0.5) * 10.0;
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(px, hy, pz, 1.0D);
                } else {
                    citizen.getNavigation().stop();
                }
                pathRecalcDelay = 80;
            }
            logMode(Mode.HOLD_GROUND, "patrouille");
        }
    }

    // ── NAVIGATION HELPER ─────────────────────────────────────────────────

    private void navigateTo(LivingEntity target, double speed, int interval) {
        if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
            citizen.clearRestriction();
            citizen.getNavigation().moveTo(target, speed);
            pathRecalcDelay = interval;
        }
    }

    // ── LOGGING ───────────────────────────────────────────────────────────

    private void logTransition(Mode newMode, String detail) {
        LOG.info("[CF:Guard#{}:{}] {} → {} | {}",
                citizen.getId(), citizen.getName().getString(),
                currentMode, newMode, detail);
        currentMode = newMode;
        logThrottle = 0;
    }

    private void logMode(Mode mode, String detail) {
        if (mode != currentMode) {
            logTransition(mode, detail);
        } else if (++logThrottle >= 100) {
            LOG.info("[CF:Guard#{}:{}] [{}] {} | sat={} dist={}",
                    citizen.getId(), citizen.getName().getString(), mode, detail,
                    String.format("%.1f", lastSaturation),
                    commander != null ? String.format("%.1f",
                            Math.sqrt(citizen.distanceToSqr(commander))) : "?");
            logThrottle = 0;
        }
    }

    // ── TICK PRINCIPAL ─────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (commander == null) return;

        // Métabolisme : ×5 — exécuté avant tout le reste
        manageMetabolism();

        double saturation = getSaturationSafe();
        double distSq     = citizen.distanceToSqr(commander);
        boolean retreat   = citizen.getPersistentData().getBoolean("ForcedRetreat");

        // Mise à jour de l'état eating
        if (isEating && saturation >= EATING_STOP) isEating = false;

        // ① FAMINE (sat == 0) — tenter de manger en priorité.
        // On ne bloque PAS le reste du tick : Focus Fire et combat passent quand même.
        if (saturation <= 0.0D) {
            isEating = true;
            if (--eatCooldown <= 0) {
                boolean ate = tryEatFood();
                eatCooldown = ate ? 40 : 10;
            }
        }

        // ② TÉLÉPORTATION DE SÉCURITÉ (> 45 blocs)
        if (distSq > TELEPORT_SQ) {
            clearCombatMemory();
            citizen.teleportTo(commander.getX(), commander.getY(), commander.getZ());
            citizen.getNavigation().stop();
            citizen.clearRestriction();
            citizen.getPersistentData().putBoolean("ForcedRetreat", false);
            enforceSMFreeze();
            pathRecalcDelay = 0;
            logTransition(Mode.TELEPORT, String.format("dist=%.1f", Math.sqrt(distSq)));
            return;
        }

        // Effacement territorial (guard tower pose setRestrictArea après le tick IA)
        citizen.clearRestriction();

        // ③ FORCED RETREAT
        if (retreat) {
            clearCombatMemory();
            enforceSMFreeze();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            if (distSq <= RETREAT_END_SQ) {
                citizen.getPersistentData().putBoolean("ForcedRetreat", false);
                citizen.getNavigation().stop();
                logTransition(Mode.FOLLOW, "retreat résolu");
            } else {
                navigateTo(commander, 1.35D, 5);
                logMode(Mode.RETREAT, String.format("dist=%.1f", Math.sqrt(distSq)));
            }
            return;
        }

        // ④ PRIORITY TARGET (Focus Fire) — interrompt l'eating si sat > 0
        LivingEntity priorityTarget = getPriorityTarget();
        if (priorityTarget != null) {
            if (commander.distanceToSqr(priorityTarget) > PRIORITY_MAX_SQ) {
                citizen.getPersistentData().remove("PriorityTarget");
                clearCombatMemory();
                logTransition(Mode.FOLLOW, "priority hors portée");
            } else {
                isEating = false; // interrompt le repas pour combattre
                enforceSMFreeze();
                citizen.clearRestriction();
                citizen.setTarget(priorityTarget);
                citizen.getLookControl().setLookAt(priorityTarget, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(priorityTarget, 1.35D);
                    pathRecalcDelay = 8;
                }
                performAttack(priorityTarget);
                logMode(Mode.PRIORITY, String.format("cible=%s dist=%.1f",
                        priorityTarget.getName().getString(),
                        Math.sqrt(citizen.distanceToSqr(priorityTarget))));
                return;
            }
        }

        // ⑤ LEASH CHECK
        LivingEntity target = citizen.getTarget();
        if (target != null && !target.isAlive()) { clearCombatMemory(); target = null; }
        if (distSq > LEASH_SQ || (target != null && commander.distanceToSqr(target) > LEASH_SQ)) {
            citizen.getPersistentData().putBoolean("ForcedRetreat", true);
            clearCombatMemory();
            enforceSMFreeze();
            navigateTo(commander, 1.35D, 5);
            logTransition(Mode.RETREAT, String.format("laisse dist=%.1f", Math.sqrt(distSq)));
            return;
        }

        // ⑥ COMBAT STANDARD (scan centré commandant) — interrompt l'eating si sat > 0
        scanForThreats();
        target = citizen.getTarget();
        if (target != null && target.isAlive()) {
            isEating = false; // interrompt le repas pour combattre
            enforceSMFreeze();
            citizen.clearRestriction();
            citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
            if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(target, 1.30D);
                pathRecalcDelay = 8;
            }
            performAttack(target);
            logMode(Mode.COMBAT, String.format("cible=%s dist=%.1f",
                    target.getName().getString(), Math.sqrt(citizen.distanceToSqr(target))));
            return;
        }

        // ⑦ HOLD GROUND
        if (citizen.getPersistentData().getBoolean("IsHoldingGround")) {
            tickHoldGround();
            return;
        }

        // Validation navigation uniquement hors combat (pas de cible active)
        validateNavigation();

        // ⑧ EATING volontaire — démarre quand sat ≤ 3, pas en combat.
        // Appelle tryEatFood() une fois toutes les 40 ticks jusqu'à sat ≥ EATING_STOP.
        if (!isEating && saturation <= EATING_START) isEating = true;

        if (isEating) {
            enforceSMFreeze();
            citizen.getNavigation().stop();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            // Consomme activement — la SM figée ne peut pas traiter NEEDS_FOOD elle-même
            if (--eatCooldown <= 0) {
                boolean ate = tryEatFood();
                eatCooldown = ate ? 40 : 20;
                if (!ate) {
                    // Plus rien à manger — sort du mode eating pour reprendre le suivi
                    isEating = false;
                    LOG.debug("[CF:Guard#{}:{}] plus de nourriture en inventaire, sortie EATING",
                            citizen.getId(), citizen.getName().getString());
                }
            }
            logMode(Mode.EATING, String.format("sat=%.1f", saturation));
            return;
        }

        // ⑨ SUIVI / ZONE DE CONFORT
        enforceSMFreeze();
        citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

        if (distSq > COMFORT_FAR_SQ) {
            navigateTo(commander, 1.25D, 8);
            logMode(Mode.FOLLOW, String.format("dist=%.1f", Math.sqrt(distSq)));
        } else if (distSq <= COMFORT_NEAR_SQ) {
            citizen.getNavigation().stop();
            logMode(Mode.COMFORT, "proche");
        } else {
            if (--pathRecalcDelay <= 0) {
                if (citizen.getRandom().nextFloat() < 0.1F) {
                    double wx = commander.getX() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                    double wz = commander.getZ() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                    citizen.getNavigation().moveTo(wx, commander.getY(), wz, 1.0D);
                } else if (citizen.getNavigation().isDone()) {
                    citizen.getNavigation().stop();
                }
                pathRecalcDelay = 40;
            }
            logMode(Mode.COMFORT, String.format("dist=%.1f", Math.sqrt(distSq)));
        }
    }
}
