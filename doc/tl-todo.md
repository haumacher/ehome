# Waiting for TopLogic

Gaps of the React UI (`src/main/webapp/WEB-INF/views/`) compared with the classic layouts, and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha10) cannot close yet.
Check each item when upgrading TopLogic (an upgrade happens only when the user asks for one). Every
entry names its TL ticket and what to remove once the engine covers it; a gap whose workaround would
not be minimal is left visible instead.

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

## Row activation button of tables (TL #29791)

The activation button a `<table>` with `<on-activate>` shows in every row runs the command without
selecting the row first. A `NavigatePushCommand` whose breadcrumb label reads the selection channel
then names the previously selected row.

Workaround in `projects/overview.view.xml`: `activation-button="false"` plus an "Open" toolbar
command (`<navigate-push>`) on the selected project. Double-click and Enter on a row work.

**When fixed:** remove `activation-button="false"` and the "Open" toolbar command.

## Closing dialogs lingers without an application animation (TL #29792)

When a dialog closes (or a drill-down frame leaves), the bridge keeps an inert copy of it on screen
until its exit animation ends (`useKeyedTransition` / `whenFinished` in
`com.top_logic.layout.react/react-src/bridge/transition.ts`). The engine ships no animation for
`tlDialog__backdrop--exiting` / `tlTileStack__frame--exiting`, so without an application stylesheet
the copy stays for the full `TRANSITION_FALLBACK_MS` (1 s): measured 1,070 ms until the closed
dialog disappears, while the server answered after 60 ms. `tl-demo-react` hides this with its own
150 ms animations (`style/tl-demo-react.css`).

Workaround: `src/main/webapp/style/ehome.css` (registered as `ClientResources` in
`WEB-INF/conf/ehomeConf.config.xml`) animates the classes; a cancelled dialog is gone after ~190 ms.

**When fixed in the engine** (default animations in the theme, no waiting without an animation):
remove `style/ehome.css` and its `ClientResources` registration.

## ETS import: workarounds for the XML import (TL #29794 to #29797, #29880 to #29883, #29889 to #29891)

The ETS import (`de.haumacher.ehome.importer`, `etsImporter.xml`) has handlers of its own for what
the engine's XML import (`com.top_logic.xio`) cannot do yet:

- TL #29794 (defect): `join-duplicates` links a reused object to its container again (fails for set
  references). Workaround: `JoiningModelBinding` skips adding an element a reference already holds.
  **When fixed:** remove `JoiningModelBinding` and its use in `Importer`.
- TL #29795: `set-translation` only takes a fixed language. Workaround: `<i18n-property>`
  (`I18NPropertyImport`, `I18NTexts`), language from the variable `language`, fills the fallback
  language. **When available:** use `<set-translation>` with the language from the variable.
- TL #29796: no handler binds a variable to an XML attribute. Workarounds: `<default-language>`
  (`DefaultLanguage`) and `<master-data-version>` (`MasterDataVersion`, the version of the master
  data for `<versioned-master-data>`). **When available:** use the engine handler.
- TL #29797: import into existing data. Workarounds: `Importer.registerSharedObjects()` registers
  the shared objects under their `extId`; `<unless-known>` (`UnlessKnown`) skips known application
  programs, except for the texts of the languages a known program lacks (`texts="translations"`,
  `ProgramLanguages`, `TranslationsImport#importBaseTexts`, #38: ETS writes a program in the language
  of the installation exporting it); `<versioned-master-data>` (`VersionedMasterData`, `MasterDataUpdates`, `ObjectMerge`)
  imports a known mask version as a transient candidate (`JoiningModelBinding#setTransient`) and, at
  the end of the import, updates the known one in place from it (newer master data) or compares and
  warns (older or the same); `<translations>` (`TranslationsImport`) reads the `Languages` sections and ignores
  unknown IDs. **When available:** declare the lookup in `etsImporter.xml`, and express the
  translations with `<with-var>`, `<object-ref>` (optional), `<switch>` and `<set-translation>`; the
  texts of a known program need a way to import the attributes of a known element without its
  structure, and a condition on the languages the known object records.
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
  known mask version with its transient candidate (#34). **When fixed:** drop that case.
- Deleting the parts the import drops one at a time took about 13 s per import of a real sample (the
  referrer scans of TL #29876). Workaround: `MasterDataUpdates` deletes them in one `deleteAll`.
- TL #29882: a `<switch>` case matches one value only, so the flags ETS 6 writes as `true` and ETS 4
  as `1` need duplicate cases in `etsImporter.xml`. **When available:** merge each pair into one
  case listing both values.
- TL #29889 (defect): adding one element to a set reference reads the whole set, so building a
  program's thousands of parameters one by one is quadratic. Workaround: `JoiningModelBinding`
  buffers additions to unordered multiple references of persistent objects and stores each set in
  one update at the end of the import (`flush()`). **When fixed:** add directly again and drop the
  buffering.
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
the constraints (run at save and commit) and the display keep calling the function. No workaround
for the derived fields: they are correct after saving.

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

A `<table>` inside a `<switch>` case whose rows read an input channel fails with a
`NullPointerException` in `RowSourceObserver#deregisterObjectListeners` when that channel changes
so that the case is removed: the switch disposes the table, and the observer, still in the
channel's listener snapshot, re-evaluates on a null scope. The user sees an error toast; the change
itself goes through. In the app: the entries table of an enumeration type in
`catalogs/program.view.xml` (select an enumeration parameter, then another node). The same table in
the tab "Geänderte Werte" of `projects/devices.view.xml` is gone with the list of changes
(haumacher/ehome #15).

Workaround: none for the structure tab (the toast is left visible). The types tab of
`catalogs/program.view.xml` shows the entries panel for every selected type (empty and without
commands for a type that is no enumeration) instead of in a `<visible-if>`: there the failure also
aborted the create command of a new type before its dialog closed.

The connections table of a group address (`projects/addresses.view.xml`) failed the same way in
the case of a selected address when a connection made by "Objekt verbinden" was selected (the
toast also kept the dialog open). Workaround: the table sits in a panel of its own below the
details, outside the switch over the selected node, and reads the address of the selection.

**When fixed:** put the entries panel of the types tab back into a `<visible-if>` on
`selectedEnumeration`; the connections table of an address may move back into the case of an
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

## Drag and drop in the structure tree of a program (TL #29776)

The structure of an application program (`catalogs/program.view.xml`) is reordered without drag
and drop, which trees do not offer in 8.0.0-alpha10: the create dialog of a structure element
(`catalogs/create-structure.view.xml`) asks whether the element goes into the selected element (at
its end) or after it, and "Move up" / "Move down" reorder the selected element among its siblings
(`de.haumacher.ehome.knx.ProgramStructure`).

**When available:** replace the position choice and the move commands by the tree's drag and drop.

## A `<value-input>` cannot be mandatory (TL #29805)

A `<value-input>` of an enumeration type always offers "no value": `ValueInputElement` builds an
`AbstractFieldModel`/`SimpleSelectFieldModel` that is never mandatory, and the configuration has no
option for it. Reproduction: `<value-input type="ehome.operational:InsertPosition" value="x">` with
`<input-control class="com.top_logic.layout.view.form.SelectControlProvider" display="radio"/>`
shows the radio buttons "Kein Wert", "Im ausgewählten Element (am Ende)", "Nach dem ausgewählten
Element".

Workaround: none needed beyond the channel: the position choice of
`catalogs/create-structure.view.xml` and the direction of a connection in
`projects/connect-group-address.view.xml` and the reference the changes of a device are compared
with (tab "Geänderte Werte" of `projects/devices.view.xml`) are derived channels with a `reverse`,
so choosing "no value" falls back to the default; the option stays visible. The value of a parameter in
`projects/device-item.view.xml` treats an emptied input as the default.

**When available:** declare the position choice, the direction of a connection and the reference of the changes mandatory.

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

## A text or number input applies its value on Enter only (TL #29808)

The `<on-submit>` command of a text or number `<value-input>` runs for the Enter gesture only; a
value committed by leaving the field writes the channel but does not run the command
(`ValueInputElement.followSubmit`, `ReactFormFieldControl.hasSubmitGesture`). A select, date or
checkbox input submits on every commit. The parameter inputs of a device
(`projects/device-item.view.xml`) store their value through `deviceApplyValue` in `<on-submit>`, so
a typed value stays unapplied until Enter is pressed.

Workaround: the text and number inputs carry the placeholder "Übernehmen mit Enter" / "Apply with
Enter".

**When available:** run the apply command on every commit (or move it to the `<on-change>` hook)
and drop the placeholder.

## A `<value-input>` of a binary value gets no binary control (TL #29809)

A `<value-input type="tl.core:Binary">` shows the file name as plain text: `FieldControlService`
maps no control to the type `tl.core:Binary`, and the fallback builds a text display, so a read-only
input offers no download (and an editable one no upload).

Workaround: the download dialog of the `.knxproj` export (`projects/export-knxproj.view.xml`) names
the control explicitly (`<input-control class="com.top_logic.layout.view.form.BinaryControlProvider"/>`),
as do the exports of a monitor recording (`projects/monitor-export.view.xml`) and of a trace of the
virtual device (`virtual/export-trace.view.xml`).

**When fixed:** remove the `<input-control>` from that `<value-input>`.

## A tree keeps a deleted object in its selection (TL #29810)

The selection channel of a `<tree>` still holds a node's object after that object was deleted in
another view (a connection removed in the device view while selected in the group address tree),
and every script over the selection fails with a `DeletedObjectAccess` toast until another node is
selected; a `<table>` drops such a selection, a tree does not.

Workaround: the TL-Script function `existingObject(x)` turns a deleted object into `null`; every
reader of the tree selection in `projects/addresses.view.xml` goes through it, and the remove
command selects the address before deleting its connection.

**When available:** drop `existingObject` and read the selection directly.

## A `<button>` cannot be icon-only (TL #29811)

`ButtonElement` renders its command with `ButtonDisplayMode.ICON_LABEL` unconditionally and the
configuration offers no display mode, so the remove button beside every connection chip of an
object (`projects/device-item.view.xml`) and the reset buttons beside the hints show their full
label.

Workaround: none; the labels stay visible.

**When available:** make the chip and hint buttons icon-only with the label as tooltip.

## Groups of a table in row order (TL #29812)

`ListRowSource` orders the groups of a grouped `<table>` with the comparator of the grouping
column, and every column a view can declare has one, so the pages of a program's parameters
(`catalogs/program.view.xml`, grouped by the page a parameter is placed on) would appear
alphabetically instead of in structure order.

Workaround: `de.haumacher.ehome.view.RowOrderColumn` (`<row-order-column>`), a computed column
without sorting; the row source then keeps the groups in the order of their first row.

**When available:** replace `<row-order-column>` by `<computed-column>` with the row-order option
and delete `RowOrderColumn`.

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

## A live source cannot push into a view (TL #29814)

The group monitor receives bus telegrams on a Calimero listener thread and has no declared way to
update the view's channels from it; only `<start-job>` updates a view from another thread, through
the internal `ReactWindowReplay.inWindow`.

Workaround: `de.haumacher.ehome.monitor.WindowPublisher` calls that internal API in the user's
session context, throttled to one update per 250 ms, and disconnects the monitor when the page is
gone.

The virtual device (`virtual/virtual-device.view.xml`) has the same need: its status and the number of
services recorded change while a programming tool downloads into it. No workaround there: the view
recomputes them when it is displayed and on its "Refresh" command, and its start and stop commands
take a derived channel of the device and the last status as input, so that their executability is
checked again after a start or stop (an `<executability>` rule cannot observe state outside the
model).

**When available:** replace `WindowPublisher` by the layer's live-source element, and let the virtual
device's status follow the recording.

## A table row cannot carry a CSS class (TL #29815)

Rows of the monitor's state board and log cannot be highlighted by state (changed recently, own
telegram).

Workaround: a column with "●" for recently changed rows and "►" for own telegrams
(`projects/monitor.view.xml`).

**When available:** a `row-class` expression on the tables, and drop the marker columns.

## A computed column cannot be hidden by default (TL #29816)

The raw data column of the monitor's log is always visible.

Workaround: none.

**When available:** declare the column hidden by default.

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
the week and the enumeration value (`projects/monitor.view.xml`).

**When available:** use the engine's setting in those three inputs and delete
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

The engine (8.0.0-alpha10) ships model modules whose types have no complete access definition: at
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

## Rule editor: no suggestions in a text field, no normalizing of input (TL #29887, #29888)

The rule editor (haumacher/ehome #57) stores expressions as text. A text field cannot offer the
project's datapoints while typing (TL #29887); workaround: a read-only "Datenpunkte" table beside the
form lists them as `@"1/2/3"`. A form cannot store an entered expression in its canonical form
(TL #29888); workaround (haumacher/ehome #60): the forms of the rule views (`rules.view.xml`,
`rule-actions.view.xml`, `schedules.view.xml`) replace the form's save by a `<save-action>` chain that
stores the form state, calls `ruleNormalize` (`RuleNormalization`) in the same transaction and leaves
edit mode with `<discard-form-state/>`. Texts written another way (a script, the API) are stored as
given and canonicalized on export.

**When available:** offer the datapoints as suggestions in the expression fields and drop the table;
normalize in the fields and drop the `<save-action>` chains and `ruleNormalize`.

## Upstream tickets to follow

- TL #29789: `tl:generate-java` cannot bootstrap a version change (the generator is therefore not
  bound to the build, see `CLAUDE.md`).
- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29791: see above.
- TL #29792: see above (default transition animations in the engine).
- TL #29793: developer-docs recipe for an application-defined primitive type (group addresses).
- TL #29794 to #29797: see above (XML import).
- TL #29798: see above (derived references in the commit check).
- TL #29799: see above (derived fields and Java script functions in forms).
- TL #29800: see above (model patch of a new generalization with overrides).
- TL #29801: see above (adaptive-detail split proportions).
- TL #29580: see above (table in a switch case disposed during a channel change; fix with registration handles described there).
- TL #29804: see above (line numbers of import messages).
- TL #29776: see above (drag and drop for trees; when available: replace the position choice and the
  move commands by the tree's drag and drop).
- TL #29805: see above (mandatory `<value-input>`).
- TL #29884, #29885: see above (access coverage of engine modules, hidden instead of disabled commands).
- TL #29886: see above (line breaks in confirmations).
- TL #29887, #29888: see above (suggestions and normalizing in text fields).
- TL #29806: see above (singleton and generated wrappers; when available: dissolve `ehome.mask`).
- TL #29807: see above (classifier constant names).
- TL #29808: see above (apply on Enter only).
- TL #29809: see above (binary value input; when fixed: drop the explicit input control of the export dialog).
- TL #29810: see above (deleted object in a tree selection).
- TL #29811: see above (icon-only buttons).
- TL #29812: see above (groups in row order).
- TL #29813: see above (tree updates for objects not displayed yet; when fixed: drop the `placements` input of the building tree).
- TL #29814: see above (live source push).
- TL #29815: see above (row CSS class).
- TL #29816: see above (hidden computed column).
- TL #29872: see above (slider range from a channel).
- TL #29873: see above (time picker seconds).
- TL #29874: see above (option order of a choice; when available: delete `OptionOrderSelectControlProvider`).
- TL #29876: see above (referrer scans when deleting).
- TL #29877: see above (developer article on the persistency configuration).
- TL #29880 to #29882: see above (XML import: unknown elements, a second container, multi-value cases).
- TL #29883: see above (an unset boolean on stored and transient objects).
- TL #29889 to #29891: see above (import performance: set additions, type lookups, defaults, reads, deletion).
