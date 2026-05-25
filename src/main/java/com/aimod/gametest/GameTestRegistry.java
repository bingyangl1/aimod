package com.aimod.gametest;

/**
 * GameTest-based integration tests.
 * NOTE: GameTest namespace is disabled in build.gradle (forge.enabledGameTestNamespaces
 * removed) because NeoForge 21.1.176's class-based test registration generates batch
 * names containing ':' which crashes ResourceLocation parsing.
 *
 * JUnit tests serve as the primary test suite (134 tests in src/test/).
 *
 * To re-enable: add systemProperty 'forge.enabledGameTestNamespaces', project.mod_id
 * back to build.gradle runs, and replace this file with a proper class-based registration
 * that uses explicit batch names.
 */
public class GameTestRegistry {
}
