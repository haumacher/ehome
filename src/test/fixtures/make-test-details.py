#!/usr/bin/env python3
"""Creates TestDetails.knxproj from samples/Test.knxproj for TestEtsImport.

The samples contain no device names, descriptions, serial numbers, unassigned devices, inactive
communication objects or details of group ranges and addresses. This copy of the Test project adds
them, and marks the device as fully downloaded after its last change.

Usage (from the project root): python3 src/test/fixtures/make-test-details.py
"""
import zipfile

SOURCE = 'samples/Test.knxproj'
TARGET = 'src/test/fixtures/TestDetails.knxproj'
PROJECT = 'P-01A0/0.xml'


def modify(xml):
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


with zipfile.ZipFile(SOURCE) as source, zipfile.ZipFile(TARGET, 'w', zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():
        data = source.read(info.filename)
        if info.filename == PROJECT:
            data = modify(data.decode('utf-8-sig')).encode('utf-8')
        target.writestr(info, data)
