# Waiting for TopLogic

Gaps of the React UI (`ehome/src/main/webapp/WEB-INF/views/`; paths below are relative to the app module `ehome/` unless they name `ehome-virtual/`) and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha13.823) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

State of the upgrade to 8.0.0-alpha13.823 (2026-11-15; the build's tickets from the commits between
the tags `PREVIEW/TL_8.0.0-alpha12.820` and `PREVIEW/TL_8.0.0-alpha13.823` of the engine, confirmed in
Trac). Breaking changes adapted: a `ModificationListener` receives the pending changes as one
`PendingChanges` (TL #30225); a `JobMonitor` declares a point of no return (`uncancelable()`, TL #30250).
Shipped, and their workarounds removed:
- the jobs (TL #30196, #30250 with #30193, #30194, #30195, #30249 as duplicates): `Uninterruptible` and
  its catch-up of the window are gone, the bus accesses are interruptible sections of the job's
  `CancelScope`, the imports, the update from ETS, the scan and the read-back declare their point of no
  return before their commit, a cancelled multi-file import reports the files saved in `<on-cancel>`,
  the password dialogs run their job themselves, an ended job is dismissed by its display;
- the AI modules: the Claude 5.5 models and Haiku's thinking in the catalog (TL #30251, the model
  overrides are gone), `tl-ai-core` without Playwright (TL #30253, the exclusion is gone), no pre-commit
  guard of agent turns (TL #30255, `NewPartsSecurityContextFactory` is gone; each writing tool checks the
  project), the commit message of every writing tool (TL #30276), the MCP tool list per caller and the
  path with a slash (TL #30252), the message of a missing key (TL #30254);
- the coverage analysis counts the security root for the Create of a project (TL #30312, the rule
  `ehomeInstallerOnOwnedProject` is gone);
- views: the sidebar entry "Discover" withheld by a visibility condition (TL #30258, which closed
  TL #30310), the icons of a program in the options of a choice and as the icon of a tree node
  (TL #30316), the button of a parameter dialog labelled with the manufacturer's text (TL #30321), the
  breadcrumb following a rename (TL #30315);
- the observation of the agent access with stable ids (TL #30200, `doc/agent-access.md`), the module
  shutdown robust against a failing class load (TL #30191).
Still open: TL #29797 (closed, shipped in part), #29884, #29885, #30048, #30105, #30192, #30217,
#30274, #30275 (in testing), #30311, #30313, #30314.

Before (8.0.0-alpha12.820, 2026-11-11; its tickets are on the Gitea release page
`PREVIEW/TL_8.0.0-alpha12.820` of `TopLogic/tl-engine-7`): the fixes of TL #30201 and #30205 shipped.
Removed: `de.haumacher.ehome.etsImport.ConstraintVeto`, its test, the catch in `ImportJobs#importFile`
and the message `ERROR_IMPORT_CONSTRAINTS__FILE_PROBLEMS` (#30201: a failed job shows the problems of
a constraint veto as its error details, `CommandErrors.messages`; the tests that name the problems of a
refused commit read them with `CommandErrors.text`); `readonly="true"` of the derived columns of
`device-templates.view.xml` (#30205). The breaking changes of the build (#30167, #30205's
`DynamicVisibility`, #30207) touch no code of the app. TL #30196 is closed, and #30191, #30200, #30250
(which absorbed #30193, #30194, #30195 and #30249 as duplicates) and #30251 to #30255 are in testing,
but none of them is in 8.0.0-alpha12.820: their workarounds stay until the next upgrade. Still open:
TL #29884, #29885, #30048, #30105, #30192, #30217.

Before (8.0.0-alpha12.819, 2026-11-10): the fixes of TL #30211, #30214, #30215, #30216
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

## Memory of a product import committed step by step (TL #30311)

The import of a product file commits the shared data and then each application program in a
transaction of its own and holds the objects of earlier steps only by their keys (haumacher/ehome
#160, `ImportSteps`, `JoiningModelBinding#release`). The database still keeps every imported object
in memory until the import ends: the association caches an import fills while it creates objects
(TL #11817 keeps them after the commit) hold their targets strongly, and the cache of the
manufacturer's programs (`Manufacturer#programs`, an `IndexedLinkCache`, held by the cleanup action of
the newest `UpdateChainLink` and by the manufacturer while the next program is imported) reaches
every program, whose own caches reach all its parts. Measured with Theben's complete database
(119 programs, 350 MB of XML): about 1.7 GB live at the end of the import with `-Xmx2g` (it fits; in
one transaction it did not fit 4 GB). There is no API to drop the caches of an object, so there is no
minimal workaround; a larger database may not fit 2 GB.
**When fixed/available:** (caches of committed objects releasable, or held through keys) nothing to
remove; measure the Theben import again (its peak should stay at the size of one program).

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

Still open, checked on 8.0.0-alpha13.823.

**When fixed** (the engine covers or marks its own modules, or the check can be limited to the
application's modules): remove `excluded-modules`.

## Enumeration values unreadable without a grant on `tl.model` (TL #30275)

`SecurityConfigurationService.isAllowed` exempts a model element only from the DISCOVER check; the
READ of a `TLClassifier` is decided by roles like a business object, and E-Home grants nothing on
`tl.model`. For an installer or a viewer every enumeration value was therefore unreadable, and the
MCP tools (`ReadFilter` of tl-ai-core) left them out of every object they showed: a schedule's
weekdays as `[]` ("every day" by the model), its handling of missed firings as `null`
(haumacher/ehome #141). Root does not see it (administrator bypass).

Workaround in `WEB-INF/autoconf/ehome.security.config.xml`: `tl.model:TLClassifier` takes its roles
from the security root (security parent rule `ehomeRootForClassifier`) and every account (the engine's
role `SecurityStructure.user` of the group "user", since the roles per project of haumacher/ehome
#152) and the installers get READ on it; `TestAccessRights.testClassifiersReadable`.

In testing, not in 8.0.0-alpha13.823.

**When fixed** (the engine treats the read of a classifier as model data): remove the rule
`ehomeRootForClassifier` and the `<class name="tl.model:TLClassifier">` grant; the test stays.

## A role query answers from before a change of the stored roles (TL #30313)

Found with the roles per project (haumacher/ehome #152) in `TestAccessRights.testMembersSeeEachOther`
on H2 (the test database and the one of the Debian package): after a member was removed from the
last project it shared with another account, the incremental security update removed the role
`ehome.Collaborator` from the security storage (`AccessManager.getRoles` and `hasRole` for each role
alone answer without it), but `hasRole(viewer, account, [PersonalProfile, ehome.Collaborator])` - the
role query of `isDiscoverAllowed`, the same SQL text the thread had sent before the removal - still
answered `true`; the same roles in the other order answered `false`. A few other statements on the
connection in between make it answer right again. The SQL of
`SecurityStorage.hasRoleFromCollection` is built with literals, so a repeated question has the same
text; H2's reuse of the last result of a cached statement (`OPTIMIZE_REUSE_RESULTS`) is suspected.
Consequence: right after a removal, the removed member may still discover the other accounts (or a
removed reader the project) in the same request or the next one on the same connection.

No workaround. The test asserts the stored roles after the removal and leaves the decision out (a
comment there names this entry). **When fixed:** assert `isDiscoverAllowed` after the removal in
`testMembersSeeEachOther` again.

## The columns of a table stay as the roles were when it was built (TL #30314)

Found in the desk check of the roles per project (haumacher/ehome #163, point 2): an installer in no
project saw the project list with only the column "Ihre Rolle", also right after creating the first
project, and the column selection offered nothing else; after the next login the columns were there.
`AttributeColumn.resolve` drops a column whose attribute the user may read on no row
(`ModelAccessPolicy.onAttributeOfType`: no granted role held on any object,
`AccessManager.hasRoleOnAnyObject`), and `TableElement.createControl` resolves the columns once, when
the table is built; a later change of the user's roles (a project created, the user added to one)
refreshes the rows but not the columns. Reproduction in any app with per-object roles: a `<table>` with
`<column attribute="name"/>` over a type whose read grant names a role held only through a role rule on
the object; log in as an account holding that role nowhere, open the table, create an object giving the
account the role (or let another session add it): the row appears, the column does not.

Workaround in `projects/overview.view.xml`: the table is built only while the user sees a project (a
`<switch>` on the derived channel `anyProject`), so it is built after the first project arrives;
without a project the page says that there is none yet. **When fixed** (the column set follows a
change of the roles, or the table is re-resolved): put the table back in place of the switch; the text
for no project may stay as the empty state of the table, if the engine offers one.

## A refused command on a composition part (TL #29885)

Under alpha11 a refused check on an object whose type delegates to its access parent (a device, a
group address, a catalog entry) hid the command instead of disabling it. With the consolidated access
checks of alpha12 (TL #29856) a viewer sees these commands disabled with the reason ("Sie dürfen
dieses Objekt nicht ändern"); four import commands (rules, Zigbee devices, product data, Zigbee
catalog) are disabled with only their own label as the reason. The ticket is still open.

Since TL #30025 (8.0.0-alpha12.817) a command is hidden for a user who holds no role granting it on
any object. Since the roles per project (haumacher/ehome #152) a user may be the viewer of one project
and the editor of another: in the first, the commands that write are shown disabled with the reason
again, and the case of the ticket can show (an account that edits no project at all does not see
them). Still open, checked on 8.0.0-alpha13.823.

**On the next upgrade:** check the ticket; nothing to remove.

## A refused submit leaves its input editable (TL #30105)

The `<access>` check of a command decides the state of its button (TL #29856); the `<on-submit>`
command of a `<value-input>` has no button, so a refused check leaves the input editable and reports
the refusal only after the submit. Workaround in `projects/device-item.view.xml`: a device's parameter
inputs switch to their read-only form with `!canWrite($device)`, repeating the `<access>` check of
their `<on-submit>` (haumacher/ehome #65).

Still open, checked on 8.0.0-alpha13.823.

**When fixed** (the input follows the state of its submit command): remove the `!canWrite($device)`
case of the input kind in `device-item.view.xml`.

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
no MCP wrapper (hence `bin/ehome-agent.py`). Still open, checked on 8.0.0-alpha13.823.

**When fixed:** decide whether E-Home restricts issuing tokens (e.g. to `ehome.Installer`) with the
rule the module offers, and replace `bin/ehome-agent.py` by the MCP wrapper.

## A tree table rebuilt by an input keeps the selection but not its parents open (TL #30217)

A `<tree-table>` whose `children` depend on an input channel is rebuilt when the input changes;
`TreeSelectionBinding.structureChanged` keeps a surviving selection without opening the nodes
above it, and `applyChannelValue` then skips it as already shown. The same after a drop moves a
selected node under a closed one. In `projects/devices.view.xml` (haumacher/ehome #106: the switch
"Struktur" and the drop into a building part) the selection is cleared and written again, which
opens its parents.

**When fixed:** write the selection once in the switch and the drop of `projects/devices.view.xml`.

## OAuth login and discovery of the MCP endpoint (TL #30274)

`/ehome/mcp` admits personal access tokens only (`doc/ai.md`, "External agents over MCP"): a client
is set up with a token copied by hand and a hand-written `Authorization` header. A client that only
knows the browser login of the MCP specification cannot connect, and nothing tells an agent given
the application's address that the endpoint exists. `tl-ai-mcp-server` 8.0.0-alpha13.823 has the
token filter (`McpAuthenticationFilter`) and no authorization server.

The engine ticket covers what E-Home needs (specification round of 2026-11-12: E-Home is its own
authorization server, the user logs in with the E-Home account; a local client is the acceptance
case): the application as the authorization server with the normal login and a consent page,
discovery by the protected resource metadata, authorization code with PKCE, refresh tokens, the
client by a client id metadata document or dynamic registration, consents listed and revocable
beside the access tokens, which stay for scripts; the server card, `llms.txt` and the repaired token
dialogs.

No workaround.

**When available:** describe the login in `doc/ai.md` (a client is set up with the address alone),
keep the token path for `bin/ehome-mcp.py`, check the login from a local client against the app on
8080 and extend the endpoint's test by a request without a token (the metadata in the refusal).

## The table of the cookie pools after their move to tl-ai-browser (TL #30357)

TL #30253 (8.0.0-alpha13.823) moved the browser tools and their cookie pools from `tl-ai-core` into
the opt-in module `tl-ai-browser`, which E-Home does not use, without a migration for an application
that stays without it. A database of before keeps the table type `CookiePool`: at the start the engine
recovers it as an in-app schema (`inapp-schema-1.meta.xml` and `.config.xml`, written into the first
writable `WEB-INF/autoconf` - in a development start the source folder of `ehome-virtual`, in the
Debian package the deployed webapp), and the model upgrade deletes the module `tl.ai.cookiepool`, so
the table is an orphan.

Workaround: the migration `Ticket_30357_Drop_cookie_pool` (`WEB-INF/kbase/migration/ehome/`) deletes
the table type from the stored schema and drops its table (`<delete-table>`) before the automatic
schema step, so nothing is recovered (checked on a copy of `tmp/`; a database without the table is
left as it is).

**When fixed** (the engine removes the type for an application without `tl-ai-browser`): the migration
stays, as migrations do; nothing to remove.

## Upstream tickets to follow

- TL #29797: see above (XML import into a transient candidate; `<versioned-master-data>`).
- TL #30087 (closed as invalid): see above (a composition walk in TL-Script fast enough for `SharedObjects`).
- TL #30192: see above (a fast TL-Script function for the composition descendants of an object).
- TL #29884, #29885: see above (access coverage of engine modules, the reason of refused commands).
- TL #30105: see above (a refused submit leaves its `<value-input>` editable).
- TL #30048: see above (agent access: the follow-ups of the module).
- TL #30217: see above (a rebuilt tree table keeps its selection without opening its parents).
- TL #30274: see above (OAuth login and discovery of the MCP endpoint; accepted, not in 8.0.0-alpha13.823).
- TL #30275: see above (enumeration values unreadable without a grant on `tl.model`; in testing).
- TL #30311: see above (memory of a product import committed step by step).
- TL #30357: see above (the table of the cookie pools after their move to `tl-ai-browser`).
- TL #30313, #30314: see above (a role query after a change of the roles on H2, the columns of a table after a change of the roles).
