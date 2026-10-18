/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.top_logic.basic.Logger;

import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * One WebSocket of a controller connected to E-Home (haumacher/ehome #54, homecontrol
 * <code>spec/api.md</code>, "E-Home connection"): the authentication, the controller's live feed and
 * E-Home's requests.
 *
 * <p>
 * On open, E-Home sends a <code>challenge</code> with a nonce of 128 random bits, new for every
 * connection; the controller answers <code>auth</code> with its id and
 * <code>HMAC-SHA256(secret, nonce)</code> in hex ({@link #hmac(String, String)}), which is compared in
 * constant time with the HMAC of the secret stored for that id. Then E-Home answers <code>ready</code> with the installation's name
 * and sends <code>subscribe</code>; otherwise <code>refused</code> with a code
 * ({@link #REFUSED_UNKNOWN}, {@link #REFUSED_AUTH}, {@link #REFUSED_REQUIRED},
 * {@link #REFUSED_TIMEOUT}) and the close code 1008. A replayed <code>auth</code> carries the HMAC of
 * an old nonce and fails.
 * </p>
 *
 * <p>
 * After <code>ready</code> the controller's messages are its live feed (<code>status</code>,
 * <code>datapoints</code>, <code>zigbee</code>, <code>monitor</code>, <code>result</code>, ...):
 * the last status and the values of the datapoints are kept, and every message goes to the
 * {@link Listener}s of the connection and of the {@link ControllerConnections}. E-Home's requests to
 * the controller's API go through {@link #request(String, String, byte[], Duration)}, one at a time
 * (later callers wait for their turn); a body above {@value #MAX_INLINE_BODY} bytes (and the runtime
 * image of any size) goes as <code>length</code> followed by binary frames of {@value #BINARY_CHUNK}
 * bytes. A response may come the same way (<code>length</code> instead of <code>body</code>, then
 * binary frames), as the controller streams <code>GET /api/image</code>.
 * </p>
 *
 * <p>
 * Threads: the messages are handled on the servlet container's thread reading the socket, one at a
 * time per connection. The authentication reads the knowledge base there (in a system interaction);
 * everything that writes it (the last contact, the firmware version) goes to the single thread of
 * {@link ControllerConnections#execute(Runnable)}. A request blocks its caller (the thread of a view
 * command) until the response arrives on the reading thread.
 * </p>
 */
public final class ControllerConnection {

	/** Close code: normal closure. */
	public static final int CLOSE_NORMAL = 1000;

	/** Close code: the endpoint goes away (a failed send, the server stops). */
	public static final int CLOSE_GOING_AWAY = 1001;

	/** Close code: a policy violation (a refused authentication). */
	public static final int CLOSE_POLICY = 1008;

	/** The largest request body sent inline in the request message. */
	public static final int MAX_INLINE_BODY = 4096;

	/** The size of the binary frames of a larger body. */
	public static final int BINARY_CHUNK = 4096;

	/** Refusal: no controller has the id (never enrolled, or forgotten). */
	public static final String REFUSED_UNKNOWN = "ehome.unknown_controller";

	/** Refusal: the HMAC does not fit the nonce and the secret. */
	public static final String REFUSED_AUTH = "ehome.auth_failed";

	/** Refusal: another message came before <code>auth</code>. */
	public static final String REFUSED_REQUIRED = "ehome.auth_required";

	/** Refusal: no <code>auth</code> within {@link ControllerConnections#AUTH_TIMEOUT}. */
	public static final String REFUSED_TIMEOUT = "ehome.auth_timeout";

	/** Close reason: the controller was forgotten in E-Home. */
	public static final String REFUSED_FORGOTTEN = "ehome.forgotten";

	/** Close reason: the same controller connected again. */
	public static final String CLOSED_REPLACED = "ehome.replaced";

	private static final int MAX_NESTING = 32;

	/** The largest response announced with <code>length</code> that E-Home takes (the largest image and some). */
	public static final int MAX_STREAMED_BODY = 512 * 1024;

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * The transport of a connection: the WebSocket ({@link ControllerSocket}).
	 */
	public interface Peer {

		/** Sends a text message. */
		void sendText(String text) throws IOException;

		/** Sends a binary message. */
		void sendBinary(byte[] data) throws IOException;

		/** Sends a close frame with the code and reason and closes the transport. */
		void close(int code, String reason);

		/** The remote address, for the log. */
		String remote();
	}

	/**
	 * Receiver of the feed of a connection. Called on the thread reading the socket: it must not
	 * block.
	 */
	public interface Listener {

		/**
		 * A message of the controller after <code>ready</code> (not the responses to requests).
		 *
		 * @param type
		 *        Its <code>type</code>.
		 */
		void message(ControllerConnection connection, String type, Map<String, Object> message);

		/**
		 * The connection ended.
		 */
		default void closed(ControllerConnection connection, String reason) {
			// Ignored by default.
		}
	}

	private enum State {
		CHALLENGED, READY, CLOSED;
	}

	private final String _pathId;

	private final Peer _peer;

	private final String _nonce;

	private final long _opened = System.nanoTime();

	private volatile State _state = State.CHALLENGED;

	private volatile String _installation;

	private volatile String _name;

	private volatile String _version;

	private volatile String _build;

	private volatile Instant _since;

	private volatile Map<String, Object> _status = Collections.emptyMap();

	private final Map<Object, Map<String, Object>> _datapoints = new ConcurrentHashMap<>();

	private final List<Listener> _listeners = new CopyOnWriteArrayList<>();

	private final ReentrantLock _requests = new ReentrantLock(true);

	private final Object _sendLock = new Object();

	private final AtomicLong _ids = new AtomicLong();

	private volatile long _pendingId;

	private volatile CompletableFuture<Answer> _pending;

	private volatile ByteArrayOutputStream _stream;

	private volatile Map<String, Object> _streamHead;

	private volatile int _streamLength;

	/**
	 * Creates a {@link ControllerConnection} for a socket opened at the path of the given controller
	 * id.
	 */
	public ControllerConnection(String pathId, Peer peer) {
		_pathId = pathId;
		_peer = peer;
		byte[] nonce = new byte[16];
		RANDOM.nextBytes(nonce);
		_nonce = HexFormat.of().formatHex(nonce);
	}

	/**
	 * The id of the controller (the path the socket was opened at).
	 */
	public String controllerId() {
		return _pathId;
	}

	/**
	 * The name of the controller in E-Home, known after <code>ready</code>.
	 */
	public String name() {
		return _name;
	}

	/**
	 * The installation's name sent in <code>ready</code>.
	 */
	public String installation() {
		return _installation;
	}

	/**
	 * The firmware version the controller reported (in <code>auth</code> or its status).
	 */
	public String version() {
		return _version;
	}

	/**
	 * The build of the firmware the controller reported.
	 */
	public String build() {
		return _build;
	}

	/**
	 * When the controller was accepted (<code>ready</code>), <code>null</code> before.
	 */
	public Instant since() {
		return _since;
	}

	/**
	 * Whether the controller is authenticated and the connection open.
	 */
	public boolean isReady() {
		return _state == State.READY;
	}

	/**
	 * The members of the last <code>status</code> message, empty before the first.
	 */
	public Map<String, Object> status() {
		return _status;
	}

	/**
	 * The last value the feed reported for each datapoint (by its id): the datapoint of the snapshot,
	 * updated by each change.
	 */
	public Map<Object, Map<String, Object>> datapoints() {
		return Collections.unmodifiableMap(_datapoints);
	}

	/**
	 * Adds a receiver of the feed.
	 */
	public void addListener(Listener listener) {
		_listeners.add(listener);
	}

	/**
	 * Removes a receiver of the feed.
	 */
	public void removeListener(Listener listener) {
		_listeners.remove(listener);
	}

	/**
	 * The nanosecond time the socket was opened.
	 */
	long opened() {
		return _opened;
	}

	/**
	 * The socket is open: sends the challenge.
	 */
	public void open() {
		Map<String, Object> challenge = new LinkedHashMap<>();
		challenge.put("type", "challenge");
		challenge.put("nonce", _nonce);
		try {
			sendText(Json.compact(challenge));
		} catch (IOException ex) {
			_peer.close(CLOSE_GOING_AWAY, "send failed");
		}
	}

	/**
	 * A text message of the controller.
	 */
	public void received(String text) {
		if (_state == State.CLOSED) {
			return;
		}
		Map<String, Object> message;
		try {
			if (!(Json.parse(text, MAX_NESTING) instanceof Map<?, ?> map)) {
				refuseOrIgnore("not a JSON object");
				return;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> object = (Map<String, Object>) map;
			message = object;
		} catch (JsonException ex) {
			refuseOrIgnore("no JSON: " + ex.getMessage());
			return;
		}
		String type = String.valueOf(message.get("type"));
		if (_state == State.CHALLENGED) {
			if ("auth".equals(type)) {
				authenticate(message);
			} else {
				refuse(REFUSED_REQUIRED, "The first message must be auth.");
			}
			return;
		}
		switch (type) {
			case "response":
				response(message, text);
				return;
			case "ping":
				try {
					sendText("{\"type\":\"pong\"}");
				} catch (IOException ex) {
					// The close reports it.
				}
				return;
			case "status":
				status(message);
				break;
			case "datapoints":
				datapoints(message);
				break;
			case "auth":
				Logger.warn("Controller " + _pathId + " sent auth again; ignored.", ControllerConnection.class);
				return;
			default:
				break;
		}
		for (Listener listener : _listeners) {
			try {
				listener.message(this, type, message);
			} catch (RuntimeException ex) {
				Logger.error("A listener of controller " + _pathId + " failed.", ex, ControllerConnection.class);
			}
		}
		ControllerConnections.message(this, type, message);
	}

	/**
	 * A binary message of the controller: a piece of a response announced with <code>length</code>
	 * (a streamed answer such as <code>GET /api/image</code>).
	 */
	public void receivedBinary(byte[] data) {
		if (_state == State.CHALLENGED) {
			refuse(REFUSED_REQUIRED, "The first message must be auth.");
			return;
		}
		ByteArrayOutputStream stream = _stream;
		if (stream == null) {
			Logger.warn("Controller " + _pathId + " sent " + data.length + " binary bytes E-Home does not wait for.",
				ControllerConnection.class);
			return;
		}
		stream.write(data, 0, data.length);
		if (stream.size() >= _streamLength) {
			streamDone();
		}
	}

	private void streamDone() {
		CompletableFuture<Answer> pending = _pending;
		Map<String, Object> head = _streamHead;
		ByteArrayOutputStream stream = _stream;
		_stream = null;
		_streamHead = null;
		if (pending == null || head == null) {
			return;
		}
		if (stream.size() != _streamLength) {
			pending.completeExceptionally(new IOException("The controller sent " + stream.size()
				+ " bytes of a response announced with " + _streamLength + "."));
			return;
		}
		// The body as it came, byte for byte (the runtime image is compared so): not embedded into the
		// envelope's JSON, which would lose the whitespace around it.
		String text = new String(stream.toByteArray(), StandardCharsets.UTF_8);
		Map<String, Object> body = Collections.emptyMap();
		if (!text.isBlank()) {
			try {
				if (Json.parse(text, MAX_NESTING) instanceof Map<?, ?> map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> object = (Map<String, Object>) map;
					body = object;
				}
			} catch (JsonException ex) {
				// A body that is no JSON: only its text.
			}
		}
		int status = head.get("status") instanceof Number number ? number.intValue() : 0;
		pending.complete(new Answer(status, body, text));
	}

	private void refuseOrIgnore(String problem) {
		if (_state == State.CHALLENGED) {
			refuse(REFUSED_REQUIRED, "The first message must be auth (" + problem + ").");
		} else {
			Logger.warn("Controller " + _pathId + " sent a message that is " + problem, ControllerConnection.class);
		}
	}

	private void authenticate(Map<String, Object> message) {
		Object id = message.get("controller");
		if (id != null && !_pathId.equals(id.toString())) {
			refuse(REFUSED_UNKNOWN, "The controller id does not match the path of the socket.");
			return;
		}
		ControllerConnections.Credentials credentials = ControllerConnections.credentials(_pathId);
		if (credentials == null) {
			refuse(REFUSED_UNKNOWN, "No controller with this id is enrolled (it may have been forgotten).");
			return;
		}
		byte[] given;
		try {
			given = HexFormat.of().parseHex(String.valueOf(message.get("mac")).trim().toLowerCase());
		} catch (IllegalArgumentException ex) {
			given = new byte[0];
		}
		byte[] expected = hmac(credentials.secret(), _nonce);
		if (!MessageDigest.isEqual(expected, given)) {
			refuse(REFUSED_AUTH, "The authentication failed.");
			return;
		}
		_name = credentials.name();
		_installation = credentials.installation();
		_version = text(message.get("version"));
		_build = text(message.get("build"));
		_since = Instant.now();
		_state = State.READY;
		Map<String, Object> ready = new LinkedHashMap<>();
		ready.put("type", "ready");
		ready.put("installation", _installation == null ? "" : _installation);
		try {
			sendText(Json.compact(ready));
			ControllerConnections.ready(this);
			sendText("{\"type\":\"subscribe\"}");
		} catch (IOException ex) {
			_peer.close(CLOSE_GOING_AWAY, "send failed");
		}
	}

	/**
	 * <code>HMAC-SHA256(secret, nonce)</code> (homecontrol <code>spec/api.md</code>, "The socket"):
	 * the key is the 32 bytes the secret's 64 hex digits encode, the data the bytes the nonce's hex
	 * digits encode, as the controller computes it (homecontrol <code>core/include/hc/ehome_link.h</code>,
	 * <code>ehome_mac</code>).
	 */
	public static byte[] hmac(String secret, String nonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
			return mac.doFinal(HexFormat.of().parseHex(nonce));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA256 is missing.", ex);
		}
	}

	private void status(Map<String, Object> message) {
		_status = message;
		if (message.get("device") instanceof Map<?, ?> device) {
			String version = text(device.get("version"));
			String build = text(device.get("build"));
			boolean changed = false;
			if (version != null && !version.equals(_version)) {
				_version = version;
				changed = true;
			}
			if (build != null && !build.equals(_build)) {
				_build = build;
				changed = true;
			}
			if (changed) {
				ControllerConnections.firmwareChanged(this);
			}
		}
	}

	private void datapoints(Map<String, Object> message) {
		Object list = message.containsKey("updates") ? message.get("updates") : message.get("datapoints");
		if (list instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> map && map.get("id") != null) {
					@SuppressWarnings("unchecked")
					Map<String, Object> datapoint = (Map<String, Object>) map;
					_datapoints.merge(datapoint.get("id"), datapoint, (before, update) -> {
						Map<String, Object> merged = new LinkedHashMap<>(before);
						merged.putAll(update);
						return merged;
					});
				}
			}
		}
	}

	private void response(Map<String, Object> message, String text) {
		CompletableFuture<Answer> pending = _pending;
		if (pending != null && message.get("id") instanceof Number id && id.longValue() == _pendingId) {
			if (message.get("length") instanceof Number length && !message.containsKey("body")) {
				if (length.longValue() < 0 || length.longValue() > MAX_STREAMED_BODY) {
					pending.completeExceptionally(new IOException("The controller announced a response of "
						+ length + " bytes, more than E-Home takes (" + MAX_STREAMED_BODY + ")."));
					return;
				}
				_streamHead = message;
				_stream = new ByteArrayOutputStream();
				_streamLength = length.intValue();
				if (_streamLength == 0) {
					streamDone();
				}
				return;
			}
			try {
				pending.complete(answer(text));
			} catch (IOException ex) {
				pending.completeExceptionally(ex);
			}
		} else {
			Logger.warn("Controller " + _pathId + " answered a request E-Home does not wait for: " + message.get("id"),
				ControllerConnection.class);
		}
	}

	/**
	 * Sends a request to the controller's API and waits for its response (homecontrol
	 * <code>spec/api.md</code>, "The socket"). Requests are sent one at a time: a caller waits for
	 * the end of the requests before its own, up to the timeout.
	 *
	 * @param method
	 *        The HTTP method (<code>GET</code>, <code>PUT</code>, ...).
	 * @param path
	 *        The path with the query, e.g. <code>/api/zigbee/export?from=16</code>.
	 * @param body
	 *        The JSON body, <code>null</code> for none.
	 * @param timeout
	 *        How long to wait for the turn, and then for the response.
	 * @return The route's status and body.
	 * @throws IOException
	 *         If the connection is closed or closes meanwhile, or no response comes in time.
	 */
	public Answer request(String method, String path, byte[] body, Duration timeout) throws IOException {
		return request(method, path, body, false, timeout);
	}

	/**
	 * Sends a request as {@link #request(String, String, byte[], Duration)} does.
	 *
	 * @param binary
	 *        Whether to send the body as binary frames whatever its size: the bytes arrive as they are,
	 *        while a body inside the request message is a JSON value of the envelope (whitespace
	 *        around it belongs to the envelope). The runtime image goes so, since the controller
	 *        stores it byte for byte.
	 */
	public Answer request(String method, String path, byte[] body, boolean binary, Duration timeout)
			throws IOException {
		if (!isReady()) {
			throw new IOException("The controller is not connected.");
		}
		try {
			if (!_requests.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IOException("An earlier request to the controller did not finish within "
					+ timeout.toSeconds() + " s.");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the controller.", ex);
		}
		try {
			if (!isReady()) {
				throw new IOException("The connection to the controller was closed.");
			}
			long id = _ids.incrementAndGet();
			CompletableFuture<Answer> result = new CompletableFuture<>();
			_pendingId = id;
			_pending = result;
			sendRequest(id, method, path, body, binary);
			return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			throw new IOException("The controller did not answer within " + timeout.toSeconds() + " s.", ex);
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			throw new IOException(cause == null ? ex.getMessage() : cause.getMessage(), cause);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the controller.", ex);
		} finally {
			_pending = null;
			_stream = null;
			_streamHead = null;
			_requests.unlock();
		}
	}

	private void sendRequest(long id, String method, String path, byte[] body, boolean binary) throws IOException {
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("type", "request");
		header.put("id", Long.valueOf(id));
		header.put("method", method);
		header.put("path", path);
		String inline = null;
		if (body != null && !binary && body.length <= MAX_INLINE_BODY) {
			inline = new String(body, StandardCharsets.UTF_8);
			try {
				Json.parse(inline, MAX_NESTING);
			} catch (JsonException ex) {
				inline = null;
			}
		}
		synchronized (_sendLock) {
			if (body == null) {
				sendText(Json.compact(header));
			} else if (inline != null) {
				String head = Json.compact(header);
				sendText(head.substring(0, head.length() - 1) + ",\"body\":" + inline + "}");
			} else {
				header.put("length", Integer.valueOf(body.length));
				sendText(Json.compact(header));
				for (int offset = 0; offset < body.length; offset += BINARY_CHUNK) {
					int length = Math.min(BINARY_CHUNK, body.length - offset);
					byte[] chunk = new byte[length];
					System.arraycopy(body, offset, chunk, 0, length);
					_peer.sendBinary(chunk);
				}
			}
		}
	}

	private static Answer answer(String text) throws IOException {
		Map<String, Object> message;
		String bodyText;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> object = (Map<String, Object>) Json.parse(text, MAX_NESTING);
			message = object;
			bodyText = Json.member(text, "body", MAX_NESTING);
		} catch (JsonException ex) {
			throw new IOException("The controller's response is no JSON: " + ex.getMessage(), ex);
		}
		int status = message.get("status") instanceof Number number ? number.intValue() : 0;
		Map<String, Object> body = Collections.emptyMap();
		if (message.get("body") instanceof Map<?, ?> map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> object = (Map<String, Object>) map;
			body = object;
		}
		return new Answer(status, body, bodyText == null ? "" : bodyText);
	}

	/**
	 * Sends a message of the live feed protocol (<code>command</code>, <code>monitor</code>, ...).
	 */
	public void send(Map<String, Object> message) throws IOException {
		if (!isReady()) {
			throw new IOException("The controller is not connected.");
		}
		synchronized (_sendLock) {
			sendText(Json.compact(message));
		}
	}

	private void sendText(String text) throws IOException {
		synchronized (_sendLock) {
			_peer.sendText(text);
		}
	}

	/**
	 * Refuses the controller: <code>refused</code> with the code, and the close.
	 */
	void refuse(String code, String error) {
		if (_state == State.CLOSED) {
			return;
		}
		Map<String, Object> refused = new LinkedHashMap<>();
		refused.put("type", "refused");
		refused.put("code", code);
		refused.put("error", error);
		try {
			sendText(Json.compact(refused));
		} catch (IOException ex) {
			// Closed anyway.
		}
		Logger.info("Refused controller " + _pathId + " from " + _peer.remote() + ": " + code,
			ControllerConnection.class);
		close(CLOSE_POLICY, code);
	}

	/**
	 * Closes the connection with the given code and reason.
	 */
	public void close(int code, String reason) {
		_peer.close(code, reason);
		closed(reason);
	}

	/**
	 * The transport ended (the controller closed, the network failed, the server closed it).
	 */
	public void closed(String reason) {
		State before;
		synchronized (this) {
			before = _state;
			_state = State.CLOSED;
		}
		if (before == State.CLOSED) {
			return;
		}
		CompletableFuture<Answer> pending = _pending;
		if (pending != null) {
			pending.completeExceptionally(new IOException("The connection to the controller was closed ("
				+ (reason == null || reason.isBlank() ? "no reason" : reason) + ")."));
		}
		if (before == State.CHALLENGED) {
			ControllerConnections.dropped(this);
		}
		if (before == State.READY) {
			for (Listener listener : _listeners) {
				try {
					listener.closed(this, reason);
				} catch (RuntimeException ex) {
					Logger.error("A listener of controller " + _pathId + " failed.", ex, ControllerConnection.class);
				}
			}
			ControllerConnections.closed(this, reason);
		}
	}

	private static String text(Object value) {
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	@Override
	public String toString() {
		return "controller " + _pathId + " (" + _state + ", " + _peer.remote() + ")";
	}

}
