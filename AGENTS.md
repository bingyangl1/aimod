# AGENTS.md

## What This Is

NeoForge 1.21.1 Minecraft mod (aimod). Adds an AI bot that executes natural language commands via LLM API calls. The bot is a **FakePlayer** (extends ServerPlayer) registered directly with the server — it IS a player entity, not a mob.

## Build

**Critical**: Standard ./gradlew build will FAIL. Must use local init script:

```
./gradlew.bat build --init-script init-local.gradle
```

This is because Java SSL connections to maven.neoforged.net are blocked on this machine. All NeoForge dependencies are cached in local-repo/ (a flat Maven repo at project root).

- Java 21 required. gradle.properties hardcodes systemProp.java.home — adjust if your JDK path differs.
- Gradle daemon is disabled (org.gradle.daemon=false).
- Parchment mappings configured for 1.21.1.

## Architecture

Package: com.example.aimod

**FakePlayer-first architecture**: The AI bot IS a FakePlayer (extends ServerPlayer), registered with the server via `placeNewPlayer()`. No separate mob entity exists. AI controls the FakePlayer directly.

| Layer | Key File | Role |
|-------|----------|------|
| Entrypoint | AIMod.java | @Mod class. Registers commands, config |
| FakePlayer | fakeplayer/FakePlayer.java | THE bot. Extends ServerPlayer, has BotAIManager |
| FP Manager | fakeplayer/FakePlayerManager.java | Manages FakePlayer lifecycle (create/destroy) |
| FP Network | fakeplayer/FakePlayerNetHandler.java | Extends ServerGamePacketListenerImpl, discards packets |
| FP Connection | fakeplayer/FakeClientConnection.java | EmbeddedChannel-based connection for registration |
| Commands | command/BotCommand.java | /ai_bot command tree |
| AI Core | ai/BotAIManager.java | Parses LLM responses into action sequences |
| Task | ai/Task.java | Task data model with status and action list |
| Task Feedback | ai/TaskFeedback.java | Reports task status to player |
| World Scanner | ai/WorldScanner.java | Scans nearby blocks, entities, and environment |
| Inventory Utils | ai/InventoryUtils.java | Bot inventory helper methods |
| Actions | ai/action/*.java | Action base class + 14 action types |
| Pathing | ai/pathing/*.java | Baritone-style A* pathfinding with Goal system |
| LLM Service | ai/llm/LLMService.java | HTTP calls to OpenAI-compatible API with world context |
| LLM Response | ai/llm/LLMResponse.java | LLM response data model |
| Config | config/ModConfig.java | ModConfigSpec — 12 configuration options |
| Client | client/ClientModEvents.java | Empty — FakePlayer renders as vanilla player |
| Logging | util/DevLog.java | Development logging utility |

## Action Types

| Action | Description |
|--------|-------------|
| MoveToAction | Pathfind to BlockPos |
| BreakBlockAction | Break a block using FakePlayer |
| PlaceBlockAction | Place a block using FakePlayer |
| AttackAction | Find + hit entity with multiple attacks |
| CraftAction | Craft using Minecraft recipe system |
| FollowAction | Follow player by name |
| GiveItemAction | Give items from bot inventory to player |
| RequireItemsAction | Gate: check inventory has items |
| SayAction | Broadcast chat message |
| WaitAction | Wait N ticks |
| MineBlockAction | Search and mine specific ore blocks (uses A* pathing) |
| GatherResourceAction | Gather wood, stone, dirt, sand resources (uses A* pathing) |
| InteractBlockAction | Interact with crafting table, furnace, chest, etc. |
| EquipItemAction | Equip armor, tools, or weapons |

## Adding an Action

1. Create class in ai/action/ extending Action with canExecute(), execute(), isComplete()
2. The `bot` parameter IS the FakePlayer — use it directly for player-like actions
3. Register in BotAIManager.convertResponseToActions() — add case to the switch

## Pathing System

Baritone-inspired A* pathfinding:

| Component | File | Role |
|-----------|------|------|
| Pathfinder | ai/pathing/Pathfinder.java | A* core algorithm |
| PathNode | ai/pathing/PathNode.java | Node with g-cost, h-cost, parent |
| PathExecutor | ai/pathing/PathExecutor.java | Executes path step-by-step |
| BinaryHeapOpenSet | ai/pathing/BinaryHeapOpenSet.java | Optimized open set |
| MovementHelper | ai/pathing/MovementHelper.java | Block passability checks |
| MoveCost | ai/pathing/MoveCost.java | Movement cost calculation |
| ToolSet | ai/pathing/ToolSet.java | Tool selection for mining |
| Goals | ai/pathing/goals/*.java | GoalBlock, GoalXZ, GoalYLevel, GoalComposite |

## World Context

The LLM receives world context including:
- Bot position, health
- Bot inventory contents
- Current time (day/night)
- Biome information
- Nearby blocks, entities, and players (via WorldScanner)

## Config

Generated at config/aimod-common.toml on first launch. 12 configuration options:

| Config | Type | Default | Description |
|--------|------|---------|-------------|
| apiUrl | String | OpenAI endpoint | LLM API endpoint URL |
| apiKey | String | "" | API Key for LLM service |
| modelName | String | gpt-3.5-turbo | Model name to use |
| maxTokens | Integer | 1024 | Maximum tokens for LLM response |
| temperature | Double | 0.7 | LLM temperature |
| connectTimeoutSeconds | Integer | 10 | HTTP connect timeout |
| readTimeoutSeconds | Integer | 600 | HTTP read timeout |
| streamResponses | Boolean | true | Request streaming responses |
| modelHealthCheck | Boolean | true | Run model availability check |
| healthCheckIntervalSeconds | Integer | 300 | Health check cache duration |
| healthCheckTimeoutSeconds | Integer | 20 | Health check read timeout |
| allowDevCreativeItemProvisioning | Boolean | false | Dev escape hatch for give_item |

## Key Conventions

- Mod ID: aimod (defined in AIMod.MODID and gradle.properties)
- FakePlayer extends ServerPlayer with fake network handler (FakePlayerNetHandler)
- Commands use Brigadier via NeoForge.EVENT_BUS.addListener
- neoforge.mods.toml uses ${} placeholders expanded from gradle.properties at build time
- DevLog is used for structured development logging with tags
- All actions receive the FakePlayer directly as `bot` parameter

## Known Issues

- FakeClientConnection uses reflection to set EmbeddedChannel — field name may differ in NeoForge patched code
- Pathfinding is Baritone-style but movement execution is basic (setPos/setDeltaMovement)
- No persistence (bot inventory and tasks are not saved across server restarts)
- No unit tests or game tests
- WorldScanner brute-force scans all blocks in a cube (O(n^3) for radius n)
- HttpClient is created per LLM call instead of being reused
- Some thread-safety concerns in LLMService health check caching
- Language files have encoding issues (mojibake in zh_cn.json)

## Files to Ignore

- build_*.txt, download_output.txt — build logs from debugging session
- local-repo/, maven-cache/, maven-proxy.ps1 — local dependency infrastructure
- init-proxy.gradle — unused proxy init script
- mc_1211.json, mc_manifest.json — Minecraft version metadata (downloaded)
- TestSSL*.class, TestSSL.java — leftover test artifacts
- temp_installertools_*.html — temporary download artifacts
- baritone-ref/ — reference code from Baritone (not compiled)
- _*.py, *.ps1 — temporary scripts from build debugging
- *.md (except AGENTS.md) — project documentation