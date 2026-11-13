# Programming devices (download over KNXnet/IP)

E-Home programs a device of a project over a KNXnet/IP gateway with Calimero
(`de.haumacher.ehome.programming.download`, haumacher/ehome #7), or through the project's controller
connected to E-Home (haumacher/ehome #148, section "Through a controller"). This document is the procedure for
the hardware: how to compare a device with its download image (read-only, safe), what a download
does, how to run the first real download on the test system, and how to recover a device with ETS.

## The rules

- **Writing to the bus is switched off by default.** Only the configuration variable
  `tl_ehome_programming_write_enabled=true` (alias `%EHOME_PROGRAMMING_WRITE_ENABLED%` of
  `WEB-INF/conf/ehomeConf.xml`, read by `de.haumacher.ehome.settings.OperatorSettings`, haumacher/ehome
  #149) enables the commands "Herunterladen" and "Physikalische Adresse programmieren"; otherwise they
  are disabled with that reason. It is set like every setting: in a development start as a system
  property (`-Dtl_ehome_programming_write_enabled=true`) or an environment variable, in the Debian
  package as a line of `/etc/ehome/ehome.env` (a JNDI entry of E-Home's context, `doc/debian.md`).
  Every write to the bus goes through the one class `CalimeroWriter`, which refuses to open without
  the switch (`TestBusWritePath` checks that no other source calls a Calimero write service, and that
  no source names the former system property).
- **Only an installation that reaches local networks talks to a gateway.** With
  `tl_ehome_local_network=false` (a hosted instance, `doc/debian.md`) the project's gateway address is
  kept but every connection to it is refused, and "Gateway wählen" offers only the project's
  controllers: the path through a controller is what stays available.
- **The test system only.** The test system is reached through the IP router 192.168.178.81:3671
  (MAC 00-24-6D-01-58-64). The other router the search finds, "KNX IP Router 750", is the home
  installation: never choose it as the gateway of a project you program or compare.
- Comparing never writes: it reads the device descriptor, the program version, the load states and
  the memory of every block of the image.

## Where

Project → tab "Geräte" → a device → tab "Programmierung":

- "Gateway wählen": the project's path to the bus, shown above as "Gateway 192.168.178.81:3671" or
  "Controller homecontrol-f883ec": a gateway (`Project#gateway`), entered (`192.168.178.81` or
  `192.168.178.81:3671`) or taken from a search, or one of the project's enrolled controllers
  (`Project#programmingController`). The two are exclusive: "Übernehmen" sets the one chosen and
  clears the other.
- The plan of the full download: every operation with its step of the load procedure, its
  addresses and bytes; the blocks of the image the procedure does not load; the reasons a download
  is refused. "Download-Abbild" shows the image.
- "Mit dem Gerät vergleichen": compares the device with the image; the report lists per block
  "Identisch" or the differing addresses.
- "Herunterladen": asks for confirmation naming the device, its address and the path ("über Gateway
  192.168.178.81:3671" or "über Controller homecontrol-f883ec"), then runs
  the plan with progress, and reports success (operations, duration) or the failing operation with
  the device's answer. On success the device records the download (`loaded`, `lastDownload`) and,
  for a full download, the revision of the project data the plan was computed from
  (`downloadRevision`): the device's state in that revision is what the real device holds. The tab
  "Geänderte Werte" lists the changes since (values and connections, grouped by page, with
  "Gerät: …" and the way back per entry, and "Alle Änderungen seit dem letzten Download
  verwerfen"); the confirmation of "Herunterladen" names their number (haumacher/ehome #15). For a
  device ETS had downloaded, the state as imported is the reference until the first download from
  E-Home.
- "Physikalische Adresse programmieren": writes the device's individual address into the one device
  whose programming button is pressed.

## Through a controller (haumacher/ehome #148)

An E-Home that is not in the installation's network (the hosted one) cannot reach a gateway there;
the installation's controller is connected to it and holds a tunnel to the gateway. A **bus session**
on the controller's E-Home connection (homecontrol #85, `spec/api.md` "Bus session") passes cEMI
frames in both directions; the controller only forwards. Everything that uses the gateway uses it
alike: the comparison, the download, the individual address, the scan, the read-back and the LED.

- **The link.** `connection.BusSessionLink` is a Calimero `KNXNetworkLink` (an `AbstractLink`) over
  the session: opened on the first use of a job (`open`; the tunnel's individual address the controller
  reports becomes the link's own address, `max_apdu` its longest APDU), every frame a `send` with a
  reference, closed when the job ends (`close`). One frame waits for its outcome at a time, in order:
  `confirmed` is the positive confirmation, `negative` and `no_confirmation` fail a send that waits for
  the confirmation (the transport connection repeats, as with a lost acknowledgement), `lost` closes the
  link; `bus.queue_full` is waited out (20 times 50 ms). The indications of `frames` go to the link's
  listeners: E-Home's transport connection (`TransportClient`) and Calimero's management procedures
  run on it unchanged. The path is chosen by `programming.download.BusPath` (the gateway's tunnel or
  the controller's session); `CalimeroReader`, `CalimeroWriter` and `scan.CalimeroScanBus` open their
  link there.
- **The choice.** "Gateway wählen" lists the project's enrolled controllers with their connection
  and whether programming through them is possible now. No fallback: a project programmed through its
  controller never tries a gateway, and the other way round.
- **One switch.** `CalimeroWriter` stays the one writing class and refuses without
  `tl_ehome_programming_write_enabled` on this path too, before the session is asked for
  (`TestBusWritePath`).
- **Refusals**, as the reason of the disabled command or the failure of the job: the controller is on
  the LAN only (no E-Home connection), not connected (with its last contact), has not reported its
  status yet, its firmware has no bus session (the status lacks `ehome.bus`: update it), a session
  through it is open already (another download, comparison or scan: one at a time, E-Home refuses
  before asking, the controller answers `bus.busy`), its KNX tunnel is down (`bus.tunnel_down`), it
  has no KNX (`bus.knx_unavailable`), any other refusal with the controller's reason and code. The
  session's end by the controller (`bus.idle` after 60 s without frames, `bus.tunnel_down`,
  `bus.overflow`) or the end of the connection closes the link: a download aborts at the failing
  operation with the reason.
- **The scan's "The gateway itself"** (KNXnet/IP local device management) is no frame on the bus and
  is refused through a controller.
- The monitor is not part of it: it attaches to the controller's monitor stream with "Mit einem
  Controller verbinden" (`doc/controllers.md`).

## Comparing (safe)

1. Start an app (any port), log in, import the project of the test system.
2. Set the project's gateway to `192.168.178.81:3671`.
3. On a device's tab "Programmierung" press "Mit dem Gerät vergleichen".

Result of 2026-10-11 against the test system with `ehome/samples/EHomeDemo.knxproj` (the devices hold the
configuration of another ETS project, so differences are expected):

- Actuator 1.3.5 (MDT AKK-0416.03): mask 07 05, program version 00 83 00 0C 21 equal to the
  program's, load state machines 1-3 loaded; address table 4 of 7 bytes differ, association table
  3 of 7, object table segment 4400 20 of 236 (configuration bytes of the objects with other
  associations), parameter segment 44EC 43 of 304.
- Tastsensor 1.3.50 (Gira 3fach komfort): mask 07 05, program version 00 08 10 D7 11 equal,
  load state machines loaded; address table 1 of 7 (the count), association table 6 of 7,
  segments 41E5 38, 435C 4, 4369 3, 436F 115, 4439 1, 444E 50 differing bytes; 454E not compared
  (ETS does not load it).

With the project the devices were programmed from (`ehome/samples/KNX-Testsystem.knxproj`), the
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

## What a download does (BCU 1, mask 0012; haumacher/ehome #13)

A BCU 1 program (e.g. Gira/Siemens switch actuators, push button interfaces and valve drives of the
ETS 2/3 era) keeps everything in the EEPROM 0100-01FF: the system header, the tables, the object
table, the parameters and the application, as the static data of its only segment. The download
image is that segment with the configuration patched in (`DownloadImageBuilder`, `GroupTables`):

| Address | What | Source |
|---|---|---|
| 0100-0115 | system header from the static data (0101-0103 masked out) | the program's segment |
| 0111, 0112 | pointers to the association table and the object table (address - 0100) | mask resources `GroupAssociationTablePtr`, `GroupObjectTablePtr` (`Ptr_StandardMemory100`) |
| 0116 | address table (`AddressTable_Bcu1`): count, individual address (0117-0118, not written), group addresses | mask resource `GroupAddressTable`, the program's `AddressTable` |
| pointer 0111 | association table (`AssociationTable_Bcu1`, the bytes of mask 7.5's) | the program's `AssociationTable` |
| pointer 0112 | object table (`GroupObjectTable_Bcu11`): count, RAM flags pointer (1 byte), 3 bytes per object (value pointer, configuration, type) | the program's `ComObjectTable`, the static data |
| elsewhere | parameters | the program's parameters |

The tables stay at the places the program gives them (the pointers in the static data name them),
also with dynamic table management; a static pointer naming another place, a table at another
place than the mask's `GroupAddressTable`, overlapping tables or a segment without static data
holding the object table are problems. A segment without static data yields a block with exactly
the parameters' bits; bytes nobody writes stay untouched (a byte written only in some bits is still
refused by the plan).

The plan is the mask's generic procedure (`DefaultProcedure`, `Load`/`all`, remote), memory writes
only, each verified by reading it back:

1. Connect; read the device descriptor and stop unless it is 0012.
2. 00 into `RunError` (010D): the application stops.
3. `LoadImageMem 0116`: nothing is sent (ETS does not document the step; the procedure writes the
   same byte from the image at its end, which the plan checks).
4. 01 into 0116: the address table holds only the individual address while the tables change.
5. The image's bytes of 0100, 0104-010C, 010E-0115, 0119-01FE, in chunks of at most 12 bytes.
6. Zeros into the RAM 00CE-00DF (inline data of the procedure).
7. The count of the address table (0116) from the image; FF into `RunError`; restart.

01FF is not written by the procedure: as with ETS the device keeps its content; the plan lists it as
not loaded (static data). Comparing reads the program identity from memory (0104-0107, mask
resource `ApplicationId`), as mask 0012 has no properties.

**Not verified against a device.** There is no BCU 1 device on the test line, and the devices of the
home installation must never be contacted. The tests (`TestBcuImage`, fixture
`ehome/src/test/fixtures/TestBcu1.knxproj`, artificial programs) check the bytes computed by hand from
the rules above and the executor against a mock. The first BCU 1 download needs a BCU 1 device on
the test line (compare first; every block identical with the project ETS last programmed it from),
or the ETS `LoadedImage` of such a device in a project the user exports. Open points it would
settle: the configuration bits of BCU 1 (taken to be those of mask 7.5, as the static data
suggests), the communication flag cleared for objects without an association (as ETS does on mask
7.5), and whether ETS moves the association table with dynamic table management.

## The first real download (the user runs it)

Start with the actuator 1.3.5: no stamps, no skipped step, the hardware type checked first.

1. Make sure ETS can restore the devices: the ETS project of the test system is at hand and was
   last downloaded from that project.
2. Stop nothing of the home installation; connect only to 192.168.178.81.
3. Start an app with writing switched on, e.g.
   `./mvnw exec:java -Dtl.port=8081 -Dtl_ehome_programming_write_enabled=true` (with its own data dir:
   environment `h2_name` and `tl_storage_path`).
4. Import `ehome/samples/KNX-Testsystem.knxproj`; set the project's gateway to `192.168.178.81:3671`.
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

## Reading an installation (scan, read-back; haumacher/ehome #9)

Projects → a project → "Installation einlesen" (`projects/scan.view.xml`, package
`de.haumacher.ehome.scan`). Everything here only reads, except "Programmier-LED an/aus".

- **Scan** (`BusScanner`, `CalimeroScanBus`): over the project's gateway, the devices of a line
  that answer a connection (Calimero `scanNetworkDevices`), optionally the couplers of all lines,
  the devices in programming mode (broadcast address read) and the gateway itself through KNXnet/IP
  local device management (it does not answer point-to-point reads through its own tunnel). Per
  device: descriptor, manufacturer (object 0 property 12), serial number (11), order info (15),
  hardware type (78), program version (object 3 property 13; BCU 1: memory 0104-0107), load
  states, run state. A device that does not answer its descriptor is read once more (right after the
  scan the actuator of the test system refused the first connection).
- **Matching** (`CatalogMatch`): manufacturer by KNX ID, program by application number and version
  (all hash variants are candidates; a program for another mask or whose load procedure compares
  another hardware type is excluded), product by the order info (a variant whose order number the
  order info starts with), else every variant of a product carrying the program. A gateway without
  application object is matched by manufacturer and order info alone. One candidate: "Anlegen";
  several: a choice dialog; none: the device is kept as `ehome.project:UnidentifiedDevice` of the
  project and matched again by "Vorgemerkte Geräte zuordnen" (e.g. after a product data import).
- **Creation** (`InstallationBuilder`): the device under the line of its address (areas and lines
  created, named by their address), `serialNumber` in the ETS form (base64), `loaded` = individual
  address and program. An existing device with the address and program is updated; one with another
  program is a conflict, nothing changes.
- **Read-back** (`ReadBack`, "Konfiguration einlesen"): reads where the download image writes (every
  loaded block, the tables at the device's table pointers, property 7, their lengths from their
  counts, the parameter properties), then decodes: the parameters with memory by their types until
  the parameters in effect settle; the parameters without memory (ETS shows them to open pages and
  objects, the device never holds them) by a search for the values that explain the device: every
  associated object in effect with a placement of the size its type byte gives, every object without
  association with its configuration byte, every parameter byte equal (`Search`: placing objects
  along their way through the switches, then switches whose cases hold evidence); the connections
  from the tables (the first address of an object is the one it sends to, as ETS links them; group
  addresses the project lacks are created, named by their address); the flags where an object's
  configuration byte differs. The proof: the image of the device as read back equals the memory read
  (report "Identisch"), and the device is recorded as downloaded in that state.
- **LED** ("Programmier-LED an/aus", `LedJob`): writes the programming mode (object 0 property 54;
  BCU 1: memory 0060 with parity) through `CalimeroWriter`, so it is disabled unless the variable
  `tl_ehome_programming_write_enabled` is true, and asks for a confirmation naming device and
  gateway. **Not yet executed against a real device**: tested only against the mock; the first real
  toggle is the user's decision.

Result of 2026-10-11 on the test system (gateway 192.168.178.81:3671, line 1.3, into an empty
project on an own app instance with the catalog of `ehome/samples/KNX-Testsystem.knxproj`):

- 1.3.1 through local device management: EIBMARKT (M-006A), serial 00C5:0101BC95, order info
  N000402000, no application object, no readable hardware type; matched to "KNX / EIB IP Router
  (N000402)", program M-006A_A-0703-10-FD20-O00C5.
- 1.3.5: mask 0705, MDT (M-0083), serial 0083:65AEF06D, program version 00 83 00 0C 21, hardware type
  00 00 00 00 03 0D, load states loaded, running; matched to AKK-0416.03.
- 1.3.50: mask 0705, Gira (M-0008), serial 000A:2A4A2711, program version 00 08 10 D7 11, hardware
  type 00 0A 00 00 00 3D, order info not set (FF); matched to "Tastsensor 3 Komfort 3fach (5133 00)".
- Read-back of the actuator: 31 values differ from the defaults, 21 connections, 21 group addresses
  created, 626 bytes of the image equal the memory; "Mit dem Gerät vergleichen" afterwards: 4 of 4
  blocks identical.

Limits: values of parameters without memory whose cases leave no trace in the device (e.g. a scene
switched on without an address or a different value) keep their default; where two choices yield
the same bytes, the read-back keeps the first it finds (`TestBusScan` logs them: the actuator's
"Scene B" of channel A). Relative segments (System B) and parameters in properties of objects given
by type are not read (reported). KNX Secure devices are not supported.

## Not supported (refused with the reason)

- Masks whose load state machines are controlled in memory instead of properties (mask 7.1,
  `MV-0701`: `PropertyMappedLsms` 0, load controls written to 0104), and step kinds other than
  Connect, Disconnect, Unload, Load, AbsSegment (types 0 and 1), TaskSegment, TaskCtrl1,
  LoadCompleted, LoadImageProp (download stamps), LoadImageMem (nothing sent, see BCU 1),
  CompareProp, WriteProp, WriteMem / CompareMem (standard memory), Restart, Delay,
  SetControlVariable (verify, optimistic write), ProgressText.
- BCU 2 (masks 0020, 0021): its tables (`AssociationTable_Bcu2`, `GroupObjectTable_Bcu2`) and its
  procedure (TaskPtr, TaskCtrl2, `EnableSegmentWrite`) are refused with their names.
- Writing only some bits of a byte (a parameter in a segment without static data).
- The IP router (mask 9.1A): its procedure writes the coupler's slave and filter memories
  (`LcSlave`, `LcFilter`, `ClearLCFilterTable`), and its parameters are bit fields within bytes.
- The partial download (parameters and tables only), KNX Secure, RF, firmware.
