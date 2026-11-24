# KNX manufacturers and their product data

Where the product data of KNX manufacturers can be had, in what form, and what real product files
contain that E-Home's import does not handle yet. A survey of 2026-11-12 (specification round); the
addresses are those found that day. The files themselves are the manufacturers' and are not part of
this repository: download them from the addresses below when a test needs one, and derive artificial
fixtures (`ehome/src/test/fixtures/make-fixtures.py`) for the suite.

## How ETS gets product data

- **The ETS online catalog** is a free service hosted by KNX Association. A manufacturer with a KNX
  membership uploads product files (`.knxprod`) through the MyKNX portal; taking part is optional,
  and the manufacturer decides which products appear in which markets. ETS shows the online entries
  beside its local product store and downloads an entry on demand. No interface for other tools is
  documented (https://support.knx.org/hc/en-us/sections/115000749985-ETS-Online-Catalog,
  https://support.knx.org/hc/en-us/articles/360020682760).
- **The manufacturers' own downloads.** Most publish product files on their sites: one file per
  product family, or one complete database of all products. Older formats (`.vd1` .. `.vd5`) are
  converted to `.knxprod` with a converter that ships with ETS
  (https://support.knx.org/hc/en-us/articles/115002601169).
- `https://www.knx.org/devices` lists certified devices; it offers documents (manuals), no product
  files.

## Manufacturers

Checked: the page was fetched and product files were downloaded and read. Seen: the page turned up
in a search and was not read.

| Manufacturer | KNX id | Where | Form | State |
|---|---|---|---|---|
| MDT technologies | `M-0083` | https://www.mdt.de/EN_Downloads_Productdata.html | one `.knxprod` per product family, linked directly (112 links; DALI gateways and firmware as `.zip`) | checked: 103 product files read |
| Theben | `M-0048` | https://www.theben.de/de/dali-gateway-knx-plus-4940301 (a product page; it links the complete database `..._KNXprod_KNXGesamtdatenbank.zip`, 22 MB, 119 programs, and the product's own package) | a complete database as one `.knxprod` in a `.zip`, and packages per product | checked |
| Enertex Bayern | | https://www.enertex.de/d-downloads.html | a `.zip` per product with the `.knxprod` and, beside it, an ETS project | checked: 2 files read |
| Weinzierl Engineering | | https://weinzierl.de/en/products/knx-ip-interface-731/ (per product; `.zip` per ETS version) | a `.zip` per product and ETS version | checked: 1 file read |
| Hager | | https://hager.com/de/service/downloads/knx-gesamt-produktdatenbank, archive: https://hager.com/de/service/downloads/archiv-knx-einzel-und-gesamtdatenbanken | complete databases | seen |
| Berker (Hager group) | | https://www.berker.com/downloads-service/downloads/knx-product-database/92.htm | a product database | seen |
| Siemens | | https://knx-td.sihub.siemens.com/de/pdb/files/2023_Oct/pdb_2023_en.htm | a complete database for ETS 5/6 as `.knxprod`, older archives | seen |
| ABB / Busch-Jaeger | | https://new.abb.com/low-voltage/products/building-automation/product-range/abb-i-bus-knx/products/abb-i-bus-knx-all-products | per product, and one file with the applications of all active products | seen |
| Gewiss | | https://www.gewiss.com/al/en/services/software/product-suite/knx | a product database | seen |
| ise | | https://www.ise.de/en/service/downloads | per product | seen |
| OpenKNX (open source firmware) | | releases of its GitHub repositories, e.g. https://github.com/OpenKNX/OAM-PresenceModule/releases | a `.zip` per release; the two read held no `.knxprod` at the top | checked: no product file found |

Not found that day (no download page in the results, or the page refused the request): Gira
(https://partner.gira.de/service/download/index.html lists its software, the product data is per
product), Jung (https://www.jung-group.com/de-DE/Service/Downloads/ answered 403), Zennio (per
product on https://www.zennio.com), Elsner, Schneider Electric / Merten, Steinel, B.E.G., eelectron,
ekinex.

## What E-Home's import does with such files today

- Since haumacher/ehome #154 the baggage of a manufacturer (`Baggages`) is imported from a project
  file and from a product file and kept with the manufacturer, and a program names its files
  (`Static/Extension/Baggage`, `doc/knx/schema-coverage.md`, "Files"); before, a product file with
  baggage was refused and a project file skipped it. A product file is still **refused** when it
  holds an encrypted entry.
- A product file is imported step by step since haumacher/ehome #160: the data its programs share
  first, then each application program in a transaction of its own; a program that cannot be imported
  is rolled back alone and named in the report. Theben's complete database (119 programs, 350 MB of
  XML) imports in about 2 minutes with a heap of 2 GB (`doc/tl-todo.md`, "Memory of a product import
  committed step by step"), a second import in about 10 seconds. Its enumeration values written as
  unsigned 32-bit numbers (`4294967236`) and its renames of separators are read since #160; its
  channel number `18d` (`M-0048_A-F140-13-8536`, schema type `String50_t`) is kept as written since
  #162 (`Channel#number` is a text; the OpenKNX modules number channels `BASE`, `LOG` likewise).
- `TypePicture` is read since #154 and its pictures are shown in the parameters since #155; the plug-in
  attributes of `Static/Extension` are read since #159 as the requirements of the program (Theben's
  "DALI-Gateway KNX plus" shows the notice of its user interface plug-in and is downloaded as its
  product data declare); `IconFile` and `Icon` are read and the icons shown since #156, `ContextHelpFile`
  and `HelpContext` with the help texts since #157 (`doc/knx/schema-coverage.md`, "Icons and help texts of
  a program"); `Static/BinaryData` is not read (`doc/knx/schema-coverage.md`, "Not covered").
- The icon archives are ZIP archives of PNG pictures named as the elements name their icon (MDT
  `Symbole_new.zip`: 191 pictures, 188 of 32x32 pixels, all 163 icons of `BE-GTSx6Tx-01S` present;
  Theben `Enumicons.zip` 40, `SM4_icons.zip` 2, `2023029_4969278_icons.zip` 2). Besides enumeration
  entries and channels, Theben's "TA x.1 KNX" name an icon at a parameter block and MDT's room
  temperature controllers (`SCN-xxxxxx-01`, `SCN_01_..._Smart`) at a `ParameterRefRef`. The help archives
  are ZIP archives of one `.txt` per topic in Markdown (UTF-8, Theben's `Context_Help_*` with a byte order
  mark, CRLF line ends; bold, italics, rules); Theben ships one per language (DE, EN, ES, FR, IT, NL, SV).
  MDT writes `HelpContext=""` (empty) at parameters of 7 files and ships no help.
- The product files of the test fixtures (`MDT_AKK_V23.knxprod`, `Zennio_DALI_BOX_64_v3.knxprod`)
  hold no baggage; `TestBaggage*.knxprod` and `TestBaggage.knxproj` (`make-fixtures.py`) hold
  artificial baggage, `TestResources*.knxprod` and `TestResources.knxproj` artificial icons and help texts.

## What the files contain

Counted over the product files read (a file counts once per feature; a file may hold several
programs).

| | MDT (103 files) | Theben complete database (1 file, 119 programs) |
|---|---|---|
| Baggage (`M-xxxx/Baggages/`), refused before #154 | 31 files | yes |
| Baggage contents | 502 `.png`, 4 `.jpg`, 10 `.zip`, 2 `.pdf`, 1 `.msi` | 54 `.png`, 34 `.zip`, 1 `.msi` |
| Encrypted entries | none | none |
| `TypePicture` (a picture in the parameter dialog) | 28 files | 14 programs |
| `IconFile` (a parameter chooses an icon from a `.zip` of the baggage) | 10 files | yes |
| `ContextHelpFile` (help texts per language as `.zip`) | none (an empty `HelpContext` in 7 files) | 5 programs |
| `Button` with an event handler of the script | 7 files | |
| `BinaryData` | 2 files | |
| An ETS plug-in (`Extension@EtsUiPlugin`) | none | 1 program |
| Modules (`ModuleDef`) | 30 files | 26 programs |
| `Script`, `ParameterCalculation`, `ParameterValidation` | 21, 38, 1 files | yes |
| `TypeColor` | 22 files | 18 programs |
| KNX Secure (`IsSecureEnabled`) | 26 files | 35 programs |
| Schema versions | 11, 13, 14, 20, 21, 23 | 20 |
| Mask versions | 0012, 0025, 0701, 0705, 07B0, 0912, 091A, 2705, 27B0, 2920 | also 0001, 0011, 0021, 0911 |

The buttons are imported and shown in the parameters since haumacher/ehome #158: the 8 of the two Glass Touch
Smart files (`BE-GTSx6Tx-01S`, `GTS-x4Tx-01S`, "Copy text") copy a parameter and run in E-Home; those of the
other 5 files (binary inputs, presence detector, `GTL`, `TAL`) talk to the device (`online.connect()`) and are
shown disabled with that reason.

So about a third of MDT's product files, and Theben's complete database, could not be imported as a
product file before haumacher/ehome #154, in nearly every case because of pictures; since then they are
imported with their baggage, and since #155 their pictures are shown in the parameters.

## Examples of complex product files

Each is a public download of the manufacturer (address above); the name is the file's.

| File | What makes it complex |
|---|---|
| MDT `AKD-02x0CC-02_MDT_KP_V31.knxprod` | `TypePicture` with pictures in the baggage: `<TypePicture RefId="M-0083_BG--…PNG"/>`, referenced through `<Extension>` / `<Baggage>` |
| MDT `MDT_KP_BE_Push_Button_Smart_55_63_V12a.knxprod` | 48 pictures, `BinaryData`, modules, a script, `TypeColor`: a push button with a display |
| MDT `BE-GTSx6Tx-01S_MDT_KP_V23.knxprod` | a `Button` in the parameter dialog calling the script (`<Button Text="Copy text" EventHandler="SetButtonsActiveStatus"/>`), an icon list `Symbole_new.zip` in the baggage |
| MDT `MDT_KP_SCN_02_Glass_Room_Temperature_Controller_V12b.knxprod` | an icon list `Symbole.zip` |
| MDT `SCN-CO2M601x-02_MDT_KP_V12.knxprod` | a `.pdf` in the baggage |
| MDT `MDT_KP_SCN_02_USB_Interface_V10.knxprod` | no application program at all, only a driver installer (`inf/ETS USB Installation.msi`) in the baggage |
| MDT `SCN-DA641P.04S_2.1.7.zip` | the DALI gateway's package holds no product file but an update for a commissioning tool of its own (`MDTDCPro_…partial.pack`) |
| MDT `MDT_KP_SCN_03_IP_Router_Secure_V30.knxprod`, `MDT_KP_SCN_03_Line_Coupler_Data_Secure_V42.knxprod` | KNX Secure devices, coupler masks |
| MDT `MDT_KP_RF_02_RF_Line_Coupler_V12.knxprod` and the other `RF` files | KNX RF (masks 2705, 27B0, 2920) |
| Theben `KNX_DB_D_GB_E_F_I_NL_Theben_AG_ETS5_2506.knxprod` (in `o27748v146_KNXprod_KNXGesamtdatenbank.zip`) | 119 programs in one file, master data version 427; program "DALI-Gateway KNX plus" needs an ETS plug-in (`<Extension EtsUiPlugin="{869928F4-…}">`); "SM 4" has context help (`ContextHelpFile="M-0048_BG--HilfeTexte….zip"`, one `.zip` per language); "iON 108" has an icon list (`IconFile="M-0048_BG--Enumicons.zip"`); "DM 4 DALI KNX" a parameter validation |
| Enertex `1157-ETS-MeTa-1.030.knxprod` (in `1157-ETS-MeTa.zip`) | schema 11 with `TypePicture` and two pictures: the feature is as old as ETS 4 |
| Enertex `1164-ETS-KNXIPSecureRouter-1.004.knxprod` | a secure IP router with script and calculation |

Not found among the files read: a product file with an encrypted entry (the fixture
`Encrypted.knxprod` is artificial). A download plug-in (`Extension@EtsDownloadPlugin`) was found in a
real house project only (below).

## A download plug-in: ABB's AbbUsU (haumacher/ehome #224)

ABB's US/U 2.2 "Binäreingang Anzeige Heizen 2f/1.3b" (`M-0002_A-A002-13-8DB0`, mask 0701, ETS 4)
names the download plug-in `{15053D1A-12DE-462A-AEE9-E967A27EFF5B}` ("AbbUsU.SwapPlugin.1", baggage
`AbbUsU/AbbUsU.dll`, a native 32-bit Windows COM DLL, version R1.00.0510.0000 of 2009-02-04, with its
German texts in `AbbUsU0407.loc`). The product file comes from a private house project and is not in
the repository. Its disassembly (`objdump -d`, image base `10000000`; the addresses below are virtual
addresses in the DLL) shows what it does; E-Home does not run it and has no code for it. It holds no
AVR code: it decides how ETS downloads, it does not generate the device's application.

**Two passes.** ETS calls the plug-in twice. In the first pass (`1000A6B6`-`1000A71E`) it appends a
procedure of three records of 16 bytes (data at `1002C198`): `0E` (connect), `F0 04 04 03 0D 00 01 01`
(read a property: object index 3, property 13, one element from element 1) and `FF` (end), and registers
a buffer of 5 bytes for object 3, property 13 (`10006C00`). Property 13 of object 3 is
`PID_PROGRAM_VERSION` of the application program, which mask 0701 names as its resource
`ApplicationId`. In the second pass (`10009EF5`-`1000A10F`) it takes those 5 bytes (all must have been
read, "MultiPass - Pass2 - not all bytes of prop read") as manufacturer (bytes 0-1), application number
(2-3) and version (4), and asks ETS for the application ID of the project (`GetApplicationId`, method
`+30` of the data bridge). It reads nothing else from the device: no memory, in particular not
`6F3A`-`6F3B`.

**What it compares** (`1000A16D`-`1000A1D5`, the manufacturer at `10006350`):

1. The manufacturer of the application in the device with the project's: equal, or ABB (0002) and
   Busch-Jaeger (0007) either way (logged as an exception; the master data put both in the
   compatibility group 2). Anything else stops the download (`C0042B13`).
2. The application number: different is "Incompatible device type. Please check if the correct
   device is to be programmed." (string 103), and the download stops (`C0042B15`), for every kind of
   download.
3. A partial download asked for and the version byte of the device `FF`: "The application download
   was incomplete" (107), the complete download.
4. The high nibbles of the version bytes differ: "The already downloaded application in the device
   differs from the application in the ETS project" (104), the complete download.
5. The low nibble of the project greater: "The application version in the ETS project is newer"
   (105), the complete download; smaller: "The version of the application in the device is newer"
   (106) and "Nevertheless download the older application?" (114), the complete download.
6. Equal versions: no dialog. A partial download stays one; **a complete download is reduced to the
   partial one** (`1000A259`: the download options keep only `PROC_PARAMETERS` and
   `PROC_GROUP_COMMUNICATION`, the table of the options is at `1002C110`).

A partial download is the download options naming some, not all, parts of the application
(`options & FF` neither 0 nor `FF`); confirming a dialog of the cases 3-5 sets all of them
(`1000A233`), cancelling it ends the download (`80004004`). The dialog texts are strings 103-114 of the
DLL's string table (case table at `10009CF8`, called from `1000A1E4`).

**What it changes in the procedure** ETS generates for those options (ETS's own component, created at
`1000A283`, CLSID `{67867306-EB66-4D61-B43D-766D63D44F29}`):

- "patching param" (`1000A338`-`1000A602`): for each entry of a list the component returns (of
  address space 2 or 3 and longer than 8 bits) it copies the entry's bytes into the image segment
  holding its address, logging the old and the new bytes. What the list holds is ETS's and not in the
  DLL; that it is the parameters of more than one byte, rewritten in the byte order the product data
  name (`ParameterByteOrder="LittleEndian"`, `PreEts4Style="1"`), is plausible but **not proven**.
  E-Home writes parameters in the byte order the product data name; that the parameter segment of a
  device of this program compared bit-identical after the take-over of its parameters is consistent
  with it, but a take-over reads and writes through the same encoding and proves nothing about it.
- `100063C0`: in every record whose first byte has the bits `03` set and whose second byte has the
  bit `02`, bytes 6-7 become the manufacturer read from the device. Which load controls these records
  are is **not determinable**: the binary record format is ETS's, not in the DLL. Presumably the
  application ID of a task segment, so that a Busch-Jaeger device keeps its manufacturer.
- Partial download only (`100064C0`): of the records of 16 bytes, the first of type `0E` (connect)
  and the first of type `31` ("LoadApp" in its log, "patching load control for partial download,
  proc size=%d, index Connect=%d, index LoadApp=%d") are found, and the `31` record is moved directly
  after the connect (the records between shift by one). Which load control `31` is follows from the
  log's name only.

**The device of the house project.** It reports the project's program version (`00 02 A0 02 13`), but
its code segment 4600 is another build of the application (one routine 30 bytes longer, the code after
it and every address into it moved by 15 words). The bytes `6F3A`-`6F3B` that differ as well are not a
version: `0E ED 16 E2` (product data) and `0D EE 16 E2` (device) are the AVR instructions `ldi r16,0xDE;
ldi r17,0x26` and `ldi r16,0xED; ldi r17,0x26`, the word address of a routine in 4600 (`26DE`, byte
`4DBC`, right after the changed routine), moved by the same 15 words. By case 6 ETS with this plug-in
never writes 4600 into a device holding the same version, not even for a complete download: that is how
the device keeps a build the product data do not contain.

**What E-Home does instead, for every program** (no code for this plug-in; `doc/programming.md`): every
download compares the manufacturer before it writes (case 1 above, without the compatibility group:
E-Home refuses an ABB program on a Busch-Jaeger device); a download of the parameters only reads, besides
the program version and the load states, the code it leaves to the device and becomes the complete
download where the device holds anything else (cases 3-5, and the other build of case 6). Unlike the
plug-in, E-Home does not refuse a complete download into a device holding another application number
(case 2: a complete download replaces the application, and the mask names no other identity of the
hardware), and does not reduce a complete download to a partial one. The download of a program with a
download plug-in stays refused (haumacher/ehome #159).

**A complete download of this program** unloads the load state machines 1-3 (address table,
association table, application), allocates and writes the segments 4000 and 4200 (tables) and 4600,
6F10, 43FE, 6D00 and 6F00 (application and parameters), all above `4000`, writes the task segments,
completes the loads and restarts the device. No step addresses the individual address or the memory of
the system software. A download interrupted after the unload leaves the application stopped and the
load state machines not loaded; the system software still answers the management services, so a
further complete download is accepted (the plug-in itself handles that state: case 3, version byte
`FF`). E-Home leaves out the task segment of load state machine 5 (mask 7.1 has no load control for it,
`DownloadPlanner`), as for every program of that mask; both builds put it at `43FE`.
