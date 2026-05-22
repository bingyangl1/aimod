package com.example.aimod.ai.action;

import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import java.util.List;

public class AttackAction extends Action {
    private final String targetType;
    private LivingEntity targetEntity;
    private int attackCooldown;
    private int maxAttacks;
    private int attackCount;
    private static final int ATTACK_RANGE = 4; // 攻击范围（方块）
    private static final int MOVE_RANGE = 16; // 移动范围（方块）

    public AttackAction(String targetType) {
        super("Attack " + targetType);
        this.targetType = targetType;
        this.targetEntity = null;
        this.attackCooldown = 0;
        this.maxAttacks = 10; // 最多攻击 10 次
        this.attackCount = 0;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        // 查找附近的目标实体
        if (targetEntity == null || !targetEntity.isAlive()) {
            findTarget(bot);
        }
        return targetEntity != null && targetEntity.isAlive();
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            if (canExecute(bot)) {
                status = ActionStatus.IN_PROGRESS;
                attackCount = 0;
                DevLog.info("ATTACK_START", "target={}, type={}", targetEntity.getName().getString(), targetType);
            } else {
                status = ActionStatus.FAILED;
                DevLog.warn("ATTACK_FAIL_NO_TARGET", "type={}", targetType);
            }
        }

        if (status == ActionStatus.IN_PROGRESS) {
            // 检查目标是否还活着
            if (targetEntity == null || !targetEntity.isAlive()) {
                status = ActionStatus.COMPLETED;
                DevLog.info("ATTACK_COMPLETE_TARGET_DEAD", "attacks={}", attackCount);
                return;
            }

            // 检查是否达到最大攻击次数
            if (attackCount >= maxAttacks) {
                status = ActionStatus.COMPLETED;
                DevLog.info("ATTACK_COMPLETE_MAX", "attacks={}", attackCount);
                return;
            }

            // 冷却时间
            if (attackCooldown > 0) {
                attackCooldown--;
                return;
            }

            // 计算距离
            double distance = bot.distanceTo(targetEntity);

            // 使用 FakePlayer 攻击
            FakePlayer fakePlayer = getFakePlayer(bot);
            if (fakePlayer != null) {
                // 面向目标
                fakePlayer.lookAtEntity(targetEntity);

                // 如果距离太远，移动到目标附近
                if (distance > ATTACK_RANGE) {
                    moveToward(bot, targetEntity);
                    return;
                }

                // 攻击目标
                fakePlayer.attackEntity(targetEntity);
                attackCount++;
                attackCooldown = 10; // 0.5 秒冷却

                DevLog.info("ATTACK_HIT", "target={}, attacks={}, distance={}",
                        targetEntity.getName().getString(), attackCount, String.format("%.1f", distance));
            } else {
                // 没有 FakePlayer，使用原始攻击
                bot.getLookControl().setLookAt(targetEntity);

                if (distance > ATTACK_RANGE) {
                    moveToward(bot, targetEntity);
                    return;
                }

                bot.doHurtTarget(targetEntity);
                attackCount++;
                attackCooldown = 10;

                DevLog.info("ATTACK_HIT_NO_FAKE", "target={}, attacks={}",
                        targetEntity.getName().getString(), attackCount);
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private void findTarget(AIBotEntity bot) {
        // 根据类型查找附近实体
        List<Entity> entities = bot.level().getEntities(bot, bot.getBoundingBox().inflate(MOVE_RANGE), 
                entity -> entity instanceof LivingEntity && entity.isAlive());
        
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity livingEntity) {
                // 简单匹配实体类型名称
                if (entity.getType().toShortString().toLowerCase().contains(targetType.toLowerCase())) {
                    targetEntity = livingEntity;
                    return;
                }
            }
        }

        // 如果没找到特定类型，找最近的敌对实体
        if (targetEntity == null) {
            for (Entity entity : entities) {
                if (entity instanceof LivingEntity livingEntity && !(entity instanceof Player)) {
                    targetEntity = livingEntity;
                    return;
                }
            }
        }
    }

    /**
     * 移动到目标附近
     */
    private void moveToward(AIBotEntity bot, LivingEntity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > 0.1) {
            double speed = 0.3;
            double moveX = (dx / distance) * speed;
            double moveZ = (dz / distance) * speed;
            bot.setDeltaMovement(moveX, bot.getDeltaMovement().y, moveZ);
        }
    }

    public String getTargetType() {
        return targetType;
    }
}
