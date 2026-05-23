package com.aimod.ai.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LLMResponse Tests")
class LLMResponseTest {

    @Test
    @DisplayName("success factory creates successful response")
    void successFactoryCreatesSuccess() {
        LLMResponse response = LLMResponse.success("raw response");
        assertTrue(response.isSuccess());
        assertEquals("raw response", response.getRawResponse());
        assertNull(response.getError());
        assertTrue(response.getActions().isEmpty());
    }

    @Test
    @DisplayName("failure factory creates failed response")
    void failureFactoryCreatesFailure() {
        LLMResponse response = LLMResponse.failure("error message");
        assertFalse(response.isSuccess());
        assertNull(response.getRawResponse());
        assertEquals("error message", response.getError());
    }

    @Test
    @DisplayName("setActions updates actions list")
    void setActionsUpdatesList() {
        LLMResponse response = LLMResponse.success("raw");
        List<String> actions = Arrays.asList("move 100 64 200", "say hello");
        response.setActions(actions);
        assertEquals(2, response.getActions().size());
        assertEquals("move 100 64 200", response.getActions().get(0));
    }

    @Test
    @DisplayName("constructor initializes empty actions list")
    void constructorInitializesEmptyActions() {
        LLMResponse response = new LLMResponse(true, "raw");
        assertNotNull(response.getActions());
        assertTrue(response.getActions().isEmpty());
    }
}