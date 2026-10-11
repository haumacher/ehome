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

## ETS import: workarounds for the XML import (TL #29794 to #29797)

The ETS import (`de.haumacher.ehome.importer`, `etsImporter.xml`) has handlers of its own for what
the engine's XML import (`com.top_logic.xio`) cannot do yet:

- TL #29794 (defect): `join-duplicates` links a reused object to its container again (fails for set
  references). Workaround: `JoiningModelBinding` skips adding an element a reference already holds.
  **When fixed:** remove `JoiningModelBinding` and its use in `Importer`.
- TL #29795: `set-translation` only takes a fixed language. Workaround: `<i18n-property>`
  (`I18NPropertyImport`, `I18NTexts`), language from the variable `language`, fills the fallback
  language. **When available:** use `<set-translation>` with the language from the variable.
- TL #29796: no handler binds a variable to an XML attribute. Workaround: `<default-language>`
  (`DefaultLanguage`). **When available:** use the engine handler.
- TL #29797: import into existing data. Workarounds: `Importer.registerSharedObjects()` registers
  the shared objects under their `extId`; `<unless-known>` (`UnlessKnown`) skips known application
  programs; `<translations>` (`TranslationsImport`) reads the `Languages` sections and ignores
  unknown IDs. **When available:** declare the lookup in `etsImporter.xml`, and express the
  translations with `<with-var>`, `<object-ref>` (optional), `<switch>` and `<set-translation>`.

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

**When fixed:** call `parameterValueType` in the visibility and options scripts of `ParameterValue`.

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
display. With 32 % the settings table of a device (`projects/devices.view.xml`: name, value, default)
shows only its first column.

Workaround: `projects/devices.view.xml` arranges the settings table and the detail of the selected
setting in a `<split-panel>` (55/45) and guards the selection itself (`setting` derived channel:
the selected setting only if it belongs to the selected device), losing the drill-in on narrow
screens and `reset-on`.

**When available** (configurable selector share): replace the split panel and the guard by an
`<adaptive-detail>` with `reset-on="selected"`.

## Error toast when a table in a switch case disappears (TL #29802)

A `<table>` inside a `<switch>` case whose rows read an input channel fails with a
`NullPointerException` in `RowSourceObserver#deregisterObjectListeners` when that channel changes
so that the case is removed: the switch disposes the table, and the observer, still in the
channel's listener snapshot, re-evaluates on a null scope. The user sees an error toast; the change
itself goes through. In the app: the entries table of an enumeration type in
`projects/devices.view.xml` (select an enumeration setting, then a line in the tree) and in
`catalogs/program.view.xml` (select an enumeration parameter, then another node).

Workaround: none; the toast is left visible.

**When fixed:** nothing to remove.

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
- TL #29802: see above (table in a switch case disposed during a channel change).
