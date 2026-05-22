package com.example.aimod.ai.action;

import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.util.DevLog;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class RequireItemsAction extends Action {
    private final Map<String, Integer> requiredItemIds;

    public RequireItemsAction(Map<String, Integer> requiredItemIds) {
        super("Require items " + requiredItemIds);
        this.requiredItemIds = new LinkedHashMap<>(requiredItemIds);
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        return true;
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status != ActionStatus.PENDING) {
            return;
        }

        Map<Item, Integer> requiredItems = resolveRequiredItems();
        Map<Item, Integer> missing = InventoryUtils.missingItems(bot, requiredItems);
        if (missing.isEmpty()) {
            status = ActionStatus.COMPLETED;
            DevLog.info("RESOURCE_CHECK_OK", "required={}", InventoryUtils.describeItems(requiredItems));
        } else {
            status = ActionStatus.FAILED;
            String missingText = InventoryUtils.describeItems(missing);
            DevLog.warn("RESOURCE_MISSING", "required={}, missing={}",
                    InventoryUtils.describeItems(requiredItems), missingText);
            if (bot.level().getServer() != null) {
                bot.level().getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("[AI Bot] Missing resources: " + missingText),
                        false);
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private Map<Item, Integer> resolveRequiredItems() {
        Map<Item, Integer> requiredItems = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : requiredItemIds.entrySet()) {
            Item item = resolveItem(entry.getKey());
            if (item != Items.AIR) {
                requiredItems.put(item, requiredItems.getOrDefault(item, 0) + entry.getValue());
            }
        }
        return requiredItems;
    }

    private Item resolveItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }
}
