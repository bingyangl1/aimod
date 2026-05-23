package com.aimod.ai.llm;

import java.util.List;
import java.util.ArrayList;

public class LLMResponse {
    private boolean success;
    private String rawResponse;
    private List<String> actions;
    private String error;

    public LLMResponse(boolean success, String rawResponse) {
        this.success = success;
        this.rawResponse = rawResponse;
        this.actions = new ArrayList<>();
        this.error = null;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public static LLMResponse success(String rawResponse) {
        return new LLMResponse(true, rawResponse);
    }

    public static LLMResponse failure(String error) {
        LLMResponse response = new LLMResponse(false, null);
        response.setError(error);
        return response;
    }
}