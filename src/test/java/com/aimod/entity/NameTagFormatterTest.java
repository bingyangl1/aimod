package com.aimod.entity;

import com.aimod.ai.llm.BotAIStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NameTagFormatter and BotAIStateMachine — name tag display logic.
 * Note: NameTagFormatter.formatState() returns Minecraft Component which
 * is not available in test classpath, so we test the underlying state machine.
 */
@DisplayName("NameTagFormatter — state machine integration")
class NameTagFormatterTest {

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {
        @Test
        @DisplayName("IDLE -> PLANNING -> EXECUTING -> COMPLETED")
        void fullLifecycle() {
            BotAIStateMachine sm = new BotAIStateMachine();
            assertEquals(BotAIStateMachine.State.IDLE, sm.getCurrent());

            sm.startPlanning("test", 5);
            assertEquals(BotAIStateMachine.State.PLANNING, sm.getCurrent());

            sm.startExecuting();
            assertEquals(BotAIStateMachine.State.EXECUTING, sm.getCurrent());

            sm.complete();
            assertEquals(BotAIStateMachine.State.COMPLETED, sm.getCurrent());
        }

        @Test
        @DisplayName("IDLE -> PLANNING -> EXECUTING -> FAILED")
        void failedLifecycle() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.startPlanning("test", 5);
            sm.startExecuting();
            sm.fail();
            assertEquals(BotAIStateMachine.State.FAILED, sm.getCurrent());
        }

        @Test
        @DisplayName("actionCompleted increments counter")
        void actionCompleted() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.startPlanning("test", 5);
            assertEquals(0, sm.getActionsDone());

            sm.actionCompleted();
            assertEquals(1, sm.getActionsDone());

            sm.actionCompleted();
            assertEquals(2, sm.getActionsDone());
        }

        @Test
        @DisplayName("setTaskInfo sets description and total")
        void setTaskInfo() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.setTaskInfo("my task", 10);
            assertEquals("my task", sm.getTaskDescription());
            assertEquals(10, sm.getActionsTotal());
        }

        @Test
        @DisplayName("setCurrentActionDesc sets description")
        void setCurrentActionDesc() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.setCurrentActionDesc("Mine iron_ore");
            assertEquals("Mine iron_ore", sm.getCurrentActionDesc());
        }

        @Test
        @DisplayName("reset clears all state")
        void reset() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.startPlanning("test", 5);
            sm.startExecuting();
            sm.actionCompleted();
            sm.reset();

            assertEquals(BotAIStateMachine.State.IDLE, sm.getCurrent());
            assertEquals(0, sm.getActionsDone());
            assertNull(sm.getTaskDescription());
        }

        @Test
        @DisplayName("isActive returns true when not IDLE/COMPLETED/FAILED")
        void isActive() {
            BotAIStateMachine sm = new BotAIStateMachine();
            assertFalse(sm.isActive());

            sm.startPlanning("test", 5);
            assertTrue(sm.isActive());

            sm.startExecuting();
            assertTrue(sm.isActive());

            sm.complete();
            assertFalse(sm.isActive());
        }

        @Test
        @DisplayName("canAcceptTask returns true when IDLE/COMPLETED/FAILED")
        void canAcceptTask() {
            BotAIStateMachine sm = new BotAIStateMachine();
            assertTrue(sm.canAcceptTask());

            sm.startPlanning("test", 5);
            assertFalse(sm.canAcceptTask());

            sm.startExecuting();
            assertFalse(sm.canAcceptTask());

            sm.complete();
            assertTrue(sm.canAcceptTask());
        }

        @Test
        @DisplayName("getProgress returns correct ratio")
        void getProgress() {
            BotAIStateMachine sm = new BotAIStateMachine();
            sm.startPlanning("test", 10);
            assertEquals(0.0f, sm.getProgress(), 0.001f);

            sm.actionCompleted();
            sm.actionCompleted();
            sm.actionCompleted();
            assertEquals(0.3f, sm.getProgress(), 0.001f);
        }
    }
}
