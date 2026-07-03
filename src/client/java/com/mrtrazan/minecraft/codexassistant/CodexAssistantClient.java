package com.mrtrazan.minecraft.codexassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import com.mrtrazan.minecraft.codexassistant.ai.DualAICoordinator;
import com.mrtrazan.minecraft.codexassistant.ai.OpenAIClient;
import com.mrtrazan.minecraft.codexassistant.config.ModConfig;

public class CodexAssistantClient implements ClientModInitializer {

    private KeyBinding openChatKey;
    private KeyBinding panicKey;
    private boolean commandRegistered = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[Codex Assistant Client] Initializing dual AI system...");

        ModConfig.load();

        // Register a keybinding for opening the chat (J)
        try {
            openChatKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.codex_assistant.open_chat",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                KeyBinding.Category.MISC
            ));
        } catch (Throwable t) {
            // KeyBinding API unavailable
        }

        // Register panic key (disable all AI actions)
        try {
            panicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.codex_assistant.panic",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyBinding.Category.MISC
            ));
        } catch (Throwable t) {
            // KeyBinding API unavailable
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Intercept player chat messages client-side
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("openai ") || lower.startsWith("gemini ")) {
                MinecraftClient.getInstance().execute(() -> {
                    com.mrtrazan.minecraft.codexassistant.chat.ChatManager.sendUserMessageFromCommand(trimmed, true);
                });
                return false; // prevent sending to the server
            }
            return true; // allow sending to the server
        });

        // HUD overlay render
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context, tickDelta) -> {
            com.mrtrazan.minecraft.codexassistant.chat.ChatOverlay.render(context);
            com.mrtrazan.minecraft.codexassistant.chat.DebugOverlay.render(context);
        });

        // Try to register /cai later when the client dispatcher becomes available.
        // We'll attempt registration in the client tick handler so it works when the dispatcher is ready.

        System.out.println("[Codex Assistant Client] Dual AI system ready!");
        System.out.println("  - Gemini: PvP Combat Management");
        System.out.println("  - ChatGPT: Inventory & Block Management");
        System.out.println("  - OpenAI API: " + (OpenAIClient.hasApiKey() ? "enabled" : "disabled"));
    }

    private boolean lastJPressed = false;

    private void onClientTick(MinecraftClient client) {
        if (client.player != null && client.world != null) {
            DualAICoordinator.tick(client);
        }

        // ActionPermissionManager is event-driven (accept/decline via /cai commands).
        // CopperBotManager is ticked inside DualAICoordinator.tick above.

        // Register client command when dispatcher becomes available
        if (!commandRegistered) {
            try {
                var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
                if (dispatcher != null) {
                    // /cai — main AI command
                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("cai")
                            .executes(src -> {
                                // Print help instead of opening a GUI
                                MinecraftClient.getInstance().execute(() -> {
                                    if (MinecraftClient.getInstance().player != null) {
                                        MinecraftClient.getInstance().player.sendMessage(
                                            net.minecraft.text.Text.literal("§e[Codex AI] Commands:\n§7  openai <msg> §f- Ask ChatGPT\n§7  gemini <msg> §f- Ask Gemini\n§7  /cai ask <msg>\n§7  /cai ask status\n§7  /cai ask look\n§7  /spawnAI §for §7/spawnbot §f- Spawn Copper Golem bot\n§7  /removeAI §f- Remove bot\n§7  P key §f- Panic toggle (disable AI)"), false);
                                    }
                                });
                                return 1;
                            })
                            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("ask")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.codexassistant.chat.ChatManager.askStatus();
                                        });
                                        return 1;
                                    })
                                )
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("look")
                                    .executes(ctx -> {
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.codexassistant.chat.ChatManager.askLook();
                                        });
                                        return 1;
                                    })
                                )
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String message = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "message");
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.codexassistant.chat.ChatManager.sendUserMessageFromCommand(message);
                                        });
                                        return 1;
                                    })
                                )
                            )
                            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("accept")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(ctx -> {
                                        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.codexassistant.ai.ActionPermissionManager.acceptAction(id);
                                        });
                                        return 1;
                                    })
                                )
                            )
                            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("decline")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(ctx -> {
                                        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.codexassistant.ai.ActionPermissionManager.declineAction(id);
                                        });
                                        return 1;
                                    })
                                )
                            )
                    );

                    // /spawnAI — spawn Copper Golem bot + test APIs
                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("spawnAI")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient mc = MinecraftClient.getInstance();
                                    if (mc.player == null) return;

                                    // Spawn the bot
                                    com.mrtrazan.minecraft.codexassistant.ai.CopperBotManager.spawnBot(mc);

                                    // Test API keys and show status
                                    com.mrtrazan.minecraft.codexassistant.config.ModConfig cfg =
                                        com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance();

                                    boolean hasOpenAI = cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank();
                                    boolean hasGemini = cfg.geminiApiKey != null && !cfg.geminiApiKey.isBlank();

                                    mc.player.sendMessage(
                                        net.minecraft.text.Text.literal(
                                            "§e[Codex AI] API Status:\n"
                                            + "§7  OpenAI key: " + (hasOpenAI ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini key: " + (hasGemini ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini URL: §f" + (cfg.geminiApiUrl != null && !cfg.geminiApiUrl.isBlank() ? cfg.geminiApiUrl : "(default)") + "\n"
                                            + (hasOpenAI || hasGemini ? "§aAI online!" : "§cNo API keys set — use Mod Menu to configure keys!")
                                        ), false);

                                    // Fire quick API ping tests in background
                                    if (hasOpenAI) {
                                        com.mrtrazan.minecraft.codexassistant.ai.OpenAIClient.testApiKey(
                                            cfg.openAiApiKey, cfg.openAiApiUrl, false
                                        ).thenAccept(ok -> mc.execute(() ->
                                            mc.player.sendMessage(
                                                net.minecraft.text.Text.literal("§7  OpenAI ping: " + (ok ? "§a✓ OK" : "§c✗ FAIL - check log for details")), false)
                                        ));
                                    }
                                    if (hasGemini) {
                                        com.mrtrazan.minecraft.codexassistant.ai.OpenAIClient.testApiKey(
                                            cfg.geminiApiKey, cfg.geminiApiUrl, true
                                        ).thenAccept(ok -> mc.execute(() ->
                                            mc.player.sendMessage(
                                                net.minecraft.text.Text.literal("§7  Gemini ping: " + (ok ? "§a✓ OK" : "§c✗ FAIL - check log for details")), false)
                                        ));
                                    }
                                });
                                return 1;
                            })
                    );

                    // /spawnbot — lowercase alias for /spawnAI
                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("spawnbot")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient mc = MinecraftClient.getInstance();
                                    if (mc.player == null) return;
                                    com.mrtrazan.minecraft.codexassistant.ai.CopperBotManager.spawnBot(mc);
                                    com.mrtrazan.minecraft.codexassistant.config.ModConfig cfg =
                                        com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance();
                                    boolean hasOpenAI = cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank();
                                    boolean hasGemini = cfg.geminiApiKey != null && !cfg.geminiApiKey.isBlank();
                                    mc.player.sendMessage(
                                        net.minecraft.text.Text.literal(
                                            "§e[Codex AI] Bot spawned! API:\n"
                                            + "§7  OpenAI: " + (hasOpenAI ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini: " + (hasGemini ? "§aConfigured" : "§cMissing")
                                        ), false);
                                });
                                return 1;
                            })
                    );

                    // /removeAI — remove the bot
                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("removeAI")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    com.mrtrazan.minecraft.codexassistant.ai.CopperBotManager.removeBot(
                                        MinecraftClient.getInstance());
                                });
                                return 1;
                            })
                    );

                    commandRegistered = true;
                }
            } catch (Throwable t) {
                // ignore - will retry on subsequent ticks
            }
        }

        // Keybinding open chat (fallback to old input check if keybinding unavailable)
        try {
            if (openChatKey != null && openChatKey.wasPressed()) {
                client.execute(() -> client.setScreen(new com.mrtrazan.minecraft.codexassistant.chat.ChatScreen()));
            }
            // Panic key handling
            if (panicKey != null && panicKey.wasPressed()) {
                client.execute(() -> {
                    ModConfig.getInstance().aiDisabled = !ModConfig.getInstance().aiDisabled;
                    ModConfig.save();
                    System.out.println("[Codex Assistant] AI disabled: " + ModConfig.getInstance().aiDisabled);
                });
            } else {
                var window = client.getWindow();
                boolean j = net.minecraft.client.util.InputUtil.isKeyPressed(window, org.lwjgl.glfw.GLFW.GLFW_KEY_J);
                if (j && !lastJPressed) {
                    client.execute(() -> client.setScreen(new com.mrtrazan.minecraft.codexassistant.chat.ChatScreen()));
                }
                lastJPressed = j;
            }
        } catch (Throwable t) {
            // ignore input-related issues
        }
    }
}
