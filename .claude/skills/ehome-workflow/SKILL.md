---
name: ehome-workflow
description: The working method for developing E-Home — orchestrating sub-agents in the two TopLogic Maven modules (the app ehome/ and the development tooling ehome-virtual/), adversarial probe reviews, the verification gate (checkstyle plus the full test run), the run loop against the local app with browser checks, GitHub-issue triage, TopLogic-gap bookkeeping (Trac tickets and doc/tl-todo.md), the commit discipline, and the model/import doctrines. Load at the start of any session doing feature or bug work on this repository.
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

0. **Engine upgrade — only when the user asks for it.** Do not look for a newer TopLogic version
   or poll the TL tickets at the start of a session. When the user requests an upgrade, it is a
   package of its own: set the new version in the parents of `ehome/pom.xml` and `ehome-virtual/pom.xml`, go through every entry of
   `doc/tl-todo.md` (§8) and check whether its ticket has shipped (`get_ticket`), undo the
   workarounds that have, re-run the gate, and tell the user which entries remain.
1. **Intake**: user reports (chat, screenshots, ETS XML snippets, GitHub issues) and the open
   issues at `haumacher/ehome`. Bugs outrank queued features. Triage every new GitHub issue with a
   diagnosis comment *before* fixing; use the repository's labels (`bug`, `enhancement`,
   `question`, `documentation` — the GitHub defaults; do not invent new ones without the user).
2. **Reproduce before dispatching.** An import or model report gets a headless reproduction
   first: a JUnit test in the `TestEtsImport` harness against `ehome/samples/Test.knxproj` or a fixture
   in `ehome/src/test/fixtures/` (§4 for the traps). A UI report gets a look at the running app
   (§7) — a Playwright snapshot of the view, not a guess from the `.view.xml`. Know whether it is
   a real bug, an engine gap already recorded in `doc/tl-todo.md`, or your own probe error before
   an agent burns tokens. If the main tree is agent-owned, reproduce in a git worktree at HEAD
   (`git worktree add <scratch>/repro <commit>`), never in the live tree.
3. **Dispatch** one sub-agent per coherent package (§2). Sequential by default; parallel only with
   disjoint file ownership stated in both briefs (§3). The natural split is importer/Java versus
   views/configuration — but see §3 on why one app module makes parallel work rare.
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
  (Calimero gateway discovery), `de.haumacher.ehome.virtual.tooling` (module `ehome-virtual`: the
  programmable virtual device, the ETS recorder and its traces), `WEB-INF/model/ehome.*.model.xml` (the model),
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
  contained. Extend the fixture projects only through `ehome/src/test/fixtures/make-fixtures.py` (it
  derives `TestDetails.knxproj` and `TestDynamic.knxproj` from `samples/Test.knxproj` and names
  them as artificial test data); never hand-edit a `.knxproj` zip, never add a real customer
  project.
- **Explicit acceptance tests**, including exact values where computable (counts of imported
  objects per type, `extId`s, labels in both languages, group address strings per style).
  "import → read back the attribute → equals the fixture's value" belongs in almost every import
  brief; "import the same project twice → no duplicated shared objects" (TL #29797 workarounds)
  belongs in every brief that touches shared objects (catalogs, programs, translations).
- **The build discipline block** (the point is not wasting runs, not avoiding them):
  - The test gate is **one command and fast**: `./mvnw test -DskipTests=false` in the repository
    root runs the suite of each module (`test.TestAll` plus the conformance checks, once in `ehome/`
    and once in `ehome-virtual/`, each in a JVM and a database of its own). **Budget: both suites
    together at most 95 s; a new test class at most 5 s, or its javadoc names why it needs more.**
    After the split into modules (haumacher/ehome #28, October 2026) the app's suite took 68 s and
    the tooling's 24 s (345 tests) at a load average of about 3; the tooling's own database means
    its tests import their samples once more. `bin/slowest-tests.py` prints the time of each
    suite, the total and the 10 slowest tests and classes from the surefire reports; read it
    after every gate. The cost of a test is its imports: all classes of a module share one database in one JVM,
    the shared data (master data, catalogs, programs) stays, and an import of a sample costs seconds
    per program the first time and about one second after that; deleting shared data costs as much
    as importing it. So a test deletes only its projects, asserts project-relative state or shared
    objects by ID (never the counts of a fresh database), shares one import between its methods,
    and establishes a mask-version state with `SharedData.resetMaskVersions` instead of emptying
    the database (`test.de.haumacher.ehome.importer.SharedData`, CLAUDE.md). Concurrent Maven runs
    on the same machine roughly double the time. If it suddenly takes minutes, check that the
    single-branch configuration is still in `metaConf.txt` (TL #29876), then the slowest tests. Run the full suite as the gate; while iterating on
    one area, `-Dtest=<Class>` is fine, but the gate before a commit is always the full suite. Without `-DskipTests=false` **nothing runs
    and the build is green** (the TopLogic parent skips tests by default).
  - `./mvnw checkstyle:check` in the root after every Java edit (both modules; not bound to the build).
  - Where a change goes: the product (including the rules simulation and its virtual
    installation) is the app `ehome/`; the programmable virtual device and the ETS recorder are the
    tooling `ehome-virtual/`, which depends on the app and never the other way round (CLAUDE.md,
    "Modules"). A test of the app must not need the tooling: `./mvnw install -DskipTests=false
    -Pproduction` builds and tests the app alone.
  - A **model change** costs a cycle: `cd ehome && ../mvnw tl:generate-java` (it reads the model
    from the module's sources since TL #29789, no install before it), build again. A change the
    automatic model upgrade would apply destructively to an existing database (a class moved to
    another module, a renamed attribute) also needs a data migration (CLAUDE.md, "Generated
    code"), checked on a copy of `tmp/`. Only `ehome.core` and `ehome.operational` have generated Java
    bindings (`ehome/pom.xml`, `tl-maven-plugin`); the other modules are reached through
    `tValueByName`/`tUpdateByName` and the test harness does the same.
  - `mvn` and `./mvnw` are the same Maven here (3.9.16, installed in `~/tools/maven` and first on
    the PATH); the distribution's 3.8.7 is below TopLogic 8's minimum, so if `mvn -v` ever shows
    it again, the PATH entry in `~/.bashrc`/`~/.profile` is missing.
  - A run whose inputs did not change is not repeated "to be sure". Batch independent lookups
    (several greps in one call; several `describe_type`/`show_source` calls in one turn).
- **The standing rules block**: ALL existing tests green; checkstyle clean; UTF-8 with umlauts
  written directly; tabs; the copyright header with the file's creation year; UI texts as `ResKey`
  constants in `I18NConstants` with an `@en` javadoc tag; **never hand-edit generated code**
  (`internal/*Base.java`, `internal/*Impl.java`, `*Constants.java`, `*Factory.java`, the
  enumeration classes) — edit the model XML; only the interface `X.java` is hand-written code (own
  methods as default methods); a changed `.view.xml` needs an app
  restart; access rights are deny-by-default (`ehome.security.config.xml`; a test without a person
  reads derived attributes as `root`); do NOT commit; **nothing
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
  `tl:generate-java`, and an install overwrites the one `1.0.0-SNAPSHOT` in `~/.m2` that the other
  trees' tooling builds resolve. Whoever changes a model file owns the regeneration and the
  compile fallout; the other agent must not touch any model file and must not run `install`
  while the model is mid-edit. Model changes happen **only in the main tree**.
- **One tree means one set of `target/` directories** (`ehome/target/`, `ehome-virtual/target/`).
  Concurrent `./mvnw` runs in one tree collide. Never run Maven in a tree an agent is mid-edit in;
  give a second agent its own git worktree (§10) and have it use `./mvnw test -DskipTests=false`
  in the worktree's root (never `install`) so it cannot overwrite the main tree's installed
  artifacts (the tooling resolves the app from the reactor there).
- **One app instance per tree.** The running app holds the H2 database in the root `tmp/` and port
  8080; a worktree that needs its own app starts it with `-Dtl.port=8081` and gets its own `tmp/`.
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
- **Stale generated code.** After a model change the wrappers must be regenerated; a probe
  compiled against the old shape fails to compile, which is the build telling you to run
  `tl:generate-java` (CLAUDE.md), not a defect. A test of the tooling that behaves as if a model
  change never happened is running against an old install of the app.
- **The app shows the old view.** A changed `.view.xml` (and `ehomeConf.config.xml`) is not
  picked up by a running app; restart before probing the UI (§7).
- **Hygiene failures look like feature failures.** `test.TestAll` adds TopLogic conformance
  checks, in each module: copyright comment (`TestComment`, pattern in `ehome/src/test/webapp/WEB-INF/conf/
  ehome-testSettings.config.xml`), normalized layouts, JSPs, I18N completeness. A red suite after
  a feature may be a missing header, an `@en` tag, or an unused resource key — fix the hygiene,
  then judge the feature.
- **The fixtures are generated.** `TestDetails.knxproj` and `TestDynamic.knxproj` come from
  `make-fixtures.py`; a probe that needs a new ETS construct extends the script (and re-runs it
  from the app module `ehome/`), never edits the zip. A fixture that changes an application program must
  give it a new ID and a name marking it as test data (as `TestDynamic` does), or the import
  skips the program as already known and the probe tests the old one.
- **Re-import is a feature, not a bug.** Importing a project again into existing data must reuse
  shared objects by `extId` (`<resolve-existing>` in `etsImporter.xml`, `SharedObjects` for the parts of programs, `<unless-known-program>`). A probe that
  counts doubled catalog entries after two imports found a real p0; a probe that expects a second
  *project* object after importing the same file twice may be probing the design — read
  `doc/tl-todo.md` (TL #29797) before judging.
- **The tl-app script picks its own port.** `tl-app.sh` (skill `tl-app`) starts the app on a
  random free port unless `tmp/app-port.txt` names one, and records the port there; read the
  `url:` line it prints instead of assuming 8080.
- **TopLogic build output bypasses `-q`.** The install boots the full service stack through
  log4j; filter with `grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"` instead of `-q` or `tail`.
- **The Playwright MCP writes into the repository root** (`.playwright-mcp/`, snapshots, console
  logs and screenshots), not the scratchpad. The directory is in `.gitignore`; still add files by
  name rather than `git add -A`, because a screenshot saved elsewhere is not.
- **`tmp/` is the database, and it is the user's.** `tmp/h2.mv.db` and `tmp/app-data` survive
  restarts, which is what you want (imported sample projects stay). Never `rm -rf tmp/`. When a
  model change cannot be migrated or `root`/`root1234` is refused (the initial password applies
  only to a fresh DB), **ask first**, then move the old data aside (`mv tmp tmp.bak-<timestamp>`)
  and start the app on an empty directory, so the old state stays restorable. `rm -rf` is for
  worktrees and files you created yourself under the session scratchpad, nothing else.
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
./mvnw checkstyle:check                              # both modules: header, tabs, imports, braces
./mvnw install -DskipTests=false                     # both modules, both suites (budget 95 s together); read every "Tests run:" line
bin/slowest-tests.py                                 # time of each suite, the total and the 10 slowest tests: within budget?
git status --short                                   # only the files of this change; no .playwright-mcp, no tmp/
```

All commands run in the repository root. When a change touches the boundary between the modules
(a hook, a class moved, a dependency), also run the app alone: `./mvnw install -DskipTests=false
-Pproduction`. All green or no commit. If the model changed, the gate runs **after** the generate →
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
cd ehome && (tl_initial_password=root1234 tl_browser=none nohup ../mvnw -B exec:java > ../tmp/tl-app.log 2>&1 &)
# In the app module (the deploy aspect deploy/local applies only there), with the tooling built by the last
# ./mvnw install in the root (-Pproduction: without it). Jetty on 8080, context /ehome (tl.contextPath
# in ehome/pom.xml), dev mode, data in the root tmp/ (ehome.dataDir in ehome/pom.xml); "up and
# running" in tmp/tl-app.log after ~10 s on a warm tmp/ (the first start of a fresh tmp/ is slow)
curl -sf http://localhost:8080/admin/stop               # stop; the port is free ~2 s later
```

The `tl-app` skill (`tl-app.sh start|stop|restart ehome`) does the same with its own port and
`ehome/tmp/tl-app.log`; but its start keeps the data in the root `tmp/` only through the
`ehome.dataDir` of the POM — use it when the port does not matter, the command above when the user
expects 8080. To start on a copy of the data: `-Dehome.dataDir=<dir>` (the environment variables
`h2_name` and `tl_storage_path` are overridden by the POM).

Open `http://localhost:8080/ehome/view/` and log in through the in-app dialog as
`root` / `root1234` (the initial password is read from `tl_initial_password` — environment
variable or `-D` system property — and applies only to a fresh `tmp/`). The classic
`/servlet/LayoutServlet` only shows the engine's administration. `-Dtl.port=...` changes the port.
Run the start outside the sandbox (`dangerouslyDisableSandbox`), or the JVM dies with the shell.

The app does not hot-reload Java, views or configuration; after a change, rebuild and restart it.
For an import feature, import `ehome/samples/Test.knxproj` (one device, quick) and, when the change
touches catalogs or many devices, `ehome/samples/EHomeDemo.knxproj`. The tab "Virtual device" of a
device exists only with the tooling (development start); a production start (`-Pproduction`) must not
show it. Delegate the browser interaction to
a Playwright sub-agent with a self-contained brief (URL, login, path to the view, steps, expected
outcome per step) and prefer `browser_snapshot` over screenshots, as the `tl-app` skill describes.
Tell the user the commit hash and what to try. Never leave a broken build running — the running
app is the shared reference for bug reports.

## 8. Model, import and engine doctrines (enforce in every brief and review)

- **The model XML is the single source of truth** for the Java bindings. Never edit generated
  output; regenerate. The hand-written interfaces `X.java` (default methods) are where behavior goes.
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
- **Access is deny-by-default.** `WEB-INF/autoconf/ehome.security.config.xml` (haumacher/ehome #48)
  gives the groups `ehome.installers` and `ehome.viewers` their roles; a new type that no
  composition holds needs a role parent rule and grants there, or `TestSecurityCoverage` fails
  (`access-configuration` article). Java code is not access checked: a view command changing data
  through Java or an E-Home TL-Script function carries `<model-access operation="Write"/>`. Check a
  UI change as `root` and, where it edits, as a viewer.
- **Engine gaps are bookkept, never buried.** When the TopLogic engine in use cannot do something
  out of the box (the view layer, the XML import, the generator) or has a proven defect, the
  sequence is fixed: (1) **search Trac before filing** — several `search_tickets` queries with
  one filter each (`summary~=<keyword>`), in English and German, with the class or element name
  (`RowSourceObserver`, `adaptive-detail`), the symptom (`drag`, `Baum`, `NPE`) and the feature,
  without a status filter (a closed or approved ticket may already carry the fix); when a ticket
  exists, reference it (and add the app's reproduction as a comment only if it adds something)
  instead of filing a duplicate; only then file a TL Trac ticket with a minimal reproduction
  (`create_ticket`); (2) work around it in the app **only if
  the workaround is minimal** — a few lines, no copy of engine code, no second mechanism;
  otherwise **leave the problem visible** (the gap shows in the UI or the report, the issue stays
  open) rather than building around it; (3) record the ticket number and the workaround in
  `doc/tl-todo.md` with a **When fixed/available:** line saying what to remove; (4) when the user
  asks for an engine upgrade (§1 step 0, never unprompted), check the status of every recorded
  ticket (`get_ticket`) and undo the workarounds whose fix has shipped, as the entries say. A
  workaround without a ticket, or a ticket without an entry, is not done.
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
  BOUNDARY paragraph. Separate trees keep their `target/` directories and `tmp/` apart. The
  worktree agent runs `./mvnw test -DskipTests=false` and `./mvnw checkstyle:check` in the
  worktree's root, never `install` and never
  `tl:generate-java`, so it cannot overwrite the main tree's `~/.m2` artifact or regenerate the
  bindings the main tree owns.
- **The model has one writer** (§3), and that writer is in the main tree. If both packages need
  model changes, serialize them.
- **Merging**: commit the worktree branch yourself (house-style message), merge it into master
  after the main-tree package is committed, run the full gate (§5) on the merged tree — generated
  bindings, `ehomeConf.config.xml` and the resource bundles are exactly where merges break — then
  push.
- After a merge, `git ls-files` the new directories the report names; the `.gitignore` rules
  (`/tmp/`, `target/` and `tmp/` of the modules) can swallow a wrongly placed source directory.
- **A session limit kills every running agent at once** (HTTP 429 mid-flight). Their uncommitted
  work is in the trees; when the limit resets, `SendMessage` each one "Resume exactly where you
  left off: <its last visible step, read from the transcript's last tool calls>" rather than
  re-dispatching. Make the stall monitor grep the transcript tail for "session limit" so the two
  cases are told apart.
