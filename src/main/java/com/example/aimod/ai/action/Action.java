package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;

public abstract class Action {
    protected ActionStatus status;
    protected String description;

    public Action(String description) {
        this.description = description;
        this.status = ActionStatus.PENDING;
    }

    public abstract boolean canExecute(FakePlayer bot);
    public abstract void execute(FakePlayer bot);
    public abstract boolean isComplete(FakePlayer bot);

    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public String getDescription() { return description; }

    /**
     * Move bot toward target using direct position update (setPos).
     * FakePlayer's tick overrides setDeltaMovement, so we use setPos instead.
     * Speed is in blocks/second (4.317 = normal walk speed).
     */
    /**
     * Move bot toward target using move() for proper collision handling.
     * Speed is in blocks/second (4.317 = normal walk speed).
     */
    protected double navigateTo(FakePlayer bot, BlockPos target, double speed) {
        double dx = target.getX() + 0.5 - bot.getX();
        double dy = target.getY() - bot.getY();
        double dz = target.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;
        double dist = Math.sqrt(distSqr);
        if (dist > 0.15) {
            // Walk speed: 4.317 blocks/sec at 20 TPS = ~0.216 blocks/tick
            double stepPerTick = speed * 4.317 / 20.0;
            double step = Math.min(stepPerTick, dist);
            double moveX = (dx / dist) * step;
            double moveZ = (dz / dist) * step;
            double moveY;

            if (dy > 0.3 && dy <= 1.5 && bot.onGround()) {
                // Step-up: jump to reach the block above
                moveY = 0.42;
            } else if (!bot.onGround()) {
                // In air: keep current Y velocity (gravity handled by travel())
                moveY = bot.getDeltaMovement().y;
            } else {
                // On flat ground or descending: let move() handle step-down
                moveY = dy < -0.5 ? -0.4 : 0;
            }

            net.minecraft.world.phys.Vec3 movement = new net.minecraft.world.phys.Vec3(moveX, moveY, moveZ);
            bot.setDeltaMovement(movement);
            bot.move(net.minecraft.world.entity.MoverType.SELF, movement);

            // Face movement direction
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }
        return distSqr;
    }

    protected void stopNavigation(FakePlayer bot) {
        bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
    }

    public enum ActionStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
}