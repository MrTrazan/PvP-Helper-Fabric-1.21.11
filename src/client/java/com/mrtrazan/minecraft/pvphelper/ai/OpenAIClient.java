package com.mrtrazan.minecraft.pvphelper.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrtrazan.minecraft.pvphelper.config.ModConfig;

public class OpenAIClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    public static boolean hasApiKey() {
        return hasApiKey(false) || hasApiKey(true);
    }

    public static boolean hasApiKey(boolean isGemini) {
        if (isGemini) {
            String geminiKey = ModConfig.getInstance().geminiApiKey;
            return geminiKey != null && !geminiKey.isBlank();
        } else {
            String apiKey = ModConfig.getInstance().openAiApiKey;
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public static CompletableFuture<String> requestChatCompletion(String model, String systemMessage, String userMessage) {
        return requestChatCompletionWithHistory(model, systemMessage, ConversationManager.recent(), userMessage);
    }

    public static CompletableFuture<String> requestChatCompletionWithHistory(String model, String systemMessage, java.util.List<ConversationManager.Message> history, String userMessage) {
        String apiKey = ModConfig.getInstance().openAiApiKey;
        String apiUrl = ModConfig.getInstance().openAiApiUrl;
        return requestChatCompletionWithHistory(model, systemMessage, history, userMessage, apiKey, apiUrl, false);
    }

    public static CompletableFuture<String> requestChatCompletionWithHistory(String model, String systemMessage, java.util.List<ConversationManager.Message> history, String userMessage, String overrideApiKey, String overrideApiUrl) {
        return requestChatCompletionWithHistory(model, systemMessage, history, userMessage, overrideApiKey, overrideApiUrl, false);
    }

    public static CompletableFuture<String> requestChatCompletionWithHistory(String model, String systemMessage, java.util.List<ConversationManager.Message> history, String userMessage, String overrideApiKey, String overrideApiUrl, boolean isGemini) {
        return enrichMessageWithUrls(userMessage).thenCompose(enrichedUserMessage -> {
            String apiKey = overrideApiKey != null && !overrideApiKey.isBlank() ? overrideApiKey : (isGemini ? ModConfig.getInstance().geminiApiKey : ModConfig.getInstance().openAiApiKey);
            String apiUrl = normalizeApiUrl(overrideApiUrl != null && !overrideApiUrl.isBlank() ? overrideApiUrl : (isGemini ? ModConfig.getInstance().geminiApiUrl : ModConfig.getInstance().openAiApiUrl), isGemini ? DEFAULT_GEMINI_URL : DEFAULT_API_URL);

            if (apiKey == null || apiKey.isBlank()) {
                return CompletableFuture.completedFuture(generateLocalFallbackResponse(model, systemMessage, history, enrichedUserMessage));
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);

            // build messages: include system, history, then user
            JsonArray messages = new JsonArray();
            if (systemMessage != null && !systemMessage.isBlank()) {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", systemMessage);
                messages.add(sys);
            }

            if (history != null) {
                for (ConversationManager.Message m : history) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("role", m.role);
                    mo.addProperty("content", m.content);
                    messages.add(mo);
                }
            }

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", enrichedUserMessage);
            messages.add(user);

            requestBody.add("messages", messages);
            requestBody.addProperty("temperature", 0.35);
            requestBody.addProperty("max_tokens", 300);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("x-goog-api-key", apiKey) // Native Gemini API key compatibility
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody), StandardCharsets.UTF_8))
                .build();

            // simple retry: try twice
            return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    String body = response.body();
                    if (status != 200) {
                        System.err.println("[PvPHelper] API error HTTP " + status + " from " + apiUrl);
                        System.err.println("[PvPHelper] Response body: " + body);
                    }
                    return body;
                })
                .thenApply(OpenAIClient::parseResponse)
                .thenCompose(resp -> {
                    if (resp == null || resp.isBlank()) {
                        // one retry
                        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenApply(retryResp -> {
                                int status = retryResp.statusCode();
                                String body = retryResp.body();
                                if (status != 200) {
                                    System.err.println("[PvPHelper] Retry API error HTTP " + status + " from " + apiUrl);
                                    System.err.println("[PvPHelper] Retry response body: " + body);
                                }
                                return body;
                            })
                            .thenApply(OpenAIClient::parseResponse);
                    }
                    return CompletableFuture.completedFuture(resp);
                })
                .exceptionally(ex -> {
                    System.err.println("[PvPHelper] Network exception calling " + apiUrl + ": " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
        });
    }

    public static CompletableFuture<Boolean> testApiKey(String apiKey, String apiUrl) {
        return testApiKey(apiKey, apiUrl, false);
    }

    public static CompletableFuture<Boolean> testApiKey(String apiKey, String apiUrl, boolean isGemini) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }

        String testUrl = normalizeApiUrl(apiUrl, isGemini ? DEFAULT_GEMINI_URL : DEFAULT_API_URL);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", isGemini ? "gemini-2.0-flash" : "gpt-4o-mini");
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "You are a simple API key validation assistant. Reply with OK.");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Ping");
        messages.add(user);
        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.0);
        requestBody.addProperty("max_tokens", 3);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(testUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .header("x-goog-api-key", apiKey) // Native Gemini API key compatibility
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody), StandardCharsets.UTF_8))
            .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(HttpResponse::body)
            .thenApply(OpenAIClient::parseResponse)
            .thenApply(resp -> resp != null && !resp.isBlank())
            .exceptionally(ex -> false);
    }

    public static CompletableFuture<String> fetchUrlContent(String urlString) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                if (body == null) return "";
                String text = body.replaceAll("<[^>]*>", " ");
                text = text.replaceAll("\\s+", " ").trim();
                if (text.length() > 800) {
                    text = text.substring(0, 800) + "... [truncated]";
                }
                return text;
            } catch (Exception e) {
                return "[Error fetching URL: " + e.getMessage() + "]";
            }
        });
    }

    public static CompletableFuture<String> enrichMessageWithUrls(String content) {
        if (content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(content);
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("https?://\\S+");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        java.util.List<String> urls = new java.util.ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group());
        }

        if (urls.isEmpty()) {
            return CompletableFuture.completedFuture(content);
        }

        java.util.List<CompletableFuture<String>> futures = new java.util.ArrayList<>();
        for (String url : urls) {
            futures.add(fetchUrlContent(url).thenApply(text -> "\n[Website Content of " + url + ": " + text + "]"));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                StringBuilder enriched = new StringBuilder(content);
                for (CompletableFuture<String> f : futures) {
                    enriched.append(f.join());
                }
                return enriched.toString();
            });
    }

    private static String normalizeApiUrl(String apiUrl) {
        return normalizeApiUrl(apiUrl, DEFAULT_API_URL);
    }

    private static String normalizeApiUrl(String apiUrl, String defaultUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return defaultUrl;
        }

        String trimmed = apiUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return defaultUrl;
        }

        // Auto-fix: if a Gemini URL is pointing at the old native API (not the OpenAI-compat endpoint),
        // redirect it to the correct OpenAI-compatible endpoint automatically.
        if (defaultUrl.equals(DEFAULT_GEMINI_URL)) {
            // Old patterns that mean they saved the wrong URL:
            //   https://generativelanguage.googleapis.com/v1beta/models/...
            //   https://generativelanguage.googleapis.com/v1/models/...
            //   https://generativelanguage.googleapis.com/v1beta/models  (no model suffix)
            boolean isOldGeminiNativeUrl =
                (trimmed.contains("generativelanguage.googleapis.com") &&
                 (trimmed.contains("/v1beta/models") || trimmed.contains("/v1/models")));

            if (isOldGeminiNativeUrl) {
                System.err.println("[PvPHelper] Detected old Gemini native API URL: " + trimmed);
                System.err.println("[PvPHelper] Auto-correcting to OpenAI-compatible endpoint.");
                return DEFAULT_GEMINI_URL;
            }

            // Partial fix: if URL ends with /openai, append /chat/completions
            String noSlash = trimmed.replaceAll("/+$", "");
            if (noSlash.endsWith("/openai")) {
                trimmed = noSlash + "/chat/completions";
            }
        }

        try {
            URI.create(trimmed);
            return trimmed;
        } catch (IllegalArgumentException e) {
            return defaultUrl;
        }
    }

    private static String generateLocalFallbackResponse(String model, String systemMessage, java.util.List<ConversationManager.Message> history, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "I'm here but I need a message to respond to.";
        }

        String prompt = userMessage.toLowerCase();
        boolean isGeminiSystem = systemMessage != null && systemMessage.toLowerCase().contains("gemini");
        String aiName = isGeminiSystem ? "Gemini" : "ChatGPT";

        // ── Greeting check FIRST (before any PvP keyword check) ─────────────────
        // Bug fix: previously the Gemini branch returned "NONE" for hello/hi/hey.
        if (prompt.contains("hello") || prompt.contains("hi") || prompt.contains("hey")
                || prompt.matches(".*\\bsup\\b.*") || prompt.matches(".*\\byo\\b.*")) {
            return "Hey! I'm " + aiName + ", your Minecraft AI assistant. "
                + (isGeminiSystem
                    ? "I specialise in PvP combat — I can help you fight, dodge, use crystals, maces, and totems!"
                    : "I handle your inventory and resource planning. Ask me anything about items, crafting, or survival!"
                );
        }

        // ── Gemini PvP fallback (no API key) ─────────────────────────────────────
        if (isGeminiSystem) {
            if (prompt.contains("distance") || prompt.contains("enemy") || prompt.contains("attack")) {
                return "ATTACK";
            }
            return "NONE";
        }

        // ── ChatGPT / general fallback ────────────────────────────────────────────
        if (systemMessage != null && systemMessage.toLowerCase().contains("resource")) {
            if (prompt.contains("gather") || prompt.contains("resources") || prompt.contains("stone") || prompt.contains("dirt")) {
                return "You should collect more wood and food if your supplies are low, then return to safety.";
            }
            return "Focus on gathering essentials like food, tools, and building materials.";
        }

        if (prompt.contains("inventory") || prompt.contains("items") || prompt.contains("stack")) {
            return "Keep your sword, tools, and food. Drop excess dirt, cobblestone, and low-value blocks.";
        }

        if (prompt.contains("craft") || prompt.contains("build")) {
            return "Use your best tools and craft backups. If you're low on wood, gather logs first.";
        }

        if (prompt.contains("attack") || prompt.contains("fight") || prompt.contains("enemy")) {
            return "Stay on the move, keep your health up, and strike when your opponent is close.";
        }

        return "I don't have API access right now, but I'm still here to offer basic Minecraft guidance. Try asking about inventory, resources, or combat.";
    }

    private static String parseResponse(String body) {
        if (body == null || body.isBlank()) {
            System.err.println("[PvPHelper] parseResponse: received null/empty body");
            return null;
        }
        try {
            // Handle case where response is a JSON array (old native Gemini API format)
            // This means the wrong URL was used — log it clearly
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(body);
            if (element.isJsonArray()) {
                System.err.println("[PvPHelper] parseResponse: response is a JSON array — you are hitting the OLD native Gemini API, not the OpenAI-compatible endpoint!");
                System.err.println("[PvPHelper] Clear the Gemini URL field in Mod Menu config and Save again.");
                // Try to extract text from native Gemini format anyway
                com.google.gson.JsonArray arr = element.getAsJsonArray();
                if (arr.size() > 0) {
                    JsonObject first = arr.get(0).getAsJsonObject();
                    if (first.has("candidates")) {
                        JsonArray candidates = first.getAsJsonArray("candidates");
                        if (candidates.size() > 0) {
                            JsonObject cand = candidates.get(0).getAsJsonObject();
                            if (cand.has("content")) {
                                JsonObject content = cand.getAsJsonObject("content");
                                if (content.has("parts")) {
                                    JsonArray parts = content.getAsJsonArray("parts");
                                    if (parts.size() > 0 && parts.get(0).getAsJsonObject().has("text")) {
                                        return parts.get(0).getAsJsonObject().get("text").getAsString().trim();
                                    }
                                }
                            }
                        }
                    }
                }
                return null;
            }

            JsonObject json = element.getAsJsonObject();
            if (json == null) {
                System.err.println("[PvPHelper] parseResponse: body is not valid JSON: " + body);
                return null;
            }
            // Check for API-level error object
            if (json.has("error")) {
                JsonObject err = json.getAsJsonObject("error");
                String errMsg = err.has("message") ? err.get("message").getAsString() : body;
                System.err.println("[PvPHelper] API returned error: " + errMsg);
                return null;
            }

            // Native Gemini API Format (JSON Object containing candidates)
            if (json.has("candidates")) {
                JsonArray candidates = json.getAsJsonArray("candidates");
                if (candidates.size() > 0) {
                    JsonObject cand = candidates.get(0).getAsJsonObject();
                    if (cand.has("content")) {
                        JsonObject content = cand.getAsJsonObject("content");
                        if (content.has("parts")) {
                            JsonArray parts = content.getAsJsonArray("parts");
                            if (parts.size() > 0 && parts.get(0).getAsJsonObject().has("text")) {
                                return parts.get(0).getAsJsonObject().get("text").getAsString().trim();
                            }
                        }
                    }
                }
            }

            if (!json.has("choices")) {
                System.err.println("[PvPHelper] parseResponse: no 'choices' field. Body: " + body);
                return null;
            }
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices.size() == 0) {
                System.err.println("[PvPHelper] parseResponse: 'choices' array is empty");
                return null;
            }
            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            if (firstChoice.has("message")) {
                JsonObject message = firstChoice.getAsJsonObject("message");
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    return message.get("content").getAsString().trim();
                }
            }
            if (firstChoice.has("text")) {
                return firstChoice.get("text").getAsString().trim();
            }
            System.err.println("[PvPHelper] parseResponse: could not extract content. Choice: " + firstChoice);
        } catch (Exception e) {
            System.err.println("[PvPHelper] parseResponse exception. Body was: " + body);
            e.printStackTrace();
        }
        return null;
    }
}
