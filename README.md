# PvP Helper ⚔️🤖

A client-side Fabric mod (Minecraft 1.21.1) that brings dual AI models (**Gemini 2.0 Flash** & **ChatGPT**) straight into your PvP workflow. It provides real-time combat decision making, smooth aim tracking, automated emergency survival actions, and an optional client-side Copper Golem companion bot.

---

## ✨ Features

- **Dual AI Architecture**:
  - **Gemini 2.0 Flash**: Handles real-time combat tactics, range evaluation, moshpit round-robin targeting, mace smash setups, end crystal placements, and rod combos.
  - **ChatGPT (GPT-4o)**: Manages inventory cleaning, tool prioritisations, and resource strategy.
- **Smooth & Human-Like Aiming**:
  - Uses cubic dampening and target velocity prediction (`target.getVelocity()`) for fluid, non-snapping head movement that tracks targets naturally without jitter.
- **Master AI On/Off Controls**:
  - Toggle all AI features instantly in-game using the **`O`** key (customisable) or through the **Master AI System** button in Mod Settings.
  - Immediately releases all movement keys when turned OFF so your character doesn't keep running into walls.
- **100% Private AI Chat**:
  - Talk directly to your AI using `openai <msg>` or `gemini <msg>` in chat or by pressing **`J`** to open the custom AI screen.
  - Intercepted client-side — server logs and other players never see your prompts.
- **Copper Golem Companion Bot**:
  - Spawn a client-side companion (`/spawnAI` or `/spawnbot`) that follows you around, distracts hostile mobs, and assists in fight scenarios.
- **AFK Auto-Pilot & Survival Safety**:
  - Automatically equips Totems of Undying when health drops below 3.5 hearts (`< 7.0 HP`).
  - Auto-eats food when hungry or low on health during AFK.

---

## ⌨️ Controls & Commands

| Key / Command | Action |
|---|---|
| **`O`** | Toggle Master AI System On/Off |
| **`P`** | Panic Button (Emergency Disable AI) |
| **`J`** | Open AI Chat Screen |
| **`/cai ask <message>`** | Query your configured AI directly |
| **`/cai afk`** | Toggle AFK Auto-Pilot manually |
| **`/spawnAI`** or **`/spawnbot`** | Spawn the client-side Copper Golem bot |
| **`/removeAI`** | Despawn the bot |
| **`openai <message>`** | Chat directly with ChatGPT (client-side only) |
| **`gemini <message>`** | Chat directly with Gemini (client-side only) |

---

## ⚙️ Configuration & API Keys

1. Press **Esc** -> **Mod Menu** -> Select **PvP Helper** -> Click **Settings** (or configure `config/pvp_helper_config.json`).
2. Enter your API key(s):
   - **OpenAI Key**: Starts with `sk-proj-...`
   - **Gemini Key**: Starts with `AIzaSy...` (Native Google or Jio Gemini API key)
3. Use the **Test API Keys** button in the settings screen to verify your keys.
4. Customize your preferred models (default: `gemini-2.0-flash` and `gpt-4o-mini`).

---

## 🛠️ Building from Source

### Requirements
- **JDK 21** or higher
- **Gradle 8.x+** (wrapper included)

### Build Command

**Windows (PowerShell / Command Prompt):**
```cmd
.\gradlew.bat build
```

**Linux / macOS:**
```bash
./gradlew build
```

The compiled mod file will be located in `build/libs/pvp-helper-1.21.1-1.0.0.jar`. Copy it into your `.minecraft/mods` directory alongside **Fabric API** and **Cloth Config**.

---

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
