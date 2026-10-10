# Waiting for TopLogic

Gaps of the React UI (`src/main/webapp/WEB-INF/views/`) compared with the classic layouts that the
TopLogic version in use (8.0.0-alpha10) cannot close yet. Check each item when upgrading TopLogic.

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

## Upstream tickets to follow

- TL #29789: `tl:generate-java` cannot bootstrap a version change (the generator is therefore not
  bound to the build, see `CLAUDE.md`).
- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29791: see above.
- TL #29792: see above (default transition animations in the engine).
