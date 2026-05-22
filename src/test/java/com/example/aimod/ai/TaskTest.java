package com.example.aimod.ai;

import com.example.aimod.ai.Task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task Tests")
class TaskTest {

    private Task task;

    @BeforeEach
    void setUp() {
        task = new Task("test command");
    }

    @Test
    @DisplayName("New task should be PENDING with empty actions")
    void newTaskShouldBePending() {
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals("test command", task.getDescription());
        assertTrue(task.getActions().isEmpty());
        assertEquals(0, task.getActionCount());
        assertEquals(0, task.getCurrentActionIndex());
        assertNull(task.getCurrentAction());
    }

    @Test
    @DisplayName("isCompleted returns true for COMPLETED status")
    void isCompletedForCompletedStatus() {
        task.setStatus(TaskStatus.COMPLETED);
        assertTrue(task.isCompleted());
    }

    @Test
    @DisplayName("isCompleted returns true for FAILED status")
    void isCompletedForFailedStatus() {
        task.setStatus(TaskStatus.FAILED);
        assertTrue(task.isCompleted());
    }

    @Test
    @DisplayName("isCompleted returns false for PENDING status")
    void isCompletedFalseForPending() {
        assertFalse(task.isCompleted());
    }

    @Test
    @DisplayName("isCompleted returns false for IN_PROGRESS status")
    void isCompletedFalseForInProgress() {
        task.setStatus(TaskStatus.IN_PROGRESS);
        assertFalse(task.isCompleted());
    }

    @Test
    @DisplayName("getCurrentAction returns null when no actions")
    void getCurrentActionNullWhenEmpty() {
        assertNull(task.getCurrentAction());
    }

    @Test
    @DisplayName("setStatus updates task status correctly")
    void setStatusUpdatesCorrectly() {
        task.setStatus(TaskStatus.IN_PROGRESS);
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        
        task.setStatus(TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
    }
}