package com.mrtrazan.minecraft.pvphelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import com.mrtrazan.minecraft.pvphelper.ai.DualAICoordinator;
import com.mrtrazan.minecraft.pvphelper.ai.OpenAIClient;
import com.mrtrazan.minecraft.pvphelper.config.ModConfig;

public class PvPHelperClient implements ClientModInitializer {

    private KeyBinding openChatKey;
    private KeyBinding panicKey;
    private KeyBinding toggleAiKey;
    private boolean commandRegistered = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[PvP Helper Client] Initializing dual AI system...");

        ModConfig.load();

        try {
            openChatKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pvp_helper.open_chat",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                KeyBinding.MISC_CATEGORY
            ));
        } catch (Throwable t) {
        }

        try {
            panicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pvp_helper.panic",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyBinding.MISC_CATEGORY
            ));
        } catch (Throwable t) {
        }

        try {
            toggleAiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pvp_helper.toggle_ai",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.MISC_CATEGORY
            ));
        } catch (Throwable t) {
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("openai ") || lower.startsWith("gemini ")) {
                MinecraftClient.getInstance().execute(() -> {
                    com.mrtrazan.minecraft.pvphelper.chat.ChatManager.sendUserMessageFromCommand(trimmed, true);
                });
                return false;
            }
            return true;
        });

        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context, tickDelta) -> {
            com.mrtrazan.minecraft.pvphelper.chat.ChatOverlay.render(context);
            com.mrtrazan.minecraft.pvphelper.chat.DebugOverlay.render(context);
        });


        System.out.println("[PvP Helper Client] Dual AI system ready!");
        System.out.println("  - Gemini: PvP Combat Management");
        System.out.println("  - ChatGPT: Inventory & Block Management");
        System.out.println("  - OpenAI API: " + (OpenAIClient.hasApiKey() ? "enabled" : "disabled"));
    }

    private boolean lastJPressed = false;

    private void onClientTick(MinecraftClient client) {
        if (client.player != null && client.world != null) {
            DualAICoordinator.tick(client);
        }


        if (!commandRegistered) {
            try {
                var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
                if (dispatcher != null) {
                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("cai")
                            .executes(src -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    if (MinecraftClient.getInstance().player != null) {
                                        MinecraftClient.getInstance().player.sendMessage(
                                            net.minecraft.text.Text.literal("§e[PvP AI] Commands:\n§7  openai <msg> §f- Ask ChatGPT\n§7  gemini <msg> §f- Ask Gemini\n§7  /cai ask <msg>\n§7  /cai ask status\n§7  /cai ask look\n§7  /spawnAI §for §7/spawnbot §f- Spawn Copper Golem bot\n§7  /removeAI §f- Remove bot\n§7  P key §f- Panic toggle (disable AI)"), false);
                                    }
                                });
                                return 1;
                            })
                            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("ask")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.pvphelper.chat.ChatManager.askStatus();
                                        });
                                        return 1;
                                    })
                                )
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("look")
                                    .executes(ctx -> {
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.pvphelper.chat.ChatManager.askLook();
                                        });
                                        return 1;
                                    })
                                )
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String message = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "message");
                                        MinecraftClient.getInstance().execute(() -> {
                                            com.mrtrazan.minecraft.pvphelper.chat.ChatManager.sendUserMessageFromCommand(message);
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
                                            com.mrtrazan.minecraft.pvphelper.ai.ActionPermissionManager.acceptAction(id);
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
                                            com.mrtrazan.minecraft.pvphelper.ai.ActionPermissionManager.declineAction(id);
                                        });
                                        return 1;
                                    })
                                )
                            )
                    );

                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("spawnAI")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient mc = MinecraftClient.getInstance();
                                    if (mc.player == null) return;

                                    com.mrtrazan.minecraft.pvphelper.ai.CopperBotManager.spawnBot(mc);

                                    com.mrtrazan.minecraft.pvphelper.config.ModConfig cfg =
                                        com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();

                                    boolean hasOpenAI = cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank();
                                    boolean hasGemini = cfg.geminiApiKey != null && !cfg.geminiApiKey.isBlank();

                                    mc.player.sendMessage(
                                        net.minecraft.text.Text.literal(
                                            "§e[PvP AI] API Status:\n"
                                            + "§7  OpenAI key: " + (hasOpenAI ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini key: " + (hasGemini ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini URL: §f" + (cfg.geminiApiUrl != null && !cfg.geminiApiUrl.isBlank() ? cfg.geminiApiUrl : "(default)") + "\n"
                                            + (hasOpenAI || hasGemini ? "§aAI online!" : "§cNo API keys set — use Mod Menu to configure keys!")
                                        ), false);

                                    if (hasOpenAI) {
                                        com.mrtrazan.minecraft.pvphelper.ai.OpenAIClient.testApiKey(
                                            cfg.openAiApiKey, cfg.openAiApiUrl, false
                                        ).thenAccept(ok -> mc.execute(() ->
                                            mc.player.sendMessage(
                                                net.minecraft.text.Text.literal("§7  OpenAI ping: " + (ok ? "§a✓ OK" : "§c✗ FAIL - check log for details")), false)
                                        ));
                                    }
                                    if (hasGemini) {
                                        com.mrtrazan.minecraft.pvphelper.ai.OpenAIClient.testApiKey(
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

                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("spawnbot")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    MinecraftClient mc = MinecraftClient.getInstance();
                                    if (mc.player == null) return;
                                    com.mrtrazan.minecraft.pvphelper.ai.CopperBotManager.spawnBot(mc);
                                    com.mrtrazan.minecraft.pvphelper.config.ModConfig cfg =
                                        com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();
                                    boolean hasOpenAI = cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank();
                                    boolean hasGemini = cfg.geminiApiKey != null && !cfg.geminiApiKey.isBlank();
                                    mc.player.sendMessage(
                                        net.minecraft.text.Text.literal(
                                            "§e[PvP AI] Bot spawned! API:\n"
                                            + "§7  OpenAI: " + (hasOpenAI ? "§aConfigured" : "§cMissing") + "\n"
                                            + "§7  Gemini: " + (hasGemini ? "§aConfigured" : "§cMissing")
                                        ), false);
                                });
                                return 1;
                            })
                    );

                    dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("removeAI")
                            .executes(ctx -> {
                                MinecraftClient.getInstance().execute(() -> {
                                    com.mrtrazan.minecraft.pvphelper.ai.CopperBotManager.removeBot(
                                        MinecraftClient.getInstance());
                                });
                                return 1;
                            })
                    );

                    commandRegistered = true;
                }
            } catch (Throwable t) {
            }
        }

        try {
            if (openChatKey != null && openChatKey.wasPressed()) {
                client.execute(() -> client.setScreen(new com.mrtrazan.minecraft.pvphelper.chat.ChatScreen()));
            }

            boolean toggled = false;
            if (toggleAiKey != null && toggleAiKey.wasPressed()) {
                toggled = true;
            } else if (panicKey != null && panicKey.wasPressed()) {
                toggled = true;
            }

            if (toggled) {
                client.execute(() -> {
                    boolean nowDisabled = ModConfig.getInstance().toggleAiDisabled();
                    if (client.player != null) {
                        client.player.sendMessage(
                            net.minecraft.text.Text.literal(
                                "§e[PvP Helper] AI System is now: " + (nowDisabled ? "§c§lOFF (Disabled)" : "§a§lON (Active)")
                            ), false
                        );
                    }
                    if (nowDisabled) {
                        client.options.leftKey.setPressed(false);
                        client.options.rightKey.setPressed(false);
                        client.options.forwardKey.setPressed(false);
                        client.options.backKey.setPressed(false);
                        client.options.jumpKey.setPressed(false);
                        client.options.attackKey.setPressed(false);
                        client.options.useKey.setPressed(false);
                        DualAICoordinator.activeTarget = null;
                        DualAICoordinator.nextPlannedAction = "NONE";
                    }
                });
            } else {
                var window = client.getWindow();
                boolean j = net.minecraft.client.util.InputUtil.isKeyPressed(window.getHandle(), org.lwjgl.glfw.GLFW.GLFW_KEY_J);
                if (j && !lastJPressed) {
                    client.execute(() -> client.setScreen(new com.mrtrazan.minecraft.pvphelper.chat.ChatScreen()));
                }
                lastJPressed = j;
            }
        } catch (Throwable t) {
        }
    }
}
