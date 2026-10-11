---
name: ehome-workflow
description: The working method for developing E-Home — orchestrating sub-agents in the single TopLogic Maven module, adversarial probe reviews, the verification gate (checkstyle plus the full test run), the run loop against the local app with browser checks, GitHub-issue triage, TopLogic-gap bookkeeping (Trac tickets and doc/tl-todo.md), the commit discipline, and the model/import doctrines. Load at the start of any session doing feature or bug work on this repository.
---

# The E-Home working method

This skill is the meta-knowledge of how a session on this repository should run. It complements,
never duplicates, `CLAUDE.md` (build facts, conventions, generated code, the TL view layer),
`doc/tl-todo.md` (what waits for a newer TopLogic, with the TL Trac ticket numbers) and
`doc/knx/schema-coverage.md` (which parts of the KNX project schema the model covers). Read all
three first. The GitHub issue tracker at `haumacher/ehome` **is** the work queue (§1 and §8); there
is no roadmap file — direction lives in issues and commit messages.

## 1. The operating loop

Work is a pipeline, not a pile:

1. **Intake**: user reports (chat, screenshots, ETS XML snippets, GitHub issues) and the open
   issues at `haumacher/ehome`. Bugs outrank queued features. Triage every new GitHub issue with a
   diagnosis comment *before* fixing; use the repository's labels (`bug`, `enhancement`,
   `question`, `documentation` — the GitHub defaults; do not invent new ones without the user).
2. **Reproduce before dispatching.** An import or model report gets a headless reproduction
   first: a JUnit test in the `TestEtsImport` harness against `samples/Test.knxproj` or a fixture
   in `src/test/fixtures/` (§4 for the traps). A UI report gets a look at the running app
   (§7) — a Playwright snapshot of the view, not a guess from the `.view.xml`. Know whether it is
   a real bug, an engine gap already recorded in `doc/tl-todo.md`, or your own probe error before
   an agent burns tokens. If the main tree is agent-owned, reproduce in a git worktree at HEAD
   (`git worktree add <scratch>/repro <commit>`), never in the live tree.
3. **Dispatch** one sub-agent per coherent package (§2). Sequential by default; parallel only with
   disjoint file ownership stated in both briefs (§3). The natural split is importer/Java versus
   views/configuration — but see §3 on why one Maven module makes parallel work rare.
4. **Review with a novel probe** (§4). Non-negotiable.
5. **Gate** (§5), **commit to master and push** (§6), **restart the app and check it** (§7),
   close the issue with the fixing commit plus a summary comment naming the regression test.
6. **Before ever finishing: `gh issue list -R haumacher/ehome`.** Stop only when the issue list
   holds nothing actionable. Anything you defer becomes an issue (or, for an engine gap, a TL
   ticket plus a `doc/tl-todo.md` entry), so a crashed session loses nothing.

Track packages with TaskCreate/TaskUpdate; keep statuses honest (in_progress on dispatch,
completed only after the commit is pushed).

## 2. Agent briefs — what makes them work

Use Opus for feature/bug packages (standing directive: Opus or cheaper; Sonnet only for genuinely
mechanical chores). Run in background; you'll be notified. A brief that produces a good delivery
contains, in order:

- **Grounding**: "Read CLAUDE.md, doc/tl-todo.md and doc/knx/schema-coverage.md. Study <the
  specific packages, classes, model files, views and tests that are the behavioral contract>."
  Name where the work lives — `de.haumacher.ehome.importer` and `etsImporter.xml` (the ETS
  import), `de.haumacher.ehome.knx` (group addresses, styles), `de.haumacher.ehome.discover`
  (Calimero gateway discovery), `WEB-INF/model/ehome.*.model.xml` (the model),
  `WEB-INF/views/**` (the UI), `WEB-INF/conf/ehomeConf.config.xml` (TL-Script functions, command
  cliques, client resources) — and **whether a model file is in scope**, because that decides the
  regeneration cycle (CLAUDE.md "Generated code"). For view work the brief must say: read the
  `view-layer` chapter of the developer docs with the tl-mcp tools (`list_docs`, `read_doc`) and
  use `tl-demo-react` as the reference; for labels, the `i18n` article.
- **The task as design intent**, not implementation orders — with the load-bearing decisions
  made: what the model stores versus what a derived attribute computes, what the import reads
  from ETS versus what the user edits afterwards, what the view shows versus what the detail form
  edits, what refuses versus what heals. Quote the user's own words where they designed the
  feature; their design is usually better than your draft. Model the KNX concept in the app's own
  terms (schema-coverage.md), never as a copy of the ETS XML.
- **User fixtures embedded verbatim** as required regression tests: the ETS XML fragment, the
  expected label, the screenshot's content, the group address notation — whatever the report
  contained. Extend the fixture projects only through `src/test/fixtures/make-fixtures.py` (it
  derives `TestDetails.knxproj` and `TestDynamic.knxproj` from `samples/Test.knxproj` and names
  them as artificial test data); never hand-edit a `.knxproj` zip, never add a real customer
  project.
- **Explicit acceptance tests**, including exact values where computable (counts of imported
  objects per type, `extId`s, labels in both languages, group address strings per style).
  "import → read back the attribute → equals the fixture's value" belongs in almost every import
  brief; "import the same project twice → no duplicated shared objects" (TL #29797 workarounds)
  belongs in every brief that touches shared objects (catalogs, programs, translations).
- **The build discipline block** (the point is not wasting runs, not avoiding them):
  - The test gate is **one command and fast**: `./mvnw test -DskipTests=false` runs the whole
    suite (`test.TestAll` plus the conformance checks) in **about 17 s**; a targeted
    `-Dtest=TestEtsImport` saves two seconds because the KB setup dominates — so there is no
    reason to target, run the full suite every time. Without `-DskipTests=false` **nothing runs
    and the build is green** (the TopLogic parent skips tests by default).
  - `./mvnw checkstyle:check` after every Java edit (not bound to the build).
  - A **model change** costs a cycle: `./mvnw install -DskipTests` (add `-Dmaven.main.skip` when
    the old wrappers no longer compile), `./mvnw tl:generate-java`, build again. The generator
    reads the model from the artifact in `~/.m2`, not from the sources — an install *before*
    generating is not optional. Only `ehome.core` and `ehome.operational` have generated Java
    bindings (`pom.xml`, `tl-maven-plugin`); the other modules are reached through
    `tValueByName`/`tUpdateByName` and the test harness does the same.
  - `mvn` and `./mvnw` are the same Maven here (3.9.16, installed in `~/tools/maven` and first on
    the PATH); the distribution's 3.8.7 is below TopLogic 8's minimum, so if `mvn -v` ever shows
    it again, the PATH entry in `~/.bashrc`/`~/.profile` is missing.
  - A run whose inputs did not change is not repeated "to be sure". Batch independent lookups
    (several greps in one call; several `describe_type`/`show_source` calls in one turn).
- **The standing rules block**: ALL existing tests green; checkstyle clean; UTF-8 with umlauts
  written directly; tabs; the copyright header with the file's creation year; UI texts as `ResKey`
  constants in `I18NConstants` with an `@en` javadoc tag; **never hand-edit generated code**
  (`internal/*Base.java`, `*Constants.java`, `*Factory.java`) — edit the model XML; the plain
  `X.java`/`XImpl.java` wrappers are hand-written code; a changed `.view.xml` needs an app
  restart; access rights are deny-by-default (test as `root`); do NOT commit; **nothing
  half-done — cut whole items only, refuse in-app with a visible reason, and report every cut**;
  an engine limitation is not silently worked around: name it in the report so it becomes a TL
  ticket and a `doc/tl-todo.md` entry (§8).
- **Ask for a report**: mechanism chosen and why, alternatives rejected, cuts, engine gaps hit,
  test count before/after. The report is your review input; a vague report predicts a vague
  delivery.

**Reworks go back to the same agent** via SendMessage with the failing probe path and the demand:
*fix it generally, not to the probe*. If several rework rounds fail, question the architecture,
not the tests.

**Stalled agents are real.** If a background agent's transcript goes quiet: arm a Monitor on the
output file's mtime (10-minute quiet threshold, until-loop, not tail -f). **The tasks-dir output
path is a symlink — stat it with `-L`**, or the monitor reads the symlink's own never-changing
mtime and cries wolf in exact 600 s increments. Verify before acting on any event: a quiet
transcript with a busy `java` process is a TopLogic build booting its service stack, not a stall.
On silence: nudge-resume via SendMessage ("Resume exactly where you left off: <last visible
step>"). If the transcript stays frozen after a resume, the agent is dead — take the remainder
over yourself; its uncommitted work is usually further along than the last message suggests
(`git status`, then run the tests before redoing anything).

**Crash recovery.** The crashed session's transcript is
`~/.claude/projects/-home-haui-devel-ehome/<session-id>.jsonl`; its sub-agents' transcripts are
`<session-id>/subagents/agent-*.jsonl` beside it; `~/.claude/tasks/<session-id>/` holds the task
list. Pull the last `Agent` tool_use out of the session transcript with a short python filter —
that is the brief, verbatim — and the last tool calls out of the newest sub-agent transcript to see
where it died. The agent's uncommitted work is in the tree: `git status`, `./mvnw -q compile`,
then **re-dispatch the same brief with a resume preamble** stating exactly what exists, what
compiles, and what is not started; never redo the work. The app does **not** survive a crash
(`ss -ltnp | grep 8080`; restart per §7). Check `gh issue list` immediately — reports filed during
the crash are untriaged.

## 3. Concurrency — the rules

- **Never let two agents edit the same file.** When parallelizing, write an explicit FILE BOUNDARY
  paragraph into *both* briefs ("do not touch X, Y, Z; if you must, STOP and report").
- **The model XML has one owner per round.** A model change regenerates the Java bindings through
  `install` + `tl:generate-java`, and the install overwrites the one `1.0.0-SNAPSHOT` in `~/.m2`
  that every tree's generator reads. Whoever changes a model file owns the regeneration and the
  compile fallout; the other agent must not touch any model file and must not run `install`
  while the model is mid-edit. Model changes happen **only in the main tree**.
- **One Maven module means one `target/`.** Concurrent `./mvnw` runs in one tree collide. Never
  run Maven in a tree an agent is mid-edit in; give a second agent its own git worktree (§10) and
  have it use `./mvnw test -DskipTests=false` (never `install`) so it cannot overwrite the main
  tree's installed artifact.
- **One app instance per tree.** The running app holds the H2 database in `tmp/` and port 8080;
  a worktree that needs its own app starts it with `-Dtl.port=8081` and gets its own `tmp/`.
- A commit made while an agent has uncommitted work in the tree must be **selective**
  (`git add <files>`).

## 4. The probe review (the quality mechanism)

After a delivery reports done and before committing: **write a test the agent never saw**,
composing the new feature with pre-existing features — import `TestDetails.knxproj` *and*
`TestDynamic.knxproj` into one KB, re-import a project into existing data, read labels in `de`
and `en`, a parameter block without a title of its own, a group address in each style, a device
without a location, a conditional channel whose parameter takes the default case. Good probes ask
"is the mechanism general?" not "does the happy path work?". Keep passing probes as permanent
tests (named `Test<Feature>Probe`, in package `test.<package>` with a `suite()`, wrapped like
`TestEtsImport` when they need the KB).

**Expect your own probes to be wrong first.** The recurring traps — check these before blaming the
delivery:

- **Nothing ran.** `./mvnw test` without `-DskipTests=false` is green with zero tests. Read the
  `Tests run:` line, not the banner.
- **Derived attributes read as `null`** in a test: a `<storage-algorithm><query>` evaluates with
  the reading user's access rights, and a test runs without a person. Read them as `root`
  (`TestEtsImport.derived`). The same applies in the app: a non-admin user sees nothing until the
  `ehome.*` modules have grants (`access-configuration` article) — an empty view for a test user
  is rights, not the feature.
- **Stale generated code.** After a model change the wrappers are regenerated from the `~/.m2`
  artifact; a probe compiled against the old shape fails to compile, which is the build telling
  you to run the install → generate cycle (CLAUDE.md), not a defect. A test that behaves as if a
  model change never happened is running against an old install.
- **The app shows the old view.** A changed `.view.xml` (and `ehomeConf.config.xml`) is not
  picked up by a running app; restart before probing the UI (§7).
- **Hygiene failures look like feature failures.** `test.TestAll` adds TopLogic conformance
  checks: copyright comment (`TestComment`, pattern in `src/test/webapp/WEB-INF/conf/
  ehome-testSettings.config.xml`), normalized layouts, JSPs, I18N completeness. A red suite after
  a feature may be a missing header, an `@en` tag, or an unused resource key — fix the hygiene,
  then judge the feature.
- **The fixtures are generated.** `TestDetails.knxproj` and `TestDynamic.knxproj` come from
  `make-fixtures.py`; a probe that needs a new ETS construct extends the script (and re-runs it
  from the project root), never edits the zip. A fixture that changes an application program must
  give it a new ID and a name marking it as test data (as `TestDynamic` does), or the import
  skips the program as already known and the probe tests the old one.
- **Re-import is a feature, not a bug.** Importing a project again into existing data must reuse
  shared objects by `extId` (`Importer.registerSharedObjects`, `<unless-known>`). A probe that
  counts doubled catalog entries after two imports found a real p0; a probe that expects a second
  *project* object after importing the same file twice may be probing the design — read
  `doc/tl-todo.md` (TL #29797) before judging.
- **The tl-app script picks its own port.** `tl-app.sh` (skill `tl-app`) starts the app on a
  random free port unless `tmp/app-port.txt` names one, and records the port there; read the
  `url:` line it prints instead of assuming 8080.
- **TopLogic build output bypasses `-q`.** The install boots the full service stack through
  log4j; filter with `grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"` instead of `-q` or `tail`.
- **The Playwright MCP writes into the repository root** (`.playwright-mcp/`, hundreds of
  snapshots and console logs), not the scratchpad, and **it is not in `.gitignore`** — never
  `git add -A`; add files by name.
- **`tmp/` is the database.** `tmp/h2.mv.db` and `tmp/app-data` survive restarts, which is what
  you want (imported sample projects stay). Delete `tmp/` only after an incompatible model change
  without migration or when `root`/`root1234` is refused (the initial password applies only to a
  fresh DB) — and say so in the report, because it destroys the user's test data.
- **The Bash tool resets its working directory between calls.** Use absolute paths or one `cd`
  per command, and never `git add -A` after a relative `cd ..`.
- **`pkill -f <pattern>` matches your own shell** when the pattern appears in the command line you
  are running. Stop the app through its endpoint instead: `curl -sf http://localhost:8080/admin/stop`,
  or by port: `kill $(ss -ltnp | grep ':8080' | sed -E 's/.*pid=([0-9]+).*/\1/')`, in its own call.
- **A merge is not gated by the commits it merges.** Run the gate on the merged tree before
  pushing, even when every branch was green on its own.

When a probe legitimately fails: send it back (§2). When it exposes something deeper than the
package (a model gap, an import that loses information, a view that cannot express the structure),
fix small ones yourself with a regression test; spawn a package for large ones; and when the cause
is the engine, file the TL ticket and record it (§8). Two classics to stay paranoid about: silent
refusals (nothing may decline without a visible message), and an import that overwrites what the
user edited after the previous import.

## 5. The verification gate (before every commit)

```bash
./mvnw checkstyle:check                              # header, tabs, imports, braces
./mvnw install -DskipTests=false                     # compile, full suite (~17 s of tests), app self-test
git status --short                                   # only the files of this change; no .playwright-mcp, no tmp/
```

All green or no commit. If the model changed, the gate runs **after** the install → generate →
build cycle, and the regenerated bindings ride the same commit as the model change. Never trust a
green result from a tree with uncommitted foreign edits; check `git status` first.

## 6. Committing

- **Commit to `master` and push to `origin/master` every time** (standing directive; CLAUDE.md
  records this convention). Feature branches and PRs are for external contributors. `Closes #N`
  in the body closes the issue.
- House style, read from the log: a one-line title naming the area and the change, ending with a
  period ("Program structure: one tree owned by the program, conditional channels, structure
  editor."), then a paragraph of the why in prose — what was wrong, what the mechanism is now,
  which labels or IDs changed (never a bullet changelog) — ending with the Co-Authored-By line from
  the harness rules.
- The agents don't commit; you do, after review. Regenerated Java bindings ride the same commit as
  the model XML change that produced them; a new `doc/tl-todo.md` entry rides with the workaround
  it documents.

## 7. The run loop

```bash
(tl_initial_password=root1234 nohup ./mvnw -B exec:java > tmp/tl-app.log 2>&1 &)
# Jetty on 8080, context /de.haumacher.ehome, dev mode (pom.xml); "up and running" in
# tmp/tl-app.log after ~10 s on a warm tmp/ (the first start of a fresh tmp/ is slow)
curl -sf http://localhost:8080/admin/stop               # stop; the port is free ~2 s later
```

The `tl-app` skill (`tl-app.sh start|stop|restart .`) does the same with its own port and
`tmp/tl-app.log`; use it when the port does not matter, the command above when the user expects
8080.

Open `http://localhost:8080/de.haumacher.ehome/view/` and log in through the in-app dialog as
`root` / `root1234` (the initial password is read from `tl_initial_password` — environment
variable or `-D` system property — and applies only to a fresh `tmp/`). The classic
`/servlet/LayoutServlet` only shows the engine's administration. `-Dtl.port=...` changes the port.
Run the start outside the sandbox (`dangerouslyDisableSandbox`), or the JVM dies with the shell.

The app does not hot-reload Java, views or configuration; after a change, rebuild and restart it.
For an import feature, import `samples/Test.knxproj` (one device, quick) and, when the change
touches catalogs or many devices, `samples/EHomeDemo.knxproj`. Delegate the browser interaction to
a Playwright sub-agent with a self-contained brief (URL, login, path to the view, steps, expected
outcome per step) and prefer `browser_snapshot` over screenshots, as the `tl-app` skill describes.
Tell the user the commit hash and what to try. Never leave a broken build running — the running
app is the shared reference for bug reports.

## 8. Model, import and engine doctrines (enforce in every brief and review)

- **The model XML is the single source of truth** for the Java bindings. Never edit generated
  output; regenerate. The hand-written wrappers (`X.java`, `XImpl.java`) are where behavior goes.
- **The model is E-Home's, not ETS's.** Extend it in the app's own terms and record the coverage
  in `doc/knx/schema-coverage.md` (`schema-coverage.py` compares the XSD with `etsImporter.xml`);
  an attribute that merely mirrors an XML attribute nobody uses is noise.
- **ETS files are read-only input.** The import reads a `.knxproj`; nothing writes one back. The
  samples and fixtures are committed artifacts — fixtures change only through `make-fixtures.py`.
- **Import into existing data is idempotent by `extId`.** Shared objects (catalog, programs,
  translations) are reused, known programs skipped, and user edits made after an import survive
  the next one. A duplicate after re-import is a p0.
- **Labels are data of two languages.** Every UI text is a `ResKey` in an `I18NConstants` class
  with an `@en` tag; the German is the hand-maintained bundle (`i18n` article). ETS texts are
  `I18NString`s imported per language, with the project's default language as fallback. Umlauts
  are written as characters, never as `\uXXXX`.
- **Access is deny-by-default.** A new type or module needs grants before a non-admin user can see
  it (`access-configuration` article); until a `<security-config>` exists, only `root` sees data —
  say so in every UI report.
- **Engine gaps are bookkept, never buried.** When TopLogic 8.0.0-alpha10 cannot do something
  (the view layer, the XML import, the generator), the workaround lives in the app with a TL Trac
  ticket number (`tl-dev` tools: `create_ticket`, `search_tickets`) and an entry in
  `doc/tl-todo.md` saying **When fixed/available:** what to remove. Check that file on every
  TopLogic upgrade.
- **Views are the UI.** No classic layouts (`WEB-INF/layouts/`) come back; a gap of the view layer
  is an entry in `doc/tl-todo.md` (tree tables, Excel export), not a classic component.
- **Refusals speak**: no command may decline silently; the UI shows the reason.
- **Everything generic**: issues are exercises of general mechanisms — if a fix is shaped like the
  bug report, it isn't done.
- **Decisions live in issues, commit messages and `doc/`.** The commit message is the as-built
  note, the issue comment is the rationale, `doc/tl-todo.md` is the engine ledger; deliberate cuts
  become issues, never silence.

## 9. Working with this user

They are an expert (Java, software architecture) and the author of both this app and the
TopLogic engine it runs on, testing continuously. Their bug reports come as ETS screenshots
(`doc/*.png`), German label texts, XML fragments from a `.knxproj`, or a view that "shows the
wrong thing" — embed them verbatim as regressions. Their feature sketches are usually complete
designs — restate the design back crisply, adopt it, credit it in the commit message. When an
engine defect blocks the app, they may fix it upstream: report it as a TL ticket with a minimal
reproduction, keep the local workaround marked in `doc/tl-todo.md`, and move on. When they
overrule a recorded decision, the reversal is documented in the issue, not litigated. Answer their
questions with the mechanism, then the plan, then queue position. Run notices always include the
commit hash and what to try.

## 10. Two agents at once — the worktree pattern

Parallel packages work when, and only when, they are physically separated:

- **One agent in the main tree, the other in a git worktree**
  (`git worktree add -b <branch> <scratch>/wt-x HEAD`, run **from this repo**), each with a FILE
  BOUNDARY paragraph. Separate trees keep their `target/` and `tmp/` apart. The worktree agent
  runs `./mvnw test -DskipTests=false` and `./mvnw checkstyle:check`, never `install` and never
  `tl:generate-java`, so it cannot overwrite the main tree's `~/.m2` artifact or regenerate from
  the wrong model.
- **The model has one writer** (§3), and that writer is in the main tree. If both packages need
  model changes, serialize them.
- **Merging**: commit the worktree branch yourself (house-style message), merge it into master
  after the main-tree package is committed, run the full gate (§5) on the merged tree — generated
  bindings, `ehomeConf.config.xml` and the resource bundles are exactly where merges break — then
  push.
- After a merge, `git ls-files` the new directories the report names; the `.gitignore` rules
  (`/tmp/`, `/target/`) can swallow a wrongly placed source directory.
- **A session limit kills every running agent at once** (HTTP 429 mid-flight). Their uncommitted
  work is in the trees; when the limit resets, `SendMessage` each one "Resume exactly where you
  left off: <its last visible step, read from the transcript's last tool calls>" rather than
  re-dispatching. Make the stall monitor grep the transcript tail for "session limit" so the two
  cases are told apart.
