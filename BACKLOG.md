# OpenMinis-Max — Improvement Backlog (Prompt for Coding Agent)

## Your job

You are working on the repository **OpenMinis-Max** (fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis)): a free, open-source (GPL-3.0), on-device AI agent app for Android and iOS. It gives models (Claude, GPT, Gemini, etc.) a sandboxed on-device Linux shell, browser automation, extensible skills, persistent memory, workspaces, and device-integration bridges.

Implement the **45 improvements below**, organized in three groups:

- **Group A — 15 changes to existing behavior**
- **Group B — 15 capabilities to add**
- **Group C — 15 new features**

## Working rules (read first)

1. **Treat this document as a backlog, not a single task.** Work item by item, in the order A1→A15, then B, then C, unless an item depends on a later one.
2. **Create `ROADMAP.md` at the repo root** on your first commit. Copy all 45 items into it as a checklist (`- [ ] A1 ...`). Check off each item (`- [x]`) only when it is implemented, builds, and is tested. Keep this file updated on every commit — it is the source of truth for progress.
3. **One commit (or one branch/PR) per item**, with a message like `A3: first-task onboarding flow`. Never bundle unrelated items.
4. **Android is the priority platform.** Where a feature exists on iOS only, port it to Android using the Android equivalent (e.g. Health → Health Connect, HomeKit → Google Home/Tasker APIs). Do not break iOS behavior.
5. **Do not regress existing features.** After each change, build the Android app and run any existing tests. If the repo has no tests for an area you touch, add a minimal one when practical.
6. **Keep the GPL-3.0 license.** Any code you copy must be license-compatible.
7. **No paid or account-requiring third-party services** unless the item explicitly calls for an optional integration. Prefer free, open-source, on-device solutions. If an item is impossible without a paid service, implement everything up to that boundary and mark the item as blocked in `ROADMAP.md` with the reason.
8. **Privacy first:** sensitive data (messages, notifications, keys) must stay on-device unless the user explicitly enables a cloud feature. Never send data to a third party silently.
9. **Every sensitive agent action introduced below must go through the consent system in B13** once it exists. Until then, add a simple confirmation dialog.
10. If an item is unclear or too large, split it into sub-items in `ROADMAP.md` and implement the smallest useful version first. Never skip an item silently.
11. Update `README.md` and a `CHANGELOG.md` as features land.

---

## Group A — Changes to existing behavior

**A1. Android bridge parity with iOS.**
The iOS app exposes rich device bridges (Health, HomeKit, etc.). Bring Android to parity using Android equivalents: Health Connect, Google Home / Tasker / Intent APIs, Calendar, Contacts, Bluetooth, Clipboard, Media, Alarms. Each bridge should expose tools to the agent with the same naming and behavior as the iOS ones where possible.

**A2. Frictionless install and updates.**
Ship a ready-to-install APK from Releases (no build step, no computer needed). Add in-app update checking with one-tap self-update (or F-Droid/IzzyOnDroid metadata so updates arrive automatically).

**A3. Two-minute first-task onboarding.**
Redesign the first-launch flow so a new user completes their first successful agent task within two minutes: pick a provider, enter/test a key, run a guided sample task. No walls of text.

**A4. Lower battery and RAM use for the background shell.**
Profile the sandboxed Linux shell when idle and in the background. Reduce wakeups, unload unused services, and add a battery-saver mode that suspends the shell until the agent is invoked. Document before/after measurements in the PR.

**A5. Data-flow transparency.**
Add a clear, readable privacy screen showing exactly what is sent to the model provider (prompts, attachments, memory snippets) and what stays on-device. Link it from onboarding and settings.

**A6. Unified permissions page.**
One settings page listing every Android permission the app uses, why it is needed, the feature it unlocks, and when it was last used. Tapping a permission jumps to the system setting.

**A7. Human-readable error screens.**
Replace technical/raw error messages with: a plain-language cause, what the user can do, and a "Try again" button. Keep the technical detail behind an expandable "Details" section for debugging.

**A8. Long-running tasks survive screen-off and system kills.**
Use a foreground service (with proper notification) and wake locks so scheduled/long agent tasks keep running when the screen is off, and restart gracefully if the process is killed. Handle Doze mode and battery-optimization exemption politely.

**A9. Secure API-key management.**
Store provider keys in Android Keystore-backed encrypted storage. Add a "Test key" button that validates the key with the provider before saving, and show a clear error if it fails.

**A10. Fast search over past conversations and memory.**
Index conversation history and memory (e.g. FTS) so search returns results instantly, with filters by workspace and date.

**A11. Token cost control.**
Show a live token/cost counter per conversation and per day, with a user-settable spending/token cap that pauses the agent (with a prompt) when reached.

**A12. Full Arabic support.**
Complete RTL layout support across every screen, Arabic UI strings, Arabic-capable fonts, and correct handling of mixed Arabic/English (bidi) text in chat, including code blocks. Voice input/output must work in Arabic (Egyptian dialect tolerance).

**A13. Backup and restore.**
One-tap export/import of memory, skills, workspaces and settings to a single encrypted file the user can store anywhere. Restore must validate the file and report what was recovered.

**A14. True offline mode.**
Support a local on-device model (e.g. via llama.cpp/MLC or a user-supplied GGUF) so core chat and shell-agent tasks work with no internet, degrading gracefully and clearly labeling offline mode.

**A15. Organized skills store.**
A browsable skills directory with categories, search, and community ratings. Before running any skill from an unknown source, show a warning describing what the skill can access, with an explicit approve/deny.

---

## Group B — Capabilities to add

**B1. SMS send/receive from the user's number.**
Agent tools to send and read SMS on the device (default-SMS-app role or a companion gateway), with per-message user approval before anything is sent.

**B2. Notification access: read, summarize, reply.**
A notification listener service that lets the agent read incoming notifications, summarize them on demand or on a schedule, and offer inline replies where the app supports it.

**B3. Accessibility control.**
An accessibility service that lets the agent tap, type, scroll and read screens in other apps, strictly scoped by the consent system (B13) and logged in the activity log (B14).

**B4. Scheduled and recurring tasks.**
A reliable scheduler (cron-like plus natural language: "every morning at 8") that runs tasks even when the app UI is closed, built on the A8 foreground-service work.

**B5. Widget and floating bubble.**
A home-screen widget and an optional floating bubble that summon the agent from any screen, with configurable quick actions.

**B6. Voice mode.**
Full duplex voice: speech-to-text in (Arabic and English), spoken replies out, with push-to-talk and hands-free wake options.

**B7. Share-sheet target.**
Register the app as a share target so any app can send text, links, images or files straight into an agent conversation with an optional instruction.

**B8. Phone, contacts, location, camera tools.**
Agent tools to read contacts, get current location, take/attach photos, and initiate calls — each gated by its own Android permission and the consent system.

**B9. Built-in SSH client.**
Let the agent open SSH sessions to user-configured hosts (keys in Keystore) to administer a computer or server from inside the app.

**B10. Termux integration.**
Share files and run commands between OpenMinis-Max and Termux (via shared storage or Termux's intent/RPC interfaces) so both toolchains are available to the agent.

**B11. Sub-agents with roles.**
Support spawning sub-agents with distinct roles/instructions that can work in parallel and deliberate with each other before returning a combined answer.

**B12. Optional free cloud sync.**
An opt-in, free, end-to-end-encrypted sync of memory and settings across the user's devices (e.g. user-owned storage such as Google Drive app-data folder or a self-hosted option). Off by default.

**B13. Consent system for sensitive actions.**
A central approval layer: before any sensitive action (sending SMS/messages, purchases, posting, deleting, accessibility taps in other apps), the agent must present exactly what it will do and wait for the user's approval. Per-action "always allow" scopes, revocable from settings.

**B14. Activity log with undo.**
A chronological log of everything the agent did (tool calls, messages sent, files changed), each entry with detail view and, where the action is reversible, an undo button.

**B15. Easy local-model servers (Ollama & LAN).**
First-class support for Ollama and other OpenAI-compatible servers on the device or the local network: auto-discovery where possible, a one-field setup form, and a connection test.

---

## Group C — New features

**C1. Guard mode (anti-scam watcher).**
An optional mode that monitors notifications and messages for scam/phishing patterns (suspicious links, urgency, impersonation) and warns the user with a plain-language explanation. All analysis on-device or with explicit consent.

**C2. Security research agent.**
A skill/agent that collects new CVEs and security news relevant to the user's stack on a schedule and delivers a short morning brief (Arabic or English per user setting).

**C3. Safe pentest lab.**
A pre-configured, clearly-labeled security toolkit inside the shell (nmap and similar), hard-restricted to the user's own network ranges with a first-run warning and consent. No scanning of external targets.

**C4. Self-improving memory.**
Memory that learns the user's style, language and preferences over time, with a simple UI to view, correct and delete what it has learned.

**C5. Screen-record and explain.**
Record the screen (with permission), then have the agent watch it, explain what happened, and optionally replay/guide the steps.

**C6. App generator.**
"Describe an idea, get an Android app": the agent scaffolds a complete Android project and builds it via a free CI path (e.g. GitHub Actions free tier), delivering an APK or a repo link.

**C7. Student mode.**
Summarize lectures (audio/files/slides), generate quiz questions and spaced-repetition flashcards, and track study sessions.

**C8. Natural-language automation (Tasker-style).**
Users write rules in plain language ("if battery < 20, turn off X; at 11pm enable DND") that compile to reliable on-device automations using device events and tools.

**C9. Job-hunt assistant.**
Track remote job postings from free sources on a schedule, match them to the user's profile, and draft a tailored CV and cover letter per posting for the user to review.

**C10. Mini finance agent.**
Parse bank/wallet/merchant notifications (from B2) into a local expense and subscription tracker with weekly summaries. No account credentials; notification-based only.

**C11. Agent sharing with a friend.**
Share a configured agent — its skills and a workspace — with another OpenMinis-Max user (link or QR), with the receiver approving every included skill.

**C12. Live translation.**
Real-time translation of conversations and notifications between Arabic and English (and other languages via the configured model), inline in the chat UI.

**C13. Focus mode.**
A study/work focus mode that holds, batches and summarizes notifications (using B2), with allowed-app and schedule controls.

**C14. Mobile coding companion.**
A lightweight built-in code editor with syntax highlighting, run-in-sandbox, git integration (clone/commit/push), and an agent review flow for diffs.

**C15. "Explain this" mode.**
From any screen or error in any app (via accessibility/screenshot with consent), the agent explains what is on screen and what to do next.

---

## Definition of done (for the whole backlog)

- All 45 items checked in `ROADMAP.md`, or explicitly marked blocked with a reason.
- Android release APK builds cleanly and passes a manual smoke test of: onboarding, one chat with tool use, one scheduled task, backup/restore.
- README and CHANGELOG describe every landed item.
