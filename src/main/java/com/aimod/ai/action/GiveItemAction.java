package com.aimod.ai.action;

import com.aimod.ai.InventoryUtils;
import com.aimod.config.ModConfig;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class GiveItemAction extends Action {
    private final String itemId;
    private final int count;
    private final String targetPlayerName;
    private int givenCount = 0;
    private int deficit = 0;

    public GiveItemAction(String itemId, int count, String targetPlayerName) {
        super("Give " + count + " " + itemId + " to " + targetPlayerName);
        this.itemId = itemId;
        this.count = Math.max(1, count);
        this.targetPlayerName = targetPlayerName;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        Item item = resolveItem();
        return bot.level() instanceof ServerLevel && item != Items.AIR;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status != ActionStatus.PENDING) {
            return;
        }
        DevLog.info("GIVE_ITEM_START", "bot={}, item={}, count={}, target={}",
                bot.getStringUUID(), itemId, count, targetPlayerName);
        if (!(bot.level() instanceof ServerLevel serverLevel)) {
            status = ActionStatus.FAILED;
            DevLog.warn("GIVE_ITEM_FAIL", "reason=not_server_level, item={}, count={}", itemId, count);
            return;
        }

        Item item = resolveItem();
        if (item == Items.AIR) {
            status = ActionStatus.FAILED;
            DevLog.warn("GIVE_ITEM_FAIL", "reason=unknown_item, item={}, count={}", itemId, count);
            return;
        }

        // Try to take the full amount
        ItemStack stack = InventoryUtils.removeItem(bot, item, count);
        int available = stack.getCount();

        if (available < count) {
            deficit = count - available;
            if (ModConfig.getAllowDevCreativeItemProvisioning()) {
                DevLog.warn("GIVE_ITEM_DEV_CREATIVE_PROVISION", "item={}, requested={}, missing={}",
                        itemId, count, deficit);
                stack.grow(deficit);
                deficit = 0;
            } else {
                // Give whatever we have — partial success
                if (available > 0) {
                    DevLog.info("GIVE_ITEM_PARTIAL", "item={}, requested={}, given={}, deficit={}",
                            itemId, count, available, deficit);
                } else {
                    // Nothing to give at all
                    status = ActionStatus.FAILED;
                    DevLog.warn("GIVE_ITEM_BLOCKED_NEEDS_ITEM", "item={}, requested={}, missing={}",
                            itemId, count, deficit);
                    return;
                }
            }
        }

        givenCount = available;

        ServerPlayer target = findTargetPlayer(serverLevel);
        if (target != null) {
            ItemStack remaining = stack.copy();
            boolean inserted = target.getInventory().add(remaining);
            if (!inserted || !remaining.isEmpty()) {
                dropAtPlayer(serverLevel, target, remaining.isEmpty() ? stack : remaining);
                DevLog.info("GIVE_ITEM_DROP", "target={}, item={}, count={}, reason=inventory_full_or_partial",
                        target.getName().getString(), itemId, available);
            } else {
                DevLog.info("GIVE_ITEM_DONE", "target={}, mode=inventory, item={}, count={}",
                        target.getName().getString(), itemId, available);
            }
        } else {
            dropAtBot(serverLevel, bot, stack);
            DevLog.info("GIVE_ITEM_DROP", "target={}, item={}, count={}, reason=target_not_found",
                    targetPlayerName, available);
        }

        if (deficit > 0) {
            // Partial success — report deficit but still mark COMPLETED so task can continue
            DevLog.info("GIVE_ITEM_DEFICIT_REMAINING", "item={}, deficit={}", itemId, deficit);
        }
        status = ActionStatus.COMPLETED;
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public int getDeficit() {
        return deficit;
    }

    public int getGivenCount() {
        return givenCount;
    }

    public String getItemId() {
        return itemId;
    }

    public int getRequestedCount() {
        return count;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
    }

    private Item resolveItem() {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private ServerPlayer findTargetPlayer(ServerLevel level) {
        if (targetPlayerName == null || targetPlayerName.isBlank() || level.getServer() == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayerByName(targetPlayerName);
    }

    private void dropAtPlayer(ServerLevel level, ServerPlayer player, ItemStack stack) {
        ItemEntity itemEntity = new ItemEntity(level, player.getX(), player.getY() + 0.5D, player.getZ(), stack);
        level.addFreshEntity(itemEntity);
    }

    private void dropAtBot(ServerLevel level, FakePlayer bot, ItemStack stack) {
        ItemEntity itemEntity = new ItemEntity(level, bot.getX(), bot.getY() + 0.5D, bot.getZ(), stack);
        level.addFreshEntity(itemEntity);
    }
}