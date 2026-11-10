# Waiting for TopLogic

Gaps of the React UI (`ehome/src/main/webapp/WEB-INF/views/`; paths below are relative to the app module `ehome/` unless they name `ehome-virtual/`) and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha12.819) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

State of the upgrade to 8.0.0-alpha12.819 (2026-11-10): the fixes of TL #30211, #30214, #30215, #30216
and #30218 shipped. Removed: the tooltip rule of `style/ehome.css` (#30214); the own sub-session of
`de.haumacher.ehome.job.Uninterruptible` (#30216: `JobRunner` runs the body in a sub-session derived
from the window, `ThreadContextManager#inDerivedContext`; the body's thread of `Uninterruptible.run`
stays in it; the catch-up of the window stays, now for TL #30249, see below); the `Committable` of
`IdRenames`, whose messages are now an `AfterCommitAction` registered on the `CommitActions` the `ModificationListener` receives (#30218,
a breaking change of `ModificationListener`, adapted in `ProgramChanges` as well). The structure of
the device tree is remembered per user (`personalize="true"`, #30215), and "Neu › Linie" is hidden in
the structure "Gebäude" (#30211). TL #30201 and #30205 are closed, but their fixes came after the
build of 8.0.0-alpha12.819 (no `jobErrorDetails`, no `DynamicMandatoryConstraintCheck` in its jars):
their workarounds stay until the next upgrade. TL #30200 (in testing) is not in 8.0.0-alpha12.819
either. Still open: TL #29884, #29885, #30048, #30105, #30191, #30192, #30193 to #30196, #30217; new: #30249.

Before (8.0.0-alpha12.817, 2026-11-06): the fixes of TL #30086, #30088, #30125,
#30127, #30134, #30139 and #30140 shipped, and their workarounds are removed (`<unless-known-program>`
with `TranslationsImport#importBaseTexts`, the 60 s check of `MonitorSource`, the redirect case of
`bin/ehome-agent.py`, `HISTORY_ARCHIVES_NOT_MANUAL`, the unit in the label of a block parameter).
TL #30087 was closed as invalid (TL-Script can walk the composition of an object through the model);
the walk works but is too slow, so `SharedObjects` stays (see below). Breaking changes adapted: the
sidebar's `<separator>` is gone (TL #30044), the items of `app.view.xml` stand in the shell's
`<section id="application">`; a command the user can run on no object is hidden instead of disabled
(TL #30025), which `TestAccessRights` now expects for a viewer and an account without an E-Home group.
TL #30031 migrates the link tables of an existing database at the start. TL #29884, #29885, #30105 and
#30048 are open (checked on 8.0.0-alpha12.817).

Before (8.0.0-alpha12, 2026-10-30): the fixes of TL #29798, #29812, #29814, #29869, #29872 to
#29874, #29876, #29877, #29880 to #29883, #29886 to #29896, #29898, #29973 to #29983 and #29990
shipped; TL #29899 is closed as wontfix. `parameterValueType` (TL #29798) stays: the options,
constraints and visibility of a parameter value call it because it reads the values entered in a
form, which the derived `valueType` does not.

## ETS import: workarounds for the XML import (TL #29797, #30087, #30192)

The ETS import (`de.haumacher.ehome.importer`, `etsImporter.xml`) has handlers of its own for what
the engine's XML import (`com.top_logic.xio`) cannot express without restating the model. Since
alpha12 it uses `<set-translation>` for the texts, merged `<switch>` cases, warnings on unknown
elements (every construct deliberately not modeled is declared with `<skip-deep/>` at its place) and
adds to set references directly. What stays:

- A known application program gets the texts of the languages it lacks (haumacher/ehome #38) through
  `<unless-known>` with `<update>` and `<update-known>` (TL #30086): the text handlers of the
  program's elements are `<group>`s of the creating section, applied to the known descendants by
  their IDs. Two small handlers of the app stay, as the record of the languages is the app's
  (`ProgramLanguages`): `<known-program-texts>` (`KnownProgramTexts`) decides in the `<update>`
  whether the program lacks the file's language and records it (the engine's `condition` could
  decide, not record), and `<program-imported>` (`ProgramImported`), the last handler of a new
  program, records its language and decides the held-back warnings about hidden parameter values
  (`HiddenProgramValues`, haumacher/ehome #83). Nothing to undo.
- TL #29975 shipped the `partition` of `<resolve-existing>`; the `objects` of a partition are all
  parts of a program, about ten thousand reached through 24 composite references of
  `ehome.operational`. Workaround: `SharedObjects`, an `IdLookup` that `Importer` installs with
  `ImportContext#withIdLookup` and that walks the composition of a program in Java. TL #30087 asked
  for a TL-Script walk of the composition and was closed as invalid with a recipe in plain TL-Script
  (the composite references of the object's type and its generalizations, from
  `tl.model:TLClass#generalizations`, `TLStructuredType#localParts` and `TLReference#composite`, in a
  `recursion`). An inner `<resolve-existing>` with that recipe as `objects` (and the program ID at
  the start of a part's ID as `partition`) imports the same (checked on 8.0.0-alpha12.817, the suite
  green), but the app's suite took 136 s instead of 89 s with `SharedObjects` (one run each, the
  slower one on the less loaded machine; TestRoundTrip#testDemo 10.8 s against 8.7 s): the walk asks
  the model for the composite references of every one of the parts. Engine gap: a TL-Script function for the composition descendants
  of an object that is as fast as the Java walk (TL #30192; #30087 is closed). **When available:** declare the
  partition in `etsImporter.xml` and remove `SharedObjects`.
- `<versioned-master-data>` (`VersionedMasterData`, `MasterDataUpdates`, `ObjectMerge`) imports a known
  mask version as a transient candidate (`JoiningModelBinding#setTransient`) and, at the end of the
  import, updates the known one in place from it (newer master data) or compares and warns (older or
  the same). The engine has no import of an element into a transient candidate of a known object
  (TL #29797, shipped in part).
- `<translations>` (`TranslationsImport`) reads the `Languages` sections: the attribute a translation
  goes to depends on the type of the object (`Text` is the description of a parameter, the name of a
  variant), and a translation of a known program sets only the languages it lacks (#38).
- `<set-translation>` names the fallback language as `default-language="en"` instead of reading the
  application's fallback language.
- An unset boolean reads `false` on stored and transient objects alike since TL #29883; the Zigbee
  import (`ZigbeeImport#set`, `ZigbeeCatalogJson`) still treats a missing flag of an export (`null`)
  as the stored `false`, which is the format's meaning, not a workaround.
- Deleting the parts the import drops one at a time was slow (the referrer scans of TL #29876, since
  fixed); `MasterDataUpdates` deletes them in one `deleteAll`, which stays the cheaper way. The tests
  still avoid deleting shared data (`TestRoundTrip` runs first, `SharedData.resetMaskVersions` updates
  in place).

## Single branch (TL #29876, #29877)

E-Home runs without branches: `top-logic.singleBranch.config.xml` (from tl-core) in
`WEB-INF/conf/metaConf.txt`. Since alpha12 it only sets `multiple-branches="false"`, and H2 databases
switch to the key layout `branch-last` automatically; a database created with branches has its
branch columns removed at the start when it holds only the trunk. The developer article of TL #29877
describes the persistency configuration.

**On the next upgrade:** nothing to undo.

## Live sources of a view

The group monitor (`projects/monitor.view.xml`, `de.haumacher.ehome.monitor.MonitorSource`) stays
attached to the bus while a window that showed it is open, also while the window hides its view:
since TL #30088 (documentation of `ChannelSource`) the window is its sub-session, and a
`SubSessionListener` of the sub-session releases the monitor when the window is torn down or the
session ends; the 60 s check of the window registry is gone. The simulation (`SimulationSource`) and
the Zigbee tab (`ZigbeeSource`) only listen while their view is displayed and need no such hold.

The virtual device (`ehome-virtual/src/main/webapp/WEB-INF/views/virtual/virtual-device.view.xml`):
its status and the number of services recorded change while a programming tool downloads into it,
but the recorder tells nobody of a change. The view recomputes them when it is displayed and on its
"Refresh" command. A change notification of the recorder would let a source channel follow it (an
app change, no engine gap).

## Access coverage of engine modules (TL #29884)

The engine ships model modules whose types have no complete access definition: at the start of
E-Home, `SecurityCoverageCheck` reports engine types of 15 modules (`Contacts`,
`SearchExpressionStructure`, `SecurityStructure`, `Tag`, `mandatorStructure`, `orgStructure`,
`tl.accounts`, `tl.comments`, `tl.contact.ui`, `tl.core`, `tl.folder`, `tl.model`, `tl.reporting`,
`tl.search`, `tl.units`; e.g. `tl.accounts:Group`, `tl.model:TLClass`: no role source and no read
grant). An application that switches `TestSecurityCoverage` on fails on them although it does not
grant them to its users.

Workaround in `WEB-INF/autoconf/ehome.security.config.xml`: `SecurityCoverageCheck` excludes these
15 modules.

Still open, checked on 8.0.0-alpha12.819.

**When fixed** (the engine covers or marks its own modules, or the check can be limited to the
application's modules): remove `excluded-modules`.

## A refused command on a composition part (TL #29885)

Under alpha11 a refused check on an object whose type delegates to its access parent (a device, a
group address, a catalog entry) hid the command instead of disabling it. With the consolidated access
checks of alpha12 (TL #29856) a viewer sees these commands disabled with the reason ("Sie dürfen
dieses Objekt nicht ändern"); four import commands (rules, Zigbee devices, product data, Zigbee
catalog) are disabled with only their own label as the reason. The ticket is still open.

Since TL #30025 (8.0.0-alpha12.817) a command is hidden for a user who holds no role granting it on
any object; the roles of E-Home are global, so a viewer no longer sees the commands that write
(nor the import commands), and the case of the ticket does not show in E-Home any more. Still open,
checked on 8.0.0-alpha12.819.

**On the next upgrade:** check the ticket; nothing to remove.

## A refused submit leaves its input editable (TL #30105)

The `<access>` check of a command decides the state of its button (TL #29856); the `<on-submit>`
command of a `<value-input>` has no button, so a refused check leaves the input editable and reports
the refusal only after the submit. Workaround in `projects/device-item.view.xml`: a device's parameter
inputs switch to their read-only form with `!canWrite($device)`, repeating the `<access>` check of
their `<on-submit>` (haumacher/ehome #65).

Still open, checked on 8.0.0-alpha12.819.

**When fixed** (the input follows the state of its submit command): remove the `!canWrite($device)`
case of the input kind in `device-item.view.xml`.

## A failing class load aborts the whole module shutdown (TL #30191)

When the app's jars were deleted before Tomcat undeployed it (the old `prerm`, haumacher/ehome
#81), `ModuleUtil.stopInThreadContext` could not load its job class and stopped no module at all:
the H2 database stayed open in Tomcat's JVM and the next deployment found it locked. The package
now waits for the undeploy before anything is deleted (`packaging/debian/prerm`), so the trigger is
gone; no workaround in the app.

**When fixed:** nothing to undo; the shutdown is robust against such a failure.

## No at-rest encryption out of the box (TL #29899, wontfix)

The secret of an enrolled controller (haumacher/ehome #54) must be readable by E-Home to check the
controller's HMAC, so it cannot be hashed. TopLogic's `EncryptionService` needs a key set up per
installation, and the engine will not provide an attribute storage that encrypts with a key of its
own (TL #29899, wontfix). The secret is stored as is in the hidden attribute `Controller#secret`
with a read grant without roles (only administrators read it through the model), like the controller
token.

## Agent access (TL #30048)

E-Home depends on `tl-layout-view-agent` (`doc/agent-access.md`). Since TL #30125 a request without a
valid credential is answered with 401. TL #30048 collects the unbuilt parts of the design that matter
for an installation in the cloud, without a workaround in the app: every logged-in account may issue
a token (an agent then acts with that account's rights, no more); a token of an ended session
(logout, restart) stays listed until it expires, though it admits nobody; every admitted request
writes `lastUsed` in a commit of its own, so a polling agent creates a revision per request; there is
no MCP wrapper (hence `bin/ehome-agent.py`). Still open, checked on 8.0.0-alpha12.819.

**When fixed:** decide whether E-Home restricts issuing tokens (e.g. to `ehome.Installer`) with the
rule the module offers, and replace `bin/ehome-agent.py` by the MCP wrapper.

The observation of a window is unusable for a real page (TL #30200, found on 8.0.0-alpha12.817 behind the
Apache proxy of an installation): `observe --actions` inlined the same ~2.9 MB JSON schema of a model
name into every `selectByKey`, `activateByKey` and `dropObjects` command, 84 MB for a device page with
13 dropdowns, a table and a tree; `observe` without actions answered 502 within 3 s. No workaround:
`bin/ehome-agent.py` passes the answer through as it comes.

**When fixed:** check `observe` with and without `--actions` on a device page of a large project.

## Jobs of the app: cancel, import jobs (TL #30193, #30194, #30195, #30196, #30250)

The cancelable `<start-job>`s of the app: the ETS import, the password dialog and the product data
import (`ImportEtsProjectJob`, `ImportProductDataJob`, haumacher/ehome #84), the bus scan and the
read-back of a device (`ScanJob`, `ReadBackJob`, `projects/scan.view.xml`) and the comparison with a
device (`CompareJob`, `projects/device-programming.view.xml`). `DownloadJob` and `LedJob` are not
cancelable. Four gaps of the job support:

- Cancelling a job interrupts its worker thread (TL #30196). An interrupt during a database access,
  a write or a read, closes the file channel of the embedded H2 database: the MVStore panics and every
  later database access of the app fails until a restart (seen in the browser check with a cancel
  during the commit of an import; a read on an interrupted thread fails the same way). Workaround:
  the shared utility `de.haumacher.ehome.job.Uninterruptible`. `Uninterruptible.run` runs the body of
  every cancelable job on a thread of its own in the job's sub-session, the worker only waits and
  keeps its interrupt as a cancel request; the body answers it at its next report to the job. A
  blocking bus access opens `Uninterruptible.interruptible()`: only inside it the body's thread is
  interrupted, and its interrupt status is cleared on leaving it. Callers of `run`:
  `ImportEtsProjectJob`, `ImportProductDataJob`, `ScanJob`, `ReadBackJob`, `CompareJob`; of
  `interruptible`: the blocking accesses of `CalimeroScanBus` and `CalimeroReader`.
  `TestImportProgress#testInterruptedWorker` and `TestScanJobsInterrupted` cover it.
- A dialog cannot close itself in the `<with-result>` of a job it started (TL #30193): it is not
  closable while its own command is suspended, so `<close-dialog>` is ignored, and the report opened
  next is closed again by the reveal of the opener. Workaround in `projects/import-password.view.xml`:
  the dialog closes first and starts the job on `importJob`, bound to the job channel of the projects
  overview; the job and a wrong password are shown there, and the file is uploaded again.
- A cancel during the commit (phase "Saving", no report point) ends the job as cancelled although the
  file was saved (TL #30194): no report, no selection of the project, which the table shows anyway.
  No workaround.
- A cancelled job carries no result (TL #30195): of a cancelled multi-file upload, the files committed
  before stay, but no report names them (the projects table shows them). No workaround.
- A failed or cancelled job cannot be dismissed (TL #30250): its `<job-status>` stays above the table
  until the next job starts, since the follow-up that clears it runs on success only. Workaround: a
  button "Close" beside the display, shown while no job runs, that clears the job channel
  (`projects/overview.view.xml`, `catalogs.view.xml`; the displays of the scan and the device
  programming are part of their panel and stay).

**When fixed:** TL #30196: remove `de.haumacher.ehome.job.Uninterruptible` (and its test `TestUninterruptible`) - its `run` from
`ImportEtsProjectJob`, `ImportProductDataJob`, `ScanJob`, `ReadBackJob` and `CompareJob`, its
`interruptible()` sections from `CalimeroScanBus` and `CalimeroReader` - and the refetch of
`RecordingJobMonitor#run` in the tests. TL #30193: run the job in the dialog with its own `<job-status>` and the
`<close-dialog>` first in the `<with-result>`, and drop the `importJob` binding of the dialog in
`projects/overview.view.xml`. TL #30194: declare the commit in `ImportJobs#importFile` as past the
point of no return. TL #30250: replace the "Close" buttons beside `<job-status>` in
`projects/overview.view.xml` and `catalogs.view.xml` by the display's own dismissal. TL #30195: continue the chain of a cancelled multi-file import with the report
of the files imported.

## The `<with-result>` of a job before the window saw its commits (TL #30249)

Since TL #30216 `JobRunner` runs a job's body in a sub-session derived from the window, with a session
revision of its own; the window learns about the job's commits through its model events, which the
engine synthesizes when the window is entered next, after the `<with-result>` of the job has run.
When the window committed during the job (a project created while an import ran), the import's
`<with-result>` selected the imported project in a table that did not show it yet, and no row was
selected (seen in the browser on 8.0.0-alpha12.819). Workaround: `Uninterruptible#catchUp` enters the
window (`ReactWindowReplay.inWindow` with the origin of the job's sub-session), which synthesizes the
events, before the job ends.

**When fixed:** remove `Uninterruptible#catchUp` and its call in `Uninterruptible#run`.

## A constraint veto without its problems (TL #30201)

The model's commit check (`ModelService#checkCommit`) refuses with "Cannot store, constraints
violated." alone and keeps the problems as the exception's causes; a failed job shows only the
message and logs nothing, so a production import ended with "Speichern nicht möglich,
Wertüberprüfung fehlgeschlagen" and no hint. Workaround: `ImportJobs#importFile` catches the veto at
its commit, names the problems (`de.haumacher.ehome.etsImport.ConstraintVeto`, test
`TestConstraintVeto`) in its own message `ERROR_IMPORT_CONSTRAINTS__FILE_PROBLEMS` and logs it at
ERROR.

**When fixed:** remove `ConstraintVeto`, its test, the catch in `ImportJobs#importFile` and the
message `ERROR_IMPORT_CONSTRAINTS__FILE_PROBLEMS`, if the engine's message or the job's failure
display names the problems.

## A derived attribute is editable in a row-editing table (TL #30205)

In a `<table row-edit="ALL">` an attribute column of a derived attribute (`<query>` storage) shows an
input while the form is edited, and saving it fails. The objects of a device template
(`device-templates.view.xml`, haumacher/ehome #103) list the number, function, size and "installed
without type" of each object as derived attributes of `ehome.template:ObjectOverlay` beside the
editable datapoint type. Workaround: those columns (and the placement, which the overlay must keep)
carry `readonly="true"`.

**When fixed:** remove `readonly="true"` from the derived columns `number`, `function`, `size` and
`untypedConnected` there (keep it on `placement`).

## A tree table rebuilt by an input keeps the selection but not its parents open (TL #30217)

A `<tree-table>` whose `children` depend on an input channel is rebuilt when the input changes;
`TreeSelectionBinding.structureChanged` keeps a surviving selection without opening the nodes
above it, and `applyChannelValue` then skips it as already shown. The same after a drop moves a
selected node under a closed one. In `projects/devices.view.xml` (haumacher/ehome #106: the switch
"Struktur" and the drop into a building part) the selection is cleared and written again, which
opens its parents.

**When fixed:** write the selection once in the switch and the drop of `projects/devices.view.xml`.

## AI modules: models, catalog, MCP tool list (TL ticket to file)

The assistant and the MCP endpoint (`doc/ai.md`, haumacher/ehome #126, #129) on the modules of
8.0.0-alpha12.819:

- **The bundled model catalog lacks the current Claude models.** `models-dev-snapshot.json` of
  `tl-ai-core` knows `claude-sonnet-4-6` and `claude-opus-4-7`, not `claude-sonnet-5-5`,
  `claude-opus-5-5` and `claude-haiku-5-5`. Workaround: their metadata as `<model-overrides>` in
  `WEB-INF/autoconf/ehome.ai.config.xml`. **When fixed** (the snapshot knows them): remove the three
  overrides.
- **A thinking effort for `claude-haiku-5-5` is sent as a budget.**
  `AnthropicRequestBuilder.requiresBudgetTokens` answers true for every model whose name contains
  `haiku`, so a run on `claude-haiku-5-5` with an effort (the chat's thinking picker) sends
  `thinking: {type: "enabled", budget_tokens: N}`, which that model refuses with HTTP 400 (it takes
  `{type: "adaptive"}` and `output_config.effort`). Reproduction: pick Haiku 5.5 and an effort in the
  chat. Workaround: the override of `claude-haiku-5-5` declares `reasoning="false"`, so the chat offers
  no effort for it and the model thinks adaptively as it does unasked. **When fixed** (only the Haiku
  models before 5 take a budget): set `reasoning="true"` on the override.
- **`tools/list` of the MCP endpoint is the same for every caller** (the MCP Java SDK answers it from
  the global tool set; `ai/security/mcp-endpoint`, "What it does not do"): a viewer's client lists
  the writing tools of `data.write` and is refused when it calls one. No workaround. **When fixed**
  (the list follows the caller's tool access): `TestMcpEndpoint` asserts that a viewer does not list
  them.
- **`tl-ai-core` brings the browsers of Playwright** (`com.microsoft.playwright:driver-bundle`,
  200 MB) for its browser tools, also to an application that grants no browser tool: the war grew
  from 136 MB to 367 MB. Workaround: `ehome/pom.xml` excludes `driver-bundle` from `tl-ai-core`
  (the browser tools, group `egress`, granted to nobody, would fail when called). **When fixed**
  (the browser is an optional module): remove the exclusion.
- **A missing key is announced as a rejected key.** Without `tl_anthropic_api_key` the chat's headline
  reads "Das Modell hat den Zugangsschlüssel abgelehnt. Bitte wende dich an deine Administration."
  (the provider error kind `AUTHENTICATION`); only the detail below says that the secret
  `tl_anthropic_api_key` is missing. No workaround. **When fixed:** check the chat without a key.
- **`/ehome/mcp/` (with a slash) is answered with 404**: the transport of the MCP SDK
  (`HttpServletStreamableServerTransportProvider`) matches the end of the path against `/mcp`.
  No workaround; `doc/ai.md` names the address without the slash. **When fixed:** nothing to undo.

## Upstream tickets to follow

- TL #29797: see above (XML import into a transient candidate; `<versioned-master-data>`).
- TL #30087 (closed as invalid): see above (a composition walk in TL-Script fast enough for `SharedObjects`).
- TL #30192: see above (a fast TL-Script function for the composition descendants of an object).
- TL #29884, #29885: see above (access coverage of engine modules, the reason of refused commands).
- TL #30105: see above (a refused submit leaves its `<value-input>` editable).
- TL #30048: see above (agent access: the follow-ups of the module).
- TL #30200: see above (agent access: the size of an observation, `observe` without actions failing).
- TL #30191: see above (a failing class load aborts the module shutdown).
- TL #30249: see above (the `<with-result>` of a job runs before the window saw the job's commits).
- TL ticket to file: see above (AI modules: the Claude 5.5 models in the catalog, the thinking budget of Haiku 5.5, the MCP tool list per caller, the Playwright browsers, the message of a missing key, the MCP path with a slash).
- TL #30193, #30194, #30195, #30196: see above (jobs of the app: closing the dialog after its job, a cancel during the commit, the result of a cancelled job, the interrupt of a cancelled job closing H2).
