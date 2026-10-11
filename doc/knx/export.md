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

The exporter is written by hand along the model, mirroring `etsImporter.xml` section by section (`MasterDataExport`, `ManufacturerExport`, `ProgramExport`, `ProjectExport`, `LoadSteps`). Executing the declarative mapping in reverse was considered and rejected: the mapping describes how to *consume* XML, not how to *enumerate* the model. Its constructs are not invertible without additional information: `<dispatch>` follows the document order, not the schema's, and does not say which objects to write where; `<assign>` and `<switch>` run TL-Script (the download parts of a device, the default subtype, `Program#unplaced`); `<forward>` builds the shared location of a union from its first child; `<object-ref>` with `list-linking` places existing objects (catalog items, `ParameterRefRef`); `<translations>` and `<default-language>` fold the `Languages` sections into `I18NString`s; and the file split (master, catalog, hardware, program, project) is not part of the mapping at all. Annotating all of this would amount to a second description, so a hand-written exporter is the smaller one; the round-trip test keeps the two directions in step.

Values are written through the inverse of the import formats (`getSpecification` of `ObjectSizeFormat`, `MediumFormat`, `DecimalsFormat`, `Base64Format`, `HexBinaryFormat`, `TLEnumerationFormat`); the typed parameter values through `ParameterValues.encode`. Element IDs are the external IDs (`extId`).

## Conventions

- **Texts.** The model holds a text per application language (`I18NString`), not the language a manufacturer wrote first. Every file is written with a fixed default language: `de-DE` for manufacturer data (`DefaultLanguage` of programs, products and catalog sections), English for the master data (as ETS writes it). A translation (`en-US` resp. `de-DE`) is written where the other language's text differs. Without a text in the default language, the attribute takes the text of the other language. Translations into languages other than de and en are not modeled and not written.
- **Booleans.** As ETS writes them: `1`/`0` in project and manufacturer data, `true`/`false` in load procedures, master data and the dynamic part (`when@default`). Since the model reads a missing boolean as `false`, attributes whose schema default is `false` are written only when `true` (`Acknowledge`, `Central`, `Unfiltered`, `Optional`, `UseFunctionProp`, `DatapointSubtype@Default`), the required `Verify`, `Writable` and `Value` only for the load steps that have them, `TypeDate@DisplayTheYear` only when `false`, `TypeIPAddress@Version` only for IPv6.
- **Enumerations** are written by their ETS value where it is no identifier (`DPT 9`, `IEEE-754 Single`, `par,grp`); object sizes as the schema enumerates them (`1 Bit`, `1 Byte`, `2 Bytes`).
- **Order.** Ordered model lists keep their order. Sets whose order the model does not keep (segments, parameter types, parameters, parameter and object references, communication objects, areas, lines, devices, connectors, buildings, group ranges) are written sorted: by address where there is one, else by ID (numbers within an ID by value).
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

Everything else the model does not hold is left out. The complete list, as rules of the round-trip comparison, is [not-modeled.txt](not-modeled.txt); the reasons are in [schema-coverage.md](schema-coverage.md). In short: signatures, `Hash`/`CheckSums` and other ETS bookkeeping, project information beyond name and group address style, history, to-dos, comments (RTF), trades, bus access and IP settings, hardware properties, ETS plug-ins and baggages, the UI elements of parameter dialogs (separators, assignments, renames), translations into further languages, and the legacy configuration of a mask version as a section of its own (it is merged into the current one).

## Other schema versions

The export writes schema 11 (ETS 4), the version of the samples and of the import. ETS 5 and 6 write schema 12 to 20 (namespace `http://knx.org/xml/project/<n>`), where among others application programs may use modules (`ModuleDefs`), the dynamic part gains elements (`Rename`, `ParameterBlock` layouts), parameter types gain kinds, and devices carry security data (keys, `Security`). An ETS 5 export would need the namespace and these elements (most with no counterpart in the model yet), and ETS would still refuse it for the missing signatures. The import does not check the namespace either; see "Limits of this analysis" in schema-coverage.md.
