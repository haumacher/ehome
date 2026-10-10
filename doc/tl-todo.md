# Waiting for TopLogic

Gaps of the React UI (`src/main/webapp/WEB-INF/views/`) compared with the classic layouts, and
workarounds in the ETS import, that the TopLogic version in use (8.0.0-alpha10) cannot close yet.
Check each item when upgrading TopLogic.

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

## Upstream tickets to follow

- TL #29789: `tl:generate-java` cannot bootstrap a version change (the generator is therefore not
  bound to the build, see `CLAUDE.md`).
- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29791: see above.
- TL #29792: see above (default transition animations in the engine).
- TL #29793: developer-docs recipe for an application-defined primitive type (group addresses).
- TL #29794 to #29797: see above (XML import).
