package fr.gauthier.colonyfrontiers.ai;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Suivi militaire pour gardes MineColonies.
 *
 * Conflits MineColonies gérés :
 *  - SM tick rate réinitialisé par colonie/building → re-freezé CHAQUE tick (enforceSMFreeze)
 *  - setRestrictArea() posé par guard tower après notre clear → clearRestriction() juste avant moveTo()
 *  - Navigation SM vs notre Goal en combat → SM restauré, on donne impulsion initiale, SM pilote ensuite
 *  - Brain erasure over-agressive → clearCombatMemory() limité aux transitions retreat/teleport
 *  - originalTickRate perdu après eating window → capturé une seule fois dans start()
 */
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
    private static final double COMFORT_NEAR_SQ  = 36.0D;    // 6²  — stop zone
    private static final double COMFORT_FAR_SQ   = 225.0D;   // 15² — begin closing
    private static final double LEASH_SQ         = 900.0D;   // 30² — leash limit
    private static final double RETREAT_END_SQ   = 100.0D;   // 10² — retreat resolved
    private static final double TELEPORT_SQ      = 2025.0D;  // 45² — safety teleport

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
        // Capture original tick rate ONCE — restored on stop(), not touched elsewhere.
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

    // ── STATE MACHINE MANAGEMENT ───────────────────────────────────────────

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
     * Re-sets BOTH tickRate AND currentDelay every tick we want the SM frozen.
     * Handles the case where the colony/building externally resets the SM tick rate
     * (e.g. on citizen sick/heal/level-up events).
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

    // ── METABOLISM — 20× SLOWER SATURATION DRAIN ──────────────────────────
    // Intercept every saturation drop and restore 19 out of 20.
    // The 20th drop passes through so food system stays functional.
    // This avoids 0-hunger crisis state and blue particles without
    // completely disabling MineColonies' food consumption logic.

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
                // Citizen ate — reset baseline
                lastSaturation = current;
                hungerTicks    = 0;
            }
        } catch (Exception ignored) {}
    }

    // ── COMBAT MEMORY CLEAR ────────────────────────────────────────────────
    // Intentionally limited to ATTACK_TARGET and ANGRY_AT.
    // WALK_TARGET / HOME / JOB_SITE must not be erased on every tick:
    // it causes constant Brain overhead and conflicts with colony navigation.
    // Called ONLY on forced retreat and safety teleport transitions.

    private void clearCombatMemory() {
        citizen.setTarget(null);
        try {
            citizen.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        } catch (Exception ignored) {}
    }

    // ── THREAT SCANNER — centered on commander ─────────────────────────────

    private void scanForThreats() {
        if (commander == null) return;

        // Immediate response: commander or guard was just struck this tick
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

    // ── SAFE NAVIGATION HELPER ─────────────────────────────────────────────
    // clearRestriction() is called immediately before moveTo() — not globally.
    // Reason: guard building calls setRestrictArea() in its colony tick, which
    // runs AFTER entity AI tick. Clearing restriction right before path
    // calculation ensures the computed path ignores territorial bounds.

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

        // ① SAFETY TELEPORT (> 45 blocks) ──────────────────────────────────
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

        // ② FORCED RETREAT — clear all combat, sprint to commander ──────────
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

        // ③ LEASH CHECK — triggers retreat if guard or target escapes 30 blocks
        LivingEntity target = citizen.getTarget();
        if (distSq > LEASH_SQ
                || (target != null && target.isAlive()
                    && commander.distanceToSqr(target) > LEASH_SQ)) {
            citizen.getPersistentData().putBoolean("ForcedRetreat", true);
            clearCombatMemory();
            enforceSMFreeze();
            navigateTo(commander, 1.35D, 10);
            return;
        }

        // ④ ACTIVE COMBAT ────────────────────────────────────────────────────
        // Restore SM so MineColonies' guard states (GUARD_ATTACK_PHYSICAL /
        // GUARD_ATTACK_RANGED) can run and deal damage normally.
        // We provide one initial movement impulse; the SM drives navigation after.
        // We do NOT call moveTo() every 5 ticks here: two competing moveTo() calls
        // on the same navigator cause micro-stutters and path cancellation.
        if (target != null && target.isAlive()) {
            restoreSM();
            citizen.clearRestriction();
            citizen.getLookControl().setLookAt(target, 10.0F, (float) citizen.getMaxHeadXRot());
            // Initial approach impulse — SM takes over once GUARD_ATTACK state fires
            if (--pathRecalcDelay <= 0) {
                citizen.clearRestriction();
                citizen.getNavigation().moveTo(target, 1.30D);
                pathRecalcDelay = 20; // sparse recalc — SM drives the rest
            }
            return;
        }

        // ⑤ PEACEFUL FOLLOW — scan threats, manage comfort zone ──────────────
        scanForThreats();

        // Eating window: hungry + in comfort zone (6–15 blocks) + no combat
        // Release navigation + restore SM so MineColonies eating states can run.
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

        // Normal follow — SM frozen, we drive navigation exclusively
        enforceSMFreeze();
        citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

        if (distSq > COMFORT_FAR_SQ) {
            // > 15 blocks: close the gap
            navigateTo(commander, 1.25D, 10);
        } else if (distSq <= COMFORT_NEAR_SQ) {
            // ≤ 6 blocks: stand still
            citizen.getNavigation().stop();
        }
        // 6–15 blocks: comfort zone — look at commander, no movement forced
    }
}
