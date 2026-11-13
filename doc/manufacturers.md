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
  channel number `18d` (`M-0048_A-F140-13-8536`, schema type `String50_t`) does not fit the model's
  integer `Channel#number` and is reported, the program imported without it.
- `TypePicture` is read since #154 and its pictures are shown in the parameters since #155; `IconFile`, `ContextHelpFile` (#156, #157), the plug-in
  attributes of `Static/Extension` (#159) and `Static/BinaryData` are not read
  (`doc/knx/schema-coverage.md`, "Not covered").
- The product files of the test fixtures (`MDT_AKK_V23.knxprod`, `Zennio_DALI_BOX_64_v3.knxprod`)
  hold no baggage; `TestBaggage*.knxprod` and `TestBaggage.knxproj` (`make-fixtures.py`) hold
  artificial baggage.

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
| `ContextHelpFile` (help texts per language as `.zip`) | none | yes |
| `Button` with an event handler of the script | 7 files | |
| `BinaryData` | 2 files | |
| An ETS plug-in (`Extension@EtsUiPlugin`) | none | 1 program |
| Modules (`ModuleDef`) | 30 files | 26 programs |
| `Script`, `ParameterCalculation`, `ParameterValidation` | 21, 38, 1 files | yes |
| `TypeColor` | 22 files | 18 programs |
| KNX Secure (`IsSecureEnabled`) | 26 files | 35 programs |
| Schema versions | 11, 13, 14, 20, 21, 23 | 20 |
| Mask versions | 0012, 0025, 0701, 0705, 07B0, 0912, 091A, 2705, 27B0, 2920 | also 0001, 0011, 0021, 0911 |

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
`Encrypted.knxprod` is artificial), and a download plug-in (`Extension@EtsDownloadPlugin`).
