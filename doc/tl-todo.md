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

## Tree tables

The classic UI showed the building structure, the group addresses, the devices and the catalogs as
tree grids: a tree with columns (e.g. name, type, address, external ID) and inline editing.

The view layer has no tree table: `<tree>` (`TreeElement`) shows one label per node, `<table>`
(`TableElement`) has no tree mode (as of 8.0.0-alpha10 and tl-engine `master` of 2026-10-10). The
views therefore show a tree plus a form for the selected node (`projects/buildings.view.xml`,
`projects/addresses.view.xml`, `projects/devices.view.xml`, `catalogs.view.xml`).

**When available:** replace the trees by tree tables with the columns of the classic grids
(`name, tType, extId`; `name, address, extId`; `name, extId, location, address`;
`name, sections, variants, extId`) and keep the detail form for the remaining attributes.

## Excel export

The classic grids offered an Excel export (`StreamingExcelExportHandler`). The view layer has no
export of a `<table>`/`<tree>` and no command that delivers a file to the browser (8.0.0-alpha10;
`ReactDownloadControl` exists but no view element or action uses it).

**When available:** add the export command to the trees/tables of the structure tabs and the
catalogs.

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

## Derived attributes in a form in edit mode (TL #29799)

A derived attribute shown in a form does not follow the values entered in the same form: in the
details of a placed parameter (`catalogs/program.view.xml`), "Wirksame Vorgabe"
(`ParameterView#displayDefault`) and "Vorgabe von" (`ParameterView#defaultSource`) keep their stored
state while a default is entered or the parameter is changed, and show the new state only after
saving. Both read other derived attributes (`defaultSource`, `displayValue`).

The same holds for a script calling a TL-Script function implemented in Java, such as
`parameterValueType`: the script tracer records only the model
accesses written in TL-Script, so a function implemented in Java (`GenericMethod`) cannot declare
what it reads, and changing the parameter in the form does not show the field of the new type's kind
until saving. The engine needs a way for a script function to report its dependencies to the
tracer.

Workaround: the scripts a form re-evaluates while editing - the four `dynamic-visibility` scripts
of the typed attributes of `ehome.operational:ParameterValue` and the options of its `choice` -
write the navigation to the parameter type in TL-Script instead of calling `parameterValueType`;
the constraints (run at save and commit) and the display keep calling the function. Likewise the
options of the switching and state objects of an object follower (`ehome.runtime:ObjectFollower`,
haumacher/ehome #62) navigate from the device in TL-Script, so they follow the device entered in the
same form. No workaround for the derived fields: they are correct after saving.

The configuration of a device (`projects/devices.view.xml`, `projects/device-item.view.xml`) is
computed by Java functions (`DeviceFunctions`: `deviceValue`, `deviceItems`, `devicePages`, …),
because `DeviceConfiguration` is the one definition of what the device's values select. It is shown
in channels, a tree and an object list, not in a form: these name the settings, connectors and
connections of devices in `observed-types`, and the tree takes the selected cases of the switches
(`deviceSelectedCases`) as an input, so that a change of a value rebuilds the pages it opens or
closes.

**When fixed:** call `parameterValueType` in the visibility and options scripts of `ParameterValue`;
the `observed-types` of the device configuration may stay (they are what the channels need anyway).

## Model change of an existing database: new generalization with overrides (TL #29800)

Adding a generalization to a class and, in the same change, overriding attributes of the new
generalization (`ehome.physical:ParameterSetting` became an `ehome.operational:ParameterValue` and
overrides `number`, `decimal`, `text`) cannot be applied to an existing database: the model patch
at startup aborts with "Override failed. ehome.physical:ParameterSetting#number is declared to be an
override, but is not overriding anything (ehome.physical:ParameterSetting extends
tl.model:TLObject.)" - the overrides are applied before the generalization. A new database is set
up correctly.

Workaround: none; the local database (`tmp/`) was reset for this change (there is no production
data yet). The app no longer contains such an override: the overrides were later removed and the
typed attributes are labeled on the interface only.

**When fixed:** nothing to remove; check that such a change applies to an existing database.

## Master-detail of the device settings without `<adaptive-detail>` (TL #29801)

`<adaptive-detail>` splits selector and detail at a fixed 32/68
(`ReactAdaptiveDetailControl#buildRegularSplit`); its `detail-size` applies only to the drawer
display. With 32 % the settings table of a device (name, value, default) showed only its first
column.

Workaround: none any more. The settings table with its detail in a `<split-panel>` (tab "Geänderte
Werte" of `projects/devices.view.xml`) was replaced by the grouped list of changes, each entry with
its input (haumacher/ehome #15), which needs no master-detail.

**When available** (configurable selector share): nothing to remove.

## Error toast when a table in a switch case disappears (TL #29580)

A `<table>` inside a `<switch>` case whose rows read an input channel failed with a
`NullPointerException` in `RowSourceObserver#deregisterObjectListeners` when that channel changed so
that the case was removed (the toast also aborted a create command before its dialog closed). The
fix (registration handles) shipped in 8.0.0-alpha11; the entries panel of the types tab of
`catalogs/program.view.xml` is back in a `<visible-if>` on `selectedEnumeration`.

What remains: the connections table of a group address (`projects/addresses.view.xml`) sits in a
panel of its own below the details, outside the switch over the selected node, and reads the address
of the selection.

**Now that it is fixed:** the connections table of an address may move back into the case of an
address.

## Line numbers of XML import messages with a thousands separator (TL #29804)

The location the engine adds to a message of the XML import (`I18NLogAsXMLStreamLog.withLocation`,
keys `com.top_logic.basic.xml.log.I18NConstants.AT_LOCATION__FILE_LINE_COL_DETAIL` and
`AT_LOCATION__LINE_COL_DETAIL`, texts `In {0} line {1} column {2}: {3}`) formats the line and column
as numbers of the locale, so a line beyond 999 reads "line 4,433" (English) or "Zeile 4.433"
(German). Reproduction: import `src/test/fixtures/TestDynamic.knxproj` into a database without its
program; the ETS import report shows "In knxproj:M-0008/M-0008_A-10D7-11-A0E5-O000C.xml Zeile 4.433
Spalte 183: …". The texts need `{1,number,#}` / `{2,number,#}` (and `{0,number,#}` /
`{1,number,#}` for the variant without file).

Workaround: none; the message is readable, the separator stays visible.

**When fixed:** nothing to remove; check the line numbers in the ETS import report.

## A `<value-input>` cannot be mandatory (TL #29805)

A `<value-input>` of an enumeration type always offers "no value": `ValueInputElement` builds an
`AbstractFieldModel`/`SimpleSelectFieldModel` that is never mandatory, and the configuration has no
option for it. Reproduction: `<value-input type="ehome.operational:InsertPosition" value="x">` with
`<input-control class="com.top_logic.layout.view.form.SelectControlProvider" display="radio"/>`
shows the radio buttons "Kein Wert", "Im ausgewählten Element (am Ende)", "Nach dem ausgewählten
Element".

Workaround: none needed beyond the channel: the direction of a connection in
`projects/connect-group-address.view.xml` and the reference the changes of a device are compared
with (tab "Geänderte Werte" of `projects/devices.view.xml`) are derived channels with a `reverse`,
so choosing "no value" falls back to the default; the option stays visible. The value of a parameter in
`projects/device-item.view.xml` treats an emptied input as the default. (The position choice of
`catalogs/create-structure.view.xml` is gone: a new structure element takes its default place and is
moved by drag and drop in the structure tree, TL #29776.)

**When available:** declare the direction of a connection and the reference of the changes mandatory.

## A singleton turns every generated wrapper of its module into a structured element (TL #29806)

`InterfaceGenerator` decides by `TLModelGenerator.isStructure(module)`, i.e. by the module carrying
`TLSingletons`, that a generated interface without generalizations extends `StructuredElement`
instead of `Wrapper`. Putting the root of the shared mask versions into `ehome.operational` turned
all its wrappers into structured-element wrappers although no model class specializes
`tl.element:StructuredElement` (regression of TL #26716).

Workaround: the root lives in the separate module `ehome.mask` (`ehome.mask.model.xml`, no generated
bindings, reached by `tValueByName`); the mask classes stay in `ehome.operational`.

**When available:** move `MaskVersions` into `ehome.operational`, regenerate, delete `ehome.mask`
(with a model migration renaming the module of the root).

## Classifier constants of two enumerations collide in the generated factory (TL #29807)

`FactoryGenerator` names a classifier constant `<CLASSIFIER>_<ENUM>_CLASSIFIER` with a single
underscore, which is ambiguous: `LcFilterMemory` of `AddressSpace` and `LcFilter` of
`MemoryAddressSpace` both became `LC_FILTER_MEMORY_ADDRESS_SPACE_CLASSIFIER`, and the factory did
not compile.

Workaround: the enumeration of the memory a load step writes to is named `MemorySpace` instead of
`MemoryAddressSpace` (the schema's `LdCtrlMemAddrSpace_t`).

**When available:** nothing to undo; the name may stay.

## A tree keeps a deleted object in its selection (TL #29810)

The selection channel of a `<tree>` still holds a node's object after that object was deleted in
another view (a connection removed in the device view while selected in the group address tree),
and every script over the selection fails with a `DeletedObjectAccess` toast until another node is
selected; a `<table>` drops such a selection, a tree does not.

Workaround: the TL-Script function `existingObject(x)` turns a deleted object into `null`; every
reader of the tree selection in `projects/addresses.view.xml` goes through it, and the remove
command selects the address before deleting its connection.

**When available:** drop `existingObject` and read the selection directly.

## Groups of a table in row order (TL #29812)

Since 8.0.0-alpha11 a column declares its own `<comparator>`, which orders the groups it forms. The
comparator sees only the two cell values, though, neither the rows nor the table's inputs. The pages
of a program's parameters and communication objects (`catalogs/program.view.xml`, grouped by the
path text of the page, `de.haumacher.ehome.knx.ProgramPages`) carry no order of their own; their
order is that of the rows, which the row function computes from the structure.

Workaround: the page column declares `<comparator>a -> b -> 0</comparator>`: all pages rank equal,
and the stable sort of `ListRowSource` keeps the groups in the order of their first rows. This
relies on the stability of the group sort, which the view layer does not document. (The former
`de.haumacher.ehome.view.RowOrderColumn` is deleted.) Follow-up ticket to file: a declared way to
keep the groups of a column in row order, or a comparator that sees the rows.

**When available:** declare the row order instead of the tie comparator.

## A tree ignores updates of objects it does not display yet (TL #29813)

`ObservableTreeModel` reconciles a `<tree>` on a create of an object of its `observed-types`, and
on an update or delete of an object it displays; an update of an object it does not display is not
observed, even where the tree's `parents` function would say where the object hangs now. In the
building tree (`projects/buildings.view.xml`) a device placed in a part from the device view, or
one placed nowhere before, changes only `ehome.physical:Device#location` of a device not yet in the
tree: the device did not appear under its part until the view was opened again.

Workaround: the derived channel `placements` (the locations of the project's devices, observing
`ehome.physical:Device`) is an input of the tree, which is built anew (with its open nodes) when a
device is placed or removed.

**When fixed:** drop the `placements` channel and the tree input.

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
without traffic. A signal of the source API for a window that is gone (or the sweep running) would
replace this check.

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

- TL #29789: `tl:generate-java` cannot bootstrap a version change (the generator is therefore not
  bound to the build, see `CLAUDE.md`).
- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29793: developer-docs recipe for an application-defined primitive type (group addresses).
- TL #29794 to #29797: shipped in 8.0.0-alpha11; #29794 and #29796 are undone, #29795 and #29797 in part,
  see above (XML import).
- TL #29798: see above (derived references in the commit check).
- TL #29799: see above (derived fields and Java script functions in forms).
- TL #29800: see above (model patch of a new generalization with overrides).
- TL #29801: see above (adaptive-detail split proportions).
- TL #29580: shipped in 8.0.0-alpha11; see above (the connections table of an address may move back into its case).
- TL #29804: see above (line numbers of import messages).
- TL #29805: see above (mandatory `<value-input>`).
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
- TL #29806: see above (singleton and generated wrappers; when available: dissolve `ehome.mask`).
- TL #29807: see above (classifier constant names).
- TL #29810: see above (deleted object in a tree selection).
- TL #29812: see above (groups in row order: a tie comparator, a follow-up ticket to file).
- TL #29813: see above (tree updates for objects not displayed yet; when fixed: drop the `placements` input of the building tree).
- TL #29814: see above (live sources: the Zigbee tab and the virtual device remain; no signal for a window that is gone, a closed page is never collected in alpha11).
- TL #29872: see above (slider range from a channel).
- TL #29873: see above (time picker seconds).
- TL #29874: see above (option order of a choice; when available: delete `OptionOrderSelectControlProvider`).
- TL #29876: see above (referrer scans when deleting).
- TL #29877: see above (developer article on the persistency configuration).
- TL #29880 to #29882: see above (XML import: unknown elements, a second container, multi-value cases).
- TL #29883: see above (an unset boolean on stored and transient objects).
- TL #29889 to #29891: see above (import performance: set additions, type lookups, defaults, reads, deletion).
- TL #29973 to #29975: see above (XML import after alpha11: empty texts and a default language, attributes of a known element, a partitioned lookup).

## The generator rewrites the header of hand-written wrappers (TL #29897)

`tl:generate-java` (8.0.0-alpha10) regenerates `internal/*Base.java` and `*Factory.java` as
documented, but after the model change of haumacher/ehome #40 it also replaced the copyright
header of the hand-written `MaskConfigurationImpl.java`, which the generator creates once and is
not supposed to touch again. Workaround: after every generation run, `git diff --stat` and revert
hand-written wrappers that show only a header change (`git checkout -- <file>`). Remove this entry
once the ticket has shipped.

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
