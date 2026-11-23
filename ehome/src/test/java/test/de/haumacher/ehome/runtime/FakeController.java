/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;
import de.haumacher.ehome.runtime.binary.ImageCompiler;
import de.haumacher.ehome.runtime.binary.ImageDecoder;
import de.haumacher.ehome.runtime.binary.ImageDecoder.DecodeException;
import de.haumacher.ehome.runtime.binary.Layout;

/**
 * An in-process stand-in for a HomeControl controller speaking the deployment protocol (homecontrol
 * <code>spec/api.md</code>, "Runtime image" and "Status") on <code>127.0.0.1</code>.
 *
 * <p>
 * It authenticates by a bearer token (401 <code>auth.required</code>, <code>auth.token_invalid</code>)
 * and takes the binary runtime image (homecontrol <code>spec/runtime-image.md</code> section 9,
 * <code>spec/api.md</code> "Runtime image", haumacher/ehome #176) into its image slot of the given size:
 * refused from the header with the running image untouched (413 <code>image.too_large</code> beyond the
 * slot, 400 <code>image.format</code> for no binary image, 422 <code>image.version</code> for another
 * major format, 403 <code>image.unsigned</code> while it holds a key), or after the slot was erased, so
 * that it runs no image afterwards (422 <code>image.damaged</code> for a wrong CRC, 403
 * <code>image.bad_signature</code>, 422 <code>image.invalid</code> for an image that does not read
 * back, {@link ImageDecoder}); else it loads the image and answers 200. A test can script the next
 * answer to <code>PUT /api/image</code> ({@link #refuseNext}), e.g. 507 <code>image.no_memory</code>
 * or 500 <code>image.apply_failed</code>, which no image provokes; a refusal after the erase leaves no
 * image, as on the device. The status (<code>GET /api/status</code>) reports the format it reads
 * ({@link #setFormat(String)}, <code>null</code>: a firmware reading JSON images), the slot and the
 * loaded image, or the refusal of the last one. <code>GET /api/image</code> answers the loaded image
 * byte for byte (404 <code>image.none</code> without one), <code>DELETE /api/image</code> unloads it
 * (200 <code>{"ok": true, "loaded": false}</code>; a test can script the storage error of the next
 * one, {@link #storageErrorOnNextDelete()}: 500 <code>image.storage_error</code>, the image unloaded
 * anyway). For a manual check, the next refusal is scripted with <code>PUT /fake/refuse-next</code>.
 * Given an installation key ({@link #setKey(String)}, homecontrol issue #35), it accepts only images
 * signed with it.
 * </p>
 *
 * <p>
 * The monitor stream (haumacher/ehome #41) is a WebSocket on a port of its own
 * ({@link FakeMonitorStream}), named by <code>GET /api/auth/session</code> (<code>ws_port</code>);
 * for a manual check an event is scripted with <code>POST /fake/monitor</code>.
 * </p>
 *
 * <p>
 * The Zigbee coordinator (haumacher/ehome #40, <code>spec/api.md</code> "Zigbee"): without a device
 * list ({@link #setZigbeeExport(String, int)}) every route answers 501 <code>system.unavailable</code> as
 * the POSIX build does; with one, <code>GET /api/zigbee/export</code> serves it in pages and
 * <code>POST /api/zigbee/permit</code> records the time the network was opened for
 * ({@link #getPermitSeconds()}).
 * </p>
 *
 * <p>
 * The blocks (haumacher/ehome #78, <code>spec/api.md</code> "Blocks"): <code>GET /api/blocks</code>
 * serves the diagnostics a test gives it ({@link #setBlocks(List, int)}) in pages, with
 * <code>count</code>, <code>from</code> and <code>next</code>.
 * </p>
 *
 * <p>
 * Started from the command line (<code>java -cp target/classes:target/test-classes
 * test.de.haumacher.ehome.runtime.FakeController &lt;port&gt; &lt;token&gt; [&lt;zigbee export&gt;]</code>), it
 * serves until it is stopped, for trying the deployment (and the Zigbee pairing) in the running
 * application.
 * </p>
 */
public class FakeController {

	static {
		// Without it, the JDK's server sends the headers of an answer and its body in two TCP segments,
		// and the client's delayed acknowledgement holds the second one back for 40 ms: every request
		// of a test took 40 ms (haumacher/ehome #208). Read when the first server is created.
		System.setProperty("sun.net.httpserver.nodelay", "true");
	}

	private final HttpServer _server;

	private final String _token;

	private final int _limit;

	private Map<String, Object> _loaded;

	private byte[] _stored;

	private String _refused;

	private String _refusedReason;

	private int _nextStatus;

	private String _format = Layout.FORMAT;

	private Map<String, Object> _nextBody;

	private int _puts;

	private int _deletes;

	private boolean _storageError;

	private final FakeMonitorStream _monitor;

	private Map<String, Object> _zigbee;

	private int _zigbeePage;

	private int _zigbeeRefusal;

	private Integer _permitSeconds;

	private List<Map<String, Object>> _blocks = new ArrayList<>();

	private int _blocksPage = 1;

	private long _nowMs = 90211;

	private int _blockRequests;

	private String _key;

	private String _lastSignature;

	/**
	 * Creates and starts a {@link FakeController} on a free port.
	 *
	 * @param token
	 *        The only token it accepts.
	 * @param limit
	 *        The largest body of <code>PUT /api/image</code> it reads.
	 */
	public FakeController(String token, int limit) throws IOException {
		this(0, token, limit);
	}

	/**
	 * Creates and starts a {@link FakeController} on the given port of <code>127.0.0.1</code>.
	 */
	public FakeController(int port, String token, int limit) throws IOException {
		_token = token;
		_limit = limit;
		_server = createServer(port);
		_server.createContext("/api/image", this::image);
		_server.createContext("/api/status", this::status);
		_server.createContext("/fake/refuse-next", this::script);
		_server.createContext("/api/auth/session", this::session);
		_server.createContext("/fake/monitor", this::scriptMonitor);
		_server.createContext("/api/zigbee/export", this::zigbeeExport);
		_server.createContext("/api/zigbee/permit", this::zigbeePermit);
		_server.createContext("/api/blocks", this::blocks);
		_monitor = new FakeMonitorStream(token);
		_server.start();
	}

	/**
	 * A server of the JDK on the given port of <code>127.0.0.1</code> (0 for a free one) that sends
	 * without delay, for a test answering requests of the app.
	 */
	public static HttpServer createServer(int port) throws IOException {
		return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
	}

	/**
	 * The port it listens on.
	 */
	public int getPort() {
		return _server.getAddress().getPort();
	}

	/**
	 * Stops it.
	 */
	public void stop() {
		_server.stop(0);
		_monitor.stop();
	}

	/**
	 * The WebSocket with the monitor stream.
	 */
	public FakeMonitorStream getMonitor() {
		return _monitor;
	}

	/**
	 * <code>GET /api/auth/session</code> (open): the WebSocket is on a port of its own, as in the
	 * POSIX build.
	 */
	private void session(HttpExchange exchange) throws IOException {
		drain(exchange);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("onboarded", Boolean.TRUE);
		body.put("authenticated", Boolean.FALSE);
		body.put("via", null);
		body.put("device", "homecontrol-fake");
		body.put("version", "0.0.0-fake");
		body.put("ws_port", Integer.valueOf(_monitor.getPort()));
		answer(exchange, 200, body);
	}

	/**
	 * <code>POST /fake/monitor</code> with an event (the members of a monitor event without
	 * <code>seq</code> and <code>ms</code>): {@link FakeMonitorStream#emit(Map)} for a manual check in
	 * the running application; <code>{"drop": true}</code> drops the connections.
	 */
	private void scriptMonitor(HttpExchange exchange) throws IOException {
		Map<String, Object> body;
		try (InputStream in = exchange.getRequestBody()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), 10);
			body = parsed;
		} catch (JsonException ex) {
			answer(exchange, 400, error(ex.getMessage(), "fake.bad_json"));
			return;
		}
		if (Boolean.TRUE.equals(body.get("drop"))) {
			_monitor.dropConnections();
		} else {
			_monitor.emit(body);
		}
		answer(exchange, 200, error("emitted", "fake.ok"));
	}

	/**
	 * Gives the controller a Zigbee coordinator with the device list of the given export (the file
	 * format of <code>spec/zigbee.md</code>, "Export"), served in pages of the given number of devices;
	 * <code>null</code> takes the coordinator away.
	 */
	public synchronized void setZigbeeExport(String export, int pageSize) throws JsonException {
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = export == null ? null : (Map<String, Object>) Json.parse(export, 16);
		_zigbee = parsed;
		_zigbeePage = pageSize;
	}

	/**
	 * Answers every <code>GET /api/zigbee/export</code> with the given status and an error body until
	 * called with 0.
	 */
	public synchronized void refuseZigbeeExport(int status) {
		_zigbeeRefusal = status;
	}

	/**
	 * The time the network was last opened for (0: closed), <code>null</code> if it never was.
	 */
	public synchronized Integer getPermitSeconds() {
		return _permitSeconds;
	}

	private void zigbeeExport(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			answer(exchange, 405, error("method not allowed", "api.method_not_allowed"));
			return;
		}
		if (!authorized(exchange) || !coordinator(exchange)) {
			return;
		}
		int refusal;
		synchronized (this) {
			refusal = _zigbeeRefusal;
		}
		if (refusal != 0) {
			answer(exchange, refusal, error("the coordinator is busy", "zigbee.busy"));
			return;
		}
		String query = exchange.getRequestURI().getQuery();
		int from = query != null && query.startsWith("from=") ? Integer.parseInt(query.substring(5)) : 0;
		Map<String, Object> body = new LinkedHashMap<>();
		synchronized (this) {
			for (String member : List.of("format", "version", "controller", "exported", "network")) {
				body.put(member, _zigbee.get(member));
			}
			List<?> devices = (List<?>) _zigbee.get("devices");
			int to = Math.min(devices.size(), from + _zigbeePage);
			body.put("count", Integer.valueOf(devices.size()));
			body.put("max_devices", Integer.valueOf(32));
			body.put("from", Integer.valueOf(from));
			body.put("devices", new ArrayList<>(devices.subList(Math.min(from, to), to)));
			body.put("next", to < devices.size() ? Integer.valueOf(to) : null);
		}
		answer(exchange, 200, body);
	}

	private void zigbeePermit(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			answer(exchange, 405, error("method not allowed", "api.method_not_allowed"));
			return;
		}
		if (!authorized(exchange) || !coordinator(exchange)) {
			return;
		}
		Object seconds;
		try (InputStream in = exchange.getRequestBody()) {
			seconds = ((Map<?, ?>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), 4)).get("seconds");
		} catch (JsonException | ClassCastException ex) {
			answer(exchange, 400, error("bad JSON", "api.bad_json"));
			return;
		}
		if (!(seconds instanceof Long value) || value.longValue() < 0 || value.longValue() > 254) {
			answer(exchange, 422, error("seconds must be 0..254", "zigbee.invalid"));
			return;
		}
		synchronized (this) {
			_permitSeconds = Integer.valueOf(value.intValue());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", Boolean.TRUE);
		body.put("seconds", value);
		answer(exchange, 200, body);
	}

	private boolean coordinator(HttpExchange exchange) throws IOException {
		synchronized (this) {
			if (_zigbee != null) {
				return true;
			}
		}
		drain(exchange);
		answer(exchange, 501, error("Zigbee is not available on this platform", "system.unavailable"));
		return false;
	}

	/**
	 * The diagnostics <code>GET /api/blocks</code> answers (the entries of <code>blocks</code>), in
	 * pages of the given number of blocks.
	 */
	public synchronized void setBlocks(List<Map<String, Object>> blocks, int pageSize) {
		_blocks = new ArrayList<>(blocks);
		_blocksPage = pageSize;
	}

	/**
	 * The number of <code>GET /api/blocks</code> it answered.
	 */
	public synchronized int getBlockRequests() {
		return _blockRequests;
	}

	private void blocks(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod()) || !"/api/blocks".equals(exchange.getRequestURI().getPath())) {
			answer(exchange, 405, error("method not allowed", "api.method_not_allowed"));
			return;
		}
		if (!authorized(exchange)) {
			return;
		}
		String query = exchange.getRequestURI().getQuery();
		int from = query != null && query.startsWith("from=") ? Integer.parseInt(query.substring(5)) : 0;
		Map<String, Object> body = new LinkedHashMap<>();
		synchronized (this) {
			_blockRequests++;
			int to = Math.min(_blocks.size(), from + _blocksPage);
			body.put("now_ms", Long.valueOf(_nowMs));
			body.put("count", Integer.valueOf(_blocks.size()));
			body.put("from", Integer.valueOf(from));
			body.put("next", to < _blocks.size() ? Integer.valueOf(to) : null);
			body.put("blocks", new ArrayList<>(_blocks.subList(Math.min(from, to), to)));
		}
		answer(exchange, 200, body);
	}

	/**
	 * Gives the controller an installation key (homecontrol issue #35, <code>spec/api.md</code>, "Signed
	 * runtime images"), the public key in 64 hex digits; <code>null</code> takes it away. While it holds
	 * one, an image must be signed (its header flag, the signature its last 64 bytes, homecontrol
	 * <code>spec/runtime-image.md</code> 9.17) and the signature verify under it: 403
	 * <code>image.unsigned</code> from the header, with the running image untouched, or 403
	 * <code>image.bad_signature</code> after the erase; the reason names the key's fingerprint, as the
	 * device's does.
	 */
	public synchronized void setKey(String publicKey) {
		_key = publicKey;
	}

	/**
	 * The installation key it holds, <code>null</code> for none.
	 */
	public synchronized String getKey() {
		return _key;
	}

	/**
	 * The signature the last <code>PUT /api/image</code> carried (its last 64 bytes in hex),
	 * <code>null</code> for an unsigned image.
	 */
	public synchronized String getLastSignature() {
		return _lastSignature;
	}

	/**
	 * The number of images it was sent.
	 */
	public synchronized int getPuts() {
		return _puts;
	}

	/**
	 * The number of unloads it was asked for.
	 */
	public synchronized int getDeletes() {
		return _deletes;
	}

	/**
	 * Answers the next <code>DELETE /api/image</code> with 500 <code>image.storage_error</code>: the
	 * image is unloaded, its stored copy could not be removed.
	 */
	public synchronized void storageErrorOnNextDelete() {
		_storageError = true;
	}

	/**
	 * Loads the given binary image, as if it had been deployed (by another tool); bytes that are no
	 * image are kept, but run no image.
	 */
	public synchronized void store(byte[] image) {
		Map<String, Object> view;
		try {
			view = ImageDecoder.decode(image);
		} catch (DecodeException ex) {
			view = null;
		}
		_loaded = view;
		_stored = image.clone();
		_refused = null;
	}

	/**
	 * Loads the image compiled from the given source (a JSON text), as {@link #store(byte[])}; a text
	 * that is no image is kept as it is.
	 */
	public void store(String source) {
		byte[] image;
		try {
			image = ImageCompiler.compile(source, ImageCompiler.Options.unsigned());
		} catch (RuntimeException ex) {
			System.err.println("FakeController: no image (" + ex.getMessage() + "), the text is kept.");
			image = source.getBytes(StandardCharsets.UTF_8);
		}
		store(image);
	}

	/**
	 * The binary image it runs, <code>null</code> for none.
	 */
	public synchronized byte[] getStored() {
		return _stored == null ? null : _stored.clone();
	}

	/**
	 * The image it runs in its source form ({@link ImageDecoder}), as JSON text; <code>null</code> for
	 * none.
	 */
	public synchronized String getStoredText() {
		return _loaded == null ? null : Json.write(_loaded);
	}

	/**
	 * The image it runs in its source form, <code>null</code> for none.
	 */
	public synchronized Map<String, Object> getLoaded() {
		return _loaded;
	}

	/**
	 * Answers the next <code>PUT /api/image</code> with a refusal of the given status and code,
	 * whatever the image (after the authentication).
	 */
	public synchronized void refuseNext(int status, String code, String error, List<Refusal> refusals) {
		Map<String, Object> body = refusal(error, code, refusals, refusals.size());
		_nextStatus = status;
		_nextBody = body;
	}

	/**
	 * <code>PUT /fake/refuse-next</code> with <code>{"status", "code", "error", "refusals": [{"path",
	 * "reason"}]}</code>: {@link #refuseNext} for a manual check in the running application.
	 */
	private void script(HttpExchange exchange) throws IOException {
		Map<?, ?> body;
		try (InputStream in = exchange.getRequestBody()) {
			body = (Map<?, ?>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), 10);
		} catch (JsonException ex) {
			answer(exchange, 400, error(ex.getMessage(), "fake.bad_json"));
			return;
		}
		List<Refusal> refusals = new ArrayList<>();
		if (body.get("refusals") instanceof List<?> list) {
			for (Object entry : list) {
				Map<?, ?> refusal = (Map<?, ?>) entry;
				refusals.add(new Refusal(String.valueOf(refusal.get("path")), String.valueOf(refusal.get("reason"))));
			}
		}
		refuseNext(((Number) body.get("status")).intValue(), String.valueOf(body.get("code")),
			String.valueOf(body.get("error")), refusals);
		answer(exchange, 200, error("scripted", "fake.ok"));
	}

	/**
	 * The format of the binary runtime image the fake reports it reads (<code>device.format</code> of
	 * <code>/api/status</code>, <code>format</code> of its <code>auth</code>): <code>null</code> as a
	 * firmware up to 1.2, which read JSON images and reports none; by default the newest E-Home writes
	 * ({@link Layout#FORMAT}, 1.3: the meter flag, haumacher/ehome #221).
	 */
	public synchronized String getFormat() {
		return _format;
	}

	/**
	 * @see #getFormat()
	 */
	public synchronized void setFormat(String format) {
		_format = format;
	}

	/**
	 * The size of its image slot in bytes (the limit it was created with).
	 */
	public int getSlot() {
		return _limit;
	}

	/**
	 * The code of the refusal of the last image it was given after which it runs none,
	 * <code>null</code> for none.
	 */
	public synchronized String getRefused() {
		return _refused;
	}

	/**
	 * As if its image did not load at boot (a power cut while it was written, homecontrol
	 * <code>spec/runtime-image.md</code> 7): it runs no image and reports the refusal.
	 */
	public synchronized void failAtBoot(String code, String reason) {
		_loaded = null;
		_stored = null;
		_refused = code;
		_refusedReason = reason;
	}

	private void status(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			answer(exchange, 405, error("method not allowed", "api.method_not_allowed"));
			return;
		}
		if (!authorized(exchange)) {
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		Map<String, Object> device = new LinkedHashMap<>();
		device.put("name", "homecontrol-fake");
		device.put("version", "0.0.0-fake");
		String format = getFormat();
		if (format != null) {
			device.put("format", format);
		}
		body.put("device", device);
		body.put("image", imageStatus());
		answer(exchange, 200, body);
	}

	/**
	 * The member <code>image</code> of its status and of its <code>auth</code>.
	 */
	public synchronized Map<String, Object> imageStatus() {
		Map<String, Object> image = new LinkedHashMap<>();
		if (_loaded == null) {
			image.put("loaded", Boolean.FALSE);
			image.put("slot", Integer.valueOf(_limit));
			if (_refused != null) {
				image.put("refused", _refused);
				image.put("reason", _refusedReason);
			}
			return image;
		}
		Map<?, ?> info = (Map<?, ?>) _loaded.get("image");
		image.put("loaded", Boolean.TRUE);
		image.put("project", info.get("projectId"));
		image.put("revision", info.get("revision"));
		image.put("name", info.get("name"));
		image.put("source", "api");
		image.put("format", "1.0");
		image.put("schemaVersion", info.get("schemaVersion"));
		image.put("bytes", Integer.valueOf(_stored.length));
		image.put("slot", Integer.valueOf(_limit));
		image.put("datapoints", Integer.valueOf(size(_loaded.get("datapoints"))));
		image.put("rules", Integer.valueOf(size(_loaded.get("rules"))));
		image.put("schedules", Integer.valueOf(size(_loaded.get("schedules"))));
		image.put("degraded", Boolean.FALSE);
		return image;
	}

	private void image(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		if (!"PUT".equals(method) && !"GET".equals(method) && !"DELETE".equals(method)) {
			answer(exchange, 405, error("method not allowed", "api.method_not_allowed"));
			return;
		}
		if (!authorized(exchange)) {
			return;
		}
		if ("GET".equals(method)) {
			byte[] stored = getStored();
			// Bytes E-Home cannot read (stored by a test) are answered as they are.
			if (stored == null) {
				answer(exchange, 404, error("no image is loaded", "image.none"));
			} else {
				exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
				exchange.sendResponseHeaders(200, stored.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(stored);
				}
			}
			return;
		}
		if ("DELETE".equals(method)) {
			boolean storageError;
			synchronized (this) {
				_deletes++;
				_loaded = null;
				_stored = null;
				_refused = null;
				storageError = _storageError;
				_storageError = false;
			}
			if (storageError) {
				answer(exchange, 500, error("the stored image could not be removed", "image.storage_error"));
			} else {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("ok", Boolean.TRUE);
				body.put("loaded", Boolean.FALSE);
				answer(exchange, 200, body);
			}
			return;
		}
		String length = exchange.getRequestHeaders().getFirst("Content-Length");
		if (length != null && Long.parseLong(length) > _limit) {
			drain(exchange);
			answer(exchange, 413, untouched(error("the image has " + length + " bytes; the device holds at most " + _limit
				+ " (its image slot)", "image.too_large")));
			return;
		}
		byte[] bytes;
		try (InputStream in = exchange.getRequestBody()) {
			bytes = in.readAllBytes();
		}
		if (exchange.getRequestHeaders().getFirst("X-Image-Signature") != null) {
			answer(exchange, 400, untouched(error("the signature is part of the binary image", "image.bad_request")));
			return;
		}
		String key;
		synchronized (this) {
			_puts++;
			key = _key;
		}
		if (bytes.length == 0) {
			answer(exchange, 400, untouched(error("the body is empty", "image.missing")));
			return;
		}
		if (bytes.length > _limit) {
			answer(exchange, 413, untouched(error("the image has " + bytes.length + " bytes; the device holds at most "
				+ _limit + " (its image slot)", "image.too_large")));
			return;
		}
		// The header, before the slot is erased (spec/runtime-image.md 10.1).
		ImageDecoder.Header header;
		try {
			header = ImageDecoder.header(bytes);
		} catch (DecodeException ex) {
			answer(exchange, 400, untouched(refusal("the image is no binary runtime image", "image.format",
				List.of(new Refusal("header.magic", ex.getMessage())), 1)));
			return;
		}
		if (header.major() != 1) {
			answer(exchange, 422, untouched(refusal("another major format", "image.version",
				List.of(new Refusal("header.major", "format " + header.format() + ": this firmware reads format 1 (up to 1.0)")), 1)));
			return;
		}
		if (key != null && !header.signed()) {
			answer(exchange, 403, untouched(refusal("the image is not signed; the device accepts only images signed with the "
				+ "installation key " + Signatures.fingerprint(key), "image.unsigned", List.of(new Refusal(
				"header.flags", "the image is not signed; the device accepts only images signed with the installation key "
					+ Signatures.fingerprint(key))), 1)));
			return;
		}
		synchronized (this) {
			_lastSignature = header.signed() && bytes.length >= 64
				? HexFormat.of().formatHex(bytes, bytes.length - 64, bytes.length) : null;
			if (_nextBody != null) {
				int status = _nextStatus;
				Map<String, Object> body = _nextBody;
				_nextBody = null;
				String code = String.valueOf(body.get("code"));
				if (GONE.contains(code)) {
					erase(code, body);
				}
				body.put("loaded", Boolean.valueOf(_loaded != null));
				body.put("revision", revision());
				answer(exchange, status, body);
				return;
			}
		}
		// The slot is erased and written: a refusal from here on leaves no image (spec/runtime-image.md I9).
		if (key != null) {
			byte[] signed = Arrays.copyOf(bytes, bytes.length - 64);
			if (!Signatures.verify(key, Signatures.message(signed), Arrays.copyOfRange(bytes, bytes.length - 64, bytes.length))) {
				answer(exchange, 403, gone(refusal("the signature does not verify under the installation key " + Signatures.fingerprint(key), "image.bad_signature", List.of(new Refusal(
					"signature", "the signature does not verify under the installation key " + Signatures.fingerprint(key))), 1)));
				return;
			}
		}
		Map<String, Object> image;
		try {
			image = ImageDecoder.decode(bytes);
		} catch (DecodeException ex) {
			String code = ex.getMessage().startsWith("the CRC-32") ? "image.damaged" : "image.invalid";
			answer(exchange, 422, gone(refusal("the image is invalid", code, List.of(new Refusal("header", ex.getMessage())), 1)));
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", Boolean.TRUE);
		Map<String, Object> info = new LinkedHashMap<>((Map<String, Object>) image.get("image"));
		info.put("format", header.format());
		info.put("bytes", Integer.valueOf(bytes.length));
		info.put("crc", header.crcText());
		info.put("signed", Boolean.valueOf(header.signed()));
		body.put("image", info);
		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("datapoints", Integer.valueOf(size(image.get("datapoints"))));
		counts.put("locations", Integer.valueOf(size(image.get("locations"))));
		counts.put("functions", Integer.valueOf(size(image.get("functions"))));
		counts.put("rules", Integer.valueOf(size(image.get("rules"))));
		counts.put("schedules", Integer.valueOf(size(image.get("schedules"))));
		body.put("counts", counts);
		body.put("source", "api");
		synchronized (this) {
			_loaded = image;
			_stored = bytes;
			_refused = null;
		}
		answer(exchange, 200, body);
	}

	/** The codes of the refusals after which the device runs no image (spec/runtime-image.md 4). */
	private static final Set<String> GONE =
		Set.of("image.damaged", "image.bad_signature", "image.invalid", "image.no_memory", "image.apply_failed");

	private synchronized Map<String, Object> untouched(Map<String, Object> body) {
		body.put("loaded", Boolean.valueOf(_loaded != null));
		body.put("revision", revision());
		return body;
	}

	private synchronized Map<String, Object> gone(Map<String, Object> body) {
		erase(String.valueOf(body.get("code")), body);
		body.put("loaded", Boolean.FALSE);
		return body;
	}

	private void erase(String code, Map<String, Object> body) {
		_loaded = null;
		_stored = null;
		_refused = code;
		Object first = body.get("refusals") instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
		_refusedReason = first instanceof Map<?, ?> map ? map.get("path") + ": " + map.get("reason") : null;
	}

	private Object revision() {
		return _loaded == null ? null : ((Map<?, ?>) _loaded.get("image")).get("revision");
	}

	private boolean authorized(HttpExchange exchange) throws IOException {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null) {
			drain(exchange);
			answer(exchange, 401, error("authentication required", "auth.required"));
			return false;
		}
		if (!header.equals("Bearer " + _token)) {
			drain(exchange);
			answer(exchange, 401, error("unknown or revoked token", "auth.token_invalid"));
			return false;
		}
		return true;
	}

	private static void drain(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			in.readAllBytes();
		}
	}

	private static Map<String, Object> error(String error, String code) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("code", code);
		return body;
	}

	private static Map<String, Object> refusal(String error, String code, List<Refusal> refusals, int total) {
		Map<String, Object> body = error(error, code);
		List<Object> list = new ArrayList<>();
		for (Refusal refusal : refusals) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("path", refusal.path());
			entry.put("reason", refusal.reason());
			list.add(entry);
		}
		body.put("refusals", list);
		body.put("total", Integer.valueOf(total));
		return body;
	}

	private static int size(Object list) {
		return list instanceof List<?> elements ? elements.size() : 0;
	}

	private static void answer(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
		answer(exchange, status, Json.write(body).getBytes(StandardCharsets.UTF_8));
	}

	private static void answer(HttpExchange exchange, int status, byte[] bytes) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/**
	 * Runs a {@link FakeController} on <code>127.0.0.1</code> until the process is stopped.
	 *
	 * @param args
	 *        The port and the token; optionally the file of a Zigbee export it serves as its device
	 *        list.
	 */
	public static void main(String[] args) throws IOException, JsonException {
		FakeController controller = new FakeController(Integer.parseInt(args[0]), args[1], 262144);
		if (args.length > 2) {
			controller.setZigbeeExport(java.nio.file.Files.readString(java.nio.file.Path.of(args[2])), 16);
		}
		System.out.println("Fake controller on 127.0.0.1:" + controller.getPort());
	}

}
