package com.aimod.ai;

import com.aimod.ai.llm.BotAIStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BotAIManager structure and decomposition.
 */
class BotAIManagerTest {

    @Test
    @DisplayName("BotAIStateMachine has setTaskInfo method")
    void stateMachineHasSetTaskInfo() {
        assertDoesNotThrow(() -> {
            BotAIStateMachine.class.getMethod("setTaskInfo", String.class, int.class);
        });
    }

    @Test
    @DisplayName("BotAIStateMachine has setCurrentActionDesc method")
    void stateMachineHasSetCurrentActionDesc() {
        assertDoesNotThrow(() -> {
            BotAIStateMachine.class.getMethod("setCurrentActionDesc", String.class);
        });
    }

    @Test
    @DisplayName("BotAIStateMachine has getCurrentActionDesc method")
    void stateMachineHasGetCurrentActionDesc() {
        assertDoesNotThrow(() -> {
            BotAIStateMachine.class.getMethod("getCurrentActionDesc");
        });
    }

    @Test
    @DisplayName("BotAIStateMachine has getTaskDescription method")
    void stateMachineHasGetTaskDescription() {
        assertDoesNotThrow(() -> {
            BotAIStateMachine.class.getMethod("getTaskDescription");
        });
    }
}
