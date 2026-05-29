package com.aimod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * Interface for subcommand handlers.
 * Each subcommand group implements this to register its commands
 * under the /ai_bot root.
 */
public interface SubCommand {
    /**
     * Register this subcommand's nodes under the given root builder.
     */
    void register(LiteralArgumentBuilder<CommandSourceStack> root);
}
