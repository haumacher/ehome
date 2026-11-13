#!/usr/bin/env python3
"""Creates the test fixtures for TestEtsImport from samples/Test.knxproj.

TestDetails.knxproj: the samples contain no device names, descriptions, serial numbers, unassigned
devices, inactive communication objects or details of group ranges and addresses. This copy of the
Test project adds them, and marks the device as fully downloaded after its last change.

The samples contain no building parts and place no device. TestDetails.knxproj adds a building
"Haus" with the floor "Erdgeschoss", holding the empty room "Wohnzimmer", the room "Küche", in which
the device "Taster Küche" is placed, and an empty distribution board "Verteiler", in this order.

TestDynamic.knxproj: the samples contain no channels that depend on a parameter (a choose directly
below Dynamic). This copy gives the application program a new ID (so that an import does not skip it
as known), a name marking it as test data, and wraps its channel in such a choose, with a default
case. The choose decides by a parameter added for the test ("Kanal Generic", an enumeration "Aus" 0 /
"Ein" 1 with the default "Ein", stored nowhere in the device), which a block of its own in a channel
independent part shows; the channel is therefore shown by default and hidden when the parameter is
set to "Aus". The samples contain only enumeration, integer, text and
void parameter types; the program of this copy adds one parameter of every other kind (floating point,
time, text with a pattern, date, IP address) to its block "Allgemein", with a default each, and the
device sets the floating point parameter. The values are written in the notation of XML (a decimal
point, an ISO date, a dotted IP address), as ETS writes its files. One more parameter of the block,
"Helligkeit" (an integer 0 to 100), has the value 300, which does not fit its type, and the device
sets it to 300 as well: the import keeps the values as text and reports a warning for each (a defect
of the artificial test data, not of ETS). The setting is part of the project, so that every import
of the fixture reports it, also when the program is already known and skipped. The samples contain
no relative segment (System B) and no union stored in a property: the program of this copy adds a
relative segment with data, a parameter stored in it ("Relativ"), and a union of two parameters
stored in a property of an interface object ("Property A", "Property B"); none of them is placed in
the structure. Every change of the program gives it a new version in its ID (now O000D), so that a
database holding an earlier version imports it as a new program instead of skipping it.

TestBcu1.knxproj: the samples contain no BCU 1 device (mask 1.2, MV-0012), whose program keeps the
system header, the tables, the object table and the parameters in its only segment, the EEPROM
0100-01FF, and is loaded by the generic procedure of the mask (DefaultProcedure). This copy adds two
artificial programs of that kind, marked as test data by their names, and replaces the project's
device by two devices of them on line 1.0: "TEST BCU1 mit Daten" (1.0.1) runs the program
M-0008_A-FFF1-01-0000, whose segment has static data (bytes made up for the test, in the layout of
the BCU 1 memory map: the header 0100-0115 with the pointers 0111 and 0112 to the association table
0125 and the object table 0136, the address table 0116 for 6 group addresses, the association
table for 8 associations, an object table of three objects, four parameters from 0150, and 5A in
01FF, which the mask's procedure does not write); "TEST BCU1 ohne Daten" (1.0.2) runs
M-0008_A-FFF2-01-0000, the same program with a segment without static data. Both have the
parameters Betriebsart = 2 and Verhalten = 2 set, and their objects 0 (send 0/0/3, receive 0/0/1) and
1 (send 0/1/1) connected.

The projects of all fixtures are named after the fixture, not "Test".

The master data (knx_master.xml) of the fixtures derived from the Test project are trimmed to what
the fixture uses (trim_master): the datapoint types it names, the mask versions of its programs with
those they are downward compatible to, their media, and the manufacturers it names (haumacher/ehome
#36). ETS writes its whole master data into every file; the trimmed part keeps an import of a fixture
cheap, also into an empty database, and a test of the master data import compares with what its
fixture defines, not with the whole master data of ETS.

TestProduct.knxprod: a product file of a manufacturer, as ETS imports it into its catalog: the Test
project without its project part (the folder P-01A0 and its signature), that is, the master data and
the data of the manufacturer M-0008 (catalog, hardware, application program). Its IDs are those of
the Test project, so an import of it reuses what an import of the Test project created, and vice
versa.

TestNewProduct.knxprod and TestNewProduct.knxproj: the same pair of a product file and a project, with
the IDs of the product data changed (the hardware, its product, the catalog sections, the application
program with a new version O00F0, and the assignment of the program to the hardware) and names
marking them as test data, so that a database already holding the Test project imports them as new
products. The project (named "TestNewProduct") uses the renamed product.

Encrypted.knxprod: TestProduct.knxprod with an entry of its manufacturer folder encrypted with the
traditional ZIP password protection (password "test"), which Java cannot read; the import refuses
it. Its content is a short text, not ETS data.

TestEts6.knxproj: the samples are ETS 4 files (project schema 11); this copy is written as ETS 6
writes a project (schema 23, haumacher/ehome #32), with artificial content marked as test data:
- Master data: the named values of 20.102 HVAC mode (DatapointSubtype/Format/Enumeration/EnumValue,
  the English names of the KNX standard, with German translations made up for the test, the value
  4 without one), a composed format of 10.001 whose enumeration names only the day field (not a
  named value of the subtype), the medium KNX IoT (MT-6) and an artificial mask version
  "MV-FFF0" on it with a resource in the address space RelativeMemoryByObjectType and a load
  procedure with a master reset.
- The program gets a new ID (version O0024, so that an import does not skip it as known) and a name
  marking it as test data. It adds a channel independent block whose switch places one parameter
  reference and one communication object reference in two cases (ETS 6 shares a reference between
  cases), a parameter block directly in the program with a color parameter (TypeColor), and a
  dynamic module (ModuleDefs, a module in a case) whose argument comes from an allocator (Allocators,
  haumacher/ehome #119).
- The project part is the folder P-01A0 with project.xml (lower case, ETS 5/6) and 0.xml: the
  device lies in a segment of its line, names its communication objects by IDs relative to its
  program and links them to group addresses by the attribute Links (IDs relative to the
  installation; one object sends to GA-3 and receives from GA-1), reports its load state as "true"
  and "false", sets the shared parameter, sets a parameter of an instance of the module and links an
  object of the instance (IDs MD-1_M-1_MI-1_...), which do not resolve: the device does not take the
  case placing the module, so it has no instance of it, and the module has no such object; and
  the building is a location with spaces.
The test builds the protected variants (the project part as P-01A0.zip, AES and password, or plain)
from this file with zip4j.

TestModules.knxproj: the samples contain no dynamic module (ETS 5.6 and later, haumacher/ehome #119).
This file (schema 23, the master data and the project of the Test project) holds an artificial program
of the BCU 1 kind (as in TestBcu1.knxproj, with the memory of "TEST BCU1 mit Daten"), marked as test data by its name, M-0008_A-FFF7-01-0000 on
its own hardware "TEST Module", with one module definition "Kanal" (MD-1) with the arguments
ParamOffsBase, ObjNumberBase and ChNo: the parameters "Modus" (2 bits at byte 2, bit 4, default 2
"Treppenlicht") and "Beschreibung" (a text of 4 bytes at byte 3, default "Flur") at BaseOffset
ParamOffsBase, the objects "Schalten" (Number 0) and "Status" (Number 1) at BaseNumber ObjNumberBase,
the reference of "Schalten" with the text "Kanal {{ChNo}}: {{0}}" filled from "Beschreibung"
(TextParameterRefId), and the structure: a channel "Kanal {{ChNo}}" with a block placing both
parameters, "Schalten" in a switch on "Modus" = 1, and "Status". The texts of the module have English
translations. The program places the module twice: "Kanal A" (0x60, 1, A) at the top of its dynamic
part after a channel independent block, "Kanal B" (0x70, 3, B) in the case "Ein" of a switch on the
program's parameter "Kanal B" (default "Aus"). The program's own object "Zentral" has the text
"Zentral {{0}}" filled from its parameter "Bezeichnung" (default "Haus"). The device "TEST Modulgerät"
(1.0.1, as ETS 6 writes it: objects relative to the program, Links, ModuleInstances with their
arguments) sets "Kanal B" = "Ein", "Modus" of Kanal A = 1 and its "Beschreibung" = "WZ", the
"Beschreibung" of Kanal B empty, and links
"Zentral" to GA-1, Kanal A "Schalten" to GA-2, its "Status" to GA-3, and Kanal B "Schalten" to GA-4 and
GA-1. The device "TEST Modulgerät alt" (1.0.2, the same program) lists for the instance of Kanal A the
argument ParamOffsBase 80 instead of 96 (0x60): its memory was written with another layout. It has
the default "Aus" of "Kanal B", so E-Home computes no instance of the placement "Kanal B", but it sets
the "Beschreibung" of that instance ("Bad") and links its "Schalten" to GA-4 (as ETS keeps the values
of a module whose placement is not in effect).

TestModules.knxproj also holds a second artificial program of the BCU 1 kind, M-0008_A-FFF8-01-0000,
marked as test data by its name, assigned to "TEST Module" as well, with repeats, allocators and a
sub-module (haumacher/ehome #119, the layouts of the ABB Modbus RTU Gateway and the Zennio DALI BOX):
- the parameters "Anzahl Signale" (0 to 8, default 2, byte 0x58) and "Anzahl Kanäle" (0 to 3, default
  2, byte 0x59) in the block "Allgemein";
- the objects 0 to 2 of its object table ("Allgemein 0" to "Allgemein 2"), placed nowhere;
- a Repeat over "Anzahl Signale" placing the module "Signal" (MD-1): its ParamsOffsetBase from the
  allocator Signal_params (Start 160, maxInclusive 224, Allocates 32), its ObjNumberBase from
  Signal_objs (Start 10, Allocates 2), its number SigNo from Nummer (Start 1, maxInclusive 6, Allocates
  1). The module has the parameters "Typ" (an enumeration Aus 0 / Analog 1 / Digital 2, default 1, at
  offset 0) and "Name" (a text of 4 bytes, default "Sig", at offset 1), the objects "Wert" (Number 0)
  and "Status" (Number 1), the reference of "Wert" with the text "Signal {{SigNo}}: {{0}}" filled from
  "Name", and the structure: a channel "Signal {{SigNo}}: {{0}}" and in it a block
  "{{0:Signal {{SigNo}}}}", both filled from "Name" (TextParameterRefId), placing the parameters and
  objects. So the signals lie at 160, 192, 224, their objects at 10/11, 12/13, 14/15, and a fourth
  signal (offset 256) exceeds Signal_params;
- a Repeat over "Anzahl Kanäle" placing the module "Kanal" (MD-2): its KaOffs from Kanal_params (Start
  96, Allocates 12), its KaObj from Kanal_objs (Start 20, Allocates 4), its number KaNo from Nummer
  (shared with the signals: the channels are numbered after them). The module has the parameter
  "Anzahl Szenen" (0 to 5, default 1, at offset 0), the object "Kanal {{KaNo}}" (Number 0), allocators
  of its own (Szene_params Start 2 maxInclusive 6, Szene_objs Start 1, Szene_num Start 1), and in its
  channel a Repeat over "Anzahl Szenen" placing the sub-module "Szene" (MD-2_SM-1): SzOffs from
  Szene_params on top of KaOffs (BaseValue), SzObj from Szene_objs on top of KaObj, KaNo as 0 on top of
  the channel's KaNo, SzNo from Szene_num. The scene has the parameter "Wert" (0 to 255, default 5, at
  offset 0) and the object "Szene" (Number 0) with the text "Kanal {{KaNo}}, Szene {{SzNo}}". So with
  two scenes in the second channel (offset 108), its scenes lie at 110 and 112, their objects at 25 and
  26 (after 21 of the first channel's scene).
The device "TEST Repeat-Gerät" (1.0.3, this program) sets "Anzahl Signale" = 3, the "Name" of signal 2
= "Süd", that of signal 3 empty, "Anzahl Szenen" of channel 2 = 2 and the "Wert" of its scene 2 = 7 (named as
MD-2_M-1_MI-2-1-2_SM-1_P-1_R-1), links "Wert" of signal 2 to GA-2 and the object of scene 2 of channel 2
(MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_O-1-0_R-1) to GA-3, and lists its module instances with their arguments
as ETS writes them (an argument fed by an allocator by the allocator's ID).

TestLanguagesA.knxprod and TestLanguagesB.knxprod: product files holding the master data (trimmed) and
two artificial programs of the BCU 1 kind (as in TestBcu1.knxproj), marked as test data by their
names, without catalog and hardware (haumacher/ehome #38). Each program is written once in German
(DefaultLanguage de-DE, with a de-DE translation of every text equal to it, as Test.knxproj writes its
program) and once in English (DefaultLanguage en-US, with de-DE translations giving the same German
texts, and en-US translations equal to the English texts but one: the parameter "Delay" is translated
as "Switch-on delay"). TestLanguagesA holds M-0008_A-FFF3-01-0000 in German and M-0008_A-FFF4-01-0000
in English, TestLanguagesB the other way round: importing A, then B imports each program in one of
the two orders, the German file first (B adds the English texts) or the English one first (B adds
nothing).

TestMasterDataOld.knxproj and TestMasterDataNew.knxproj: master data only (knx_master.xml, no
manufacturer, no project), of the artificial versions 1001 and 1002, for the mask versions by the
version of their master data (haumacher/ehome #34). They hold the medium TP and two artificial mask
versions marked as test data by their names: "MV-FFF1" with two configurations, whose legacy marking
and order the new master data swap (as ETS 6 does for the IP router mask MV-091A against ETS 4),
with a changed feature value and a further load step, and which is downward compatible to MV-FFF2
(a forward reference); "MV-FFF2" with a management descriptor, the same in both versions.

TestHiddenValues.knxproj: the samples contain no program value outside its type for a hidden parameter
(haumacher/ehome #83). This copy of the Test project gives the application program a new ID (version
O0083, so that an import does not skip it as known) and a name marking it as test data, and adds an
enumeration type "Test_Kind" with the entries 4, 5 and 6 and parameters of it, each with one reference
and placed in the block "Allgemein": "Verborgen" (access None, value 7), "Sichtbar" (ReadWrite, value
7), "Verborgen am Ort" (ReadWrite, value 7, its reference None), "Sichtbar am Ort" (None, value 7, its
reference ReadWrite), "Verborgene Vorgabe" (None, value 5, its reference without access with the value
7), "Sichtbare Vorgabe" (ReadWrite, value 5, its reference with the value 7) and "Einstellung"
(ReadWrite, value 5), which the device of the project sets to 7. The import keeps every 7 as text; it
warns about the values of the visible parameters ("Sichtbar", "Sichtbar am Ort", "Sichtbare Vorgabe")
and about the setting, not about those of the hidden ones.

TestFunctionKeys.knxproj: the samples contain no dimming or blind actuator and no push-button sensor
whose keys send on the inputs of an actuator (haumacher/ehome #92). This copy of the Test project
replaces its device by two devices of two artificial programs of the BCU 1 kind (as in
TestBcu1.knxproj, without parameters), marked as test data by their names, with numbered channels:
"TEST Aktor" (1.0.1, program M-0008_A-FFF5-01-0000) with "Kanal A" (Schalten 1.001 on 1/1/1,
Status 1.011 on 1/1/8), "Kanal B" (Schalten 1.001 on 1/1/2, Dimmen 3.007 on 1/1/3, Helligkeitswert
5.001 on 1/1/4, Status Helligkeitswert 5.001 on 1/1/5), "Kanal C" (Fahren 1.008 on 2/1/1,
Stopp/Lamellenverstellung 1.007 on 2/1/2, Position anfahren 5.001 on 2/1/3, Status Position on
2/1/4, Lamellenposition on 2/1/5, Status Lamellenposition on 2/1/6) and "Zentral" (Schalten
Zentral 1.001 on 1/0/0); "TEST Taster" (1.0.2, program M-0008_A-FFF6-01-0000) with "Taste 1"
(sending on 1/1/1), "Taste 2" (Schalten on 1/1/2, Dimmen 3.007 on 1/1/3), "Wippe 3" (Langzeit
1.008 on 2/1/1, Kurzzeit 1.007 on 2/1/2), "Taste 4" (on 1/0/0), "Taste 5" (on 1/1/7) and
"Status-LED" (receiving 1/1/7). The actuator's objects receive (write flag), its status objects
send; the keys' objects send and have the write flag as well (as the Gira push-button sensor of the
test system), the status LED only receives. The objects without a datapoint type are the keys'
1-bit objects and the LED; 1/1/7 has no type at all. The push-button sensor's program has a
temperature as well ("Temperatur", Raumtemperatur 9.001), which TestFunctionKeys does not connect.

TestFunctionRooms.knxproj: the same programs for the rooms of the proposed functions (haumacher/ehome
#93), in the building of TestDetails (Haus, Erdgeschoss: the room "Wohnzimmer", the room "Küche",
the distribution board "Verteiler"). The actuator (1.0.1, all objects as in TestFunctionKeys) is
placed in the distribution board; three push-button sensors connect some of their objects: "TEST
Taster Küche 1" (1.0.2, in "Küche": Taste 2 Schalten on 1/1/2, Wippe 3 Langzeit on 2/1/1), "TEST
Taster Küche 2" (1.0.3, in "Küche": Taste 2 Dimmen on 1/1/3, the temperature on 3/1/1) and "TEST
Taster Wohnzimmer" (1.0.4, in "Wohnzimmer": Taste 1 on 1/1/1, Taste 2 Dimmen on 1/1/3, Wippe 3
Kurzzeit on 2/1/2).

TestFunctionLinks.knxproj: the same programs for the candidates of the function proposal linked by
their sending addresses (haumacher/ehome #98), with group addresses of their own (main group 4) and
objects receiving from further addresses as well (an object sends on its first address and receives
from the others). "TEST Aktor Küche" (1.0.1): "Kanal A" Schalten on 4/0/1 "Licht Küche" and the
central 4/0/9 "Licht Zentral", Status on 4/0/2 "Licht Küche Status"; "Kanal B" Schalten on 4/0/3
"Licht Flur" and 4/0/9; "Zentral" on 4/0/9; "Kanal C" connected to nothing. "TEST Aktor Flur" (1.0.2):
"Kanal B" Schalten on 4/0/3 (two actuators on one address); "Kanal A" and "Kanal C" connected to
nothing. "TEST Taster Küche" (1.0.3): "Taste 1" sending on 4/0/1 and receiving the status 4/0/2 (a
toggle key), "Taste 4" on the central 4/0/9, "Taste 5" on 4/0/8 "Klingel", which no other object
receives, the temperature on 4/1/1 "Raumtemperatur Küche"; "Taste 2" and "Wippe 3" connected to
nothing. The addresses 4/0/4 "Licht Bad" and 4/2/1 "Markise fahren" have no object; the test
connects some later.

TestChannels.knxproj: the samples contain no channel descriptions of a device (ETS 6: ChannelInstances)
and no renamed parameter block (Rename, haumacher/ehome #122). This file (schema 23, the master data and
the project of the Test project) holds an artificial program of the BCU 1 kind, M-0008_A-FFF9-01-0000,
marked as test data by its name, on its own hardware "TEST Kanäle": the parameter "Funktion" (Aus 0 / Ein
1, default Aus) in the block "Allgemein" of the channel 1 "Kanal A" (English "Channel A"), whose case Ein
renames the block "LED" of the same channel to "LED oben rechts" (English "LED top right"); the channel 2
"Kanal B" with the block "Reserve", which a case Ein of a switch directly in the dynamic part renames to
"Kanal B (frei)"; and a module "Modul" (MD-1, arguments ParamOffsBase and No) placed once (No 1), with a
channel "Modul {{No}}" whose block "Wert" places the parameter "Wert" (Aus/Ein) and is renamed to "Wert
{{No}} (ein)" by its case Ein. The device "TEST Kanalgerät" (1.0.1) sets "Funktion" and the module's "Wert"
to Ein and describes its channels: CH-1 "Binäreingang Küchentür" (active), CH-2 "Reserve" (IsActive
false), the module instance's channel MD-1_M-1_MI-1_CH-1 "Modulkanal Flur" (active); it carries a
Security element (KNX Secure, not read: the import warns). The device "TEST Kanalgerät ohne" (1.0.2) has
the defaults and no channel descriptions. project.xml holds ProjectTraces (ETS 6's history of the
project, skipped without a warning).

TestCalculations.knxproj: the samples contain no parameter calculation (ETS 6: ParameterCalculation,
haumacher/ehome #123). This file (schema 23, the master data and the project of the Test project) holds
two artificial programs of the BCU 1 kind on their own hardware "TEST Berechnungen", marked as test data
by their names. M-0008_A-FFFA-01-0000 has the L parameters (without memory) "Dauer" (seconds, 0..86399,
default 60), "Schwelle" (Niedrig 1 / Mittel 2 / Hoch 3, default Hoch) and "Fehlerwert" (0..10, default 1),
and the R parameters (access None, in memory from 0x158) "Dauer_h" (0..23, default 0), "Dauer_min"
(0..59, default 1), "Dauer_s" (0..59, default 0), "Szene" (0..255, default 0: not what the calculation
gives for the default of "Schwelle") and "FehlerR" (0..255, default 30), with the calculations, their
code inline as ETS 6 writes it (RLTransformation, LRTransformation, then the parameters): PC-1
"s2hms" in VBScript without aliases (the names of the parameters: LR Dauer_h = (Dauer \\ 3600), Dauer_min =
(Dauer - (3600 * Dauer_h)) \\ 60, Dauer_s = Dauer - (3600 * Dauer_h) - (60 * Dauer_min); RL Dauer =
(3600 * Dauer_h) + (60 * Dauer_min) + Dauer_s), PC-2 in JavaScript with the aliases BlindsActionThreshold
and BlindScene as functions of the program's Script (LRTransformationFunc "szeneLR" with
LRTransformationParameters {"Faktor": 1}: if input.BlindsActionThreshold is 3, output.BlindScene =
verdoppeln(context.Faktor), another function of the Script; RLTransformationFunc "szeneRL": if
input.BlindScene is 2, output.BlindsActionThreshold = 3), PC-3 in
JavaScript with the aliases x and r (LR: an exception for 7, an endless loop for 9, else r = x * 30, which
is out of range for 10; RL x = r / 30). Its module "Teil" (MD-1, argument ParamOffsBase) has the L
parameters "Zeit" (0..1000, default 50) and "Teiler" (1..100, default 10) and the R parameters "Stufe"
(offset 0, default 0: inconsistent) and "Rest" (offset 1, default 5), with the calculation of the issue,
MD-1_PC-1 in JavaScript with the aliases p1 (Zeit), p3 (Teiler), p2 (Stufe), p4 (Rest): LR p2 = p1 / p3;
if (p2 < 1) p2 = 1; p4 = p1 / p3; if (p4 < 1) p4 = 0; RL p1 = mal(p2, p3), a function of the module's
own Script. The module is placed twice, at 0x160 and 0x168. M-0008_A-FFFB-01-0000 has the L parameter
"Gesperrt" and the R parameter "GesperrtR" (0x158) of a VBScript calculation with Select Case, which E-Home
does not support, and the L parameter "Minuten" (default 1) and the R parameter "Sekunden" (0x159, default
60) of a VBScript calculation calling the function InSekunden of the program's VBScript Script (LR
Sekunden = InSekunden(Minuten), RL Minuten = Sekunden \\ 60). Devices: "TEST
Rechengerät" (1.0.1, FFFA) with consistent values (Dauer 3725 = 1 h 2 min 5 s, Szene 2, Zeit 55 → Stufe
5 and Rest 5 in the first instance, Stufe 5 in the second); "TEST Rechengerät RL" (1.0.2, FFFA) with R
values only (Dauer_h 2, Dauer_min 0, Dauer_s 30, Stufe 7 in the first instance), from which RL derives
the L values; "TEST Rechengerät gesperrt" (1.0.3, FFFB) with GesperrtR 1.

TestValidations.knxproj: the samples contain no parameter validation (ETS 6: ParameterValidation,
haumacher/ehome #124). This file (schema 23, the master data and the project of the Test project) holds
two artificial programs of the BCU 1 kind on their own hardware "TEST Prüfungen", marked as test data by
their names, with the validations in the shape of the KNX schema (Static/ParameterValidations/
ParameterValidation with Id, Name, ValidationFunc, optional ValidationParameters and InternalDescription,
Parameters/ParameterRefRef with RefId and AliasName) and with the calling convention of the programs in the
field (as in OpenKNX OFM-PresenceModule and OFM-AccessControl; the functions are artificial).
M-0008_A-FFFC-01-0000 (JavaScript Script, after the association table as the schema orders it) has the L
parameters (without memory) "Untergrenze" (0..100, default 1), "Obergrenze" (default 5), "Modell" (Basis
0 / Profi 1, default Basis), "Nummer" (0..2000, default 1), "Helligkeit" (0..255, default 10),
"Fehlerwert" (0..10, default 0), "Typ" (0..5, default 0) and the R parameter "TypR" (access None, 0x158,
default 0), with the validations PV-1 "Abstand" (TEST_abstand, aliases Low and High: Low below High, an
InternalDescription), PV-2 "Kapazitaet" (TEST_kapazitaet: Nummer at most 100 or 1000 by the value of
Modell, the text by changed), PV-3 "Helligkeit" (without an alias, ValidationParameters {"Max": 50}: at
most context.Max, else a text with prevValue), PV-4 "Fehler" (alias Wert: 7 throws, 8 answers false) and
PV-5 "Typ" (alias Art: 4 is refused), the calculation PC-1 "Typ" with the LR and RL function
TEST_uebernehmen (the alias Art on both sides: Typ to TypR), and a function of a button
(Button@EventHandler, not called) and a top-level variable in the Script. Its module "Kanal" (MD-1, placed
twice) has the parameters "Ein" (default 10) and "Aus" (default 20) and the validation MD-1_PV-1
"EinVorAus" (TEST_kanal of the program's Script: Ein below Aus). M-0008_A-FFFD-01-0000 (VBScript Script)
has the parameters "Von" (default 1), "Bis" (default 10), "Wert" (default 5) and "Gesperrt" (default 0),
with the validations PV-1 "Bereich" (PruefeBereich: Von not above Bis, a text by changed with prevValue),
PV-2 "Grenze" (PruefeGrenze without an alias, ValidationParameters {"Grenze": 50}: False above it) and
PV-3 "Fehlt" (ValidationFunc PruefeFehlt, which the Script does not define). Devices: "TEST Prüfgerät"
(1.0.1, FFFC, Untergrenze 2, Obergrenze 8), "TEST Prüfgerät ungültig" (1.0.2, FFFC, Untergrenze 9 and
Obergrenze 3, and Ein 50 in the second module instance: values the validations refuse), "TEST Prüfgerät
VBScript" (1.0.3, FFFD, defaults).

TestUpdateBase.knxproj, TestUpdateNew.knxproj and TestUpdateAdopt.knxproj: a project updated in ETS
(haumacher/ehome #125). The Test project with the ID P-0125 (its own ID, so that no other fixture is
the same project), named "TestUpdate". The base adds to the area "Neuer Bereich" the empty line 1.1
"Linie 2" (L-2); beside the device of the Test project (named "Taster Küche", 1.0.1, placed in "Küche") the
devices "Taster Flur" (DI-2, 1.0.2) and "Taster Reserve" (DI-3, 1.0.3) of the same product, each setting
one parameter and sending on one address; the group addresses "Unbenutzt" (GA-11, 1/0/1) and "Jalousie"
(GA-12, 1/0/2), both connected to no object; and the building of TestDetails (Haus, Erdgeschoss:
Wohnzimmer, Küche, Verteiler). The update ("updated in ETS") re-addresses and renames GA-3 (0/0/3
"GA3" to 0/0/4 "Licht Küche"), adds "Neu" (GA-13, 0/0/5), removes GA-11 and GA-12, changes a parameter
of "Taster Küche" (P-2384 "ZyklSenden" from 255 to 30), moves "Taster Flur" to the line 1.1 (1.1.2), removes "Taster
Reserve", adds "Taster Bad" (DI-4, 1.1.1, placed in the new room) and adds the room "Bad" (BP-6) as the
last part of "Erdgeschoss". TestUpdateAdopt.knxproj is the update with one device more, "Taster Diele"
(DI-5, 1.0.5, the same product), for a device planned in E-Home at that place.

TestNaming.knxproj: group addresses an ETS project left without names, the case of the assistant's
naming of group addresses (haumacher/ehome #127). TestDetails, named "TestNaming", with every group
address's name removed (Name="") and moved into its description, where the address had none ("GA-1"
keeps its description "Raumtemperatur Küche").

TestBaggage.knxprod, TestBaggageNewer.knxprod, TestBaggageDriver.knxprod and TestBaggage.knxproj: the
files a manufacturer ships with its product data (ETS: baggage, haumacher/ehome #154), none of them a
manufacturer's: pictures, archives and texts made for the test. TestBaggage.knxprod holds the master
data (trimmed), an artificial program of the BCU 1 kind "TEST: Beigaben (künstliche Testdaten)"
(M-0008_A-FFB0-01-0000) whose extension names three files, a hardware with its product, the index
M-0008/Baggages.xml and the files, which come before the index and the program in the archive: the
picture "TEST_Bild.png" (a 4x4 PNG, red, of 2026-11-01T10:00:00Z, no folder), the archive
"hilfe/TEST_Hilfe.zip" (one text, of 2026-11-02T09:03:26.8724918Z, seven decimal places as ETS
writes them), "TEST_Fehlt.png", listed and named by the program but missing in the archive, and
"TEST_Ungelistet.txt", in the archive but not listed; the program has a parameter "Schaltbild" of a picture
type (TypePicture) showing "TEST_Bild.png", placed in its block. TestBaggageNewer.knxprod is the same with a
newer picture (green, of 2026-11-05T08:00:00Z) and an older archive with another text (of
2026-10-01T00:00:00Z), without the unlisted file. TestBaggageDriver.knxprod holds no application
program, only a hardware whose product names the file "inf/TEST Treiber.msi" (3000 bytes of text,
no installer), as MDT's USB interface does. TestBaggage.knxproj is the Test project with the picture
"TEST_Projekt.png" (blue) in its manufacturer's baggage and an artificial program "TEST: Beigaben im
Projekt (künstliche Testdaten)" (M-0008_A-FFB1-01-0000, used by no device) naming it.

TestPictures.knxprod: the pictures of the parameter dialog (ETS: TypePicture, haumacher/ehome #155), with
pictures made for the test. An artificial program of the BCU 1 kind (with the memory of "TEST BCU1 mit Daten") "TEST: Bilder im
Parameterdialog (künstliche Testdaten)" (M-0008_A-FFB2-01-0000) on its own hardware "TEST Bilder", whose block places,
inside a choose on "Betriebsart" (default Schalten) in its case Treppenlicht (2), three parameters of
picture types: "Schaltbild Treppenlicht" (P-9) showing "TEST_Schaltbild.png" (a 3x2 PNG, yellow), "Kurve
Treppenlicht" (P-10) showing "TEST_Kurve.bmp" (a 2x2 BMP, a format a browser is not asked to show) and
"Fehlendes Bild" (P-11) showing "TEST_Ohne.png", listed in the index but missing in the archive.

TestValueForms.knxprod: the value forms of Theben's complete database (haumacher/ehome #160), with two
artificial programs of the BCU 1 kind, read before their hardware in the file. "TEST: Wertformen der
Produktdaten (künstliche Testdaten)" (M-0008_A-FFB3-01-0000, hardware "TEST Wertformen") has an
enumeration "Versatz" of 8 bits whose entry "-60%" has the value 4294967236 (the 32-bit form of -60, as
Theben writes it), "0%" 0 and "5%" 5, a parameter "Versatz" (P-9, at offset 0x54 of its segment) of it with
the default 4294967236, and, in its block, a separator (ParameterSeparator PS-1, which E-Home does not
import) that a case of "Betriebsart" (Treppenlicht) renames together with the block (PR-1, PR-2). "TEST:
Zweites Programm der Produktdaten (künstliche Testdaten)" (M-0008_A-FFB4-01-0000, hardware "TEST
Schritte") is a plain BCU 1 program, imported in a step of its own.

TestChannelNumber.knxprod: a channel numbered in its own notation (haumacher/ehome #162): the KNX schema types
Channel@Number as a text (String50_t), and Theben's program M-0048_A-F140-13-8536 numbers its logic channels "18d".
An artificial program of the BCU 1 kind "TEST: Kanalnummer als Text (künstliche Testdaten)"
(M-0008_A-FFA1-01-0000, hardware "TEST Kanalnummer") whose channel "Kanal A" is numbered "18d" (CH-18d).

TestPlugins.knxprod: programs needing what ETS has besides the product data (Static/Extension, haumacher/ehome
#159), three artificial programs of the BCU 1 kind, each on its own hardware: "TEST: Plug-in der Bedienoberfläche
(künstliche Testdaten)" (M-0008_A-FFA2-01-0000, hardware "TEST Plugin UI") with a user interface plug-in
(EtsUiPlugin) only; "TEST: Download-Plug-in (künstliche Testdaten)" (M-0008_A-FFA3-01-0000, "TEST Plugin
Download") with a user interface and a download plug-in (EtsDownloadPlugin); "TEST: Daten-Handler und externe
Software (künstliche Testdaten)" (M-0008_A-FFA4-01-0000, "TEST Plugin Daten") with a data handler
(EtsDataHandler) and RequiresExternalSoftware="true". The plug-in IDs are made up.

TestResources.knxprod and TestResourcesNewer.knxprod: the icons and help texts of a program from archives of the
baggage (ETS: IconFile, Icon, ContextHelpFile, HelpContext, haumacher/ehome #156, #157), with icons and texts made
for the test. An artificial program of the BCU 1 kind "TEST: Symbole und Hilfe (künstliche Testdaten)"
(M-0008_A-FFB5-01-0000, hardware "TEST Symbole") names the icon archive "TEST_Symbole.zip" (the PNG pictures
TEST_Aus (2x2, red), TEST_Ein (2x2, green), TEST_Kanal (2x2, blue), TEST_Block (2x2, gray) and the SVG
TEST_Vektor, a document with a script, which is not shown) and the help archive "TEST_Hilfe_DE.zip" of its
default language (de-DE), translated to "TEST_Hilfe_EN.zip" (en-US) and to "TEST_Hilfe_FR.zip" (fr-FR, a language
E-Home does not offer, not in the baggage). The entries of "Betriebsart" name the icons TEST_Aus, TEST_Ein and
TEST_Fehlt (which the archive does not hold); the channel "Kanal A" names TEST_Kanal; the block "Allgemein" names
TEST_Block and the help topic "Allgemein"; the parameter "Betriebsart" at its place the topic "Betriebsart", the
parameter "Verzögerung" the topic "Leer" (a text of white space only), "Verhalten" the topic "NurDeutsch" (a text
in German only) and "Zeit" the icon TEST_Vektor and the topic "Größe", whose file is named in the original encoding
of the ZIP format (IBM 437, without the UTF-8 flag), as in Theben's help archives. The German texts are written as ETS writes them on Windows:
UTF-8 with a byte order mark and CRLF line ends, in Markdown (bold, italics, a rule), one with raw HTML (a
script). TestResourcesNewer.knxprod holds the same program with a newer icon archive (of 2026-11-14) that adds
TEST_Fehlt (2x2, yellow) and gives TEST_Ein another color (2x2, white). TestResources.knxproj is the Test project
named "TestResources" with the same archives in its manufacturer's baggage and the same
program as "TEST: Symbole und Hilfe im Projekt (künstliche Testdaten)" (M-0008_A-FFB6-01-0000, used by no device).

TestButtons.knxproj: the samples contain no button of the parameter dialog (ETS 6: Button in the dynamic
part, haumacher/ehome #158). This file (schema 23, the master data and the project of the Test project) holds
an artificial program of the BCU 1 kind on its own hardware "TEST Schaltflächen", marked as test data by its
name, M-0008_A-FFE2-01-0000, with buttons in the shape of the KNX schema (Button with Id, Text, EventHandler,
EventHandlerParameters) calling functions of the program's JavaScript Script in the convention of the programs
in the field (MDT BE-GTSx6Tx-01S: function(device, online, progress, context) with
device.GetParameterById(id).Value; MDT GTL-xxxx-02S: device.getParameterByName(name).value; the functions are
artificial). The block "Allgemein" holds the text parameters "Quelle" (P-1, default "leer") and "Ziel" (P-2,
default empty) and the numbers "Anzahl" (P-3, 0..100, default 5) and "Kopie" (P-4, 0..100, default 0), with
the validation PV-1 "Hoechstens" (Kopie at most 50), and the buttons B-1 "Text kopieren" (English "Copy text";
Ziel takes Quelle), B-2 "Anzahl kopieren" (EventHandlerParameters {"Plus": 1}; Ziel takes "Anzahl " and the
number, then Kopie the number plus context.Plus), B-3 "Unbekannt" (a member of a parameter E-Home does not
provide, .Text, visible in the text of the function), B-4 "Versteckt" (Ziel takes "geändert", then the member
.Text of a parameter held in a variable, found only when it runs) and B-6 "Gerät" (online.connect()), and in a
case of a switch on Anzahl (when 7) the button B-5 "Bei sieben" (as B-1). Its module "Kanal" (MD-1, placed twice
as "Kanal 1" and "Kanal 2") has the numbers "Wert" (default 1) and "Kopie" (default 0) and the button MD-1_B-1
"Wert kopieren" (Kopie takes Wert, by the IDs of the module definition, MD-1_P-1_R-1). Device: "TEST
Schaltgerät" (1.0.1, Quelle "Hallo", Wert 3 in the second module instance).

Usage (from the app module, ehome/ in the repository): python3 src/test/fixtures/make-fixtures.py
"""
import base64
import io
import re
import struct
import zipfile
import zlib

SOURCE = 'samples/Test.knxproj'
PROJECT = 'P-01A0/0.xml'


PROJECT_INFO = 'P-01A0/Project.xml'

# Names that make clear in the app that the fixtures are artificial test data.
PROGRAM_NAME = 'Tastsensor 3 Komfort 3fach 10D711'
TEST_PROGRAM_NAME = 'TEST: bedingter Kanal (künstliche Testdaten)'


def rename_project(xml, name):
    project = '<ProjectInformation Name="Test"'
    assert xml.count(project) == 1
    return xml.replace(project, '<ProjectInformation Name="' + name + '"', 1)


def details(xml):
    device = '<DeviceInstance Id="P-01A0-0_DI-1" '
    assert xml.count(device) == 1
    xml = xml.replace(device, device
        + 'Name="Taster Küche" Description="Neben der Tür" InstallationHints="Unterputzdose links" '
        + 'SerialNumber="0008:1234ABCD" LastDownload="2025-08-06T10:00:00" ', 1)
    for flag in ('IndividualAddressLoaded', 'ApplicationProgramLoaded', 'ParametersLoaded',
                 'CommunicationPartLoaded', 'MediumConfigLoaded'):
        xml = xml.replace(flag + '="0"', flag + '="1"', 1)

    # The first communication object is not in use.
    active = 'IsActive="1"'
    assert active in xml
    xml = xml.replace(active, 'IsActive="0"', 1)

    # Details of a group range and a group address.
    group_range = '<GroupRange Id="P-01A0-0_GR-3" '
    assert xml.count(group_range) == 1
    xml = xml.replace(group_range, group_range + 'Unfiltered="1" Description="Küche" ', 1)
    group_address = '<GroupAddress Id="P-01A0-0_GA-1" '
    assert xml.count(group_address) == 1
    xml = xml.replace(group_address, group_address
        + 'Central="1" Unfiltered="1" Description="Raumtemperatur Küche" ', 1)

    # A building with a floor, an empty room, a room holding the device, and an empty distribution
    # board. The empty room "Wohnzimmer" comes first and has the highest ID: the order of the parts
    # (haumacher/ehome #71) is neither that of their names nor that of their IDs.
    buildings = '<Buildings />'
    assert xml.count(buildings) == 1
    xml = xml.replace(buildings, '<Buildings>\n'
        + '          <BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">\n'
        + '            <BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">\n'
        + '              <BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room" />\n'
        + '              <BuildingPart Id="P-01A0-0_BP-3" Name="Küche" Type="Room">\n'
        + '                <DeviceInstanceRef RefId="P-01A0-0_DI-1" />\n'
        + '              </BuildingPart>\n'
        + '              <BuildingPart Id="P-01A0-0_BP-4" Name="Verteiler" Type="DistributionBoard" />\n'
        + '            </BuildingPart>\n'
        + '          </BuildingPart>\n'
        + '        </Buildings>', 1)

    # A second device of the same product, not placed in a line yet.
    end = '</Topology>'
    assert xml.count(end) == 1
    xml = xml.replace(end, '  <UnassignedDevices>\n'
        + '            <DeviceInstance Id="P-01A0-0_DI-2" '
        + 'ProductRefId="M-0008_H-8.20.2F.2F.20zxy3.20xx-1_P-5133.2000" '
        + 'Hardware2ProgramRefId="M-0008_H-8.20.2F.2F.20zxy3.20xx-1_HP-10D7-11-A0E5-O000A" '
        + 'Name="Taster Reserve" LastModified="2025-08-05T18:58:05" />\n'
        + '          </UnassignedDevices>\n        ' + end, 1)
    return xml


PROGRAM = 'M-0008_A-10D7-11-A0E5-O000A'
NEW_PROGRAM = 'M-0008_A-10D7-11-A0E5-O000E'


# The version suffix shared by the IDs of the program and of its assignment to the hardware.
VERSION = '10D7-11-A0E5-O000A'
NEW_VERSION = '10D7-11-A0E5-O000E'


def dynamic(name, xml):
    xml = xml.replace(VERSION, NEW_VERSION)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestDynamic')
    if name == PROJECT:
        return typed_setting(xml)
    if name.endswith(PROGRAM + '.xml'):
        assert xml.count('"' + PROGRAM_NAME + '"') == 2
        xml = xml.replace('"' + PROGRAM_NAME + '"', '"' + TEST_PROGRAM_NAME + '"')
        channel = '<Channel Name="Generic"'
        assert xml.count(channel) == 1
        start = xml.index(channel)
        end = xml.index('</Channel>', start) + len('</Channel>')
        xml = (xml[:start]
            + CHANNEL_SWITCH_BLOCK
            + '<choose ParamRefId="' + NEW_PROGRAM + '_P-' + CHANNEL_SWITCH + '_R-' + CHANNEL_SWITCH
            + '"><when test="1">'
            + xml[start:end]
            + '</when><when default="true" /></choose>'
            + xml[end:])
        xml = typed_parameters(xml)
        xml = download_data(xml)
    return xml


# The parameter the channel depends on, and the block showing it outside the channel.
CHANNEL_SWITCH = '9010'
CHANNEL_SWITCH_BLOCK = ('<ChannelIndependentBlock><ParameterBlock Id="' + NEW_PROGRAM + '_PB-' + CHANNEL_SWITCH
    + '" Name="Test" Text="Test"><ParameterRefRef RefId="' + NEW_PROGRAM + '_P-' + CHANNEL_SWITCH + '_R-'
    + CHANNEL_SWITCH + '" /></ParameterBlock></ChannelIndependentBlock>\n')


# Parameter types, parameters and references of the kinds the samples lack (IDs in the ETS scheme of
# the program), and where they are placed.
NEW_TYPES = [
    ('Test.5FFloat', 'Test_Float',
        '<TypeFloat Encoding="DPT 9" minInclusive="0" maxInclusive="100" DisplayFormat="0.0" />'),
    ('Test.5FTime', 'Test_Time',
        '<TypeTime SizeInBit="16" Unit="Seconds" minInclusive="0" maxInclusive="3600" />'),
    ('Test.5FPattern', 'Test_Pattern',
        '<TypeText SizeInBit="80" Pattern="[A-ZÄÖÜ][a-zäöüß]+" />'),
    ('Test.5FDate', 'Test_Date', '<TypeDate Encoding="DPT 11" DisplayTheYear="false" />'),
    ('Test.5FIP', 'Test_IP', '<TypeIPAddress AddressType="GatewayAddress" />'),
    ('Test.5FPercent', 'Test_Percent',
        '<TypeNumber SizeInBit="8" Type="unsignedInt" minInclusive="0" maxInclusive="100" />'),
    ('Test.5FSwitch', 'Test_Switch',
        '<TypeRestriction Base="Value" SizeInBit="1">'
        + '<Enumeration Id="' + NEW_PROGRAM + '_PT-Test.5FSwitch_EN-0" DisplayOrder="0" Text="Aus" Value="0" />'
        + '<Enumeration Id="' + NEW_PROGRAM + '_PT-Test.5FSwitch_EN-1" DisplayOrder="1" Text="Ein" Value="1" />'
        + '</TypeRestriction>'),
]
NEW_PARAMETERS = [
    ('9001', 'Test.5FFloat', 'Sollwert', '20.5'),
    ('9002', 'Test.5FTime', 'Verzögerung', '90'),
    ('9003', 'Test.5FPattern', 'Raumname', 'Küche'),
    ('9004', 'Test.5FDate', 'Stichtag', '2025-08-06'),
    ('9005', 'Test.5FIP', 'Gateway', '192.168.1.1'),
    # A value outside the range of its type: kept as text by the import, reported as a warning.
    ('9006', 'Test.5FPercent', 'Helligkeit', '300'),
    # The parameter the channel depends on (placed in a block of its own, see CHANNEL_SWITCH_BLOCK).
    (CHANNEL_SWITCH, 'Test.5FSwitch', 'Kanal Generic', '1'),
]
# The values the device of the project sets: the floating point parameter, and the parameter whose
# value does not fit its type.
NEW_SETTINGS = [('9001', '21.75'), ('9006', '300')]


def typed_parameters(xml):
    prefix = NEW_PROGRAM + '_'
    types = ''.join('<ParameterType Id="' + prefix + 'PT-' + tid + '" Name="' + name + '">'
        + body + '</ParameterType>\n' for tid, name, body in NEW_TYPES)
    parameters = ''.join('<Parameter Id="' + prefix + 'P-' + pid + '" Name="' + text + '" Text="' + text
        + '" ParameterType="' + prefix + 'PT-' + tid + '" Access="ReadWrite" Value="' + value + '" />\n'
        for pid, tid, text, value in NEW_PARAMETERS)
    refs = ''.join('<ParameterRef Id="' + prefix + 'P-' + pid + '_R-' + pid + '" RefId="' + prefix + 'P-'
        + pid + '" Tag="' + pid + '" />\n' for pid, _, _, _ in NEW_PARAMETERS)
    placements = ''.join('<ParameterRefRef RefId="' + prefix + 'P-' + pid + '_R-' + pid + '" />\n'
        for pid, _, _, _ in NEW_PARAMETERS if pid != CHANNEL_SWITCH)
    for end, content in (('</ParameterTypes>', types), ('</Parameters>', parameters),
                         ('</ParameterRefs>', refs)):
        assert xml.count(end) == 1
        xml = xml.replace(end, content + end, 1)
    block = '<ParameterBlock Id="' + prefix + 'PB-3762" '
    assert xml.count(block) == 1
    start = xml.index('>', xml.index(block)) + 1
    return xml[:start] + '\n' + placements + xml[start:]


# A relative segment (ETS ID scheme: _RS-<load state machine>-<offset>) with the bytes 01 02 03 04,
# all of them written to the device.
RELATIVE_SEGMENT = 'RS-04-00000'
RELATIVE_DATA = 'AQIDBA=='
RELATIVE_MASK = '/////w=='


def download_data(xml):
    prefix = NEW_PROGRAM + '_'
    segment = ('<RelativeSegment Id="' + prefix + RELATIVE_SEGMENT + '" Name="Test relative" Offset="0" '
        + 'Size="4" LoadStateMachine="4"><Data>' + RELATIVE_DATA + '</Data><Mask>' + RELATIVE_MASK
        + '</Mask></RelativeSegment>\n')
    end = '</Code>'
    assert xml.count(end) == 1
    xml = xml.replace(end, segment + end, 1)
    parameters = ('<Parameter Id="' + prefix + 'P-9007" Name="Relativ" Text="Relativ" ParameterType="'
        + prefix + 'PT-Test.5FPercent" Access="ReadWrite" Value="5"><Memory CodeSegment="' + prefix
        + RELATIVE_SEGMENT + '" Offset="2" BitOffset="0" /></Parameter>\n'
        + '<Union SizeInBit="8"><Property ObjectIndex="4" PropertyId="51" Offset="1" BitOffset="0" />'
        + '<Parameter Id="' + prefix + 'UP-9008" Name="Property A" Text="Property A" ParameterType="'
        + prefix + 'PT-Test.5FPercent" Offset="0" BitOffset="0" Access="ReadWrite" Value="1" />'
        + '<Parameter Id="' + prefix + 'UP-9009" Name="Property B" Text="Property B" ParameterType="'
        + prefix + 'PT-Test.5FPercent" Offset="0" BitOffset="0" Access="ReadWrite" Value="2" />'
        + '</Union>\n')
    end = '</Parameters>'
    assert xml.count(end) == 1
    return xml.replace(end, parameters + end, 1)


def typed_setting(xml):
    refs = '<ParameterInstanceRefs>'
    assert xml.count(refs) == 1
    settings = ''.join('\n<ParameterInstanceRef RefId="' + NEW_PROGRAM + '_P-' + pid + '_R-' + pid
        + '" Value="' + value + '" />' for pid, value in NEW_SETTINGS)
    return xml.replace(refs, refs + settings, 1)


# TestHiddenValues.knxproj: program values outside their type for hidden and visible parameters (see the
# module comment).
HIDDEN_VERSION = '10D7-11-A0E5-O0083'
HIDDEN_PROGRAM = 'M-0008_A-' + HIDDEN_VERSION
HIDDEN_PROGRAM_NAME = 'TEST: verborgene Parameterwerte (künstliche Testdaten)'
# ID, name, access and value of the parameter, access and value of its reference (None: not given).
HIDDEN_PARAMETERS = [
    ('9101', 'Verborgen', 'None', '7', None, None),
    ('9102', 'Sichtbar', 'ReadWrite', '7', None, None),
    ('9103', 'Verborgen am Ort', 'ReadWrite', '7', 'None', None),
    ('9104', 'Sichtbar am Ort', 'None', '7', 'ReadWrite', None),
    ('9105', 'Verborgene Vorgabe', 'None', '5', None, '7'),
    ('9106', 'Sichtbare Vorgabe', 'ReadWrite', '5', None, '7'),
    ('9107', 'Einstellung', 'ReadWrite', '5', None, None),
]
# The value the device of the project sets.
HIDDEN_SETTINGS = [('9107', '7')]


def hidden_values(name, xml):
    xml = xml.replace(VERSION, HIDDEN_VERSION)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestHiddenValues')
    if name == PROJECT:
        refs = '<ParameterInstanceRefs>'
        assert xml.count(refs) == 1
        settings = ''.join('\n<ParameterInstanceRef RefId="' + HIDDEN_PROGRAM + '_P-' + pid + '_R-' + pid
            + '" Value="' + value + '" />' for pid, value in HIDDEN_SETTINGS)
        return xml.replace(refs, refs + settings, 1)
    if name.endswith(PROGRAM + '.xml'):
        assert xml.count('"' + PROGRAM_NAME + '"') == 2
        xml = xml.replace('"' + PROGRAM_NAME + '"', '"' + HIDDEN_PROGRAM_NAME + '"')
        prefix = HIDDEN_PROGRAM + '_'
        kind = prefix + 'PT-Test.5FKind'
        types = ('<ParameterType Id="' + kind + '" Name="Test_Kind"><TypeRestriction Base="Value" SizeInBit="4">'
            + ''.join('<Enumeration Id="' + kind + '_EN-' + str(v) + '" DisplayOrder="' + str(v - 4)
                + '" Text="Art ' + str(v) + '" Value="' + str(v) + '" />' for v in (4, 5, 6))
            + '</TypeRestriction></ParameterType>\n')
        parameters = ''.join('<Parameter Id="' + prefix + 'P-' + pid + '" Name="' + text + '" Text="' + text
            + '" ParameterType="' + kind + '" Access="' + access + '" Value="' + value + '" />\n'
            for pid, text, access, value, _, _ in HIDDEN_PARAMETERS)
        refs = ''.join('<ParameterRef Id="' + prefix + 'P-' + pid + '_R-' + pid + '" RefId="' + prefix + 'P-'
            + pid + '"' + ('' if ref_access is None else ' Access="' + ref_access + '"')
            + ('' if ref_value is None else ' Value="' + ref_value + '"') + ' Tag="' + pid + '" />\n'
            for pid, _, _, _, ref_access, ref_value in HIDDEN_PARAMETERS)
        placements = ''.join('<ParameterRefRef RefId="' + prefix + 'P-' + pid + '_R-' + pid + '" />\n'
            for pid, _, _, _, _, _ in HIDDEN_PARAMETERS)
        for end, content in (('</ParameterTypes>', types), ('</Parameters>', parameters),
                             ('</ParameterRefs>', refs)):
            assert xml.count(end) == 1
            xml = xml.replace(end, content + end, 1)
        block = '<ParameterBlock Id="' + prefix + 'PB-3762" '
        assert xml.count(block) == 1
        start = xml.index('>', xml.index(block)) + 1
        xml = xml[:start] + '\n' + placements + xml[start:]
    return xml


# TestBcu1.knxproj: two artificial BCU 1 programs (see the module comment).
BCU1_PROGRAMS = [
    ('M-0008_A-FFF1-01-0000', 'FFF1', 65521, 'TEST: BCU1 mit Daten (künstliche Testdaten)', True),
    ('M-0008_A-FFF2-01-0000', 'FFF2', 65522, 'TEST: BCU1 ohne Daten (künstliche Testdaten)', False),
]
BCU1_HARDWARE = 'M-0008_H-TEST.20BCU1-1'
BCU1_PRODUCT = BCU1_HARDWARE + '_P-TEST.20BCU1'


def bcu1_memory():
    """The static data and mask of the segment 0100-01FF."""
    data = bytearray(256)
    mask = bytearray(b'\xff' * 256)
    header = {
        0x00: 0xFF,                    # 0100 option register
        0x04: 0x08, 0x05: 0xFF, 0x06: 0xF1, 0x07: 0x01,  # 0104 manufacturer, device type, version
        0x08: 0xFF,                    # 0108 check limit
        0x0D: 0xFF,                    # 010D run error
        0x0E: 0x61, 0x0F: 0x63, 0x10: 0x2F,
        0x11: 0x25,                    # 0111 pointer to the association table 0125
        0x12: 0x36,                    # 0112 pointer to the object table 0136
        0x16: 0x01,                    # 0116 address table: the individual address only
        0x17: 0xFF, 0x18: 0xFF,        # 0117 the individual address (not written)
        # 0136 object table: 3 objects, RAM flags at 00CE, then value pointer, configuration, type
        0x36: 0x03, 0x37: 0xCE,
        0x38: 0xD0, 0x39: 0xD7, 0x3A: 0x00,
        0x3B: 0xD1, 0x3C: 0xD7, 0x3D: 0x00,
        0x3E: 0xD2, 0x3F: 0xFF, 0x40: 0x07,
        0x51: 0x03,                    # 0151 the low two bits belong to no parameter
        0xFF: 0x5A,                    # 01FF not written by the procedure of the mask
    }
    for offset, value in header.items():
        data[offset] = value
    for offset in (0x01, 0x02, 0x03, 0x17, 0x18):
        mask[offset] = 0
    return base64.b64encode(bytes(data)).decode('ascii'), base64.b64encode(bytes(mask)).decode('ascii')


def bcu1_program(program, number, name, with_data):
    segment = program + '_AS-0100'
    if with_data:
        data, mask = bcu1_memory()
        content = '<Data>' + data + '</Data><Mask>' + mask + '</Mask>'
    else:
        content = ''
    types = (
        '<ParameterType Id="{p}_PT-Betriebsart" Name="Betriebsart"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Betriebsart_EN-0" Text="Aus" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Betriebsart_EN-1" Text="Schalten" Value="1" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Betriebsart_EN-2" Text="Treppenlicht" Value="2" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Stufe" Name="Stufe">'
        '<TypeNumber SizeInBit="4" Type="unsignedInt" minInclusive="0" maxInclusive="15" /></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Verhalten" Name="Verhalten"><TypeRestriction Base="Value" SizeInBit="2">'
        '<Enumeration Id="{p}_PT-Verhalten_EN-0" Text="Unverändert" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Verhalten_EN-1" Text="Aus" Value="1" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Verhalten_EN-2" Text="Ein" Value="2" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Zeit" Name="Zeit">'
        '<TypeNumber SizeInBit="16" Type="unsignedInt" minInclusive="0" maxInclusive="65535" /></ParameterType>\n')
    parameters = [
        # (number, name, type, default, offset, bit offset)
        (1, 'Betriebsart', 'Betriebsart', 1, 0x50, 0),
        (2, 'Verzögerung', 'Stufe', 3, 0x51, 0),
        (3, 'Verhalten', 'Verhalten', 1, 0x51, 4),
        (4, 'Zeit', 'Zeit', 300, 0x52, 0),
    ]
    params = ''.join(
        '<Parameter Id="{p}_P-%d" Name="%s" Text="%s" ParameterType="{p}_PT-%s" Access="ReadWrite" Value="%d">'
        '<Memory CodeSegment="%s" Offset="%d" BitOffset="%d" /></Parameter>\n'
        % (n, text, text, t, value, segment, offset, bit) for n, text, t, value, offset, bit in parameters)
    refs = ''.join('<ParameterRef Id="{p}_P-%d_R-%d" RefId="{p}_P-%d" Tag="%d" />\n' % (n, n, n, n)
        for n, _, _, _, _, _ in parameters)
    objects = [
        # (number, name, size, communication, read, write, transmit, update)
        (0, 'Schalten', '1 Bit', 'Enabled', 'Disabled', 'Enabled', 'Disabled', 'Disabled'),
        (1, 'Rückmeldung', '1 Bit', 'Enabled', 'Enabled', 'Disabled', 'Enabled', 'Disabled'),
        (2, 'Wert', '1 Byte', 'Enabled', 'Enabled', 'Enabled', 'Enabled', 'Disabled'),
    ]
    comobjects = ''.join(
        '<ComObject Id="{p}_O-%d" Name="%s" Text="%s" Number="%d" ObjectSize="%s" Priority="Low" '
        'CommunicationFlag="%s" ReadFlag="%s" WriteFlag="%s" TransmitFlag="%s" UpdateFlag="%s" '
        'ReadOnInitFlag="Disabled" />\n' % (o[0], o[1], o[1], o[0], o[2], o[3], o[4], o[5], o[6], o[7])
        for o in objects)
    corefs = ''.join('<ComObjectRef Id="{p}_O-%d_R-%d" RefId="{p}_O-%d" Tag="%d" />\n' % (o[0], o[0] + 1, o[0], o[0] + 1)
        for o in objects)
    placed = (''.join('<ParameterRefRef RefId="{p}_P-%d_R-%d" />\n' % (n, n) for n, _, _, _, _, _ in parameters)
        + ''.join('<ComObjectRefRef RefId="{p}_O-%d_R-%d" />\n' % (o[0], o[0] + 1) for o in objects))
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/11" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + name + '" ApplicationNumber="' + str(number) + '" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="4.0" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="' + segment + '" Address="256" Size="256" UserMemory="0">'
        + content + '</AbsoluteSegment></Code>\n'
        '<ParameterTypes>\n' + types + '</ParameterTypes>\n'
        '<Parameters>\n' + params + '</Parameters>\n'
        '<ParameterRefs>\n' + refs + '</ParameterRefs>\n'
        '<ComObjectTable CodeSegment="' + segment + '" Offset="54">\n' + comobjects + '</ComObjectTable>\n'
        '<ComObjectRefs>\n' + corefs + '</ComObjectRefs>\n'
        '<AddressTable CodeSegment="' + segment + '" Offset="22" MaxEntries="6" />\n'
        '<AssociationTable CodeSegment="' + segment + '" Offset="37" MaxEntries="8" />\n'
        '<Options ParameterByteOrder="BigEndian" />\n'
        '</Static>\n'
        '<Dynamic><Channel Name="Kanal A" Text="Kanal A" Number="1" Id="{p}_CH-1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">\n' + placed
        + '</ParameterBlock></Channel></Dynamic>\n'
        '</ApplicationProgram>\n</ApplicationPrograms></Manufacturer></ManufacturerData>\n</KNX>\n')
    return xml.replace('{p}', program)


# The programs of TestLanguagesA/B: (ID, application number, German name, English name).
LANGUAGE_PROGRAMS = [
    ('M-0008_A-FFF3-01-0000', 65523, 'TEST: Sprachen 1 (künstliche Testdaten)', 'TEST: languages 1 (artificial test data)'),
    ('M-0008_A-FFF4-01-0000', 65524, 'TEST: Sprachen 2 (künstliche Testdaten)', 'TEST: languages 2 (artificial test data)'),
]

# The English texts of the BCU 1 program, by its German texts.
ENGLISH_TEXTS = {'Aus': 'Off', 'Schalten': 'Switching', 'Treppenlicht': 'Staircase lighting', 'Unverändert': 'Unchanged',
    'Ein': 'On', 'Betriebsart': 'Operating mode', 'Verzögerung': 'Delay', 'Verhalten': 'Behaviour', 'Zeit': 'Time',
    'Rückmeldung': 'Feedback', 'Wert': 'Value', 'Kanal A': 'Channel A', 'Allgemein': 'General'}

# The en-US translations of the English file that differ from its texts.
ENGLISH_TRANSLATIONS = {'Delay': 'Switch-on delay'}


def language_program(program, number, german, english, in_english):
    """A BCU 1 program written in German or in English, with its Languages section."""
    xml = bcu1_program(program, number, german, False)
    texts = []
    for match in re.finditer(r'<(\w+) ([^>]*?)/?>', xml):
        attributes = dict(re.findall(r'(\w+)="([^"]*)"', match.group(2)))
        if 'Id' in attributes:
            for name in ('Name', 'Text') if match.group(1) == 'ApplicationProgram' else ('Text',):
                if name in attributes:
                    texts.append((attributes['Id'], name, attributes[name]))
    english_of = dict(ENGLISH_TEXTS, **{german: english})

    def translations(identifier, text_of):
        return ('<Language Identifier="' + identifier + '"><TranslationUnit RefId="' + program + '" Version="0">'
            + ''.join('<TranslationElement RefId="' + ident + '"><Translation AttributeName="' + name + '" Text="'
                + text_of(text) + '" /></TranslationElement>' for ident, name, text in texts)
            + '</TranslationUnit></Language>')

    if in_english:
        xml = re.sub(r' Text="([^"]*)"', lambda m: ' Text="' + english_of[m.group(1)] + '"', xml)
        xml = replace_once(xml, 'Name="' + german + '"', 'Name="' + english + '"')
        xml = replace_once(xml, 'DefaultLanguage="de-DE"', 'DefaultLanguage="en-US"')
        languages = (translations('de-DE', lambda text: text)
            + translations('en-US', lambda text: ENGLISH_TRANSLATIONS.get(english_of[text], english_of[text])))
    else:
        languages = translations('de-DE', lambda text: text)
    return replace_once(xml, '</ApplicationPrograms>', '</ApplicationPrograms><Languages>' + languages + '</Languages>')


def languages_fixture(target, english):
    """TestLanguagesA/B: the master data and the language programs, those named in English."""
    write(target, unchanged, [('M-0008/' + program + '.xml', language_program(program, number, german, en, program in english))
        for program, number, german, en in LANGUAGE_PROGRAMS], keep=lambda name: name == 'knx_master.xml')


def bcu1_hardware(xml):
    # Before the end of the list of the manufacturer's hardware, which the languages follow.
    assert xml.count('<Languages>') == 1
    end = xml.rindex('</Hardware>', 0, xml.index('<Languages>'))
    programs = ''.join('<Hardware2Program Id="' + BCU1_HARDWARE + '_HP-' + number + '-01-0000" MediumTypes="MT-0">'
        '<ApplicationProgramRef RefId="' + program + '" /></Hardware2Program>\n'
        for program, number, _, _, _ in BCU1_PROGRAMS)
    hardware = ('<Hardware Id="' + BCU1_HARDWARE + '" Name="TEST BCU1" SerialNumber="TEST BCU1" VersionNumber="1" '
        'HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="' + BCU1_PRODUCT + '" '
        'Text="TEST BCU1 (künstliche Testdaten)" OrderNumber="TEST BCU1" IsRailMounted="1" DefaultLanguage="de-DE" />'
        '</Products><Hardware2Programs>\n' + programs + '</Hardware2Programs></Hardware>\n')
    return xml[:end] + hardware + xml[end:]


def bcu1_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    devices = ''
    for n, (program, number, _, _, with_data) in enumerate(BCU1_PROGRAMS, 1):
        devices += ('<DeviceInstance Id="P-01A0-0_DI-' + str(n) + '" Name="TEST BCU1 ' + ('mit' if with_data else 'ohne')
            + ' Daten" ProductRefId="' + BCU1_PRODUCT + '" Hardware2ProgramRefId="' + BCU1_HARDWARE + '_HP-' + number
            + '-01-0000" Address="' + str(n) + '" LastModified="2026-10-11T12:00:00">\n'
            '<ParameterInstanceRefs>\n'
            '<ParameterInstanceRef RefId="' + program + '_P-1_R-1" Value="2" />\n'
            '<ParameterInstanceRef RefId="' + program + '_P-3_R-3" Value="2" />\n'
            '</ParameterInstanceRefs>\n<ComObjectInstanceRefs>\n'
            '<ComObjectInstanceRef RefId="' + program + '_O-0_R-1" IsActive="1"><Connectors>'
            '<Send GroupAddressRefId="P-01A0-0_GA-3" /><Receive GroupAddressRefId="P-01A0-0_GA-1" />'
            '</Connectors></ComObjectInstanceRef>\n'
            '<ComObjectInstanceRef RefId="' + program + '_O-1_R-2" IsActive="1"><Connectors>'
            '<Send GroupAddressRefId="P-01A0-0_GA-4" /></Connectors></ComObjectInstanceRef>\n'
            '<ComObjectInstanceRef RefId="' + program + '_O-2_R-3" IsActive="1" />\n'
            '</ComObjectInstanceRefs>\n</DeviceInstance>\n')
    return xml[:start] + devices + xml[end:]


def bcu1(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestBcu1')
    if name == PROJECT:
        return bcu1_devices(xml)
    if name == 'M-0008/Hardware.xml':
        return bcu1_hardware(xml)
    return xml


def naming(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestNaming')
    if name != PROJECT:
        return xml
    xml = details(xml)

    def strip(match):
        element = match.group(0)
        old = re.search(r' Name="([^"]*)"', element).group(1)
        element = element.replace(' Name="' + old + '"', ' Name=""', 1)
        if ' Description="' not in element:
            element = element.replace('<GroupAddress ', '<GroupAddress Description="' + old + '" ', 1)
        return element

    result, count = re.subn(r'<GroupAddress [^>]*>', strip, xml)
    assert count > 0
    return result


def keep_details(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestDetails')
    return details(xml) if name == PROJECT else xml


# TestFunctionKeys.knxproj: an actuator and a push-button sensor of artificial programs (see the module
# comment). An object: (name, size, write flag, transmit flag, datapoint type or None, group address).
W, T, WT = (True, False), (False, True), (True, True)
FUNCTION_PROGRAMS = [
    ('M-0008_A-FFF5-01-0000', 'FFF5', 65525, 'TEST: Aktor (künstliche Testdaten)', [
        ('Kanal A', [('Schalten', '1 Bit', W, 'DPST-1-1', '1/1/1'), ('Status', '1 Bit', T, 'DPST-1-11', '1/1/8')]),
        ('Kanal B', [('Schalten', '1 Bit', W, 'DPST-1-1', '1/1/2'), ('Dimmen', '4 Bit', W, 'DPST-3-7', '1/1/3'),
            ('Helligkeitswert', '1 Byte', W, 'DPST-5-1', '1/1/4'),
            ('Status Helligkeitswert', '1 Byte', T, 'DPST-5-1', '1/1/5')]),
        ('Kanal C', [('Fahren', '1 Bit', W, 'DPST-1-8', '2/1/1'),
            ('Stopp/Lamellenverstellung', '1 Bit', W, 'DPST-1-7', '2/1/2'),
            ('Position anfahren', '1 Byte', W, 'DPST-5-1', '2/1/3'), ('Status Position', '1 Byte', T, 'DPST-5-1', '2/1/4'),
            ('Lamellenposition', '1 Byte', W, 'DPST-5-1', '2/1/5'),
            ('Status Lamellenposition', '1 Byte', T, 'DPST-5-1', '2/1/6')]),
        ('Zentral', [('Schalten Zentral', '1 Bit', W, 'DPST-1-1', '1/0/0')]),
    ]),
    ('M-0008_A-FFF6-01-0000', 'FFF6', 65526, 'TEST: Taster (künstliche Testdaten)', [
        ('Taste 1', [('Schalten', '1 Bit', WT, None, '1/1/1')]),
        ('Taste 2', [('Schalten', '1 Bit', WT, None, '1/1/2'), ('Dimmen', '4 Bit', WT, 'DPST-3-7', '1/1/3')]),
        ('Wippe 3', [('Langzeit', '1 Bit', WT, 'DPST-1-8', '2/1/1'), ('Kurzzeit', '1 Bit', WT, 'DPST-1-7', '2/1/2')]),
        ('Taste 4', [('Schalten', '1 Bit', WT, None, '1/0/0')]),
        ('Taste 5', [('Schalten', '1 Bit', WT, None, '1/1/7')]),
        ('Status-LED', [('Status-LED', '1 Bit', W, None, '1/1/7')]),
        ('Temperatur', [('Raumtemperatur', '2 Bytes', T, 'DPST-9-1', None)]),
    ]),
]
FUNCTION_HARDWARE = 'M-0008_H-TEST.20FUNKTION-1'
# The names of the group addresses, by address.
FUNCTION_ADDRESSES = {'1/0/0': 'Zentral', '1/1/1': 'Licht', '1/1/2': 'Dimmer', '1/1/3': 'Dimmer relativ',
    '1/1/4': 'Dimmer Wert', '1/1/5': 'Dimmer Wert Status', '1/1/7': 'Melder', '1/1/8': 'Licht Status',
    '2/1/1': 'Jalousie auf/ab', '2/1/2': 'Jalousie Stopp', '2/1/3': 'Jalousie Position',
    '2/1/4': 'Jalousie Position Status', '2/1/5': 'Jalousie Lamelle', '2/1/6': 'Jalousie Lamelle Status',
    '3/1/1': 'Temperatur'}
# The devices of TestFunctionKeys: (program, name, building part or None, the group address of each
# connected object by its number, None for those the program gives).
FUNCTION_KEY_DEVICES = [(0, 'TEST Aktor', None, None), (1, 'TEST Taster', None, None)]
# The group addresses of TestFunctionLinks (haumacher/ehome #98), by address.
LINK_ADDRESSES = {'4/0/1': 'Licht Küche', '4/0/2': 'Licht Küche Status', '4/0/3': 'Licht Flur', '4/0/4': 'Licht Bad',
    '4/0/8': 'Klingel', '4/0/9': 'Licht Zentral', '4/1/1': 'Raumtemperatur Küche', '4/2/1': 'Markise fahren'}
# The devices of TestFunctionLinks, see the module comment: the addresses of an object, the one it sends on
# first.
LINK_DEVICES = [
    (0, 'TEST Aktor Küche', None, {0: ['4/0/1', '4/0/9'], 1: '4/0/2', 2: ['4/0/3', '4/0/9'], 12: '4/0/9'}),
    (0, 'TEST Aktor Flur', None, {2: '4/0/3'}),
    (1, 'TEST Taster Küche', None, {0: ['4/0/1', '4/0/2'], 5: '4/0/9', 6: '4/0/8', 8: '4/1/1'}),
]
# The devices of TestFunctionRooms (haumacher/ehome #93), see the module comment.
FUNCTION_ROOM_DEVICES = [
    (0, 'TEST Aktor', 'P-01A0-0_BP-4', None),
    (1, 'TEST Taster Küche 1', 'P-01A0-0_BP-3', {1: '1/1/2', 3: '2/1/1'}),
    (1, 'TEST Taster Küche 2', 'P-01A0-0_BP-3', {2: '1/1/3', 8: '3/1/1'}),
    (1, 'TEST Taster Wohnzimmer', 'P-01A0-0_BP-5', {0: '1/1/1', 2: '1/1/3', 4: '2/1/2'}),
]


def ga_value(text):
    main, middle, sub = (int(part) for part in text.split('/'))
    return (main << 11) | (middle << 8) | sub


def function_objects(channels):
    """The objects of the channels, numbered from 0, each with its channel number."""
    result = []
    for number, (_, objects) in enumerate(channels, 1):
        for obj in objects:
            result.append((len(result), number) + obj)
    return result


def function_program(program, number, name, channels, described=()):
    """An artificial program of channels of objects; each channel whose number is in described has the text
    "<name>: {{0}}" filled from a text parameter "Beschreibung <name>" of its own (TextParameterRefId), empty by
    default."""
    segment = program + '_AS-0100'
    objects = function_objects(channels)
    comobjects = ''.join(
        '<ComObject Id="{p}_O-%d" Name="%s" Text="%s" FunctionText="%s" Number="%d" ObjectSize="%s" Priority="Low" '
        'CommunicationFlag="Enabled" ReadFlag="%s" WriteFlag="%s" TransmitFlag="%s" UpdateFlag="Disabled" '
        'ReadOnInitFlag="Disabled"%s />\n' % (n, channels[c - 1][0], channels[c - 1][0], text, n, size,
            'Enabled' if flags[1] else 'Disabled', 'Enabled' if flags[0] else 'Disabled',
            'Enabled' if flags[1] else 'Disabled', '' if dpt is None else ' DatapointType="' + dpt + '"')
        for n, c, text, size, flags, dpt, _ in objects)
    corefs = ''.join('<ComObjectRef Id="{p}_O-%d_R-%d" RefId="{p}_O-%d" />\n' % (o[0], o[0] + 1, o[0]) for o in objects)
    dynamic = ''.join(('<Channel Name="%s" Text="%s: {{0}}" TextParameterRefId="{p}_P-%d_R-1" Number="%d" Id="{p}_CH-%d">'
        % (label, label, c, c, c) if c in described else
        '<Channel Name="%s" Text="%s" Number="%d" Id="{p}_CH-%d">' % (label, label, c, c))
        + '<ParameterBlock Id="{p}_PB-%d" Name="Objekte" Text="Objekte">' % c
        + ''.join('<ParameterRefRef RefId="{p}_P-%d_R-1" />' % c for _ in [0] if c in described)
        + ''.join('<ComObjectRefRef RefId="{p}_O-%d_R-%d" />' % (o[0], o[0] + 1) for o in objects if o[1] == c)
        + '</ParameterBlock></Channel>\n' for c, (label, _) in enumerate(channels, 1))
    parameters = '' if not described else ('<ParameterTypes>\n<ParameterType Id="{p}_PT-Text" Name="Text">'
        '<TypeText SizeInBit="160" /></ParameterType>\n</ParameterTypes>\n<Parameters>\n'
        + ''.join('<Parameter Id="{p}_P-%d" Name="Beschreibung%d" Text="Beschreibung %s" ParameterType="{p}_PT-Text" '
            'Access="ReadWrite" Value="" />\n' % (c, c, channels[c - 1][0]) for c in sorted(described))
        + '</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_P-%d_R-1" RefId="{p}_P-%d" />\n' % (c, c) for c in sorted(described))
        + '</ParameterRefs>\n')
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/11" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + name + '" ApplicationNumber="' + str(number) + '" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="4.0" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="' + segment + '" Address="256" Size="256" UserMemory="0" /></Code>\n'
        + parameters + '<ComObjectTable CodeSegment="' + segment + '" Offset="54">\n' + comobjects + '</ComObjectTable>\n'
        '<ComObjectRefs>\n' + corefs + '</ComObjectRefs>\n'
        '<AddressTable CodeSegment="' + segment + '" Offset="22" MaxEntries="20" />\n'
        '<AssociationTable CodeSegment="' + segment + '" Offset="80" MaxEntries="20" />\n'
        '<Options ParameterByteOrder="BigEndian" />\n'
        '</Static>\n'
        '<Dynamic>' + dynamic + '</Dynamic>\n'
        '</ApplicationProgram>\n</ApplicationPrograms></Manufacturer></ManufacturerData>\n</KNX>\n')
    return xml.replace('{p}', program)


def function_hardware(xml):
    end = xml.rindex('</Hardware>', 0, xml.index('<Languages>'))
    hardware = ''
    for program, number, _, name, _ in FUNCTION_PROGRAMS:
        short = name.split(' ')[1]
        hardware += ('<Hardware Id="' + FUNCTION_HARDWARE + number + '" Name="TEST ' + short + '" SerialNumber="TEST '
            + number + '" VersionNumber="1" HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="'
            + FUNCTION_HARDWARE + number + '_P-TEST.20' + number + '" Text="TEST ' + short
            + ' (künstliche Testdaten)" OrderNumber="TEST ' + number + '" IsRailMounted="1" DefaultLanguage="de-DE" />'
            '</Products><Hardware2Programs><Hardware2Program Id="' + FUNCTION_HARDWARE + number + '_HP-' + number
            + '-01-0000" MediumTypes="MT-0"><ApplicationProgramRef RefId="' + program + '" /></Hardware2Program>'
            '</Hardware2Programs></Hardware>\n')
    return xml[:end] + hardware + xml[end:]


def function_connectors(addresses, connected):
    """The connectors of an object: the first address it sends on, each further one it receives from."""
    if isinstance(connected, str):
        connected = [connected]
    return ''.join('<%s GroupAddressRefId="P-01A0-0_GA-%d" />' % ('Send' if n == 0 else 'Receive',
        addresses.index(address) + 1) for n, address in enumerate(connected))


def function_devices(xml, specs, names=None):
    names = FUNCTION_ADDRESSES if names is None else names
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    addresses = sorted(names, key=ga_value)
    devices = ''
    for n, (index, device_name, _, connected) in enumerate(specs, 1):
        program, number, _, _, channels = FUNCTION_PROGRAMS[index]
        objects = [(o[0], o[6] if connected is None else connected.get(o[0])) for o in function_objects(channels)]
        refs = ''.join('<ComObjectInstanceRef RefId="' + program + '_O-%d_R-%d" IsActive="1"><Connectors>'
            % (o, o + 1) + function_connectors(addresses, address) + '</Connectors></ComObjectInstanceRef>\n'
            for o, address in objects if address is not None)
        devices += ('<DeviceInstance Id="P-01A0-0_DI-' + str(n) + '" Name="' + device_name
            + '" ProductRefId="' + FUNCTION_HARDWARE + number + '_P-TEST.20' + number + '" Hardware2ProgramRefId="'
            + FUNCTION_HARDWARE + number + '_HP-' + number + '-01-0000" Address="' + str(n)
            + '" LastModified="2026-11-07T12:00:00">\n<ComObjectInstanceRefs>\n' + refs
            + '</ComObjectInstanceRefs>\n</DeviceInstance>\n')
    xml = xml[:start] + devices + xml[end:]
    start = xml.index('<GroupRanges>')
    end = xml.index('</GroupRanges>') + len('</GroupRanges>')
    ranges = ''
    for main in sorted(set(int(a.split('/')[0]) for a in addresses)):
        ranges += ('<GroupRange Id="P-01A0-0_GR-%d" Name="HG%d" RangeStart="%d" RangeEnd="%d">'
            % (main, main, main << 11, (main << 11) + 2047)
            + ''.join('<GroupAddress Id="P-01A0-0_GA-%d" Address="%d" Name="%s" />'
                % (addresses.index(a) + 1, ga_value(a), names[a])
                for a in addresses if a.startswith(str(main) + '/'))
            + '</GroupRange>\n')
    xml = xml[:start] + '<GroupRanges>\n' + ranges + '</GroupRanges>' + xml[end:]
    if any(part for _, _, part, _ in specs):
        def refs(part):
            return ''.join('<DeviceInstanceRef RefId="P-01A0-0_DI-%d" />' % n
                for n, (_, _, placed, _) in enumerate(specs, 1) if placed == part)
        # The building of TestDetails: the room "Wohnzimmer" first (with the highest ID), then "Küche" and
        # the distribution board "Verteiler".
        xml = replace_once(xml, '<Buildings />', '<Buildings>\n'
            '<BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">'
            '<BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">'
            '<BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room">' + refs('P-01A0-0_BP-5') + '</BuildingPart>'
            '<BuildingPart Id="P-01A0-0_BP-3" Name="Küche" Type="Room">' + refs('P-01A0-0_BP-3') + '</BuildingPart>'
            '<BuildingPart Id="P-01A0-0_BP-4" Name="Verteiler" Type="DistributionBoard">' + refs('P-01A0-0_BP-4')
            + '</BuildingPart></BuildingPart></BuildingPart>\n</Buildings>')
    return xml


def function_fixture(project, specs, names=None):
    def modify(name, xml):
        if name == PROJECT_INFO:
            return rename_project(xml, project)
        if name == PROJECT:
            return function_devices(xml, specs, names)
        if name == 'M-0008/Hardware.xml':
            return function_hardware(xml)
        return xml
    return modify


# TestFunctionWiring.knxproj: the wiring of the function proposal (haumacher/ehome #133, #134, #135), with three
# artificial programs of their own (M-0008_A-FFD1/FFD2/FFD3, marked as test data) and the building "Haus",
# "Erdgeschoss" holding the room "Flur" with the distribution board "Schaltschrank" in it, then the rooms
# "Wohnzimmer", "Werkstatt" and "Küche". "TEST Aktor Verteiler" (1.0.1, in the board): "Kanal A" a light on
# 5/0/1 "Licht Werkstatt" (status 5/0/2), "Kanal B" a light whose status object sends on its switching address
# 5/0/3, "Kanal C" a blind whose up/down (1.008) and slat/stop (1.007) objects are on addresses typed 1.001
# ("Jalousie Küche"), "Kanal D" a blind whose stop is a 1.008 object "Kurzzeitbetrieb/Stop" on an address typed
# 1.009, "Kanal E" a dimmer (switching 5/2/1 "Licht Esstisch", 3.007, value 5/2/3 "Licht Esstisch Dimmwert",
# its status), "Kanal F" connected to nothing. "TEST Heizungsaktor" (1.0.2, in the board): "Kanal A" receives
# the temperature 5/3/1 "Temperatur Wohnzimmer" with an object whose transmit flag is set (as the MDT
# heating actuator's), "Kanal B" connected to nothing. Five push-button sensors: three named "TEST Taster"
# (1.0.3 in "Wohnzimmer": Taste 1 on 5/2/1, Wippe 3 on 5/1/1 and 5/1/2, the temperature on 5/3/1; 1.0.4 in
# "Werkstatt" and 1.0.5 in "Flur": Taste 1 on 5/0/1), one without a name (1.0.6) and one with a name too long
# for a function (1.0.7), both in "Küche" and connected to nothing. The program of the keys has two channels
# with the same name "Taste".
WIRING_PROGRAMS = [
    ('M-0008_A-FFD1-01-0000', 'FFD1', 65489, 'TEST: Aktor Verdrahtung (künstliche Testdaten)', [
        ('Kanal A', [('Schalten', '1 Bit', W, 'DPST-1-1', '5/0/1'), ('Status', '1 Bit', T, 'DPST-1-11', '5/0/2')]),
        ('Kanal B', [('Schalten', '1 Bit', W, 'DPST-1-1', '5/0/3'), ('Status', '1 Bit', T, 'DPST-1-11', '5/0/3')]),
        ('Kanal C', [('Jalousie Auf/Ab', '1 Bit', W, 'DPST-1-8', '5/1/1'),
            ('Lamellenverstellung / Stopp', '1 Bit', W, 'DPST-1-7', '5/1/2')]),
        ('Kanal D', [('Rollladen Auf/Ab', '1 Bit', W, 'DPST-1-8', '5/1/3'),
            ('Kurzzeitbetrieb/Stop', '1 Bit', W, 'DPST-1-8', '5/1/4'),
            ('Position anfahren', '1 Byte', W, 'DPST-5-1', '5/1/5'), ('Status Position', '1 Byte', T, 'DPST-5-1', '5/1/6')]),
        ('Kanal E', [('Schalten', '1 Bit', W, 'DPST-1-1', '5/2/1'), ('Dimmen', '4 Bit', W, 'DPST-3-7', '5/2/2'),
            ('Dimmwert', '1 Byte', W, 'DPST-5-1', '5/2/3'), ('Status Dimmwert', '1 Byte', T, 'DPST-5-1', '5/2/4')]),
        ('Kanal F', [('Schalten', '1 Bit', W, 'DPST-1-1', None), ('Status', '1 Bit', T, 'DPST-1-11', None)]),
    ]),
    ('M-0008_A-FFD2-01-0000', 'FFD2', 65490, 'TEST: Heizungsaktor (künstliche Testdaten)', [
        ('Kanal A', [('Isttemperatur empfangen', '2 Bytes', WT, 'DPST-9-1', '5/3/1')]),
        ('Kanal B', [('Isttemperatur empfangen', '2 Bytes', WT, 'DPST-9-1', None)]),
    ]),
    ('M-0008_A-FFD3-01-0000', 'FFD3', 65491, 'TEST: Taster Verdrahtung (künstliche Testdaten)', [
        ('Taste 1', [('Schalten', '1 Bit', WT, None, None)]),
        ('Taste 2', [('Schalten', '1 Bit', WT, None, None)]),
        ('Wippe 3', [('Langzeit', '1 Bit', WT, 'DPST-1-8', None), ('Kurzzeit', '1 Bit', WT, 'DPST-1-7', None)]),
        ('Taste', [('Schalten', '1 Bit', WT, None, None)]),
        ('Taste', [('Schalten', '1 Bit', WT, None, None)]),
        ('Temperatur', [('Raumtemperatur', '2 Bytes', T, 'DPST-9-1', None)]),
    ]),
    # A channel whose name leaves no room for the device part (as the heating actuator of the ETS6 demo):
    # the individual address is never cut, nor given an ellipsis.
    ('M-0008_A-FFD4-01-0000', 'FFD4', 65492, 'TEST: Langer Kanal (künstliche Testdaten)', [
        ('Taste mit einem außergewöhnlich langen Namen für die Prüfung', [('Schalten', '1 Bit', WT, None, None)]),
        ('Taste kurz', [('Schalten', '1 Bit', WT, None, None)]),
    ]),
]
WIRING_HARDWARE = 'M-0008_H-TEST.20WIRING-1'
# The group addresses of TestFunctionWiring: name and datapoint type by address.
WIRING_ADDRESSES = {'5/0/1': ('Licht Werkstatt', 'DPST-1-1'), '5/0/2': ('Licht Werkstatt Status', 'DPST-1-11'),
    '5/0/3': ('Licht Flur', 'DPST-1-1'), '5/1/1': ('Jalousie Küche', 'DPST-1-1'),
    '5/1/2': ('Jalousie Küche Stopp', 'DPST-1-1'), '5/1/3': ('Rollladen Bad', 'DPST-1-8'),
    '5/1/4': ('Rollladen Bad Stopp', 'DPST-1-9'), '5/1/5': ('Rollladen Bad Position', 'DPST-5-1'),
    '5/1/6': ('Rollladen Bad Position Status', 'DPST-5-1'), '5/2/1': ('Licht Esstisch', 'DPST-1-1'),
    '5/2/2': ('Licht Esstisch dimmen', 'DPST-3-7'), '5/2/3': ('Licht Esstisch Dimmwert', 'DPST-5-1'),
    '5/2/4': ('Licht Esstisch Dimmwert Status', 'DPST-5-1'), '5/3/1': ('Temperatur Wohnzimmer', 'DPST-9-1')}
# The devices of TestFunctionWiring: (program, name or None, building part, the group address of each connected
# object by its number, None for those the program gives).
WIRING_DEVICES = [
    (0, 'TEST Aktor Verteiler', 'P-01A0-0_BP-4', None),
    (1, 'TEST Heizungsaktor', 'P-01A0-0_BP-4', None),
    (2, 'TEST Taster', 'P-01A0-0_BP-5', {0: '5/2/1', 2: '5/1/1', 3: '5/1/2', 6: '5/3/1'}),
    (2, 'TEST Taster', 'P-01A0-0_BP-6', {0: '5/0/1'}),
    (2, 'TEST Taster', 'P-01A0-0_BP-3', {0: '5/0/1'}),
    (2, None, 'P-01A0-0_BP-7', {}),
    (2, 'TEST Taster mit einem sehr langen Namen, länger als jede Funktion', 'P-01A0-0_BP-7', {}),
    (3, None, 'P-01A0-0_BP-7', {}),
    (3, 'TEST Langkanal', 'P-01A0-0_BP-7', {}),
    (3, 'TEST Langkanal', 'P-01A0-0_BP-7', {}),
]


def wiring_hardware(xml):
    end = xml.rindex('</Hardware>', 0, xml.index('<Languages>'))
    hardware = ''
    for program, number, _, name, _ in WIRING_PROGRAMS:
        short = name.split(' (')[0].split(': ')[1]
        hardware += ('<Hardware Id="' + WIRING_HARDWARE + number + '" Name="TEST ' + short + '" SerialNumber="TEST '
            + number + '" VersionNumber="1" HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="'
            + WIRING_HARDWARE + number + '_P-TEST.20' + number + '" Text="TEST ' + short
            + ' (künstliche Testdaten)" OrderNumber="TEST ' + number + '" IsRailMounted="1" DefaultLanguage="de-DE" />'
            '</Products><Hardware2Programs><Hardware2Program Id="' + WIRING_HARDWARE + number + '_HP-' + number
            + '-01-0000" MediumTypes="MT-0"><ApplicationProgramRef RefId="' + program + '" /></Hardware2Program>'
            '</Hardware2Programs></Hardware>\n')
    return xml[:end] + hardware + xml[end:]


def wiring_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    addresses = sorted(WIRING_ADDRESSES, key=ga_value)
    devices = ''
    for n, (index, device_name, _, connected) in enumerate(WIRING_DEVICES, 1):
        program, number, _, _, channels = WIRING_PROGRAMS[index]
        objects = [(o[0], o[6] if connected is None else connected.get(o[0])) for o in function_objects(channels)]
        refs = ''.join('<ComObjectInstanceRef RefId="' + program + '_O-%d_R-%d" IsActive="1"><Connectors>'
            % (o, o + 1) + function_connectors(addresses, address) + '</Connectors></ComObjectInstanceRef>\n'
            for o, address in objects if address is not None)
        devices += ('<DeviceInstance Id="P-01A0-0_DI-' + str(n) + '"'
            + ('' if device_name is None else ' Name="' + device_name + '"')
            + ' ProductRefId="' + WIRING_HARDWARE + number + '_P-TEST.20' + number + '" Hardware2ProgramRefId="'
            + WIRING_HARDWARE + number + '_HP-' + number + '-01-0000" Address="' + str(n)
            + '" LastModified="2026-11-11T12:00:00">\n<ComObjectInstanceRefs>\n' + refs
            + '</ComObjectInstanceRefs>\n</DeviceInstance>\n')
    xml = xml[:start] + devices + xml[end:]
    start = xml.index('<GroupRanges>')
    end = xml.index('</GroupRanges>') + len('</GroupRanges>')
    ranges = ''
    for main in sorted(set(int(a.split('/')[0]) for a in addresses)):
        ranges += ('<GroupRange Id="P-01A0-0_GR-%d" Name="HG%d" RangeStart="%d" RangeEnd="%d">'
            % (main, main, main << 11, (main << 11) + 2047)
            + ''.join('<GroupAddress Id="P-01A0-0_GA-%d" Address="%d" Name="%s" DatapointType="%s" />'
                % (addresses.index(a) + 1, ga_value(a), WIRING_ADDRESSES[a][0], WIRING_ADDRESSES[a][1])
                for a in addresses if a.startswith(str(main) + '/'))
            + '</GroupRange>\n')
    xml = xml[:start] + '<GroupRanges>\n' + ranges + '</GroupRanges>' + xml[end:]

    def refs(part):
        return ''.join('<DeviceInstanceRef RefId="P-01A0-0_DI-%d" />' % n
            for n, (_, _, placed, _) in enumerate(WIRING_DEVICES, 1) if placed == part)
    return replace_once(xml, '<Buildings />', '<Buildings>\n'
        '<BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">'
        '<BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">'
        '<BuildingPart Id="P-01A0-0_BP-3" Name="Flur" Type="Room">' + refs('P-01A0-0_BP-3')
        + '<BuildingPart Id="P-01A0-0_BP-4" Name="Schaltschrank" Type="DistributionBoard">' + refs('P-01A0-0_BP-4')
        + '</BuildingPart></BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room">' + refs('P-01A0-0_BP-5') + '</BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-6" Name="Werkstatt" Type="Room">' + refs('P-01A0-0_BP-6') + '</BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-7" Name="Küche" Type="Room">' + refs('P-01A0-0_BP-7') + '</BuildingPart>'
        '</BuildingPart></BuildingPart>\n</Buildings>')


def wiring_fixture(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestFunctionWiring')
    if name == PROJECT:
        return wiring_devices(xml)
    if name == 'M-0008/Hardware.xml':
        return wiring_hardware(xml)
    return xml


# Renames of the product data in TestNewProduct (applied to the entry names as well).
NEW_PRODUCT_IDS = [
    (VERSION, '10D7-11-A0E5-O00F0'),
    ('H-8.20.2F.2F.20zxy3.20xx-1', 'H-8.20.2F.2F.20TEST-1'),
    ('M-0008_CS-PB', 'M-0008_CS-TEST'),
]
NEW_PRODUCT_NAMES = [
    ('"' + PROGRAM_NAME + '"', '"TEST: Produktdaten 10D711 (künstliche Testdaten)"'),
    ('"Tastsensor 3 Komfort 3fach"', '"TEST: Produktdaten (künstliche Testdaten)"'),
    ('"Taster"', '"TEST: Taster"'),
    ('"Taster, 3fach"', '"TEST: Taster, 3fach"'),
]


def new_product_name(name):
    for old, new in NEW_PRODUCT_IDS:
        name = name.replace(old, new)
    return name


def new_product(name, xml):
    xml = new_product_name(xml)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestNewProduct')
    if name.startswith('M-0008/'):
        for old, new in NEW_PRODUCT_NAMES:
            xml = xml.replace(old, new)
    return xml


def is_product_data(name):
    """Whether an entry of a project file belongs to the product data: everything but the project."""
    return not name.startswith('P-')


def element(tag, id_attribute, ident):
    """A pattern matching the element with the given ID, empty or with content."""
    return re.compile(r'[ \t]*<' + tag + r' ' + id_attribute + r'="' + re.escape(ident) + r'"[^>]*?(?:/>|(?<!/)>.*?</'
        + tag + r'>)\n?', re.S)


def trim_master(xml, others, extra=()):
    """The master data trimmed to what the other entries of the archive use.

    Kept are the datapoint types the other entries name (each main type with all its subtypes and
    its translations), the mask versions their programs run on with the masks these are downward
    compatible to, the media of these masks, and the manufacturers the other entries name; the
    given extra IDs (of mask versions or main datapoint types) are kept as well. ETS writes its whole
    master data into every file; an import of a fixture with its own part only is cheap, also into
    an empty database. The signature no longer fits and is left out.
    """
    used = set(extra)
    for text in others:
        used.update('DPT-' + n for n in re.findall(r'\bDPS?T-(\d+)', text))
        used.update(re.findall(r'MaskVersion="(MV-[0-9A-F]+)"', text))
        used.update(re.findall(r'\b(M-[0-9A-F]{4})', text))
    masks = set(m for m in used if m.startswith('MV-'))
    todo = list(masks)
    while todo:
        mask = element('MaskVersion', 'Id', todo.pop()).search(xml)
        if mask is None:
            continue
        for compatible in re.findall(r'<DownwardCompatibleMask RefId="(MV-[0-9A-F]+)"', mask.group(0)):
            if compatible not in masks:
                masks.add(compatible)
                todo.append(compatible)
        used.update(re.findall(r'MediumTypeRefId="(MT-\d+)"', mask.group(0)))
    used |= masks
    for tag, id_attribute, prefix in [('DatapointType', 'Id', 'DPT-'), ('MaskVersion', 'Id', 'MV-'),
            ('MediumType', 'Id', 'MT-'), ('Manufacturer', 'Id', 'M-'), ('TranslationUnit', 'RefId', 'DPT-')]:
        for ident in re.findall('<' + tag + ' ' + id_attribute + '="(' + prefix + '[^"]+)"', xml):
            if ident not in used:
                xml = element(tag, id_attribute, ident).sub('', xml, count=1)
    return re.sub(r' Signature="[^"]*"', '', xml, count=1)


def write(target, modify, additions=(), keep=lambda name: True, rename=lambda name: name, master=()):
    """Writes a copy of the Test project, modified per entry, with master data trimmed to its use.

    @param master: IDs of master data the fixture keeps although no other entry names them.
    """
    entries = []
    with zipfile.ZipFile(SOURCE) as source:
        for info in source.infolist():
            if not keep(info.filename):
                continue
            data = source.read(info.filename)
            if info.filename.endswith('.xml'):
                text = data.decode('utf-8-sig')
                modified = modify(info.filename, text)
                if modified != text:
                    data = modified.encode('utf-8')
            info.filename = rename(info.filename)
            entries.append((info, data))
    others = [text for _, text in additions] + [data.decode('utf-8-sig') for info, data in entries
        if info.filename.endswith('.xml') and info.filename != 'knx_master.xml']
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for name, text in additions:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 11, 12, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, text.encode('utf-8'))
        for info, data in entries:
            if info.filename == 'knx_master.xml':
                data = trim_master(data.decode('utf-8-sig'), others, master).encode('utf-8')
            out.writestr(info, data)


def unchanged(name, xml):
    return xml


# The local file header of a ZIP entry, see the ZIP specification (APPNOTE.TXT, 4.3.7).
LOCAL_HEADER = struct.Struct('<IHHHHHIIIHH')
CENTRAL_HEADER_SIGNATURE = 0x02014b50


def encrypted(target, source, entry, password, content):
    """Copies the archive, adding an entry encrypted with the traditional PKWARE encryption.

    Python's zipfile reads such entries but cannot write them, so the entry is written by hand: the
    content stored uncompressed, preceded by the 12 byte encryption header, the entry flagged as
    encrypted (bit 0) in its local and central header.
    """
    crc = zlib.crc32(content)
    keys = [0x12345678, 0x23456789, 0x34567890]

    def update(c):
        keys[0] = zlib.crc32(bytes([c]), keys[0] ^ 0xffffffff) ^ 0xffffffff
        keys[1] = (keys[1] + (keys[0] & 0xff)) & 0xffffffff
        keys[1] = (keys[1] * 134775813 + 1) & 0xffffffff
        keys[2] = zlib.crc32(bytes([keys[1] >> 24]), keys[2] ^ 0xffffffff) ^ 0xffffffff

    def encrypt(data):
        result = bytearray()
        for c in data:
            k = keys[2] | 2
            result.append(c ^ (((k * (k ^ 1)) >> 8) & 0xff))
            update(c)
        return bytes(result)

    for c in password:
        update(c)
    # Fixed random bytes (reproducible fixture); the last byte checks the password: the high byte of
    # the CRC.
    header = bytes(range(1, 12)) + bytes([crc >> 24])
    data = encrypt(header) + encrypt(content)

    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            out.writestr(info, src.read(info.filename))
        name = entry.encode('utf-8')
        info = zipfile.ZipInfo(entry, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.flag_bits |= 0x1
        info.CRC = crc
        info.compress_size = len(data)
        info.file_size = len(content)
        info.header_offset = out.fp.tell()
        out.fp.write(LOCAL_HEADER.pack(0x04034b50, 20, 0x1, 0, 0, 0x5C21, crc, len(data), len(content),
            len(name), 0))
        out.fp.write(name)
        out.fp.write(data)
        out.start_dir = out.fp.tell()
        out.filelist.append(info)
        out.NameToInfo[entry] = info


# TestEts6.knxproj: an ETS 6 project file (schema 23), see the module comment.
ETS6_VERSION = '10D7-11-A0E5-O0024'
ETS6_PROGRAM = 'M-0008_A-' + ETS6_VERSION
ETS6_PROGRAM_NAME = 'TEST: ETS 6 (künstliche Testdaten)'
SCHEMA_11 = 'xmlns="http://knx.org/xml/project/11"'
SCHEMA_23 = 'xmlns="http://knx.org/xml/project/23"'

# 20.102 HVAC mode: (value, English name of the KNX standard, German text made up for the test).
HVAC_VALUES = [(0, 'Auto', 'Automatik'), (1, 'Comfort', 'Komfort'), (2, 'Standby', 'Bereitschaft'),
               (3, 'Economy', 'Sparbetrieb'), (4, 'Building Protection', None)]


def replace_once(xml, old, new):
    assert xml.count(old) == 1, old
    return xml.replace(old, new, 1)


def ets6_master(xml):
    values = ''.join('<EnumValue Id="DPST-20-102_F-1-%d" Value="%d" Text="%s" />' % (v, v, en)
        for v, en, _ in HVAC_VALUES)
    xml = replace_once(xml, '<DatapointSubtype Id="DPST-20-102" Number="102" Name="DPT_HVACMode" Text="HVAC mode" />',
        '<DatapointSubtype Id="DPST-20-102" Number="102" Name="DPT_HVACMode" Text="HVAC mode"><Format>'
        '<Enumeration Id="DPST-20-102_F-1" Width="8" Name="HVACMode">' + values + '</Enumeration>'
        '</Format></DatapointSubtype>')
    # A composed value: the enumeration names the day field only.
    xml = replace_once(xml,
        '<DatapointSubtype Id="DPST-10-1" Number="1" Name="DPT_TimeOfDay" Text="time of day" Default="true" />',
        '<DatapointSubtype Id="DPST-10-1" Number="1" Name="DPT_TimeOfDay" Text="time of day" Default="true"><Format>'
        '<Enumeration Id="DPST-10-1_F-1" Width="3" Name="Day"><EnumValue Id="DPST-10-1_F-1-0" Value="0" Text="no day" />'
        '<EnumValue Id="DPST-10-1_F-1-1" Value="1" Text="Monday" /></Enumeration>'
        '<UnsignedInteger Id="DPST-10-1_F-2" Width="5" Name="Hour" Unit="h" MinInclusive="0" MaxInclusive="23" />'
        '</Format></DatapointSubtype>')
    xml = replace_once(xml, '</MediumTypes>',
        '  <MediumType Id="MT-6" Number="6" Name="IoT" Text="KNX IoT" DomainAddressLength="32" />\n    </MediumTypes>')
    xml = replace_once(xml, '</MaskVersions>',
        '  <MaskVersion Id="MV-FFF0" MaskVersion="65520" Name="TEST IoT (künstliche Testdaten)" ManagementModel="None" '
        'MediumTypeRefId="MT-6"><HawkConfigurationData><Resources><Resource Name="TestResource" Access="remote" '
        'MgmtStyle="simple"><Location AddressSpace="RelativeMemoryByObjectType" InterfaceObjectRef="6" '
        'Occurrence="1" PropertyID="7" StartAddress="0" /><ResourceType Length="4" Flavour="Void" />'
        '<AccessRights Read="Runtime" Write="Configuration" /></Resource></Resources><Procedures>'
        '<Procedure ProcedureType="Load" ProcedureSubType="all"><LdCtrlConnect />'
        '<LdCtrlMasterReset EraseCode="2" ChannelNumber="0" /><LdCtrlDisconnect /></Procedure></Procedures>'
        '</HawkConfigurationData></MaskVersion>\n    </MaskVersions>')
    translations = ''.join('<TranslationElement RefId="DPST-20-102_F-1-%d"><Translation AttributeName="Text" '
        'Text="%s" /></TranslationElement>' % (v, de) for v, _, de in HVAC_VALUES if de)
    end = '</Language>'
    first = xml.index(end)
    return xml[:first] + '<TranslationUnit RefId="DPT-20" Version="1">' + translations + '</TranslationUnit>\n      ' \
        + xml[first:]


def ets6_program(xml):
    assert xml.count('"' + PROGRAM_NAME + '"') == 2
    xml = xml.replace('"' + PROGRAM_NAME + '"', '"' + ETS6_PROGRAM_NAME + '"')
    p = ETS6_PROGRAM + '_'
    types = ('<ParameterType Id="' + p + 'PT-Test.5FMode" Name="Test_Mode"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="' + p + 'PT-Test.5FMode_EN-0" DisplayOrder="0" Text="Aus" Value="0" />'
        '<Enumeration Id="' + p + 'PT-Test.5FMode_EN-1" DisplayOrder="1" Text="Variante 1" Value="1" />'
        '<Enumeration Id="' + p + 'PT-Test.5FMode_EN-2" DisplayOrder="2" Text="Variante 2" Value="2" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="' + p + 'PT-Test.5FLevel" Name="Test_Level">'
        '<TypeNumber SizeInBit="8" Type="unsignedInt" minInclusive="0" maxInclusive="100" /></ParameterType>\n'
        '<ParameterType Id="' + p + 'PT-Test.5FColor" Name="Test_Color"><TypeColor Space="RGB" /></ParameterType>\n')
    parameters = [('9101', 'Test.5FMode', 'TEST Variante', '0'), ('9102', 'Test.5FLevel', 'TEST geteilt', '10'),
                  ('9104', 'Test.5FLevel', 'TEST direkt', '20'), ('9105', 'Test.5FColor', 'TEST Farbe', '#FF8000')]
    params = ''.join('<Parameter Id="' + p + 'P-' + n + '" Name="' + t + '" Text="' + t + '" ParameterType="' + p
        + 'PT-' + ty + '" Access="ReadWrite" Value="' + v + '" />\n' for n, ty, t, v in parameters)
    refs = ''.join('<ParameterRef Id="' + p + 'P-' + n + '_R-' + n + '" RefId="' + p + 'P-' + n + '" Tag="' + n
        + '" />\n' for n, _, _, _ in parameters)
    obj = ('<ComObject Id="' + p + 'O-9110" Name="TEST geteilt" Text="TEST geteilt" Number="91" ObjectSize="1 Byte" '
        'Priority="Low" CommunicationFlag="Enabled" ReadFlag="Disabled" WriteFlag="Enabled" TransmitFlag="Disabled" '
        'UpdateFlag="Disabled" ReadOnInitFlag="Disabled" DatapointType="DPST-20-102" />\n')
    objref = '<ComObjectRef Id="' + p + 'O-9110_R-9110" RefId="' + p + 'O-9110" />\n'
    allocators = ('<Allocators><Allocator Id="' + p + 'L-1" Name="TestAllocator" Start="0" maxInclusive="10" />'
        '</Allocators>\n')
    for end, content in (('</ParameterTypes>', types), ('</Parameters>', params), ('</ParameterRefs>', refs),
                         ('</ComObjectTable>', obj), ('</ComObjectRefs>', objref), ('</Static>', allocators)):
        xml = replace_once(xml, end, content + end)
    shared = ('<ParameterRefRef RefId="' + p + 'P-9102_R-9102" /><ComObjectRefRef RefId="' + p + 'O-9110_R-9110" />')
    module = ('<Module Id="' + p + 'MD-1_M-1" RefId="' + p + 'MD-1"><NumericArg RefId="' + p
        + 'MD-1_A-1" AllocatorRefId="' + p + 'L-1" /></Module>')
    block = ('<ChannelIndependentBlock><ParameterBlock Id="' + p + 'PB-9100" Name="Test ETS6" Text="TEST ETS 6">'
        '<ParameterRefRef RefId="' + p + 'P-9101_R-9101" /><choose ParamRefId="' + p + 'P-9101_R-9101">'
        '<when test="1">' + shared + '</when><when test="2">' + shared + module + '</when><when default="true" />'
        '</choose></ParameterBlock></ChannelIndependentBlock>\n'
        '<ParameterBlock Id="' + p + 'PB-9103" Name="Test direkt" Text="TEST direkt">'
        '<ParameterRefRef RefId="' + p + 'P-9104_R-9104" /><ParameterRefRef RefId="' + p + 'P-9105_R-9105" />'
        '</ParameterBlock>\n')
    xml = replace_once(xml, '<Dynamic>', '<Dynamic>\n' + block)
    modules = ('<ModuleDefs><ModuleDef Id="' + p + 'MD-1" Name="TestModul"><Arguments><Argument Id="' + p
        + 'MD-1_A-1" Name="Basis" /></Arguments><Static><Parameters><Parameter Id="' + p + 'MD-1_P-1" Name="Modul" '
        'Text="TEST Modul" ParameterType="' + p + 'PT-Test.5FLevel" Access="ReadWrite" Value="0" /></Parameters>'
        '<ParameterRefs><ParameterRef Id="' + p + 'MD-1_P-1_R-1" RefId="' + p + 'MD-1_P-1" /></ParameterRefs>'
        '</Static><Dynamic><Channel Id="' + p + 'MD-1_CH-1" Name="Modul" Text="TEST Modul" Number="1">'
        '<ParameterBlock Id="' + p + 'MD-1_PB-1" Name="Modul" Text="TEST Modul"><ParameterRefRef RefId="' + p
        + 'MD-1_P-1_R-1" /></ParameterBlock></Channel></Dynamic></ModuleDef></ModuleDefs>\n')
    return replace_once(xml, '<Dynamic>', modules + '<Dynamic>')


def ets6_project(xml):
    program = 'M-0008_A-10D7-11-A0E5-O000A_'
    assert program not in xml.replace('M-0008_A-10D7-11-A0E5-O000A_O-', '').replace(
        'M-0008_A-10D7-11-A0E5-O000A_P-', '').replace('M-0008_A-10D7-11-A0E5-O000A_UP-', '')
    # Communication objects relative to the program; settings keep their full IDs (as ETS 6 writes them).
    xml = xml.replace('RefId="' + program + 'O-', 'RefId="O-')
    xml = xml.replace('ParameterInstanceRef RefId="' + program, 'ParameterInstanceRef RefId="' + ETS6_PROGRAM + '_')
    xml = xml.replace(VERSION, ETS6_VERSION)
    # Links instead of Connectors, relative to the installation.
    import re
    xml = re.sub(r'IsActive="1">\s*<Connectors>\s*<Send GroupAddressRefId="P-01A0-0_(GA-\d+)" />\s*</Connectors>\s*'
        r'</ComObjectInstanceRef>', r'IsActive="1" Links="\1" />', xml)
    assert '<Connectors>' not in xml
    xml = replace_once(xml, 'Links="GA-3"', 'Links="GA-3 GA-1"')
    # Load state as newer ETS versions write it.
    xml = xml.replace('Loaded="0"', 'Loaded="false"')
    xml = replace_once(xml, 'IndividualAddressLoaded="false"', 'IndividualAddressLoaded="true"')
    # The device in a segment of its line.
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1"')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    xml = (xml[:start] + '<Segment Id="P-01A0-0_L-1_S-0" Name="" Number="0" MediumTypeRefId="MT-0">'
        + xml[start:end] + '</Segment>' + xml[end:])
    # The shared parameter set by the device, and a setting and a linked object of an instance of the
    # dynamic module the device does not have: both are left out with a warning.
    xml = replace_once(xml, '<ParameterInstanceRefs>', '<ParameterInstanceRefs>\n<ParameterInstanceRef RefId="'
        + ETS6_PROGRAM + '_P-9102_R-9102" Value="42" />\n<ParameterInstanceRef RefId="'
        + ETS6_PROGRAM + '_MD-1_M-1_MI-1_P-1_R-1" Value="7" />')
    xml = replace_once(xml, '<ComObjectInstanceRefs>', '<ComObjectInstanceRefs>\n<ComObjectInstanceRef '
        'RefId="MD-1_M-1_MI-1_O-3-1_R-1" IsActive="1" Links="GA-2" />')
    return replace_once(xml, '<Buildings />', '<Locations><Space Id="P-01A0-0_BP-1" Name="TEST Haus" Type="Building">'
        '<Space Id="P-01A0-0_BP-2" Name="TEST Raum" Type="Room"><DeviceInstanceRef RefId="P-01A0-0_DI-1" /></Space>'
        '</Space></Locations>')


def ets6(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == 'knx_master.xml':
        return ets6_master(xml)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestEts6')
    if name == PROJECT:
        return ets6_project(xml)
    if name.endswith(PROGRAM + '.xml'):
        return ets6_program(xml.replace(VERSION, ETS6_VERSION))
    return xml.replace(VERSION, ETS6_VERSION)


def ets6_name(name):
    name = name.replace(VERSION, ETS6_VERSION)
    return 'P-01A0/project.xml' if name == PROJECT_INFO else name


# TestModules.knxproj: an application program with a dynamic module (haumacher/ehome #119), see the
# module comment.
MODULE_PROGRAM = 'M-0008_A-FFF7-01-0000'
MODULE_PROGRAM_NAME = 'TEST: dynamische Module (künstliche Testdaten)'
MODULE_HARDWARE = 'M-0008_H-TEST.20MODULE-1'
MODULE_PRODUCT = MODULE_HARDWARE + '_P-TEST.20MODULE'
MODULE_ASSIGNMENT = MODULE_HARDWARE + '_HP-FFF7-01-0000'
# The placements of the module: (number, name, ParamOffsBase, ObjNumberBase, ChNo).
MODULE_PLACEMENTS = [(1, 'Kanal A', 0x60, 1, 'A'), (2, 'Kanal B', 0x70, 3, 'B')]


def module_program():
    p = MODULE_PROGRAM
    segment = p + '_AS-0100'
    # The memory of the BCU 1 programs (header, tables); the parameters lie above it.
    data, mask = bcu1_memory()
    flags = ('Priority="Low" CommunicationFlag="Enabled" ReadFlag="Disabled" WriteFlag="Enabled" '
        'TransmitFlag="Disabled" UpdateFlag="Disabled" ReadOnInitFlag="Disabled"')
    types = ('<ParameterType Id="{p}_PT-Schalter" Name="Schalter"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Schalter_EN-0" Text="Aus" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Schalter_EN-1" Text="Ein" Value="1" DisplayOrder="1" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Modus" Name="Modus"><TypeRestriction Base="Value" SizeInBit="2">'
        '<Enumeration Id="{p}_PT-Modus_EN-0" Text="Aus" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Modus_EN-1" Text="Schalten" Value="1" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Modus_EN-2" Text="Treppenlicht" Value="2" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Text" Name="Text"><TypeText SizeInBit="32" /></ParameterType>\n')
    params = ('<Parameter Id="{p}_P-1" Name="KanalB" Text="Kanal B" ParameterType="{p}_PT-Schalter" '
        'Access="ReadWrite" Value="0"><Memory CodeSegment="' + segment + '" Offset="80" BitOffset="0" /></Parameter>\n'
        '<Parameter Id="{p}_P-2" Name="Bezeichnung" Text="Bezeichnung" ParameterType="{p}_PT-Text" '
        'Access="ReadWrite" Value="Haus"><Memory CodeSegment="' + segment + '" Offset="84" BitOffset="0" /></Parameter>\n')
    refs = ('<ParameterRef Id="{p}_P-1_R-1" RefId="{p}_P-1" />\n<ParameterRef Id="{p}_P-2_R-1" RefId="{p}_P-2" />\n')
    objects = ('<ComObject Id="{p}_O-0" Name="Zentral" Text="Zentral" FunctionText="Ein/Aus" Number="0" '
        'ObjectSize="1 Bit" ' + flags + ' DatapointType="DPST-1-1" />\n')
    objrefs = '<ComObjectRef Id="{p}_O-0_R-1" RefId="{p}_O-0" Text="Zentral {{0}}" TextParameterRefId="{p}_P-2_R-1" />\n'
    module = ('<ModuleDefs><ModuleDef Id="{p}_MD-1" Name="Kanal"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamOffsBase" /><Argument Id="{p}_MD-1_A-2" Name="ObjNumberBase" />'
        '<Argument Id="{p}_MD-1_A-3" Name="ChNo" /></Arguments>\n<Static><Parameters>'
        '<Parameter Id="{p}_MD-1_P-1" Name="Modus" Text="Kanal {{ChNo}}: Modus" ParameterType="{p}_PT-Modus" '
        'Access="ReadWrite" Value="2"><Memory CodeSegment="' + segment + '" Offset="2" BitOffset="4" '
        'BaseOffset="{p}_MD-1_A-1" /></Parameter>'
        '<Parameter Id="{p}_MD-1_P-2" Name="Beschreibung" Text="Kanal {{ChNo}}: Beschreibung" '
        'ParameterType="{p}_PT-Text" Access="ReadWrite" Value="Flur"><Memory CodeSegment="' + segment + '" Offset="3" '
        'BitOffset="0" BaseOffset="{p}_MD-1_A-1" /></Parameter></Parameters>\n'
        '<ParameterRefs><ParameterRef Id="{p}_MD-1_P-1_R-1" RefId="{p}_MD-1_P-1" />'
        '<ParameterRef Id="{p}_MD-1_P-2_R-1" RefId="{p}_MD-1_P-2" /></ParameterRefs>\n'
        '<ComObjects><ComObject Id="{p}_MD-1_O-2-0" Name="Schalten" Text="Kanal {{ChNo}}: Schalten" FunctionText="Ein/Aus" '
        'Number="0" BaseNumber="{p}_MD-1_A-2" ObjectSize="1 Bit" ' + flags + ' DatapointType="DPST-1-1" />'
        '<ComObject Id="{p}_MD-1_O-2-1" Name="Status" Text="Kanal {{ChNo}}: Status" FunctionText="Status" '
        'Number="1" BaseNumber="{p}_MD-1_A-2" ObjectSize="1 Bit" ' + flags + ' DatapointType="DPST-1-11" />'
        '</ComObjects>\n<ComObjectRefs>'
        '<ComObjectRef Id="{p}_MD-1_O-2-0_R-1" RefId="{p}_MD-1_O-2-0" Text="Kanal {{ChNo}}: {{0}}" '
        'TextParameterRefId="{p}_MD-1_P-2_R-1" />'
        '<ComObjectRef Id="{p}_MD-1_O-2-1_R-1" RefId="{p}_MD-1_O-2-1" /></ComObjectRefs></Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Kanal" Text="Kanal {{ChNo}}">'
        '<ParameterBlock Id="{p}_MD-1_PB-1" Name="Kanal" Text="Kanal {{ChNo}}">'
        '<ParameterRefRef RefId="{p}_MD-1_P-1_R-1" /><ParameterRefRef RefId="{p}_MD-1_P-2_R-1" />'
        '<choose ParamRefId="{p}_MD-1_P-1_R-1"><when test="1"><ComObjectRefRef RefId="{p}_MD-1_O-2-0_R-1" /></when>'
        '</choose><ComObjectRefRef RefId="{p}_MD-1_O-2-1_R-1" /></ParameterBlock></Channel></Dynamic>'
        '</ModuleDef></ModuleDefs>\n')

    def placement(number, name, offset, base, channel):
        return ('<Module Id="{p}_MD-1_M-%d" RefId="{p}_MD-1" Name="%s"><NumericArg RefId="{p}_MD-1_A-1" Value="%d" />'
            '<NumericArg RefId="{p}_MD-1_A-2" Value="%d" /><TextArg RefId="{p}_MD-1_A-3" Value="%s" /></Module>'
            % (number, name, offset, base, channel))

    first, second = (placement(*m) for m in MODULE_PLACEMENTS)
    dynamic = ('<Dynamic><ChannelIndependentBlock><ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        '<ParameterRefRef RefId="{p}_P-1_R-1" /><ParameterRefRef RefId="{p}_P-2_R-1" />'
        '<ComObjectRefRef RefId="{p}_O-0_R-1" /></ParameterBlock></ChannelIndependentBlock>\n'
        + first + '\n<choose ParamRefId="{p}_P-1_R-1"><when test="1">' + second + '</when><when default="true" />'
        '</choose></Dynamic>\n')
    # The texts of the module in English; ETS writes a translation per language and text.
    english = [('{p}_MD-1_O-2-0_R-1', 'Channel {{ChNo}}: {{0}}'), ('{p}_MD-1_O-2-0', 'Channel {{ChNo}}: Switching'),
               ('{p}_MD-1_CH-1', 'Channel {{ChNo}}'), ('{p}_MD-1_P-1', 'Channel {{ChNo}}: Mode')]
    languages = ('<Languages><Language Identifier="en-US"><TranslationUnit RefId="{p}" Version="0">'
        + ''.join('<TranslationElement RefId="%s"><Translation AttributeName="Text" Text="%s" /></TranslationElement>'
            % entry for entry in english)
        + '</TranslationUnit></Language></Languages>')
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/23" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + MODULE_PROGRAM_NAME + '" ApplicationNumber="65527" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="5.7" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="' + segment + '" Address="256" Size="256" UserMemory="0"><Data>' + data
        + '</Data><Mask>' + mask + '</Mask></AbsoluteSegment></Code>\n'
        '<ParameterTypes>\n' + types + '</ParameterTypes>\n'
        '<Parameters>\n' + params + '</Parameters>\n'
        '<ParameterRefs>\n' + refs + '</ParameterRefs>\n'
        '<ComObjectTable CodeSegment="' + segment + '" Offset="54">\n' + objects + '</ComObjectTable>\n'
        '<ComObjectRefs>\n' + objrefs + '</ComObjectRefs>\n'
        '<AddressTable CodeSegment="' + segment + '" Offset="22" MaxEntries="6" />\n'
        '<AssociationTable CodeSegment="' + segment + '" Offset="37" MaxEntries="8" />\n'
        '<Options ParameterByteOrder="BigEndian" />\n'
        '</Static>\n' + module + dynamic
        + '</ApplicationProgram>\n</ApplicationPrograms>' + languages + '</Manufacturer></ManufacturerData>\n</KNX>\n')
    return xml.replace('{p}', p)


def module_hardware():
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/23" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><Hardware>'
        '<Hardware Id="' + MODULE_HARDWARE + '" Name="TEST Module" SerialNumber="TEST MODULE" VersionNumber="1" '
        'HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="' + MODULE_PRODUCT + '" '
        'Text="TEST Module (künstliche Testdaten)" OrderNumber="TEST MODULE" IsRailMounted="1" DefaultLanguage="de-DE" />'
        '</Products><Hardware2Programs><Hardware2Program Id="' + MODULE_ASSIGNMENT + '" MediumTypes="MT-0">'
        '<ApplicationProgramRef RefId="' + MODULE_PROGRAM + '" /></Hardware2Program>'
        '<Hardware2Program Id="' + REPEAT_ASSIGNMENT + '" MediumTypes="MT-0">'
        '<ApplicationProgramRef RefId="' + REPEAT_PROGRAM + '" /></Hardware2Program></Hardware2Programs></Hardware>'
        '</Hardware></Manufacturer></ManufacturerData>\n</KNX>\n')


def module_device(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    p = MODULE_PROGRAM
    device = ('<DeviceInstance Id="P-01A0-0_DI-1" Name="TEST Modulgerät" ProductRefId="' + MODULE_PRODUCT
        + '" Hardware2ProgramRefId="' + MODULE_ASSIGNMENT + '" Address="1" LastModified="2026-11-08T12:00:00">\n'
        '<ParameterInstanceRefs>\n'
        '<ParameterInstanceRef RefId="' + p + '_P-1_R-1" Value="1" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-1_MI-1_P-1_R-1" Value="1" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-1_MI-1_P-2_R-1" Value="WZ" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-2_MI-1_P-2_R-1" Value="" />\n'
        '</ParameterInstanceRefs>\n<ComObjectInstanceRefs>\n'
        '<ComObjectInstanceRef RefId="O-0_R-1" IsActive="true" Links="GA-1" />\n'
        '<ComObjectInstanceRef RefId="MD-1_M-1_MI-1_O-2-0_R-1" IsActive="true" Links="GA-2" />\n'
        '<ComObjectInstanceRef RefId="MD-1_M-1_MI-1_O-2-1_R-1" IsActive="true" Links="GA-3" />\n'
        '<ComObjectInstanceRef RefId="MD-1_M-2_MI-1_O-2-0_R-1" IsActive="true" Links="GA-4 GA-1" />\n'
        '</ComObjectInstanceRefs>\n<ModuleInstances>\n'
        + ''.join('<ModuleInstance Id="MD-1_M-%d_MI-1" RefId="MD-1_M-%d"><Arguments>'
            '<Argument RefId="MD-1_A-1" Value="%d" /><Argument RefId="MD-1_A-2" Value="%d" />'
            '<Argument RefId="MD-1_A-3" Value="%s" /></Arguments></ModuleInstance>\n'
            % (n, n, offset, base, channel) for n, _, offset, base, channel in MODULE_PLACEMENTS)
        + '</ModuleInstances>\n</DeviceInstance>')
    return xml[:start] + device + '\n' + repeat_devices() + xml[end:]


REPEAT_PROGRAM = 'M-0008_A-FFF8-01-0000'
REPEAT_PROGRAM_NAME = 'TEST: Repeat, Allocatoren, Untermodule (künstliche Testdaten)'
REPEAT_ASSIGNMENT = MODULE_HARDWARE + '_HP-FFF8-01-0000'


def repeat_program():
    p = REPEAT_PROGRAM
    segment = p + '_AS-0100'
    data, mask = bcu1_memory()
    flags = ('Priority="Low" CommunicationFlag="Enabled" ReadFlag="Disabled" WriteFlag="Enabled" '
        'TransmitFlag="Disabled" UpdateFlag="Disabled" ReadOnInitFlag="Disabled"')
    types = ('<ParameterType Id="{p}_PT-Anzahl8" Name="Anzahl8"><TypeNumber SizeInBit="8" Type="unsignedInt" '
        'minInclusive="0" maxInclusive="8" /></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Anzahl3" Name="Anzahl3"><TypeNumber SizeInBit="8" Type="unsignedInt" '
        'minInclusive="0" maxInclusive="3" /></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Anzahl5" Name="Anzahl5"><TypeNumber SizeInBit="8" Type="unsignedInt" '
        'minInclusive="0" maxInclusive="5" /></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Wert" Name="Wert"><TypeNumber SizeInBit="8" Type="unsignedInt" '
        'minInclusive="0" maxInclusive="255" /></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Typ" Name="Typ"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Typ_EN-0" Text="Aus" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Typ_EN-1" Text="Analog" Value="1" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Typ_EN-2" Text="Digital" Value="2" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n'
        '<ParameterType Id="{p}_PT-Text" Name="Text"><TypeText SizeInBit="32" /></ParameterType>\n')
    params = ('<Parameter Id="{p}_P-1" Name="AnzahlSignale" Text="Anzahl Signale" ParameterType="{p}_PT-Anzahl8" '
        'Access="ReadWrite" Value="2"><Memory CodeSegment="' + segment + '" Offset="88" BitOffset="0" /></Parameter>\n'
        '<Parameter Id="{p}_P-2" Name="AnzahlKanaele" Text="Anzahl Kanäle" ParameterType="{p}_PT-Anzahl3" '
        'Access="ReadWrite" Value="2"><Memory CodeSegment="' + segment + '" Offset="89" BitOffset="0" /></Parameter>\n')
    refs = '<ParameterRef Id="{p}_P-1_R-1" RefId="{p}_P-1" />\n<ParameterRef Id="{p}_P-2_R-1" RefId="{p}_P-2" />\n'
    # The objects 0 to 2 of the object table of the static data: the program's own.
    objects = ''.join('<ComObject Id="{p}_O-%d" Name="Allgemein%d" Text="Allgemein %d" Number="%d" ObjectSize="1 Bit" '
        % (n, n, n, n) + flags + ' />' for n in range(3))
    objrefs = ''.join('<ComObjectRef Id="{p}_O-%d_R-1" RefId="{p}_O-%d" />' % (n, n) for n in range(3))
    allocators = ('<Allocators><Allocator Id="{p}_L-1" Name="Signal_params" Start="160" maxInclusive="224" />'
        '<Allocator Id="{p}_L-2" Name="Signal_objs" Start="10" maxInclusive="15" />'
        '<Allocator Id="{p}_L-3" Name="Nummer" Start="1" maxInclusive="6" />'
        '<Allocator Id="{p}_L-4" Name="Kanal_params" Start="96" maxInclusive="120" />'
        '<Allocator Id="{p}_L-5" Name="Kanal_objs" Start="20" maxInclusive="28" /></Allocators>\n')
    signal = ('<ModuleDef Id="{p}_MD-1" Name="Signal"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamsOffsetBase" Allocates="32" />'
        '<Argument Id="{p}_MD-1_A-2" Name="ObjNumberBase" Allocates="2" />'
        '<Argument Id="{p}_MD-1_A-3" Name="SigNo" Allocates="1" /></Arguments>\n<Static><Parameters>'
        '<Parameter Id="{p}_MD-1_P-1" Name="Typ" Text="Signal {{SigNo}}: Typ" ParameterType="{p}_PT-Typ" '
        'Access="ReadWrite" Value="1"><Memory CodeSegment="' + segment + '" Offset="0" BitOffset="0" '
        'BaseOffset="{p}_MD-1_A-1" /></Parameter>'
        '<Parameter Id="{p}_MD-1_P-2" Name="Name" Text="Signal {{SigNo}}: Name" ParameterType="{p}_PT-Text" '
        'Access="ReadWrite" Value="Sig"><Memory CodeSegment="' + segment + '" Offset="1" BitOffset="0" '
        'BaseOffset="{p}_MD-1_A-1" /></Parameter></Parameters>\n'
        '<ParameterRefs><ParameterRef Id="{p}_MD-1_P-1_R-1" RefId="{p}_MD-1_P-1" />'
        '<ParameterRef Id="{p}_MD-1_P-2_R-1" RefId="{p}_MD-1_P-2" /></ParameterRefs>\n'
        '<ComObjects><ComObject Id="{p}_MD-1_O-1-0" Name="Wert" Text="Signal {{SigNo}}: Wert" FunctionText="Wert" '
        'Number="0" BaseNumber="{p}_MD-1_A-2" ObjectSize="1 Byte" ' + flags + ' />'
        '<ComObject Id="{p}_MD-1_O-1-1" Name="Status" Text="Signal {{SigNo}}: Status" FunctionText="Status" '
        'Number="1" BaseNumber="{p}_MD-1_A-2" ObjectSize="1 Bit" ' + flags + ' DatapointType="DPST-1-11" />'
        '</ComObjects>\n<ComObjectRefs>'
        '<ComObjectRef Id="{p}_MD-1_O-1-0_R-1" RefId="{p}_MD-1_O-1-0" Text="Signal {{SigNo}}: {{0}}" '
        'TextParameterRefId="{p}_MD-1_P-2_R-1" />'
        '<ComObjectRef Id="{p}_MD-1_O-1-1_R-1" RefId="{p}_MD-1_O-1-1" /></ComObjectRefs></Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Signal" Text="Signal {{SigNo}}: {{0}}" '
        'TextParameterRefId="{p}_MD-1_P-2_R-1">'
        '<ParameterBlock Id="{p}_MD-1_PB-1" Name="Signal" Text="{{0:Signal {{SigNo}}}}" '
        'TextParameterRefId="{p}_MD-1_P-2_R-1">'
        '<ParameterRefRef RefId="{p}_MD-1_P-1_R-1" /><ParameterRefRef RefId="{p}_MD-1_P-2_R-1" />'
        '<ComObjectRefRef RefId="{p}_MD-1_O-1-0_R-1" /><ComObjectRefRef RefId="{p}_MD-1_O-1-1_R-1" />'
        '</ParameterBlock></Channel></Dynamic></ModuleDef>\n')
    q = '{p}_MD-2_SM-1'
    scene = ('<SubModuleDefs><ModuleDef Id="' + q + '" Name="Szene"><Arguments>'
        '<Argument Id="' + q + '_A-1" Name="SzOffs" Allocates="2" /><Argument Id="' + q + '_A-2" Name="SzObj" Allocates="1" />'
        '<Argument Id="' + q + '_A-3" Name="KaNo" /><Argument Id="' + q + '_A-4" Name="SzNo" Allocates="1" /></Arguments>'
        '<Static><Parameters><Parameter Id="' + q + '_P-1" Name="Wert" Text="Kanal {{KaNo}}, Szene {{SzNo}}: Wert" '
        'ParameterType="{p}_PT-Wert" Access="ReadWrite" Value="5"><Memory CodeSegment="' + segment + '" Offset="0" '
        'BitOffset="0" BaseOffset="' + q + '_A-1" /></Parameter></Parameters>'
        '<ParameterRefs><ParameterRef Id="' + q + '_P-1_R-1" RefId="' + q + '_P-1" /></ParameterRefs>'
        '<ComObjects><ComObject Id="' + q + '_O-1-0" Name="Szene" Text="Szene {{SzNo}}" FunctionText="Szene" Number="0" '
        'BaseNumber="' + q + '_A-2" ObjectSize="1 Byte" ' + flags + ' /></ComObjects>'
        '<ComObjectRefs><ComObjectRef Id="' + q + '_O-1-0_R-1" RefId="' + q + '_O-1-0" '
        'Text="Kanal {{KaNo}}, Szene {{SzNo}}" /></ComObjectRefs></Static>'
        '<Dynamic><ParameterBlock Id="' + q + '_PB-1" Name="Szene" Text="Szene {{SzNo}}">'
        '<ParameterRefRef RefId="' + q + '_P-1_R-1" /><ComObjectRefRef RefId="' + q + '_O-1-0_R-1" />'
        '</ParameterBlock></Dynamic></ModuleDef></SubModuleDefs>')
    channel = ('<ModuleDef Id="{p}_MD-2" Name="Kanal"><Arguments>'
        '<Argument Id="{p}_MD-2_A-1" Name="KaOffs" Allocates="12" />'
        '<Argument Id="{p}_MD-2_A-2" Name="KaObj" Allocates="4" />'
        '<Argument Id="{p}_MD-2_A-3" Name="KaNo" Allocates="1" /></Arguments>\n<Static><Parameters>'
        '<Parameter Id="{p}_MD-2_P-1" Name="AnzahlSzenen" Text="Kanal {{KaNo}}: Anzahl Szenen" '
        'ParameterType="{p}_PT-Anzahl5" Access="ReadWrite" Value="1"><Memory CodeSegment="' + segment + '" Offset="0" '
        'BitOffset="0" BaseOffset="{p}_MD-2_A-1" /></Parameter></Parameters>\n'
        '<ParameterRefs><ParameterRef Id="{p}_MD-2_P-1_R-1" RefId="{p}_MD-2_P-1" /></ParameterRefs>\n'
        '<ComObjects><ComObject Id="{p}_MD-2_O-1-0" Name="Kanal" Text="Kanal {{KaNo}}" FunctionText="Kanal" Number="0" '
        'BaseNumber="{p}_MD-2_A-2" ObjectSize="1 Bit" ' + flags + ' /></ComObjects>\n'
        '<ComObjectRefs><ComObjectRef Id="{p}_MD-2_O-1-0_R-1" RefId="{p}_MD-2_O-1-0" /></ComObjectRefs>\n'
        '<Allocators><Allocator Id="{p}_MD-2_L-1" Name="Szene_params" Start="2" maxInclusive="6" />'
        '<Allocator Id="{p}_MD-2_L-2" Name="Szene_objs" Start="1" maxInclusive="3" />'
        '<Allocator Id="{p}_MD-2_L-3" Name="Szene_num" Start="1" maxInclusive="3" /></Allocators></Static>\n'
        + scene +
        '<Dynamic><Channel Id="{p}_MD-2_CH-1" Name="Kanal" Text="Kanal {{KaNo}}">'
        '<ParameterBlock Id="{p}_MD-2_PB-1" Name="Kanal" Text="Kanal {{KaNo}}">'
        '<ParameterRefRef RefId="{p}_MD-2_P-1_R-1" /><ComObjectRefRef RefId="{p}_MD-2_O-1-0_R-1" /></ParameterBlock>'
        '<Repeat Id="{p}_MD-2_X-1" Name="Szenen" ParameterRefId="{p}_MD-2_P-1_R-1">'
        '<Module Id="' + q + '_M-1" RefId="' + q + '" Name="Szene">'
        '<NumericArg RefId="' + q + '_A-1" AllocatorRefId="{p}_MD-2_L-1" BaseValue="{p}_MD-2_A-1" />'
        '<NumericArg RefId="' + q + '_A-2" AllocatorRefId="{p}_MD-2_L-2" BaseValue="{p}_MD-2_A-2" />'
        '<NumericArg RefId="' + q + '_A-3" Value="0" BaseValue="{p}_MD-2_A-3" />'
        '<NumericArg RefId="' + q + '_A-4" AllocatorRefId="{p}_MD-2_L-3" /></Module>'
        '</Repeat></Channel></Dynamic></ModuleDef>\n')
    # The positions of the elements of the dynamic part (ETS: XmlOrder, the dynamic part counting 0):
    # X-1 is 5, MD-1_M-1 6, X-2 10, MD-2_M-1 11; in MD-2, MD-2_X-1 is 5 and the placement of the scene 6.
    dynamic = ('<Dynamic><ChannelIndependentBlock><ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        '<ParameterRefRef RefId="{p}_P-1_R-1" /><ParameterRefRef RefId="{p}_P-2_R-1" /></ParameterBlock>'
        '</ChannelIndependentBlock>\n'
        '<Repeat Id="{p}_X-1" Name="Signale" ParameterRefId="{p}_P-1_R-1"><Module Id="{p}_MD-1_M-1" RefId="{p}_MD-1" '
        'Name="Signal"><NumericArg RefId="{p}_MD-1_A-1" AllocatorRefId="{p}_L-1" />'
        '<NumericArg RefId="{p}_MD-1_A-2" AllocatorRefId="{p}_L-2" /><NumericArg RefId="{p}_MD-1_A-3" '
        'AllocatorRefId="{p}_L-3" /></Module></Repeat>\n'
        '<Repeat Id="{p}_X-2" Name="Kanäle" ParameterRefId="{p}_P-2_R-1"><Module Id="{p}_MD-2_M-1" RefId="{p}_MD-2" '
        'Name="Kanal"><NumericArg RefId="{p}_MD-2_A-1" AllocatorRefId="{p}_L-4" />'
        '<NumericArg RefId="{p}_MD-2_A-2" AllocatorRefId="{p}_L-5" /><NumericArg RefId="{p}_MD-2_A-3" '
        'AllocatorRefId="{p}_L-3" /></Module></Repeat></Dynamic>\n')
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/23" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + REPEAT_PROGRAM_NAME + '" ApplicationNumber="65528" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="6.0" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="' + segment + '" Address="256" Size="256" UserMemory="0"><Data>' + data
        + '</Data><Mask>' + mask + '</Mask></AbsoluteSegment></Code>\n'
        '<ParameterTypes>\n' + types + '</ParameterTypes>\n'
        '<Parameters>\n' + params + '</Parameters>\n'
        '<ParameterRefs>\n' + refs + '</ParameterRefs>\n'
        '<ComObjectTable CodeSegment="' + segment + '" Offset="54">' + objects + '</ComObjectTable>\n'
        '<ComObjectRefs>' + objrefs + '</ComObjectRefs>\n'
        '<AddressTable CodeSegment="' + segment + '" Offset="22" MaxEntries="6" />\n'
        '<AssociationTable CodeSegment="' + segment + '" Offset="37" MaxEntries="8" />\n'
        '<Options ParameterByteOrder="BigEndian" />\n' + allocators +
        '</Static>\n<ModuleDefs>' + signal + channel + '</ModuleDefs>\n' + dynamic
        + '</ApplicationProgram>\n</ApplicationPrograms></Manufacturer></ManufacturerData>\n</KNX>\n')
    return xml.replace('{p}', p)


def repeat_devices():
    p = REPEAT_PROGRAM

    def instance(ident, ref, index, arguments):
        return ('<ModuleInstance Id="%s" RefId="%s" RepeatIndex="%s"><Arguments>' % (ident, ref, index)
            + ''.join('<Argument RefId="%s" Value="%s" />' % arg for arg in arguments) + '</Arguments></ModuleInstance>\n')

    signals = ''.join(instance('MD-1_M-1_MI-%d' % n, 'MD-1_M-1', '5x%d 6x1' % n,
        [('MD-1_A-1', 'L-1'), ('MD-1_A-2', 'L-2'), ('MD-1_A-3', 'L-3')]) for n in (1, 2, 3))
    channels = ''.join(instance('MD-2_M-1_MI-%d' % n, 'MD-2_M-1', '10x%d 11x1' % n,
        [('MD-2_A-1', 'L-4'), ('MD-2_A-2', 'L-5'), ('MD-2_A-3', 'L-3')]) for n in (1, 2))
    scenes = ''.join(instance('MD-2_M-1_MI-%d_SM-1_M-1_MI-%d-1-%d' % (c, c, k), 'MD-2_M-1', '10x%d 11x1 5x%d 6x1' % (c, k),
        [('SM-1_A-1', 'MD-2_L-1'), ('SM-1_A-2', 'MD-2_L-2'), ('SM-1_A-3', '0'), ('SM-1_A-4', 'MD-2_L-3')])
        for c, k in ((1, 1), (2, 1), (2, 2)))
    repeat = ('<DeviceInstance Id="P-01A0-0_DI-3" Name="TEST Repeat-Gerät" ProductRefId="' + MODULE_PRODUCT
        + '" Hardware2ProgramRefId="' + REPEAT_ASSIGNMENT + '" Address="3" LastModified="2026-11-08T12:00:00">\n'
        '<ParameterInstanceRefs>\n'
        '<ParameterInstanceRef RefId="' + p + '_P-1_R-1" Value="3" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-1_MI-2_P-2_R-1" Value="Süd" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-1_MI-3_P-2_R-1" Value="" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-2_M-1_MI-2_P-1_R-1" Value="2" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-2_M-1_MI-2-1-2_SM-1_P-1_R-1" Value="7" />\n'
        '</ParameterInstanceRefs>\n<ComObjectInstanceRefs>\n'
        '<ComObjectInstanceRef RefId="MD-1_M-1_MI-2_O-1-0_R-1" IsActive="true" Links="GA-2" />\n'
        '<ComObjectInstanceRef RefId="MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_O-1-0_R-1" IsActive="true" Links="GA-3" />\n'
        '</ComObjectInstanceRefs>\n<ModuleInstances>\n' + signals + channels + scenes
        + '</ModuleInstances>\n</DeviceInstance>')
    # The same program as 1.0.1, its memory written with another layout: ParamOffsBase of Kanal A 80.
    old = ('<DeviceInstance Id="P-01A0-0_DI-2" Name="TEST Modulgerät alt" ProductRefId="' + MODULE_PRODUCT
        + '" Hardware2ProgramRefId="' + MODULE_ASSIGNMENT + '" Address="2" LastModified="2026-11-08T12:00:00">\n'
        '<ParameterInstanceRefs>\n<ParameterInstanceRef RefId="' + MODULE_PROGRAM + '_MD-1_M-2_MI-1_P-2_R-1" '
        'Value="Bad" />\n</ParameterInstanceRefs>\n<ComObjectInstanceRefs>\n'
        '<ComObjectInstanceRef RefId="MD-1_M-2_MI-1_O-2-0_R-1" IsActive="true" Links="GA-4" />\n'
        '</ComObjectInstanceRefs>\n<ModuleInstances>\n'
        '<ModuleInstance Id="MD-1_M-1_MI-1" RefId="MD-1_M-1"><Arguments><Argument RefId="MD-1_A-1" Value="80" />'
        '<Argument RefId="MD-1_A-2" Value="1" /><Argument RefId="MD-1_A-3" Value="A" /></Arguments></ModuleInstance>\n'
        '</ModuleInstances>\n</DeviceInstance>')
    return old + '\n' + repeat


def modules(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestModules')
    if name == PROJECT:
        return module_device(xml)
    return xml


MASTER_DATA_HEADER = ('<?xml version="1.0" encoding="UTF-8"?>\n<KNX xmlns="http://knx.org/xml/project/11">\n'
    '  <MasterData Version="%d">\n'
    '    <MediumTypes><MediumType Id="MT-0" Number="0" Name="TP" Text="Twisted Pair" DomainAddressLength="0" />'
    '</MediumTypes>\n    <MaskVersions>\n')
MASTER_DATA_FOOTER = '    </MaskVersions>\n  </MasterData>\n</KNX>\n'

# The configuration of MV-FFF1 by properties (the current one of the old master data).
TEST_MASK_PROPERTIES = ('<Features><Feature Name="FirstAppObjectIdx" Value="%d" /><Feature Name="VerifyMode" Value="1" />'
    '</Features><Resources><Resource Name="ApplicationId" Access="remote"><Location AddressSpace="SystemProperty" '
    'InterfaceObjectRef="3" PropertyID="13" /><ResourceType Length="5" /><AccessRights Read="Runtime" '
    'Write="Configuration" /></Resource><Resource Name="ManagementStyle" Access="remote"><Location '
    'AddressSpace="Constant" StartAddress="2" /><ResourceType Length="1" /></Resource></Resources><Procedures>'
    '<Procedure ProcedureType="Load" ProcedureSubType="all" Access="remote"><LdCtrlConnect /><LdCtrlUnload '
    'LsmIdx="4" />%s<LdCtrlDisconnect /></Procedure></Procedures>')

# The configuration of MV-FFF1 by memory (the legacy one of the old master data).
TEST_MASK_MEMORY = ('<Resources><Resource Name="ManagementStyle" Access="remote"><Location AddressSpace="Constant" '
    'StartAddress="1" /><ResourceType Length="1" /></Resource></Resources><Procedures><Procedure ProcedureType="Load" '
    'ProcedureSubType="all" Access="remote"><LdCtrlConnect /><LdCtrlWriteMem Address="256" Size="4" Verify="true" '
    'InlineData="01020304" /><LdCtrlDisconnect /></Procedure></Procedures><MemorySegments><MemorySegment '
    'MemoryType="EEPROM" Length="256"><Location AddressSpace="StandardMemory" StartAddress="256" /></MemorySegment>'
    '</MemorySegments>')


def test_masks(new):
    properties = TEST_MASK_PROPERTIES % ((5, '<LdCtrlLoadCompleted LsmIdx="4" />') if new else (4, ''))
    if new:
        configurations = ('<HawkConfigurationData>' + properties + '</HawkConfigurationData>'
            '<HawkConfigurationData LegacyVersion="true">' + TEST_MASK_MEMORY + '</HawkConfigurationData>')
    else:
        configurations = ('<HawkConfigurationData>' + TEST_MASK_MEMORY + '</HawkConfigurationData>'
            '<HawkConfigurationData LegacyVersion="true">' + properties + '</HawkConfigurationData>')
    return ('      <MaskVersion Id="MV-FFF1" MaskVersion="65521" Name="TEST Maske A (künstliche Testdaten)" '
        'ManagementModel="BimM112" MediumTypeRefId="MT-0"><DownwardCompatibleMasks><DownwardCompatibleMask '
        'RefId="MV-FFF2" /></DownwardCompatibleMasks><MaskEntries><MaskEntry Id="MV-FFF1_ME-Test" Name="Test" '
        'Address="4660" /></MaskEntries>' + configurations + '</MaskVersion>\n'
        '      <MaskVersion Id="MV-FFF2" MaskVersion="65522" Name="TEST Maske B (künstliche Testdaten)" '
        'ManagementModel="BimM112" MediumTypeRefId="MT-0" MgmtDescriptor01="0102"><HawkConfigurationData>'
        '<Features><Feature Name="FirstAppObjectIdx" Value="1" /></Features></HawkConfigurationData>'
        '</MaskVersion>\n')


def master_data_only(target, version, new):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        info = zipfile.ZipInfo('knx_master.xml', date_time=(2026, 10, 17, 12, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, (MASTER_DATA_HEADER % version + test_masks(new) + MASTER_DATA_FOOTER).encode('utf-8'))


# TestChannels.knxproj: channel descriptions of a device and renamed blocks (haumacher/ehome #122), see
# the module comment.
CHANNEL_PROGRAM = 'M-0008_A-FFF9-01-0000'
CHANNEL_HARDWARE = 'M-0008_H-TEST.20KANAL-1'
CHANNEL_PRODUCT = CHANNEL_HARDWARE + '_P-TEST.20KANAL'
CHANNEL_ASSIGNMENT = CHANNEL_HARDWARE + '_HP-FFF9-01-0000'


def bcu1_program_file(p, number, name, static, module_defs, dynamic, languages='', script=''):
    """An application program of the BCU 1 kind (memory of "TEST BCU1 mit Daten"), schema 23.

    The script (a Script element) goes where the schema places it, after the association table.
    """
    segment = p + '_AS-0100'
    data, mask = bcu1_memory()
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/23" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + name + '" ApplicationNumber="' + str(number) + '" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="6.0" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="{s}" Address="256" Size="256" UserMemory="0"><Data>' + data
        + '</Data><Mask>' + mask + '</Mask></AbsoluteSegment></Code>\n' + static
        + '<AddressTable CodeSegment="{s}" Offset="22" MaxEntries="6" />\n'
        '<AssociationTable CodeSegment="{s}" Offset="37" MaxEntries="8" />\n' + script
        + '<Options ParameterByteOrder="BigEndian" />\n'
        '</Static>\n' + module_defs + dynamic
        + '</ApplicationProgram>\n</ApplicationPrograms>' + languages + '</Manufacturer></ManufacturerData>\n</KNX>\n')
    return xml.replace('{s}', segment).replace('{p}', p)


def test_hardware(hardware, product, name, assignments):
    """The hardware of artificial programs: (assignment ID, program ID) per program."""
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/23" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><Hardware>'
        '<Hardware Id="' + hardware + '" Name="' + name + '" SerialNumber="' + name + '" VersionNumber="1" '
        'HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="' + product + '" '
        'Text="' + name + ' (künstliche Testdaten)" OrderNumber="' + name + '" IsRailMounted="1" DefaultLanguage="de-DE" />'
        '</Products><Hardware2Programs>'
        + ''.join('<Hardware2Program Id="%s" MediumTypes="MT-0"><ApplicationProgramRef RefId="%s" /></Hardware2Program>'
            % assignment for assignment in assignments)
        + '</Hardware2Programs></Hardware></Hardware></Manufacturer></ManufacturerData>\n</KNX>\n')


SWITCH_TYPE = ('<ParameterType Id="{p}_PT-Funktion" Name="Funktion"><TypeRestriction Base="Value" SizeInBit="8">'
    '<Enumeration Id="{p}_PT-Funktion_EN-0" Text="Aus" Value="0" DisplayOrder="0" />'
    '<Enumeration Id="{p}_PT-Funktion_EN-1" Text="Ein" Value="1" DisplayOrder="1" />'
    '</TypeRestriction></ParameterType>\n')


def channel_program():
    flags = ('Priority="Low" CommunicationFlag="Enabled" ReadFlag="Disabled" WriteFlag="Enabled" '
        'TransmitFlag="Disabled" UpdateFlag="Disabled" ReadOnInitFlag="Disabled"')
    static = ('<ParameterTypes>\n' + SWITCH_TYPE + '</ParameterTypes>\n'
        '<Parameters>\n<Parameter Id="{p}_P-1" Name="Funktion" Text="Funktion" ParameterType="{p}_PT-Funktion" '
        'Access="ReadWrite" Value="0"><Memory CodeSegment="{s}" Offset="88" BitOffset="0" /></Parameter>\n</Parameters>\n'
        '<ParameterRefs>\n<ParameterRef Id="{p}_P-1_R-1" RefId="{p}_P-1" />\n</ParameterRefs>\n'
        '<ComObjectTable CodeSegment="{s}" Offset="54">\n<ComObject Id="{p}_O-0" Name="LED" Text="LED" Number="0" '
        'ObjectSize="1 Bit" ' + flags + ' DatapointType="DPST-1-1" />\n</ComObjectTable>\n'
        '<ComObjectRefs>\n<ComObjectRef Id="{p}_O-0_R-1" RefId="{p}_O-0" />\n</ComObjectRefs>\n')
    module = ('<ModuleDefs><ModuleDef Id="{p}_MD-1" Name="Modul"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamOffsBase" /><Argument Id="{p}_MD-1_A-2" Name="No" /></Arguments>\n'
        '<Static><Parameters><Parameter Id="{p}_MD-1_P-1" Name="Wert" Text="Modul {{No}}: Wert" '
        'ParameterType="{p}_PT-Funktion" Access="ReadWrite" Value="0"><Memory CodeSegment="{s}" Offset="0" '
        'BitOffset="0" BaseOffset="{p}_MD-1_A-1" /></Parameter></Parameters>\n'
        '<ParameterRefs><ParameterRef Id="{p}_MD-1_P-1_R-1" RefId="{p}_MD-1_P-1" /></ParameterRefs></Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Modul" Text="Modul {{No}}">'
        '<ParameterBlock Id="{p}_MD-1_PB-1" Name="Wert" Text="Wert"><ParameterRefRef RefId="{p}_MD-1_P-1_R-1" />'
        '<choose ParamRefId="{p}_MD-1_P-1_R-1"><when test="1"><Rename Id="{p}_MD-1_PR-1" RefId="{p}_MD-1_PB-1" '
        'Text="Wert {{No}} (ein)" /></when></choose></ParameterBlock></Channel></Dynamic>'
        '</ModuleDef></ModuleDefs>\n')
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="KanalA" Text="Kanal A" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein"><ParameterRefRef RefId="{p}_P-1_R-1" />'
        '<choose ParamRefId="{p}_P-1_R-1"><when test="1"><Rename Id="{p}_PR-1" RefId="{p}_PB-2" '
        'Text="LED oben rechts" /></when></choose></ParameterBlock>'
        '<ParameterBlock Id="{p}_PB-2" Name="LED" Text="LED"><ComObjectRefRef RefId="{p}_O-0_R-1" /></ParameterBlock>'
        '</Channel>\n'
        '<Channel Id="{p}_CH-2" Name="KanalB" Text="Kanal B" Number="2"><ParameterBlock Id="{p}_PB-3" Name="Reserve" '
        'Text="Reserve" /></Channel>\n'
        '<choose ParamRefId="{p}_P-1_R-1"><when test="1"><Rename Id="{p}_PR-2" RefId="{p}_CH-2" Text="Kanal B (frei)" />'
        '</when><when default="true" /></choose>\n'
        '<Module Id="{p}_MD-1_M-1" RefId="{p}_MD-1" Name="Modul 1"><NumericArg RefId="{p}_MD-1_A-1" Value="96" />'
        '<TextArg RefId="{p}_MD-1_A-2" Value="1" /></Module>\n</Dynamic>\n')
    english = [('{p}_PR-1', 'LED top right'), ('{p}_CH-1', 'Channel A'), ('{p}_PR-2', 'Channel B (free)')]
    languages = ('<Languages><Language Identifier="en-US"><TranslationUnit RefId="{p}" Version="0">'
        + ''.join('<TranslationElement RefId="%s"><Translation AttributeName="Text" Text="%s" /></TranslationElement>'
            % entry for entry in english)
        + '</TranslationUnit></Language></Languages>')
    return bcu1_program_file(CHANNEL_PROGRAM, 65529, 'TEST: Kanäle und Umbenennungen (künstliche Testdaten)',
        static, module, dynamic, languages)


def channel_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    p = CHANNEL_PROGRAM
    instance = ('<ModuleInstances><ModuleInstance Id="MD-1_M-1_MI-1" RefId="MD-1_M-1"><Arguments>'
        '<Argument RefId="MD-1_A-1" Value="96" /><Argument RefId="MD-1_A-2" Value="1" /></Arguments>'
        '</ModuleInstance></ModuleInstances>\n')
    devices = ('<DeviceInstance Id="P-01A0-0_DI-1" Name="TEST Kanalgerät" ProductRefId="' + CHANNEL_PRODUCT
        + '" Hardware2ProgramRefId="' + CHANNEL_ASSIGNMENT + '" Address="1" LastModified="2026-11-08T12:00:00">\n'
        '<ParameterInstanceRefs>\n<ParameterInstanceRef RefId="P-1_R-1" Value="1" />\n'
        '<ParameterInstanceRef RefId="' + p + '_MD-1_M-1_MI-1_P-1_R-1" Value="1" />\n</ParameterInstanceRefs>\n'
        + instance +
        '<ChannelInstances>\n'
        '<ChannelInstance Id="P-01A0-0_DI-1_CI-1" Description="Binäreingang Küchentür" IsActive="true" RefId="CH-1" />\n'
        '<ChannelInstance Id="P-01A0-0_DI-1_CI-2" Description="Reserve" IsActive="false" RefId="CH-2" />\n'
        '<ChannelInstance Id="P-01A0-0_DI-1_CI-3" Description="Modulkanal Flur" IsActive="true" '
        'RefId="MD-1_M-1_MI-1_CH-1" />\n'
        '</ChannelInstances>\n'
        '<Security LoadedToolKey="false" SequenceNumber="0" />\n'
        '</DeviceInstance>\n'
        '<DeviceInstance Id="P-01A0-0_DI-2" Name="TEST Kanalgerät ohne" ProductRefId="' + CHANNEL_PRODUCT
        + '" Hardware2ProgramRefId="' + CHANNEL_ASSIGNMENT + '" Address="2" LastModified="2026-11-08T12:00:00">\n'
        + instance + '</DeviceInstance>')
    return xml[:start] + devices + xml[end:]


def channels(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        xml = rename_project(xml, 'TestChannels')
        return replace_once(xml, '  </Project>', '    <ProjectTraces><ProjectTrace Date="2026-11-08T12:00:00" '
            'UserName="TEST" Comment="künstliche Testdaten" /></ProjectTraces>\n  </Project>')
    if name == PROJECT:
        return channel_devices(xml)
    return xml


# TestCalculations.knxproj: parameter calculations (haumacher/ehome #123), see the module comment.
CALC_PROGRAM = 'M-0008_A-FFFA-01-0000'
LOCKED_PROGRAM = 'M-0008_A-FFFB-01-0000'
CALC_HARDWARE = 'M-0008_H-TEST.20RECHNUNG-1'
CALC_PRODUCT = CALC_HARDWARE + '_P-TEST.20RECHNUNG'
CALC_ASSIGNMENT = CALC_HARDWARE + '_HP-FFFA-01-0000'
LOCKED_ASSIGNMENT = CALC_HARDWARE + '_HP-FFFB-01-0000'
# A line break in the code of a transformation.
NL = '\n'


def number_type(name, bits, low, high):
    return ('<ParameterType Id="{p}_PT-%s" Name="%s"><TypeNumber SizeInBit="%d" Type="unsignedInt" '
        'minInclusive="%d" maxInclusive="%d" /></ParameterType>\n' % (name, name, bits, low, high))


def parameter(ident, name, type_name, value, offset=None, base=None):
    """A parameter: an L parameter without memory, an R parameter (access None) at the given offset."""
    if offset is None:
        return ('<Parameter Id="{p}_%s" Name="%s" Text="%s" ParameterType="{p}_PT-%s" Access="ReadWrite" '
            'Value="%d" />' % (ident, name, name, type_name, value))
    return ('<Parameter Id="{p}_%s" Name="%s" Text="%s" ParameterType="{p}_PT-%s" Access="None" Value="%d">'
        '<Memory CodeSegment="{s}" Offset="%d" BitOffset="0"%s /></Parameter>'
        % (ident, name, name, type_name, value, offset, '' if base is None else ' BaseOffset="{p}_%s"' % base))


def xml_text(text):
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


def calculation(ident, name, language, lr, rl, inputs, outputs, functions=None):
    """A calculation: the parameter references of each side as (RefId, AliasName or None).

    The code inline as ETS 6 writes it (RLTransformation before LRTransformation, then the parameters), or,
    with functions = (LR function, its parameters, RL function, its parameters), as functions of the
    program's script named by the attributes LRTransformationFunc and RLTransformationFunc.
    """
    def refs(side):
        return ''.join('<ParameterRefRef RefId="{p}_%s"%s />' % (ref, '' if alias is None else ' AliasName="%s"' % alias)
            for ref, alias in side)
    if functions is None:
        return ('<ParameterCalculation Id="{p}_%s" Name="%s" Language="%s">\n'
            '<RLTransformation>%s</RLTransformation>\n<LRTransformation>%s</LRTransformation>\n'
            '<LParameters>%s</LParameters><RParameters>%s</RParameters></ParameterCalculation>\n'
            % (ident, name, language, xml_text(rl), xml_text(lr), refs(inputs), refs(outputs)))
    lr_function, lr_parameters, rl_function, rl_parameters = functions
    return ('<ParameterCalculation Id="{p}_%s" Name="%s" Language="%s" LRTransformationFunc="%s" '
        'RLTransformationFunc="%s" LRTransformationParameters="%s" RLTransformationParameters="%s">'
        '<LParameters>%s</LParameters><RParameters>%s</RParameters></ParameterCalculation>\n'
        % (ident, name, language, lr_function, rl_function, xml_text(lr_parameters), xml_text(rl_parameters),
            refs(inputs), refs(outputs)))


def bcu1_objects():
    """The three objects of the object table of the BCU 1 memory, placed nowhere."""
    flags = ('Priority="Low" CommunicationFlag="Enabled" ReadFlag="Disabled" WriteFlag="Enabled" '
        'TransmitFlag="Disabled" UpdateFlag="Disabled" ReadOnInitFlag="Disabled"')
    return ('<ComObjectTable CodeSegment="{s}" Offset="54">\n'
        + ''.join('<ComObject Id="{p}_O-%d" Name="Objekt %d" Text="Objekt %d" Number="%d" ObjectSize="1 Bit" %s />\n'
            % (n, n, n, n, flags) for n in range(3))
        + '</ComObjectTable>\n<ComObjectRefs>\n'
        + ''.join('<ComObjectRef Id="{p}_O-%d_R-1" RefId="{p}_O-%d" />\n' % (n, n) for n in range(3))
        + '</ComObjectRefs>\n')


def calc_program():
    types = (number_type('Dauer', 32, 0, 86399) + number_type('Std', 8, 0, 23) + number_type('Min', 8, 0, 59)
        + number_type('Byte', 8, 0, 255) + number_type('Klein', 8, 0, 10) + number_type('Zeit', 16, 0, 1000)
        + number_type('Teiler', 8, 1, 100)
        + '<ParameterType Id="{p}_PT-Schwelle" Name="Schwelle"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Schwelle_EN-1" Text="Niedrig" Value="1" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Schwelle_EN-2" Text="Mittel" Value="2" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Schwelle_EN-3" Text="Hoch" Value="3" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n')
    params = [('P-1', 'Dauer', 'Dauer', 60, None), ('P-2', 'Dauer_h', 'Std', 0, 0x58), ('P-3', 'Dauer_min', 'Min', 1, 0x59),
        ('P-4', 'Dauer_s', 'Min', 0, 0x5A), ('P-5', 'Schwelle', 'Schwelle', 3, None), ('P-6', 'Szene', 'Byte', 0, 0x5B),
        ('P-7', 'Fehlerwert', 'Klein', 1, None), ('P-8', 'FehlerR', 'Byte', 30, 0x5C)]
    calculations = (
        calculation('PC-1', 's2hms', 'VBScript',
            'Dauer_h = (Dauer \\ 3600)' + NL + 'Dauer_min = (Dauer - (3600 * Dauer_h)) \\ 60' + NL
            + 'Dauer_s = Dauer - (3600 * Dauer_h) - (60 * Dauer_min)',
            'Dauer = (3600 * Dauer_h) + (60 * Dauer_min) + Dauer_s',
            [('P-1_R-1', None)], [('P-2_R-1', None), ('P-3_R-1', None), ('P-4_R-1', None)])
        + calculation('PC-2', 'Szene', 'JavaScript', None, None,
            [('P-5_R-1', 'BlindsActionThreshold')], [('P-6_R-1', 'BlindScene')],
            ('szeneLR', '{"Faktor": 1}', 'szeneRL', ''))
        + calculation('PC-3', 'Fehler', 'JavaScript',
            "if (x == 7) { throw new Error('kaputt'); }" + NL + 'if (x == 9) { while (true) {} }' + NL + 'r = x * 30;',
            'x = r / 30;', [('P-7_R-1', 'x')], [('P-8_R-1', 'r')]))
    script = ('<Script>' + xml_text('function verdoppeln(x) { return x * 2; }\n'
        'function szeneLR(input, output, context) { if (input.BlindsActionThreshold == 3) { '
        'output.BlindScene = verdoppeln(context.Faktor); } }\n'
        'function szeneRL(input, output, context) { if (input.BlindScene == 2) { output.BlindsActionThreshold = 3; } }\n'
        'function ParameterCalculationFunc1(input, output, context) {}\n') + '</Script>\n')
    static = ('<ParameterTypes>\n' + types + '</ParameterTypes>\n<Parameters>\n'
        + '\n'.join(parameter(i, n, t, v, o) for i, n, t, v, o in params) + '\n</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_%s_R-1" RefId="{p}_%s" />\n' % (i, i) for i, _, _, _, _ in params)
        + '</ParameterRefs>\n' + bcu1_objects()
        + '<ParameterCalculations>\n' + calculations + '</ParameterCalculations>\n' + script)
    module_params = [('MD-1_P-1', 'Zeit', 'Zeit', 50, None), ('MD-1_P-2', 'Teiler', 'Teiler', 10, None),
        ('MD-1_P-3', 'Stufe', 'Byte', 0, 0), ('MD-1_P-4', 'Rest', 'Byte', 5, 1)]
    module = ('<ModuleDefs><ModuleDef Id="{p}_MD-1" Name="Teil"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamOffsBase" /></Arguments>\n<Static><Parameters>'
        + ''.join(parameter(i, n, t, v, o, None if o is None else 'MD-1_A-1') for i, n, t, v, o in module_params)
        + '</Parameters>\n<ParameterRefs>'
        + ''.join('<ParameterRef Id="{p}_%s_R-1" RefId="{p}_%s" />' % (i, i) for i, _, _, _, _ in module_params)
        + '</ParameterRefs>\n<ParameterCalculations>'
        + calculation('MD-1_PC-1', 'Stufen', 'JavaScript',
            'p2 = p1 / p3; if (p2 < 1) p2 = 1; p4 = p1 / p3; if (p4 < 1) p4 = 0', 'p1 = mal(p2, p3)',
            [('MD-1_P-1_R-1', 'p1'), ('MD-1_P-2_R-1', 'p3')], [('MD-1_P-3_R-1', 'p2'), ('MD-1_P-4_R-1', 'p4')])
        + '</ParameterCalculations><Script>function mal(a, b) { return a * b; }</Script></Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Teil" Text="Teil"><ParameterBlock Id="{p}_MD-1_PB-1" Name="Teil" '
        'Text="Teil">'
        + ''.join('<ParameterRefRef RefId="{p}_%s_R-1" />' % i for i, _, _, _, _ in module_params)
        + '</ParameterBlock></Channel></Dynamic></ModuleDef></ModuleDefs>\n')
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="Allgemein" Text="Allgemein" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        + ''.join('<ParameterRefRef RefId="{p}_%s_R-1" />' % i for i, _, _, _, _ in params)
        + '</ParameterBlock></Channel>\n'
        + ''.join('<Module Id="{p}_MD-1_M-%d" RefId="{p}_MD-1" Name="Teil %d"><NumericArg RefId="{p}_MD-1_A-1" '
            'Value="%d" /></Module>\n' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
        + '</Dynamic>\n')
    return bcu1_program_file(CALC_PROGRAM, 65530, 'TEST: Parameterberechnungen (künstliche Testdaten)', static,
        module, dynamic)


def locked_program():
    static = ('<ParameterTypes>\n' + number_type('Byte', 8, 0, 255) + '</ParameterTypes>\n<Parameters>\n'
        + parameter('P-1', 'Gesperrt', 'Byte', 0) + '\n' + parameter('P-2', 'GesperrtR', 'Byte', 0, 0x58) + '\n'
        + parameter('P-3', 'Minuten', 'Byte', 1) + '\n' + parameter('P-4', 'Sekunden', 'Byte', 60, 0x59)
        + '\n</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_P-%d_R-1" RefId="{p}_P-%d" />\n' % (n, n) for n in range(1, 5))
        + '</ParameterRefs>\n' + bcu1_objects() + '<ParameterCalculations>\n'
        + calculation('PC-1', 'Auswahl', 'VBScript',
            'Select Case Gesperrt' + NL + 'Case 1' + NL + 'GesperrtR = 1' + NL + 'End Select', 'Gesperrt = GesperrtR',
            [('P-1_R-1', None)], [('P-2_R-1', None)])
        + calculation('PC-2', 'Sekunden', 'VBScript', 'Sekunden = InSekunden(Minuten)', 'Minuten = Sekunden \\ 60',
            [('P-3_R-1', None)], [('P-4_R-1', None)])
        + '</ParameterCalculations>\n<Script>' + xml_text('Function InSekunden(m)' + NL + '  InSekunden = m * 60'
            + NL + 'End Function') + '</Script>\n')
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="Allgemein" Text="Allgemein" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        + ''.join('<ParameterRefRef RefId="{p}_P-%d_R-1" />' % n for n in range(1, 5))
        + '</ParameterBlock></Channel></Dynamic>\n')
    return bcu1_program_file(LOCKED_PROGRAM, 65531, 'TEST: nicht auswertbare Berechnung (künstliche Testdaten)',
        static, '', dynamic)


def calc_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')

    def device(number, name, assignment, values, instances):
        return ('<DeviceInstance Id="P-01A0-0_DI-%d" Name="%s" ProductRefId="%s" Hardware2ProgramRefId="%s" '
            'Address="%d" LastModified="2026-11-08T12:00:00">\n<ParameterInstanceRefs>\n' % (number, name, CALC_PRODUCT,
                assignment, number)
            + ''.join('<ParameterInstanceRef RefId="%s" Value="%s" />\n' % entry for entry in values)
            + '</ParameterInstanceRefs>\n'
            + ('' if not instances else '<ModuleInstances>' + ''.join(
                '<ModuleInstance Id="MD-1_M-%d_MI-1" RefId="MD-1_M-%d"><Arguments><Argument RefId="MD-1_A-1" '
                'Value="%d" /></Arguments></ModuleInstance>' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
                + '</ModuleInstances>\n')
            + '</DeviceInstance>')
    p = CALC_PROGRAM
    consistent = device(1, 'TEST Rechengerät', CALC_ASSIGNMENT, [('P-1_R-1', '3725'), ('P-2_R-1', '1'),
        ('P-3_R-1', '2'), ('P-4_R-1', '5'), ('P-6_R-1', '2'), (p + '_MD-1_M-1_MI-1_P-1_R-1', '55'),
        (p + '_MD-1_M-1_MI-1_P-3_R-1', '5'), (p + '_MD-1_M-2_MI-1_P-3_R-1', '5')], True)
    r_only = device(2, 'TEST Rechengerät RL', CALC_ASSIGNMENT, [('P-2_R-1', '2'), ('P-3_R-1', '0'), ('P-4_R-1', '30'),
        (p + '_MD-1_M-1_MI-1_P-3_R-1', '7')], True)
    locked = device(3, 'TEST Rechengerät gesperrt', LOCKED_ASSIGNMENT, [('P-2_R-1', '1')], False)
    return xml[:start] + consistent + '\n' + r_only + '\n' + locked + xml[end:]


def calculations_fixture(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestCalculations')
    if name == PROJECT:
        return calc_devices(xml)
    return xml


# TestValidations.knxproj: parameter validations (haumacher/ehome #124), see the module comment.
CHECK_PROGRAM = 'M-0008_A-FFFC-01-0000'
VB_CHECK_PROGRAM = 'M-0008_A-FFFD-01-0000'
CHECK_HARDWARE = 'M-0008_H-TEST.20PRUEFUNG-1'
CHECK_PRODUCT = CHECK_HARDWARE + '_P-TEST.20PRUEFUNG'
CHECK_ASSIGNMENT = CHECK_HARDWARE + '_HP-FFFC-01-0000'
VB_CHECK_ASSIGNMENT = CHECK_HARDWARE + '_HP-FFFD-01-0000'


def validation(ident, name, function, refs, parameters=None, description=None):
    """A validation in the shape of the KNX schema: the parameter references as (RefId,
    AliasName or None)."""
    return ('<ParameterValidation Id="{p}_%s" Name="%s" ValidationFunc="%s"%s%s>\n<Parameters>\n' % (ident, name,
            function, '' if parameters is None else ' ValidationParameters="%s"' % xml_text(parameters),
            '' if description is None else ' InternalDescription="%s"' % xml_text(description))
        + ''.join('<ParameterRefRef RefId="{p}_%s"%s />\n' % (ref, '' if alias is None else ' AliasName="%s"' % alias)
            for ref, alias in refs)
        + '</Parameters>\n</ParameterValidation>\n')


# The calling convention of the functions (validations: function(input, changed, prevValue, context);
# calculations: function(input, output, context), the same alias on both sides) is that of the programs in the
# field, e.g. OpenKNX OFM-PresenceModule (src/PMmodul.script.js), OFM-AccessControl
# (src/AccessControl.script.js) and OGM-Common (src/Common.script.js); the code below is artificial.
CHECK_SCRIPT = (
    'function TEST_abstand(input, changed, prevValue, context) {\n'
    '    if (input.Low < input.High) {\n'
    '        return true;\n'
    '    }\n'
    '    return "Die Untergrenze muss unter der Obergrenze liegen.";\n'
    '}\n\n'
    # A function of a button (Button@EventHandler), which E-Home does not call, and a top-level variable.
    'function TEST_knopf(device, online, progress, context) {\n'
    '    device.getParameterByName("TEST_Knopf").value = 0;\n'
    '}\n\n'
    'var TEST_stufen = [100, 1000];\n\n'
    'function TEST_kapazitaet(input, changed, prevValue, context) {\n'
    '    var max = TEST_stufen[input.Modell];\n'
    '    if (input.Nummer <= max) {\n'
    '        return true;\n'
    '    }\n'
    '    return changed == "Nummer" ? "Nummer " + input.Nummer + " liegt über " + max + "."\n'
    '        : "Das Modell erlaubt höchstens " + max + " Nummern.";\n'
    '}\n\n'
    'function TEST_helligkeit(input, changed, prevValue, context) {\n'
    '    return input.Helligkeit <= context.Max || ("Höchstens " + context.Max + " (bisher " + prevValue + ")");\n'
    '}\n\n'
    'function TEST_fehler(input, changed, prevValue, context) {\n'
    '    if (input.Wert == 7) { throw new Error("kaputt"); }\n'
    '    return input.Wert != 8;\n'
    '}\n\n'
    'function TEST_typ(input, changed, prevValue, context) {\n'
    '    return input.Art != 4 || "Die Art 4 gibt es nicht.";\n'
    '}\n\n'
    'function TEST_uebernehmen(input, output, context) {\n'
    '    output.Art = input.Art;\n'
    '}\n\n'
    'function TEST_kanal(input, changed, prevValue, context) {\n'
    '    return input.Ein < input.Aus || "Ein muss kleiner als Aus sein.";\n'
    '}\n')


def check_program():
    types = (number_type('Weite', 8, 0, 100) + number_type('Nummer', 16, 0, 2000) + number_type('Byte', 8, 0, 255)
        + number_type('Klein', 8, 0, 10) + number_type('Typ', 8, 0, 5)
        + '<ParameterType Id="{p}_PT-Modell" Name="Modell"><TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Modell_EN-0" Text="Basis" Value="0" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Modell_EN-1" Text="Profi" Value="1" DisplayOrder="1" />'
        '</TypeRestriction></ParameterType>\n')
    params = [('P-1', 'Untergrenze', 'Weite', 1, None), ('P-2', 'Obergrenze', 'Weite', 5, None),
        ('P-3', 'Modell', 'Modell', 0, None), ('P-4', 'Nummer', 'Nummer', 1, None),
        ('P-5', 'Helligkeit', 'Byte', 10, None), ('P-6', 'Fehlerwert', 'Klein', 0, None),
        ('P-7', 'Typ', 'Typ', 0, None), ('P-8', 'TypR', 'Byte', 0, 0x58)]
    validations = (
        validation('PV-1', 'Abstand', 'TEST_abstand', [('P-1_R-1', 'Low'), ('P-2_R-1', 'High')],
            description='Untergrenze unter Obergrenze')
        + validation('PV-2', 'Kapazitaet', 'TEST_kapazitaet', [('P-3_R-1', 'Modell'), ('P-4_R-1', 'Nummer')])
        + validation('PV-3', 'Helligkeit', 'TEST_helligkeit', [('P-5_R-1', None)], '{"Max": 50}')
        + validation('PV-4', 'Fehler', 'TEST_fehler', [('P-6_R-1', 'Wert')])
        + validation('PV-5', 'Typ', 'TEST_typ', [('P-7_R-1', 'Art')]))
    calculations = calculation('PC-1', 'Typ', 'JavaScript', None, None, [('P-7_R-1', 'Art')],
        [('P-8_R-1', 'Art')], ('TEST_uebernehmen', '', 'TEST_uebernehmen', ''))
    static = ('<ParameterTypes>\n' + types + '</ParameterTypes>\n<Parameters>\n'
        + '\n'.join(parameter(i, n, t, v, o) for i, n, t, v, o in params) + '\n</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_%s_R-1" RefId="{p}_%s" />\n' % (i, i) for i, _, _, _, _ in params)
        + '</ParameterRefs>\n<ParameterCalculations>\n' + calculations + '</ParameterCalculations>\n'
        + '<ParameterValidations>\n' + validations + '</ParameterValidations>\n' + bcu1_objects())
    module_params = [('MD-1_P-1', 'Ein', 'Weite', 10, None), ('MD-1_P-2', 'Aus', 'Weite', 20, None)]
    module = ('<ModuleDefs><ModuleDef Id="{p}_MD-1" Name="Kanal"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamOffsBase" /></Arguments>\n<Static><Parameters>'
        + ''.join(parameter(i, n, t, v, o) for i, n, t, v, o in module_params)
        + '</Parameters>\n<ParameterRefs>'
        + ''.join('<ParameterRef Id="{p}_%s_R-1" RefId="{p}_%s" />' % (i, i) for i, _, _, _, _ in module_params)
        + '</ParameterRefs>\n<ParameterValidations>\n'
        + validation('MD-1_PV-1', 'EinVorAus', 'TEST_kanal', [('MD-1_P-1_R-1', 'Ein'), ('MD-1_P-2_R-1', 'Aus')])
        + '</ParameterValidations>\n</Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Kanal" Text="Kanal"><ParameterBlock Id="{p}_MD-1_PB-1" '
        'Name="Kanal" Text="Kanal">'
        + ''.join('<ParameterRefRef RefId="{p}_%s_R-1" />' % i for i, _, _, _, _ in module_params)
        + '</ParameterBlock></Channel></Dynamic></ModuleDef></ModuleDefs>\n')
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="Allgemein" Text="Allgemein" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        + ''.join('<ParameterRefRef RefId="{p}_%s_R-1" />' % i for i, _, _, _, _ in params)
        + '</ParameterBlock></Channel>\n'
        + ''.join('<Module Id="{p}_MD-1_M-%d" RefId="{p}_MD-1" Name="Kanal %d"><NumericArg RefId="{p}_MD-1_A-1" '
            'Value="%d" /></Module>\n' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
        + '</Dynamic>\n')
    return bcu1_program_file(CHECK_PROGRAM, 65532, 'TEST: Parameterprüfungen (künstliche Testdaten)', static, module,
        dynamic, script='<Script>' + xml_text(CHECK_SCRIPT) + '</Script>\n')


VB_CHECK_SCRIPT = NL.join([
    'Function PruefeBereich(input, changed, prevValue, context)',
    '  If input.Von > input.Bis Then',
    '    If changed = "Von" Then',
    '      PruefeBereich = "Von (" & input.Von & ") darf nicht größer als Bis sein, bisher " & prevValue',
    '    Else',
    '      PruefeBereich = "Bis darf nicht kleiner als Von sein"',
    '    End If',
    '  Else',
    '    PruefeBereich = True',
    '  End If',
    'End Function',
    'Function PruefeGrenze(input, changed, prevValue, context)',
    '  PruefeGrenze = input.Wert <= context.Grenze',
    'End Function'])


def vb_check_program():
    static = ('<ParameterTypes>\n' + number_type('Byte', 8, 0, 255) + '</ParameterTypes>\n<Parameters>\n'
        + parameter('P-1', 'Von', 'Byte', 1) + '\n' + parameter('P-2', 'Bis', 'Byte', 10) + '\n'
        + parameter('P-3', 'Wert', 'Byte', 5) + '\n' + parameter('P-4', 'Gesperrt', 'Byte', 0)
        + '\n</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_P-%d_R-1" RefId="{p}_P-%d" />\n' % (n, n) for n in range(1, 5))
        + '</ParameterRefs>\n<ParameterValidations>\n'
        + validation('PV-1', 'Bereich', 'PruefeBereich', [('P-1_R-1', 'Von'), ('P-2_R-1', 'Bis')])
        + validation('PV-2', 'Grenze', 'PruefeGrenze', [('P-3_R-1', None)], '{"Grenze": 50}')
        + validation('PV-3', 'Fehlt', 'PruefeFehlt', [('P-4_R-1', None)])
        + '</ParameterValidations>\n' + bcu1_objects())
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="Allgemein" Text="Allgemein" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        + ''.join('<ParameterRefRef RefId="{p}_P-%d_R-1" />' % n for n in range(1, 5))
        + '</ParameterBlock></Channel></Dynamic>\n')
    return bcu1_program_file(VB_CHECK_PROGRAM, 65533, 'TEST: Parameterprüfungen in VBScript (künstliche Testdaten)',
        static, '', dynamic, script='<Script>' + xml_text(VB_CHECK_SCRIPT) + '</Script>\n')


def check_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')

    def device(number, name, assignment, values, instances):
        return ('<DeviceInstance Id="P-01A0-0_DI-%d" Name="%s" ProductRefId="%s" Hardware2ProgramRefId="%s" '
            'Address="%d" LastModified="2026-11-08T12:00:00">\n<ParameterInstanceRefs>\n' % (number, name,
                CHECK_PRODUCT, assignment, number)
            + ''.join('<ParameterInstanceRef RefId="%s" Value="%s" />\n' % entry for entry in values)
            + '</ParameterInstanceRefs>\n'
            + ('' if not instances else '<ModuleInstances>' + ''.join(
                '<ModuleInstance Id="MD-1_M-%d_MI-1" RefId="MD-1_M-%d"><Arguments><Argument RefId="MD-1_A-1" '
                'Value="%d" /></Arguments></ModuleInstance>' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
                + '</ModuleInstances>\n')
            + '</DeviceInstance>')
    p = CHECK_PROGRAM
    valid = device(1, 'TEST Prüfgerät', CHECK_ASSIGNMENT, [('P-1_R-1', '2'), ('P-2_R-1', '8')], True)
    invalid = device(2, 'TEST Prüfgerät ungültig', CHECK_ASSIGNMENT, [('P-1_R-1', '9'), ('P-2_R-1', '3'),
        (p + '_MD-1_M-2_MI-1_P-1_R-1', '50')], True)
    basic = device(3, 'TEST Prüfgerät VBScript', VB_CHECK_ASSIGNMENT, [], False)
    return xml[:start] + valid + '\n' + invalid + '\n' + basic + xml[end:]


def validations_fixture(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestValidations')
    if name == PROJECT:
        return check_devices(xml)
    return xml


# TestButtons.knxproj: buttons of the parameter dialog (haumacher/ehome #158), see the module comment.
BUTTON_PROGRAM = 'M-0008_A-FFE2-01-0000'
BUTTON_HARDWARE = 'M-0008_H-TEST.20SCHALTFLAECHEN-1'
BUTTON_PRODUCT = BUTTON_HARDWARE + '_P-TEST.20SCHALTFLAECHEN'
BUTTON_ASSIGNMENT = BUTTON_HARDWARE + '_HP-FFE2-01-0000'

# The calling convention of the handlers (function(device, online, progress, context), a parameter by its ID or
# by its name with its value) is that of the programs in the field (MDT BE-GTSx6Tx-01S, GTL-xxxx-02S); the code
# below is artificial.
BUTTON_SCRIPT = (
    'function TEST_text(device, online, progress, context) {\n'
    '    device.GetParameterById("P-2_R-1").Value = device.GetParameterById("P-1_R-1").Value;\n'
    '}\n\n'
    'function TEST_anzahl(device, online, progress, context) {\n'
    '    var anzahl = device.getParameterByName("Anzahl").value;\n'
    '    device.GetParameterById("P-2_R-1").Value = "Anzahl " + anzahl;\n'
    '    device.getParameterByName("Kopie").value = anzahl + context.Plus;\n'
    '}\n\n'
    'function TEST_unbekannt(device, online, progress, context) {\n'
    '    device.GetParameterById("P-1_R-1").Text = "x";\n'
    '}\n\n'
    'function TEST_versteckt(device, online, progress, context) {\n'
    '    device.GetParameterById("P-2_R-1").Value = "geändert";\n'
    '    var quelle = device.GetParameterById("P-1_R-1");\n'
    '    return quelle.Text;\n'
    '}\n\n'
    'function TEST_geraet(device, online, progress, context) {\n'
    '    online.connect();\n'
    '}\n\n'
    'function TEST_hoechstens(input, changed, prevValue, context) {\n'
    '    return input.Kopie <= 50 || "Die Kopie darf höchstens 50 sein.";\n'
    '}\n\n'
    'function TEST_modul(device, online, progress, context) {\n'
    '    device.GetParameterById("MD-1_P-2_R-1").Value = device.GetParameterById("MD-1_P-1_R-1").Value;\n'
    '}\n')


def button(ident, text, handler, parameters=None):
    return ('<Button Id="{p}_%s" Text="%s" EventHandler="%s"%s />' % (ident, text, handler,
        '' if parameters is None else ' EventHandlerParameters="%s"' % xml_text(parameters)))


def button_program():
    types = (number_type('Weite', 8, 0, 100)
        + '<ParameterType Id="{p}_PT-Text" Name="Text"><TypeText SizeInBit="112" /></ParameterType>\n')
    static = ('<ParameterTypes>\n' + types + '</ParameterTypes>\n<Parameters>\n'
        '<Parameter Id="{p}_P-1" Name="Quelle" Text="Quelle" ParameterType="{p}_PT-Text" Access="ReadWrite" '
        'Value="leer" />\n'
        '<Parameter Id="{p}_P-2" Name="Ziel" Text="Ziel" ParameterType="{p}_PT-Text" Access="ReadWrite" Value="" />\n'
        + parameter('P-3', 'Anzahl', 'Weite', 5) + '\n' + parameter('P-4', 'Kopie', 'Weite', 0)
        + '\n</Parameters>\n<ParameterRefs>\n'
        + ''.join('<ParameterRef Id="{p}_P-%d_R-1" RefId="{p}_P-%d" />\n' % (n, n) for n in range(1, 5))
        + '</ParameterRefs>\n<ParameterValidations>\n'
        + validation('PV-1', 'Hoechstens', 'TEST_hoechstens', [('P-4_R-1', 'Kopie')])
        + '</ParameterValidations>\n' + bcu1_objects())
    module_params = [('MD-1_P-1', 'Wert', 'Weite', 1, None), ('MD-1_P-2', 'Kopie', 'Weite', 0, None)]
    module = ('<ModuleDefs><ModuleDef Id="{p}_MD-1" Name="Kanal"><Arguments>'
        '<Argument Id="{p}_MD-1_A-1" Name="ParamOffsBase" /></Arguments>\n<Static><Parameters>'
        + ''.join(parameter(i, n, t, v, o) for i, n, t, v, o in module_params)
        + '</Parameters>\n<ParameterRefs>'
        + ''.join('<ParameterRef Id="{p}_%s_R-1" RefId="{p}_%s" />' % (i, i) for i, _, _, _, _ in module_params)
        + '</ParameterRefs>\n</Static>\n'
        '<Dynamic><Channel Id="{p}_MD-1_CH-1" Name="Kanal" Text="Kanal"><ParameterBlock Id="{p}_MD-1_PB-1" '
        'Name="Kanal" Text="Kanal"><ParameterRefRef RefId="{p}_MD-1_P-1_R-1" />'
        + button('MD-1_B-1', 'Wert kopieren', 'TEST_modul')
        + '<ParameterRefRef RefId="{p}_MD-1_P-2_R-1" /></ParameterBlock></Channel></Dynamic></ModuleDef></ModuleDefs>\n')
    dynamic = ('<Dynamic><Channel Id="{p}_CH-1" Name="Allgemein" Text="Allgemein" Number="1">'
        '<ParameterBlock Id="{p}_PB-1" Name="Allgemein" Text="Allgemein">'
        '<ParameterRefRef RefId="{p}_P-1_R-1" />' + button('B-1', 'Text kopieren', 'TEST_text', '')
        + '<ParameterRefRef RefId="{p}_P-2_R-1" /><ParameterRefRef RefId="{p}_P-3_R-1" />'
        + button('B-2', 'Anzahl kopieren', 'TEST_anzahl', '{"Plus": 1}')
        + '<ParameterRefRef RefId="{p}_P-4_R-1" />'
        + button('B-3', 'Unbekannt', 'TEST_unbekannt') + button('B-4', 'Versteckt', 'TEST_versteckt')
        + button('B-6', 'Gerät', 'TEST_geraet')
        + '<choose ParamRefId="{p}_P-3_R-1"><when test="7">' + button('B-5', 'Bei sieben', 'TEST_text')
        + '</when></choose>'
        '</ParameterBlock></Channel>\n'
        + ''.join('<Module Id="{p}_MD-1_M-%d" RefId="{p}_MD-1" Name="Kanal %d"><NumericArg RefId="{p}_MD-1_A-1" '
            'Value="%d" /></Module>\n' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
        + '</Dynamic>\n')
    languages = ('<Languages><Language Identifier="en-US"><TranslationUnit RefId="{p}" Version="0">'
        '<TranslationElement RefId="{p}_B-1"><Translation AttributeName="Text" Text="Copy text" /></TranslationElement>'
        '</TranslationUnit></Language></Languages>')
    return bcu1_program_file(BUTTON_PROGRAM, 65506, 'TEST: Schaltflächen (künstliche Testdaten)', static, module,
        dynamic, languages, script='<Script>' + xml_text(BUTTON_SCRIPT) + '</Script>\n')


def button_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    device = ('<DeviceInstance Id="P-01A0-0_DI-1" Name="TEST Schaltgerät" ProductRefId="' + BUTTON_PRODUCT
        + '" Hardware2ProgramRefId="' + BUTTON_ASSIGNMENT + '" Address="1" LastModified="2026-11-13T12:00:00">\n'
        '<ParameterInstanceRefs>\n<ParameterInstanceRef RefId="P-1_R-1" Value="Hallo" />\n'
        '<ParameterInstanceRef RefId="' + BUTTON_PROGRAM + '_MD-1_M-2_MI-1_P-1_R-1" Value="3" />\n'
        '</ParameterInstanceRefs>\n<ModuleInstances>'
        + ''.join('<ModuleInstance Id="MD-1_M-%d_MI-1" RefId="MD-1_M-%d"><Arguments><Argument RefId="MD-1_A-1" '
            'Value="%d" /></Arguments></ModuleInstance>' % (n, n, base) for n, base in ((1, 0x60), (2, 0x68)))
        + '</ModuleInstances>\n</DeviceInstance>')
    return xml[:start] + device + xml[end:]


def buttons_fixture(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestButtons')
    if name == PROJECT:
        return button_devices(xml)
    return xml


# TestUpdateBase/New/Adopt.knxproj (haumacher/ehome #125, see the module comment).
UPDATE_PROJECT = 'P-0125'
UPDATE_PRODUCT = ('ProductRefId="M-0008_H-8.20.2F.2F.20zxy3.20xx-1_P-5133.2000" '
    'Hardware2ProgramRefId="M-0008_H-8.20.2F.2F.20zxy3.20xx-1_HP-10D7-11-A0E5-O000A"')


def update_device(number, name, address, ga, value='5'):
    return ('<DeviceInstance Id="P-01A0-0_DI-%d" Name="%s" %s Address="%d" LastModified="2026-11-10T12:00:00">\n'
        '<ParameterInstanceRefs><ParameterInstanceRef RefId="' + PROGRAM + '_P-579_R-579" Value="%s" />'
        '</ParameterInstanceRefs>\n<ComObjectInstanceRefs><ComObjectInstanceRef RefId="' + PROGRAM
        + '_O-0_R-81" IsActive="1"><Connectors><Send GroupAddressRefId="P-01A0-0_GA-%d" /></Connectors>'
        '</ComObjectInstanceRef></ComObjectInstanceRefs>\n</DeviceInstance>\n') % (number, name, UPDATE_PRODUCT,
            address, value, ga)


def update_project(xml, updated, adopt=False):
    xml = replace_once(xml, '<DeviceInstance Id="P-01A0-0_DI-1" ', '<DeviceInstance Id="P-01A0-0_DI-1" Name="Taster Küche" ')
    if updated:
        # A parameter of "Taster Küche" changed (before the devices of the fixture are added).
        xml = replace_once(xml, 'RefId="' + PROGRAM + '_P-2384_R-2384" Value="255"', 'RefId="' + PROGRAM + '_P-2384_R-2384" Value="30"')
    line1_end = xml.index('</Line>', xml.index('<Line Id="P-01A0-0_L-1"'))
    devices = update_device(3, 'Taster Reserve', 3, 7) if not updated else ''
    if not updated:
        devices = update_device(2, 'Taster Flur', 2, 6) + devices
    if adopt:
        devices += update_device(5, 'Taster Diele', 5, 8)
    line2 = ''
    if updated:
        line2 = update_device(4, 'Taster Bad', 1, 9) + update_device(2, 'Taster Flur', 2, 6)
    xml = xml[:line1_end] + devices + xml[line1_end:]
    line1_close = xml.index('</Line>', xml.index('<Line Id="P-01A0-0_L-1"')) + len('</Line>')
    xml = (xml[:line1_close] + '\n<Line Id="P-01A0-0_L-2" Name="Linie 2" Address="1" MediumTypeRefId="MT-0">'
        + line2 + '</Line>' + xml[line1_close:])
    if updated:
        xml = replace_once(xml, '<GroupAddress Id="P-01A0-0_GA-3" Address="3" Name="GA3" />',
            '<GroupAddress Id="P-01A0-0_GA-3" Address="4" Name="Licht Küche" />'
            '<GroupAddress Id="P-01A0-0_GA-13" Address="5" Name="Neu" />')
    else:
        xml = replace_once(xml, '<GroupRange Id="P-01A0-0_GR-2" Name="HG2" RangeStart="2048" RangeEnd="4095" />',
            '<GroupRange Id="P-01A0-0_GR-2" Name="HG2" RangeStart="2048" RangeEnd="4095">'
            '<GroupAddress Id="P-01A0-0_GA-11" Address="2049" Name="Unbenutzt" />'
            '<GroupAddress Id="P-01A0-0_GA-12" Address="2050" Name="Jalousie" /></GroupRange>')
    bath = ('<BuildingPart Id="P-01A0-0_BP-6" Name="Bad" Type="Room"><DeviceInstanceRef RefId="P-01A0-0_DI-4" />'
        '</BuildingPart>') if updated else ''
    xml = replace_once(xml, '<Buildings />', '<Buildings>\n'
        '<BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">'
        '<BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">'
        '<BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room" />'
        '<BuildingPart Id="P-01A0-0_BP-3" Name="Küche" Type="Room"><DeviceInstanceRef RefId="P-01A0-0_DI-1" />'
        '</BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-4" Name="Verteiler" Type="DistributionBoard" />' + bath
        + '</BuildingPart></BuildingPart>\n</Buildings>')
    return xml


def update_fixture(updated, adopt=False):
    def modify(name, xml):
        if name == PROJECT_INFO:
            xml = rename_project(xml, 'TestUpdate')
        elif name == PROJECT:
            xml = update_project(xml, updated, adopt)
        return xml.replace('P-01A0', UPDATE_PROJECT)
    return modify


def update_name(name):
    return name.replace('P-01A0', UPDATE_PROJECT)


write('src/test/fixtures/TestDetails.knxproj', keep_details)
write('src/test/fixtures/TestNaming.knxproj', naming)
write('src/test/fixtures/TestDynamic.knxproj', dynamic,
    rename=lambda name: name.replace(VERSION, NEW_VERSION))
write('src/test/fixtures/TestProduct.knxprod', unchanged, keep=is_product_data)
write('src/test/fixtures/TestNewProduct.knxproj', new_product, rename=new_product_name)
write('src/test/fixtures/TestNewProduct.knxprod', new_product, keep=is_product_data, rename=new_product_name)
encrypted('src/test/fixtures/Encrypted.knxprod', 'src/test/fixtures/TestProduct.knxprod',
    'M-0008/Secret.xml', b'test', b'<KNX/>')
write('src/test/fixtures/TestBcu1.knxproj', bcu1,
    [('M-0008/' + program + '.xml', bcu1_program(program, number, name, with_data))
     for program, _, number, name, with_data in BCU1_PROGRAMS])
write('src/test/fixtures/TestEts6.knxproj', ets6, rename=ets6_name, master=('MV-FFF0', 'DPT-10', 'DPT-20'))
write('src/test/fixtures/TestModules.knxproj', modules,
    [('M-0008/Hardware.xml', module_hardware()), ('M-0008/' + MODULE_PROGRAM + '.xml', module_program()),
     ('M-0008/' + REPEAT_PROGRAM + '.xml', repeat_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
languages_fixture('src/test/fixtures/TestLanguagesA.knxprod', {LANGUAGE_PROGRAMS[1][0]})
languages_fixture('src/test/fixtures/TestLanguagesB.knxprod', {LANGUAGE_PROGRAMS[0][0]})
write('src/test/fixtures/TestHiddenValues.knxproj', hidden_values,
    rename=lambda name: name.replace(VERSION, HIDDEN_VERSION))
FUNCTION_PROGRAM_FILES = [('M-0008/' + program + '.xml', function_program(program, number, name, channels))
    for program, _, number, name, channels in FUNCTION_PROGRAMS]
write('src/test/fixtures/TestFunctionKeys.knxproj', function_fixture('TestFunctionKeys', FUNCTION_KEY_DEVICES),
    FUNCTION_PROGRAM_FILES)
write('src/test/fixtures/TestFunctionRooms.knxproj', function_fixture('TestFunctionRooms', FUNCTION_ROOM_DEVICES),
    FUNCTION_PROGRAM_FILES)
write('src/test/fixtures/TestFunctionLinks.knxproj',
    function_fixture('TestFunctionLinks', LINK_DEVICES, LINK_ADDRESSES), FUNCTION_PROGRAM_FILES)
master_data_only('src/test/fixtures/TestMasterDataOld.knxproj', 1001, False)
master_data_only('src/test/fixtures/TestMasterDataNew.knxproj', 1002, True)
write('src/test/fixtures/TestChannels.knxproj', channels,
    [('M-0008/Hardware.xml', test_hardware(CHANNEL_HARDWARE, CHANNEL_PRODUCT, 'TEST Kanäle',
        [(CHANNEL_ASSIGNMENT, CHANNEL_PROGRAM)])), ('M-0008/' + CHANNEL_PROGRAM + '.xml', channel_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
write('src/test/fixtures/TestCalculations.knxproj', calculations_fixture,
    [('M-0008/Hardware.xml', test_hardware(CALC_HARDWARE, CALC_PRODUCT, 'TEST Berechnungen',
        [(CALC_ASSIGNMENT, CALC_PROGRAM), (LOCKED_ASSIGNMENT, LOCKED_PROGRAM)])),
     ('M-0008/' + CALC_PROGRAM + '.xml', calc_program()), ('M-0008/' + LOCKED_PROGRAM + '.xml', locked_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
write('src/test/fixtures/TestValidations.knxproj', validations_fixture,
    [('M-0008/Hardware.xml', test_hardware(CHECK_HARDWARE, CHECK_PRODUCT, 'TEST Prüfungen',
        [(CHECK_ASSIGNMENT, CHECK_PROGRAM), (VB_CHECK_ASSIGNMENT, VB_CHECK_PROGRAM)])),
     ('M-0008/' + CHECK_PROGRAM + '.xml', check_program()),
     ('M-0008/' + VB_CHECK_PROGRAM + '.xml', vb_check_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
write('src/test/fixtures/TestButtons.knxproj', buttons_fixture,
    [('M-0008/Hardware.xml', test_hardware(BUTTON_HARDWARE, BUTTON_PRODUCT, 'TEST Schaltflächen',
        [(BUTTON_ASSIGNMENT, BUTTON_PROGRAM)])), ('M-0008/' + BUTTON_PROGRAM + '.xml', button_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
write('src/test/fixtures/TestUpdateBase.knxproj', update_fixture(False), rename=update_name)
write('src/test/fixtures/TestUpdateNew.knxproj', update_fixture(True), rename=update_name)
write('src/test/fixtures/TestUpdateAdopt.knxproj', update_fixture(True, True), rename=update_name)


# TestAssigns.knxproj: assignments of the structure (ETS: Assign, haumacher/ehome #142) and switches with
# several matching cases. This file (schema 23, the master data and the project of the Test project) holds
# an artificial program of the BCU 1 kind on its own hardware "TEST Zuweisungen", marked as test data by its
# name, M-0008_A-FFE1-01-0000, modeled on the construct of the MDT Taster Light (the device type decides
# whether the key mode is entered directly or assigned): the parameters "Geraet" (device type, "2-fach" 0 /
# "1-fach" 1 / "4-fach" 4, default 0, memory 0x158), "Tasten" (key mode, "Einzel" 0 / "Zwei-Tasten" 1 /
# "Wippe" 2, default 2, memory 0x159) and "Variante" ("Wahl" 0 / "Fest" 1, default 0, without memory). The
# dynamic part begins (before the assignments in the order of the document) with a switch on the target
# reference P-2_R-1 of "Tasten": "1 2" places the block "Taste 1" (object 0), "2" the block "Taste 2"
# (objects 1 and 2), so that the value 2 takes both cases. Then the block "Tasten" with a switch on
# "Geraet": "0" places P-2_R-1 itself; "1" places the reference P-2_R-3 (Value 1), assigns it to P-2_R-1 and
# shows P-2_R-1 once more (read only while assigned);
# "4" places "Variante", whose case "0" places the source P-2_R-2 and assigns it to P-2_R-1, and whose
# case "1" assigns the constant 0 to P-2_R-1. Devices: "TEST Zuweisung Standard" (1.0.1, defaults) and
# "TEST Zuweisung 4-fach" (1.0.2, Geraet 4 and P-2_R-2 = 1).
ASSIGN_PROGRAM = 'M-0008_A-FFE1-01-0000'
ASSIGN_HARDWARE = 'M-0008_H-TEST.20ZUWEISUNG-1'
ASSIGN_PRODUCT = ASSIGN_HARDWARE + '_P-TEST.20ZUWEISUNG'
ASSIGN_ASSIGNMENT = ASSIGN_HARDWARE + '_HP-FFE1-01-0000'


def assign_enumeration(name, entries):
    return ('<ParameterType Id="{p}_PT-%s" Name="%s"><TypeRestriction Base="Value" SizeInBit="8">' % (name, name)
        + ''.join('<Enumeration Id="{p}_PT-%s_EN-%d" Text="%s" Value="%d" DisplayOrder="%d" />'
            % (name, value, text, value, n) for n, (value, text) in enumerate(entries))
        + '</TypeRestriction></ParameterType>\n')


def assign_program():
    static = ('<ParameterTypes>\n'
        + assign_enumeration('Geraet', [(0, '2-fach'), (1, '1-fach'), (4, '4-fach')])
        + assign_enumeration('Tasten', [(0, 'Einzel'), (1, 'Zwei-Tasten'), (2, 'Wippe')])
        + assign_enumeration('Variante', [(0, 'Wahl'), (1, 'Fest')])
        + '</ParameterTypes>\n<Parameters>\n'
        '<Parameter Id="{p}_P-1" Name="Geraet" Text="Geräteauswahl" ParameterType="{p}_PT-Geraet" Access="ReadWrite" '
        'Value="0"><Memory CodeSegment="{s}" Offset="88" BitOffset="0" /></Parameter>\n'
        '<Parameter Id="{p}_P-2" Name="Tasten" Text="Tasten 1/2" ParameterType="{p}_PT-Tasten" Access="ReadWrite" '
        'Value="2"><Memory CodeSegment="{s}" Offset="89" BitOffset="0" /></Parameter>\n'
        '<Parameter Id="{p}_P-3" Name="Variante" Text="Variante" ParameterType="{p}_PT-Variante" Access="ReadWrite" '
        'Value="0" />\n</Parameters>\n<ParameterRefs>\n'
        '<ParameterRef Id="{p}_P-1_R-1" RefId="{p}_P-1" />\n'
        '<ParameterRef Id="{p}_P-2_R-1" RefId="{p}_P-2" />\n'
        '<ParameterRef Id="{p}_P-2_R-2" RefId="{p}_P-2" />\n'
        '<ParameterRef Id="{p}_P-2_R-3" RefId="{p}_P-2" Value="1" />\n'
        '<ParameterRef Id="{p}_P-3_R-1" RefId="{p}_P-3" />\n'
        '</ParameterRefs>\n' + bcu1_objects())
    dynamic = ('<Dynamic><ChannelIndependentBlock>'
        '<choose ParamRefId="{p}_P-2_R-1">'
        '<when test="1 2"><ParameterBlock Id="{p}_PB-1" Name="Taste1" Text="Taste 1">'
        '<ComObjectRefRef RefId="{p}_O-0_R-1" /></ParameterBlock></when>'
        '<when test="2"><ParameterBlock Id="{p}_PB-2" Name="Taste2" Text="Taste 2">'
        '<ComObjectRefRef RefId="{p}_O-1_R-1" /><ComObjectRefRef RefId="{p}_O-2_R-1" /></ParameterBlock></when>'
        '</choose>\n'
        '<ParameterBlock Id="{p}_PB-3" Name="Tasten" Text="Tasten"><ParameterRefRef RefId="{p}_P-1_R-1" />'
        '<choose ParamRefId="{p}_P-1_R-1">'
        '<when test="0"><ParameterRefRef RefId="{p}_P-2_R-1" /></when>'
        '<when test="1"><ParameterRefRef RefId="{p}_P-2_R-3" />'
        '<Assign TargetParamRefRef="{p}_P-2_R-1" SourceParamRefRef="{p}_P-2_R-3" />'
        '<ParameterRefRef RefId="{p}_P-2_R-1" /></when>'
        '<when test="4"><ParameterRefRef RefId="{p}_P-3_R-1" /><choose ParamRefId="{p}_P-3_R-1">'
        '<when test="0"><ParameterRefRef RefId="{p}_P-2_R-2" />'
        '<Assign TargetParamRefRef="{p}_P-2_R-1" SourceParamRefRef="{p}_P-2_R-2" /></when>'
        '<when test="1"><Assign TargetParamRefRef="{p}_P-2_R-1" Value="0" /></when>'
        '</choose></when>'
        '</choose></ParameterBlock>'
        '</ChannelIndependentBlock></Dynamic>\n')
    return bcu1_program_file(ASSIGN_PROGRAM, 65505, 'TEST: Zuweisungen (künstliche Testdaten)', static, '',
        dynamic)


def assign_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')

    def device(number, name, values):
        return ('<DeviceInstance Id="P-01A0-0_DI-%d" Name="%s" ProductRefId="%s" Hardware2ProgramRefId="%s" '
            'Address="%d" LastModified="2026-11-11T12:00:00">\n<ParameterInstanceRefs>\n'
            % (number, name, ASSIGN_PRODUCT, ASSIGN_ASSIGNMENT, number)
            + ''.join('<ParameterInstanceRef RefId="%s" Value="%s" />\n' % entry for entry in values)
            + '</ParameterInstanceRefs>\n</DeviceInstance>')
    return (xml[:start] + device(1, 'TEST Zuweisung Standard', []) + '\n'
        + device(2, 'TEST Zuweisung 4-fach', [('P-1_R-1', '4'), ('P-2_R-2', '1')]) + xml[end:])


def assigns_fixture(name, xml):
    xml = replace_once(xml, SCHEMA_11, SCHEMA_23)
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestAssigns')
    if name == PROJECT:
        return assign_devices(xml)
    return xml


write('src/test/fixtures/TestAssigns.knxproj', assigns_fixture,
    [('M-0008/Hardware.xml', test_hardware(ASSIGN_HARDWARE, ASSIGN_PRODUCT, 'TEST Zuweisungen',
        [(ASSIGN_ASSIGNMENT, ASSIGN_PROGRAM)])), ('M-0008/' + ASSIGN_PROGRAM + '.xml', assign_program())],
    keep=lambda name: name == 'knx_master.xml' or name.startswith('P-01A0/'))
write('src/test/fixtures/TestFunctionWiring.knxproj', wiring_fixture,
    [('M-0008/' + p + '.xml', function_program(p, n, name, c)) for p, _, n, name, c in WIRING_PROGRAMS])


# TestFunctionRoles.knxproj: the roles of the function proposal of schema 1.6 (haumacher/ehome #136, #138), with
# three artificial programs of their own (M-0008_A-FFC1/FFC2/FFC5, marked as test data) in the building of
# TestFunctionWiring. "TEST Jalousieaktor Endlagen" (1.0.1, in the distribution board): "Kanal A" an awning
# without positioning (up/down 1.008 on 6/1/1, stop 1.007) that reports its end positions with the 1-bit objects
# "Status obere Position" and "Status untere Position" (1.002) and its direction with "Status akt. Richtung"
# (1.008, no end position), "Kanal B" a shutter with a position (5.001) and the end positions "Endlage oben" and
# "Endlage unten" (1.001), "Kanal C" a blind whose one object "Position oben/unten" names both ends (no role).
# "TEST Heizungsaktor Regler" (1.0.2, in the board), a heating actuator with its own controller: "Kanal A" receives
# the room temperature (6/4/1) and the comfort setpoint, reports the current setpoint, takes the operating mode,
# reports its actuating value "Status Stellwert" (5.001, 6/4/5) and "Heizen" (1.001); "Kanal B" the same without
# "Heizen" and without the current setpoint and mode. Two room sensors "TEST Raumfühler" (1.0.3 in "Wohnzimmer"
# on 6/4/1, 1.0.4 in "Küche" on 6/5/1) send the temperatures.
ROLE_PROGRAMS = [
    ('M-0008_A-FFC1-01-0000', 'FFC1', 65473, 'TEST: Jalousieaktor Endlagen (künstliche Testdaten)', [
        ('Kanal A', [('Markise Auf/Ab', '1 Bit', W, 'DPST-1-8', '6/1/1'), ('Stopp', '1 Bit', W, 'DPST-1-7', '6/1/2'),
            ('Status obere Position', '1 Bit', T, 'DPST-1-2', '6/1/3'),
            ('Status untere Position', '1 Bit', T, 'DPST-1-2', '6/1/4'),
            ('Status akt. Richtung', '1 Bit', T, 'DPST-1-8', '6/1/5')]),
        ('Kanal B', [('Rollladen Auf/Ab', '1 Bit', W, 'DPST-1-8', '6/2/1'),
            ('Status Position', '1 Byte', T, 'DPST-5-1', '6/2/2'), ('Endlage oben', '1 Bit', T, 'DPST-1-1', '6/2/3'),
            ('Endlage unten', '1 Bit', T, 'DPST-1-1', '6/2/4')]),
        ('Kanal C', [('Jalousie Auf/Ab', '1 Bit', W, 'DPST-1-8', '6/3/1'),
            ('Position oben/unten', '1 Bit', T, 'DPST-1-1', '6/3/2')]),
    ]),
    ('M-0008_A-FFC2-01-0000', 'FFC2', 65474, 'TEST: Heizungsaktor Regler (künstliche Testdaten)', [
        ('Kanal A', [('Isttemperatur', '2 Bytes', W, 'DPST-9-1', '6/4/1'),
            ('Sollwert Komfort', '2 Bytes', W, 'DPST-9-1', '6/4/2'),
            ('Aktueller Sollwert', '2 Bytes', T, 'DPST-9-1', '6/4/3'),
            ('Betriebsart', '1 Byte', W, 'DPST-20-102', '6/4/4'),
            ('Status Stellwert', '1 Byte', T, 'DPST-5-1', '6/4/5'), ('Heizen', '1 Bit', T, 'DPST-1-1', '6/4/6')]),
        ('Kanal B', [('Isttemperatur', '2 Bytes', W, 'DPST-9-1', '6/5/1'),
            ('Sollwert Komfort', '2 Bytes', W, 'DPST-9-1', '6/5/2'),
            ('Status Stellwert', '1 Byte', T, 'DPST-5-1', '6/5/5')]),
    ]),
    ('M-0008_A-FFC5-01-0000', 'FFC5', 65477, 'TEST: Raumfühler (künstliche Testdaten)', [
        ('Temperatur', [('Raumtemperatur', '2 Bytes', T, 'DPST-9-1', None)]),
    ]),
]
ROLE_HARDWARE = 'M-0008_H-TEST.20ROLES-1'
# The group addresses of TestFunctionRoles: name and datapoint type by address.
ROLE_ADDRESSES = {'6/1/1': ('Markise Terrasse', 'DPST-1-8'), '6/1/2': ('Markise Terrasse Stopp', 'DPST-1-7'),
    '6/1/3': ('Markise Terrasse oben', 'DPST-1-2'), '6/1/4': ('Markise Terrasse unten', 'DPST-1-2'),
    '6/1/5': ('Markise Terrasse Richtung', 'DPST-1-8'), '6/2/1': ('Rollladen Büro', 'DPST-1-8'),
    '6/2/2': ('Rollladen Büro Position', 'DPST-5-1'), '6/2/3': ('Rollladen Büro oben', 'DPST-1-1'),
    '6/2/4': ('Rollladen Büro unten', 'DPST-1-1'), '6/3/1': ('Jalousie Gast', 'DPST-1-8'),
    '6/3/2': ('Jalousie Gast Endlage', 'DPST-1-1'), '6/4/1': ('Temperatur Bad', 'DPST-9-1'),
    '6/4/2': ('Sollwert Bad', 'DPST-9-1'), '6/4/3': ('Sollwert Bad Status', 'DPST-9-1'),
    '6/4/4': ('Betriebsart Bad', 'DPST-20-102'), '6/4/5': ('Stellwert Bad', 'DPST-5-1'),
    '6/4/6': ('Heizen Bad', 'DPST-1-1'), '6/5/1': ('Temperatur Kind', 'DPST-9-1'),
    '6/5/2': ('Sollwert Kind', 'DPST-9-1'), '6/5/5': ('Stellwert Kind', 'DPST-5-1')}
# The devices of TestFunctionRoles: (program, name, building part, the group address of each connected object by
# its number, None for those the program gives).
ROLE_DEVICES = [
    (0, 'TEST Jalousieaktor Endlagen', 'P-01A0-0_BP-4', None),
    (1, 'TEST Heizungsaktor Regler', 'P-01A0-0_BP-4', None),
    (2, 'TEST Raumfühler', 'P-01A0-0_BP-5', {0: '6/4/1'}),
    (2, 'TEST Raumfühler', 'P-01A0-0_BP-7', {0: '6/5/1'}),
]


def role_hardware(xml):
    end = xml.rindex('</Hardware>', 0, xml.index('<Languages>'))
    hardware = ''
    for program, number, _, name, _ in ROLE_PROGRAMS:
        short = name.split(' (')[0].split(': ')[1]
        hardware += ('<Hardware Id="' + ROLE_HARDWARE + number + '" Name="TEST ' + short + '" SerialNumber="TEST '
            + number + '" VersionNumber="1" HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="'
            + ROLE_HARDWARE + number + '_P-TEST.20' + number + '" Text="TEST ' + short
            + ' (künstliche Testdaten)" OrderNumber="TEST ' + number + '" IsRailMounted="1" DefaultLanguage="de-DE" />'
            '</Products><Hardware2Programs><Hardware2Program Id="' + ROLE_HARDWARE + number + '_HP-' + number
            + '-01-0000" MediumTypes="MT-0"><ApplicationProgramRef RefId="' + program + '" /></Hardware2Program>'
            '</Hardware2Programs></Hardware>\n')
    return xml[:end] + hardware + xml[end:]


def role_devices(xml):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    addresses = sorted(ROLE_ADDRESSES, key=ga_value)
    devices = ''
    for n, (index, device_name, _, connected) in enumerate(ROLE_DEVICES, 1):
        program, number, _, _, channels = ROLE_PROGRAMS[index]
        objects = [(o[0], o[6] if connected is None else connected.get(o[0])) for o in function_objects(channels)]
        refs = ''.join('<ComObjectInstanceRef RefId="' + program + '_O-%d_R-%d" IsActive="1"><Connectors>'
            % (o, o + 1) + function_connectors(addresses, address) + '</Connectors></ComObjectInstanceRef>\n'
            for o, address in objects if address is not None)
        devices += ('<DeviceInstance Id="P-01A0-0_DI-' + str(n) + '" Name="' + device_name
            + '" ProductRefId="' + ROLE_HARDWARE + number + '_P-TEST.20' + number + '" Hardware2ProgramRefId="'
            + ROLE_HARDWARE + number + '_HP-' + number + '-01-0000" Address="' + str(n)
            + '" LastModified="2026-11-12T12:00:00">\n<ComObjectInstanceRefs>\n' + refs
            + '</ComObjectInstanceRefs>\n</DeviceInstance>\n')
    xml = xml[:start] + devices + xml[end:]
    start = xml.index('<GroupRanges>')
    end = xml.index('</GroupRanges>') + len('</GroupRanges>')
    ranges = ''
    for main in sorted(set(int(a.split('/')[0]) for a in addresses)):
        ranges += ('<GroupRange Id="P-01A0-0_GR-%d" Name="HG%d" RangeStart="%d" RangeEnd="%d">'
            % (main, main, main << 11, (main << 11) + 2047)
            + ''.join('<GroupAddress Id="P-01A0-0_GA-%d" Address="%d" Name="%s" DatapointType="%s" />'
                % (addresses.index(a) + 1, ga_value(a), ROLE_ADDRESSES[a][0], ROLE_ADDRESSES[a][1])
                for a in addresses if a.startswith(str(main) + '/'))
            + '</GroupRange>\n')
    xml = xml[:start] + '<GroupRanges>\n' + ranges + '</GroupRanges>' + xml[end:]

    def refs(part):
        return ''.join('<DeviceInstanceRef RefId="P-01A0-0_DI-%d" />' % n
            for n, (_, _, placed, _) in enumerate(ROLE_DEVICES, 1) if placed == part)
    return replace_once(xml, '<Buildings />', '<Buildings>\n'
        '<BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">'
        '<BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">'
        '<BuildingPart Id="P-01A0-0_BP-3" Name="Flur" Type="Room">' + refs('P-01A0-0_BP-3')
        + '<BuildingPart Id="P-01A0-0_BP-4" Name="Schaltschrank" Type="DistributionBoard">' + refs('P-01A0-0_BP-4')
        + '</BuildingPart></BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room">' + refs('P-01A0-0_BP-5') + '</BuildingPart>'
        '<BuildingPart Id="P-01A0-0_BP-7" Name="Küche" Type="Room">' + refs('P-01A0-0_BP-7') + '</BuildingPart>'
        '</BuildingPart></BuildingPart>\n</Buildings>')


def role_fixture(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestFunctionRoles')
    if name == PROJECT:
        return role_devices(xml)
    if name == 'M-0008/Hardware.xml':
        return role_hardware(xml)
    return xml


write('src/test/fixtures/TestFunctionRoles.knxproj', role_fixture,
    [('M-0008/' + p + '.xml', function_program(p, n, name, c)) for p, _, n, name, c in ROLE_PROGRAMS])


# TestFunctionHeating.knxproj: a heating actuator with its own controller in the distribution board and the room
# keys sending the temperatures it receives (haumacher/ehome #138, the construct of the MDT AKH-0600.03 of the ETS6
# demo), with two artificial programs of their own (M-0008_A-FFC6/FFC7, marked as test data) in the building of
# TestFunctionWiring. "TEST Heizungsaktor Stellwert" (1.0.1, in the board): each channel receives the room
# temperature with "Temperaturwert empfangen" (9.001, flags W and T, as the MDT actuator's), has a setpoint, an
# operating mode and the current setpoint connected to nothing, and reports "Stellwert > 0%: Status senden" (1.011)
# and "Stellwert Heizen: Status senden" (5.001); "Kanal A" on 7/1/1..3, "Kanal B" on 7/2/1..3, "Kanal C" connected
# to nothing, "Kanal D" receives the temperature 7/4/1 alone (its reports connected to nothing). Four keys "TEST
# Taster Raumtemperatur" send "Gemessene Raumtemperatur" (9.001): 1.0.2 in "Wohnzimmer" on 7/1/1, 1.0.3 in
# "Küche" on 7/2/1, 1.0.4 in "Flur" on 7/3/1, which no heating receives, 1.0.5 in "Wohnzimmer" on 7/4/1.
HEATING_CHANNEL = [('Temperaturwert empfangen', '2 Bytes', WT, 'DPST-9-1'), ('Sollwert vorgeben', '2 Bytes', W, 'DPST-9-1'),
    ('Betriebsartvorwahl', '1 Byte', W, 'DPST-20-102'), ('Aktueller Sollwert senden', '2 Bytes', T, 'DPST-9-1'),
    ('Stellwert > 0%: Status senden', '1 Bit', T, 'DPST-1-11'), ('Stellwert Heizen: Status senden', '1 Byte', T, 'DPST-5-1')]


def heating_channel(name, addresses):
    """A channel of the heating actuator: its objects, the temperature, the heating and its level on the addresses."""
    connected = {n: a for n, a in zip((0, 4, 5), addresses or ()) if a is not None}
    return (name, [obj + (connected.get(n),) for n, obj in enumerate(HEATING_CHANNEL)])


HEATING_PROGRAMS = [
    ('M-0008_A-FFC6-01-0000', 'FFC6', 65478, 'TEST: Heizungsaktor Stellwert (künstliche Testdaten)', [
        heating_channel('Kanal A', ('7/1/1', '7/1/2', '7/1/3')), heating_channel('Kanal B', ('7/2/1', '7/2/2', '7/2/3')),
        heating_channel('Kanal C', None), heating_channel('Kanal D', ('7/4/1', None, None))]),
    ('M-0008_A-FFC7-01-0000', 'FFC7', 65479, 'TEST: Taster Raumtemperatur (künstliche Testdaten)', [
        ('Temperatur', [('Gemessene Raumtemperatur', '2 Bytes', T, 'DPST-9-1', None)])]),
]
HEATING_HARDWARE = 'M-0008_H-TEST.20HEATING-1'
# The group addresses of TestFunctionHeating: name and datapoint type by address.
HEATING_ADDRESSES = {'7/1/1': ('Temperatur Wohnzimmer', 'DPST-9-1'), '7/1/2': ('Heizen Wohnzimmer', 'DPST-1-11'),
    '7/1/3': ('Stellwert Wohnzimmer', 'DPST-5-1'), '7/2/1': ('Temperatur Küche', 'DPST-9-1'),
    '7/2/2': ('Heizen Küche', 'DPST-1-11'), '7/2/3': ('Stellwert Küche', 'DPST-5-1'),
    '7/3/1': ('Temperatur Flur', 'DPST-9-1'), '7/4/1': ('Temperatur Gast', 'DPST-9-1')}
# The devices of TestFunctionHeating: (program, name, building part, the group address of each connected object by
# its number, None for those the program gives).
HEATING_DEVICES = [
    (0, 'TEST Heizungsaktor Stellwert', 'P-01A0-0_BP-4', None),
    (1, 'TEST Taster Raumtemperatur', 'P-01A0-0_BP-5', {0: '7/1/1'}),
    (1, 'TEST Taster Raumtemperatur', 'P-01A0-0_BP-7', {0: '7/2/1'}),
    (1, 'TEST Taster Raumtemperatur', 'P-01A0-0_BP-3', {0: '7/3/1'}),
    (1, 'TEST Taster Raumtemperatur', 'P-01A0-0_BP-5', {0: '7/4/1'}),
]


def artificial_fixture(project, programs, hardware_id, addresses, devices):
    """The modification of the Test project into a project of artificial programs (each channel a list of objects
    (name, size, flags, datapoint type, group address or None)), named group addresses with their types, devices
    placed in the building of TestFunctionWiring."""
    def hardware(xml):
        end = xml.rindex('</Hardware>', 0, xml.index('<Languages>'))
        result = ''
        for program, number, _, name, _ in programs:
            short = name.split(' (')[0].split(': ')[1]
            result += ('<Hardware Id="' + hardware_id + number + '" Name="TEST ' + short + '" SerialNumber="TEST '
                + number + '" VersionNumber="1" HasIndividualAddress="1" HasApplicationProgram="1"><Products><Product Id="'
                + hardware_id + number + '_P-TEST.20' + number + '" Text="TEST ' + short
                + ' (künstliche Testdaten)" OrderNumber="TEST ' + number + '" IsRailMounted="1" DefaultLanguage="de-DE" />'
                '</Products><Hardware2Programs><Hardware2Program Id="' + hardware_id + number + '_HP-' + number
                + '-01-0000" MediumTypes="MT-0"><ApplicationProgramRef RefId="' + program + '" /></Hardware2Program>'
                '</Hardware2Programs></Hardware>\n')
        return xml[:end] + result + xml[end:]

    def placed(xml):
        start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
        end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
        ordered = sorted(addresses, key=ga_value)
        result = ''
        for n, (index, device_name, _, connected, *values) in enumerate(devices, 1):
            program, number, _, _, channels = programs[index]
            objects = [(o[0], o[6] if connected is None else connected.get(o[0])) for o in function_objects(channels)]
            refs = ''.join('<ComObjectInstanceRef RefId="' + program + '_O-%d_R-%d" IsActive="1"><Connectors>'
                % (o, o + 1) + function_connectors(ordered, address) + '</Connectors></ComObjectInstanceRef>\n'
                for o, address in objects if address is not None)
            # The values of the device's text parameters by the number of their channel.
            parameters = '' if not values or not values[0] else ('<ParameterInstanceRefs>\n' + ''.join(
                '<ParameterInstanceRef RefId="' + program + '_P-%d_R-1" Value="%s" />\n' % (c, v)
                for c, v in sorted(values[0].items())) + '</ParameterInstanceRefs>\n')
            result += ('<DeviceInstance Id="P-01A0-0_DI-' + str(n) + '" Name="' + device_name
                + '" ProductRefId="' + hardware_id + number + '_P-TEST.20' + number + '" Hardware2ProgramRefId="'
                + hardware_id + number + '_HP-' + number + '-01-0000" Address="' + str(n)
                + '" LastModified="2026-11-12T12:00:00">\n' + parameters + '<ComObjectInstanceRefs>\n' + refs
                + '</ComObjectInstanceRefs>\n</DeviceInstance>\n')
        xml = xml[:start] + result + xml[end:]
        start = xml.index('<GroupRanges>')
        end = xml.index('</GroupRanges>') + len('</GroupRanges>')
        ranges = ''
        for main in sorted(set(int(a.split('/')[0]) for a in ordered)):
            ranges += ('<GroupRange Id="P-01A0-0_GR-%d" Name="HG%d" RangeStart="%d" RangeEnd="%d">'
                % (main, main, main << 11, (main << 11) + 2047)
                + ''.join('<GroupAddress Id="P-01A0-0_GA-%d" Address="%d" Name="%s" DatapointType="%s" />'
                    % (ordered.index(a) + 1, ga_value(a), addresses[a][0], addresses[a][1])
                    for a in ordered if a.startswith(str(main) + '/'))
                + '</GroupRange>\n')
        xml = xml[:start] + '<GroupRanges>\n' + ranges + '</GroupRanges>' + xml[end:]

        def refs(part):
            return ''.join('<DeviceInstanceRef RefId="P-01A0-0_DI-%d" />' % n
                for n, (_, _, at, *_) in enumerate(devices, 1) if at == part)
        return replace_once(xml, '<Buildings />', '<Buildings>\n'
            '<BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">'
            '<BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">'
            '<BuildingPart Id="P-01A0-0_BP-3" Name="Flur" Type="Room">' + refs('P-01A0-0_BP-3')
            + '<BuildingPart Id="P-01A0-0_BP-4" Name="Schaltschrank" Type="DistributionBoard">' + refs('P-01A0-0_BP-4')
            + '</BuildingPart></BuildingPart>'
            '<BuildingPart Id="P-01A0-0_BP-5" Name="Wohnzimmer" Type="Room">' + refs('P-01A0-0_BP-5') + '</BuildingPart>'
            '<BuildingPart Id="P-01A0-0_BP-7" Name="Küche" Type="Room">' + refs('P-01A0-0_BP-7') + '</BuildingPart>'
            '</BuildingPart></BuildingPart>\n</Buildings>')

    def modify(name, xml):
        if name == PROJECT_INFO:
            return rename_project(xml, project)
        if name == PROJECT:
            return placed(xml)
        if name == 'M-0008/Hardware.xml':
            return hardware(xml)
        return xml
    return modify


write('src/test/fixtures/TestFunctionHeating.knxproj',
    artificial_fixture('TestFunctionHeating', HEATING_PROGRAMS, HEATING_HARDWARE, HEATING_ADDRESSES, HEATING_DEVICES),
    [('M-0008/' + p + '.xml', function_program(p, n, name, c)) for p, _, n, name, c in HEATING_PROGRAMS])


# TestFunctionNames.knxproj: distinct names of the functions in use (haumacher/ehome #144), where the project's
# group addresses carry the generic names ETS gives ("Schalten EIN/AUS"), with two artificial programs of their own
# (M-0008_A-FFCA/FFCB, marked as test data) in the building of TestFunctionWiring. Two switching actuators "TEST
# Schaltaktor Namen" (1.0.1 and 1.0.2, in the distribution board) with the channels "Kanal A" to "Kanal E", each
# with "Schalten" (1.001) and "Status" (1.011); "Kanal E" has the text "Kanal E: {{0}}" filled from its text
# parameter, which 1.0.1 sets to "Licht Speisekammer". 1.0.1 switches its channels A to E on 9/0/1 to 9/0/5, all
# named "Schalten EIN/AUS" (A reports on 9/1/1 "Status"); 1.0.2 switches "Kanal A" on 9/0/6, "Schalten EIN/AUS" as
# well, and "Kanal B" on 9/2/1 "Licht Esstisch", the only connected address of that name (9/2/2 has the same name
# and no object). Seven keys "TEST Taster Namen"
# send on them: 1.0.3 in "Wohnzimmer" on 9/0/1, 1.0.4 in "Küche" on 9/0/2, 1.0.5 and 1.0.6 in "Flur" on 9/0/3 and
# 9/0/4, 1.0.7 in "Wohnzimmer" on 9/0/6, 1.0.8 in "Küche" on 9/0/5, 1.0.9 in "Wohnzimmer" on 9/2/1.
NAME_PROGRAMS = [
    ('M-0008_A-FFCA-01-0000', 'FFCA', 65482, 'TEST: Schaltaktor Namen (künstliche Testdaten)', [
        (channel, [('Schalten', '1 Bit', W, 'DPST-1-1', None), ('Status', '1 Bit', T, 'DPST-1-11', None)])
        for channel in ('Kanal A', 'Kanal B', 'Kanal C', 'Kanal D', 'Kanal E')]),
    ('M-0008_A-FFCB-01-0000', 'FFCB', 65483, 'TEST: Taster Namen (künstliche Testdaten)', [
        ('Taste 1', [('Schalten', '1 Bit', WT, None, None)])]),
]
NAME_HARDWARE = 'M-0008_H-TEST.20NAMES-1'
# The group addresses of TestFunctionNames: name and datapoint type by address.
NAME_ADDRESSES = dict([('9/0/%d' % n, ('Schalten EIN/AUS', 'DPST-1-1')) for n in range(1, 7)]
    + [('9/1/1', ('Status', 'DPST-1-11')), ('9/2/1', ('Licht Esstisch', 'DPST-1-1')),
    ('9/2/2', ('Licht Esstisch', 'DPST-1-1'))])
# The devices of TestFunctionNames: (program, name, building part, the group address of each connected object by
# its number, the values of the text parameters by channel).
NAME_DEVICES = [
    (0, 'TEST Schaltaktor Namen', 'P-01A0-0_BP-4', {0: '9/0/1', 1: '9/1/1', 2: '9/0/2', 4: '9/0/3', 6: '9/0/4',
        8: '9/0/5'}, {5: 'Licht Speisekammer'}),
    (0, 'TEST Schaltaktor Namen', 'P-01A0-0_BP-4', {0: '9/0/6', 2: '9/2/1'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-5', {0: '9/0/1'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-7', {0: '9/0/2'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-3', {0: '9/0/3'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-3', {0: '9/0/4'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-5', {0: '9/0/6'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-7', {0: '9/0/5'}),
    (1, 'TEST Taster Namen', 'P-01A0-0_BP-5', {0: '9/2/1'}),
]
write('src/test/fixtures/TestFunctionNames.knxproj',
    artificial_fixture('TestFunctionNames', NAME_PROGRAMS, NAME_HARDWARE, NAME_ADDRESSES, NAME_DEVICES),
    [('M-0008/' + NAME_PROGRAMS[0][0] + '.xml', function_program(*[NAME_PROGRAMS[0][i] for i in (0, 2, 3, 4)],
        described={5})),
     ('M-0008/' + NAME_PROGRAMS[1][0] + '.xml', function_program(*[NAME_PROGRAMS[1][i] for i in (0, 2, 3, 4)]))])


# TestBaggage*.knxprod and TestBaggage.knxproj: the files of a manufacturer (baggage, haumacher/ehome #154), see the
# module comment.
BAGGAGE_PROGRAM = 'M-0008_A-FFB0-01-0000'
BAGGAGE_PROJECT_PROGRAM = 'M-0008_A-FFB1-01-0000'
BAGGAGE_HARDWARE = 'M-0008_H-TEST.20BEIGABEN-1'
DRIVER_HARDWARE = 'M-0008_H-TEST.20TREIBER-1'
BG_PICTURE = 'M-0008_BG--TEST.5FBild.2Epng'
BG_HELP = 'M-0008_BG-hilfe-TEST.5FHilfe.2Ezip'
BG_MISSING = 'M-0008_BG--TEST.5FFehlt.2Epng'
BG_DRIVER = 'M-0008_BG-inf-TEST.20Treiber.2Emsi'
BG_PROJECT = 'M-0008_BG--TEST.5FProjekt.2Epng'
SCHEMA_11_HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n'
    '<KNX xmlns="http://knx.org/xml/project/11" CreatedBy="make-fixtures.py" ToolVersion="test">\n')


def png(width, height, rgb):
    """A PNG picture of one color, made for the test."""
    raw = b''.join(b'\x00' + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def text_archive(name, text):
    """A ZIP archive holding one text, made for the test."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        info = zipfile.ZipInfo(name, date_time=(2026, 11, 2, 9, 3, 26))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, text.encode('utf-8'))
    return buffer.getvalue()


BAGGAGE_PICTURE = png(4, 4, (255, 0, 0))
BAGGAGE_PICTURE_NEWER = png(4, 4, (0, 255, 0))
BAGGAGE_HELP = text_archive('Hilfe.txt', '**Schalten:** künstlicher Hilfetext für den Test.\n')
BAGGAGE_HELP_OLDER = text_archive('Hilfe.txt', 'Älterer künstlicher Hilfetext.\n')
BAGGAGE_DRIVER = (b'TEST: kein Treiber, kuenstliche Testdaten.\n' * 70)[:3000]
BAGGAGE_PROJECT_PICTURE = png(2, 2, (0, 0, 255))


def baggages_xml(files):
    """The index of the baggage of M-0008: (ID, target path, name, time) per file."""
    return (SCHEMA_11_HEADER + '<ManufacturerData><Manufacturer RefId="M-0008"><Baggages>\n'
        + ''.join('<Baggage TargetPath="%s" Name="%s" Id="%s"><FileInfo TimeInfo="%s" /></Baggage>\n'
            % (path, name, ident, time) for ident, path, name, time in files)
        + '</Baggages></Manufacturer></ManufacturerData>\n</KNX>\n')


def baggage_program(program, number, name, references, picture=None):
    """A BCU 1 program whose extension names the given files of the baggage, with a parameter "Schaltbild"
    of a picture type (TypePicture) showing the given file, placed in the block "Allgemein", if any."""
    extension = '<Extension>' + ''.join('<Baggage RefId="%s" />' % r for r in references) + '</Extension>\n'
    xml = replace_once(bcu1_program(program, number, name, False), '<Options ', extension + '<Options ')
    if picture:
        xml = replace_once(xml, '</ParameterTypes>', '<ParameterType Id="{p}_PT-Bild" Name="Bild"><TypePicture RefId="'
            + picture + '" /></ParameterType>\n</ParameterTypes>')
        xml = replace_once(xml, '</Parameters>', '<Parameter Id="{p}_P-9" Name="Schaltbild" Text="Schaltbild" '
            'ParameterType="{p}_PT-Bild" Access="ReadWrite" Value="" />\n</Parameters>')
        xml = replace_once(xml, '</ParameterRefs>', '<ParameterRef Id="{p}_P-9_R-9" RefId="{p}_P-9" Tag="9" />\n'
            '</ParameterRefs>')
        xml = replace_once(xml, '</ParameterBlock>', '<ParameterRefRef RefId="{p}_P-9_R-9" />\n</ParameterBlock>')
        xml = xml.replace('{p}', program)
    return xml


def baggage_hardware(hardware, name, program, baggage):
    """A hardware with one product (naming the given files of the baggage), assigned the program if any."""
    product = hardware + '_P-' + name.replace(' ', '.20')
    names = ('<Baggages>' + ''.join('<Baggage RefId="%s" />' % b for b in baggage) + '</Baggages>') if baggage else ''
    assignment = ('<Hardware2Programs><Hardware2Program Id="%s_HP-%s" MediumTypes="MT-0"><ApplicationProgramRef RefId="%s" />'
        '</Hardware2Program></Hardware2Programs>' % (hardware, program[9:], program)) if program else ''
    return (SCHEMA_11_HEADER + '<ManufacturerData><Manufacturer RefId="M-0008"><Hardware>'
        '<Hardware Id="' + hardware + '" Name="' + name + '" SerialNumber="' + name + '" VersionNumber="1" '
        'HasIndividualAddress="1" HasApplicationProgram="' + ('1' if program else '0') + '"><Products>'
        '<Product Id="' + product + '" Text="' + name + ' (künstliche Testdaten)" OrderNumber="' + name
        + '" IsRailMounted="1" DefaultLanguage="de-DE">' + names + '</Product></Products>' + assignment
        + '</Hardware></Hardware></Manufacturer></ManufacturerData>\n</KNX>\n')


def baggage_archive(target, entries, project=False, project_name='TestBaggage'):
    """A product file (or with project=True the Test project) with the given entries (name, text or bytes), in
    their order after the master data (trimmed to what the entries use)."""
    with zipfile.ZipFile(SOURCE) as source:
        master = source.read('knx_master.xml').decode('utf-8-sig')
        kept = [(info, source.read(info.filename)) for info in source.infolist()
            if project and info.filename != 'knx_master.xml']
    texts = [e.decode('utf-8-sig') for _, e in kept if isinstance(e, bytes) and _.filename.endswith('.xml')]
    texts += [content for _, content in entries if isinstance(content, str)]
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        info = zipfile.ZipInfo('knx_master.xml', date_time=(2026, 11, 12, 12, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, trim_master(master, texts).encode('utf-8'))
        for info, data in kept:
            if info.filename == PROJECT_INFO:
                data = rename_project(data.decode('utf-8-sig'), project_name).encode('utf-8')
            out.writestr(info, data)
        for name, content in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 12, 12, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, content.encode('utf-8') if isinstance(content, str) else content)


BAGGAGE_INDEX = [(BG_PICTURE, '', 'TEST_Bild.png', '2026-11-01T10:00:00Z'),
    (BG_HELP, 'hilfe', 'TEST_Hilfe.zip', '2026-11-02T09:03:26.8724918Z'),
    (BG_MISSING, '', 'TEST_Fehlt.png', '2026-11-01T10:00:00Z')]
BAGGAGE_PROGRAM_FILE = ('M-0008/' + BAGGAGE_PROGRAM + '.xml',
    baggage_program(BAGGAGE_PROGRAM, 65456, 'TEST: Beigaben (künstliche Testdaten)', [BG_PICTURE, BG_HELP, BG_MISSING],
        BG_PICTURE))
BAGGAGE_HARDWARE_FILE = ('M-0008/Hardware.xml', baggage_hardware(BAGGAGE_HARDWARE, 'TEST Beigaben', BAGGAGE_PROGRAM, []))
baggage_archive('src/test/fixtures/TestBaggage.knxprod', [
    ('M-0008/Baggages/TEST_Bild.png', BAGGAGE_PICTURE),
    ('M-0008/Baggages/hilfe/TEST_Hilfe.zip', BAGGAGE_HELP),
    ('M-0008/Baggages/TEST_Ungelistet.txt', 'Nicht im Verzeichnis der Beigaben (künstliche Testdaten).\n'),
    BAGGAGE_PROGRAM_FILE, BAGGAGE_HARDWARE_FILE,
    ('M-0008/Baggages.xml', baggages_xml(BAGGAGE_INDEX))])
baggage_archive('src/test/fixtures/TestBaggageNewer.knxprod', [
    BAGGAGE_HARDWARE_FILE,
    ('M-0008/Baggages.xml', baggages_xml([(BG_PICTURE, '', 'TEST_Bild.png', '2026-11-05T08:00:00Z'),
        (BG_HELP, 'hilfe', 'TEST_Hilfe.zip', '2026-10-01T00:00:00Z'), BAGGAGE_INDEX[2]])),
    ('M-0008/Baggages/TEST_Bild.png', BAGGAGE_PICTURE_NEWER),
    ('M-0008/Baggages/hilfe/TEST_Hilfe.zip', BAGGAGE_HELP_OLDER),
    BAGGAGE_PROGRAM_FILE])
baggage_archive('src/test/fixtures/TestBaggageDriver.knxprod', [
    ('M-0008/Hardware.xml', baggage_hardware(DRIVER_HARDWARE, 'TEST Treiber', None, [BG_DRIVER])),
    ('M-0008/Baggages.xml', baggages_xml([(BG_DRIVER, 'inf', 'TEST Treiber.msi', '2009-05-07T12:12:06Z')])),
    ('M-0008/Baggages/inf/TEST Treiber.msi', BAGGAGE_DRIVER)])
baggage_archive('src/test/fixtures/TestBaggage.knxproj', [
    ('M-0008/Baggages.xml', baggages_xml([(BG_PROJECT, '', 'TEST_Projekt.png', '2026-11-03T12:00:00Z')])),
    ('M-0008/Baggages/TEST_Projekt.png', BAGGAGE_PROJECT_PICTURE),
    ('M-0008/' + BAGGAGE_PROJECT_PROGRAM + '.xml',
        baggage_program(BAGGAGE_PROJECT_PROGRAM, 65457, 'TEST: Beigaben im Projekt (künstliche Testdaten)', [BG_PROJECT]))],
    project=True)


# TestPictures.knxprod: the pictures of the parameter dialog (TypePicture, haumacher/ehome #155), see the module comment.
PICTURES_PROGRAM = 'M-0008_A-FFB2-01-0000'
PICTURES_HARDWARE = 'M-0008_H-TEST.20BILDER-1'
BG_DIAGRAM = 'M-0008_BG--TEST.5FSchaltbild.2Epng'
BG_CURVE = 'M-0008_BG--TEST.5FKurve.2Ebmp'
BG_ABSENT = 'M-0008_BG--TEST.5FOhne.2Epng'


def bmp(width, height, rgb):
    """A BMP picture of one color (24 bits, rows padded to four bytes), made for the test."""
    row = bytes(reversed(rgb)) * width
    row += b'\x00' * ((4 - len(row) % 4) % 4)
    pixels = row * height
    header = struct.pack('<2sIHHI', b'BM', 54 + len(pixels), 0, 0, 54)
    info = struct.pack('<IiiHHIIiiII', 40, width, height, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    return header + info + pixels


def pictures_program(program, number, name):
    """A BCU 1 program placing three parameters of picture types in the case Treppenlicht of a choose on
    "Betriebsart"."""
    pictures = [(9, 'Schaltbild Treppenlicht', 'Schaltbild', BG_DIAGRAM), (10, 'Kurve Treppenlicht', 'Kurve', BG_CURVE),
        (11, 'Fehlendes Bild', 'Ohne', BG_ABSENT)]
    extension = '<Extension>' + ''.join('<Baggage RefId="%s" />' % p[3] for p in pictures) + '</Extension>\n'
    xml = replace_once(bcu1_program(program, number, name, True), '<Options ', extension + '<Options ')
    xml = replace_once(xml, '</ParameterTypes>', ''.join('<ParameterType Id="%s_PT-%s" Name="%s"><TypePicture RefId="%s" />'
        '</ParameterType>\n' % (program, t, t, ref) for _, _, t, ref in pictures) + '</ParameterTypes>')
    xml = replace_once(xml, '</Parameters>', ''.join('<Parameter Id="%s_P-%d" Name="%s" Text="%s" ParameterType="%s_PT-%s" '
        'Access="ReadWrite" Value="" />\n' % (program, n, t, text, program, t) for n, text, t, _ in pictures) + '</Parameters>')
    xml = replace_once(xml, '</ParameterRefs>', ''.join('<ParameterRef Id="%s_P-%d_R-%d" RefId="%s_P-%d" Tag="%d" />\n'
        % (program, n, n, program, n, n) for n, _, _, _ in pictures) + '</ParameterRefs>')
    xml = replace_once(xml, '</ParameterBlock>', '<choose ParamRefId="%s_P-1_R-1"><when test="2">' % program
        + ''.join('<ParameterRefRef RefId="%s_P-%d_R-%d" />' % (program, n, n) for n, _, _, _ in pictures)
        + '</when></choose>\n</ParameterBlock>')
    return xml


baggage_archive('src/test/fixtures/TestPictures.knxprod', [
    ('M-0008/Baggages/TEST_Schaltbild.png', png(3, 2, (255, 255, 0))),
    ('M-0008/Baggages/TEST_Kurve.bmp', bmp(2, 2, (0, 128, 255))),
    ('M-0008/' + PICTURES_PROGRAM + '.xml', pictures_program(PICTURES_PROGRAM, 65458,
        'TEST: Bilder im Parameterdialog (künstliche Testdaten)')),
    ('M-0008/Hardware.xml', baggage_hardware(PICTURES_HARDWARE, 'TEST Bilder', PICTURES_PROGRAM, [])),
    ('M-0008/Baggages.xml', baggages_xml([(BG_DIAGRAM, '', 'TEST_Schaltbild.png', '2026-11-12T10:00:00Z'),
        (BG_CURVE, '', 'TEST_Kurve.bmp', '2026-11-12T10:00:00Z'),
        (BG_ABSENT, '', 'TEST_Ohne.png', '2026-11-12T10:00:00Z')]))])


# TestValueForms.knxprod: the value forms of Theben's complete database (haumacher/ehome #160), see the module comment.
VALUES_PROGRAM = 'M-0008_A-FFB3-01-0000'
VALUES_HARDWARE = 'M-0008_H-TEST.20WERTFORMEN-1'
STEPS_PROGRAM = 'M-0008_A-FFB4-01-0000'
STEPS_HARDWARE = 'M-0008_H-TEST.20SCHRITTE-1'


def value_forms_program(program, number, name):
    """A BCU 1 program with an enumeration of 8 bits whose entry -60% has the value 4294967236 (its 32-bit form, as
    Theben writes it), a parameter "Versatz" of it with that default, and a separator renamed (with the block) while
    "Betriebsart" is Treppenlicht."""
    xml = bcu1_program(program, number, name, True)
    xml = replace_once(xml, '</ParameterTypes>', '<ParameterType Id="{p}_PT-Versatz" Name="Versatz">'
        '<TypeRestriction Base="Value" SizeInBit="8">'
        '<Enumeration Id="{p}_PT-Versatz_EN-4294967236" Text="-60%" Value="4294967236" DisplayOrder="0" />'
        '<Enumeration Id="{p}_PT-Versatz_EN-0" Text="0%" Value="0" DisplayOrder="1" />'
        '<Enumeration Id="{p}_PT-Versatz_EN-5" Text="5%" Value="5" DisplayOrder="2" />'
        '</TypeRestriction></ParameterType>\n</ParameterTypes>')
    xml = replace_once(xml, '</Parameters>', '<Parameter Id="{p}_P-9" Name="Versatz" Text="Versatz" '
        'ParameterType="{p}_PT-Versatz" Access="ReadWrite" Value="4294967236">'
        '<Memory CodeSegment="{p}_AS-0100" Offset="84" BitOffset="0" /></Parameter>\n</Parameters>')
    xml = replace_once(xml, '</ParameterRefs>', '<ParameterRef Id="{p}_P-9_R-9" RefId="{p}_P-9" Tag="9" />\n'
        '</ParameterRefs>')
    xml = replace_once(xml, '</ParameterBlock>', '<ParameterRefRef RefId="{p}_P-9_R-9" />\n'
        '<ParameterSeparator Id="{p}_PS-1" Text="Überschrift" UIHint="Headline" />\n'
        '<choose ParamRefId="{p}_P-1_R-1"><when test="2">'
        '<Rename Id="{p}_PR-1" RefId="{p}_PS-1" Text="Überschrift Treppenlicht" />'
        '<Rename Id="{p}_PR-2" RefId="{p}_PB-1" Text="Treppenlicht" />'
        '</when></choose>\n</ParameterBlock>')
    return xml.replace('{p}', program)


def value_forms_hardware():
    """The hardware of both programs of TestValueForms."""
    first = baggage_hardware(VALUES_HARDWARE, 'TEST Wertformen', VALUES_PROGRAM, [])
    second = baggage_hardware(STEPS_HARDWARE, 'TEST Schritte', STEPS_PROGRAM, [])
    begin = '<Manufacturer RefId="M-0008"><Hardware>'
    end = '</Hardware></Manufacturer>'
    inner = second[second.index(begin) + len(begin):second.index(end)]
    return replace_once(first, end, inner + end)


baggage_archive('src/test/fixtures/TestValueForms.knxprod', [
    ('M-0008/' + VALUES_PROGRAM + '.xml', value_forms_program(VALUES_PROGRAM, 65459,
        'TEST: Wertformen der Produktdaten (künstliche Testdaten)')),
    ('M-0008/' + STEPS_PROGRAM + '.xml', bcu1_program(STEPS_PROGRAM, 65460,
        'TEST: Zweites Programm der Produktdaten (künstliche Testdaten)', False)),
    ('M-0008/Hardware.xml', value_forms_hardware())])


def hardware_list(entries):
    """One hardware file with a hardware (and its product) per (hardware ID, name, program) entry."""
    begin = '<Manufacturer RefId="M-0008"><Hardware>'
    end = '</Hardware></Manufacturer>'
    files = [baggage_hardware(hardware, name, program, []) for hardware, name, program in entries]
    inner = ''.join(f[f.index(begin) + len(begin):f.index(end)] for f in files[1:])
    return replace_once(files[0], end, inner + end)


# TestChannelNumber.knxprod: a channel numbered in its own notation (haumacher/ehome #162), see the module comment.
CHANNEL_NUMBER_PROGRAM = 'M-0008_A-FFA1-01-0000'
CHANNEL_NUMBER_HARDWARE = 'M-0008_H-TEST.20KANALNUMMER-1'


def channel_number_program(program, number, name):
    """A BCU 1 program whose channel is numbered "18d", as Theben's M-0048_A-F140-13-8536 numbers its logic channels."""
    xml = bcu1_program(program, number, name, True)
    return replace_once(xml, 'Number="1" Id="' + program + '_CH-1"', 'Number="18d" Id="' + program + '_CH-18d"')


baggage_archive('src/test/fixtures/TestChannelNumber.knxprod', [
    ('M-0008/' + CHANNEL_NUMBER_PROGRAM + '.xml', channel_number_program(CHANNEL_NUMBER_PROGRAM, 65441,
        'TEST: Kanalnummer als Text (künstliche Testdaten)')),
    ('M-0008/Hardware.xml', hardware_list([(CHANNEL_NUMBER_HARDWARE, 'TEST Kanalnummer', CHANNEL_NUMBER_PROGRAM)]))])


# TestPlugins.knxprod: programs needing ETS plug-ins or external software (haumacher/ehome #159), see the module comment.
PLUGIN_PROGRAMS = [
    # (program, application number, name, hardware, hardware name, attributes of the extension)
    ('M-0008_A-FFA2-01-0000', 65442, 'TEST: Plug-in der Bedienoberfläche (künstliche Testdaten)',
        'M-0008_H-TEST.20PLUGIN.20UI-1', 'TEST Plugin UI', 'EtsUiPlugin="{00000000-0000-0000-0000-0000000000A1}"'),
    ('M-0008_A-FFA3-01-0000', 65443, 'TEST: Download-Plug-in (künstliche Testdaten)',
        'M-0008_H-TEST.20PLUGIN.20DOWNLOAD-1', 'TEST Plugin Download',
        'EtsUiPlugin="{00000000-0000-0000-0000-0000000000A2}" EtsDownloadPlugin="{00000000-0000-0000-0000-0000000000B2}"'),
    ('M-0008_A-FFA4-01-0000', 65444, 'TEST: Daten-Handler und externe Software (künstliche Testdaten)',
        'M-0008_H-TEST.20PLUGIN.20DATEN-1', 'TEST Plugin Daten',
        'EtsDataHandler="Test.DataHandler" RequiresExternalSoftware="true"'),
]


def plugin_program(program, number, name, attributes):
    """A BCU 1 program whose extension declares the given attributes (plug-ins, a data handler, external software)."""
    xml = bcu1_program(program, number, name, True)
    return replace_once(xml, '<Options ', '<Extension ' + attributes + ' />\n<Options ')


baggage_archive('src/test/fixtures/TestPlugins.knxprod', [
    ('M-0008/' + p + '.xml', plugin_program(p, n, name, attributes)) for p, n, name, _, _, attributes in PLUGIN_PROGRAMS]
    + [('M-0008/Hardware.xml', hardware_list([(h, hn, p) for p, _, _, h, hn, _ in PLUGIN_PROGRAMS]))])


# TestResources.knxprod and TestResourcesNewer.knxprod: the icons and help texts of a program (haumacher/ehome #156,
# #157), see the module comment.
RESOURCES_PROGRAM = 'M-0008_A-FFB5-01-0000'
RESOURCES_HARDWARE = 'M-0008_H-TEST.20SYMBOLE-1'
BG_ICONS = 'M-0008_BG--TEST.5FSymbole.2Ezip'
BG_HELP_DE = 'M-0008_BG--TEST.5FHilfe.5FDE.2Ezip'
BG_HELP_EN = 'M-0008_BG--TEST.5FHilfe.5FEN.2Ezip'
BG_HELP_FR = 'M-0008_BG--TEST.5FHilfe.5FFR.2Ezip'
TEST_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><script>alert("TEST")</script>'
    '<rect width="2" height="2" fill="black"/></svg>')


def files_archive(files):
    """A ZIP archive of the given files (name, text or bytes), made for the test."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, content in files:
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 13, 10, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content.encode('utf-8') if isinstance(content, str) else content)
    return buffer.getvalue()


def windows_text(text):
    """A text as ETS writes it on Windows: UTF-8 with a byte order mark, CRLF line ends."""
    return b'\xef\xbb\xbf' + text.replace('\n', '\r\n').encode('utf-8')


RESOURCE_ICONS = files_archive([('TEST_Aus.png', png(2, 2, (255, 0, 0))), ('TEST_Ein.png', png(2, 2, (0, 255, 0))),
    ('TEST_Kanal.png', png(2, 2, (0, 0, 255))), ('TEST_Block.png', png(2, 2, (128, 128, 128))),
    ('TEST_Vektor.svg', TEST_SVG)])
RESOURCE_ICONS_NEWER = files_archive([('TEST_Aus.png', png(2, 2, (255, 0, 0))),
    ('TEST_Ein.png', png(2, 2, (255, 255, 255))), ('TEST_Kanal.png', png(2, 2, (0, 0, 255))),
    ('TEST_Block.png', png(2, 2, (128, 128, 128))), ('TEST_Vektor.svg', TEST_SVG),
    ('TEST_Fehlt.png', png(2, 2, (255, 255, 0)))])
RESOURCE_HELP_DE = files_archive([
    ('Allgemein.txt', windows_text('**Allgemein:** künstlicher Hilfetext für den Test.\n\n<script>alert("TEST")</script>')),
    ('Betriebsart.txt', windows_text('**Schalten:** Der Ausgang folgt dem Objekt.\n\n'
        '***Treppenlicht***: Der Ausgang schaltet nach der *Zeit* aus.\nZweite Zeile.\n***\n**Hinweis:** künstlich')),
    ('Leer.txt', windows_text('  \n')),
    ('NurDeutsch.txt', windows_text('Nur auf Deutsch.')),
    ('GrXYe.txt', windows_text('Die *Größe* in Zentimetern.'))]).replace(b'GrXYe', b'Gr\x94\xe1e')
RESOURCE_HELP_EN = files_archive([
    ('Allgemein.txt', '**General:** artificial help text for the test.'),
    ('Betriebsart.txt', '**Switching:** the output follows the object.')])


def resources_program(program, number, name):
    """A BCU 1 program naming icons and help topics in the archives of its baggage."""
    xml = bcu1_program(program, number, name, True)
    xml = replace_once(xml, 'DefaultLanguage="de-DE"', 'IconFile="%s" ContextHelpFile="%s" DefaultLanguage="de-DE"'
        % (BG_ICONS, BG_HELP_DE))
    for value, icon in ((0, 'TEST_Aus'), (1, 'TEST_Ein'), (2, 'TEST_Fehlt')):
        xml = replace_once(xml, 'Id="%s_PT-Betriebsart_EN-%d"' % (program, value),
            'Id="%s_PT-Betriebsart_EN-%d" Icon="%s"' % (program, value, icon))
    xml = replace_once(xml, 'Id="%s_CH-1">' % program, 'Id="%s_CH-1" Icon="TEST_Kanal">' % program)
    xml = replace_once(xml, 'Id="%s_PB-1" Name="Allgemein" Text="Allgemein"' % program,
        'Id="%s_PB-1" Name="Allgemein" Text="Allgemein" Icon="TEST_Block" HelpContext="Allgemein"' % program)
    for n, attributes in ((1, 'HelpContext="Betriebsart"'), (2, 'HelpContext="Leer"'), (3, 'HelpContext="NurDeutsch"'),
            (4, 'Icon="TEST_Vektor" HelpContext="Größe"')):
        xml = replace_once(xml, '<ParameterRefRef RefId="%s_P-%d_R-%d" />' % (program, n, n),
            '<ParameterRefRef RefId="%s_P-%d_R-%d" %s />' % (program, n, n, attributes))
    extension = ('<Extension>' + ''.join('<Baggage RefId="%s" />' % b for b in (BG_ICONS, BG_HELP_DE, BG_HELP_EN))
        + '</Extension>\n')
    xml = replace_once(xml, '<Options ', extension + '<Options ')

    def language(identifier, help_file):
        return ('<Language Identifier="%s"><TranslationUnit RefId="%s" Version="0"><TranslationElement RefId="%s">'
            '<Translation AttributeName="ContextHelpFile" Text="%s" /></TranslationElement></TranslationUnit></Language>'
            % (identifier, program, program, help_file))
    languages = ('<Languages>' + language('de-DE', BG_HELP_DE) + language('en-US', BG_HELP_EN)
        + language('fr-FR', BG_HELP_FR) + '</Languages>')
    return replace_once(xml, '</ApplicationPrograms>', '</ApplicationPrograms>' + languages)


def resources_archive(target, icons, icons_time):
    baggage_archive(target, [
        ('M-0008/Baggages/TEST_Symbole.zip', icons),
        ('M-0008/Baggages/TEST_Hilfe_DE.zip', RESOURCE_HELP_DE),
        ('M-0008/Baggages/TEST_Hilfe_EN.zip', RESOURCE_HELP_EN),
        ('M-0008/' + RESOURCES_PROGRAM + '.xml', resources_program(RESOURCES_PROGRAM, 65461,
            'TEST: Symbole und Hilfe (künstliche Testdaten)')),
        ('M-0008/Hardware.xml', baggage_hardware(RESOURCES_HARDWARE, 'TEST Symbole', RESOURCES_PROGRAM, [])),
        ('M-0008/Baggages.xml', baggages_xml([(BG_ICONS, '', 'TEST_Symbole.zip', icons_time),
            (BG_HELP_DE, '', 'TEST_Hilfe_DE.zip', '2026-11-13T10:00:00Z'),
            (BG_HELP_EN, '', 'TEST_Hilfe_EN.zip', '2026-11-13T10:00:00Z')]))])


resources_archive('src/test/fixtures/TestResources.knxprod', RESOURCE_ICONS, '2026-11-13T10:00:00Z')
resources_archive('src/test/fixtures/TestResourcesNewer.knxprod', RESOURCE_ICONS_NEWER, '2026-11-14T10:00:00Z')
RESOURCES_PROJECT_PROGRAM = 'M-0008_A-FFB6-01-0000'
baggage_archive('src/test/fixtures/TestResources.knxproj', [
    ('M-0008/Baggages.xml', baggages_xml([(BG_ICONS, '', 'TEST_Symbole.zip', '2026-11-13T10:00:00Z'),
        (BG_HELP_DE, '', 'TEST_Hilfe_DE.zip', '2026-11-13T10:00:00Z'),
        (BG_HELP_EN, '', 'TEST_Hilfe_EN.zip', '2026-11-13T10:00:00Z')])),
    ('M-0008/Baggages/TEST_Symbole.zip', RESOURCE_ICONS),
    ('M-0008/Baggages/TEST_Hilfe_DE.zip', RESOURCE_HELP_DE),
    ('M-0008/Baggages/TEST_Hilfe_EN.zip', RESOURCE_HELP_EN),
    ('M-0008/' + RESOURCES_PROJECT_PROGRAM + '.xml', resources_program(RESOURCES_PROJECT_PROGRAM, 65462,
        'TEST: Symbole und Hilfe im Projekt (künstliche Testdaten)'))],
    project=True, project_name='TestResources')
