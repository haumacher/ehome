# Controllers and the E-Home connection

A HomeControl controller (the runtime of the sibling project `homecontrol`) runs what E-Home deploys.
E-Home reaches a controller in one of two ways:

- **The E-Home connection** (haumacher/ehome #54): the controller is enrolled for a project with a
  one-time code and from then on connects to E-Home by itself, over a WebSocket it opens. E-Home
  needs no address of the controller, so a controller behind NAT, in another network or at another
  site works. This document is about this way.
- **On the LAN**: a controller entered with its host and bearer token (project → tab "Controller" →
  "Controller im LAN" or "Controller suchen", then "Token setzen") is called over HTTP, as before
  (haumacher/ehome #39).

Either way the controller is a part of exactly one project (`Project#controllers`, haumacher/ehome
#151): it is created and found in the project's tab "Controller", read and changed where the project
is (it takes its access from the project), and a project deploys to, monitors and reads the Zigbee
network of its own controllers only (`ProjectControllers`; `Deployer.veto` and `ControllerTarget`
refuse another project's). An administrator sees all controllers with their project in Administration →
"E-Home-Controller" (`controllers.view.xml`). The data migration `Issue_151_Controllers_in_projects`
(startup action `ControllerOwnershipMigration`) moved the controllers on the LAN of before into the
projects: one that exactly one project deployed to into that project; one that several deployed to
into the first by name, and a copy with its token into each of the others; one that no project deployed
to stays without a project, which only an administrator sees and assigns to a project or deletes in that
list.

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
 "socket": "wss://ehome.example/ehome/api/controllers/<id>/socket", "public_key": "<64 hex>"}
```

`public_key` is the project's installation key (section "Signatures"), present when the project has
one; the first controller of a project creates it.

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

and the controller answers `{"type": "auth", "controller": "<id>", "mac": "<64 hex>", "key": "<fingerprint>"}` with
`HMAC-SHA256` keyed with the 32 bytes the secret's hex encodes, over the 16 bytes the nonce's hex
encodes, in lower-case hex (as homecontrol's `ehome_mac` computes it). `key`, only while the device
holds an installation key, is that key's fingerprint (section "Signatures"). E-Home compares it in constant
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
over the connection. An installation that does not reach local networks (`tl_ehome_local_network=false`,
haumacher/ehome #150, `doc/debian.md`) has no LAN path: a controller that is not connected refuses every
action with the reason (an enrolled one names its last contact), "Controller im LAN", "Controller suchen",
"Token setzen" and host, port and token of "Connect to a controller" are not offered, and the server
refuses them as well.

The state of a controller (tab "Controller" of the project, and the administrators' list): "Online
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

## Signatures

Every runtime image E-Home deploys or exports is signed (homecontrol issue #35, `spec/api.md`
"Signed runtime images"); a controller holding the installation key refuses any other image, so a
token alone cannot push one. The code is `connection.Signatures` (the construction and the
encodings) and `connection.InstallationKeys` (the keys of a project and the rotation).

- **One key pair per project.** An Ed25519 pair from the JDK (`KeyPairGenerator.getInstance("Ed25519")`),
  generated at the enrollment of the project's first controller, or by "Create key" in the tab
  "Controller" for a project whose controllers were enrolled before it had one (until then it
  deploys unsigned, as before). Further controllers get the same key.
- **Where the private key lives.** In the knowledge base with the project, as the 32-byte seed in
  64 hex digits: `Project#installationKey` (and `#newInstallationKey` during a rotation), hidden, with
  a read grant without roles (`ehome.security.config.xml`): nobody but an administrator reads it
  through the model, like the controller's secret (section "Security", which also says why it is not
  encrypted at rest). It never leaves E-Home: it is neither sent, nor shown, nor logged (the key's
  `toString` names only the fingerprint). The public keys (`#installationPublicKey`,
  `#newInstallationPublicKey`) are not secret.
- **Encodings.** The public key travels as the 32 bytes of RFC 8032 in 64 lower-case hex digits (the
  enrollment answer's `public_key`, the `key` message): the JDK's X.509 `SubjectPublicKeyInfo` of an
  Ed25519 key is a fixed 12-byte prefix followed by those 32 bytes, and its PKCS #8 encoding a fixed
  16-byte prefix followed by the seed, so `Signatures` cuts or adds the prefix. The **fingerprint** is
  the first 8 bytes of SHA-256 over the 32 public-key bytes, 16 hex digits in four groups:
  `ce7d 4a41 a476 9ee3`.
- **The construction.** Plain Ed25519 (`Signature.getInstance("Ed25519")`, RFC 8032 "pure") over
  `M = "homecontrol-image-v1" || SHA-256(image)`, the 20 ASCII bytes followed by the digest of the
  image's exact bytes; the signature is the 64 bytes `R || S` in 128 lower-case hex digits. Ed25519 is
  deterministic: `TestImageSignatures` reproduces the controller's fixtures (RFC 8032 test 1, the
  fingerprints, `example.json.sig`) byte for byte.
- **Where it travels.** `Deployer` signs the exported bytes and `ControllerClient.putImage(image,
  signature)` sends them: over the socket the request carries `"signature"` next to `"length"` and the
  image goes as binary frames (never the inline body, which the device refuses for a signed image),
  over HTTP on the LAN the header `X-Image-Signature`. The deployment records the signature and the
  key's fingerprint (column "Signed with"). "Download image" offers `<image>.sig` (128 hex digits and
  a line end) beside the image, which the controller's Settings page installs with it.
- **Refusals.** A device refuses an image without signature (403 `image.unsigned`) or one that does
  not verify under its key (403 `image.bad_signature`) and names the fingerprint it expects; the
  recorded message adds the key E-Home signed with, or that the project has no key.
- **The device's key.** The enrollment records the key given to the controller; each connection's
  `auth` (member `key`) and each `key_accepted` update `Controller#deviceKey`. The detail of the tab
  "Controller" says which key the device holds, that it holds none (it accepts unsigned images), and
  warns when it holds a key that is not the project's.
- **The rotation.** "Renew key" generates a new pair and keeps the old one. After `ready`, every
  controller that does not hold the key the project wants (the new key during a rotation, the
  installation key otherwise; also a device that holds no key or a foreign one) is offered it:
  `{"type": "key", "public_key": ..., "fingerprint": ...}`, and connected controllers at once. The
  device answers `key_pending` and keeps it for its owner, who compares the fingerprint and accepts
  on the device; then it sends `key_accepted` (or names the new key in the `auth` of a later
  connection). An `ehome.bad_key` error is shown in the controller's detail. Each image is signed with
  the key its controller holds: the new key for one that accepted it, the old one otherwise. The new
  key becomes the installation key, and the old one is forgotten, when no enrolled controller of the
  project holds the old key any more, so the rotation never leaves a device with a key E-Home no
  longer signs with. A controller enrolled during a rotation gets the new key at once. "Renew key"
  again replaces an unaccepted new key, and is refused while a controller holds it.
- **What the owner compares.** The fingerprint below the controller table (the project's key, and
  during a rotation the new one) with the one the controller's Settings page shows before accepting
  a key; the device's key in the controller's detail.

## Forgetting a controller

"Vergessen" (project tab, or "Löschen" in the administrators' list for a controller without a project) asks, then deletes the controller with its
secret, removes it as the deployment target of its project and from the deployments that name it
(they keep its name and address in their target), and closes its connection with
`ehome.forgotten`. When the project has exactly one other controller, that one becomes the target
(the same device enrolled again with a new code, after which the old entry is forgotten); with
several, the installer chooses it in the tab "Bereitstellung".

## Security

- The enrollment endpoint and the socket are outside TopLogic's login (`WEB-INF/web.xml`:
  `ControllerServlet` at `/api/controllers/*`, the socket `ControllerSocket` registered by the engine's
  `WebSocketEndpointListener`);
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

## TLS during development

No public host is needed to run the whole path over TLS on the desk. `bin/dev-tls.sh start` makes a
local CA and a server certificate for this machine (localhost, the host name, `<host name>.local`
and every IPv4 address; the keys in `~/.config/ehome/dev-tls/`, mode 600, outside the repository)
and runs nginx (the docker image `nginx:1.27-alpine`, host network) with the proxy settings listed
above, from port 8443 to the E-Home of `./mvnw -pl ehome exec:java` on 8080; it prints the
addresses `https://<address>:8443/ehome` and the CA's path. `bin/dev-tls.sh stop` ends it.

The controller trusts the CA when its firmware is built with
`-DHC_EXTRA_CA=$HOME/.config/ehome/dev-tls/ca.pem` (homecontrol `doc/ehome.md` "TLS during
development"; a build directory of its own, e.g. `build-esp32c6-devtls`) and flashed over the WLAN.
It is then enrolled with the address `https://<this machine>:8443/ehome` and a code of the project's
tab "Controller"; the answer names `wss://<this machine>:8443/ehome/api/controllers/<id>/socket`.
Verified on 2026-11-03 with the ESP32-C6: the enrollment, the socket, its reconnect after off/on,
the pings through the proxy and a signed deployment over `wss://`.

The Jetty of `exec:java` does not evaluate `X-Forwarded-For`: behind the development proxy every
request comes from 127.0.0.1 (the log says "Enrolled controller … from 127.0.0.1"), so the
enrollment's rate limit counts all clients as one. That is acceptable on the desk; the Debian
package's Tomcat needs the `RemoteIpValve` (above).

## The WebSocket implementation

The socket is an annotated Jakarta WebSocket endpoint (`ControllerSocket`, `@ServerEndpoint`),
registered at startup with the container's implementation by the engine's
`com.top_logic.util.WebSocketEndpointListener`, which reads the endpoint classes from the context
parameter `websocket.endpoints` of `WEB-INF/web.xml` (developer article `websocket-endpoints`). Tomcat
and Jetty with its WebSocket module provide the implementation; the war does not contain one.
TopLogic's development start (`tl-ide-jetty`) and the tests run Jetty's (ee11), which the engine
brings, without scanning the classes: there the listener's registration is the only one. Tomcat also
finds the annotated endpoint by its own scan; the listener's second registration of the same class is
ignored, as the WebSocket specification demands. A container without any implementation logs an error
at startup; the controllers cannot connect and the rest of E-Home works. The servlet API's own HTTP
upgrade is not used: Jetty 12.1.14 delivers no data after the upgrade of a request without a body,
which every WebSocket handshake is.
