# Export as `.knxproj`

A project is exported as an ETS project archive (`.knxproj`, project schema 11 as ETS 4 writes it, namespace `http://knx.org/xml/project/11`): in the app with the command "Als .knxproj exportieren" / "Export as .knxproj" in the projects overview (a dialog offers the file for download), in Java with `de.haumacher.ehome.exporter.KnxprojExporter.export(project, out)`.

**The file is not signed.** A `.knxproj` carries signature files (`M-xxxx.signature`, `P-xxxx.signature`) that ETS verifies on import; they are proprietary and can only be created by an installed ETS. The export writes none, so ETS refuses the file. E-Home imports it, as do other tools reading the schema. A round trip into ETS is not a goal; the export serves other tools, backups, and the completeness check of the import (see "Round trip" in [schema-coverage.md](schema-coverage.md)).

## Content

| Entry | Content |
|---|---|
| `knx_master.xml` | The master data the project uses: the datapoint types its programs, devices and group addresses name (each main type with all its subtypes), the mask versions of its programs and those they are downward compatible to, the manufacturers of its devices and programs (and the original manufacturers of the programs), the translations of the datapoint types. ETS writes its whole master data; the model holds what the imported files contained, so the export writes the part the project needs. |
| `M-xxxx/Catalog.xml` | Per manufacturer, the catalog sections containing products of the project's devices, with an item per product. |
| `M-xxxx/Hardware.xml` | The hardware of the project's devices with the products and program assignments used. |
| `M-xxxx/M-xxxx_A-….xml` | Per application program used: the static part (segments, parameter types, parameters and unions, parameter references, communication objects and their references, address, association and object table, load procedures, options), the dynamic part (the configuration structure) and the translations. |
| `P-xxxx/0.xml` | The installation: topology (areas, lines, devices with their parameter settings, communication objects and connections, unassigned devices), buildings, group addresses. |
| `P-xxxx/Project.xml` | The project information (name, group address style). |

The entries come in the order ETS writes them; the import needs master data before manufacturer data before the project.

## Mechanism

The exporter is written by hand along the model, mirroring `etsImporter.xml` section by section (`MasterDataExport`, `ManufacturerExport`, `ProgramExport`, `ProjectExport`, `LoadSteps`). Executing the declarative mapping in reverse was considered and rejected: the mapping describes how to *consume* XML, not how to *enumerate* the model. Its constructs are not invertible without additional information: `<dispatch>` follows the document order, not the schema's, and does not say which objects to write where; `<assign>` and `<switch>` run TL-Script (the download parts of a device, the default subtype, `Program#unplaced`); `<forward>` builds the shared location of a union from its first child; `<object-ref>` with `list-linking` places existing objects (catalog items, `ParameterRefRef`); `<translations>` and the `<with-var>` of the language fold the `Languages` sections into `I18NString`s; and the file split (master, catalog, hardware, program, project) is not part of the mapping at all. Annotating all of this would amount to a second description, so a hand-written exporter is the smaller one; the round-trip test keeps the two directions in step.

Values are written through the inverse of the import formats (`getSpecification` of `ObjectSizeFormat`, `MediumFormat`, `DecimalsFormat`, `Base64Format`, `HexBinaryFormat`, `TLEnumerationFormat`); the typed parameter values through `ParameterValues.encode`. Element IDs are the external IDs (`extId`).

## Conventions

- **Texts.** The model holds a text per application language (`I18NString`), not the language a manufacturer wrote first. Every file is written with a fixed default language: `de-DE` for manufacturer data (`DefaultLanguage` of programs, products and catalog sections), English for the master data (as ETS writes it). A translation (`en-US` resp. `de-DE`) is written where the other language's text differs. Without a text in the default language, the attribute takes the text of the other language. An application program is written with translations of the languages of its texts only (`Program#languages`): in another language its text is a stand-in, the base text of the file it came from, which the attribute then holds (so a German-only program exports as German-only, and a re-import does not take the stand-ins for English texts). Translations into languages other than de and en are not modeled and not written.
- **Booleans.** As ETS writes them: `1`/`0` in project and manufacturer data, `true`/`false` in load procedures, master data and the dynamic part (`when@default`). Since the model reads a missing boolean as `false`, attributes whose schema default is `false` are written only when `true` (`Acknowledge`, `Central`, `Unfiltered`, `Optional`, `UseFunctionProp`, `DatapointSubtype@Default`), the required `Verify`, `Writable` and `Value` only for the load steps that have them, `TypeDate@DisplayTheYear` only when `false`, `TypeIPAddress@Version` only for IPv6.
- **Enumerations** are written by their ETS value where it is no identifier (`DPT 9`, `IEEE-754 Single`, `par,grp`); object sizes as the schema enumerates them (`1 Bit`, `1 Byte`, `2 Bytes`).
- **Order.** Ordered model lists keep their order, among them the buildings and the parts of each building part (haumacher/ehome #71: the order of the imported file, then as the user arranged them). Sets whose order the model does not keep (segments, parameter types, parameters, parameter and object references, communication objects, areas, lines, devices, connectors, the devices placed in a building part, group ranges) are written sorted: by address where there is one, else by ID (numbers within an ID by value).
- **Dates** (`LastModified`, `LastDownload`) without time zone, as UTC (the import reads them as UTC; see "Open questions" in schema-coverage.md).
- **Derived IDs.** A catalog item has no object of its own in the model; its ID is formed as ETS forms it (program assignment, `_CI-`, the product part of the product ID, `-1`). A mask entry's ID is the mask ID, `_ME-` and its escaped name.

## Defaults for required attributes the model does not hold

| Attribute | Written |
|---|---|
| `KNX@CreatedBy`, `@ToolVersion` | `E-Home`, `E-Home .knxproj export (schema 11, unsigned)` |
| `DatapointType@Name` (main type) | `<number>.xxx` (as ETS names them) |
| `CatalogSection@Number` | empty |
| `CatalogItem@Name`, `@Number` | the product's name, `1` |
| `Hardware@HasIndividualAddress`, `@HasApplicationProgram` | `1`, `1` |
| `Product@IsRailMounted` | `0` |
| `ApplicationProgram@ProgramType`, `@Linkable` | `ApplicationProgram`, `0` |
| `Installation@Name`, `@InstallationId` | empty, `0` |
| `Line@MediumTypeRefId` | `MT-0` (twisted pair) |

Everything else the model does not hold is left out. The complete list, as rules of the round-trip comparison, is [not-modeled.txt](not-modeled.txt); the reasons are in [schema-coverage.md](schema-coverage.md). In short: signatures, `Hash`/`CheckSums` and other ETS bookkeeping, project information beyond name and group address style, history, to-dos, comments (RTF), trades, bus access and IP settings, hardware properties, ETS plug-ins and baggages, the UI elements of parameter dialogs (separators, assignments, renames), and translations into further languages.

## Other schema versions

The export writes schema 11 (ETS 4), the version of the samples and of the import. ETS 5 and 6 write schema 12 to 20 (namespace `http://knx.org/xml/project/<n>`), where among others application programs may use modules (`ModuleDefs`), the dynamic part gains elements (`Rename`, `ParameterBlock` layouts), parameter types gain kinds, and devices carry security data (keys, `Security`). An ETS 5 export would need the namespace and these elements (most with no counterpart in the model yet), and ETS would still refuse it for the missing signatures. The import does not check the namespace either; see "Limits of this analysis" in schema-coverage.md.

## Data of newer schemas

The export writes schema 11, also for a project imported from an ETS 6 file (schema 23). What the model holds of schema 23 is written where schema 11 has a place for it and left out otherwise; the round trip compares the samples, which are schema 11, so these items are listed here and in [not-modeled.txt](not-modeled.txt) as comments:

- **Written in schema-11 form:** the communication object links (`Links`) as `Connectors` (first `Send`, then `Receive`), IDs relative to the program or the installation as full IDs, devices of line segments as devices of the line, locations (`Space`) as building parts (`BuildingPart`), and a reference placed in several cases (`SharedPlacement`) as a `ParameterRefRef` or `ComObjectRefRef` in each case.
- **Written as their values, without a schema-11 counterpart:** the medium `MT-6` (KNX IoT) and the address space `RelativeMemoryByObjectType` of a mask resource (only for a mask the project's programs use).
- **Left out:** the descriptions and active flags of a device's channels (`ChannelInstances`, haumacher/ehome #122; the project file carries them in its document), the renames of parameter blocks (`Rename`), the parameter calculations and the script of a program (`ParameterCalculations`, `Script`, haumacher/ehome #123), its parameter validations (`ParameterValidations`, haumacher/ehome #124), the option `DownloadInvisibleParameters` of a program, the named values of datapoint subtypes (`Format/Enumeration/EnumValue`), the master reset step (`LdCtrlMasterReset`), and the definition of a color parameter type (`TypeColor`: the parameter type is written without its definition, as for any type without a schema-11 element). Dynamic modules are not imported in the first place.

## Project file (`.ehome`)

The `.knxproj` holds only the KNX part. A whole project moves to another installation or is backed up as project file `<name>.ehome` (haumacher/ehome #117; commands "Export project file" and "Import project file" in the projects overview): a zip of the `.knxproj` as described here and a JSON document (`ehome.json`) with everything else in the terms of the model — rules, schedule entries, application datapoints, virtual devices, functions, pages, Zigbee devices, dismissed proposals, the simulation's followers, the controllers (name, host, port), the project's settings, the settings of the group addresses and the attributes of devices schema 11 has no place for (their channels with their descriptions, format 1.1) — with references by stable IDs (group address, external ID, IEEE address, identifiers) and a format version. Without secrets (installation key, connection secrets, HTTP tokens: the controllers are paired again) and without operating history (recordings, deployments, image revision, unidentified devices of a scan, the simulation's overrides, the revision of a device's last download). The import always creates a new project with the ID of the file (an existing project is brought up to date from ETS by "Update from ETS" instead, see "Updating a project from ETS" in [schema-coverage.md](schema-coverage.md)). The split between the `.knxproj` and the document is the one between ETS and E-Home declared in `de.haumacher.ehome.knx.ProjectParts`, which the update from ETS uses as well. The format, the rules of what is left out and the version policy are described in `de.haumacher.ehome.projectfile.ProjectFile`; `test.de.haumacher.ehome.projectfile.TestProjectFile` checks the round trip.
