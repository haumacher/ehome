# Waiting for TopLogic

Gaps of the React UI (`ehome/src/main/webapp/WEB-INF/views/`; paths below are relative to the app module `ehome/` unless they name `ehome-virtual/`) compared with the classic layouts, and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha11) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

State of the upgrade to 8.0.0-alpha11 (2026-10-25): the fixes of TL #29798, #29872 to #29874, #29877,
#29880 to #29883, #29886 to #29892, #29895, #29896 and #29898 are on the engine's `master` but not
in 8.0.0-alpha11; their workarounds stay until the next release. TL #29876, #29884, #29885, #29893,
#29894 and #29899 are open.

## A tree table has no Excel export (TL #29976)

8.0.0-alpha11 brought `<tree-table>` and the Excel export of a table (`<export>`, `TableExportCommand`,
`TableViewExcelExport`), but only `TableElement.Config` declares `<export>`: `TreeTableElement.Config`
(through `AbstractTableElement.Config`) has none, and `TableExportCommand` configured as a command of
its own is a configuration error, although the export writes a tree as an outline of the sheet.
Reproduction: `<panel><tree-table root="..." children="..."><export/><columns>...</columns></tree-table></panel>`
is rejected as an unknown property `export`.

Workaround: `de.haumacher.ehome.view.ExportTreeTableElement` (`<export-tree-table>`), a `<tree-table>`
with the `<export>` of a table: it contributes the engine's `TableExportCommand` over the view of the
tree table to the toolbar of the panel around it, as `TableElement#contributeExportCommand` does. The
structure tabs (`projects/buildings.view.xml`, `projects/addresses.view.xml`, `projects/devices.view.xml`)
and the catalogs (`catalogs.view.xml`) use it.

**When available:** replace `<export-tree-table>` by `<tree-table>` and delete `ExportTreeTableElement`.

## A tree table has no node display of its own (TL #29977)

A `<tree>` shows a node by a `nodeContent` control provider (E-Home's `ScriptLabelControlProvider`: the
icon of the object, a label and a CSS class computed by TL-Script). A `<tree-table>` has no such
display: its first column shows a value by the type of the column alone (a reference by its own label
and icon, a text without icon), no column declaration chooses a display control or a CSS class per
cell (`Column#cssClass(row)` exists in the table API, but no declaration sets it), and the
`row-class` of a `<table>` is not offered (`TreeTableElement` passes no row class to `createTable`).
Reproduction: a `<tree-table>` over devices cannot show a device as "Taster (1.1.3)" with its icon,
nor mark a changed device, other than through a text column without the icon.

Workaround: `de.haumacher.ehome.view.ObjectColumn` (`<object-column>`), a `<computed-column>` that
displays its value as the tree's node display did (`ReactResourceCellControl` with the
`ScriptLabelControlProvider` resources: icon, the label of a `text` function, the class of a
`css-class` function) and sorts, filters, searches and exports by that label. It is the first column
of the four tree tables: the device label with the individual address in the building structure, the
connection label and the mark of an address without a datapoint type (`ehomeUntyped`) in the group
addresses, the mark of a changed device (`ehomeChanged`) in the topology.

**When available** (a column with a display control, or a node display of the tree table): use it and
delete `ObjectColumn`.

## A tree table neither reveals its selection nor catches up after being hidden (TL #29978)

Two differences of `<tree-table>` from `<tree>`, left visible (no workaround):

- An object written to the selection channel below a collapsed node is selected but not shown: the
  tree table does not expand its parents. Reproduction: in the building structure, select "Haus"
  (collapsed), create a room under it with "Neu"; the dialog writes the room into `selected`, the
  details show it, the tree table shows "Haus" still collapsed. The same holds for a new connection
  from "Objekt verbinden" in the group addresses.
- A tree table in a tab that is not shown misses the changes made meanwhile. Reproduction: in the
  building structure place a device of the topology with "Gerät zuordnen", switch to the tab
  "Geräte": the column "Standort" of that device is still empty (until the view is rebuilt).

**When fixed:** check both scenarios; nothing to remove in E-Home.

## A panel of its own size takes the whole height beside a filling one (TL #29982)

Since 8.0.0-alpha11 a panel around a table fills, so a `<stack>` holding it fills too; a sibling
panel of its own size then resolves the inline `height: 100%` every panel carries (`TLPanel`)
against the stack and takes all of it, while the panel with the table shrinks to its title bar.

Workaround: `fill="false"` on the panel with the table in `projects/device-programming.view.xml`
(Download), `projects/scan.view.xml` (devices found) and
`ehome-virtual/src/main/webapp/WEB-INF/views/virtual/virtual-device.view.xml` (traces): the tab
scrolls, as before alpha11.

**When fixed:** drop the three `fill="false"`.

## A tab strip wider than its container (TL #29983)

The tabs of a project (twelve) and of a device (seven, eight with the virtual device) are wider than
their container on a common screen; the strip neither scrolls nor offers the rest in a menu, so the
last tabs lie outside and selecting one scrolls the whole panel body sideways.

Workaround: none; the problem stays visible.

**When fixed:** nothing to remove; check the device tabs in the detail pane.

## Migrations of the application (TL #29979, #29980)

The first migration of E-Home (`WEB-INF/kbase/migration/ehome/Ticket_29806_Dissolve_ehome_mask.migration.xml`,
moving the root of the mask versions from `ehome.mask` into `ehome.operational`) needed two workarounds:

- TL #29979: a migration module without a stored version in the database never runs; the automatic
  model upgrade ran instead and replaced the singleton with an empty one. Workaround:
  `minimum-modules="ehome"` in `WEB-INF/autoconf/ehome.migration.config.xml`.
- TL #29980: `<update-class>` with a `new-name` in another module leaves the associations of the
  class in the old module, so `<delete-module>` fails. Workaround: an `UPDATE META_ELEMENT` in
  `Ticket_29806_Dissolve_ehome_mask_scripts/moveSingleton.h2.sql`.

**When fixed:** nothing to undo in the shipped migration; a later migration needs neither workaround.

## ETS import: workarounds for the XML import (TL #29795, #29797, #29880 to #29883, #29889 to #29891)

The ETS import (`de.haumacher.ehome.importer`, `etsImporter.xml`) has handlers of its own for what
the engine's XML import (`com.top_logic.xio`) cannot do yet. TopLogic 8.0.0-alpha11 shipped TL #29794
to #29797; with it, the import uses `<resolve-existing>` for the shared objects (master data,
catalogs, hardware, programs: found by their `extId` among the existing objects of the types it
lists), `<with-var>` for the language of the texts (`DefaultLanguage`, variable `language`) and the
version of the master data (`MasterData@Version`, variable `masterDataVersion`), and the engine's
`join-duplicates` linking an object only once. What stays:

- TL #29795 (shipped, not adopted): `<set-translation language-var="language" fill-fallback="true">`
  differs from the import's texts in two respects. It stores an empty attribute (ETS writes `Text=""`
  for most parameters and communication objects) as an empty translation for the language and the
  fallback language, where the import leaves the attribute unset; and without a language in the
  variable (the master data name none) it reports "no language" and drops the text, where the import
  files it under the fallback language - `<with-var>` binds only an XML attribute, no default value.
  Reproduction: `<set-translation name="text" xml-attribute="Text" language-var="language"
  fill-fallback="true"/>` on `<P Text=""/>` sets `text` to a literal key with `""`; outside a
  `<with-var>` it reports `ERROR_NO_LANGUAGE`. Workaround: `<i18n-property>` (`I18NPropertyImport`,
  `I18NTexts#language`, `#set`). TL #29973 (an option to skip an empty value, a default
  language or a default value of `<with-var>`). **When available:** use `<set-translation>` there.
- TL #29797 (shipped in part): the engine's `<unless-known>` skips a known element as a whole. A known
  application program must still get the texts of the languages it lacks (haumacher/ehome #38: ETS
  writes a program in the language of the installation exporting it). Workaround:
  `<unless-known-program>` (`UnlessKnownProgram`, `ProgramLanguages`,
  `TranslationsImport#importBaseTexts`), a tag of its own besides the engine's. Missing in the
  engine: importing the attributes of a known element (and of its descendants, each to the object of
  its ID) without its structure, and a condition on the known object (the languages it records,
  `Program#languages`). Reproduction: a known object `I1` (texts in `de`) and the element
  `<Item Id="I1" DefaultLanguage="en-US" Name="..."/>`: `<unless-known id-attribute="Id">` skips it, so
  `name@en` stays unset. TL #29974. **When available:** replace `<unless-known-program>`.
- TL #29797 (shipped in part): `<resolve-existing>` indexes all its objects once per import, at the
  first ID it misses. The parts of the application programs (about ten thousand per program) are
  made known per program instead, when the import first asks for an ID within a program (their IDs
  start with the program's ID): `SharedObjects`, an `IdLookup` that `Importer` installs with
  `ImportContext#withIdLookup` outside the `<resolve-existing>` of `etsImporter.xml`. Missing in the
  engine: a lookup partitioned by a key (e.g. the prefix of an ID up to a separator) that loads one
  partition on demand (TL #29975). **When available:** declare it in `etsImporter.xml`.
- TL #29797 (shipped in part): `<versioned-master-data>` (`VersionedMasterData`, `MasterDataUpdates`,
  `ObjectMerge`) imports a known mask version as a transient candidate
  (`JoiningModelBinding#setTransient`) and, at the end of the import, updates the known one in place
  from it (newer master data) or compares and warns (older or the same). The engine has no import of
  an element into a transient candidate of a known object. **When available:** use the engine's.
- TL #29797 (shipped in part): `<translations>` (`TranslationsImport`) reads the `Languages` sections
  and ignores unknown IDs. With alpha11 they could be read by `<with-var>` (`Language@Identifier`),
  `<object-ref ignore-unresolved="true">`, `<switch>` and `<set-translation>`, but not: the attribute
  a translation goes to depends on the type of the object (`Text` is the description of a parameter,
  the name of a variant), a translation of a known program sets only the languages it lacks (#38),
  and an empty translation is skipped (TL #29795 above). **When available:** express them in
  `etsImporter.xml`.
- TL #29880: `<dispatch>` skips an unknown element and its subtree silently, so a construct of a newer
  KNX schema vanishes without a message (found with the ETS 6 import, #32). No workaround: new
  constructs were found by comparing element paths by hand (`doc/knx/schema-coverage.md`).
  **When available:** switch the ETS import to warn on unknown elements and list what it reports.
- TL #29881 (defect): linking an object into a second composite container aborts the whole import
  ("part of multiple containers") instead of reporting an error at its location. Workaround for the
  one known case: a `ParameterRefRef` placed in several branches becomes a `SharedPlacement`
  (`<place-reference>`, `PlaceReference`). **When fixed:** nothing to undo; a new construct of that
  kind is then reported instead of breaking the import.
- TL #29883 (defect): an unset boolean attribute reads as `false` on a stored object and as `null` on
  a transient one. Workaround: `ObjectMerge` treats `null` and `false` as equal when it compares a
  known mask version with its transient candidate (#34); the Zigbee import (`ZigbeeImport#set`, #40)
  does the same when it compares a stored value with the export's, and a stored flag reset to `null`
  reads `false`. **When fixed:** drop those cases.
- Deleting the parts the import drops one at a time took about 13 s per import of a real sample (the
  referrer scans of TL #29876). Workaround: `MasterDataUpdates` deletes them in one `deleteAll`.
- TL #29882: a `<switch>` case matches one value only, so the flags ETS 6 writes as `true` and ETS 4
  as `1` need duplicate cases in `etsImporter.xml`. **When available:** merge each pair into one
  case listing both values.
- TL #29889 (defect): adding one element to a set reference reads the whole set, so building a
  program's thousands of parameters one by one is quadratic. Workaround: `JoiningModelBinding`
  buffers additions to unordered multiple references of persistent objects and stores each set in
  one update at the end of the import (`flush()`); a buffered element the set holds already leaves it
  unchanged. Since alpha11 the engine's `AbstractModelBinding#addValue` (TL #29794) reads the whole
  value of every multiple reference before it adds an element, so the buffering also saves that read.
  **When fixed:** add directly again and drop the buffering.
- TL #29890 (defect): `TLModelUtil.findType` and the compatibility check scan all modules by name on
  every call. Workaround: `JoiningModelBinding` caches types per import; `ExtIds.isA` compares the
  names of the cached generalizations. **When fixed:** use `TLModelUtil.isCompatibleInstance` again.
- TL #29891 (defect): default values looked up per created transient object, overhead per attribute
  read, deletion as expensive as import. No workaround; the tests avoid deleting shared data
  (`TestRoundTrip` runs first, `SharedData.resetMaskVersions` updates in place).

## Derived references and the commit check (TL #29798)

A derived reference (`<storage-algorithm><query>`) is computed with security disabled in its
expression, but `AttributeByExpression#getAttributeValue` runs it through a `QueryExecutor` whose
result filter for the reading user's rights stays on. Where no user is logged in - the commit check
of `ModelService`, which runs the `<constraint-by-expression>` checks, in tests and in background
work - the derived reference reads as empty for every object not created in the current
transaction. Reproduction: the constraint

```
value -> obj -> if($obj.get(`m:T#derivedRef`) == null, "empty", null)
```

on an object whose derived reference points to an object of an earlier commit rejects every commit
without a user (seen with `ParameterValue#valueType` when `TestEtsImport` imported a second project
using an already known program).

Workaround: the options, constraints, visibility and display of `ehome.operational:ParameterValue`
call the TL-Script function `parameterValueType` (`ParameterValues.valueType`, navigating the stored
references) instead of reading the derived `valueType`.

**When fixed:** read `ParameterValue#valueType` in those scripts instead of calling the function.

## Groups of a table in row order (TL #29812)

Since 8.0.0-alpha11 a column declares its own `<comparator>`, which orders the groups it forms. The
comparator sees only the two cell values, though, neither the rows nor the table's inputs. The pages
of a program's parameters and communication objects (`catalogs/program.view.xml`, grouped by the
path text of the page, `de.haumacher.ehome.knx.ProgramPages`) carry no order of their own; their
order is that of the rows, which the row function computes from the structure.

Workaround: the page column declares `<comparator>a -> b -> 0</comparator>`: all pages rank equal,
and the stable sort of `ListRowSource` keeps the groups in the order of their first rows. This
relies on the stability of the group sort, which the view layer does not document. (The former
`de.haumacher.ehome.view.RowOrderColumn` is deleted.) TL #29981: a declared way to keep
the groups of a column in row order, or a comparator that sees the rows.

**When available:** declare the row order instead of the tie comparator.

## Live sources of a view (TL #29814)

Since 8.0.0-alpha11, `<source-channel>` with a `ChannelSource` brings what a server-side source
publishes into a channel of a displayed view, throttled by its `update-interval`. The group monitor
(`projects/monitor.view.xml`, `state` fed by `de.haumacher.ehome.monitor.MonitorSource`, 250 ms) and
the simulation of a project (`projects/simulation.view.xml`, `refresh` fed by
`de.haumacher.ehome.simulation.SimulationSource`, 500 ms) use it; `WindowUpdates`,
`WindowPublisher` and `SimulationUpdatesElement` are deleted. The source API has no signal for a
window that is gone, and the window registry of alpha11 does not collect a closed page: a closed
tab only reports `beforeunload`, which marks the window unloaded and detaches its tree (the
subscription ends), and since nothing calls `ReactWindowRegistry.sweepUnloadedWindows()` (it exists
only in newer engine sources) the window stays registered until the session ends. Reproduction: a
view with a `<source-channel>` whose `ChannelSource` logs `getWindow(windowName)` at the end of its
subscription and 60 s later; close the tab: the entry is still there, with `getUnloadedAt() != 0`,
long after the 30 s grace for a reload. `MonitorSource` therefore counts a window as gone when it
is no longer registered or its page has been unloaded for 30 s (`MonitorSource.UNLOAD_GRACE`, the
engine's `UNLOAD_GRACE_MILLIS`); it detaches the monitor when the view goes with its window, at the
next change of the monitor for a window that hid the view, and 31 s after an unload for a bus
without traffic. The engine's `master` tears down a window whose client stays away (TL #29869, after alpha11); with
it, the end of the subscription replaces this check.

What remains:

- The Zigbee tab (`projects/zigbee.view.xml`, `<zigbee-updates>`) still wraps its content in
  `ZigbeeUpdatesElement` (`ProjectUpdatesElement`, now subscribing a `ProjectSource` through the
  engine's `ChannelPublisher`, which refetches the knowledge base on the delivering thread before
  writing `refresh`). It can become a `<source-channel>` with a `ProjectSource` that refetches before
  it publishes; then `ProjectUpdatesElement` and `ZigbeeUpdatesElement` go.
- The virtual device (`ehome-virtual/src/main/webapp/WEB-INF/views/virtual/virtual-device.view.xml`):
  its status and the number of services recorded change while a programming tool downloads into it,
  but the recorder tells nobody of a change. The view recomputes them when it is displayed and on
  its "Refresh" command, and its start and stop commands take a derived channel of the device and
  the last status as input, so that their executability is checked again after a start or stop (an
  `<executability>` rule cannot observe state outside the model). A change notification of the
  recorder would let a source channel follow it.

## A slider's range cannot come from a channel (TL #29872)

`NumberInputControlProvider.Config` takes `min`, `max` and `step` as fixed numbers; no channel or
expression can set them. The monitor's send form cannot use the range `GroupMonitor.target`
computes for the datapoint type.

Workaround: two fixed slider kinds, `percent` (0–100, DPT 5.001) and `scale` (0–255, DPT
5.004/5.005/5.010); DPT 5.003 (0–360) and 5.006 (0–254) keep the number field
(`DatapointCodec.kind`, `projects/monitor.view.xml`).

**When available:** one slider kind bound to the target's `min`/`max`/`unit`, and slider kinds for
5.003 and 5.006.

## A time picker has no seconds (TL #29873)

`ReactDatePickerControl.Kind.TIME` uses the ISO pattern `HH:mm` and has no step option: the
monitor's DPT 10 input cannot enter or show seconds ("Now" keeps them in the value until the field
is edited).

Workaround: none.

**When available:** a time picker with seconds for the DPT 10 input.

## A choice always sorts its options by label (TL #29874)

`SelectControlProvider.createControl` hands `ReactDropdownSelectControl` a `LabelComparator`
unconditionally and has no setting to keep the order of the options: the monitor's days of the week
came out alphabetically ("Dienstag, Donnerstag, …, Kein Tag, …").

Workaround: `de.haumacher.ehome.view.OptionOrderSelectControlProvider`, a `SelectControlProvider`
passing no comparator, as `<input-control>` of the monitor's choices of the step size, the day of
the week and the enumeration value (`projects/monitor.view.xml`), and of the switching and state
objects of an object follower, ordered by object number (`projects/simulation.view.xml`).

**When available:** use the engine's setting in those five inputs and delete
`OptionOrderSelectControlProvider`.

## Deleting objects scans the referrer tables (TL #29876, #29877)

With branch support (the engine's default), every index starts with the column `BRANCH`. When
objects are deleted, `ReferenceDeletionHelper` looks for their referrers with
`(BRANCH, rev, SOURCE_ID) IN (...)` queries on the association tables (`HAS_STRUCTURE_CHILD`,
`HAS_WRAPPER_ATT_VALUE`); H2 seeks an IN list only on the first column of an index, so it seeks
`BRANCH = 1` and reads every row. Deleting about 400 projects, manufacturers, datapoint types and
mask versions took 15–30 s.

Workaround: E-Home runs without branches: `top-logic.singleBranch.config.xml` (from tl-core) in
`WEB-INF/conf/metaConf.txt` drops `BRANCH` from the tables and their keys. The same deletions take
1–2 s, and the test suite halved (163 s to 85 s). The switch changes the table layout; a database
created before it (an old `tmp/`) does not fit and is not migrated. No developer article covers
this configuration (TL #29877).

**When available:** nothing to undo; read the article of TL #29877 for anything else the persistency
configuration should set.

## Access coverage of engine modules (TL #29884)

The engine (8.0.0-alpha11) ships model modules whose types have no complete access definition: at
the start of E-Home, `SecurityCoverageCheck` reports 36 engine types in 15 modules (`Contacts`,
`SearchExpressionStructure`, `SecurityStructure`, `Tag`, `mandatorStructure`, `orgStructure`,
`tl.accounts`, `tl.comments`, `tl.contact.ui`, `tl.core`, `tl.folder`, `tl.model`, `tl.reporting`,
`tl.search`, `tl.units`; e.g. `tl.accounts:Group`, `tl.model:TLClass`, `tl.core:Revision`: no role
source and no read grant). An application that switches `TestSecurityCoverage` on
(`test-security-coverage` of `ElementTestCollector`) fails on them although it does not grant them
to its users, and the startup log reports them on every start. The check has no way to analyze only
the modules of the application except by listing every engine module as excluded.

Workaround in `WEB-INF/autoconf/ehome.security.config.xml`: `SecurityCoverageCheck` excludes these
15 modules.

**When fixed** (the engine covers or marks its own modules, or the check can be limited to the
application's modules): remove `excluded-modules`.

## A refused command on a composition part is hidden, not disabled (TL #29885)

`ModelAccessPolicy.grantedToNoRole` decides "hide" from the grants of the object's own type. A type
that delegates to its access parent has no grants of its own, so every refused `<model-access>`
check on such an object (a device, a group address, a catalog entry) hides the command instead of
disabling it with the reason: a viewer does not see "Download" or "Compare with the device" at all,
while "Choose the gateway" (checked on the project, which has grants) is disabled with its reason.
No workaround (the behavior is safe, only less informative).

**When fixed** (the decision follows the access parent): nothing to change; the commands show
disabled with the reason.

## A confirmation shows its lines as one paragraph (TL #29886)

The `<confirm>` of a command collapses the line breaks of its message. The deploy confirmation
(`ImageComparison.confirmation`, haumacher/ehome #47) lists the stored revision, the counts of both
images and the changed parts on lines of their own; they run together, still readable because each
line is a full sentence. No workaround.

**When fixed:** nothing to change; the lines show as written.

## Rule editor: no suggestions in a text field, no normalizing of input (TL #29887, #29888, #29892)

The rule editor (haumacher/ehome #57) stores expressions as text. A text field cannot offer the
project's datapoints while typing (TL #29887); workaround: a read-only "Datenpunkte" table beside the
form lists them as `@"1/2/3"`. A form cannot store an entered expression in its canonical form
(TL #29888); workaround (haumacher/ehome #60): the forms of the rule views (`rules.view.xml`,
`rule-actions.view.xml`, `schedules.view.xml`) replace the form's save by a `<save-action>` chain that
stores the form state, calls `ruleNormalize` (`RuleNormalization`) in the same transaction and leaves
edit mode with `<discard-form-state/>` (no action only leaves edit mode, TL #29892). Texts written another way (a script, the API) are stored as
given and canonicalized on export.

**When available:** offer the datapoints as suggestions in the expression fields and drop the table;
normalize in the fields and drop the `<save-action>` chains and `ruleNormalize`; until then, replace
`<discard-form-state/>` by the action of TL #29892 once it exists.

## Upstream tickets to follow

- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29793: developer-docs recipe for an application-defined primitive type (group addresses).
- TL #29794 to #29797: shipped in 8.0.0-alpha11; #29794 and #29796 are undone, #29795 and #29797 in part,
  see above (XML import).
- TL #29798: see above (derived references in the commit check).
- TL #29884, #29885: see above (access coverage of engine modules, hidden instead of disabled commands).
- TL #29886: see above (line breaks in confirmations).
- TL #29887, #29888, #29892: see above (suggestions and normalizing in text fields, leaving edit mode).
- TL #29895: `deploy/local` applies only to the module in the working directory, so the app starts
  from `ehome/` and `ehome/pom.xml` points `h2_name` and `tl_storage_path` at the root `tmp/`
  (overriding the environment variables of the same names; use `-Dehome.dataDir`). **When fixed:**
  start from the root and drop the two properties.
- TL #29896: an overlay cannot add a tab inside a view (unkeyed split panes and switch cases), so the
  device tab bar lives in `projects/device-tabs.view.xml`, which `ehome-virtual` overlays.
  **When available:** fold it back into `devices.view.xml` and contribute the tab there.
- TL #29894: the model upgrade logs an ERROR with stack trace when a stored annotation of the old
  model no longer instantiates (an options expression whose script function changed its arity);
  the upgrade itself succeeds. No workaround.
- TL #29893: the model upgrade's log does not name a reference whose target type changed (the
  retargeted function roles of haumacher/ehome #61 were checked in the database instead). No workaround.
- TL #29812: see above (groups in row order: a tie comparator until TL #29981).
- TL #29814: see above (live sources: the Zigbee tab and the virtual device remain; a closed page is collected only with TL #29869).
- TL #29872: see above (slider range from a channel).
- TL #29873: see above (time picker seconds).
- TL #29874: see above (option order of a choice; when available: delete `OptionOrderSelectControlProvider`).
- TL #29876: see above (referrer scans when deleting).
- TL #29877: see above (developer article on the persistency configuration).
- TL #29880 to #29882: see above (XML import: unknown elements, a second container, multi-value cases).
- TL #29883: see above (an unset boolean on stored and transient objects).
- TL #29889 to #29891: see above (import performance: set additions, type lookups, defaults, reads, deletion).
- TL #29973 to #29975: see above (XML import after alpha11: empty texts and a default language, attributes of a known element, a partitioned lookup).
- TL #29976 to #29978: see above (tree tables: export, node display, revealing the selection and catching up).
- TL #29979, #29980: see above (migrations of the application).
- TL #29981: see above (groups of a column in row order).
- TL #29982: see above (a panel of its own size beside a filling one).
- TL #29983: see above (a tab strip wider than its container).

## No WebSocket in the development start (TL #29898)

The controllers connect over a WebSocket (haumacher/ehome #54, `doc/controllers.md`). TopLogic's
development start (`tl-ide-jetty` `Bootstrap`, Jetty 12.1.14 ee10) runs no WebSocket implementation:
no Jakarta WebSocket `ServerContainer` in the servlet context, and no annotation scanning that would
run the initializer of one on the classpath. The servlet API's own HTTP upgrade is no way around it:
in Jetty 12.1.14 the request of the upgrade keeps its end-of-content mark, so a request without a
body (every WebSocket handshake) reads only its end after the upgrade, and the next bytes are parsed
as a new HTTP request (`HttpConnection.HttpStreamOverHTTP1#read`, an upstream Jetty issue).

Workaround: Jetty's `jetty-ee10-websocket-jakarta-server` with `test` scope in `ehome/pom.xml` (the
classpath of the tests and of `exec:java`, not the war), without `jetty-ee10-annotations`; and
`ControllerSocketRegistration` runs Jetty's initializer by its name when the container has no
`ServerContainer`. **When available** (the development start provides Jakarta WebSocket): remove the
dependency and the fallback in `ControllerSocketRegistration#container`.

## No at-rest encryption out of the box (TL #29899)

The secret of an enrolled controller (haumacher/ehome #54) must be readable by E-Home to check the
controller's HMAC, so it cannot be hashed. TopLogic's `EncryptionService` needs a key set up per
installation (password-based), so an application cannot rely on it to encrypt an attribute at rest.

Workaround: the secret is stored as is in the hidden attribute `Controller#secret` with a read grant
without roles (only administrators read it through the model), like the controller token. **When
available** (an attribute storage that encrypts with a key the engine manages): store
`Controller#secret` and `Controller#token` encrypted, with a migration of the existing values.
