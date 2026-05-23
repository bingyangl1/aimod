package com.aimod.ai.llm;

import com.aimod.util.DevLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LLMResponseParser {
    private LLMResponseParser() {}

    public static LLMResponse parseResponse(String response) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                String content = message.get("content").getAsString();
                DevLog.info("LLM_RESPONSE", "content={}", DevLog.compact(content));
                LLMResponse llmResponse = LLMResponse.success(content);
                List<String> actions = parseActionsFromContent(content);
                llmResponse.setActions(actions);
                DevLog.info("LLM_ACTIONS", "count={}, actions={}", actions.size(), DevLog.compact(actions.toString()));
                return llmResponse;
            }
        } catch (Exception e) {
            DevLog.warn("LLM_PARSE_ERROR", "failed to parse provider response: {}", e.getMessage());
        }
        return LLMResponse.failure("Failed to parse LLM response");
    }

    public static List<String> parseActionsFromContent(String content) {
        // 策略1：标准解析 - 查找完整的JSON对象
        List<String> actions = parseStandard(content);
        if (!actions.isEmpty()) return actions;

        // 策略2：宽松解析 - 直接查找"actions": [...]模式
        actions = findActionsArrayLoosely(content);
        if (!actions.isEmpty()) return actions;

        // 策略3：提取独立的action对象
        return extractActionObjects(content);
    }

    private static List<String> parseStandard(String content) {
        try {
            if (content.contains("{") && content.contains("}")) {
                int start = content.indexOf("{");
                int end = content.lastIndexOf("}") + 1;
                String jsonStr = content.substring(start, end);
                JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                if (json.has("actions")) {
                    JsonArray actionsArray = json.getAsJsonArray("actions");
                    List<String> actions = new ArrayList<>();
                    for (int i = 0; i < actionsArray.size(); i++) {
                        actions.add(actionsArray.get(i).toString());
                    }
                    return actions;
                }
            }
        } catch (Exception e) {
            DevLog.warn("LLM_PARSE_STANDARD_FAILED", "standard parse failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 宽松解析：直接查找"actions": [...]模式
     */
    private static List<String> findActionsArrayLoosely(String content) {
        List<String> actions = new ArrayList<>();
        try {
            // 使用正则表达式查找"actions": [ ... ] 模式
            Pattern pattern = Pattern.compile("\"actions\"\\s*:\\s*(\\[[^\\]]*\\])");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String jsonArrayStr = matcher.group(1);
                // 尝试解析这个数组
                JsonElement element = JsonParser.parseString(jsonArrayStr);
                if (element.isJsonArray()) {
                    JsonArray actionsArray = element.getAsJsonArray();
                    for (int i = 0; i < actionsArray.size(); i++) {
                        actions.add(actionsArray.get(i).toString());
                    }
                }
            }
        } catch (Exception e) {
            DevLog.warn("LLM_ACTION_PARSE_LOOSE_ERROR", "loose parsing failed: {}", e.getMessage());
        }
        return actions;
    }

    /**
     * 提取看起来像action对象的内容（最后的备选方案）
     */
    private static List<String> extractActionObjects(String content) {
        List<String> actions = new ArrayList<>();
        try {
            // 查找所有看起来像独立JSON对象的内容
            Pattern pattern = Pattern.compile("\\{[^}]*\\}");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String potentialAction = matcher.group();
                // 验证这是否包含action类型字段
                if (potentialAction.contains("\"type\"")) {
                    actions.add(potentialAction);
                }
            }
        } catch (Exception e) {
            DevLog.warn("LLM_ACTION_EXTRACT_ERROR", "action extraction failed: {}", e.getMessage());
        }
        return actions;
    }

    public static List<ActionDescriptor> parseActionDescriptors(LLMResponse response) {
        List<ActionDescriptor> descriptors = new ArrayList<>();
        for (String actionJson : response.getActions()) {
            try {
                JsonObject actionObj = JsonParser.parseString(actionJson).getAsJsonObject();
                String type = getString(actionObj, "type", "");
                descriptors.add(new ActionDescriptor(type, actionObj));
            } catch (Exception e) {
                DevLog.warn("ACTION_DESCRIPTOR_ERROR", "failed to parse action: {}", e.getMessage());
            }
        }
        return descriptors;
    }

    public static void appendStreamingChunk(JsonObject chunk, StringBuilder content) {
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return;
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                ? choice.getAsJsonObject("delta") : null;
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        JsonObject source = delta != null ? delta : message;
        if (source == null) return;
        appendIfPresent(source, "reasoning_content", null);
        appendIfPresent(source, "reasoning", null);
        appendIfPresent(source, "content", content);
    }

    public static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsString();
        return defaultValue;
    }

    public static int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsInt(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }

    public static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsDouble(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static void appendIfPresent(JsonObject object, String key, StringBuilder target) {
        if (!object.has(key) || object.get(key).isJsonNull()) return;
        String value = object.get(key).getAsString();
        if (value.isEmpty()) return;
        DevLog.info("LLM_STREAM_CHUNK", "{}", DevLog.compact(value));
        if (target != null) target.append(value);
    }
}