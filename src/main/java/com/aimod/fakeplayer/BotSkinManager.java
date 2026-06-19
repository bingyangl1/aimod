package com.aimod.fakeplayer;

import com.aimod.config.ModConfig;
import com.aimod.util.DevLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Manages bot skin application via Mojang textures API.
 * Downloads skin PNG and applies it to GameProfile via textures property.
 */
public class BotSkinManager {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private static final String MOJANG_TEXTURES_API = "https://api.mojang.com/minecraft/profile/lookup/";

    /**
     * Apply skin from URL to a GameProfile.
     * The skin URL should be a direct PNG URL (e.g., https://example.com/skin.png).
     *
     * @param profile the GameProfile to apply skin to
     * @param skinUrl direct PNG URL of the skin
     */
    public static void applySkinFromUrl(GameProfile profile, String skinUrl) {
        if (skinUrl == null || skinUrl.isBlank()) return;

        try {
            // Encode the skin URL as a textures property
            // Mojang's textures format: {"textures":{"SKIN":{"url":"<skin_url>"}}}
            String texturesJson = String.format(
                    "{\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}",
                    skinUrl);
            String encodedTextures = Base64.getEncoder().encodeToString(
                    texturesJson.getBytes(StandardCharsets.UTF_8));

            // Add textures property to the profile
            profile.getProperties().put("textures", new Property("textures", encodedTextures, ""));
            DevLog.info("BOT_SKIN_APPLIED", "profile={}, url={}", profile.getName(), skinUrl);
        } catch (Exception e) {
            DevLog.warn("BOT_SKIN_APPLY_FAILED", "profile={}, url={}, err={}",
                    profile.getName(), skinUrl, e.getMessage());
        }
    }

    /**
     * Apply skin from Mojang username (downloads from Mojang API).
     *
     * @param profile the GameProfile to apply skin to
     * @param username the Mojang username to look up
     */
    public static void applySkinFromUsername(GameProfile profile, String username) {
        if (username == null || username.isBlank()) return;

        CompletableFuture.runAsync(() -> {
            try {
                // Look up UUID from username
                HttpRequest uuidRequest = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                        .GET()
                        .build();

                HttpResponse<String> uuidResponse = HTTP_CLIENT.send(uuidRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (uuidResponse.statusCode() != 200) {
                    DevLog.warn("BOT_SKIN_UUID_FAILED", "username={}, code={}", username, uuidResponse.statusCode());
                    return;
                }

                JsonObject uuidJson = JsonParser.parseString(uuidResponse.body()).getAsJsonObject();
                String uuid = uuidJson.get("id").getAsString();

                // Get textures from UUID
                HttpRequest texturesRequest = HttpRequest.newBuilder()
                        .uri(URI.create(MOJANG_TEXTURES_API + uuid))
                        .GET()
                        .build();

                HttpResponse<String> texturesResponse = HTTP_CLIENT.send(texturesRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (texturesResponse.statusCode() != 200) {
                    DevLog.warn("BOT_SKIN_TEXTURES_FAILED", "uuid={}, code={}", uuid, texturesResponse.statusCode());
                    return;
                }

                JsonObject texturesJson = JsonParser.parseString(texturesResponse.body()).getAsJsonObject();
                var properties = texturesJson.getAsJsonArray("properties");
                if (properties != null && properties.size() > 0) {
                    String value = properties.get(0).getAsJsonObject().get("value").getAsString();
                    String signature = properties.get(0).getAsJsonObject().has("signature")
                            ? properties.get(0).getAsJsonObject().get("signature").getAsString() : "";
                    synchronized (profile) {
                        profile.getProperties().put("textures", new Property("textures", value, signature));
                    }
                    DevLog.info("BOT_SKIN_USERNAME_APPLIED", "profile={}, username={}", profile.getName(), username);
                }
            } catch (Exception e) {
                DevLog.warn("BOT_SKIN_USERNAME_FAILED", "username={}, err={}", username, e.getMessage());
            }
        });
    }

    /**
     * Apply skin based on config. Checks botSkinUrl first, then falls back to default.
     */
    public static void applyConfiguredSkin(GameProfile profile) {
        String skinUrl = ModConfig.getBotSkinUrl();
        if (skinUrl != null && !skinUrl.isBlank()) {
            applySkinFromUrl(profile, skinUrl);
        }
    }
}
