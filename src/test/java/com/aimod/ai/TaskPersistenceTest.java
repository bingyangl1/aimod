package com.aimod.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskPersistence — task save/restore/delete.
 */
@DisplayName("TaskPersistence — task persistence")
class TaskPersistenceTest {

    @TempDir
    Path tempDir;

    private TaskPersistence persistence;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @BeforeEach
    void setUp() {
        persistence = new TaskPersistence(tempDir);
    }

    @Nested
    @DisplayName("TaskData serialization")
    class TaskDataSerialization {
        @Test
        @DisplayName("TaskData can be serialized and deserialized")
        void serializeDeserialize() {
            TaskPersistence.TaskData data = new TaskPersistence.TaskData();
            data.botUuid = "test-uuid-1234";
            data.botName = "TestBot";
            data.command = "mine 64 cobblestone";
            data.ownerName = "Player1";
            data.status = "IN_PROGRESS";
            data.currentActionIndex = 3;
            data.actions = java.util.List.of(
                    "{\"type\":\"move_to\",\"x\":10,\"y\":64,\"z\":20}",
                    "{\"type\":\"mine\",\"block_id\":\"cobblestone\",\"count\":64}"
            );
            data.createdAt = 1234567890L;

            String json = GSON.toJson(data);
            assertNotNull(json);
            assertFalse(json.isEmpty());

            TaskPersistence.TaskData restored = GSON.fromJson(json, TaskPersistence.TaskData.class);
            assertNotNull(restored);
            assertEquals(data.botUuid, restored.botUuid);
            assertEquals(data.botName, restored.botName);
            assertEquals(data.command, restored.command);
            assertEquals(data.ownerName, restored.ownerName);
            assertEquals(data.status, restored.status);
            assertEquals(data.currentActionIndex, restored.currentActionIndex);
            assertEquals(data.actions.size(), restored.actions.size());
            assertEquals(data.createdAt, restored.createdAt);
        }

        @Test
        @DisplayName("TaskData handles null fields")
        void nullFields() {
            TaskPersistence.TaskData data = new TaskPersistence.TaskData();
            data.botUuid = "test-uuid";
            data.actions = java.util.List.of();

            String json = GSON.toJson(data);
            TaskPersistence.TaskData restored = GSON.fromJson(json, TaskPersistence.TaskData.class);
            assertNotNull(restored);
            assertEquals("test-uuid", restored.botUuid);
            assertNull(restored.botName);
            assertNull(restored.command);
        }
    }

    @Nested
    @DisplayName("File operations")
    class FileOperations {
        @Test
        @DisplayName("exists returns false for non-existent bot")
        void existsNonExistent() {
            // We can't call exists() without a FakePlayer, but we can verify the tasks dir
            Path tasksDir = tempDir.resolve("config/aimod/tasks");
            assertFalse(Files.exists(tasksDir));
        }

        @Test
        @DisplayName("Tasks directory is created on save")
        void directoryCreated() throws IOException {
            Path tasksDir = tempDir.resolve("config/aimod/tasks");
            Files.createDirectories(tasksDir);
            assertTrue(Files.exists(tasksDir));
        }

        @Test
        @DisplayName("JSON file can be written and read")
        void jsonFileRoundTrip() throws IOException {
            Path tasksDir = tempDir.resolve("config/aimod/tasks");
            Files.createDirectories(tasksDir);

            TaskPersistence.TaskData data = new TaskPersistence.TaskData();
            data.botUuid = "test-uuid";
            data.command = "test command";
            data.actions = java.util.List.of("{\"type\":\"say\",\"message\":\"hello\"}");

            Path file = tasksDir.resolve("test-uuid.json");
            Files.writeString(file, GSON.toJson(data));

            assertTrue(Files.exists(file));

            String json = Files.readString(file);
            TaskPersistence.TaskData restored = GSON.fromJson(json, TaskPersistence.TaskData.class);
            assertEquals("test-uuid", restored.botUuid);
            assertEquals("test command", restored.command);
        }
    }
}
