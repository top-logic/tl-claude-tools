---
name: tl-app
description: Start, stop, or restart a TopLogic application. Use when you need a running app to test changes in the browser. Examples - "start the demo app", "restart the app", "stop the server".
allowed-tools: Bash, Read, Glob, Grep, WebFetch
---

# TopLogic Application Management

## Commands

```bash
"${CLAUDE_PLUGIN_ROOT}/skills/tl-app/tl-app.sh" start   <app-module-path>
"${CLAUDE_PLUGIN_ROOT}/skills/tl-app/tl-app.sh" stop    <app-module-path>
"${CLAUDE_PLUGIN_ROOT}/skills/tl-app/tl-app.sh" restart <app-module-path>
```

**IMPORTANT: The script MUST be called with `dangerouslyDisableSandbox: true`.** The sandbox (bubblewrap) kills all child processes on exit — the background JVM would die immediately. Running unsandboxed lets the `nohup`'d Maven process survive.

The script tracks the port in `<app-module-path>/tmp/app-port.txt` to prevent duplicate starts.

On start success, prints two lines:
```
url: http://localhost:PORT/context-path/
log: <app-module-path>/tmp/tl-app.log
```

Report the URL and credentials (user `root`, password `root1234`) to the user.

## Building before start

If a build is needed, run `mvn install -DskipTests=true` **once** in the app module. Skip the build entirely if the user says "just start".

**Recommended pattern for TopLogic apps:**

```bash
mvn install -DskipTests=true -B 2>&1 | grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"
```

This shows every Maven `[ERROR]` line plus the final banner — small on success (just the banner, sometimes a couple of `Resource check`-style warnings), and diagnostic on failure (each underlying error is reported on its own `[ERROR]` line, not buried in a stack trace).

Why not `-q`? TopLogic builds run an in-process app self-test as part of the install lifecycle, which boots the full service stack via log4j. Those service-lifecycle logs are not Maven output and bypass `-q` entirely — so a "quiet" build is in practice not quiet, and the visible output omits the `BUILD SUCCESS` banner that `-q` actively suppresses. The combination makes both observation (no banner) and silence (lots of side-effect logs) fail.

Why not `tail -N` or `grep ... -B 5`? On failure the Maven footer (Total time, Finished at, trailing separators) sits between the banner and the actual `[ERROR]` lines — a tight window of context lines shows the banner but not the cause. Filtering on `[ERROR]` captures every cause regardless of position.

**Avoid:** piping through `tail` alone, and combining `-q` with `tail`/`grep` (the banner is gone).

**Never run the build twice "to be sure"** — if the result is ambiguous, that's a reading problem, not a build problem. The exit code is authoritative.

## Determining the app module

If not specified: check current directory, check recent git changes, default to `com.top_logic.demo`. Ask if ambiguous.

## Database / `tmp/` directory

**Do NOT delete `tmp/`** unless an incompatible model change was made without a data migration (e.g. back-and-forth model changes during development), or the user explicitly asks. Adding new modules does NOT require deletion. Deleting forces slow full re-initialization and destroys test data.

## Verifying in the browser

Once the app is running, verification usually continues with Playwright. This is the token-expensive part — not because of any single call, but because a verification session is hundreds of browser round-trips and every turn re-sends the whole conversation context, which in a long session is enormous.

**Delegate the interaction loop to a sub-agent.** Start the app here (cheap, one-shot), then hand the browser work to a sub-agent (the `Agent` tool) that begins from a near-empty context, so those hundreds of turns re-bill a small context instead of the main thread's large one. Only a compact pass/fail report returns.

- Give the sub-agent a **self-contained brief** — it has none of the main conversation's context. Include the app URL, the exact path to the feature, the steps to perform, the expected outcome for each, and the login (`root` / `root1234`).
- Keep the fix-the-code work in the main thread; the sub-agent only verifies and reports.

Within the loop, **prefer `browser_snapshot` over `browser_take_screenshot`** — the accessibility-tree text is easier to assert against, and a screenshot is heavier per call:

- **Default to `browser_snapshot`** for anything checkable from structure or content: element presence, labels, values, enabled/disabled state, list contents, whether a dialog opened, whether navigation landed on the right page.
- **Use `browser_take_screenshot` only for genuinely visual checks** the snapshot cannot answer: layout/alignment, spacing, colors, theming, icon rendering, overflow/clipping, chart or diagram appearance — then take one targeted shot rather than repeated full-page captures.
