# Programming devices (download over KNXnet/IP)

E-Home programs a device of a project over a KNXnet/IP gateway with Calimero
(`de.haumacher.ehome.programming.download`, haumacher/ehome #7). This document is the procedure for
the hardware: how to compare a device with its download image (read-only, safe), what a download
does, how to run the first real download on the test system, and how to recover a device with ETS.

## The rules

- **Writing to the bus is switched off by default.** Only the application started with
  `-Dehome.programming.writeEnabled=true` enables the commands "Herunterladen" and "Physikalische
  Adresse programmieren"; otherwise they are disabled with that reason. Every write to the bus goes
  through the one class `CalimeroWriter`, which refuses to open without the switch
  (`TestBusWritePath` checks that no other source calls a Calimero write service).
- **The test system only.** The test system is reached through the IP router 192.168.178.81:3671
  (MAC 00-24-6D-01-58-64). The other router the search finds, "KNX IP Router 750", is the home
  installation: never choose it as the gateway of a project you program or compare.
- Comparing never writes: it reads the device descriptor, the program version, the load states and
  the memory of every block of the image.

## Where

Project → tab "Geräte" → a device → tab "Programmierung":

- "Gateway wählen": the project's gateway (`Project#gateway`), entered (`192.168.178.81` or
  `192.168.178.81:3671`) or taken from a search.
- The plan of the full download: every operation with its step of the load procedure, its
  addresses and bytes; the blocks of the image the procedure does not load; the reasons a download
  is refused. "Download-Abbild" shows the image.
- "Mit dem Gerät vergleichen": compares the device with the image; the report lists per block
  "Identisch" or the differing addresses.
- "Herunterladen": asks for confirmation naming the device, its address and the gateway, then runs
  the plan with progress, and reports success (operations, duration) or the failing operation with
  the device's answer. On success the device records the download (`loaded`, `lastDownload`).
- "Physikalische Adresse programmieren": writes the device's individual address into the one device
  whose programming button is pressed.

## Comparing (safe)

1. Start an app (any port), log in, import the project of the test system.
2. Set the project's gateway to `192.168.178.81:3671`.
3. On a device's tab "Programmierung" press "Mit dem Gerät vergleichen".

Result of 2026-10-11 against the test system with `samples/EHomeDemo.knxproj` (the devices hold the
configuration of another ETS project, so differences are expected):

- Actuator 1.3.5 (MDT AKK-0416.03): mask 07 05, program version 00 83 00 0C 21 equal to the
  program's, load state machines 1-3 loaded; address table 4 of 7 bytes differ, association table
  3 of 7, object table segment 4400 20 of 236 (configuration bytes of the objects with other
  associations), parameter segment 44EC 43 of 304.
- Tastsensor 1.3.50 (Gira 3fach komfort): mask 07 05, program version 00 08 10 D7 11 equal,
  load state machines loaded; address table 1 of 7 (the count), association table 6 of 7,
  segments 41E5 38, 435C 4, 4369 3, 436F 115, 4439 1, 444E 50 differing bytes; 454E not compared
  (ETS does not load it).

With the project the devices were programmed from (`samples/KNX-Testsystem.knxproj`), the
comparison must report every block identical (once the image rules found with that project, the
type byte of the object table, are in the download image). That is the check before the first
download: a difference there is a defect of the image, and the download must wait for its fix.

## What a download does (mask 7.5, BIM M112)

The program's own load procedure (`ProductProcedure`), as the plan lists it:

1. Connect; read the device descriptor and stop unless it is the program's mask (0705).
2. The actuator only: compare the hardware type (object 0, property 78) with the program's; stop
   on a difference. Nothing is written before these checks.
3. Unload the load state machines 1 (address table), 2 (association table), 3 (application): a
   load control (10 bytes) into property 5 of the interface objects 1, 2, 3; the device must answer
   "unloaded".
4. Per load state machine: start loading; allocate the segments (`AbsSegment`); the task segment
   with the program's identity (PEI type, manufacturer, application number, version); for the
   Tastsensor also "task control 1"; write the memory of the image into the segments (at most 12
   bytes per write, each read back and compared); load completed, the device must answer "loaded".
5. After the application: read the program version (object 3, property 13) and stop unless it is
   the program's (this checks the task segment encoding on the device).
6. The Tastsensor: write the download stamps (property 27 of objects 1, 2, 3: length, access, CRC of
   each stamped segment).
7. Restart, disconnect.

Verified against what ETS left in the test devices: the table pointers, the program versions and
all eight download stamps of the Tastsensor (length, access and CRC-16/CCITT over the memory) equal
what the plan computes. Not verified before the first download:

- The order of the bytes of the task segment control: `03 02 <address> <PEI type> <manufacturer>
  <application number> <version>` (KNX specification). Step 5 detects a wrong order; the device is
  then loaded with a wrong program version and is reloaded with ETS.
- The answer of the device to a load control: E-Home takes a one-byte answer as the load state and
  otherwise reads property 5; transitional states (unloading, load completing) are read again until
  they settle.
- The Tastsensor's last step `TaskSegment LsmIdx=5` is not executed: mask 7.5 has no interface
  object 5 for a property-controlled load state machine. The plan shows it as "Not executed". The
  object table address is part of the application header, so the device does not need it; the
  first download of the Tastsensor shows whether that holds.
- The zero fill: the segment of the Tastsensor's association table (dynamic table management) is
  written up to its end with zeros, so that its stamp's CRC describes the memory (the device held
  zeros there after ETS).

## The first real download (the user runs it)

Start with the actuator 1.3.5: no stamps, no skipped step, the hardware type checked first.

1. Make sure ETS can restore the devices: the ETS project of the test system is at hand and was
   last downloaded from that project.
2. Stop nothing of the home installation; connect only to 192.168.178.81.
3. Start an app with writing switched on, e.g.
   `./mvnw exec:java -Dtl.port=8081 -Dehome.programming.writeEnabled=true` (with its own data dir:
   environment `h2_name` and `tl_storage_path`).
4. Import `samples/KNX-Testsystem.knxproj`; set the project's gateway to `192.168.178.81:3671`.
5. Actuator → "Programmierung" → "Mit dem Gerät vergleichen": every block identical.
6. Read the plan; then "Herunterladen", confirm the dialog naming 1.3.5 and 192.168.178.81:3671.
   The download takes about 20-40 s. During it, the actuator does not switch.
7. The report says success; compare again: every block identical. Switch a channel on the bus
   (wall switch or ETS group monitor) to see the actuator work.
8. Change a parameter in E-Home (e.g. a channel's operating mode), download, observe the changed
   behavior on the bus (acceptance of #7).
9. Then the Tastsensor 1.3.50 the same way. If step 5 of the download fails (program version), or
   the device does not work after the restart, recover it with ETS.

A download that fails stops at the failing operation; the report names it and the device's answer.
The device may then be unloaded (its LED or behavior shows no application): recover it with ETS.

## Recovering a device with ETS

1. In ETS open the project of the test system, select the device.
2. Download → "Applikationsprogramm" (full download). If ETS reports that the device is in an
   unexpected state, use "Gerät entladen" first, then the full download.
3. If the individual address is lost (a download of E-Home never writes it; only "Physikalische
   Adresse programmieren" does), program it in ETS with the programming button.

## Not supported (refused with the reason)

- Masks whose load state machines are controlled in memory instead of properties (no feature
  `PropertyMappedLsms`), and step kinds other than Connect, Disconnect, Unload, Load, AbsSegment
  (types 0 and 1), TaskSegment, TaskCtrl1, LoadCompleted, LoadImageProp (download stamps),
  CompareProp, WriteProp, WriteMem / CompareMem (standard memory), Restart, Delay,
  SetControlVariable (verify, optimistic write), ProgressText.
- The IP router (mask 9.1A): its procedure writes the coupler's slave and filter memories
  (`LcSlave`, `LcFilter`, `ClearLCFilterTable`), and its parameters are bit fields within bytes.
- The partial download (parameters and tables only), KNX Secure, RF, firmware.
