# AI Mod for Minecraft 1.21.1

A NeoForge mod that adds an AI-controlled bot entity to Minecraft. The bot executes natural language commands by calling a Large Language Model (LLM) API, then performs the resulting actions using a FakePlayer system.

## Features

- **Natural Language Commands**: Tell the bot what to do in plain language via /ai_bot task <command>
- **LLM Integration**: Supports OpenAI-compatible APIs (OpenAI, Claude, Ollama, LM Studio)
- **14 Action Types**: Move, mine, build, craft, fight, follow, interact, and more
- **FakePlayer System**: Bot simulates real player actions through a fake network handler
- **World Awareness**: LLM receives context about bot position, inventory, nearby blocks, and entities
- **Task Feedback**: Real-time status updates sent to the player who assigned the task
- **Configurable**: 12 configuration options for API, timeouts, and behavior

## Quick Start

### Prerequisites

- Java 21 (JDK)
- Minecraft 1.21.1
- NeoForge 21.1.176

### Build

`ash
# Standard build will FAIL due to SSL issues. Use local init script:
./gradlew.bat build --init-script init-local.gradle
`

The mod JAR will be in uild/libs/.

### Installation

1. Install NeoForge 1.21.1
2. Place the mod JAR in your mods/ folder
3. Launch Minecraft
4. Configure API settings in config/aimod-common.toml

### Configuration

Edit config/aimod-common.toml after first launch:

`	oml
apiUrl = "https://api.openai.com/v1/chat/completions"
apiKey = "sk-your-api-key"
modelName = "gpt-4"
maxTokens = 1024
temperature = 0.7
`

For local models (Ollama/LM Studio), set piUrl to your local endpoint and leave piKey empty.

## Usage

### Commands

| Command | Description |
|---------|-------------|
| /ai_bot spawn | Spawn an AI bot near you |
| /ai_bot task <command> | Assign a natural language task to the nearest bot |
| /ai_bot status | Show status of the nearest bot |

### Examples

`
/ai_bot task chop 10 oak logs
/ai_bot task build a small wooden house
/ai_bot task mine 5 iron ore
/ai_bot task follow me
/ai_bot task craft a diamond sword
/ai_bot task kill all zombies nearby
`

### How It Works

1. Player assigns a task via /ai_bot task <command>
2. Bot sends the command + world context to the configured LLM API
3. LLM returns a JSON action plan
4. BotAIManager parses the plan into Action objects
5. Actions execute sequentially (move, mine, craft, etc.)
6. TaskFeedback reports progress to the player

## Architecture

`
src/main/java/com/example/aimod/
├── AIMod.java                    # @Mod entrypoint
├── ai/
│   ├── BotAIManager.java         # LLM response parsing, task execution
│   ├── Task.java                 # Task data model
│   ├── TaskFeedback.java         # Player notification system
│   ├── WorldScanner.java         # Block/entity/environment scanning
│   ├── InventoryUtils.java       # Bot inventory helpers
│   ├── action/                   # 14 action implementations
│   │   ├── Action.java           # Base class
│   │   ├── MoveToAction.java
│   │   ├── BreakBlockAction.java
│   │   ├── PlaceBlockAction.java
│   │   ├── AttackAction.java
│   │   ├── CraftAction.java
│   │   ├── FollowAction.java
│   │   ├── GiveItemAction.java
│   │   ├── RequireItemsAction.java
│   │   ├── SayAction.java
│   │   ├── WaitAction.java
│   │   ├── MineBlockAction.java
│   │   ├── GatherResourceAction.java
│   │   ├── InteractBlockAction.java
│   │   └── EquipItemAction.java
│   └── llm/
│       ├── LLMService.java       # HTTP client for LLM API
│       └── LLMResponse.java      # Response data model
├── command/
│   └── BotCommand.java           # Brigadier command registration
├── config/
│   └── ModConfig.java            # 12 config options
├── entity/
│   ├── AIBotEntity.java          # Bot mob (extends PathfinderMob)
│   └── ModEntities.java          # Entity type registration
├── fakeplayer/
│   ├── FakePlayer.java           # ServerPlayer subclass
│   ├── FakePlayerManager.java    # Lifecycle management
│   ├── FakeNetHandler.java       # Fake packet handler
│   └── FakeConnection.java       # EmbeddedChannel connection
├── client/
│   ├── ClientModEvents.java      # Renderer registration
│   ├── renderer/AIBotRenderer.java
│   └── model/AIBotModel.java
└── util/
    └── DevLog.java               # Structured logging
`

## Development

### Adding a New Action

1. Create a class in i/action/ extending Action:

`java
public class MyAction extends Action {
    public MyAction() {
        super("My action description");
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        // Check if action can be performed
        return true;
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
        }
        // Perform action logic
        if (done) {
            status = ActionStatus.COMPLETED;
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }
}
`

2. Register in BotAIManager.convertResponseToActions() — add a case to the switch statement.

### Key Design Patterns

- **State Machine**: Actions use PENDING → IN_PROGRESS → COMPLETED/FAILED lifecycle
- **FakePlayer Delegation**: Actions use getFakePlayer(bot) for player-like operations
- **Async LLM Calls**: LLM requests run on daemon threads to avoid blocking the server tick
- **World Context Injection**: WorldScanner provides environment data to the LLM prompt

## Known Limitations

- **Pathfinding**: Uses vanilla navigation; no advanced pathfinding (e.g., Baritone-style)
- **Persistence**: Bot inventory and tasks are not saved across server restarts
- **Performance**: WorldScanner brute-force scans blocks (O(n³) for radius n)
- **Concurrency**: LLMService health check caching has thread-safety concerns
- **Testing**: No unit tests or game tests implemented

## Dependencies

- NeoForge 21.1.176
- Minecraft 1.21.1
- Parchment mappings 2024.11.17

## License

MIT
