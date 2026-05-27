package com.aimod.client;

import com.aimod.AIMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(BuiltInRegistries.MENU, AIMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<BotInventoryMenu>> BOT_INVENTORY =
            MENU_TYPES.register("bot_inventory", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new BotInventoryMenu(id, inv)));

    public static void register(IEventBus eventBus) {
        MENU_TYPES.register(eventBus);
    }
}
