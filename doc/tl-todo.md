# Waiting for TopLogic

Gaps of the React UI (`ehome/src/main/webapp/WEB-INF/views/`; paths below are relative to the app module `ehome/` unless they name `ehome-virtual/`) and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha12.817) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

State of the upgrade to 8.0.0-alpha12.817 (2026-11-06): the fixes of TL #30086, #30088, #30125,
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

Still open, checked on 8.0.0-alpha12.817.

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
checked on 8.0.0-alpha12.817.

**On the next upgrade:** check the ticket; nothing to remove.

## A refused submit leaves its input editable (TL #30105)

The `<access>` check of a command decides the state of its button (TL #29856); the `<on-submit>`
command of a `<value-input>` has no button, so a refused check leaves the input editable and reports
the refusal only after the submit. Workaround in `projects/device-item.view.xml`: a device's parameter
inputs switch to their read-only form with `!canWrite($device)`, repeating the `<access>` check of
their `<on-submit>` (haumacher/ehome #65).

Still open, checked on 8.0.0-alpha12.817.

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
no MCP wrapper (hence `bin/ehome-agent.py`). Still open, checked on 8.0.0-alpha12.817.

**When fixed:** decide whether E-Home restricts issuing tokens (e.g. to `ehome.Installer`) with the
rule the module offers, and replace `bin/ehome-agent.py` by the MCP wrapper.

The observation of a window is unusable for a real page (TL #30200, found on 8.0.0-alpha12.817 behind the
Apache proxy of an installation): `observe --actions` inlined the same ~2.9 MB JSON schema of a model
name into every `selectByKey`, `activateByKey` and `dropObjects` command, 84 MB for a device page with
13 dropdowns, a table and a tree; `observe` without actions answered 502 within 3 s. No workaround:
`bin/ehome-agent.py` passes the answer through as it comes.

**When fixed:** check `observe` with and without `--actions` on a device page of a large project.

## Jobs of the app: cancel, import jobs (TL #30193, #30194, #30195, #30196)

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

**When fixed:** TL #30196: remove `de.haumacher.ehome.job.Uninterruptible` - its `run` from
`ImportEtsProjectJob`, `ImportProductDataJob`, `ScanJob`, `ReadBackJob` and `CompareJob`, its
`interruptible()` sections from `CalimeroScanBus` and `CalimeroReader` - and the refetch of
`RecordingJobMonitor#run` in the tests. TL #30193: run the job in the dialog with its own `<job-status>` and the
`<close-dialog>` first in the `<with-result>`, and drop the `importJob` binding of the dialog in
`projects/overview.view.xml`. TL #30194: declare the commit in `ImportJobs#importFile` as past the
point of no return. TL #30195: continue the chain of a cancelled multi-file import with the report
of the files imported.

## Upstream tickets to follow

- TL #29797: see above (XML import into a transient candidate; `<versioned-master-data>`).
- TL #30087 (closed as invalid): see above (a composition walk in TL-Script fast enough for `SharedObjects`).
- TL #30192: see above (a fast TL-Script function for the composition descendants of an object).
- TL #29884, #29885: see above (access coverage of engine modules, the reason of refused commands).
- TL #30105: see above (a refused submit leaves its `<value-input>` editable).
- TL #30048: see above (agent access: the follow-ups of the module).
- TL #30200: see above (agent access: the size of an observation, `observe` without actions failing).
- TL #30191: see above (a failing class load aborts the module shutdown).
- TL #30193, #30194, #30195, #30196: see above (jobs of the app: closing the dialog after its job, a cancel during the commit, the result of a cancelled job, the interrupt of a cancelled job closing H2).
