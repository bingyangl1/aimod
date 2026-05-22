package com.example.aimod.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.common.EventBusSubscriber;
import com.example.aimod.AIMod;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = AIMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
    // FakePlayer renders as vanilla player - no custom renderer needed
}