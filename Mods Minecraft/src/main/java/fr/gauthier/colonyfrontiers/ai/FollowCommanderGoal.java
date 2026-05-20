package fr.gauthier.colonyfrontiers.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
 * tick (sauf stop()). Cela évite que GUARD_GUARD / GUARD_PATROL reprenne la
 * navigation et renvoie le garde à sa tour.
 *
 * Les attaques sont pilotées manuellement via doHurtTarget() + swing().
 *
 * MODES (dans l'ordre de priorité) :
 *  ① Téléportation de sécurité (> 45 blocs)
 *  ② Forced Retreat
 *  ③ Priority Target (Focus Fire)
 *  ④ Leash check
 *  ⑤ Combat standard (scan centré commandant)
 *  ⑥ Hold Ground (coordonnées statiques)
 *  ⑦ Suivi / zone de confort
 */
public class FollowCommanderGoal extends Goal {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Goal");

    // ── MODES pour le logging ──────────────────────────────────────────────
    private enum Mode { NONE, FOLLOW, COMFORT, COMBAT, PRIORITY, HOLD_GROUND, RETREAT, TELEPORT, EATING }
    private Mode currentMode = Mode.NONE;
    private int  logThrottle = 0;

    private final AbstractEntityCitizen citizen;
    private Player commander;

    private int    pathRecalcDelay = 0;
    private int    attackCooldown  = 0;
    private int    scanCooldown    = 0;
    private int    hungerTicks     = 0;
    private double lastSaturation  = -1.0D;
    private int    originalTickRate = 1;

    // ── CONSTANTES ─────────────────────────────────────────────────────────
    private static final int    SLOWED_TICK_RATE  = 999_999;
    private static final int    SCAN_INTERVAL     = 4;
    private static final double COMFORT_NEAR_SQ   = 36.0D;    // 6²
    private static final double COMFORT_FAR_SQ    = 225.0D;   // 15²
    private static final double LEASH_SQ          = 900.0D;   // 30²
    private static final double RETREAT_END_SQ    = 100.0D;   // 10²
    private static final double TELEPORT_SQ       = 2025.0D;  // 45²
    private static final double PRIORITY_MAX_SQ   = 2500.0D;  // 50²
    private static final double MELEE_RANGE_SQ    = 6.25D;    // 2.5²
    private static final double HOLD_ARRIVE_2D_SQ = 9.0D;     // 3² (XZ only)
    private static final double HOLD_COMBAT_SQ    = 400.0D;   // 20²
    private static final int    ATTACK_RATE_TICKS = 20;       // 1 attaque/s

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
        currentMode     = Mode.NONE;
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        captureAndFreezeSM();

        // Log weapon status at enlist time
        boolean hasWeapon = !citizen.getMainHandItem().isEmpty();
        LOG.info("[CF:Guard#{}:{}] ENLIST — weapon={} job={}",
                citizen.getId(),
                citizen.getName().getString(),
                hasWeapon ? citizen.getMainHandItem().getDisplayName().getString() : "NONE",
                citizen.getCitizenData() != null ? citizen.getCitizenData().getJob().getJobRegistryEntry().getKey() : "?");
        if (!hasWeapon) {
            LOG.warn("[CF:Guard#{}:{}] No weapon in main hand — guard will use bare hands until dismissed.",
                    citizen.getId(), citizen.getName().getString());
        }
    }

    @Override
    public void stop() {
        logTransition(Mode.NONE, "DISMISSED");
        commander = null;
        citizen.getNavigation().stop();
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(false);
        restoreSM(); // Only place we restore — SM resumes normal colony AI (requests, patrol, etc.)
    }

    // ── SM MANAGEMENT — SM reste figée pendant tout le suivi ──────────────

    private void captureAndFreezeSM() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm == null) return;
            originalTickRate = sm.getTickRate();
            sm.setTickRate(SLOWED_TICK_RATE);
            sm.setCurrentDelay(SLOWED_TICK_RATE);
        } catch (Exception ignored) {}
    }

    /**
     * Ré-appliqué CHAQUE tick pour résister aux resets externes
     * (colony tick, building tick, soin, level-up).
     */
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

    // ── MÉTABOLISME 20× ───────────────────────────────────────────────────
    // Intercept 19/20 drops. Hard floor à 1.0 → jamais de particules bleues.

    private void manageMetabolism() {
        ICitizenData data = citizen.getCitizenData();
        if (data == null) return;
        try {
            double current = data.getSaturation();
            if (current < 1.0D) { data.setSaturation(1.0D); lastSaturation = 1.0D; return; }
            if (lastSaturation < 0.0D) { lastSaturation = current; return; }
            if (current < lastSaturation) {
                if (++hungerTicks < 20) {
                    data.setSaturation(lastSaturation);
                } else {
                    hungerTicks    = 0;
                    lastSaturation = Math.max(1.0D, current);
                }
            } else if (current > lastSaturation) {
                lastSaturation = current;
                hungerTicks    = 0;
            }
        } catch (Exception ignored) {}
    }

    // ── COMBAT — attaques manuelles (SM figée) ────────────────────────────
    // doHurtTarget() utilise les attributs de l'entité + l'arme en main.
    // Pas besoin de restoreSM() : on évite ainsi que GUARD_GUARD reprenne.

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
        citizen.doHurtTarget(target);
        citizen.swing(InteractionHand.MAIN_HAND);
        attackCooldown = ATTACK_RATE_TICKS;
    }

    // ── PRIORITY TARGET ────────────────────────────────────────────────────
    // Auto-clear si cible morte/disparue.

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
        LOG.info("[CF:Guard#{}:{}] PriorityTarget cleared (dead/despawned)",
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
                m -> m.isAlive()
                        && m.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ);
        if (!threats.isEmpty())
            threats.stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(hx, hy, hz)))
                   .ifPresent(citizen::setTarget);
    }

    // ── HOLD GROUND ───────────────────────────────────────────────────────
    // Utilise la distance 2D (XZ) pour l'arrivée : évite le bug où
    // le garde à y=holdY+1 croit être à holdY et ne bouge pas.

    private void tickHoldGround() {
        double hx = citizen.getPersistentData().getDouble("HoldX");
        double hy = citizen.getPersistentData().getDouble("HoldY");
        double hz = citizen.getPersistentData().getDouble("HoldZ");

        scanHoldGroundThreats(hx, hy, hz);
        LivingEntity target = citizen.getTarget();

        if (target != null && target.isAlive()) {
            if (target.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ) {
                // Combat sur place — SM figée, on attaque manuellement
                enforceSMFreeze();
                citizen.clearRestriction();
                citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(target, 1.30D);
                    pathRecalcDelay = 15;
                }
                performAttack(target);
                logMode(Mode.COMBAT, String.format("HoldGround combat dist=%.1f", Math.sqrt(target.distanceToSqr(hx, hy, hz))));
                return;
            }
            clearCombatMemory(); // cible sortie du périmètre
        }

        enforceSMFreeze();

        // Distance 2D (XZ) uniquement pour l'arrivée
        double dx = citizen.getX() - hx;
        double dz = citizen.getZ() - hz;
        double dist2DSq = dx * dx + dz * dz;

        if (dist2DSq > HOLD_ARRIVE_2D_SQ) {
            if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(hx, hy, hz, 1.15D);
                pathRecalcDelay = 20;
            }
            logMode(Mode.HOLD_GROUND, String.format("moving dist2D=%.1f", Math.sqrt(dist2DSq)));
        } else {
            // Arrivé — petite patrouille locale
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
            logMode(Mode.HOLD_GROUND, "patrolling");
        }
    }

    // ── NAVIGATION HELPER ─────────────────────────────────────────────────
    // clearRestriction() juste avant moveTo() : la guard tower pose
    // setRestrictArea() après le tick IA, pas avant.

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
                    citizen.getId(), citizen.getName().getString(),
                    mode, detail,
                    String.format("%.1f", lastSaturation),
                    commander != null ? String.format("%.1f", Math.sqrt(citizen.distanceToSqr(commander))) : "?");
            logThrottle = 0;
        }
    }

    // ── TICK PRINCIPAL ─────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (commander == null) return;

        // Métabolisme — exécuté en tout premier, chaque tick
        manageMetabolism();

        double distSq   = citizen.distanceToSqr(commander);
        boolean retreat = citizen.getPersistentData().getBoolean("ForcedRetreat");

        // ① TÉLÉPORTATION DE SÉCURITÉ (> 45 blocs)
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

        // Restriction territoriale — toujours effacée avant les calculs de chemin
        citizen.clearRestriction();

        // ② FORCED RETREAT — retour forcé, ignorer tous les combats
        if (retreat) {
            clearCombatMemory();
            enforceSMFreeze();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            if (distSq <= RETREAT_END_SQ) {
                citizen.getPersistentData().putBoolean("ForcedRetreat", false);
                citizen.getNavigation().stop();
                logTransition(Mode.FOLLOW, "retreat resolved");
            } else {
                navigateTo(commander, 1.35D, 10);
                logMode(Mode.RETREAT, String.format("dist=%.1f", Math.sqrt(distSq)));
            }
            return;
        }

        // ③ PRIORITY TARGET (Focus Fire) — priorité absolue sur tous les modes
        LivingEntity priorityTarget = getPriorityTarget();
        if (priorityTarget != null) {
            if (commander.distanceToSqr(priorityTarget) > PRIORITY_MAX_SQ) {
                citizen.getPersistentData().remove("PriorityTarget");
                clearCombatMemory();
                logTransition(Mode.FOLLOW, "priority target out of range");
            } else {
                enforceSMFreeze(); // SM figée — on pilote manuellement
                citizen.clearRestriction();
                citizen.setTarget(priorityTarget);
                citizen.getLookControl().setLookAt(priorityTarget, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(priorityTarget, 1.35D);
                    pathRecalcDelay = 10;
                }
                performAttack(priorityTarget);
                logMode(Mode.PRIORITY, String.format("target=%s dist=%.1f",
                        priorityTarget.getName().getString(),
                        Math.sqrt(citizen.distanceToSqr(priorityTarget))));
                return;
            }
        }

        // ④ LEASH CHECK — cibles mortes nettoyées avant le test de distance
        LivingEntity target = citizen.getTarget();
        if (target != null && !target.isAlive()) {
            clearCombatMemory();
            target = null;
        }
        if (distSq > LEASH_SQ || (target != null && commander.distanceToSqr(target) > LEASH_SQ)) {
            citizen.getPersistentData().putBoolean("ForcedRetreat", true);
            clearCombatMemory();
            enforceSMFreeze();
            navigateTo(commander, 1.35D, 10);
            logTransition(Mode.RETREAT, String.format("leash dist=%.1f", Math.sqrt(distSq)));
            return;
        }

        // ⑤ COMBAT STANDARD (scan centré commandant)
        scanForThreats();
        target = citizen.getTarget();
        if (target != null && target.isAlive()) {
            enforceSMFreeze(); // SM figée — on gère le combat manuellement
            citizen.clearRestriction();
            citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
            if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(target, 1.30D);
                pathRecalcDelay = 15;
            }
            performAttack(target);
            logMode(Mode.COMBAT, String.format("target=%s dist=%.1f",
                    target.getName().getString(),
                    Math.sqrt(citizen.distanceToSqr(target))));
            return;
        }

        // ⑥ HOLD GROUND — défense de coordonnées statiques
        if (citizen.getPersistentData().getBoolean("IsHoldingGround")) {
            tickHoldGround();
            return;
        }

        // ⑦ SUIVI / ZONE DE CONFORT
        boolean isHungry = false;
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null) isHungry = data.getSaturation() < (ICitizenData.MAX_SATURATION / 2.0D);
        } catch (Exception ignored) {}

        // Fenêtre de nourriture : faim + zone de confort (6–15 blocs)
        // La saturation 20x + floor à 1.0 gère les particules bleues.
        // On arrête juste le mouvement pour laisser le garde se comporter naturellement.
        if (isHungry && distSq > COMFORT_NEAR_SQ && distSq <= COMFORT_FAR_SQ) {
            enforceSMFreeze();
            citizen.getNavigation().stop();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            logMode(Mode.EATING, String.format("sat=%.1f", lastSaturation));
            return;
        }

        enforceSMFreeze();
        citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

        if (distSq > COMFORT_FAR_SQ) {
            navigateTo(commander, 1.25D, 10);
            logMode(Mode.FOLLOW, String.format("dist=%.1f", Math.sqrt(distSq)));
        } else if (distSq <= COMFORT_NEAR_SQ) {
            citizen.getNavigation().stop();
            logMode(Mode.COMFORT, "close");
        } else {
            // Zone de confort 6–15 blocs — petite errance naturelle
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
