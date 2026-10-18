# Controllers and the E-Home connection

A HomeControl controller (the runtime of the sibling project `homecontrol`) runs what E-Home deploys.
E-Home reaches a controller in one of two ways:

- **The E-Home connection** (haumacher/ehome #54): the controller is enrolled for a project with a
  one-time code and from then on connects to E-Home by itself, over a WebSocket it opens. E-Home
  needs no address of the controller, so a controller behind NAT, in another network or at another
  site works. This document is about this way.
- **On the LAN**: a controller entered with its host and bearer token (`controllers.view.xml`, "Set
  token") is called over HTTP, as before (haumacher/ehome #39).

The protocol is the controller's contract, `spec/api.md` of homecontrol, sections "E-Home connection
(issue #41)", "WebSocket /ws" and "Tokens". The code is in `de.haumacher.ehome.connection`.

## Enrollment

Project → tab "Controller" → "Controller hinzufügen" creates an enrollment code for the project and
shows it once in a dialog: eight characters as `ABCD-EFGH` from an alphabet without the look-alikes
`0 O 1 I L`, valid 15 minutes, for one controller. The project stores the code, its expiry and who
created it (`Project#enrollmentCode`, `#enrollmentExpires`, `#enrollmentCreatedBy`, hidden; only
installers read the code through the model). A new code replaces an open one. Below the table the
tab says whether a code is open and until when, without the code.

On the controller's Settings page the user enters the address of E-Home (e.g.
`https://ehome.example/ehome`, with the context path) and the code. The controller calls

```
POST /ehome/api/controllers/enroll
{"code": "ABCD-EFGH", "name": "homecontrol-a1b2c3", "version": "0.1.0", "build": "a9d0216"}
```

and gets

```
{"controller": "<32 hex>", "installation": "<project name>", "secret": "<64 hex>",
 "socket": "wss://ehome.example/ehome/api/controllers/<id>/socket"}
```

E-Home creates the controller under the project (`Project#controllers`; the first one also becomes
the project's deployment target `Project#controller`) with a stable id of 128 random bits, a secret
of 256 random bits, the installation's name, the firmware version and build, the time of the
enrollment and the user who created the code. The code is consumed. The secret is shown to nobody:
it is in the answer to the controller and in the hidden attribute `Controller#secret`.

The code is read in any spelling (lower case, without the dash, with spaces). Refusals, each with
`{"error": ..., "code": ...}`:

| Status | `code` | When |
|---|---|---|
| 400 | `ehome.bad_request` | No JSON object, or no code. |
| 404 | `ehome.code_unknown` | No project has the code (never created, used, or replaced). |
| 410 | `ehome.code_expired` | The code is past its 15 minutes; it stays and keeps answering 410 until a new one replaces it. |
| 409 | `ehome.enrolled` | The request names (`"controller": "<id>"`) a controller E-Home already knows; the code is not consumed. |
| 413 | `ehome.too_large` | A body above 4 KB. |
| 429 | `ehome.rate_limited` | More than 10 attempts per minute from one address, or 60 in total; with `Retry-After`. |

The same name enrolled twice makes two controllers: the name is a label, the id is the identity.

## The connection

The controller opens the socket URL it got. E-Home sends

```
{"type": "challenge", "nonce": "<32 hex, 128 random bits, new for every connection>"}
```

and the controller answers `{"type": "auth", "controller": "<id>", "mac": "<64 hex>"}` with
`HMAC-SHA256` keyed with the 32 bytes the secret's hex encodes, over the 16 bytes the nonce's hex
encodes, in lower-case hex (as homecontrol's `ehome_mac` computes it). E-Home compares it in constant
time with the HMAC of the stored secret and answers `{"type": "ready", "installation": ...}` followed
by `{"type": "subscribe"}`, or `{"type": "refused", "code": ..., "error": ...}` and closes with 1008:

| `code` | When |
|---|---|
| `ehome.unknown_controller` | No controller has the id of the path (never enrolled, or forgotten), or `auth` names another id. |
| `ehome.auth_failed` | The HMAC does not fit. A replayed `auth` carries the HMAC of an old nonce and fails. |
| `ehome.auth_required` | Another message came before `auth`. |
| `ehome.auth_timeout` | No `auth` within 10 s. |
| `ehome.forgotten` | The controller was forgotten while connected. |

After `ready` the socket is the controller's live feed: `status` (the firmware it reports is stored
when it changes), `datapoints`, `zigbee` and `monitor` messages. They go to

- the controller status (the last `status` and the datapoint values, `ControllerConnection#status()`,
  `#datapoints()`),
- the group monitor: a project whose controller is connected monitors through the socket
  (`monitor.SocketBusLink`, the same events as over the controller's own `/ws`),
- the listeners of `ControllerConnections`: `zigbee.ZigbeeLive` re-reads the controller's Zigbee
  export after the events that change the device list and imports it into the projects deploying
  to the controller, debounced, and keeps the live state the Zigbee tab shows (`doc/zigbee.md`).

E-Home's requests to the controller's API go over the socket as
`{"type": "request", "id": n, "method": ..., "path": ..., "body": ...}` and come back as
`{"type": "response", "id": n, "status": ..., "body": ...}`, through the Java API
`ControllerConnection.request(method, path, body, timeout)`. One request is on the wire at a time;
later callers wait for their turn (fair, up to their timeout). A body above 4 KB, and the runtime
image of any size (it is stored and compared byte for byte), goes as `"length": n` followed by binary
frames of 4 KB; a response may come the same way, as the controller streams `GET /api/image`.

`ControllerClient`, which every action uses (deploy, unload, status, the image comparison, the
Zigbee export, permit and read), takes the socket when the controller is connected and HTTP with
host and token otherwise. An enrolled controller that is not connected and has no host and token
refuses every action visibly, naming its last contact ("not connected to E-Home (last contact
...)"), or that it never connected; entering host and token keeps the LAN path open. No firmware goes
over the connection.

The state of a controller (tab "Controller" of the project, and the global controller list): "Online
since ...", "Offline, last contact ...", "Not connected yet", or "In the LAN" for a controller that
was not enrolled. The online state is held in memory; the last contact (at the connection and at its
end), the firmware version and build are stored with the controller. A table shows the state as of
its last refresh.

### Threads

The container's WebSocket implementation calls the messages of one socket one at a time; the
authentication reads the knowledge base on that thread (in a system interaction, after a refetch).
Every write of the connection to the knowledge base (the last contact, the firmware) goes to one
thread of `ControllerConnections`, so a socket thread never waits for a commit; forgetting a
controller deletes it on that thread too, after the writes queued before. A request blocks its caller
(the thread of a view command, or the Zigbee live import's own thread) until the response arrives.
A watchdog thread refuses sockets that do
not authenticate in time; the container closes a socket idle for 90 s (the controller pings every
30 s).

### What a reconnecting controller sees

- After a network failure or a restart of either side, the controller connects again and
  authenticates with a new nonce. E-Home does not keep a session: every connection starts with
  `challenge`, and `ready` is followed by `subscribe`, so the controller sends its full state again.
- A controller that connects while E-Home still holds an older socket of it (the old one died
  without a close) replaces it: the old socket is closed with the reason `ehome.replaced`, and a
  request waiting on it fails.
- A running group monitor re-subscribes with `since` set to the last sequence number it received;
  if the controller's ring buffer no longer reaches back, the monitor shows the reset, otherwise
  it continues without a gap. While the controller is away, the monitor shows the interruption.
- A request in flight when the socket closes fails with "the connection to the controller was
  closed"; it is not repeated.
- A forgotten controller is refused (`ehome.unknown_controller`) and must be enrolled again with a
  new code; it then is a new controller.

## Forgetting a controller

"Vergessen" (project tab) or "Löschen" (global list) asks, then deletes the controller with its
secret, removes it as the deployment target of its project and from the deployments that name it
(they keep its name and address in their target), and closes its connection with
`ehome.forgotten`.

## Security

- The enrollment endpoint and the socket are outside TopLogic's login (`WEB-INF/web.xml`:
  `ControllerServlet` at `/api/controllers/*`, the socket registered by `ControllerSocketRegistration`);
  the code and the HMAC are their authentication. The enrollment is rate limited per remote address
  and in total.
- The secret is 256 random bits generated by E-Home (`SecureRandom`). It is stored as is in the
  knowledge base, in the hidden attribute `Controller#secret` with a read grant without roles
  (`ehome.security.config.xml`: nobody but an administrator reads it through the model, like the
  controller token): TopLogic's `EncryptionService` needs a key set up per installation, so no
  at-rest encryption is usable out of the box (`doc/tl-todo.md`). Whoever reads the database can
  impersonate the controllers; protect the database and its backups accordingly.
- The nonce is random per connection; the HMAC is compared in constant time.
- **TLS is operations.** E-Home speaks plain HTTP and WebSocket; a reverse proxy in front of it
  terminates TLS, so the controller connects with `https`/`wss`. The proxy must
  - pass WebSocket upgrades on for `/<context>/api/controllers/` (nginx: `proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";` and a
    `proxy_read_timeout` above 30 s, the controller's ping interval),
  - send `X-Forwarded-Proto` and `X-Forwarded-Host`: the socket URL in the enrollment's answer is
    built from them (`wss://` behind a TLS proxy),
  - let the container take the client's address from `X-Forwarded-For` (Tomcat `RemoteIpValve`,
    Jetty `ForwardedRequestCustomizer`); otherwise the rate limit counts the proxy as one address.

## The WebSocket implementation

The socket is a Jakarta WebSocket endpoint (`ControllerSocket`), registered with the container's
implementation at startup. Tomcat and Jetty with its WebSocket module provide one; the war does not
contain an implementation. TopLogic's development start (Jetty without annotation scanning) has none
running: Jetty's implementation is on the test classpath (`ehome/pom.xml`, also the classpath of
`exec:java`), and `ControllerSocketRegistration` runs its initializer when the container did not. A
container without any implementation logs an error at startup; the controllers cannot connect and
the rest of E-Home works. The servlet API's own HTTP upgrade is not used: Jetty 12.1.14 delivers no
data after the upgrade of a request without a body, which every WebSocket handshake is
(`doc/tl-todo.md`).
