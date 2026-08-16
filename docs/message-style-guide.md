# AutoStopper Message Style Guide

This guide establishes the presentation and messaging standards for AutoStopper 2.1.0 and future contributions.

---

## 1. Experience Goals & Principles

- **Recognisable:** AutoStopper uses a consistent, calm visual identity based on its signature branding: *"Empty servers sleep. Players wake them."*
- **Scannable:** The server name, current operational state, outcome, and next action are immediately obvious at a glance.
- **Quiet:** AutoStopper acknowledges operations upon admission, reports meaningful stage transitions, and sends one terminal outcome. It never floods chat with periodic percentage spam.
- **Human:** Use clear player- and operator-facing language (e.g. *Sleeping*, *Waking*, *Ready*) rather than internal enum identifiers or raw infrastructure errors.
- **Accessible:** Never rely on color, text decoration, hover text, or symbols alone to convey meaning. Symbols enhance visual scanning, but adjacent text carries the complete meaning.
- **Compatible:** Render safely across every supported Velocity and Adventure runtime (Adventure 4.x and 5.x) and degrade gracefully to readable plain text in console logs and legacy clients.

---

## 2. Semantic Palette & Tokens

All text components use central semantic tokens defined in [`MessageTokens.java`](../src/main/java/me/criseda/autostopper/messages/MessageTokens.java):

| Token | Hex / RGB | Downsampled Named Color | Usage |
|---|---|---|---|
| `BRAND` | `#8CB2C5` (140, 178, 197) | `AQUA` / `BLUE` | Product brand name in headers and prompts |
| `TEXT_PRIMARY` | `#E5E9F0` (229, 233, 240) | `WHITE` | Primary prose and descriptions |
| `TEXT_MUTED` | `#8892B0` (136, 146, 176) | `GRAY` / `DARK_GRAY` | Secondary details, timestamps, elapsed times, and middle dot separators (`·`) |
| `ACTION` | `#70D6FF` (112, 214, 255) | `AQUA` | Command syntax, server names, arguments, and clickable affordances |
| `SUCCESS` | `#A3BE8C` (163, 190, 140) | `GREEN` | Success marker `✓`, `● Ready`, and successful reload/preflight labels |
| `PROGRESS_WARNING` | `#EBCB8B` (235, 203, 139) | `YELLOW` / `GOLD` | In-flight operations (`◐ Waking`, `◐ Stopping`), unverified readiness, and warnings |
| `FAILURE` | `#BF616A` (191, 97, 106) | `RED` | Attention marker `!`, `! Unavailable`, `! Failed`, errors, and permission denials |

---

## 3. Brand Prompts & Prefixes

AutoStopper replaces the heavy, repetitive `[AutoStopper]` bracketed prefix with compact, purposeful brand marks:

- **Routine & In-Flight Operations:** `AutoStopper › `
- **Successful Terminal Outcomes:** `AutoStopper ✓ `
- **Errors, Warnings & Denials:** `AutoStopper ! `

```text
AutoStopper › Waking survival…
AutoStopper ✓ Connected to survival · 8.4s
AutoStopper ! Couldn't connect to survival · Try /server survival again in a moment
```

---

## 4. Operational Status Presentation

`/autostopper status` presents deterministic, alphabetically sorted server rows with explicit state badges and humanized metrics:

```text
Server status
● survival   Ready · held; active 2m ago
◐ creative   Waking · 2 players waiting
○ events     Sleeping
! modded     Unavailable · Docker cannot be reached
◐ lobby      Running · readiness unverified · active 5m ago
◐ minigames  Stopping
! backup     Failed · status check timed out
```

### State Mapping Table

| `OperationalState` | Glyph | Label | Tone | Description |
|---|---|---|---|---|
| `READY` | `●` | `Ready` | `SUCCESS` | Backend container is running and readiness check passed. |
| `STARTING` | `◐` | `Waking` | `PROGRESS_WARNING` | Container startup / readiness verification is in progress. |
| `RUNNING_UNVERIFIED` | `◐` | `Running · readiness unverified` | `PROGRESS_WARNING` | Container is running, but readiness has not been verified for this process generation. |
| `STOPPING` | `◐` | `Stopping` | `PROGRESS_WARNING` | Container shutdown is in progress. |
| `STOPPED` | `○` | `Sleeping` | `TEXT_MUTED` | Container is stopped and sleeping. |
| `FAILED` | `!` | `Failed` | `FAILURE` | Startup, status probe, or connection check failed. |
| `DOCKER_UNAVAILABLE` | `!` | `Unavailable · Docker cannot be reached` | `FAILURE` | Docker daemon or socket is unreachable. |

---

## 5. Root Overview & Help Menu

- `/autostopper` provides a calm overview showing the version, the signature tagline, and available commands for the source.
- `/autostopper help` displays only commands the invoking source has permission to execute.
- **Player Affordances:** For player sources, commands include `ClickEvent.suggestCommand(...)` and hover tooltips explaining the action.
- **Console Fallback:** For console sources, output is complete and copyable plain text.
- **Fuzzy Matching:** Typos (e.g. `/autostopper statsu`) provide nearest-match suggestions (`Did you mean /autostopper status?`) with click-to-suggest affordances.

---

## 6. Player vs. Operator Boundary

- **Player Messages:** Sanitized, clear, and actionable. Never expose Docker container names, container IDs, socket paths, daemon URLs, or stack traces.
- **Operator Diagnostics:** `/autostopper status` and reload reports provide safe failure contexts and suggested remediation (e.g. *Start Docker daemon* or *Check backend listener*).
- **Raw Infrastructure Logs:** Raw Docker stderr, process exit codes, and stack traces belong exclusively in server operator logs (`velocity.log`).
