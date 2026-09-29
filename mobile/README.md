# OmniDeck Mobile

The phone-side command center for OMNI-DECK. It finds the AI running on your PC (Ollama) over Wi-Fi, streams its replies to you live, and lets you talk to it, manage its models and control the PC through LaunchBridge.

It uses the same three looks as the web app: **Cyber** (the "Mainframe HUD"), **Light** and **Dark**. **System** is the default and follows your phone.

<!-- SCREENSHOTS -->

**Download:** [`dist/OmniDeck-Mobile.apk`](dist/OmniDeck-Mobile.apk). It needs Android 6.0 or newer. Version 2.0 installs over 1.0.

## Install

1. Open the APK on your phone. If Android asks, allow your browser or file manager to *install unknown apps*.
2. Play Protect may say it doesn't recognize the app. Choose **More details → Install anyway**, because the app isn't on the Play Store.
3. Open **OmniDeck** while the phone is on the same Wi-Fi as your PC.

## One-time PC setup

By default Ollama only answers the PC itself. To let the phone reach it:

1. **Allow network connections.** Set the environment variable `OLLAMA_HOST=0.0.0.0`, then quit and restart Ollama.
   - Windows: run `setx OLLAMA_HOST 0.0.0.0`, then right-click the Ollama tray icon → Quit, and start Ollama again.
   - macOS: run `launchctl setenv OLLAMA_HOST "0.0.0.0"`, then restart the Ollama app.
   - Linux (systemd): run `systemctl edit ollama`, add `Environment="OLLAMA_HOST=0.0.0.0"` under `[Service]`, then `systemctl restart ollama`.
2. **Open the firewall** for TCP port 11434 on private networks. On Windows, from an admin prompt:
   `netsh advfirewall firewall add rule name="Ollama LAN" dir=in action=allow protocol=TCP localport=11434 profile=private`

The app finds the PC by itself. If your network blocks discovery, open **Command → Enter address** (or tap the status pill) and type the PC's IP, e.g. `192.168.1.20`.

> Ollama's API has no password. Once it listens on the network, anyone on the same Wi-Fi can use it, so only do this on networks you trust.

**PC control (optional).** The PC tab talks to OMNI-DECK's LaunchBridge on port 8765 with the same API the web app uses.
- LaunchBridge listens only on `127.0.0.1` today, so the PC tab explains what's needed until it's reachable from the network.
- The bridge can have its own address (**Settings → PC bridge**), so PC control works even before Ollama is reachable.
- Once it's reachable, tap **Pair** once.

## What's inside

### OMNI acts on your PC (tool calling)
- **What it does:** with a tool-capable model (for example `qwen3`, `llama3.1`/`3.2` or `mistral`) and the PC bridge paired, just ask: *"set the volume to 40"*, *"what's my CPU at?"*, *"open Spotify"*, *"take a screenshot and tell me what's on screen"*. OMNI calls the PC's LaunchBridge tools and then answers. Each reply shows an action log of what it did.
- **Safety:**
  - Reading information just happens.
  - Anything that changes the PC asks you first. You can allow it for the rest of the chat, or turn the questions off in Settings.
  - Destructive actions (shut down, restart…) always ask.
  - In hands-free mode you can answer "yes" or "no" by voice.
- **Status:** `/tools` explains what's available and why. `/tools off` turns it off.

### Command: mission control
- **AI core:** an arc-reactor instrument that shows the link state (idle, scanning, thinking, streaming, speaking, offline). Tap it to chat; hold it to talk.
- **Status line:** the link, Ollama version and address, the active model and mode chips (tap to switch), and live generation speed.
- **Quick actions:** talk, new chat, summarize, read aloud, warm or unload the model, benchmark, models, scan the network, PC, history, diagnostics.
- **Telemetry:** latency, throughput and first-token traces, a context-window gauge, loaded models with their VRAM/CPU split, and session stats.
- **PC vitals** (when paired) and a live **system log** you can copy.
- **When the AI can't be found:** step-by-step guidance with *Scan again* and *Enter address*.

### Comms: chat
- **Streaming:** replies stream token by token with Markdown, code blocks with a language label and **Copy**, links, tables and a stop button. Reasoning from thinking models appears in a collapsible block.
- **Voice:** voice input through the mic button and read-aloud (the phone speaks replies sentence by sentence as they stream).
- **Images:** attach images to ask vision models about them.
- **Messages:** long-press any message to copy it, read it aloud, share it, edit and resend, regenerate or delete.
- **History:** search, open, rename and delete saved chats. Share text from any app to OmniDeck to ask about it.
- **Commands:** every OMNI-DECK slash command, with suggestions as you type `/`.

### Models: the model bay
- **Installed models:** one card per model with family, size, quantization and capability chips (vision, thinking, tools, embedding), plus its loaded state with VRAM and context.
- **Actions:** use, load or unload, set as the deep-thinking model, details (context length, license, parameters) and delete (with confirmation).
- **Pull:** download new models onto the PC, with suggestions and live progress, speed, ETA and cancel.

### PC: remote control through LaunchBridge
- **Link status and pairing,** with clear guidance when the bridge isn't reachable.
- **Live vitals:** a CPU trace, memory, disk, battery and power, host, OS and uptime. They refresh every 5 s while the tab is open.
- **Controls:**
  - Volume slider with presets.
  - Screen capture with a full-screen preview.
  - The PC clipboard, which you can copy to the phone or send to chat.
- **App launcher** with search and recents; it asks before opening anything.
- **Tool runner** for every desktop tool the bridge offers.

### Also
- **Voice:**
  - Speak a question and the answer is read back.
  - **Hands-free** mode listens again after each spoken answer.
  - **Stop speaking** is always one tap away.
- **Background notifications** tell you when a reply or model download finishes, or a timer rings, even if the app is closed.
- **PC power:**
  - `/wol` wakes the PC with Wake-on-LAN (the MAC is learned automatically once paired).
  - `/lock` locks it.
  - The PC tab adds sleep, restart and shut down when the bridge offers them.
- **Remote AI:** use `https://` addresses and an **API key** to reach your AI through a reverse proxy.
- **Share into OmniDeck:** share text, text files or photos from any app into OmniDeck to ask about them.
- **Launcher shortcuts** (long-press the icon): *Talk to OMNI*, *New chat*, *PC screenshot*, *Command center*.
- **Chats:** each chat remembers its model. Failed replies explain what went wrong and offer the fix (retry, pull the model, switch to a vision model, lower the context size). A message typed while offline is sent when the link returns.

### Settings
- **Appearance:** Cyber, Light, Dark or System, with live previews. Also reduce motion, HUD effects (grid and scan line) and haptics.
- **Connection:** the address, auto-detect and scan.
- **AI model and mode, plus performance:** context window and CPU threads, matched to OMNI-DECK so the PC doesn't reload the model.
- **Generation:** temperature, top-p and maximum reply length.
- **Persona:** the system prompt and remembered facts.
- **Voice:** read aloud, speech rate and a test button.
- **PC bridge:** port, token and pairing.
- **Privacy:** incognito, export, and clear history.

## Commands

Type `/` to see suggestions. Start a message with `//` to send text that begins with a slash.

| Chat | |
|---|---|
| `/help` | every command |
| `/reset` (`/new`, `/clear`) | new conversation |
| `/stop`, `/regen` | stop the reply, regenerate the last one |
| `/system <prompt \| clear>` | system prompt / persona |
| `/summarize`, `/compact` | summarize; shrink history into a summary to free context |
| `/history [name]`, `/export`, `/rename <title>` | saved chats, share as text, rename |
| `/remember`, `/facts`, `/forget` | facts sent with every message |
| `/incognito on\|off` | don't save chats |

| Control the AI | |
|---|---|
| `/model [name]`, `/models`, `/ps` | switch / list installed / list loaded models |
| `/warm`, `/unload [name]` | load a model into memory / free it |
| `/pull <name \| stop>` | download a model onto the PC, with progress |
| `/deep [model]`, `/fast`, `/auto` | thinking on (optionally a separate deep model), off, or automatic |
| `/bench` | measure load, prompt and generation speed |

| PC (LaunchBridge) | |
|---|---|
| `/open <app>`, `/vol [0-100]`, `/sys` | open an app, volume, CPU/RAM/disk/battery |
| `/shot`, `/pcclip`, `/pair`, `/desk` | screenshot into the chat, paste the PC clipboard, pair, bridge status |
| `/wol` (`/wakepc`), `/lock` | wake the PC (Wake-on-LAN), lock it |
| `/tools [on\|off]` | whether OMNI can use the PC's tools, and which |

| App | |
|---|---|
| `/server [ip[:port] \| auto]`, `/scan` | set or show the address, find every AI server on the network |
| `/appearance cyber\|light\|dark\|system` (`/theme`) | switch the look |
| `/voice` (`/talk`), `/mute` | speak a message, toggle reading replies aloud |
| `/settings`, `/debug`, `/timer 5m tea`, `/clip` | settings, diagnostics, in-app timer, paste the phone's clipboard |

`/web`, `/investigate`, `/task` and the other PC-app-only commands reply that they run in OMNI-DECK on the PC.

## Build from source

Everything builds on a plain Linux box. You don't need Android Studio or Gradle for the APK itself:

```bash
./build.sh   # installs aapt/dx/zipalign/apksigner from apt if missing → dist/OmniDeck-Mobile.apk
./test.sh    # JVM tests: Ollama client, streaming, LAN scan, bridge, commands, markdown, telemetry, vitals
cd uitest && gradle test --offline   # Robolectric: every screen on Android 6.0 and 14, plus screenshots in build/screens
```

The signing key is created in `.keystore/` on the first build and is git-ignored. Keep it if you want future builds to install over the current app; a different key means uninstalling first.

Layout:

- **`src/…/core/`:** pure Java with no Android dependencies, unit-tested on the JVM. It holds Ollama, discovery, LaunchBridge, commands, Markdown, storage, telemetry and vitals.
- **`src/…/Engine.java`:** connection state, health checks, streaming, model and PC control. It lives for the whole process, so replies survive rotation and theme changes.
- **`src/…/MainActivity.java`:** the shell, which holds the top bar, tabs, settings overlay and boot sequence. `screens/` holds one class per tab.
- **`ui/`:** the theme tokens (`Theme`), the UI kit (`Ui`, `Panel`, `Widgets`) and instruments (`CoreView`, gauges and traces). Everything is built in code with no AndroidX.
- **`docs/design-spec.md`:** the design tokens taken from the web app's themes.
- **`test/`:** JVM tests plus `MockOllama` / `MockBridge`, which mirror the real APIs.
- **`uitest/`:** the Robolectric suite. `src/stubs` holds a minimal `androidx.test` stand-in, because Google's Maven wasn't reachable from the build machine.

## Credits

The bundled fonts are converted from Orbitron (The Orbitron Project Authors), Share Tech Mono (Carrois Type Design) and Inter (The Inter Project Authors), all under the SIL Open Font License 1.1. See [`assets/fonts/OFL.txt`](assets/fonts/OFL.txt).
