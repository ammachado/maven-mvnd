# Gradle-style Live Display (Part A) and Merged Worker Line (Part C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape mvnd's live terminal output into a Gradle-style console (drawn progress bar, concise `> :module goal (execution)` worker lines, dimmed `> IDLE` slots) and, at the single intersection method `addProjectLine`, append the live test-progress suffix, on both `master` (Maven 4) and `mvnd-1.x` (Maven 3.9).

**Architecture:** All changes are presentation-only and live in one client file per branch, `common/.../logging/TerminalOutput.java`. Part A adds a `renderBar` helper, prepends the bar to the status line, rewrites the worker line to the arrow style, and replaces blank filler lines with dimmed idle slots. Part C (the one intersection) stores the latest `ProjectTestProgressEvent` on the client-side `Project` and appends a styled suffix in `addProjectLine`. No protocol change on `master` (the wire message comes from the existing test-progress plan); on `mvnd-1.x` the wire message is added here so the client compiles identically.

**Tech Stack:** Java 17, JLine `AttributedString`/`AttributedStyle`/`Display`, JUnit 5.

## Global Constraints

- American English; no em dashes.
- Every new file starts with the ASF Apache-2.0 license header used across the repo (RAT-checked). Java files use the `/* ... */` header.
- Bar geometry is fixed: inner width **20** columns; `percent = doneProjects * 100 / totalProjects`; `filled = Math.round(percent / 100.0 * 20)`.
- Message constant, when added on `mvnd-1.x`, is `PROJECT_TEST_PROGRESS = 29` (both branches' `Message` constants currently end at `INPUT_DATA = 28`).
- Colors (spec Part A4): `> ` arrow **bold green**; module id **cyan**; goal text **green**; `IDLE` label **faint/dim**; test suffix **faint/dim** with `Failures`/`Errors` counts **red** when nonzero.
- Rendering changes must not break existing `integration-tests` that assert on client output; those run per branch.
- **Master prerequisite (Part C only):** the existing test-progress plan (`docs/superpowers/plans/2026-07-02-live-test-progress.md`) Task 1 (flag) and **Task 2 (`Message.ProjectTestProgressEvent`)** must be landed on the working branch **before** this plan's Task 5, because Task 5 consumes `Message.projectTestProgress(...)` and the `PROJECT_TEST_PROGRESS` type. That plan's **Task 7 (client render) is SUPERSEDED by Task 5 here and must be skipped**, so the merged `addProjectLine` is defined exactly once (spec Part C).
- **Branch asymmetry (surfaced deliberately, Rule 7):** on `master` the wire message is owned by the existing test-progress plan; on `mvnd-1.x` no such plan exists yet, so Task 7 of this plan adds the wire message to `mvnd-1.x`'s `common`. This plan does **not** touch any daemon/fork feed on either branch; the `mvnd-1.x` daemon feed remains a separate, spike-gated effort.

---

# Part 1 — master (Maven 4)

Branch: the current `feature/live-test-progress` (off `master`). Single file: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java`, plus a new test `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java`.

Line anchors below are from `master` as read at plan time (`TerminalOutput.java`): `addStatusLine` at 674-725, `addProjectLine` at 727-760, the filler loop in `update()` at 539-541, `Project` at 140-148, `MOJO_STARTED` case at 267-272, the `doAccept` switch default at 417.

---

### Task 1: `renderBar(int percent)` helper

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java`
- Test: `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java` (create)

**Interfaces:**
- Produces: `static String TerminalOutput.renderBar(int percent)` returning the 22-char bar string (`[` + 20 inner + `]`).

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mvndaemon.mvnd.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalOutputTest {

    @Test
    void renderBarZero() {
        assertEquals("[                    ]", TerminalOutput.renderBar(0));
    }

    @Test
    void renderBarPartial() {
        // 30% -> filled = round(6.0) = 6 -> 5 '=' + '>' + 14 spaces
        assertEquals("[=====>              ]", TerminalOutput.renderBar(30));
    }

    @Test
    void renderBarFull() {
        assertEquals("[====================]", TerminalOutput.renderBar(100));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: FAIL, `TerminalOutput.renderBar` does not exist.

- [ ] **Step 3: Add the helper**

In `TerminalOutput.java`, add near the other private static helpers (e.g. just above `addStatusLine` at line 674):

```java
    static String renderBar(int percent) {
        final int width = 20;
        int filled = (int) Math.round(percent / 100.0 * width);
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        if (filled >= width) {
            for (int i = 0; i < width; i++) {
                sb.append('=');
            }
        } else if (filled > 0) {
            for (int i = 0; i < filled - 1; i++) {
                sb.append('=');
            }
            sb.append('>');
            for (int i = 0; i < width - filled; i++) {
                sb.append(' ');
            }
        } else {
            for (int i = 0; i < width; i++) {
                sb.append(' ');
            }
        }
        sb.append(']');
        return sb.toString();
    }
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java
git commit -m "feat: add renderBar helper for Gradle-style progress bar"
```

---

### Task 2: Progress-bar status line

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java` (`addStatusLine`, 674-725)

**Interfaces:**
- Consumes: `renderBar(int)` (Task 1).

- [ ] **Step 1: Prepend the bar in the `name != null` branch**

In `addStatusLine`, the `if (name != null)` block currently begins with `asb.append("Building ");` (line 678). Replace that single line with:

```java
                int percent = doneProjects * 100 / totalProjects;
                asb.append(renderBar(percent))
                        .append(' ')
                        .append(String.format("%3d", percent))
                        .append("% ");
                asb.append("Building ");
```

- [ ] **Step 2: Remove the now-duplicated standalone percent from the progress section**

In the same method, the `progress:` section (lines 702-710) ends with these three lines that must be removed:

```java
                        .append(' ')
                        .append(String.format("%3d", doneProjects * 100 / totalProjects))
                        .append('%')
```

After removal the progress section reads:

```java
                /* Progress */
                asb.append("  progress: ")
                        .style(AttributedStyle.BOLD)
                        .append(String.format(projectsDoneFomat, doneProjects))
                        .append('/')
                        .append(String.valueOf(totalProjects))
                        .style(AttributedStyle.DEFAULT);
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw -o -q -pl common -am install -DskipTests -Drat.skip=true`
Expected: BUILD SUCCESS. Resulting line shape: `[=====>              ]  30% Building foo  daemon: ...  progress: 12/40  time: 00:23`.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java
git commit -m "feat: prepend drawn progress bar to the status line"
```

---

### Task 3: Concise worker lines

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java` (style constants near 91-92; `addProjectLine`, 727-760)

**Interfaces:**
- Produces: `private static final AttributedStyle BOLD_GREEN_FOREGROUND` (reused by Task 4).

- [ ] **Step 1: Add the bold-green style constant**

After the existing constants (lines 91-92, `GREEN_FOREGROUND` / `CYAN_FOREGROUND`), add:

```java
    private static final AttributedStyle BOLD_GREEN_FOREGROUND =
            new AttributedStyle().bold().foreground(AttributedStyle.GREEN);
```

- [ ] **Step 2: Rewrite `addProjectLine` to the concise arrow style**

Replace the whole body of `addProjectLine` (727-760) with:

```java
    private void addProjectLine(final List<AttributedString> lines, Project prj) {
        final MojoStartedEvent execution = prj.runningExecution;
        final AttributedStringBuilder asb = new AttributedStringBuilder();
        asb.style(BOLD_GREEN_FOREGROUND).append("> ").style(AttributedStyle.DEFAULT);
        AttributedString transfer = formatTransfers(prj.id);
        if (transfer != null) {
            asb.style(CYAN_FOREGROUND)
                    .append(':')
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(AttributedStyle.DEFAULT)
                    .append(transfer);
        } else if (execution == null) {
            asb.style(CYAN_FOREGROUND).append(':').append(prj.id).style(AttributedStyle.DEFAULT);
        } else {
            asb.style(CYAN_FOREGROUND)
                    .append(':')
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(GREEN_FOREGROUND);
            if (execution.getPluginGoalPrefix().isEmpty()) {
                asb.append(execution.getPluginArtifactId());
            } else {
                asb.append(execution.getPluginGoalPrefix());
            }
            asb.append(':')
                    .append(execution.getMojo())
                    .append(' ')
                    .style(AttributedStyle.DEFAULT)
                    .append('(')
                    .append(execution.getExecutionId())
                    .append(')');
            // Part C test-progress suffix appended here in Task 5.
        }
        lines.add(asb.toAttributedString());
    }
```

Note the deliberate change versus today: the verbose `groupId:artifactId:version:mojo` is dropped. The goal token is `pluginGoalPrefix` when present, otherwise `pluginArtifactId`, then `:mojo`, then ` (executionId)`.

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw -o -q -pl common -am install -DskipTests -Drat.skip=true`
Expected: BUILD SUCCESS. Line shape: `> :core compiler:compile (default-compile)`.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java
git commit -m "feat: concise arrow-style worker lines"
```

---

### Task 4: Dimmed `> IDLE` slots

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java` (filler loop in `update()`, 539-541)

**Interfaces:**
- Consumes: `BOLD_GREEN_FOREGROUND` (Task 3).

- [ ] **Step 1: Replace the blank filler loop with idle lines**

In `update()`, inside the `if (projectsCount <= dispLines)` branch, replace:

```java
            while (remLogLines-- > 0 && lines.size() <= maxThreads + 1) {
                lines.add(AttributedString.EMPTY);
            }
```

with:

```java
            final AttributedString idleLine = new AttributedStringBuilder()
                    .style(BOLD_GREEN_FOREGROUND)
                    .append("> ")
                    .style(AttributedStyle.DEFAULT.faint())
                    .append("IDLE")
                    .style(AttributedStyle.DEFAULT)
                    .toAttributedString();
            int idleSlots = maxThreads - projectsCount;
            while (idleSlots-- > 0 && remLogLines-- > 0 && lines.size() <= maxThreads + 1) {
                lines.add(idleLine);
            }
```

This shows at most `maxThreads - projectsCount` idle lines (spec A3). The `else` branch (threads hidden) is untouched, so no idle lines appear when rows are constrained.

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw -o -q -pl common -am install -DskipTests -Drat.skip=true`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java
git commit -m "feat: dimmed IDLE slots for free worker threads"
```

---

### Task 5: Merged worker line (Part C, the intersection)

**Prerequisite:** existing test-progress plan Task 2 landed on this branch (provides `Message.PROJECT_TEST_PROGRESS`, `Message.ProjectTestProgressEvent`, `Message.projectTestProgress(...)`). That plan's Task 7 is superseded by this task.

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java` (`Project` 140-148; `MOJO_STARTED` case 267-272; `doAccept` switch; `addProjectLine`)
- Test: `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java`

**Interfaces:**
- Consumes: `Message.ProjectTestProgressEvent` with `getProjectId()`, `getTestClass()` (nullable), `getTestMethod()` (nullable), `getCompleted()`, `getFailures()`, `getErrors()`, `getSkipped()`; factory `Message.projectTestProgress(String, String, String, int, int, int, int)`.
- Produces: `static void TerminalOutput.appendTestProgress(AttributedStringBuilder asb, Message.ProjectTestProgressEvent tp)`.

- [ ] **Step 1: Write the failing suffix-formatter tests**

Add to `TerminalOutputTest.java` (add the imports `org.jline.utils.AttributedString`, `org.jline.utils.AttributedStringBuilder`, `org.jline.utils.AttributedStyle`, `org.mvndaemon.mvnd.common.Message`):

```java
    @Test
    void suffixAllPassing() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 12, 0, 0, 0));
        assertEquals(" [Tests: 12] FooTest#shouldWork", asb.toAttributedString().toString());
    }

    @Test
    void suffixFailuresRenderRed() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 12, 1, 0, 0));
        AttributedString s = asb.toAttributedString();
        assertEquals(" [Tests: 12, Failures: 1] FooTest#shouldWork", s.toString());
        int failureDigit = s.toString().indexOf("Failures: ") + "Failures: ".length();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), s.styleAt(failureDigit));
        int bracket = s.toString().indexOf('[');
        assertEquals(AttributedStyle.DEFAULT.faint(), s.styleAt(bracket));
    }

    @Test
    void suffixErrorsAndSkips() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 5, 0, 2, 1));
        assertEquals(" [Tests: 5, Errors: 2, Skipped: 1] FooTest#shouldWork", asb.toAttributedString().toString());
    }

    @Test
    void suffixClassOnly() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", null, 3, 0, 0, 0));
        assertEquals(" [Tests: 3] FooTest", asb.toAttributedString().toString());
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: FAIL, `TerminalOutput.appendTestProgress` does not exist.

- [ ] **Step 3: Add the `testProgress` field to the client-side `Project`**

In the `Project` static class (140-148), add below `MojoStartedEvent runningExecution;`:

```java
        Message.ProjectTestProgressEvent testProgress;
```

- [ ] **Step 4: Handle `PROJECT_TEST_PROGRESS` in `doAccept`**

In the `doAccept` switch (before `default:` at line 417), add:

```java
            case Message.PROJECT_TEST_PROGRESS: {
                final Message.ProjectTestProgressEvent e = (Message.ProjectTestProgressEvent) entry;
                final Project prj = projects.get(e.getProjectId());
                if (prj != null) {
                    prj.testProgress = e;
                }
                break;
            }
```

- [ ] **Step 5: Clear stale progress when a new mojo starts**

In the `MOJO_STARTED` case (267-272), after `prj.runningExecution = execution;`, add:

```java
                prj.testProgress = null;
```

(Project finish needs no explicit clear: `PROJECT_STOPPED` at 273-283 removes the project from the map.)

- [ ] **Step 6: Add the `appendTestProgress` helper**

Add near `addProjectLine`:

```java
    static void appendTestProgress(AttributedStringBuilder asb, Message.ProjectTestProgressEvent tp) {
        final AttributedStyle faint = AttributedStyle.DEFAULT.faint();
        final AttributedStyle red = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
        asb.append(' ').style(faint).append("[Tests: ").append(String.valueOf(tp.getCompleted()));
        if (tp.getFailures() > 0) {
            asb.style(faint).append(", Failures: ").style(red).append(String.valueOf(tp.getFailures()));
        }
        if (tp.getErrors() > 0) {
            asb.style(faint).append(", Errors: ").style(red).append(String.valueOf(tp.getErrors()));
        }
        if (tp.getSkipped() > 0) {
            asb.style(faint).append(", Skipped: ").append(String.valueOf(tp.getSkipped()));
        }
        asb.style(faint).append("]");
        final String testClass = tp.getTestClass();
        if (testClass != null) {
            final String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            asb.append(' ').append(simple);
            if (tp.getTestMethod() != null) {
                asb.append('#').append(tp.getTestMethod());
            }
        }
        asb.style(AttributedStyle.DEFAULT);
    }
```

- [ ] **Step 7: Append the suffix in `addProjectLine`**

Replace the `// Part C test-progress suffix appended here in Task 5.` comment (added in Task 3, inside the `else` branch after the execution id) with:

```java
            final Message.ProjectTestProgressEvent tp = prj.testProgress;
            if (tp != null) {
                appendTestProgress(asb, tp);
            }
```

- [ ] **Step 8: Run tests to confirm pass**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: PASS (renderBar + suffix tests). Then confirm the module still builds: `./mvnw -o -q -pl common -am install -DskipTests -Drat.skip=true`.

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java
git commit -m "feat: append live test-progress suffix to the concise worker line"
```

---

### Task 6: master verification

**Files:** none (verification only).

- [ ] **Step 1: Full common tests + RAT**

Run: `./mvnw -o -q -pl common install -Drat.skip=false`
Expected: BUILD SUCCESS; RAT passes (new test file has the ASF header).

- [ ] **Step 2: Rendering integration tests still pass**

Run: `./mvnw -pl integration-tests test -Dtest=MultiModuleTest`
Expected: PASS (client output still parses; the arrow/bar reshaping did not break message-driven assertions).

- [ ] **Step 3: Manual smoke**

Build the dist, run `mvnd install` on a multi-module JUnit 5 project in a real (non-dumb) terminal. Confirm: the drawn bar with percent leads the status line; worker lines read `> :module goal (execution)`; free slots show dimmed `> IDLE`; while surefire runs (with the existing test-progress plan landed) the line gains ` [Tests: N ...] Class#method`, and it clears when the mojo finishes.

---

# Part 2 — mvnd-1.x (Maven 3.9)

The `mvnd-1.x` rendering methods are the same shape as `master` with two cosmetic differences in `addStatusLine`: the threads value is built with a `StringBuilder` (not string concatenation), and the second guard is `else if (buildStatus != null)` (not `else`). `addProjectLine` and the `update()` filler loop are byte-identical to `master`. Line anchors from `origin/mvnd-1.x` at plan time: `Project` 147-155; `MOJO_STARTED` sets `runningExecution` at 281; filler loop 629-630; `addStatusLine` 756-809; `addProjectLine` 811-844. In `Message.java` on `mvnd-1.x`: `read()` switch at 80 (`case DISPLAY:` at 90), `getClassOrder` at 138 (`case DISPLAY:` at 147), `readUTF`/`writeUTF` helpers at 237/303, constants end at `INPUT_DATA = 28`.

Work on `mvnd-1.x` in an isolated worktree so the `master` feature branch is undisturbed.

---

### Task 7: Create the mvnd-1.x working branch

**Files:** none (git only).

- [ ] **Step 1: Create a worktree and branch off `origin/mvnd-1.x`**

```bash
git fetch origin
git worktree add ../mvnd-1x-gradle-display -b feature/gradle-display-1x origin/mvnd-1.x
```

- [ ] **Step 2: Confirm the checkout**

Run: `cd ../mvnd-1x-gradle-display && git log --oneline -1`
Expected: shows the `origin/mvnd-1.x` tip. All subsequent Part 2 steps run from this worktree.

---

### Task 8: `PROJECT_TEST_PROGRESS` message on mvnd-1.x

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/Message.java`
- Test: `common/src/test/java/org/mvndaemon/mvnd/common/MessageTest.java`

**Interfaces:**
- Produces: `Message.PROJECT_TEST_PROGRESS = 29`; `Message.ProjectTestProgressEvent` with `getProjectId()`, `getTestClass()`, `getTestMethod()`, `getCompleted()`, `getFailures()`, `getErrors()`, `getSkipped()`; factory `Message.projectTestProgress(String, String, String, int, int, int, int)`; helpers `writeNullableUTF`/`readNullableUTF`.

- [ ] **Step 1: Write the failing round-trip tests**

Add to `MessageTest.java` (it already imports the `java.io` stream types used by the other round-trip tests):

```java
    @Test
    void projectTestProgressSerialization() throws IOException {
        Message msg = Message.projectTestProgress("my-app", "com.acme.FooTest", "shouldWork", 3, 1, 0, 1);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream daos = new DataOutputStream(baos)) {
            msg.write(daos);
        }
        Message msg2;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            msg2 = Message.read(dis);
        }

        assertTrue(msg2 instanceof Message.ProjectTestProgressEvent);
        Message.ProjectTestProgressEvent e = (Message.ProjectTestProgressEvent) msg2;
        assertEquals("my-app", e.getProjectId());
        assertEquals("com.acme.FooTest", e.getTestClass());
        assertEquals("shouldWork", e.getTestMethod());
        assertEquals(3, e.getCompleted());
        assertEquals(1, e.getFailures());
        assertEquals(0, e.getErrors());
        assertEquals(1, e.getSkipped());
    }

    @Test
    void projectTestProgressNullClassAndMethod() throws IOException {
        Message msg = Message.projectTestProgress("my-app", null, null, 0, 0, 0, 0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream daos = new DataOutputStream(baos)) {
            msg.write(daos);
        }
        Message msg2;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            msg2 = Message.read(dis);
        }
        Message.ProjectTestProgressEvent e = (Message.ProjectTestProgressEvent) msg2;
        assertNull(e.getTestClass());
        assertNull(e.getTestMethod());
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o -q -pl common test -Dtest=MessageTest -Drat.skip=true`
Expected: FAIL, `Message.projectTestProgress` / `ProjectTestProgressEvent` do not exist.

- [ ] **Step 3: Add the constant**

After `public static final int INPUT_DATA = 28;` (line 67):

```java
    public static final int PROJECT_TEST_PROGRESS = 29;
```

- [ ] **Step 4: Wire into `Message.read`**

In the `switch (type)` inside `read(DataInputStream)` (switch at 80), alongside `case DISPLAY:` (90), add:

```java
            case PROJECT_TEST_PROGRESS:
                return ProjectTestProgressEvent.read(input);
```

- [ ] **Step 5: Add to `getClassOrder`**

In `getClassOrder` (138), add `case PROJECT_TEST_PROGRESS:` to the same group as `case DISPLAY:` (147) so transient display updates sort together:

```java
            case DISPLAY:
            case PROJECT_TEST_PROGRESS:
```

- [ ] **Step 6: Add the null-aware UTF helpers**

Near the existing `writeUTF`/`readUTF` helpers (237/303):

```java
    static void writeNullableUTF(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeUTF(output, value);
        }
    }

    static String readNullableUTF(DataInputStream input) throws IOException {
        return input.readBoolean() ? readUTF(input) : null;
    }
```

- [ ] **Step 7: Add the nested event class and factory**

Add the nested class near the other event classes (e.g. after the `MojoStartedEvent` class). Add `import java.util.Objects;` at the top if not already present:

```java
    public static class ProjectTestProgressEvent extends Message {
        final String projectId;
        final String testClass;
        final String testMethod;
        final int completed;
        final int failures;
        final int errors;
        final int skipped;

        public static ProjectTestProgressEvent read(DataInputStream input) throws IOException {
            final String projectId = readUTF(input);
            final String testClass = readNullableUTF(input);
            final String testMethod = readNullableUTF(input);
            final int completed = input.readInt();
            final int failures = input.readInt();
            final int errors = input.readInt();
            final int skipped = input.readInt();
            return new ProjectTestProgressEvent(
                    projectId, testClass, testMethod, completed, failures, errors, skipped);
        }

        public ProjectTestProgressEvent(
                String projectId,
                String testClass,
                String testMethod,
                int completed,
                int failures,
                int errors,
                int skipped) {
            super(PROJECT_TEST_PROGRESS);
            this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.completed = completed;
            this.failures = failures;
            this.errors = errors;
            this.skipped = skipped;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getTestClass() {
            return testClass;
        }

        public String getTestMethod() {
            return testMethod;
        }

        public int getCompleted() {
            return completed;
        }

        public int getFailures() {
            return failures;
        }

        public int getErrors() {
            return errors;
        }

        public int getSkipped() {
            return skipped;
        }

        @Override
        public void write(DataOutputStream output) throws IOException {
            super.write(output);
            writeUTF(output, projectId);
            writeNullableUTF(output, testClass);
            writeNullableUTF(output, testMethod);
            output.writeInt(completed);
            output.writeInt(failures);
            output.writeInt(errors);
            output.writeInt(skipped);
        }

        @Override
        public String toString() {
            return "ProjectTestProgress{projectId='" + projectId + "', testClass='" + testClass + "', testMethod='"
                    + testMethod + "', completed=" + completed + ", failures=" + failures + ", errors=" + errors
                    + ", skipped=" + skipped + "}";
        }
    }

    public static ProjectTestProgressEvent projectTestProgress(
            String projectId, String testClass, String testMethod, int completed, int failures, int errors,
            int skipped) {
        return new ProjectTestProgressEvent(projectId, testClass, testMethod, completed, failures, errors, skipped);
    }
```

- [ ] **Step 8: Run tests to confirm pass**

Run: `./mvnw -o -q -pl common test -Dtest=MessageTest -Drat.skip=true`
Expected: PASS (new + existing).

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/Message.java common/src/test/java/org/mvndaemon/mvnd/common/MessageTest.java
git commit -m "feat: add PROJECT_TEST_PROGRESS message"
```

---

### Task 9: mvnd-1.x rendering port + merged worker line

**Files:**
- Modify: `common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java`
- Test: `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java` (create)

**Interfaces:**
- Consumes: `Message.ProjectTestProgressEvent` and `Message.projectTestProgress(...)` (Task 8).
- Produces: `static String renderBar(int)`, `static void appendTestProgress(AttributedStringBuilder, Message.ProjectTestProgressEvent)`.

- [ ] **Step 1: Write the failing tests**

Create `common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java` with the ASF header and the same test body used on `master`:

```java
package org.mvndaemon.mvnd.common.logging;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mvndaemon.mvnd.common.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalOutputTest {

    @Test
    void renderBarZero() {
        assertEquals("[                    ]", TerminalOutput.renderBar(0));
    }

    @Test
    void renderBarPartial() {
        assertEquals("[=====>              ]", TerminalOutput.renderBar(30));
    }

    @Test
    void renderBarFull() {
        assertEquals("[====================]", TerminalOutput.renderBar(100));
    }

    @Test
    void suffixAllPassing() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 12, 0, 0, 0));
        assertEquals(" [Tests: 12] FooTest#shouldWork", asb.toAttributedString().toString());
    }

    @Test
    void suffixFailuresRenderRed() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 12, 1, 0, 0));
        AttributedString s = asb.toAttributedString();
        assertEquals(" [Tests: 12, Failures: 1] FooTest#shouldWork", s.toString());
        int failureDigit = s.toString().indexOf("Failures: ") + "Failures: ".length();
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED), s.styleAt(failureDigit));
        int bracket = s.toString().indexOf('[');
        assertEquals(AttributedStyle.DEFAULT.faint(), s.styleAt(bracket));
    }

    @Test
    void suffixErrorsAndSkips() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", "shouldWork", 5, 0, 2, 1));
        assertEquals(" [Tests: 5, Errors: 2, Skipped: 1] FooTest#shouldWork", asb.toAttributedString().toString());
    }

    @Test
    void suffixClassOnly() {
        AttributedStringBuilder asb = new AttributedStringBuilder();
        TerminalOutput.appendTestProgress(
                asb, Message.projectTestProgress("app", "com.acme.FooTest", null, 3, 0, 0, 0));
        assertEquals(" [Tests: 3] FooTest", asb.toAttributedString().toString());
    }
}
```

Prepend the ASF Java header (identical to the block shown in Part 1 Task 1 Step 1).

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: FAIL, `renderBar` / `appendTestProgress` do not exist.

- [ ] **Step 3: Add the bold-green style constant**

After the `GREEN_FOREGROUND` / `CYAN_FOREGROUND` constants (95-96):

```java
    private static final AttributedStyle BOLD_GREEN_FOREGROUND =
            new AttributedStyle().bold().foreground(AttributedStyle.GREEN);
```

- [ ] **Step 4: Add `renderBar`**

Add just above `addStatusLine` (756):

```java
    static String renderBar(int percent) {
        final int width = 20;
        int filled = (int) Math.round(percent / 100.0 * width);
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        if (filled >= width) {
            for (int i = 0; i < width; i++) {
                sb.append('=');
            }
        } else if (filled > 0) {
            for (int i = 0; i < filled - 1; i++) {
                sb.append('=');
            }
            sb.append('>');
            for (int i = 0; i < width - filled; i++) {
                sb.append(' ');
            }
        } else {
            for (int i = 0; i < width; i++) {
                sb.append(' ');
            }
        }
        sb.append(']');
        return sb.toString();
    }
```

- [ ] **Step 5: Prepend the bar in `addStatusLine` and drop the standalone percent**

In `addStatusLine` (756), the `if (name != null)` block begins with `asb.append("Building ");` (760). Replace that line with:

```java
                int percent = doneProjects * 100 / totalProjects;
                asb.append(renderBar(percent))
                        .append(' ')
                        .append(String.format("%3d", percent))
                        .append("% ");
                asb.append("Building ");
```

Then remove the three trailing lines of the `progress:` section (791-793):

```java
                        .append(' ')
                        .append(String.format("%3d", doneProjects * 100 / totalProjects))
                        .append('%')
```

so the section ends with `.append(String.valueOf(totalProjects)).style(AttributedStyle.DEFAULT);`. Leave the `else if (buildStatus != null)` guard (796) and the `StringBuilder`-based threads value (776-782) exactly as they are.

- [ ] **Step 6: Rewrite `addProjectLine` to the concise arrow style with the suffix hook**

Replace the whole body of `addProjectLine` (811-844) with:

```java
    private void addProjectLine(final List<AttributedString> lines, Project prj) {
        final MojoStartedEvent execution = prj.runningExecution;
        final AttributedStringBuilder asb = new AttributedStringBuilder();
        asb.style(BOLD_GREEN_FOREGROUND).append("> ").style(AttributedStyle.DEFAULT);
        AttributedString transfer = formatTransfers(prj.id);
        if (transfer != null) {
            asb.style(CYAN_FOREGROUND)
                    .append(':')
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(AttributedStyle.DEFAULT)
                    .append(transfer);
        } else if (execution == null) {
            asb.style(CYAN_FOREGROUND).append(':').append(prj.id).style(AttributedStyle.DEFAULT);
        } else {
            asb.style(CYAN_FOREGROUND)
                    .append(':')
                    .append(String.format(artifactIdFormat, prj.id))
                    .style(GREEN_FOREGROUND);
            if (execution.getPluginGoalPrefix().isEmpty()) {
                asb.append(execution.getPluginArtifactId());
            } else {
                asb.append(execution.getPluginGoalPrefix());
            }
            asb.append(':')
                    .append(execution.getMojo())
                    .append(' ')
                    .style(AttributedStyle.DEFAULT)
                    .append('(')
                    .append(execution.getExecutionId())
                    .append(')');
            final Message.ProjectTestProgressEvent tp = prj.testProgress;
            if (tp != null) {
                appendTestProgress(asb, tp);
            }
        }
        lines.add(asb.toAttributedString());
    }
```

- [ ] **Step 7: Add `appendTestProgress`**

Add near `addProjectLine`:

```java
    static void appendTestProgress(AttributedStringBuilder asb, Message.ProjectTestProgressEvent tp) {
        final AttributedStyle faint = AttributedStyle.DEFAULT.faint();
        final AttributedStyle red = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
        asb.append(' ').style(faint).append("[Tests: ").append(String.valueOf(tp.getCompleted()));
        if (tp.getFailures() > 0) {
            asb.style(faint).append(", Failures: ").style(red).append(String.valueOf(tp.getFailures()));
        }
        if (tp.getErrors() > 0) {
            asb.style(faint).append(", Errors: ").style(red).append(String.valueOf(tp.getErrors()));
        }
        if (tp.getSkipped() > 0) {
            asb.style(faint).append(", Skipped: ").append(String.valueOf(tp.getSkipped()));
        }
        asb.style(faint).append("]");
        final String testClass = tp.getTestClass();
        if (testClass != null) {
            final String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            asb.append(' ').append(simple);
            if (tp.getTestMethod() != null) {
                asb.append('#').append(tp.getTestMethod());
            }
        }
        asb.style(AttributedStyle.DEFAULT);
    }
```

- [ ] **Step 8: Add the `testProgress` field, the message handler, and the clear-on-mojo**

In the `Project` class (147-155), add below `MojoStartedEvent runningExecution;`:

```java
        Message.ProjectTestProgressEvent testProgress;
```

In the `doAccept` switch, add the handler (place it alongside the other display-updating cases, before `default:`):

```java
            case Message.PROJECT_TEST_PROGRESS: {
                final Message.ProjectTestProgressEvent e = (Message.ProjectTestProgressEvent) entry;
                final Project prj = projects.get(e.getProjectId());
                if (prj != null) {
                    prj.testProgress = e;
                }
                break;
            }
```

In the `MOJO_STARTED` case, after `prj.runningExecution = execution;` (281), add:

```java
                prj.testProgress = null;
```

- [ ] **Step 9: Replace the blank filler loop with idle lines**

In `update()`, replace (629-630):

```java
            while (remLogLines-- > 0 && lines.size() <= maxThreads + 1) {
                lines.add(AttributedString.EMPTY);
            }
```

with:

```java
            final AttributedString idleLine = new AttributedStringBuilder()
                    .style(BOLD_GREEN_FOREGROUND)
                    .append("> ")
                    .style(AttributedStyle.DEFAULT.faint())
                    .append("IDLE")
                    .style(AttributedStyle.DEFAULT)
                    .toAttributedString();
            int idleSlots = maxThreads - projectsCount;
            while (idleSlots-- > 0 && remLogLines-- > 0 && lines.size() <= maxThreads + 1) {
                lines.add(idleLine);
            }
```

- [ ] **Step 10: Run tests to confirm pass**

Run: `./mvnw -o -q -pl common test -Dtest=TerminalOutputTest -Drat.skip=true`
Expected: PASS. Then confirm the module builds: `./mvnw -o -q -pl common -am install -DskipTests -Drat.skip=true`.

- [ ] **Step 11: Commit**

```bash
git add common/src/main/java/org/mvndaemon/mvnd/common/logging/TerminalOutput.java common/src/test/java/org/mvndaemon/mvnd/common/logging/TerminalOutputTest.java
git commit -m "feat: Gradle-style rendering and test-progress suffix on mvnd-1.x"
```

---

### Task 10: mvnd-1.x verification

**Files:** none (verification only).

- [ ] **Step 1: Full common tests + RAT (from the 1.x worktree)**

Run: `./mvnw -o -q -pl common install -Drat.skip=false`
Expected: BUILD SUCCESS; RAT passes.

- [ ] **Step 2: Rendering integration tests still pass**

Run: `./mvnw -pl integration-tests test -Dtest=MultiModuleTest`
Expected: PASS.

- [ ] **Step 3: Note the deferred daemon feed**

On `mvnd-1.x` the wire message and client render now exist, but no daemon feed emits `PROJECT_TEST_PROGRESS` yet, so the suffix stays dormant until the separate, spike-gated 1.x test-progress feed lands (out of scope here). The bar, concise worker lines, and idle slots are fully live.

---

## Notes carried from the spec

- The rendering change is presentation-only. Do not touch buffering modes, keybindings, or the dumb path (spec "Out of scope").
- No total/percentage for tests, and no feeding test counts into the bar; the bar stays module-completion based (spec Part B out-of-scope).
- The `> IDLE` slots appear only in the `projectsCount <= dispLines` branch of `update()`; the threads-hidden branch shows none (spec A3).
- `addStatusLine` on `mvnd-1.x` keeps its `StringBuilder` threads value and `else if (buildStatus != null)` guard; only the bar prefix and the removed standalone percent change (spec "Rendering, Part A + C").
