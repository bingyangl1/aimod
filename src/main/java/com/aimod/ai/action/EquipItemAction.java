package com.aimod.ai.action;

import com.aimod.ai.InventoryUtils;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 装备物品动作：将武器、工具或盔甲装备到假人身上。
 * 支持自动识别装备槽位。
 */
public class EquipItemAction extends Action {
    private final String itemId;
    private final EquipmentSlot targetSlot;

    public EquipItemAction(String itemId) {
        this(itemId, null);
    }

    public EquipItemAction(String itemId, EquipmentSlot targetSlot) {
        super("Equip " + itemId);
        this.itemId = itemId;
        this.targetSlot = targetSlot;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        Item item = resolveItem();
        if (item == Items.AIR) {
            return false;
        }
        return InventoryUtils.countItem(bot, item) > 0;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            Item item = resolveItem();
            if (item == Items.AIR) {
                DevLog.warn("EQUIP_UNKNOWN_ITEM", "item={}", itemId);
                status = ActionStatus.FAILED;
                return;
            }

            // 检查背包中是否有该物品
            int available = InventoryUtils.countItem(bot, item);
            if (available <= 0) {
                DevLog.warn("EQUIP_NO_ITEM", "item={}", itemId);
                status = ActionStatus.FAILED;
                return;
            }

            // 从背包中取出物品
            ItemStack stack = InventoryUtils.removeItem(bot, item, 1);
            if (stack.isEmpty()) {
                DevLog.warn("EQUIP_REMOVE_FAILED", "item={}", itemId);
                status = ActionStatus.FAILED;
                return;
            }

            // 确定装备槽位
            EquipmentSlot slot = targetSlot != null ? targetSlot : getSlotForItem(item);

            // 获取当前装备的物品
            ItemStack currentEquipment = bot.getItemBySlot(slot);

            // 如果当前有装备，放回背包
            if (!currentEquipment.isEmpty()) {
                InventoryUtils.addItem(bot, currentEquipment);
                DevLog.info("EQUIP_UNEQUIP", "slot={}, item={}", slot, currentEquipment.getItem().getDescriptionId());
            }

            // 装备新物品
            bot.setItemSlot(slot, stack);

            DevLog.info("EQUIP_DONE", "item={}, slot={}", itemId, slot);
            status = ActionStatus.COMPLETED;
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    /**
     * 根据物品类型确定装备槽位
     */
    private EquipmentSlot getSlotForItem(Item item) {
        if (item instanceof ArmorItem armorItem) {
            return armorItem.getEquipmentSlot();
        }

        // 工具和武器默认装备到主手
        return EquipmentSlot.MAINHAND;
    }

    /**
     * 解析物品 ID
     */
    private Item resolveItem() {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    /**
     * 检查物品是否是盔甲
     */
    public static boolean isArmor(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item instanceof ArmorItem;
    }

    /**
     * 获取盔甲的装备槽位
     */
    public static EquipmentSlot getArmorSlot(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null) {
            return EquipmentSlot.HEAD;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item instanceof ArmorItem armorItem) {
            return armorItem.getEquipmentSlot();
        }
        return EquipmentSlot.MAINHAND;
    }

    public String getItemId() {
        return itemId;
    }

    public EquipmentSlot getTargetSlot() {
        return targetSlot;
    }
}
