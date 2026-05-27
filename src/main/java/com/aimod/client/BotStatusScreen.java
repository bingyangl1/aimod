package com.aimod.client;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Menu provider for the bot inventory screen.
 * Opens a full player-inventory layout showing bot's armor, offhand, main inventory, and hotbar.
 */
public class BotStatusScreen implements MenuProvider {

    private final FakePlayer bot;

    public BotStatusScreen(FakePlayer bot) {
        this.bot = bot;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(bot.getName().getString() + " - Inventory");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new BotInventoryMenu(containerId, playerInv, bot.getInventory());
    }

    /**
     * Open the inventory screen for a player who right-clicks a bot.
     */
    public static void open(ServerPlayer viewer, FakePlayer bot) {
        viewer.openMenu(new BotStatusScreen(bot));
    }
}
