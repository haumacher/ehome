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

## Upstream tickets to follow

- TL #29789: `tl:generate-java` cannot bootstrap a version change (the generator is therefore not
  bound to the build, see `CLAUDE.md`).
- TL #29790: missing steps in the 7.11 to 8.0 upgrade guide.
- TL #29791: see above.
