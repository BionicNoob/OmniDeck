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

