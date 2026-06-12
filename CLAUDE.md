# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build the mod JAR
./gradlew jar

# Compile only (faster feedback)
./gradlew compileJava

# Run all unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.aimod.ai.ActionFactoryTest"

# Run a single test method
./gradlew test --tests "com.aimod.ai.ActionFactoryTest#standardFormat"
```

Output JAR: `build/libs/aimod-<version>.jar`

## Version Management

Every JAR build must:
1. Bump `mod_version` in `gradle.properties`
2. Update `mod_description` in `gradle.properties`
3. Update `src/main/resources/build_info.json` (version, build, fixes array)
4. Update `README.md` version table
5. Update `docs/ANALYSIS_v3.md` with implementation record
6. Delete old JAR files from `build/libs/`

## Architecture

### Dual-Layer Planner
- **LLM Planner**: Natural language → JSON actions → 22 Action types. Uses DeepSeek/GPT/Ollama-compatible API.
- **Local Planner**: `CommandParser` (NLP) + `SequencePlanner` (MaterialTree recipe decomposition). Zero latency, offline.
- **PlanCache**: TF-IDF/Jaccard similarity matching. Successful LLM plans cached and reused.

### Entity Model
```
FakePlayer (ServerPlayer) — the actual bot entity
  ├── BotAIManager → TaskPlanner / TaskExecutor / TaskReplanner
  ├── MovementController → AsyncPathfinder + 11 BotMovement types
  ├── ChainManager → 5 BehaviorChains (Danger/Defense/PlayerDefense/Food/Unstuck)
  ├── BotMemoryStore → 3-tier memory (working/summary/knowledge)
  └── TaskPersistence → JSON file persistence for task restart recovery
```

### Movement System (11 types)
Factory pattern in `BotMovement.create(src, dest, level)` dispatches by dy/dx/dz:
- `Traverse` (horizontal), `Pillar` (up), `Ascend` (diagonal up), `Descend` (diagonal down)
- `StepUp` (up+horizontal), `Fall` (multi-block drop), `Downward` (1-block dig down)
- `DigDown` (multi-block dig down), `Diagonal` (horizontal diagonal)
- `Climb` (ladder/vine), water swimming (in Traverse)

### Behavior Chains (priority-based preemption)
```
P90 DangerChain   — lava/fire/fall/drowning, MLG water bucket
P70 DefenseChain  — mob combat, retreat, shield, ranged
P65 PlayerDefense — PvP (disabled by default)
P55 FoodChain     — auto-eating
P50 UnstuckChain  — stuck detection: WAIT→JUMP→SHIMMY→PILLAR→SKIP
```
Threshold 65: only Danger/Defense can preempt user tasks.

### Action System (22 types)
`TaskPlanner.parseActionFromJson()` handles JSON→Action conversion with:
- Shorthand format correction (`{"mine":"iron_ore"}` → `{"type":"mine","block_type":"iron_ore"}`)
- Position array flattening (`[x,y,z]` → individual fields)
- Parameter nesting flattening

### Thread Model
- Server tick thread: `FakePlayer.tick()`, `ChainManager.tick()`, `MovementController.tick()`
- Background threads: LLM calls (`TaskPlanner`), pathfinding (`AsyncPathfinder`)
- `volatile` fields for cross-thread visibility on `pathExecutor`, `navigating`, `replanning`
- `AtomicInteger`/`AtomicLong` in `BotMetrics` and `TaskReplanner`
- `ConcurrentHashMap` for health check cache and active player tracking

### Client-Server Architecture
- `FakePlayer` is a `ServerPlayer` registered via `placeNewPlayer()`
- Name tags synced via `setCustomName()` + entity metadata
- `RenderNameTagEvent` handler replaces profile name with task status on client
- `BotStatusScreen` opens full 41-slot inventory GUI (armor+offhand+main+hotbar)

## Test Patterns

- JUnit 5 with `@DisplayName`, `@Nested`, `@BeforeEach`
- Mockito available but rarely used (most tests are pure unit tests)
- Tests that need Minecraft runtime (BlockPos, Blocks) cannot run in unit tests — use `Class.forName()` reflection checks instead
- Test files in `src/test/java/com/aimod/`

## Key Config Values

| Config | Default | Notes |
|--------|---------|-------|
| `apiKey` | "" | Empty = use local planner only |
| `modelName` | deepseek-v4-pro | LLM model for task planning |
| `cheapModelName` | "" | Model for replan (falls back to modelName) |
| `maxBots` | 10 | Max simultaneous bots |
| `veinMine` | true | Connected block mining |
| `showTaskAboveHead` | true | Name tag with task status |
| `enablePvpDefense` | false | PvP defense chain (disabled by default) |
| `pathfinderTimeoutMs` | 2000 | A* timeout |
| `maxVeinSize` | 64 | Max blocks per vein mine |

## Common Patterns

### Adding a new Action type
1. Create `src/main/java/com/aimod/ai/action/NewAction.java` extending `Action`
2. Implement `canExecute()`, `execute()`, `isComplete()`
3. Set `failReason` via `setFailReason()` on failure
4. Add case in `TaskPlanner.parseActionFromJson()`
5. Add type name to `TaskPlanner.KNOWN_ACTION_TYPES`

### Adding a new Movement type
1. Create `src/main/java/com/aimod/ai/movement/NewMovement.java` extending `BotMovement`
2. Implement `calculateCost()`, `canExecute()`, `update()`
3. Add dispatch case in `BotMovement.create(src, dest, level)`

### Adding a new BehaviorChain
1. Create class extending `BehaviorChain`
2. Implement `priority()`, `shouldActivate()`, `tick()`, `isActive()`, `stop()`
3. Register in `FakePlayer` constructor with config guard
4. Add enable config to `ModConfig`
