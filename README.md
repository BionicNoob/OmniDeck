# LLM Wrapper Utility

A zero-install, fully client-side HTML app for OpenAI and Ollama. Runs entirely locally in ur browser with zero backend proxy bullshit. 

[![Download Latest Release](https://img.shields.io/badge/Download-Latest_Release-blue.svg)](https://github.com/YOUR_USER/YOUR_REPO/releases/latest)

## Architecture
This tool uses a Bring Your Own Key (BYOK) architecture [2]. API keys are saved strictly to ur browser's `localStorage` and sent directly to the respective LLM endpoints. 

## Setup and Installation
1. Click the Download badge above to grab the latest `.zip` release.
2. Extract the archive.
3. Double-click `index.html` to open it locally in any modern browser. No server required.

## Use & Configuration

### OpenAI
1. Click the **Settings** icon in the UI.
2. Paste ur OpenAI API key. 
3. The app routes fetch requests directly to `api.openai.com` [3].

### Ollama (Local)
Because browsers block external origin requests to `localhost` by default, u must override CORS rules before launching the Ollama daemon [4].

**Mac/Linux Terminal:**
```bash
launchctl setenv OLLAMA_ORIGINS "*" && pkill Ollama; open -a Ollama
