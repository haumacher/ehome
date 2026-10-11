#!/usr/bin/env python3
"""Creates the test fixtures for TestEtsImport from samples/Test.knxproj.

TestDetails.knxproj: the samples contain no device names, descriptions, serial numbers, unassigned
devices, inactive communication objects or details of group ranges and addresses. This copy of the
Test project adds them, and marks the device as fully downloaded after its last change.

The samples contain no building parts and place no device. TestDetails.knxproj adds a building
"Haus" with the floor "Erdgeschoss", holding the room "Küche", in which the device "Taster Küche" is
placed, and an empty distribution board "Verteiler".

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

The projects of both fixtures are named after the fixture, not "Test".

Usage (from the project root): python3 src/test/fixtures/make-fixtures.py
"""
import zipfile

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

    # A building with a floor, a room holding the device, and an empty distribution board.
    buildings = '<Buildings />'
    assert xml.count(buildings) == 1
    xml = xml.replace(buildings, '<Buildings>\n'
        + '          <BuildingPart Id="P-01A0-0_BP-1" Name="Haus" Type="Building">\n'
        + '            <BuildingPart Id="P-01A0-0_BP-2" Name="Erdgeschoss" Type="Floor">\n'
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


def write(target, modify):
    with zipfile.ZipFile(SOURCE) as source, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for info in source.infolist():
            data = source.read(info.filename)
            name = info.filename
            if name.endswith('.xml'):
                text = data.decode('utf-8-sig')
                modified = modify(name, text)
                if modified != text:
                    data = modified.encode('utf-8')
                if modify is dynamic:
                    name = name.replace(VERSION, NEW_VERSION)
            info.filename = name
            out.writestr(info, data)


def keep_details(name, xml):
    if name == PROJECT_INFO:
        return rename_project(xml, 'TestDetails')
    return details(xml) if name == PROJECT else xml


write('src/test/fixtures/TestDetails.knxproj', keep_details)
write('src/test/fixtures/TestDynamic.knxproj', dynamic)
