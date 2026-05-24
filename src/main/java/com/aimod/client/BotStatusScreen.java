package com.aimod.client;

import com.aimod.entity.AIBotEntity;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Simple inventory viewer for AI bot status.
 * Opens a 9x3 chest menu showing bot inventory (first 27 slots).
 *
 * <p>Simplified from SiliconeDolls' PlayerInventoryContainer.
 */
public class BotStatusScreen implements MenuProvider {

    private final FakePlayer bot;

    public BotStatusScreen(FakePlayer bot) {
        this.bot = bot;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(bot.getName().getString() + " - Status");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        // Show bot inventory slots 0-26 (hotbar + 2 rows)
        SimpleContainer container = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) {
            container.setItem(i, bot.getInventory().getItem(i).copy());
        }
        return new ChestMenu(MenuType.GENERIC_9x3, containerId, playerInv, container, 3);
    }

    /**
     * Open the status screen for a player who right-clicks a bot.
     */
    public static void open(ServerPlayer viewer, FakePlayer bot) {
        viewer.openMenu(new BotStatusScreen(bot));
    }
}
