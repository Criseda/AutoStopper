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
! backup     Failed · last failure 3m ago during server startup: Docker operation failed
```

- **Relative time:** every age in a status view (activity and last failure) uses one formatter: `just now`, `5m ago`, `1h 15m ago`, `2d 3h ago`. The overview never prints raw ISO-8601 timestamps.
- **Last failure:** an overview row shows the failure's age, context, and safe detail. The remediation lives in the per-server view, so the row stays one line.
- **Click (players):** clicking a server name runs `/autostopper status <server>`; the hover says `Click to show details for <server>`. Status is read-only, so rows run the command instead of suggesting it as help entries do (§5). Console rows have no click or hover.

### Per-server detail

`/autostopper status <server>` shows one labelled line per fact, read from a single container inspection:

```text
Server status · survival
  State: ◐ Waking · waiting for readiness
  Hold: none
  Waiting: 2 players waiting
  Activity: no activity recorded
  Last failure: 3m ago during server startup
  Cause: Docker operation failed
  Fix: Review proxy logs and Docker state, then retry.
```

- **State** carries the startup stage while the server is waking: `checking the container`, `starting the container`, or `waiting for readiness`.
- **Last failure** shows `none` when there is none. Players hover the age for the exact UTC timestamp; the console prints it inline (`3m ago (2026-10-06T21:11:45Z) during server startup`), so nothing depends on hover.
- **Cause** and **Fix** are the safe detail and remediation; raw Docker stderr stays in `velocity.log` (§6).
- A view containing a failure ends with the `Troubleshooting · Get help` footer (§7). An unmapped name gets the standard `Server <name> is not mapped in AutoStopper.` error.

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

- `/autostopper` provides a calm overview: the version, the signature tagline, a `Docs · GitHub · Modrinth · Changelog` link row, and a pointer to `/autostopper help`. For players the version opens that release's notes (the releases list on development builds). These public project links go to every source; the console gets one `Label: <url>` line per link.
- `/autostopper help` lists only commands the invoking source has permission to execute, under `General`, `Lifecycle`, and `Holds` headings; a heading with no permitted command is omitted. Descriptions are imperative ("Start a mapped server…") and come from the single subcommand definition in [`AutoStopperSubcommand.java`](../src/main/java/me/criseda/autostopper/commands/AutoStopperSubcommand.java), which also drives usage messages and tab completion.
- A source with no operator command sees `You have no AutoStopper operator commands.` followed by the project link row.
- **Player Affordances:** For player sources, help entries suggest the command on click (status rows are the read-only exception, §4), and their hover shows the description, the granting permission (`autostopper.command.<name> or autostopper.admin`), and `Click to suggest this command`.
- **Console Fallback:** For console sources, output is complete and copyable plain text.
- **Fuzzy Matching:** Typos (e.g. `/autostopper statsu`) provide nearest-match suggestions (`Did you mean /autostopper status?`) with click-to-suggest affordances.

---

## 6. Player vs. Operator Boundary

- **Player Messages:** Sanitized, clear, and actionable. Never expose Docker container names, container IDs, socket paths, daemon URLs, or stack traces.
- **Operator Diagnostics:** `/autostopper status <server>` and reload reports provide safe failure contexts and suggested remediation (e.g. *Start Docker daemon* or *Check backend listener*).
- **Raw Infrastructure Logs:** Raw Docker stderr, process exit codes, and stack traces belong exclusively in server operator logs (`velocity.log`).

---

## 7. Operator Failure Detail, Hover, Click & Links

Failed `/autostopper start`, `stop`, and `restart` commands keep the chat line short and put the detail one interaction away. The catalogue lives in [`OperatorFailureGuide.java`](../src/main/java/me/criseda/autostopper/messages/OperatorFailureGuide.java).

- **Chat line:** the reason, then the cause in plain prose, then the elapsed wait where it applies. The cause is always visible, so meaning never depends on hover.
- **Hover (players):** `Cause`, `Fix` (remediation), and `Outcome` (the typed code, identical to `outcome=` in `velocity.log`), ending with `Click to open troubleshooting`.
- **Click (players):** clicking the reason opens the troubleshooting section for that outcome. A separate trailing command component suggests the next step: the same command for transient failures, `/autostopper status` for infrastructure failures, and nothing when no command helps.
- **Links:** documentation links point at the release tag of the running plugin (for example `blob/2.1.1/docs/troubleshooting.md#start_failed`); development builds link `master`. Anchors are the lowercase outcome code, and a test fails if `docs/troubleshooting.md` lacks one.
- **Footer:** `/autostopper help` (for sources with any operator command; others get the public project link row, §5) and a status view containing failures end with `Troubleshooting · Get help`. "Get help" opens `issues/new/choose`.
- **Console:** no hover or click. The cause stays on the first line, followed by `Fix:`, `Next:`, and `Outcome: … · Troubleshooting: <url>` lines, and the footer prints both URLs.
- **Players never receive troubleshooting links or outcome codes** (§6). Player connection messages are unchanged. The public project links in §5 (docs, GitHub, Modrinth, changelog, release notes) are not operator detail and may go to anyone.
