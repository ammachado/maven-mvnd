# Gradle-style live display with live test progress

Date: 2026-07-02
Status: Approved design (v1)

This spec combines two previously separate designs into one:

1. **Gradle-style live build display** — a drawn progress bar, concise
   `> :module goal` worker lines, and dimmed `> IDLE` slots for free worker
   threads.
2. **Live test progress** — a transient per-project suffix showing running test
   counts and the currently executing class/method while Surefire/Failsafe run.

The two are almost entirely independent. They intersect at exactly one method,
`TerminalOutput.addProjectLine`, which this document defines once to cover both.

## Goal

Reshape mvnd's multithreaded live terminal output to read like Gradle's rich
console, and enrich the worker line for test phases with live per-test progress.
A project running tests renders as:

```
> :my-service surefire:test (default-test) [Tests: 12, Failures: 1] MyServiceTest#shouldWork
```

The rendering change is presentation-only. The test-progress feature adds a new
message type and a daemon/fork plumbing path, but does not change existing
message semantics.

## Structure of the change

Two concerns, split by where they live:

- **Client rendering** (`common/.../logging/TerminalOutput.java`): progress bar,
  concise worker lines, dimmed idle slots, and the test-progress suffix. Both
  designs land here.
- **Daemon / fork plumbing** (new `surefire-progress` module, a shared bridge
  type, `Message.ProjectTestProgressEvent`, lifecycle/config injection, and
  plugin-realm classpath injection): the test-progress feed. The Gradle-style
  change does not touch any of it.

## Target branches

Both features target **both** active mvnd lines:

- `master` — the 2.x line, Maven 4.
- `mvnd-1.x` — the 1.x line, Maven 3.9.16.

The design is identical on both branches. What differs is a small set of
mechanism-specific integration points, driven by the Maven 3 vs Maven 4 core.
Those differences are enumerated below (rendering) and in Part B's
"Per-branch plumbing" section (test-progress feed). Apply per branch, taking edit
anchors from each branch's own source, and follow each branch's local idiom
(Rule 11).

### Rendering (Part A + C)

The three rendering methods are effectively identical across branches; only
cosmetic differences exist in `addStatusLine` (a `StringBuilder` vs string
concatenation for the threads value, and an `else if (buildStatus != null)` vs
`else` guard). The `addProjectLine` change (concise line + suffix) and the idle
filler loop are byte-identical across branches. Fully portable.

### Test-progress feed (Part B)

The 2.x plumbing was de-risked by two runtime spikes (see Spike results and
Injection mechanism). **The equivalent 1.x path has NOT yet been spiked.** The
integration points exist on both branches but bind at different places because of
the Maven 3 vs 4 core; they are documented in "Per-branch plumbing". The 1.x path
carries a mandatory validation spike as the first implementation step, mirroring
the rigor applied to 2.x. This is a real risk, not a formality: it must be proven
before the 1.x plumbing is considered done.

The `PROJECT_TEST_PROGRESS` message type and the client-side render are added to
`common` on **both** branches so the client compiles and behaves identically; the
branches differ only in how the daemon feeds that message.

---

# Part A — Gradle-style rendering

Single file: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java`.

Methods touched:

- `addStatusLine` — prepend the drawn progress bar, remove the now-duplicated
  standalone percent from the `progress:` section.
- `addProjectLine` — reformat worker lines to the concise arrow style, and append
  the test-progress suffix (see Part C for the suffix).
- `update` — replace the blank filler lines with dimmed `> IDLE` lines.
- New private helper `renderBar(int percent)` for the bar string.

No new dependencies for Part A. No protocol changes. All fields required
(`maxThreads`, `doneProjects`, `totalProjects`, per-project `runningExecution`)
already exist and are read from the main/display thread as today.

## A1. Progress-bar status line (top)

Placement stays at the top of the live region (progress line first, worker lines
below), matching mvnd's current arrangement.

The drawn bar is prepended to the existing status line. Since the bar carries the
percent, the standalone percent is removed from the `progress:` section to avoid
showing it twice. Everything else on the line is kept: `Building <name>`,
`daemon:`, `threads used/hidden/max:`, `progress: N/M`, and `time:`.

```
[=========>          ] 30% Building foo  daemon: xxx  threads used/hidden/max: 3/0/4  progress: 12/40  time: 00:23
```

Bar rendering (`renderBar`):

- Fixed inner width of 20 columns.
- `percent = doneProjects * 100 / totalProjects` (unchanged from today's value).
- `filled = Math.round(percent / 100.0 * 20)`.
- `0 < filled < 20`  → `[` + `=`×(filled-1) + `>` + spaces×(20-filled) + `]`
- `filled == 20`     → `[` + `=`×20 + `]`  (no `>` head when complete)
- `filled == 0`      → `[` + spaces×20 + `]`

The complete line is still trimmed to the terminal width via
`columnSubSequence(0, cols)` at the end of `update()`, so narrow terminals
truncate gracefully rather than wrapping.

## A2. Worker lines (concise + execution id)

`addProjectLine` is reformatted from the verbose plugin GAV to a concise arrow
style:

```
> :core compiler:compile (default-compile)
> :web  surefire:test (default-test)
```

Rules:

- Leading `> ` arrow in **bold green**.
- Module id in **cyan**, keeping the existing `artifactIdFormat` left-padding so
  the goal columns line up.
- Goal in **green**: `pluginGoalPrefix` when non-empty, otherwise fall back to
  `pluginArtifactId` (this drops the verbose groupId:artifactId:version), then
  `:` + `mojo`, then ` (` + `executionId` + `)`.
- `execution == null` → `> :id` (arrow + cyan id only).
- Active transfer for the project → arrow + cyan id + transfer text, as today,
  but with the new `> ` arrow prefix.

Rationale for the goal-prefix fallback: `pluginGoalPrefix` is the short,
human-facing name (e.g. `compiler`, `surefire`) and is what Gradle-style output
wants. When a plugin has no registered prefix, `pluginArtifactId` is the next
most recognizable short token.

## A3. Idle slots (dimmed)

After the active project lines, when `projectsCount < maxThreads` and display
rows remain, append `maxThreads - projectsCount` lines of `> IDLE`:

```
> :core compiler:compile (default-compile)
> IDLE
> IDLE
```

- The `> ` arrow uses the same bold-green style; the `IDLE` label is rendered
  **faint/dim** so active work stands out.
- These replace today's blank filler lines (the
  `while (remLogLines-- > 0 && lines.size() <= maxThreads + 1)` loop that adds
  `AttributedString.EMPTY`).
- When threads are hidden (`projectsCount > dispLines`, the else-branch of
  `update()`), no idle lines are shown. There are no free slots to represent in
  that state, and the display is already row-constrained.
- Idle lines are still subject to available rows; they never push the line count
  past what the terminal can show.

## A4. Colors summary

| Element            | Style               |
|--------------------|---------------------|
| `> ` arrow         | bold green          |
| Module id          | cyan                |
| Goal text          | green               |
| `IDLE` label       | faint/dim           |
| Test suffix        | faint/dim; failures/errors count red when nonzero (Part C) |
| Progress-bar line  | existing bold/plain mix, unchanged except the bar prefix |

---

# Part B — Live test progress: daemon/fork plumbing

Show live test progress on each project's line while Surefire (and Failsafe) run
tests. For v1 the suffix shows a running count of completed tests with a
failure/error/skip breakdown, plus the currently executing test class and method.
The suffix is transient: it updates in place as tests run and is cleared when the
mojo (or project) finishes.

### Explicitly out of scope for v1

- **Total test count and percentage.** Investigation of Surefire 3.5.x confirmed
  that no planned total (test methods or test classes) is ever serialized across
  the fork boundary: not in `ReportEntry` / `TestSetReportEntry` /
  `SimpleReportEntry`, not in any `Event` subclass, and not by the JUnit Platform
  provider (its `TestPlan` count stays inside the fork). `RunResult.completedCount`
  is only a final tally delivered at the end of a run. The ceiling reachable
  plugin-side without touching the fork is a **class-count** total via a pre-fork
  scan of `TestsToRun`; a method-level total would require injecting code into the
  fork. Both are deferred. v1 ships running counts + current class/method only.
- Per-test timing, flake counts, and any historical reporting.
- Feeding test progress into the Part A progress bar. The bar remains
  module-completion based; no per-test total is available to drive it.

### Framework scope

Provider-agnostic by construction: the hook reads whatever Surefire places in
each `ReportEntry` (`getSourceName()` for the class, `getName()` for the method).
This covers the JUnit Platform provider (JUnit 5, and JUnit 4 / other engines via
the platform), JUnit 4, and TestNG. Cucumber is covered when run on the
`cucumber-junit-platform-engine`; the line then shows Cucumber's feature/scenario
descriptor names rather than a Java class and method. No provider is
special-cased.

## Architecture

```
Forked test JVM (Surefire)
   |  per-test events over Surefire's existing binary channel
   v
Daemon JVM
   [A] mvnd ForkNodeFactory  -- wraps the EventHandler<Event>, observes each event,
   |        accumulates per-fork counts, resolves projectId
   v
   [bridge] MvndTestProgress registry  -- shared type in the core-exported realm
   v
   [B] ClientDispatcher.testProgress(projectId, class, method, counts)
   |        -> Message.PROJECT_TEST_PROGRESS
   v  (existing daemon -> client socket)
Client JVM
   [C] TerminalOutput handles PROJECT_TEST_PROGRESS -> updates mutable
   |        Project.testProgress field
   v
   [D] addProjectLine appends the progress text; cleared on mojo/project finish
```

### Components

- **[A] `MvndForkNodeFactory`** (new module, loaded in the Surefire plugin realm).
  Implements `org.apache.maven.surefire.extensions.ForkNodeFactory`. Delegates
  `createForkChannel(ForkNodeArguments)` to Surefire's default
  (`SurefireForkNodeFactory`), then returns a `ForkChannel` that decorates the
  `EventHandler<Event>` passed to `bindEventHandler(...)`. The wrapper inspects
  each `Event` (`TestsetStartingEvent`, `TestStartingEvent`, `TestSucceededEvent`,
  `TestFailedEvent`, `TestErrorEvent`, `TestSkippedEvent`, `TestsetCompletedEvent`),
  updates a per-fork accumulator, pushes an update through the bridge, then
  delegates to the real handler so Surefire behaves exactly as before. The three
  reporter interfaces (`StatelessReporter`, `ConsoleOutputReporter`,
  `StatelessTestsetInfoReporter`) are too coarse (test-set granularity only) and
  are not used.

- **[bridge] `MvndTestProgress`** (a shared interface + static registry). Placed
  in a package **exported by mvnd's core/daemon realm** so the Surefire plugin
  realm resolves the *same* class (a single `Class` object, so the static
  registry is genuinely shared across realms). The daemon sets the active listener
  at build start to an adapter that calls `ClientDispatcher.testProgress(...)`.
  The new module references this type as a `provided`-scope dependency so it does
  **not** bundle a duplicate copy into the plugin-realm artifact.

- **[B] `ClientDispatcher.testProgress(...)`** (daemon). Enqueues a
  `Message.ProjectTestProgressEvent`, mirroring `mojoStarted`.

- **[C]/[D] Client render** (common, `TerminalOutput`). A new mutable
  `testProgress` field on the client-side `Project`; updated when a
  `PROJECT_TEST_PROGRESS` message arrives, appended by `addProjectLine`
  (see Part C), and cleared on `MOJO_STARTED` for the same project and on project
  finish.

- **[E] `MvndTestProgressLifecycleParticipant`** (new module, daemon realm).
  An `AbstractMavenLifecycleParticipant.afterProjectsRead`. For each project it
  finds `maven-surefire-plugin` / `maven-failsafe-plugin` executions and, only if
  the user has not already configured `<forkNode>`, arranges injection of the
  `MvndForkNodeFactory` (see Injection mechanism). Guarded on a Surefire version
  that supports the extensions SPI (3.x); otherwise it does nothing, silently.

- **[F] Enablement flag** (common `Environment`). A new mvnd property, e.g.
  `mvnd.testProgress`, **on by default with opt-out**. When off, nothing is
  injected and behavior is unchanged.

## Message format

New type in `common` `Message.java`, following the `MojoStartedEvent` pattern:

```java
public static final int PROJECT_TEST_PROGRESS = 29;

public static class ProjectTestProgressEvent extends Message {
    final String projectId;    // artifactId, matches TerminalOutput.Project.id
    final String currentClass; // ReportEntry.getSourceName(), nullable
    final String currentMethod;// ReportEntry.getName(), nullable
    final int completed;       // running count of finished tests
    final int failures;
    final int errors;
    final int skipped;
    // read/write via DataInputStream/DataOutputStream using writeUTF/writeInt,
    // symmetric read(); nullable strings encoded with a presence flag or "".
}
```

- Wired into `Message.read()`, and into `getClassOrder` in group `2` (transient
  display updates, alongside `DISPLAY`).
- Factory method `Message.projectTestProgress(...)`.

### Coalescing

Events fire per test method and can be chatty. mvnd already drains the queue and
repaints on a throttled refresh tick (`TerminalOutput.update()` does not redraw
every step), so v1 sends one event per test transition and relies on the existing
throttled render to collapse them. No new throttling logic in v1.

## Project attribution

mvnd attributes output to a project via `ProjectBuildLogAppender.getProjectId()`,
a thread-local read on the build thread. However, the wrapped `EventHandler` is
invoked on Surefire's fork-reader thread, where that thread-local is **not** set.
Therefore projectId is **bound into the extension instance at injection time**
(via the `<forkNode>` config parameter), not read from the thread-local at event
time.

## Classloader realms (highest risk; de-risked by spike)

`MvndForkNodeFactory` must be loadable by the Surefire plugin realm, yet it must
reach `ClientDispatcher` in mvnd's daemon core realm. For the static registry in
`MvndTestProgress` to be shared, the bridge type must be loaded by a common
parent classloader visible to both realms, i.e. the maven core realm's exported
packages.

Intended approach:
- Put `MvndTestProgress` (bridge interface + registry) in a package exported by
  mvnd's core/daemon realm, so both realms see one `Class`.
- The new module depends on that bridge type as `provided` scope (no duplicate on
  the plugin realm).
- The lifecycle participant / realm cache adds the module's `MvndForkNodeFactory`
  artifact to the Surefire plugin realm at runtime.

### Spike results (2026-07-02)

A full runtime spike (throwaway `mvnd-surefire-progress` module, exported
`org.mvndaemon.mvnd.testprogress` bridge, `MvndForkNodeFactory` extending
`SurefireForkNodeFactory`, run against a JUnit 5 project on a rebuilt dist)
**validated the runtime design**:

- The bridge is a single shared `Class` across realms: identity
  `MvndTestProgress@<same hash>` in both the daemon-realm participant and the
  plugin-realm factory. Adding `org.mvndaemon.mvnd.testprogress` to
  `collectExportedPackages` is sufficient.
- The factory loads on the surefire plugin realm, links against plugin-realm
  surefire SPI types, and resolves the bridge from the exported parent, no
  `LinkageError`.
- The wrapped `EventHandler` sees the live per-test stream
  (`TESTSET_STARTING`, `TEST_STARTING`, `TEST_SUCCEEDED`, `TEST_FAILED`,
  `TEST_SKIPPED`, `TESTSET_COMPLETED`) with correct `getSourceName()` /
  `getName()` and a running completed count; the daemon-realm listener receives
  every event. Test results are unaffected.
- Daemon-side `System.err` is surfaced to the client as `[stderr]`, useful for
  diagnostics.

### Injection mechanism (validated 2026-07-02)

Model mutation in `afterProjectsRead` has **no effect under Maven 4** (surefire
fell back to `LegacyForkNodeFactory`). A second spike validated a working
Maven-4-native mechanism, **auto-injecting with zero POM changes**:

- **Config injection, custom `MojoExecutionConfigurator`.** A component
  `@Named("default") @Singleton @Priority(10)` extending
  `org.apache.maven.lifecycle.internal.DefaultMojoExecutionConfigurator`
  (constructor takes `MessageBuilderFactory`). Its `configure(project, execution,
  allowPluginLevelConfig)` calls `super`, then for surefire `test` /
  failsafe `integration-test` executions injects
  `<forkNode implementation="...MvndForkNodeFactory"><projectId>{artifactId}</projectId></forkNode>`
  into `mojoExecution.getConfiguration()`. This reaches surefire's effective
  config (confirmed: surefire loaded and used the impl). Project attribution rides
  on the injected `<projectId>` child (surefire sets it via `setProjectId`).
- **Classpath injection, plugin realm cache.** In mvnd's existing
  `InvalidatingPluginRealmCache`, inside the `get(key, supplier)` path (Maven uses
  that path, `put` is not called), after `supplier.load()` builds the realm, if
  `realm.getId()` contains `maven-surefire-plugin` / `maven-failsafe-plugin`, call
  `realm.addURL(...)` with the `mvnd-surefire-progress` jar (located via
  `MvndTestProgress.class.getProtectionDomain().getCodeSource().getLocation()`;
  requires a daemon dependency on the module). This puts the factory on the plugin
  realm just before config parsing needs it.

Confirmed end to end with both default fork settings and
`-DforkCount=1 -DreuseForks=false`: factory instantiated with the right
`projectId`, single shared bridge, all per-test events delivered to the daemon,
test outcomes unchanged.

## Per-branch plumbing (2.x vs 1.x)

The same four integration points exist on both branches but bind differently.
The `surefire-progress` module, the bridge type, the accumulator, and
`MvndForkNodeFactory` are branch-independent. Only the daemon-side wiring differs.

| Integration point | 2.x (`master`, Maven 4) | 1.x (`mvnd-1.x`, Maven 3.9) |
|---|---|---|
| Export bridge package to core realm | `collectExportedPackages(...)` in `org.apache.maven.cli.DaemonPlexusContainerCapsuleFactory` (adds `org.mvndaemon.mvnd.testprogress`) | `DaemonMavenCli` build of `exportedPackages`, next to the existing `exportedPackages.add("org.mvndaemon.mvnd.interactivity")` line, feeding `new CoreExports(...)` |
| Inject `<forkNode>` into surefire/failsafe config | Custom `MojoExecutionConfigurator` (`@Named("default") @Priority(10)` extending `DefaultMojoExecutionConfigurator`, ctor takes `MessageBuilderFactory`) — required because Maven 4 ignores `afterProjectsRead` model mutation | `afterProjectsRead` model mutation works under Maven 3.9, so an `AbstractMavenLifecycleParticipant` injecting `<forkNode>` into the plugin config is the native path. `DefaultMojoExecutionConfigurator` also exists on 3.9 but with a **no-arg** constructor, available as a fallback |
| Put factory jar on the Surefire plugin realm | `InvalidatingPluginRealmCache.get(key, supplier)` → `realm.addURL(...)` | Same class and same `get(key, supplier)` shape on 1.x; portable |
| Enqueue message to client | `ClientDispatcher.testProgress(...)` | Same class on 1.x; portable |

Notes:

- The injection-mechanism difference is the substantive one. On 2.x the
  configurator route was necessary and validated. On 1.x the simpler lifecycle
  participant is expected to suffice, but "expected" is not "validated": the
  first 1.x implementation step is a runtime spike that proves (a) the bridge
  `Class` is shared across the 3.9 core realm and the surefire plugin realm via
  the `DaemonMavenCli` export, (b) the injected `<forkNode>` is honored by
  surefire under Maven 3.9 (i.e. it does not fall back to a legacy fork node),
  and (c) per-test events reach the daemon with correct `projectId`.
- If the 1.x lifecycle-participant injection turns out not to be honored, fall
  back to the no-arg `DefaultMojoExecutionConfigurator` subclass on 1.x. The plan
  should treat this as the identified contingency.

## New module

- Directory `surefire-progress/`, artifactId `mvnd-surefire-progress`, following
  the `mvnd-<name>` convention.
- Contents: `MvndForkNodeFactory`, the per-fork accumulator, and
  `MvndTestProgressLifecycleParticipant`. The bridge type lives wherever mvnd's
  core-exported classes live (likely `common` or `daemon`) so it is shared across
  realms; final placement decided during the classloader prototype.
- Kept minimal to keep the plugin-realm footprint small.

## Error handling (Part B)

- If anything in the extension throws, it must **never** break the test run:
  catch, drop the progress update, and delegate to the real `EventHandler`
  regardless.
- If the bridge listener is unset (feature off, or non-daemon invocation), the
  extension is a no-op passthrough.
- Unsupported Surefire version or pre-existing user `<forkNode>`: inject nothing.

---

# Part C — The merged worker line (the one intersection)

`addProjectLine` is where Part A's concise formatting and Part B's test suffix
meet. Definition:

1. Render the concise worker line per A2 (arrow, cyan module id, green goal +
   execution id).
2. If the project has a non-empty `testProgress` (set by a `PROJECT_TEST_PROGRESS`
   message), append the suffix after the goal.

Rendered example:

```
> :my-service surefire:test (default-test) [Tests: 12, Failures: 1] MyServiceTest#shouldWork
```

Suffix format and style:

- Format: ` [Tests: {completed}{, Failures: F}{, Errors: E}{, Skipped: S}] {Class}#{method}`.
- `Tests: {completed}` is always shown once a progress event has arrived.
- `Failures`, `Errors`, `Skipped` segments are included **only when their count is
  nonzero**, keeping the common (all-passing) line compact.
- `Class#method` appended when a current class is known; method omitted when the
  event carries a class but no method (e.g. test-set granularity), rendering just
  `{Class}`.
- Styling: the whole suffix is **faint/dim** so the green goal stays prominent;
  the `Failures`/`Errors` counts render **red** when nonzero. `Class#method` stays
  faint.
- Before the first progress event (or after clearing), no suffix is rendered.

Lifecycle:

- The suffix comes from a mutable `Project.testProgress` field on the client-side
  `Project`.
- Set/updated on `PROJECT_TEST_PROGRESS` for that project.
- Cleared on `MOJO_STARTED` for the same project (a new mojo starting means the
  test phase moved on) and on project finish.

Non-test worker lines (`execution == null`, active transfer, `> IDLE`) never
carry a suffix.

---

# Testing

## Rendering (Part A + C), both branches

- Run on both `master` and `mvnd-1.x` independently.
- `TerminalOutput` renders through JLine, so existing integration tests that
  assert on client output (e.g. under `integration-tests`) must still pass.
- Unit-test the pure bar logic `renderBar(int percent)` for 0%, a partial value,
  and 100%, asserting the exact string (bracket, `=` run, `>` head
  presence/absence, padding, closing bracket).
- Unit-test the suffix formatter: all-passing (only `Tests: N`), with failures
  (red segment present), with errors/skips, and class-only vs class#method.

## Plumbing (Part B), both branches

Run the suite on both `master` and `mvnd-1.x`. On 1.x this is gated on the
mandatory validation spike (see Open items) succeeding first.

- **Unit:** the per-fork accumulator maps a sequence of `Event`s to the expected
  counts and current class/method, including failures/errors/skips and test-set
  transitions. `Message.ProjectTestProgressEvent` round-trips through read/write
  (asserting each field, per the existing `MessageTest` style).
- **Unit:** `MvndForkNodeFactory` delegates to the real channel and always calls
  the wrapped handler even when the bridge listener throws.
- **Integration:** an `integration-tests` project running a handful of JUnit 5
  tests asserts that `PROJECT_TEST_PROGRESS` messages are emitted with increasing
  `completed` counts and the expected class/method names (mirroring
  `MultiModuleTest` / `ExecOutputTest` message-filtering style).
- **Integration:** feature disabled via `mvnd.testProgress=false` emits no
  `PROJECT_TEST_PROGRESS` messages and leaves the surefire config untouched.

# Out of scope

- Bottom placement of the bar (Gradle's default). Kept at top per decision.
- Any change to the buffering / no-buffering modes, keybindings, or the
  non-interactive (`dumb`) path.
- Phase words (EXECUTING/CONFIGURING); mvnd has no equivalent build phases and
  the existing `Building <name>` text is retained instead.
- Total test count / percentage and per-test timing (see Part B out-of-scope).

# Open items to resolve in the implementation plan / prototype

1. Exact mechanism to place `MvndForkNodeFactory` on the Surefire plugin realm
   (runtime `Dependency` vs. core-extension export vs. realm-cache `addURL`).
2. Final home of the `MvndTestProgress` bridge type (`common` vs `daemon`) so it
   is the single shared class across realms.
3. Later reconcile: whether a class-count total via a pre-fork `TestsToRun` scan
   is worth adding as a v2 (method-level total remains out without fork
   injection).
4. **1.x plumbing validation spike (mandatory, first 1.x step):** prove the
   bridge `Class` is shared across the Maven 3.9 core realm and the surefire
   plugin realm via the `DaemonMavenCli` export; prove the injected `<forkNode>`
   is honored by surefire under 3.9; prove per-test events reach the daemon with
   the correct `projectId`. Contingency if lifecycle-participant injection is not
   honored: no-arg `DefaultMojoExecutionConfigurator` subclass on 1.x.
