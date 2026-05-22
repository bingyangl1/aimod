# AGENTS.md

## What This Is

NeoForge 1.21.1 Minecraft mod (imod). Adds an AI bot entity that executes natural language commands via LLM API calls. The bot uses a FakePlayer system to simulate player actions.

## Build

**Critical**: Standard ./gradlew build will FAIL. Must use local init script:

`
./gradlew.bat build --init-script init-local.gradle
`

This is because Java SSL connections to maven.neoforged.net are blocked on this machine. All NeoForge dependencies are cached in local-repo/ (a flat Maven repo at project root).

- Java 21 required. gradle.properties hardcodes systemProp.java.home=D:/Java/jdk-21.0.5+11 — adjust if your JDK path differs.
- Gradle daemon is disabled (org.gradle.daemon=false).
- Parchment mappings configured for 1.21.1.

## Architecture

Package: com.example.aimod

| Layer | Key File | Role |
|-------|----------|------|
| Entrypoint | AIMod.java | @Mod class. Registers entities, commands, config |
| Entity | entity/AIBotEntity.java | The bot mob. Extends PathfinderMob, integrates FakePlayer |
| FakePlayer | akeplayer/FakePlayer.java | Extends ServerPlayer, simulates player actions |
| FakePlayer Manager | akeplayer/FakePlayerManager.java | Manages FakePlayer lifecycle |
| Fake Network | akeplayer/FakeNetHandler.java | Fake network connection for FakePlayer |
| Fake Connection | akeplayer/FakeConnection.java | EmbeddedChannel-based connection |
| Entity Reg | entity/ModEntities.java | Deferred entity type registration |
| Commands | command/BotCommand.java | /ai_bot command tree |
| AI Core | i/BotAIManager.java | Parses LLM responses into action sequences, collects world context |
| Task | i/Task.java | Task data model with status and action list |
| Task Feedback | i/TaskFeedback.java | Reports task status to player |
| World Scanner | i/WorldScanner.java | Scans nearby blocks, entities, and environment |
| Inventory Utils | i/InventoryUtils.java | Bot inventory helper methods |
| Actions | i/action/*.java | Action base class + 14 action types |
| LLM Service | i/llm/LLMService.java | HTTP calls to OpenAI-compatible API with world context |
| LLM Response | i/llm/LLMResponse.java | LLM response data model |
| Config | config/ModConfig.java | ModConfigSpec — 12 configuration options |
| Client | client/ | AIBotRenderer, AIBotModel (player model) |
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
| MineBlockAction | Search and mine specific ore blocks |
| GatherResourceAction | Gather wood, stone, dirt, sand resources |
| InteractBlockAction | Interact with crafting table, furnace, chest, etc. |
| EquipItemAction | Equip armor, tools, or weapons |

## Adding an Action

1. Create class in i/action/ extending Action with canExecute(), execute(), isComplete()
2. Use getFakePlayer(bot) to access FakePlayer for player-like actions
3. Use navigateTo(bot, targetBlockPos, speed) for pathfinding-based movement (uses vanilla PathNavigation); call stopNavigation(bot) when arrived
4. Register in BotAIManager.convertResponseToActions() — add case to the switch
## World Context

The LLM receives world context including:
- Bot position, health
- Bot inventory contents
- Current time (day/night)
- Biome information
- Nearby blocks, entities, and players (via WorldScanner)

This helps the LLM make informed decisions about task planning.

## Task Feedback

TaskFeedback reports task status to the player who assigned the task:
- Task start notification
- Action progress updates
- Task completion/failure notification
- Missing resource alerts

## Config

Generated at config/aimod-common.toml on first launch. 12 configuration options:

| Config | Type | Default | Description |
|--------|------|---------|-------------|
| piUrl | String | OpenAI endpoint | LLM API endpoint URL |
| piKey | String | "" | API Key for LLM service |
| modelName | String | gpt-3.5-turbo | Model name to use |
| maxTokens | Integer | 1024 | Maximum tokens for LLM response |
| 	emperature | Double | 0.7 | LLM temperature |
| connectTimeoutSeconds | Integer | 10 | HTTP connect timeout |
| eadTimeoutSeconds | Integer | 600 | HTTP read timeout |
| streamResponses | Boolean | true | Request streaming responses |
| modelHealthCheck | Boolean | true | Run model availability check |
| healthCheckIntervalSeconds | Integer | 300 | Health check cache duration |
| healthCheckTimeoutSeconds | Integer | 20 | Health check read timeout |
| llowDevCreativeItemProvisioning | Boolean | false | Dev escape hatch for give_item |

ModConfig.java defines the spec via ModConfigSpec.Builder. Config registered in AIMod constructor.

## Key Conventions

- Mod ID: imod (defined in AIMod.MODID and gradle.properties)
- Entity registered via DeferredRegister<EntityType<?>> in ModEntities
- Client renderer registered in ClientModEvents via @EventBusSubscriber
- Commands use Brigadier via NeoForge.EVENT_BUS.addListener
- 
eoforge.mods.toml uses ${} placeholders expanded from gradle.properties at build time
- FakePlayer extends ServerPlayer with fake network handler
- Actions use getFakePlayer(bot) to access FakePlayer capabilities
- Actions use navigateTo(bot, pos, speed) / stopNavigation(bot) from Action base class for pathfinding
- AIBotEntity.tick() auto-picks up nearby ItemEntity (3-block radius) into bot inventory
- DevLog is used for structured development logging with tags

## Known Issues

- No persistence (bot inventory and tasks are not saved across server restarts); FakePlayer is re-initialized on task assign but inventory is lost
- No unit tests or game tests
- WorldScanner brute-force scans all blocks in a cube (O(n^3) for radius n)
- HttpClient is created per LLM call instead of being reused
- Some thread-safety concerns in LLMService health check caching
- Language files have encoding issues (mojibake in zh_cn.json)

## Files to Ignore

- uild_*.txt, download_output.txt — build logs from debugging session
- local-repo/, maven-cache/, maven-proxy.ps1 — local dependency infrastructure
- init-proxy.gradle — unused proxy init script
- mc_1211.json, mc_manifest.json — Minecraft version metadata (downloaded)
- TestSSL*.class, TestSSL.java — leftover test artifacts
- 	emp_installertools_*.html — temporary download artifacts
- *.md (except AGENTS.md) — project documentation
