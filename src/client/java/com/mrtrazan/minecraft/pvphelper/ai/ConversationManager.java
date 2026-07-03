package com.mrtrazan.minecraft.pvphelper.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple in-memory conversation history to provide context for API calls.
 * Keeps the last N messages (user or assistant) per client session.
 */
public class ConversationManager {

    private static final int MAX_MESSAGES = 12;
    private static final Deque<Message> messages = new ArrayDeque<>();

    public static class Message {
        public final String role; // "user" or "assistant" or "system"
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static synchronized void addUserMessage(String content) {
        add(new Message("user", content));
    }

    public static synchronized void addAssistantMessage(String content) {
        add(new Message("assistant", content));
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            ActionPermissionManager.scanAndProposeActions(client, content);
        } catch (Throwable t) {
            // ignore if not running client thread / tests
        }
    }

    public static synchronized void addSystemMessage(String content) {
        add(new Message("system", content));
    }

    private static void add(Message m) {
        messages.addLast(m);
        while (messages.size() > MAX_MESSAGES) messages.removeFirst();
    }

    public static synchronized List<Message> recent() {
        return messages.stream().collect(Collectors.toList());
    }

    public static synchronized void clear() {
        messages.clear();
    }
}
