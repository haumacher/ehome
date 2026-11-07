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
- The program gets a new ID (version O0023, so that an import does not skip it as known) and a name
  marking it as test data. It adds a channel independent block whose switch places one parameter
  reference and one communication object reference in two cases (ETS 6 shares a reference between
  cases), a parameter block directly in the program with a color parameter (TypeColor), and a
  dynamic module (ModuleDefs, a module in a case), which E-Home does not import.
- The project part is the folder P-01A0 with project.xml (lower case, ETS 5/6) and 0.xml: the
  device lies in a segment of its line, names its communication objects by IDs relative to its
  program and links them to group addresses by the attribute Links (IDs relative to the
  installation; one object sends to GA-3 and receives from GA-1), reports its load state as "true"
  and "false", sets the shared parameter, sets a parameter of an instance of the module and links one
  of its objects (IDs MD-1_M-1_MI-1_..., which do not resolve, since the module is not imported), and
  the building is a location with spaces.
The test builds the protected variants (the project part as P-01A0.zip, AES and password, or plain)
from this file with zip4j.

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

Usage (from the app module, ehome/ in the repository): python3 src/test/fixtures/make-fixtures.py
"""
import base64
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


def function_program(program, number, name, channels):
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
    dynamic = ''.join('<Channel Name="%s" Text="%s" Number="%d" Id="{p}_CH-%d"><ParameterBlock Id="{p}_PB-%d" '
        'Name="Objekte" Text="Objekte">' % (label, label, c, c, c)
        + ''.join('<ComObjectRefRef RefId="{p}_O-%d_R-%d" />' % (o[0], o[0] + 1) for o in objects if o[1] == c)
        + '</ParameterBlock></Channel>\n' for c, (label, _) in enumerate(channels, 1))
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<KNX xmlns="http://knx.org/xml/project/11" CreatedBy="make-fixtures.py" ToolVersion="test">\n'
        '<ManufacturerData><Manufacturer RefId="M-0008"><ApplicationPrograms>\n'
        '<ApplicationProgram Id="{p}" Name="' + name + '" ApplicationNumber="' + str(number) + '" '
        'ApplicationVersion="1" ProgramType="ApplicationProgram" MaskVersion="MV-0012" '
        'LoadProcedureStyle="DefaultProcedure" PeiType="0" DefaultLanguage="de-DE" DynamicTableManagement="1" '
        'Linkable="0" MinEtsVersion="4.0" NonRegRelevantDataVersion="0">\n'
        '<Static>\n<Code><AbsoluteSegment Id="' + segment + '" Address="256" Size="256" UserMemory="0" /></Code>\n'
        '<ComObjectTable CodeSegment="' + segment + '" Offset="54">\n' + comobjects + '</ComObjectTable>\n'
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


def function_devices(xml, specs):
    start = xml.index('<DeviceInstance Id="P-01A0-0_DI-1" ')
    end = xml.index('</DeviceInstance>', start) + len('</DeviceInstance>')
    addresses = sorted(FUNCTION_ADDRESSES, key=ga_value)
    devices = ''
    for n, (index, device_name, _, connected) in enumerate(specs, 1):
        program, number, _, _, channels = FUNCTION_PROGRAMS[index]
        objects = [(o[0], o[6] if connected is None else connected.get(o[0])) for o in function_objects(channels)]
        refs = ''.join('<ComObjectInstanceRef RefId="' + program + '_O-%d_R-%d" IsActive="1"><Connectors>'
            '<Send GroupAddressRefId="P-01A0-0_GA-%d" /></Connectors></ComObjectInstanceRef>\n'
            % (o, o + 1, addresses.index(address) + 1) for o, address in objects if address is not None)
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
                % (addresses.index(a) + 1, ga_value(a), FUNCTION_ADDRESSES[a])
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


def function_fixture(project, specs):
    def modify(name, xml):
        if name == PROJECT_INFO:
            return rename_project(xml, project)
        if name == PROJECT:
            return function_devices(xml, specs)
        if name == 'M-0008/Hardware.xml':
            return function_hardware(xml)
        return xml
    return modify


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
ETS6_VERSION = '10D7-11-A0E5-O0023'
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
    for end, content in (('</ParameterTypes>', types), ('</Parameters>', params), ('</ParameterRefs>', refs),
                         ('</ComObjectTable>', obj), ('</ComObjectRefs>', objref)):
        xml = replace_once(xml, end, content + end)
    shared = ('<ParameterRefRef RefId="' + p + 'P-9102_R-9102" /><ComObjectRefRef RefId="' + p + 'O-9110_R-9110" />')
    module = ('<Module Id="' + p + 'MD-1_M-1" RefId="' + p + 'MD-1"><NumericArg RefId="' + p
        + 'MD-1_A-1" Value="0" /></Module>')
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
    # dynamic module, which E-Home does not import: both are left out with a warning.
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


write('src/test/fixtures/TestDetails.knxproj', keep_details)
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
master_data_only('src/test/fixtures/TestMasterDataOld.knxproj', 1001, False)
master_data_only('src/test/fixtures/TestMasterDataNew.knxproj', 1002, True)
