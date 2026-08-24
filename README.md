# LLM Wrapper Utility

A zero-install, fully client-side HTML app for OpenAI and Ollama. Runs entirely locally in ur browser with zero backend proxy bullshit. 

[![Download Latest Release](https://img.shields.io/badge/Download-Latest_Release-blue.svg)](https://github.com/YOUR_USER/YOUR_REPO/releases/latest)

# OMNI-DECK v4.44 ֎

**The Ultimate Universal AI Terminal**

OMNI-DECK is a highly advanced, single-file, browser-based interface for interacting with Large Language Models. Built with a stunning, customizable cyberpunk aesthetic, it serves as a unified frontend for local daemons (Ollama), remote APIs (OpenAI-compatible), and fully on-device browser-native inference engines (WebGPU/WebLLM & Chrome Gemini Nano). 

Operating entirely offline with zero build steps or external backend dependencies (beyond the LLM engines themselves), OMNI-DECK stores everything locally in your browser using IndexedDB.

---

## 🚀 Key Features

### 🧠 Multi-Engine Support
Switch seamlessly between different AI execution environments on the fly:
*   **Ollama Native:** Full support for Ollama's `/api/chat` endpoint, including model pulling and advanced parameters (num_ctx, num_gpu, etc.).
*   **OpenAI Compatible:** Connect to any remote or local vLLM, TabbyAPI, or standard OpenAI endpoint via `/v1/chat/completions`.
*   **Browser-Native WebGPU (WebLLM):** Run models entirely within the browser tab using your local GPU hardware. No daemon required. Weights are fetched once and cached in IndexedDB.
*   **Chrome Built-In (Gemini Nano):** Utilize Chrome's experimental Prompt API to run Gemini Nano entirely on-device natively.

### 🌳 Branching Chat Trees & Non-Linear History
Never lose a good prompt again. OMNI-DECK treats conversations like Git branches.
*   **Edit & Fork:** Edit any past user message or AI response to instantly fork a new conversational branch.
*   **Branch Navigation:** A built-in `< [i/n] >` HUD allows you to seamlessly traverse sibling branches at any fork point.
*   **Regenerate:** Request a new response without deleting the old one; the new response simply becomes a parallel branch.

### 🌓 Dual-Pane Interrogation (A/B Testing)
*   Activate **[ DUAL PANE ]** mode to split the terminal into "Alpha" and "Beta" sectors.
*   Route a single prompt to two entirely different models (or the same model with different parameter presets) simultaneously.
*   Compare logic, speed, and output quality side-by-side.

### 📚 Dynamic Knowledge Base & RAG
*   **Document Injection:** Drag and drop or select `PDF`, `DOCX`, `TXT`, `MD`, `CSV`, or `JSON` files. OMNI-DECK parses them locally (via pdf.js and mammoth.js) and injects them into the context window.
*   **URL Scraping:** Paste a URL to have the terminal fetch, strip HTML boilerplate, and inject the core article text into your session memory.

### 📖 World Info (Lorebook)
A powerful, Mikupad-style dynamic memory injection system.
*   Create memory entries triggered by specific comma-separated Regex keys.
*   When you or the AI mention a trigger word (e.g., "dragon"), the associated lore is instantly wrapped in your custom global prefix/suffix and silently injected into the system prompt for that turn.

### 🎛️ Advanced Parameter Control
Beyond standard Temperature and Top-P, OMNI-DECK exposes advanced Llama.cpp samplers (when supported by the backend):
*   **Standard:** Min_P, Mirostat, Context Length, GPU Layers, Top_K, Repeat Penalty.
*   **Advanced:** DRY Multiplier/Base/Allowed/Penalty (for repetition mitigation), XTC Threshold & Probability, Dynatemp Range & Exponent.

### 📊 Tactical Telemetry Rack
Live dashboard monitoring your session and system limits:
*   **Live Token Budget:** Visual gauge tracking context usage against your defined limit.
*   **TPS Monitor:** Real-time Tokens-Per-Second generation speed tracking.
*   **System Resources:** CPU Core count and JS Heap memory estimation.
*   **Latency Radar:** Live ping tracker with a visual sweeping circuit canvas.

### 💾 Robust Local Storage & Portability
*   **IndexedDB Backing:** Every keystroke, branch, and configuration is saved asynchronously to your browser's IndexedDB. 
*   **Session History:** Restore previous chats instantly from the `[ HISTORY ]` panel.
*   **Manifest Backup:** Export your entire deck—settings, API keys, persona library, lorebook, documents, and chat history—into a single `.json` config manifest. Drop the manifest back into any fresh OMNI-DECK instance to restore your exact environment instantly.

### 🎨 Customization & Accessibility
*   **Themes:** Cycle between `NEON` (Cyberpunk), `DARK` (Engineering/Slate), and `MODERN` (Clean/Light SaaS) themes.
*   **CRT Scanlines:** Toggle the retro visual overlay on or off.
*   **Text-to-Speech (TTS):** Built-in browser vocalization with adjustable Voice Profile, Rate, Pitch, and Gain.
*   **Markdown & Math:** Full support for rendering code blocks (Prism.js syntax highlighting) and LaTeX equations (MathJax) dynamically as the text streams.

---

## 🛠️ Setup & Installation

OMNI-DECK is a **Zero-Install** application. Because everything is contained within a single `.html` file, deployment is trivial.

### Basic Execution
1. Download `index.html`.
2. Double-click to open it in any modern web browser (Chrome/Edge/Brave highly recommended for WebGPU features).

### Configuring a Local Backend (Ollama)
If you wish to use Ollama natively:
1. Ensure Ollama is installed and running on your machine.
2. **CORS Requirement:** Ollama blocks browser requests by default. You MUST start Ollama with CORS enabled.
   * **Windows/Linux (CLI):** `OLLAMA_ORIGINS="*" ollama serve`
   * **macOS:** Launch via terminal with the above environment variable.
3. In OMNI-DECK, open the **PARAMETERS** module (right sidebar).
4. Set Endpoint Protocol to `Ollama Native`, Base URL to `http://localhost:11434`, and click **SAVE CONFIGURATION**.

### Configuring WebGPU (WebLLM)
To run models entirely in your browser without Ollama:
1. Ensure you are using a Chromium-based browser with Hardware Acceleration enabled.
2. Select **Browser-Native WebGPU (WebLLM)** in the API Protocol Format dropdown and Save.
3. The model list will populate with optimized WebLLM models. Selecting one and sending a prompt will initiate the model download directly into your browser's cache.

### Configuring Chrome Gemini Nano
1. Open Google Chrome (v127+).
2. Navigate to `chrome://flags/#prompt-api-for-gemini-nano` and enable it.
3. Restart Chrome.
4. Select **Chrome Built-In (Gemini Nano)** in OMNI-DECK.

---

## 🕹️ Interface & Usage Guide

*   **Left Panel (Telemetry):** Click the mobile toggle icon in the top left to hide/show. Monitors your system's heartbeat.
*   **Center Panel (Chat Stream):** Your main interaction zone. Use the `[ COPY ]` and `[ EDIT ]` buttons hovering on message bubbles.
*   **Right Panel (Parameters):** Contains API configuration, LLM sliders, utility buttons (Regenerate, Export, Diagnostics), and appearance toggles.
*   **Top Navigation Bar:**
    *   **[ DUAL PANE ]**: Splits the chat vertically for A/B testing.
    *   **[ HISTORY ]**: Browse and restore past sessions.
    *   **[ KNOWLEDGE BASE ]**: Upload documents or scrape URLs.
    *   **[ VISUALIZER ]**: View the fluid quantum avatar that reacts to the AI's state (Idle, Thinking, Streaming).
    *   **[ AUDIO ]**: Configure TTS vocoder settings.
    *   **[ SYSTEM PROMPT ]**: Configure your root instructions, Persona library, and Author's Note depth injection.

### Emergency / Factory Reset
If the system state becomes corrupted or you need to instantly wipe all sensitive data:
*   Navigate to the bottom of the Parameters panel and click **[ CLEAR ALL DATA ]**.
*   **Shortcut:** Press the `ESC` key rapidly 3 times to trigger a total environment purge (clears LocalStorage and IndexedDB entirely).

---

## 🔒 Security & Privacy (Offline Mode)
OMNI-DECK respects your privacy.
*   **Airgap / Offline Mode:** Click `[ OFFLINE MODE ]` in the Utilities panel to activate a strict network block. In this mode, OMNI-DECK will actively intercept and block any HTTP requests attempting to reach non-local IP addresses, ensuring your data never leaves your machine.
*   All API keys entered are stored exclusively in your browser's local storage and are never transmitted anywhere except your designated Base URL.

---

## 📄 License
This interface is provided as-is, intended for local development, research, and high-efficiency text generation. Ensure you comply with the licenses of the underlying models you choose to load through this terminal.
<h1 align="center">OMNI-DECK ֎</h1>
<p align="center"><strong>The Ultimate Single-File Universal AI Terminal</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/Release-v4.44-blue" alt="Version">
  <img src="https://img.shields.io/badge/Size-< 1MB-success" alt="Size">
  <img src="https://img.shields.io/badge/Dependencies-Zero-red" alt="Dependencies">
</p>

OMNI-DECK is a highly advanced, zero-install, browser-based interface for Large Language Models. It serves as a unified frontend for local daemons (Ollama), remote APIs (OpenAI-compatible), and fully on-device browser-native inference engines (WebGPU/WebLLM & Chrome Gemini Nano). 

Run elite-tier AI entirely offline. No build steps. No external backend dependencies. Everything stores locally in your browser via IndexedDB.

---

## 🚀 Core Capabilities

### 🧠 Agnostic Multi-Engine Inference
Switch seamlessly between execution environments on the fly:
- **Browser-Native WebGPU (WebLLM):** Run models entirely within the browser tab using local GPU hardware. Weights fetch once and cache in IndexedDB.
- **Chrome Built-In (Gemini Nano):** Utilize Chrome's experimental Prompt API to run Gemini Nano natively.
- **Ollama Native:** Full support for Ollama's `/api/chat` endpoint, model pulling, and advanced parameters.
- **OpenAI Compatible:** Connect to any remote or local vLLM, TabbyAPI, or standard OpenAI endpoint via `/v1/chat/completions`.

### 🌳 Branching Chat Trees (Git for Prompts)
Never lose a perfect prompt. OMNI-DECK treats conversations like version control branches.
- **Edit & Fork:** Edit any past message to instantly fork a new conversational branch.
- **Branch Navigation:** Traverse sibling branches at any fork point with the intuitive HUD.
- **Regenerate-in-Place:** Request a new response without deleting the old one—it simply becomes a parallel branch.

### 🌓 Dual-Pane Interrogation
Activate **[ DUAL PANE ]** mode to split the terminal. Route a single prompt to two different models (or the same model with different parameter presets) simultaneously to A/B test logic, speed, and output quality side-by-side.

### 📚 Local RAG & Document Injection
Drag and drop `PDF`, `DOCX`, `TXT`, `MD`, `CSV`, or `JSON` files. OMNI-DECK parses them locally and injects them into the context window. Alternatively, paste a URL to scrape and inject article text directly into your session memory.

### 📖 Dynamic Lorebook (Regex Memory)
Create memory entries triggered by specific comma-separated Regex keys. When a trigger word is mentioned, the associated lore is instantly wrapped in your custom global prefix/suffix and silently injected into the system prompt for that turn.

### 🎛️ Advanced Sampler Control
Beyond standard Temperature and Top-P, OMNI-DECK exposes advanced Llama.cpp samplers:
- **Standard:** Min_P, Mirostat, Context Length, GPU Layers, Top_K, Repeat Penalty.
- **Advanced Mitigation:** DRY Multiplier/Base/Allowed/Penalty, XTC Threshold & Probability, Dynatemp Range & Exponent.

### 📊 Tactical Telemetry Rack
Monitor your session heartbeat in real-time with visual gauges for Token Budget, TPS generation speed, CPU Core count, JS Heap memory estimation, and a live Latency Radar.

### 💾 Immutable Local Storage & Portability
Every keystroke is saved asynchronously to IndexedDB. Browse and restore past sessions instantly. Export your entire environment—settings, API keys, persona library, lorebook, documents, and chat history—into a single `.json` config manifest. Drop the manifest into any fresh OMNI-DECK instance to restore your setup perfectly.

---

## 🛠️ Zero-Friction Setup

OMNI-DECK is a **Zero-Install** application. 

1. Download `index.html`.
2. Double-click to open it in any modern web browser (Chrome/Edge recommended for WebGPU).
3. Select your engine and start typing.

*(Optional)* To use a local Ollama daemon, ensure you start Ollama with CORS enabled (`OLLAMA_ORIGINS="*" ollama serve`).

---

## 🔒 Security & Privacy (Airgap Mode)
OMNI-DECK respects your privacy. Activate **[ OFFLINE MODE ]** to enforce a strict network block. OMNI-DECK will actively intercept and block any HTTP requests attempting to reach non-local IP addresses, ensuring your data never leaves your hardware. All API keys are stored exclusively in local storage.

</details>

<details>
<summary><strong>📡 View Telemetry & Diagnostics</strong></summary>

Monitor session constraints in real-time:
- **Token Budget:** Visual gauge tracking context saturation.
- **Throughput:** Real-time Tokens-Per-Second (TPS) generation speed tracking.
- **System Overhead:** CPU Core count and JS Heap memory estimation.
- **Latency Radar:** Live ping tracker with visual sweeping circuit canvas.

</details>

## 🚀 Deployment Protocol

OMNI-DECK requires zero build steps and operates entirely offline.

1. Download the `index.html` binary release.
2. Execute in a modern web browser (WebGPU capabilities require Chromium-based browsers).
3. Select your inference engine and initialize the session.

*(Optional)* To bridge with a local Ollama daemon, configure CORS at startup:
```bash

<div align="center">

# OMNI-DECK // ENTERPRISE AI TERMINAL
**Zero-Dependency. Local-First. Universal LLM Frontend.**

[![Release](https://img.shields.io/badge/Release-v4.44-blue.svg)]()
[![Footprint](https://img.shields.io/badge/Footprint-<1MB-success.svg)]()
[![Dependencies](https://img.shields.io/badge/Dependencies-Zero-red.svg)]()
[![Platform](https://img.shields.io/badge/Platform-Browser-orange.svg)]()

</div>

---

## 📌 Executive Summary
OMNI-DECK is an enterprise-grade, browser-based interface for Large Language Models. Engineered as a single, zero-install HTML file, it provides a unified frontend for local daemons (Ollama), remote APIs, and fully on-device browser-native inference engines (WebGPU/WebLLM & Chrome Gemini Nano).

## 🏗️ Architecture & Capabilities

| Feature | Technical Implementation |
| :--- | :--- |
| **Engine Agnostic** | Seamless switching between WebLLM, Gemini Nano, Ollama, and OpenAI-compatible endpoints. |
| **Non-Linear History** | Git-style branching for conversations. Edit, fork, and navigate sibling branches via HUD. |
| **Dual-Pane Testing** | Route prompts to two discrete models simultaneously to benchmark logic and latency. |
| **Local RAG Pipeline** | Drag-and-drop parsing for PDF, DOCX, TXT, MD, CSV, and JSON directly into context. |
| **State Persistence** | Immutable, asynchronous logging to IndexedDB. Exportable JSON configuration manifests. |

<details>
<summary><strong>⚙️ View Advanced Sampler Configurations</strong></summary>

OMNI-DECK exposes advanced Llama.cpp samplers for maximum generation control:
- **Standard:** Min_P, Mirostat, Context Length, GPU Layers, Top_K, Repeat Penalty.
- **Advanced Mitigation:** DRY Multiplier/Base/Allowed/Penalty, XTC Threshold & Probability, Dynatemp Range & Exponent.

</details>

<details>
<summary><strong>📡 View Telemetry & Diagnostics</strong></summary>

Monitor session constraints in real-time:
- **Token Budget:** Visual gauge tracking context saturation.
- **Throughput:** Real-time Tokens-Per-Second (TPS) generation speed tracking.
- **System Overhead:** CPU Core count and JS Heap memory estimation.
- **Latency Radar:** Live ping tracker with visual sweeping circuit canvas.

</details>

## 🚀 Deployment Protocol

OMNI-DECK requires zero build steps and operates entirely offline.

1. Download the `index.html` binary release.
2. Execute in a modern web browser (WebGPU capabilities require Chromium-based browsers).
3. Select your inference engine and initialize the session.

*(Optional)* To bridge with a local Ollama daemon, configure CORS at startup:
```bash
OLLAMA_ORIGINS="*" ollama serve
```

## 🔐 Security & Airgap Protocol
**Strict Offline Mode:** Activating Airgap Mode triggers HTTP request interception, blocking all outbound traffic to non-local IPs. Data persistence, RAG indexing, and API key storage are strictly confined to local IndexedDB and LocalStorage environments.

OLLAMA_ORIGINS="*" ollama serve
```

## 🔐 Security & Airgap Protocol
**Strict Offline Mode:** Activating Airgap Mode triggers HTTP request interception, blocking all outbound traffic to non-local IPs. Data persistence, RAG indexing, and API key storage are strictly confined to local IndexedDB and LocalStorage environments.
