package fr.gauthier.colonyfrontiers.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class FollowCommanderGoal extends Goal {

    private final AbstractEntityCitizen citizen;
    private Player commander;

    private int    pathRecalcDelay = 0;
    private int    scanCooldown    = 0;
    private int    hungerTicks     = 0;
    private double lastSaturation  = -1.0D;
    private int    originalTickRate = 1;

    private static final int    SLOWED_TICK_RATE = 999_999;
    private static final int    SCAN_INTERVAL    = 4;
    private static final double COMFORT_NEAR_SQ  = 36.0D;    // 6²
    private static final double COMFORT_FAR_SQ   = 225.0D;   // 15²
    private static final double LEASH_SQ         = 900.0D;   // 30²
    private static final double RETREAT_END_SQ   = 100.0D;   // 10²
    private static final double TELEPORT_SQ      = 2025.0D;  // 45²
    private static final double PRIORITY_MAX_SQ  = 2500.0D;  // 50² — abandon priority if target goes beyond
    private static final double HOLD_RADIUS_SQ   = 9.0D;     // 3²  — "arrived at hold point"
    private static final double HOLD_COMBAT_SQ   = 400.0D;   // 20² — combat leash from hold point

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
        scanCooldown    = 0;
        lastSaturation  = -1.0D;
        hungerTicks     = 0;
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        captureAndFreezeSM();
    }

    @Override
    public void stop() {
        commander = null;
        citizen.getNavigation().stop();
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(false);
        restoreSM();
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

    private void pushBackSM() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm != null && sm.getTickRate() == SLOWED_TICK_RATE)
                sm.setCurrentDelay(SLOWED_TICK_RATE);
        } catch (Exception ignored) {}
    }

    // ── METABOLISM — 20× slower saturation drain ──────────────────────────

    private void manageMetabolism() {
        ICitizenData data = citizen.getCitizenData();
        if (data == null) return;
        try {
            double current = data.getSaturation();
            if (lastSaturation < 0.0D) { lastSaturation = current; return; }
            if (current < lastSaturation) {
                if (++hungerTicks < 20) {
                    data.setSaturation(lastSaturation);
                } else {
                    hungerTicks    = 0;
                    lastSaturation = current;
                }
            } else if (current > lastSaturation) {
                lastSaturation = current;
                hungerTicks    = 0;
            }
        } catch (Exception ignored) {}
    }

    // ── COMBAT MEMORY CLEAR ────────────────────────────────────────────────

    private void clearCombatMemory() {
        citizen.setTarget(null);
        try {
            citizen.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        } catch (Exception ignored) {}
    }

    // ── PRIORITY TARGET ────────────────────────────────────────────────────
    // Resolves the UUID stored in "PriorityTarget" NBT to a living entity.
    // Auto-clears the tag (and combat memory) when the target is dead or despawned.

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
        // Dead or not found — auto-clear
        citizen.getPersistentData().remove("PriorityTarget");
        clearCombatMemory();
        return null;
    }

    // ── THREAT SCANNER — centered on commander ─────────────────────────────

    private void scanForThreats() {
        if (commander == null) return;
        try {
            LivingEntity atk = commander.getLastHurtByMob();
            if (atk != null && atk.isAlive() && commander.distanceToSqr(atk) <= LEASH_SQ) {
                citizen.setTarget(atk);
                return;
            }
            atk = citizen.getLastHurtByMob();
            if (atk != null && atk.isAlive() && commander.distanceToSqr(atk) <= LEASH_SQ) {
                citizen.setTarget(atk);
                return;
            }
        } catch (Exception ignored) {}

        if (--scanCooldown > 0) return;
        scanCooldown = SCAN_INTERVAL;

        LivingEntity current = citizen.getTarget();
        if (current != null && current.isAlive()) return;

        List<Monster> threats = citizen.level().getEntitiesOfClass(
                Monster.class,
                commander.getBoundingBox().inflate(30.0D),
                m -> m.isAlive()
                        && commander.distanceToSqr(m) <= LEASH_SQ
                        && (citizen.distanceToSqr(m)   <= 64.0D
                         || commander.distanceToSqr(m) <= 64.0D
                         || citizen.hasLineOfSight(m))
        );
        if (!threats.isEmpty()) {
            threats.stream()
                   .min(Comparator.comparingDouble(commander::distanceToSqr))
                   .ifPresent(citizen::setTarget);
        }
    }

    // ── THREAT SCANNER — centered on hold position ─────────────────────────

    private void scanHoldGroundThreats(double hx, double hy, double hz) {
        if (--scanCooldown > 0) return;
        scanCooldown = SCAN_INTERVAL;

        LivingEntity current = citizen.getTarget();
        if (current != null && current.isAlive()) return;

        List<Monster> threats = citizen.level().getEntitiesOfClass(
                Monster.class,
                new AABB(hx - 20, hy - 5, hz - 20, hx + 20, hy + 5, hz + 20),
                m -> m.isAlive()
                        && (m.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ
                         || citizen.hasLineOfSight(m))
        );
        if (!threats.isEmpty()) {
            threats.stream()
                   .min(Comparator.comparingDouble(m -> m.distanceToSqr(hx, hy, hz)))
                   .ifPresent(citizen::setTarget);
        }
    }

    // ── HOLD GROUND TICK ──────────────────────────────────────────────────
    // Navigate to stored coordinates, patrol a small radius, fight threats
    // that come within 20 blocks of the hold point.

    private void tickHoldGround() {
        double hx = citizen.getPersistentData().getDouble("HoldX");
        double hy = citizen.getPersistentData().getDouble("HoldY");
        double hz = citizen.getPersistentData().getDouble("HoldZ");

        scanHoldGroundThreats(hx, hy, hz);

        LivingEntity target = citizen.getTarget();
        if (target != null && target.isAlive()) {
            if (target.distanceToSqr(hx, hy, hz) <= HOLD_COMBAT_SQ) {
                // SM restored so native GUARD_ATTACK states fire and deal damage
                restoreSM();
                citizen.clearRestriction();
                citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0) {
                    citizen.getNavigation().moveTo(target, 1.30D);
                    pathRecalcDelay = 20;
                }
                return;
            }
            // Target left the defended zone — disengage
            clearCombatMemory();
        }

        enforceSMFreeze();
        double distToHoldSq = citizen.distanceToSqr(hx, hy, hz);

        if (distToHoldSq > HOLD_RADIUS_SQ) {
            // Move toward hold point
            if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(hx, hy, hz, 1.10D);
                pathRecalcDelay = 20;
            }
        } else {
            // At hold point — short local patrol
            if (--pathRecalcDelay <= 0) {
                if (citizen.getRandom().nextFloat() < 0.3F) {
                    double dx = hx + (citizen.getRandom().nextDouble() - 0.5) * 8.0;
                    double dz = hz + (citizen.getRandom().nextDouble() - 0.5) * 8.0;
                    citizen.clearRestriction();
                    citizen.getNavigation().moveTo(dx, hy, dz, 1.0D);
                } else {
                    citizen.getNavigation().stop();
                }
                pathRecalcDelay = 60;
            }
        }
    }

    // ── NAVIGATION HELPER ─────────────────────────────────────────────────
    // clearRestriction() immediately before moveTo() so the path calculation
    // ignores the guard tower's setRestrictArea() (posed after entity AI tick).

    private void navigateTo(LivingEntity target, double speed, int recalcInterval) {
        if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
            citizen.clearRestriction();
            citizen.getNavigation().moveTo(target, speed);
            pathRecalcDelay = recalcInterval;
        }
    }

    // ── MAIN TICK ──────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (commander == null) return;

        manageMetabolism();

        double distSq   = citizen.distanceToSqr(commander);
        boolean retreat = citizen.getPersistentData().getBoolean("ForcedRetreat");

        // ① Safety teleport (> 45 blocks)
        if (distSq > TELEPORT_SQ) {
            clearCombatMemory();
            citizen.teleportTo(commander.getX(), commander.getY(), commander.getZ());
            citizen.getNavigation().stop();
            citizen.clearRestriction();
            citizen.getPersistentData().putBoolean("ForcedRetreat", false);
            enforceSMFreeze();
            pathRecalcDelay = 0;
            return;
        }

        // ② Anti-territorial — guard tower re-poses setRestrictArea after entity tick
        citizen.clearRestriction();

        // ③ Forced retreat — sprint home, ignore fights
        if (retreat) {
            clearCombatMemory();
            enforceSMFreeze();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            if (distSq <= RETREAT_END_SQ) {
                citizen.getPersistentData().putBoolean("ForcedRetreat", false);
                citizen.getNavigation().stop();
            } else {
                navigateTo(commander, 1.35D, 10);
            }
            return;
        }

        // ④ PRIORITY TARGET — overrides both follow mode and hold ground
        LivingEntity priorityTarget = getPriorityTarget();
        if (priorityTarget != null) {
            if (commander.distanceToSqr(priorityTarget) > PRIORITY_MAX_SQ) {
                // Target too far from commander — abandon priority
                citizen.getPersistentData().remove("PriorityTarget");
                clearCombatMemory();
            } else {
                restoreSM();
                citizen.clearRestriction();
                citizen.setTarget(priorityTarget);
                citizen.getLookControl().setLookAt(priorityTarget, 10.0F, (float) citizen.getMaxHeadXRot());
                if (--pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.getNavigation().moveTo(priorityTarget, 1.35D);
                    pathRecalcDelay = 10;
                }
                return;
            }
        }

        // ⑤ Leash check — stale/dead targets cleared before testing distance
        LivingEntity target = citizen.getTarget();
        if (target != null && !target.isAlive()) {
            clearCombatMemory();
            target = null;
        }
        if (distSq > LEASH_SQ
                || (target != null && commander.distanceToSqr(target) > LEASH_SQ)) {
            citizen.getPersistentData().putBoolean("ForcedRetreat", true);
            clearCombatMemory();
            enforceSMFreeze();
            navigateTo(commander, 1.35D, 10);
            return;
        }

        // ⑥ Active combat (target from leash-area scan)
        if (target != null && target.isAlive()) {
            restoreSM();
            citizen.clearRestriction();
            citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
            if (--pathRecalcDelay <= 0) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(target, 1.30D);
                pathRecalcDelay = 20;
            }
            return;
        }

        // ⑦ HOLD GROUND MODE — guard defends static coordinates, not the player
        if (citizen.getPersistentData().getBoolean("IsHoldingGround")) {
            tickHoldGround();
            return;
        }

        // ⑧ Peaceful follow mode
        scanForThreats();

        boolean isHungry = false;
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null)
                isHungry = data.getSaturation() < (ICitizenData.MAX_SATURATION / 2.0D);
        } catch (Exception ignored) {}

        if (isHungry && distSq > COMFORT_NEAR_SQ && distSq <= COMFORT_FAR_SQ) {
            restoreSM();
            citizen.getNavigation().stop();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());
            return;
        }

        enforceSMFreeze();
        citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

        if (distSq > COMFORT_FAR_SQ) {
            navigateTo(commander, 1.25D, 10);
        } else if (distSq <= COMFORT_NEAR_SQ) {
            citizen.getNavigation().stop();
        } else {
            // 6–15 blocks comfort zone — occasional idle wander
            if (--pathRecalcDelay <= 0) {
                if (citizen.getRandom().nextFloat() < 0.1F) {
                    double dx = commander.getX() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                    double dz = commander.getZ() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                    citizen.getNavigation().moveTo(dx, commander.getY(), dz, 1.0D);
                } else if (citizen.getNavigation().isDone()) {
                    citizen.getNavigation().stop();
                }
                pathRecalcDelay = 40;
            }
        }
    }
}
