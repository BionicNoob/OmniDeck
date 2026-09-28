# OMNI-DECK Mobile — design spec (v2)

Source of truth for every screen. Values come from the **updated** `OllamaChat.html`
(the web app on the PC). The web file is read-only reference and is never edited.

## 1. Brief

OMNI-DECK Mobile is the phone-side **command center for a personal AI**, in the spirit of
Tony Stark's JARVIS. It controls the AI (Ollama on the PC) and the PC itself (LaunchBridge).

- It must look **real and professional**, like avionics or a mission-control console. It must never look toy-like or like a game UI.
  - Restraint beats decoration. Use thin hairlines, precise micro-caps labels, monospaced telemetry, calm motion and generous spacing.
  - No emoji. Avoid cartoon glows, rainbow colors and big drop shadows.
- It **matches the web app's three looks** (below) and adds its own interpretation on top.
  - Examples: an arc-reactor "AI core", live telemetry traces, a system log.
- Every screen must work in all three themes, fit 360–430dp-wide phones, and handle empty, offline, loading and error states gracefully.

## 2. Themes (tokens live in `src/com/omnideck/mobile/ui/Theme.java`)

Settings offers `system | cyber | light | dark`. **System** (the default) follows the phone:
light → Light, dark → Dark. Always read colors from `Theme t` fields and never hard-code them
(except where noted).

### Cyber: "MAINFRAME HUD" (`<style id="cyber-hud-overhaul">`)

- **Backdrop:** a radial gradient `#0a1622 → #050a12 → #030509` (`ui/Backdrop`).
  - When *HUD effects* is on, it also shows a 22dp hairline grid at `rgba(56,189,248,.028)` and a soft cyan bloom from the top.
- **Surfaces:**
  - glass `--hud-surface rgba(9,16,26,.86)` → `t.surface`
  - deep `rgba(5,9,15,.94)` → `t.surface2`
  - header cap `rgba(22,30,42,.72)` → `t.cap`
  - input slots `rgba(3,7,12,.72)` → `t.input`
- **Lines:**
  - card edge `rgba(56,189,248,.22)` → `t.edge`
  - strong `rgba(56,189,248,.38)` → `t.edgeStrong`
  - steel hairline `rgba(120,150,178,.18)` → `t.hair`
  - soft `.09` → `t.hairSoft`
- **Ink:**
  - strong `#E2EFFA` → `t.ink`
  - steel `#8FA9C2` → `t.dim`
  - micro-caps `#7FA9C6` → `t.label`
  - faint `#6C8CA6` → `t.faint`
- **Accent:** sky-cyan `#38BDF8` → `t.accent` / `t.data`.
  - Danger `#FF3B5C`.
  - Amber `#FFB700` = the "engaged / switched on" state (`t.engaged`).
- **Radius** 12dp.
- **Decoration** (subtle, pointer-free):
  - Corner brackets: 10dp arms, 1.2dp, cyan .55, inset ~5dp, on the top-left and bottom-right corners.
  - Caps: a full-bleed header band with a 1px `rgba(56,189,248,.16)` rule under it. Titles are **Orbitron micro-caps**, ~9.5–10sp, letter-spacing .12–.22, in `t.inkStrong` or `t.label`.
  - A live status dot (5dp, accent, pulsing opacity 1→.25 over 2.6s).
  - An optional short dashed "data rail" after cap titles.
  - A slow scan line (7s) over the app.
- **Buttons** (web "quiet control strip"):
  - Rest: flat tint `rgba(120,160,200,.06)`, 1px steel hairline, ink-strong text.
  - Hover/active: accent tint `.16–.26` with accent edge.
  - Destructive: red. Engaged toggles: amber.
  - Primary actions may be a solid accent fill with navy ink `t.onAccent`.
- **Type:**
  - Orbitron (`t.display`, `t.labelFace`) for titles and micro-caps only.
  - Inter (`t.body*`) for reading text.
  - Share Tech Mono (`t.mono`) for numbers, telemetry and code.
- **Chat:** "Cyber chat in Modern clothes".
  - User bubble `#2C465E`, ink `#EAF2F8`, radius 16/16/4/16.
  - AI replies have **no bubble**: text sits on the surface. The app adds a faint 2dp cyan signal line on the left.
  - Composer `rgba(2,2,6,.92)`, 1.5dp edge `rgba(110,110,146,.45)`, radius 14.
  - System notices: amber `.12` fill with a dashed amber `.55` edge.

### Light: "modern"

- **Page:** `#F5F5F5`. The backdrop gradient runs `#FFFFFF → #F5F5F5 → #EDEDED`.
- **Surfaces:**
  - Cards `#FFFFFF`, 1dp edge `#D0D7DE`, radius 12. They get a soft two-layer shadow (elevation ~1.5dp).
  - Header cap `#EBEDF0` with a `#D0D7DE` rule.
  - Cap title `#57606A` (`t.label`), Inter semibold ~11sp. Labels may be upper-case with small tracking.
- **Ink:** `#171717`, dim `#666666`.
- **Accent:** slate blue `#4A6D8C` (hover `#3D5B76`), with white text on the accent.
- **Status inks:** ok `#1E7A43`, warn `#8A5E0B`, danger `#B8362E`.
- **Top bar:** white with a 1.5dp `rgba(23,23,23,.22)` rule and a small shadow.
- **Chat:**
  - User bubble = accent with white ink. AI replies have no bubble.
  - Composer `rgba(255,255,255,.85)` with a 1.5dp `rgba(23,23,23,.22)` edge, radius 14.
- **Type:** Inter everywhere; mono only for numbers and code.
- **No** grid, brackets, scan line or glow.

### Dark: modern layout on the dark layer

- **Page:** `#0F1216`. The backdrop runs `#161A1F → #12161B → #0F1216`.
- **Surfaces:** cards `#1C2128` with edge `#3C4450`, cap `#20262E`, title `#B7C0CC`.
- **Ink:** `#E6EAF0`, dim `#8F99A8`.
- **Accent is monochrome:** `#E6EAF0`, with a soft `#232A33` fill and ink `#0F1216` on the accent. Use **`t.data` = `#7EA6CC`** for charts, meters and anything that needs a hue.
- **Status inks:** ok `#5CCB8C`, warn `#E3B253`, danger `#FF8A80`.
- **Chat:** user bubble `#2B323D` with ink `#E6EAF0`. AI replies have no bubble.
- **No** HUD decoration.

## 3. Building blocks (use these; don't re-invent)

- **`ui/Ui`** (`a.ui()`):
  - Layout: `vbox/hbox`, `wrap/fillW/weight`, `space/flexSpace/divider`, `scrollColumn(holder, padH, padV)`.
  - Text: `text(s, sp, color, face)`, `title`, `label` (micro-caps), `body`, `dim`, `readout` (mono).
  - Surfaces: `panel(brackets, capHeightDp)`, `card()`, `capCard(title, rightView)` + `cardBody()`, `rounded(fill, stroke, radiusDp)`.
  - Controls:
    - `button(label, icon, Ui.PRIMARY|SECONDARY|GHOST|DANGER, listener)`
    - `iconButton(kind, contentDescription, color, listener)`
    - `chip(text, color)`, `toggle(on, onChange)`
    - `settingRow(title, subtitle, control)`
    - `field(value, hint, inputType)`, `numberField(value, hint)`
  - Dialogs: `pick(title, rows, neutral, onNeutral)` (use `Ui.Row` list menus, since PopupMenu can't be used), `confirm(title, msg, yes, onYes)`, `prompt(title, hint, value, inputType, TextResult)`.
  - Feedback: `toast(s)`, `tick(view)` (haptic when enabled), `dp(v)`.
- **`ui/Panel`** is a Drawable builder: `fill`, `edge(color, px)`, `radius/radii`, `grid(step, color)`, `bloom(color)`, `brackets(len, width, color)` + `bracketInset(px)` + `bracketsAll()`, `cap(height, fill, line)`, `highlight(color)`, `dash(on, off)`.
- **`ui/Widgets`:**
  - `Sparkline(ctx, color, baseline)`: `setData(double[])`, `setFloor`.
  - `Gauge(ctx, track, value)`: a 240° arc, `setFraction(f, animate)`.
  - `Meter(ctx, track, bar)`: `setFraction`, `setIndeterminate`.
  - `Toggle`, `StatusDot` (`setColor`, `setPulsing`), `ScanLine`.
- **`ui/IconDrawable(kind, color, color2, sizePx)`** has ~46 stroked icons (see constants): NAV_*, SEND, STOP, MIC, IMAGE, SPEAKER(_OFF), HISTORY, PLUS, SEARCH, SCAN, BOLT, POWER, DOWNLOAD, TRASH, CAMERA, CLIPBOARD, APPS, CHECK, CLOSE, COPY, SHARE, INFO, ACTIVITY, TERMINAL, LINK, DOC, CPU, EDIT, BACK, REFRESH, PLAY, WIFI, BRAIN, STAR, LOGO…
- **`ui/Fonts`:** `title`, `titleMedium`, `mono`, `inter(ctx, 400|500|600|700)`.
- **`screens/Screen`:**
  - Fields `a` (MainActivity), `e` (Engine), `ui`, `t`.
  - Lifecycle: `build()` is called once and lazily; `onShow/onHide` run when the tab becomes visible or hidden.
  - Engine events are forwarded to built screens: `onStateChanged`, `onTelemetry`, `onLog`, `onPull`, `onBusyChanged`, `onMessage*`, `onConversationReplaced`, `onActivityStart/Stop`, `onDestroy`, `onBack`.
  - Poll only while `isShown()`; stop in `onHide`/`onActivityStop`.
- **`MainActivity`:**
  - Navigation: `select(TAB_*, animate)`, `openSettings()`, `closeSettings()`, `applyTheme(pref)`.
  - Sub-objects: `commander().run("/cmd …")` (every slash command), `comms().submitText(...)`.
  - System actions: `startVoice(prompt, TextResult)`, `pickImage(ImageResult)`, `copy(text)`, `share(text)`, `showConnection()`, `promptServerAddress()`, `setBadge(tab, on)`, `appVersion()`.
- **`Engine`** (all callbacks arrive on the main thread):
  - **State:** `state()` (ONLINE/SCANNING/OFFLINE…), `server()`, `models()`, `isLoaded`, `runningInfo(model)` (VRAM/context), `currentModel()`, `effectiveModel()`, `mode()`, `isBusy()`, `streamingMessage()`, `lastSpeed()`, `speaking()`.
  - **Telemetry:** the `telemetry` field (`latencyMs`, `tokensPerSec`, `ttftMs`, `contextFill` series; replies, tokensIn/Out, errors, `events()`, `uptimeMs(now)`) and `log(level, text)`.
  - **Models:** `setModel`, `setMode`, `setDeepModel`, `warm()`, `unload(name)`, `pull(name)` + `pullState()`, `fetchDetails(model, cb)`, `details(model)`, `supportsVision/Thinking`, `deleteModel(model, cb)`, `refreshModels(after)`, `bench()`.
  - **Chats:** `newChat`, `listChats(cb)`, `openChat(id)`, `renameChat`, `deleteChat`, `send(text[, images])`, `stop()`, `regenerate()`, `summarize()`, `compact()`.
  - **PC (LaunchBridge):** `bridgeHealth(cb)`, `bridgeOnline()`, `bridgePaired()`, `bridgePair(cb)`, `bridgeVitals(cb)` + `lastVitals()`, `bridgeApps(q, limit, cb)`, `bridgeCapabilities(cb)`, `bridgeRun(tool, argsJson, cb)`, `bridgePreviewLaunch`, `bridgeLaunch(appId, name)`.
  - **Voice:** `setReadAloud`, `speakNow`, `speechStop`, `announce`.
  - **Settings:** `e.settings` holds every preference.

## 4. Engineering rules

- Java 8 **without lambdas** (anonymous classes), **no AndroidX**, and **minSdk 23**. Any API above 23 needs an `SDK_INT` guard.
- Don't use `PopupMenu`: the build's android.jar leaks hidden types. Use `Ui.pick`.
- Never block the main thread. Engine methods are async and call back on the main thread.
- Motion respects `e.settings.reduceMotion()` and, for decoration, `e.settings.hudEffects()`. Stop animators in `onHide`.
- Give every interactive icon a content description (tests and TalkBack use them).
- Build: `./build.sh` (APK) and `./test.sh` (JVM tests).
  - UI tests: `cd uitest && gradle test --offline -q --tests '<Class>'` (Robolectric; needs `./build.sh` first).
  - Screenshots go to `uitest/build/screens/`. `Harness` has the helpers.
