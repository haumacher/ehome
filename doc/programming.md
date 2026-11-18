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
- Comparing never writes: it reads the device descriptor, the program version, the load states
  (through properties on mask 7.5, the bytes B6EA-B6ED on mask 7.1) and the memory of every block of
  the image.

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
  "Identisch" or the differing addresses. The progress moves with every read (at most 12 bytes: the
  block and the address read, as "<block>: 4A30 von 4000-4FFF"); a cancel ends the
  comparison before the next read and opens the report of what was compared up to then
  (haumacher/ehome #190).
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
- One job of the tab at a time (haumacher/ehome #161): its progress is shown with the device it was
  started for only (the channel `jobDevice`), and the commands of every device say "Der Auftrag für …
  läuft noch" while it runs. The vetoes of the commands read runtime state no model event reflects
  (an open bus session, a controller connected, its status); the source channel `busState`
  (`programming.download.BusStateSource`) changes whenever a bus session opens or ends or a controller
  connects, disconnects or changes what it offers, and the commands name it among their inputs, so
  that they are decided anew when a job of another device, view or session gives the controller free.
  The same channel drives the commands of the scan dialog.
- The texts of the plan, the reports and the scan are resource keys in both languages (whole
  sentences; hex values, object and property numbers and addresses are values in them), numbers in
  the reader's format ("49,7 s"), the problems of the download image (`DownloadImage#getProblems`)
  and the reasons a parameter value cannot be encoded (`ParameterEncoding`, `ValueException`)
  included. What the KNX library reports (the message of its exception) stays as it reports it, a
  value inside the translated sentence.

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

## Device info (safe; haumacher/ehome #175)

"Geräte-Info" on a device's tab "Programmierung" (`DeviceInfoJob`, `DeviceInfo`) asks the device at
its individual address over the project's path (gateway or controller) what the scan asks each device
(`BusScanner.read`: descriptor, manufacturer, serial number, order info, hardware type, program
version or BCU 1 program identity, load states, run state) and its programming mode (property 54 of
the device object, memory 0060 bit 0 for BCU 1 and BCU 2); nothing is written, so it needs neither the write
switch nor `Write` on the project (`Read` suffices). The dialog `projects/device-info.view.xml` shows
the identity beside the project's values (the program's mask, the product's manufacturer and order
number, the hardware type the load procedure compares, the program's application ID), a differing
row as a warning, a value the project does not have (a serial number) as a note; "Seriennummer
übernehmen" writes the serial number read into the project's device (`Write`, only where mask and
program are the project's). No device at the address is the result "Kein Gerät antwortet unter …".
The reading is not stored. The assistant and MCP have it as `ehome-devices.deviceInfo`.

## What a download does (mask 7.5, BIM M112)

The program's own load procedure (`ProductProcedure`), as the plan lists it:

1. Connect; read the device descriptor and stop unless it is the program's mask (0705).
2. Compare the hardware type (object 0, property 78) with the program's; stop on a difference
   ("The device has the hardware type …, the program expects …"). The actuator's load procedure
   compares it itself (`CompareProp`); for the Tastsensor, whose procedure does not, E-Home compares
   the value of its image, because the mask names the property `HardwareConfig_Identical` (every
   such resource of the mask with a value in the image; haumacher/ehome #187). Nothing is written
   before these checks.
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
  object 5 for a property-controlled load state machine. The plan leaves it out without a notice
  (haumacher/ehome #187). The object table address is part of the application header, so the device
  does not need it; the first download of the Tastsensor shows whether that holds.
- The zero fill: the segment of the Tastsensor's association table (dynamic table management) is
  written up to its end with zeros, so that its stamp's CRC describes the memory (the device held
  zeros there after ETS).

## What a download does (mask 7.1, BIM M112, load state machines in memory; haumacher/ehome #22)

A program of mask 7.1 (`MV-0701`, `PropertyMappedLsms` 0) is loaded by the same steps as one of mask
7.5 (MDT's 7.1 programs have the load procedure of the actuator above, step by step); only the way of
the load controls differs (`LoadStateMachines`, `LoadControls.memoryRecord`, `Operation.MemoryLoadControl`):

- The load control of load state machine *n* (the object type of the loaded part: 1 address table,
  2 association table, 3 application, 4 PEI program) is written with `A_Memory_Write` into the
  mask's resource `<part>LoadControl` (flavour `LoadControl_M112`; 0104 for all four on mask 7.1) as
  a record of 11 bytes: the load state machine in the high, the event in the low nibble of the first
  byte (unload `14`, `24`, `34`; start loading `11`; load completed `12`); an additional load control
  continues with the segment type, a segment ID `00` and the data of the 10-byte control after its
  segment type (`13 00 00 40 00 02 01 FF 03 80 00`: segment 4000, 513 bytes, access FF, EEPROM,
  flags 80; `13 02 00 40 00 01 00 83 00 14 11`: task segment 4000, PEI type 1, application 0083 0014
  11). The record is not read back (the mask gives no read access to it).
- After each record the load state is read from the mask's `<part>LoadStatus` (B6EA, B6EB, B6EC,
  B6ED), one byte; a transitional state is read again until it settles, another state than the
  expected one up to three times more (250 ms apart), then the download stops with the state read.
- The task segment of load state machine 5 some 7.1 programs name (MDT's `M-0083_A-0014-11-EA36`)
  is left out: the mask names no load control for it (as ETS does).
- After the application: the program version (object 3, property 13, the mask's `ApplicationId`).

Source of the format: KNX 3/5/2 Management Procedures 3.31.2 (the memory-mapped load state machine,
"only with mask 070nh") and the conformance test of the load state machines (TSSG 2.1: the unloads
14h-44h into 0104h, the states at B6EAh-B6EDh), as cited by the open-source download tools bussard
(`dev/system7-spec.md`, section 5) and xknx-editor (`load_state.py`), whose ETS recordings of mask
7.1 devices show the same 11-byte records. The mask's resource is 12 bytes long; the record fills 11
of them. Verified against the virtual device (`TestVirtualDownload#testMask71`, which decodes the
records by the specification's constants, independently of E-Home's encoding) and a mock
(`TestMask71Download`): the plan of the fixture `TestMask71.knxproj` (the test system's actuator
program turned into mask 7.1) equals the plan of the 7.5 program but for the way of the load
controls, 626 bytes of the image written. **Not verified against a device.**

### The first 7.1 download (the owner runs it)

On the house installation (its only 7.1 devices), every step started by the owner from the UI; no
agent starts a comparison or download there.

1. Read only: "Mit dem Gerät vergleichen" shows "Ladezustandsmaschine 1-4" and the memory; load
   state machines 1-3 should be "Geladen", every block identical to E-Home's image (the device must
   hold exactly the parameters it has now, as imported).
2. The unchanged ETS project at hand: if the download stops halfway (load state machines unloaded,
   the device silent), an ETS download of that project restores it.
3. One device, the one whose failure does least harm; "Herunterladen". What to watch: the first
   step that writes is the unload of load state machine 1 (`14 …` into 0104); a refusal there with
   "meldet … den Zustand …" means the device does not take the record as specified — stop, the device
   is unchanged except for that state (reload it with ETS if it stays unloaded).
4. Afterwards "Mit dem Gerät vergleichen": load state machines 1-3 loaded, memory identical, the
   program version equal; the device switches as before.

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

## What a download does (BCU 2, masks 0020, 0021, 0025; haumacher/ehome #191)

BCU 2 (`ManagementModel` `Bcu2`) controls its load state machines through properties, as mask 7.5
(feature `PropertyMappedLsms` 1, flavour `LoadControl_Bcu2`: load control and state in property 5
of the objects 1/2/3, run control and state in property 6 of object 3, table pointers in property 7
of the objects 1/2/3, two bytes). The masks name the interface objects 0 to 3 only (no PEI program
object): a task segment of load state machine 5 (some programs name one) is left out, as on mask 7.5.

**Tables** (`GroupTables`, `DownloadImageBuilder`):

- Address table `AddressTable_Bcu1` at 0116 (count, individual address, group addresses), as on BCU 1
  and mask 7.5.
- Association table `AssociationTable_Bcu2`: the bytes of BCU 1/mask 7.5 (count, then address
  index and object number per entry), but with one reserved place per communication object first:
  entry *n* holds the address object *n* sends to (its send connection, else its first receive
  connection), `FE n` for an object without association; the other associations follow, and while
  there are any, a free place takes the first of them whose address no object sends to from its own
  place (`GroupTables.associationSlots`). The read-back decodes it the other way round: the entry in
  an object's own place is its send address, `FE` entries are no associations
  (`GroupTables.associations`). Source: the reserved places are what the templates of the
  association table BCU 2 programs ship show (`FE 00`, `FE 01`, ... one entry per object); the order
  of the other entries and the use of free places are the rules ETS follows as the open-source
  download tool xknx-editor recorded them against ETS (`association_slots`).
- Object table `GroupObjectTable_Bcu2`: the bytes of BCU 1 (count, RAM flags pointer of one byte, per
  object value pointer, configuration, type); the value pointers are offsets into the segments
  task control 2 names. The configuration and type bytes are computed as for every mask.
- Pointers: the masks 2.0 and 2.1 keep the association and object table pointers also in memory
  (0111, 0112, `Ptr_StandardMemory100`), set as on BCU 1 when the tables lie in one segment with the
  header; mask 2.5 has them in properties only, set by the task segments of the load.

**Load controls** (`LoadControls`): besides those of mask 7.5, the additional load controls
*task pointer* (segment type 3: the addresses of the initialisation, save and serial routines, two
bytes each; `LdCtrlTaskPtr` `InitPtr`, `SavePtr`, `SerialPtr`) and *task control 2* (segment type 5:
callback, object table, segment 0, segment 1, two bytes each; `LdCtrlTaskCtrl2` `Callback`,
`Address`, `Seg0`, `Seg1`), from the KNX resources specification (load state machine, additional
load controls; the numbering of the types 0, 2 and 4 E-Home writes since mask 7.5); xknx-editor
writes the same bytes.

**Procedure:** the program's own (`ProductProcedure`), or for the masks 2.0 and 2.1 the mask's
(`DefaultProcedure`, `Load`/`all`, remote). The mask's procedure unloads the load state machines 1-3,
loads 3 with its fixed segments, task segment, task pointer, task control 1 and 2 while
`EnableSegmentWrite` is off, then writes the memory as the BCU 1 procedure does (RunError 00, the
address table emptied, the image's ranges 0100, 0103-010C, 010E-0115, 0119-01FE, 0200-046F, the
count, RunError FF, `CompareMem` of it, restart; 01FF listed as not loaded).

- `SetControlVariable EnableSegmentWrite`: while it is off, a segment allocated then is not written
  by the load (its memory is the procedure's own memory writes'); a plan whose segments are all
  allocated so is memory-based as BCU 1 (static data no step writes is listed as not loaded).
- Which allocated segments the load writes: on a mask with download stamps (feature
  `DownloadStamp`, mask 7.5) those with the flag 80, as before; on every other mask (BCU 2, mask 7.1)
  the segments of EEPROM (memory type 3) - on BCU 2 the flag 80 switches the checksum control of the
  segment on, it does not decide what is loaded.
- `AllocExtraByte` (BCU 2): a segment with checksum control is one byte longer than its content, for
  the checksum. It matters where E-Home computes an allocation: with dynamic table management the
  address table's segment (load state machine 1) is allocated one byte longer than the table when
  the procedure allocates it with the flag 80, and the association table follows after that byte
  (`DownloadImage.tableChecksum`); ETS does the same (as xknx-editor recorded). The read-back and the
  read-out place the association table the same way when the device names no table pointer.
- `AuthorizeLevels` (BCU 2: 4 levels, 3 the free one): the plan authorizes with the key of the
  delivery state (FF FF FF FF, `A_Authorize_Request`) right after the mask check, as ETS does; the
  download goes on with the level the device grants (a write it does not permit is refused by the
  device with its answer). Not on the masks 7.x, whose downloads work without.
- `VerifyMode`: the device can answer memory writes itself (device control, property 14 bit 2);
  E-Home verifies by reading back, which works with and without, and leaves it off.
- `FirstAppObjectIdx`, `PollingGroupSupport`: no effect on a download by object index (the polling
  group settings, object 0 property 18, are written only by a step or parameter naming them).
- Programming mode: memory 0060 bit 0 (the masks' `ProgrammingMode`), as on BCU 1.
- Read-out, comparison and read-back read the table pointers (property 7) only of a table in a
  segment of its own; tables within the segment of the header (the mask's procedure) are at their
  places there.

Verified against the virtual device in its BCU 2 mode (`TestVirtualBcu2`: the download of the three
programs of the fixture `TestBcu2.knxproj` over KNXnet/IP, its trace equal to the plan, memory, load
states, table references and the task pointer and task control 2 as planned; the read-out compared
identical offline; the take-over into the emptied device giving the downloaded connections and
values) and a mock (`TestBcu2Download`, exact bytes). **Not verified against a device.**

### Checking a BCU 2 device (read only; the owner runs it)

On the device's tab "Programmierung", the device on the bus through the project's path (gateway or
controller); nothing of these steps writes (`CalimeroReader`; the write switch stays off):

1. "Geräte-Info": mask version 0020/0021/0025, program version (object 3 property 13), load states
   1-3, run state, programming mode (memory 0060).
2. "Auslesen" (read-out, #193): reads the device's memory in every block of E-Home's image, the
   table pointers (property 7 of the objects 1 and 2, for tables in segments of their own), the
   program version and the load states, and keeps them as a snapshot of the device.
3. Off the bus, in the list "Speicherabbilder": "Vergleichen" on that snapshot, as often as wanted:
   every block identical when the project holds the device's configuration; a difference shows the
   device's and the plan's bytes. The association table (a place per object, `FE n` for an object
   without association) and the object table (configuration and type bytes) are the BCU 2 specifics
   to look at.
4. Optional, in a copy of the project: "Übernehmen" of the snapshot shows whether the read-back
   reproduces the project's connections and values ("Identisch").

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

A project → tab "Geräte" (structure by line) → "Bus durchsuchen" in the toolbar of the tree
(`projects/scan.view.xml`, package `de.haumacher.ehome.scan`, haumacher/ehome #180); a line selected
in the tree fills the field "Linie" (`scanLine`), an area or the project leaves it empty, and the
choice "Suchen nach" stays on "Geräte im Programmiermodus". Everything here only reads, except
"Programmier-LED an/aus".

- **Scan** (`BusScanner`, `CalimeroScanBus`): over the project's path to the bus, exactly one
  search chosen under "Suchen nach" (`ScanTarget`, haumacher/ehome #182; a radio group, the default
  the devices in programming mode): the devices in programming mode (broadcast address read), the
  devices of a line that answer a connection (Calimero `scanNetworkDevices`; the line one field
  `Bereich.Linie`, e.g. `1.1`, shown only for "Linie" and refused at the field otherwise,
  `LineInputControlProvider`), the couplers of all lines, or the gateway itself through KNXnet/IP
  local device management (it does not answer point-to-point reads through its own tunnel, so a
  line scan lists it as not readable). The devices found are read then. Per
  device: descriptor, manufacturer (object 0 property 12), serial number (11), order info (15),
  hardware type (78), program version (object 3 property 13; BCU 1: memory 0104-0107), load
  states, run state. A device that does not answer its descriptor is read once more (right after the
  scan the actuator of the test system refused the first connection). "Nur neue Geräte" leaves the
  addresses at which the project has a device unread (`BusScanner.Request#skip`): found there, they
  are only listed in a note ("Geräte des Projekts, nicht gelesen"), nothing of them is read; the
  gateway itself is then read for its individual address only.
  The job status lists the steps of the scan (the search chosen, reading the devices found; the
  gateway is one step) with the bar of the current one; the line and coupler scans of Calimero tell no
  progress, so their bar follows the time they take (256 connects of 115 ms and 7.1 s for the last
  answers) and turns indeterminate once that time is over (through a controller they take longer),
  instead of standing at the end (haumacher/ehome #161).
- **Matching** (`CatalogMatch`): manufacturer by KNX ID, program by application number and version
  (all hash variants are candidates; a program for another mask or whose load procedure compares
  another hardware type is excluded), product by the order info (a variant whose order number the
  order info starts with), else every variant of a product carrying the program. A gateway without
  application object is matched by manufacturer and order info alone. Of several candidates, the
  one the project's device at the address runs (and of its variant) is the match: the catalog may
  hold several editions of a program, imported from several exports of an installation (the ETS 4 and
  ETS 6 exports of the test system, both P-0563, haumacher/ehome #161). The summary counts the devices
  identified uniquely, those to choose for, and the unidentified. Several candidates: "Produkt
  wählen" (a choice dialog) makes the chosen one the match of the row; none: the row is taken over as
  a stub (below). The scan itself changes nothing in the project. The table shows address,
  manufacturer, order number (the order info as read where it is text, else the hardware type in hex),
  serial number, program version, catalog and the state in the project; how a device was read, its mask
  version and its load states are the tooltip of its address, and a device not read completely
  carries a marker with the reasons as its tooltip.
- **Take-over** (`TakeOverJob`, "Übernehmen" on the selected rows, "Alle übernehmen" on every row):
  per row in one go the creation (below) and the read-back (below), so that no device stays with the
  defaults of its parameters. A row the catalog does not identify becomes a stub in its line (below),
  its configuration not read; a row with several candidates not chosen, or whose address holds a
  device of another program (or, unidentified, any device of the catalog), is left alone; a device
  whose configuration cannot be read (the gateway itself, the path to the bus refused, a failed read)
  is created all the same. Each row says in "Im Projekt" what happened (marked where it was refused or
  not read), and a notice counts the devices taken over, read and refused. A cancel leaves the devices
  taken over before; the job ends as cancelled with their rows.
- **Creation** (`InstallationBuilder`): the device under the line of its address (areas and lines
  created, named by their address), `serialNumber` in the ETS form (base64), `loaded` = individual
  address and program. An existing device with the address and program is updated; one with another
  program is a conflict, nothing changes; a stub at the address is replaced by the device, which keeps
  its name, serial number, location and ID.
- **Stub** (`DeviceStubs`, `ehome.physical:DeviceStub`, haumacher/ehome #183): a device the catalog
  does not identify stands in its line like a device, without product and program, with the problem
  icon of its type; it holds what the device told about itself (manufacturer, program version, mask
  version, hardware type, order info, serial number) and shares name, address, serial number and
  location with a device (`ehome.physical:BusDevice`). Selected, it shows its data and no tabs (no
  parameters, objects, programming or virtual device). "Zuordnen" (Write on the project) looks it up in
  the catalog again: found, it is replaced by a device keeping name, address, serial number, location
  and ID (its parameters have their defaults until "Übernehmen" reads them); not found, it refuses with
  "Keine Produktdaten für GIRA, Bestellnummer 5133 00 – Produktdaten des Herstellers importieren."
  (the hardware type where the device reports no order number as text); several products, it points
  to "Übernehmen" with "Produkt wählen". The export as `.knxproj` leaves stubs out and names them
  (`doc/knx/export.md`); what reads a device's program, objects or configuration (the runtime image,
  the monitor, the programming, the rules, the proposals, the assistant's wiring) takes devices only.
- **Read-back** (`ReadBack`, part of "Übernehmen"): reads where the download image writes (every
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
  BCU 1 and BCU 2: memory 0060 with parity) through `CalimeroWriter`, so it is disabled unless the variable
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

- Masks naming no load control E-Home can write for a load state machine of their procedure (neither
  `PropertyMappedLsms` 1 nor the resources `<part>LoadControl`/`<part>LoadStatus` of the flavour
  `LoadControl_M112` in memory), and step kinds other than
  Connect, Disconnect, Unload, Load, AbsSegment (types 0 and 1), TaskSegment, TaskPtr, TaskCtrl1,
  TaskCtrl2, LoadCompleted, LoadImageProp (download stamps), LoadImageMem (nothing sent, see BCU 1),
  CompareProp, WriteProp, WriteMem / CompareMem (standard memory), Restart, Delay,
  SetControlVariable (verify, optimistic write, segment write), ProgressText.
- A step naming its load state machine or interface object by object type instead of index
  (`ObjType`): resolving it needs the device's list of interface objects from
  `FirstAppObjectIdx` on, read at download time (every mask; no BCU 2 procedure of the master data
  does it).
- Writing only some bits of a byte (a parameter in a segment without static data).
- The IP router (mask 9.1A): its procedure writes the coupler's slave and filter memories
  (`LcSlave`, `LcFilter`, `ClearLCFilterTable`), and its parameters are bit fields within bytes.
- The partial download (parameters and tables only), KNX Secure, RF, firmware.
