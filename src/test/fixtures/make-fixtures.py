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

Usage (from the project root): python3 src/test/fixtures/make-fixtures.py
"""
import base64
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


def write(target, modify, additions=()):
    with zipfile.ZipFile(SOURCE) as source, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for name, text in additions:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 11, 12, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, text.encode('utf-8'))
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
write('src/test/fixtures/TestBcu1.knxproj', bcu1,
    [('M-0008/' + program + '.xml', bcu1_program(program, number, name, with_data))
     for program, _, number, name, with_data in BCU1_PROGRAMS])
