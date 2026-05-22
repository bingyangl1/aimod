package com.example.aimod.ai;

import java.util.List;
import java.util.ArrayList;
import com.example.aimod.ai.action.Action;

public class Task {
    private String description;
    private TaskStatus status;
    private List<Action> actions;
    private int currentActionIndex;

    public Task(String description) {
        this.description = description;
        this.status = TaskStatus.PENDING;
        this.actions = new ArrayList<>();
        this.currentActionIndex = 0;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public List<Action> getActions() {
        return actions;
    }

    public void setActions(List<Action> actions) {
        this.actions = actions;
    }

    public boolean isCompleted() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    public Action getCurrentAction() {
        if (currentActionIndex < actions.size()) {
            return actions.get(currentActionIndex);
        }
        return null;
    }

    public int getCurrentActionIndex() {
        return currentActionIndex;
    }

    public int getActionCount() {
        return actions.size();
    }

    public void advanceToNextAction() {
        currentActionIndex++;
        if (currentActionIndex >= actions.size()) {
            status = TaskStatus.COMPLETED;
        }
    }

    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
