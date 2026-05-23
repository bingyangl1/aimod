package com.aimod.fakeplayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serializable snapshot of a bot's complete state.
 * Used for save/load via {@link BotPersistence}.
 *
 * <p>Inspired by SiliconeDolls' {@code BotInfo} record pattern.
 */
public final class BotInfo {

    public String name;
    public String desc;
    public String uuid;
    public Pos pos;
    public Facing facing;
    public String dimType;
    public String gamemode;
    public List<SerializedItem> inventory;
    @Nullable public SerializedTask task;
    public boolean paused;
    @Nullable public String ownerName;
    public long savedAt;

    public BotInfo() {}

    // ---- sub-records ----

    public static class Pos {
        public double x, y, z;
        public Pos() {}
        public Pos(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    public static class Facing {
        public float yaw, pitch;
        public Facing() {}
        public Facing(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }
    }

    public static class SerializedItem {
        public String itemId;   // e.g. "minecraft:diamond_pickaxe"
        public int count;
        public int slot;        // inventory slot index
        @Nullable public String nbt;  // optional NBT tag for enchanted/named items

        public SerializedItem() {}

        public static SerializedItem from(ItemStack stack, int slot) {
            var si = new SerializedItem();
            si.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            si.count = stack.getCount();
            si.slot = slot;
            var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            si.nbt = (tag != null && !tag.isEmpty()) ? tag.copyTag().toString() : null;
            return si;
        }

        public ItemStack toStack() {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            var stack = new ItemStack(item, count);
            if (nbt != null && !nbt.isEmpty()) {
                try {
                    var tag = net.minecraft.nbt.TagParser.parseTag(nbt);
                    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(tag));
                } catch (Exception ignored) {}
            }
            return stack;
        }
    }

    public static class SerializedTask {
        public String description;
        public String status;
        public int actionIndex;
        public List<String> actionDescriptions; // action descriptions for display

        public SerializedTask() {}
    }

    // ---- factory ----

    public static BotInfo from(FakePlayer fp, String desc, UUID persistentUUID) {
        var info = new BotInfo();
        info.name = fp.getName().getString();
        info.desc = (desc != null && !desc.isBlank()) ? desc : info.name;
        info.uuid = persistentUUID != null ? persistentUUID.toString() : fp.getStringUUID();
        info.pos = new Pos(fp.getX(), fp.getY(), fp.getZ());
        info.facing = new Facing(fp.getYRot(), fp.getXRot());
        info.dimType = fp.level().dimension().location().toString();
        info.gamemode = fp.gameMode.getGameModeForPlayer().name();
        info.paused = fp.isPaused();

        // Inventory
        info.inventory = new ArrayList<>();
        int size = fp.getInventory().getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = fp.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                info.inventory.add(SerializedItem.from(stack, i));
            }
        }

        // Current task
        var task = fp.getCurrentTask();
        if (task != null && !task.isCompleted()) {
            info.task = new SerializedTask();
            info.task.description = task.getDescription();
            info.task.status = task.getStatus().name();
            info.task.actionIndex = task.getCurrentActionIndex();
            info.task.actionDescriptions = new ArrayList<>();
            for (var action : task.getActions()) {
                info.task.actionDescriptions.add(action.getDescription());
            }
        }

        // Owner
        info.ownerName = fp.getAiManager().getFeedback().getOwnerName();
        info.savedAt = System.currentTimeMillis();
        return info;
    }
}
