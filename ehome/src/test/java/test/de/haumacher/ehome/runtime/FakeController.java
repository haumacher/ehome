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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * An in-process stand-in for a HomeControl controller speaking the deployment protocol (homecontrol
 * <code>spec/api.md</code>, "Runtime image" and "Status") on <code>127.0.0.1</code>.
 *
 * <p>
 * It authenticates by a bearer token (401 <code>auth.required</code>, <code>auth.token_invalid</code>),
 * refuses a body above its limit before reading it (413 <code>api.body_too_large</code>), checks an
 * image with {@link ImageValidator} (the checks of the controller's loader, pinned by the same
 * conformance cases) and answers 400 <code>image.bad_json</code>, 422 <code>image.version</code> /
 * <code>image.invalid</code>, 413 <code>image.too_large</code>, or loads the image and answers 200. A
 * test can script the next answer to <code>PUT /api/image</code> ({@link #refuseNext}), e.g. 507
 * <code>image.no_memory</code> or 500 <code>image.apply_failed</code>, which no image provokes. The
 * status (<code>GET /api/status</code>) reports the loaded image. <code>GET /api/image</code> answers
 * the stored image byte for byte (404 <code>image.none</code> without one), <code>DELETE
 * /api/image</code> unloads it (200 <code>{"ok": true, "loaded": false}</code>; a test can script the
 * storage error of the next one, {@link #storageErrorOnNextDelete()}: 500
 * <code>image.storage_error</code>, the image unloaded anyway). For a manual check, the next refusal
 * is scripted with <code>PUT /fake/refuse-next</code>. Given an installation key ({@link #setKey(String)},
 * homecontrol issue #35), it accepts only images whose signature verifies under it.
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
 * Started from the command line (<code>java -cp target/classes:target/test-classes
 * test.de.haumacher.ehome.runtime.FakeController &lt;port&gt; &lt;token&gt; [&lt;zigbee export&gt;]</code>), it
 * serves until it is stopped, for trying the deployment (and the Zigbee pairing) in the running
 * application.
 * </p>
 */
public class FakeController {

	private final HttpServer _server;

	private final String _token;

	private final int _limit;

	private Map<String, Object> _loaded;

	private String _storedText;

	private int _nextStatus;

	private String _schema = "1.2";

	private Map<String, Object> _nextBody;

	private int _puts;

	private int _deletes;

	private boolean _storageError;

	private final FakeMonitorStream _monitor;

	private Map<String, Object> _zigbee;

	private int _zigbeePage;

	private int _zigbeeRefusal;

	private Integer _permitSeconds;

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
		_server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		_server.createContext("/api/image", this::image);
		_server.createContext("/api/status", this::status);
		_server.createContext("/fake/refuse-next", this::script);
		_server.createContext("/api/auth/session", this::session);
		_server.createContext("/fake/monitor", this::scriptMonitor);
		_server.createContext("/api/zigbee/export", this::zigbeeExport);
		_server.createContext("/api/zigbee/permit", this::zigbeePermit);
		_monitor = new FakeMonitorStream(token);
		_server.start();
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
	 * Gives the controller an installation key (homecontrol issue #35, <code>spec/api.md</code>, "Signed
	 * runtime images"), the public key in 64 hex digits; <code>null</code> takes it away. While it holds
	 * one, <code>PUT /api/image</code> needs the header <code>X-Image-Signature</code> with a signature
	 * of the body that verifies under it: 403 <code>image.unsigned</code> without, 400
	 * <code>image.bad_signature</code> for one that is not 128 hex digits, 403
	 * <code>image.bad_signature</code> for one that does not verify; the reason names the key's
	 * fingerprint, as the device's does.
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
	 * The signature the last <code>PUT /api/image</code> came with, <code>null</code> for none.
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
	 * Stores the given text as the image, as if it had been deployed (by another tool); a text that is
	 * no JSON object is stored, but runs no image.
	 */
	public synchronized void store(String text) {
		Map<String, Object> image;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = Json.parse(text, ImageValidator.MAX_NESTING) instanceof Map<?, ?> map
				? (Map<String, Object>) map : null;
			image = parsed;
		} catch (JsonException ex) {
			image = null;
		}
		_loaded = image;
		_storedText = text;
	}

	/**
	 * The text of the image it runs, <code>null</code> for none.
	 */
	public synchronized String getStoredText() {
		return _storedText;
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
	 * The newest image schema the fake reports it reads (<code>device.schema</code> of
	 * <code>/api/status</code>, <code>schema</code> of its <code>auth</code>): <code>null</code> as a
	 * firmware before schema 1.2, which reports none.
	 */
	public String getSchema() {
		return _schema;
	}

	/**
	 * @see #getSchema()
	 */
	public void setSchema(String schema) {
		_schema = schema;
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
		if (_schema != null) {
			device.put("schema", _schema);
		}
		body.put("device", device);
		body.put("image", imageStatus());
		answer(exchange, 200, body);
	}

	private synchronized Map<String, Object> imageStatus() {
		Map<String, Object> image = new LinkedHashMap<>();
		if (_loaded == null) {
			image.put("loaded", Boolean.FALSE);
			return image;
		}
		Map<?, ?> info = (Map<?, ?>) _loaded.get("image");
		image.put("loaded", Boolean.TRUE);
		image.put("project", info.get("projectId"));
		image.put("revision", info.get("revision"));
		image.put("name", info.get("name"));
		image.put("source", "api");
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
			String stored = getStoredText();
			if (stored == null) {
				answer(exchange, 404, error("no image is stored", "image.none"));
			} else {
				answer(exchange, 200, stored.getBytes(StandardCharsets.UTF_8));
			}
			return;
		}
		if ("DELETE".equals(method)) {
			boolean storageError;
			synchronized (this) {
				_deletes++;
				_loaded = null;
				_storedText = null;
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
			answer(exchange, 413, error("the body exceeds " + _limit + " bytes", "api.body_too_large"));
			return;
		}
		byte[] bytes;
		try (InputStream in = exchange.getRequestBody()) {
			bytes = in.readAllBytes();
		}
		if (bytes.length > _limit) {
			answer(exchange, 413, error("the body exceeds " + _limit + " bytes", "api.body_too_large"));
			return;
		}
		String signature = exchange.getRequestHeaders().getFirst(ControllerClient.SIGNATURE_HEADER);
		String key;
		synchronized (this) {
			_puts++;
			_lastSignature = signature;
			key = _key;
		}
		if (key != null) {
			String fingerprint = Signatures.fingerprint(key);
			if (signature == null) {
				answer(exchange, 403, error("the image is not signed; the device accepts only images signed with the "
					+ "installation key " + fingerprint, "image.unsigned"));
				return;
			}
			if (!signature.trim().matches("[0-9a-fA-F]{128}")) {
				answer(exchange, 400, error("the signature is not 128 hex digits", "image.bad_signature"));
				return;
			}
			if (!Signatures.verifyImage(key, bytes, signature.trim())) {
				answer(exchange, 403, error("the signature does not verify under the installation key " + fingerprint,
					"image.bad_signature"));
				return;
			}
		}
		synchronized (this) {
			if (_nextBody != null) {
				int status = _nextStatus;
				Map<String, Object> body = _nextBody;
				_nextBody = null;
				body.put("loaded", Boolean.valueOf(_loaded != null));
				body.put("revision", revision());
				answer(exchange, status, body);
				return;
			}
		}
		if (bytes.length == 0) {
			answer(exchange, 400, error("the body is empty", "image.missing"));
			return;
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		ImageReport report = ImageValidator.validate(text);
		if (!report.isValid()) {
			int status;
			String code;
			String message;
			switch (report.getKind()) {
				case SYNTAX:
					status = 400;
					code = "image.bad_json";
					message = "the image is not JSON";
					break;
				case VERSION:
					status = 422;
					code = "image.version";
					message = "the image has a newer schema version";
					break;
				case TOO_LARGE:
					status = 413;
					code = "image.too_large";
					message = "the image is too large";
					break;
				default:
					status = 422;
					code = "image.invalid";
					message = "the image is invalid";
			}
			List<Refusal> refusals = report.getRefusals();
			Map<String, Object> body = refusal(message, code, refusals.subList(0, Math.min(12, refusals.size())),
				refusals.size());
			synchronized (this) {
				body.put("loaded", Boolean.valueOf(_loaded != null));
				body.put("revision", revision());
			}
			answer(exchange, status, body);
			return;
		}
		Map<String, Object> image;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = (Map<String, Object>) Json.parse(text, ImageValidator.MAX_NESTING);
			image = parsed;
		} catch (JsonException ex) {
			throw new IOException(ex);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", Boolean.TRUE);
		Map<String, Object> info = new LinkedHashMap<>((Map<String, Object>) image.get("image"));
		info.put("bytes", Integer.valueOf(bytes.length));
		body.put("image", info);
		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("datapoints", Integer.valueOf(size(image.get("datapoints"))));
		counts.put("locations", Integer.valueOf(size(image.get("locations"))));
		counts.put("functions", Integer.valueOf(size(image.get("functions"))));
		counts.put("rules", Integer.valueOf(size(image.get("rules"))));
		counts.put("schedules", Integer.valueOf(size(image.get("schedules"))));
		body.put("counts", counts);
		body.put("source", "api");
		body.put("stored", Boolean.TRUE);
		synchronized (this) {
			_loaded = image;
			_storedText = text;
		}
		answer(exchange, 200, body);
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
