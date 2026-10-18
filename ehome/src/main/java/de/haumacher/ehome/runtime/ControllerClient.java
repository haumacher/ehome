/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The client side of the deployment protocol of a HomeControl controller (homecontrol
 * <code>spec/api.md</code>, "Runtime image" and "Status"): <code>PUT</code>, <code>GET</code> and
 * <code>DELETE /api/image</code> and <code>GET /api/status</code>, authenticated by a bearer token,
 * and the open <code>GET /api/auth/session</code>; of its Zigbee coordinator ("Zigbee", haumacher/ehome
 * #40): <code>GET /api/zigbee/export</code> and <code>POST /api/zigbee/permit</code>. The monitor stream over the WebSocket is
 * {@link de.haumacher.ehome.monitor.ControllerBusLink}.
 *
 * <p>
 * Three transports carry the same calls (haumacher/ehome #54): HTTP on the LAN (host, port, token),
 * the E-Home connection of a connected controller ({@link #over(ControllerConnection, String, Duration)}:
 * the request/response envelope of homecontrol <code>spec/api.md</code>, "The socket"), and none
 * ({@link #offline(String, String, Duration)}): every call of an offline controller fails with the
 * reason, which names its last contact. {@link de.haumacher.ehome.runtime.Deployer#client(com.top_logic.model.TLObject)}
 * chooses.
 * </p>
 */
public final class ControllerClient {

	/**
	 * An answer of the controller.
	 *
	 * @param status
	 *        The HTTP status.
	 * @param body
	 *        The JSON object of the body; empty if the body is no JSON object.
	 * @param text
	 *        The body as text.
	 */
	public record Answer(int status, Map<String, Object> body, String text) {

		/**
		 * Whether the status is 2xx.
		 */
		public boolean ok() {
			return status >= 200 && status < 300;
		}

		/**
		 * A text member of the body, <code>null</code> if there is none.
		 */
		public String string(String member) {
			Object value = body.get(member);
			return value == null ? null : value.toString();
		}
	}

	private static final int MAX_NESTING = 16;

	private final String _host;

	private final int _port;

	private final String _token;

	private final HttpClient _http;

	private final Duration _timeout;

	private final ControllerConnection _connection;

	private final String _label;

	private final String _offline;

	/**
	 * Creates a {@link ControllerClient}.
	 *
	 * @param host
	 *        The host name or address of the controller.
	 * @param port
	 *        The port of its web API.
	 * @param token
	 *        The bearer token, <code>null</code> to send none.
	 * @param timeout
	 *        How long to wait for the connection and for an answer (a load takes up to a few hundred
	 *        milliseconds on the device).
	 */
	public ControllerClient(String host, int port, String token, Duration timeout) {
		this(host, port, token, timeout, null, null, null);
	}

	private ControllerClient(String host, int port, String token, Duration timeout, ControllerConnection connection,
			String label, String offline) {
		_host = host;
		_port = port;
		_token = token;
		_timeout = timeout;
		_connection = connection;
		_label = label;
		_offline = offline;
		_http = connection == null && offline == null ? HttpClient.newBuilder().connectTimeout(timeout).build() : null;
	}

	/**
	 * A client making its calls over the E-Home connection of a controller.
	 *
	 * @param label
	 *        What {@link #address()} says, for messages.
	 */
	public static ControllerClient over(ControllerConnection connection, String label, Duration timeout) {
		return new ControllerClient("", 0, null, timeout, connection, label, null);
	}

	/**
	 * A client of a controller that cannot be reached: every call fails with the given reason.
	 *
	 * @param label
	 *        What {@link #address()} says, for messages.
	 */
	public static ControllerClient offline(String label, String reason, Duration timeout) {
		return new ControllerClient("", 0, null, timeout, null, label, reason);
	}

	/**
	 * The E-Home connection the calls go over, <code>null</code> for HTTP (or none).
	 */
	public ControllerConnection connection() {
		return _connection;
	}

	/**
	 * The address of the controller as written for the user, <code>host:port</code> (for a
	 * controller reached over its connection or not at all, the label given).
	 */
	public String address() {
		if (_label != null) {
			return _label;
		}
		return (_host.contains(":") ? "[" + _host + "]" : _host) + ":" + _port;
	}

	/**
	 * The host name or address of the controller.
	 */
	public String host() {
		return _host;
	}

	/**
	 * The port of its web API.
	 */
	public int port() {
		return _port;
	}

	/**
	 * The bearer token, <code>null</code> if none is sent.
	 */
	public String token() {
		return _token == null || _token.isBlank() ? null : _token.trim();
	}

	/**
	 * The time to wait for the connection and for an answer.
	 */
	public Duration timeout() {
		return _timeout;
	}

	/**
	 * <code>GET /api/auth/session</code> (open): whether the controller is onboarded, its name and
	 * version, and the port of its WebSocket (<code>ws_port</code>, 0 for the port of the web API).
	 */
	public Answer session() throws IOException {
		if (_http == null) {
			throw new IOException(_offline != null ? _offline : "The session is not asked over the E-Home connection.");
		}
		return send(request("/api/auth/session", null).GET());
	}

	/**
	 * <code>GET /api/status</code>.
	 */
	public Answer status() throws IOException {
		return call("GET", "/api/status", null, null, null);
	}

	/**
	 * <code>PUT /api/image</code> with the given image.
	 */
	public Answer putImage(byte[] image) throws IOException {
		if (_connection != null && _offline == null) {
			// Byte for byte, whatever its size: the controller stores the image as it comes.
			return _connection.request("PUT", "/api/image", image, true, _timeout);
		}
		return call("PUT", "/api/image", null, RuntimeImage.CONTENT_TYPE, image);
	}

	/**
	 * <code>GET /api/image</code>: the stored image, byte for byte as it was deployed (404
	 * <code>image.none</code> when none is stored).
	 */
	public Answer getImage() throws IOException {
		return call("GET", "/api/image", null, null, null);
	}

	/**
	 * <code>DELETE /api/image</code>: unloads the image and removes it with its persisted values and
	 * history.
	 */
	public Answer deleteImage() throws IOException {
		return call("DELETE", "/api/image", null, null, null);
	}

	/**
	 * <code>GET /api/zigbee/export?from=n</code>: a page of the Zigbee devices with their interview
	 * results and functions (homecontrol <code>spec/zigbee.md</code>, "Export"); <code>next</code> is
	 * the <code>from</code> of the next page or <code>null</code>. The first page is asked for with
	 * <code>from=0</code> as well, so every page's request names its position.
	 */
	public Answer zigbeeExport(int from) throws IOException {
		return call("GET", "/api/zigbee/export", "from=" + from, null, null);
	}

	/**
	 * <code>POST /api/zigbee/permit</code>: opens the Zigbee network for joining for the given number
	 * of seconds (1..254); 0 closes it.
	 */
	public Answer zigbeePermit(int seconds) throws IOException {
		return call("POST", "/api/zigbee/permit", null, "application/json",
			("{\"seconds\": " + seconds + "}").getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A call of the controller's API over the transport of this client.
	 *
	 * @param query
	 *        The query without <code>?</code>, <code>null</code> for none.
	 * @param contentType
	 *        The type of the body, <code>null</code> for none.
	 * @param body
	 *        The body, <code>null</code> for none.
	 */
	public Answer call(String method, String path, String query, String contentType, byte[] body) throws IOException {
		if (_offline != null) {
			throw new IOException(_offline);
		}
		if (_connection != null) {
			return _connection.request(method, query == null ? path : path + "?" + query, body, _timeout);
		}
		HttpRequest.Builder request = request(path, query);
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
			: HttpRequest.BodyPublishers.ofByteArray(body));
		return send(request);
	}

	private HttpRequest.Builder request(String path, String query) throws IOException {
		URI uri;
		try {
			uri = new URI("http", null, _host, _port, path, query, null);
		} catch (URISyntaxException ex) {
			throw new IOException("Invalid controller address " + address() + ": " + ex.getMessage(), ex);
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(_timeout).header("Accept", "application/json");
		if (_token != null && !_token.isBlank()) {
			builder.header("Authorization", "Bearer " + _token.trim());
		}
		return builder;
	}

	private Answer send(HttpRequest.Builder request) throws IOException {
		HttpResponse<byte[]> response;
		try {
			response = _http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the controller.", ex);
		}
		String text = new String(response.body(), StandardCharsets.UTF_8);
		Map<String, Object> body = Collections.emptyMap();
		try {
			if (!text.isBlank() && Json.parse(text, MAX_NESTING) instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> object = (Map<String, Object>) map;
				body = object;
			}
		} catch (JsonException ex) {
			// Not JSON: the text is kept.
		}
		return new Answer(response.statusCode(), body, text);
	}

}
