# PvP Assistant / Codex Assistant

A Minecraft Fabric 1.21.11 client mod that adds an in-game assistant bar with AI-powered PvP and inventory/block assistance.

## Features

- Press `J` or run `/cai` to open the assistant bar.
- Chat with the assistant using `/cai ask <message>`.
- Request local context with `/cai ask status` or `/cai ask look`.
- Trigger client actions from chat with commands like `attack`, `use`, `place`, `break`, `break 40`, and `stop`.
- Supports OpenAI-backed replies when the API key is configured in ModMenu.
- Optionally set a custom OpenAI endpoint in ModMenu.
- Local command handling still works without an OpenAI key, with fallback PvP and inventory behavior.

## Installation

1. Build the mod with Gradle.
2. Place the generated JAR from `build/libs` into your Fabric `mods` folder.
3. Launch Minecraft with Fabric loader and Fabric API.

## Build

```powershell
.\gradlew.bat build
```

The mod JAR is created in `build/libs`.
