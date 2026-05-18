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
 * Goal de suivi militaire pour les gardes MineColonies.
 * Ne s'arrête JAMAIS pendant le combat : le tick() orchestre à la fois
 * le déplacement de formation et le pathfinding de combat.
 *
 * Résout les 3 conflits systémiques :
 * 1. Faim/Particules bleues → Force la saturation au max chaque tick
 * 2. Blocage alimentation → Plus besoin de manger (saturation forcée)
 * 3. Passivité hors colonie → Scanner manuel de monstres dans un rayon de 12 blocs
 */
public class FollowCommanderGoal extends Goal {
    private final AbstractEntityCitizen citizen;
    private Player commander;
    private int pathRecalcDelay = 0;

    // State machine MineColonies
    private int originalTickRate = -1;
    private boolean smSlowed = false;
    private static final int SLOWED_TICK_RATE = 999999; // Effectively halts SM tick updates

    private int scanCooldown = 0;
    private static final int SCAN_INTERVAL = 4; // Scan toutes les 4 ticks (0.2s) pour une réactivité accrue
    private static final double SCAN_RADIUS = 30.0D; // Commander-centric

    private int hungerTicks = 0;
    private double lastSaturation = -1.0D;

    public FollowCommanderGoal(AbstractEntityCitizen citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean canUse() {
        String uuid = citizen.getPersistentData().getString("FollowTarget");
        if (uuid == null || uuid.isEmpty()) return false;
        if (citizen.getCitizenData() == null || citizen.getCitizenData().getJob() == null
                || !citizen.getCitizenData().getJob().isGuard()) return false;
        try {
            Player p = citizen.level().getPlayerByUUID(UUID.fromString(uuid));
            if (p != null && p.isAlive()) { this.commander = p; return true; }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (commander == null || !commander.isAlive()) return false;
        String uuid = citizen.getPersistentData().getString("FollowTarget");
        return uuid != null && !uuid.isEmpty() && uuid.equals(commander.getUUID().toString());
    }

    @Override
    public void start() {
        citizen.getNavigation().stop();
        citizen.clearRestriction();
        pathRecalcDelay = 0;
        scanCooldown = 0;
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        slowSM();

        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null) {
                data.setWorking(false);
                data.setJobStatus(com.minecolonies.api.entity.ai.JobStatus.IDLE);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void stop() {
        commander = null;
        citizen.getNavigation().stop();
        citizen.getPersistentData().putBoolean("ForcedRetreat", false);
        citizen.setGlowingTag(false);
        restoreSM();

        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null) {
                data.setWorking(true);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE MACHINE CONTROL
    // ═══════════════════════════════════════════════════════════════

    private void slowSM() {
        if (!smSlowed) {
            try {
                ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
                if (sm != null) {
                    originalTickRate = sm.getTickRate();
                    sm.setTickRate(SLOWED_TICK_RATE);
                    sm.setCurrentDelay(SLOWED_TICK_RATE);
                    smSlowed = true;
                }
            } catch (Exception ignored) {}
        }
    }

    private void restoreSM() {
        if (smSlowed) {
            try {
                ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
                if (sm != null) {
                    sm.setTickRate(originalTickRate > 0 ? originalTickRate : 1);
                    smSlowed = false;
                }
            } catch (Exception ignored) {}
        }
    }

    private void pushBackSM() {
        try {
            ITickRateStateMachine<IState> sm = citizen.getEntityStateController();
            if (sm != null && smSlowed) sm.setCurrentDelay(SLOWED_TICK_RATE);
        } catch (Exception ignored) {}
    }

    private void aggressiveClearTarget() {
        citizen.setTarget(null);
        try {
            citizen.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            citizen.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            citizen.getBrain().eraseMemory(MemoryModuleType.PATH);
            citizen.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
            citizen.getBrain().eraseMemory(MemoryModuleType.HOME);
            citizen.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            citizen.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
        } catch (Exception ignored) {}

        // Annuler immédiatement tout job de pathfinding asynchrone en cours dans MineColonies
        try {
            com.minecolonies.core.entity.pathfinding.pathresults.PathResult<?> pr = citizen.getNavigation().getPathResult();
            if (pr != null) {
                pr.cancel();
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // CAMPAIGN FOOD SATURATION — 20x Slower Metabolism
    // ═══════════════════════════════════════════════════════════════

    private void manageMetabolism() {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null) {
                double currentSaturation = data.getSaturation();
                if (lastSaturation == -1.0D) {
                    lastSaturation = currentSaturation;
                }
                
                if (currentSaturation < lastSaturation) {
                    hungerTicks++;
                    if (hungerTicks < 20) {
                        data.setSaturation(lastSaturation); // Intercept and restore
                    } else {
                        hungerTicks = 0;
                        lastSaturation = currentSaturation; // Let it decrease 1 out of 20 times
                    }
                } else if (currentSaturation > lastSaturation) {
                    lastSaturation = currentSaturation; // Ate food
                    hungerTicks = 0;
                }
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // MANUAL AGGRESSION SCANNER — Centered on Commander
    // ═══════════════════════════════════════════════════════════════

    private void scanForThreats() {
        if (commander == null) return;

        // 1. Réaction INSTANTANÉE (chaque tick) si le joueur ou le garde est attaqué
        try {
            LivingEntity playerAttacker = commander.getLastHurtByMob();
            if (playerAttacker != null && playerAttacker.isAlive() && commander.distanceToSqr(playerAttacker) <= 900.0D) {
                citizen.setTarget(playerAttacker);
                return;
            }
            LivingEntity guardAttacker = citizen.getLastHurtByMob();
            if (guardAttacker != null && guardAttacker.isAlive() && commander.distanceToSqr(guardAttacker) <= 900.0D) {
                citizen.setTarget(guardAttacker);
                return;
            }
        } catch (Exception ignored) {}

        // 2. Scan régulier avec intervalle rapide
        --scanCooldown;
        if (scanCooldown > 0) return;
        scanCooldown = SCAN_INTERVAL;

        LivingEntity currentTarget = citizen.getTarget();
        if (currentTarget != null && currentTarget.isAlive()) return;

        // Scan centré sur le commandant
        List<Monster> threats = citizen.level().getEntitiesOfClass(
                Monster.class,
                commander.getBoundingBox().inflate(SCAN_RADIUS),
                monster -> {
                    if (!monster.isAlive()) return false;
                    double distToCommander = commander.distanceToSqr(monster);
                    if (distToCommander > 900.0D) return false;

                    // Si le monstre est extrêmement proche (<= 8 blocs) de vous ou du garde,
                    // on contourne le test de champ de vision (Line of Sight) pour cibler instantanément.
                    double distToGuard = citizen.distanceToSqr(monster);
                    if (distToGuard <= 64.0D || distToCommander <= 64.0D) {
                        return true;
                    }

                    return citizen.hasLineOfSight(monster);
                }
        );

        if (!threats.isEmpty()) {
            Monster closest = threats.stream()
                    .min(Comparator.comparingDouble(commander::distanceToSqr))
                    .orElse(null);
            if (closest != null) {
                citizen.setTarget(closest);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN TICK LOOP — Orchestre tout le comportement
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        if (commander == null) return;

        double distToCommanderSqr = citizen.distanceToSqr(commander);
        boolean retreat = citizen.getPersistentData().getBoolean("ForcedRetreat");

        // ── 0. METABOLISM (every tick) ──
        manageMetabolism();

        // ── 1. TELEPORTATION SAFETY NET (> 45 blocks) ──
        if (distToCommanderSqr > 2025.0D) {
            citizen.teleportTo(commander.getX(), commander.getY(), commander.getZ());
            citizen.getNavigation().stop();
            citizen.getPersistentData().putBoolean("ForcedRetreat", false);
            aggressiveClearTarget();
            slowSM(); pushBackSM();
            citizen.clearRestriction();
            return;
        }

        // ── 2. ANTI-TERRITORIAL (every tick) ──
        citizen.clearRestriction();

        // ── 3. FORCED RETREAT MODE ──
        if (retreat) {
            aggressiveClearTarget();
            slowSM(); pushBackSM();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

            if (distToCommanderSqr <= 100.0D) { // 10 blocks
                citizen.getPersistentData().putBoolean("ForcedRetreat", false);
                citizen.getNavigation().stop();
            } else {
                --pathRecalcDelay;
                if (pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.getNavigation().moveTo(commander, 1.25D);
                    pathRecalcDelay = 10;
                }
            }
            return;
        }

        // ── 4. LEASH ENFORCEMENT & TRIGGER FORCED RETREAT ──
        LivingEntity attackTarget = citizen.getTarget();
        if (distToCommanderSqr > 900.0D || (attackTarget != null && attackTarget.isAlive() && commander.distanceToSqr(attackTarget) > 900.0D)) {
            citizen.getPersistentData().putBoolean("ForcedRetreat", true);
            aggressiveClearTarget();
            slowSM(); pushBackSM();
            citizen.getNavigation().moveTo(commander, 1.25D);
            pathRecalcDelay = 10;
            return;
        }

        // ── 5. NORMAL OPERATION (within 30 blocks) ──
        if (attackTarget != null && attackTarget.isAlive()) {
            // COMBAT MODE
            restoreSM();
            citizen.clearRestriction();
            citizen.getLookControl().setLookAt(attackTarget, 10.0F, (float) citizen.getMaxHeadXRot());
            --pathRecalcDelay;
            if (pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(attackTarget, 1.30D);
                pathRecalcDelay = 5;
            }
        } else {
            // FOLLOW MODE
            aggressiveClearTarget(); // Forcefully block and erase any stale combat/movement memory
            scanForThreats();

            // Eating Window Logic
            boolean isHungry = false;
            try {
                ICitizenData data = citizen.getCitizenData();
                if (data != null && data.getSaturation() < ICitizenData.MAX_SATURATION / 2) {
                    isHungry = true;
                }
            } catch (Exception ignored) {}

            if (isHungry && distToCommanderSqr > 36.0D && distToCommanderSqr <= 225.0D) {
                // Eating Window: restore SM, don't call navigation, let it eat
                restoreSM();
                citizen.getNavigation().stop();
                return;
            }

            slowSM(); pushBackSM();
            citizen.getLookControl().setLookAt(commander, 10.0F, (float) citizen.getMaxHeadXRot());

            if (distToCommanderSqr > 225.0D) {
                // > 15 blocks: sprint to player
                --pathRecalcDelay;
                if (pathRecalcDelay <= 0 || citizen.getNavigation().isDone()) {
                    citizen.getNavigation().moveTo(commander, 1.25D);
                    pathRecalcDelay = 10;
                }
            } else if (distToCommanderSqr <= 36.0D) {
                // <= 6 blocks: stop
                citizen.getNavigation().stop();
            } else {
                // Between 6 and 15 blocks: stand still or random patrol
                --pathRecalcDelay;
                if (pathRecalcDelay <= 0) {
                    if (citizen.getRandom().nextFloat() < 0.1F) {
                        // Occasional random patrol
                        double dx = commander.getX() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                        double dz = commander.getZ() + (citizen.getRandom().nextDouble() - 0.5) * 10;
                        citizen.getNavigation().moveTo(dx, commander.getY(), dz, 1.0D);
                    } else if (citizen.getNavigation().isDone()) {
                        citizen.getNavigation().stop();
                    }
                    pathRecalcDelay = 40; // Don't spam
                }
            }
        }
    }
}