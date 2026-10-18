/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The outbound connection of a {@link FakeController} to E-Home, as a HomeControl controller makes it
 * (homecontrol <code>spec/api.md</code>, "E-Home connection", "The socket"): a WebSocket client (the
 * JDK's) that answers the <code>challenge</code> with <code>auth</code> (the HMAC of the nonce with the
 * secret, both as the bytes their hex digits encode), and after <code>ready</code> serves E-Home's
 * <code>request</code>s by passing them to the fake's own HTTP routes with its token.
 *
 * <p>
 * A request with <code>length</code> collects that many bytes of binary frames before it is passed on
 * ({@link #getBinaryFrames()} counts them, {@link #getRequests()} records each request with its body).
 * A response body above {@value #MAX_TEXT_BODY} bytes, and the stored image of <code>GET /api/image</code>
 * byte for byte, go back as <code>length</code> and binary frames, as the controller streams them. A
 * <code>monitor</code> subscription is answered with <code>subscribed</code> (the events are the test's,
 * {@link #send(Map)}). A request arriving before the previous one
 * was answered is refused with 429 <code>ehome.busy</code> and recorded ({@link #getOverlaps()}). A test
 * can delay the answers ({@link #setDelay(long)}), replace the HMAC of the next authentication
 * ({@link #setMac(String)}, e.g. a replayed one), and send feed messages ({@link #send(Map)}).
 * </p>
 */
public class FakeControllerLink implements WebSocket.Listener {

	/** The largest response body the link sends in a text message. */
	public static final int MAX_TEXT_BODY = 4096;

	/** The piece of a streamed response. */
	public static final int STREAM_PIECE = 2048;

	/**
	 * A request E-Home made.
	 *
	 * @param method
	 *        The method.
	 * @param path
	 *        The path with the query.
	 * @param body
	 *        The body, <code>null</code> for none.
	 * @param binary
	 *        Whether the body came as binary frames.
	 */
	public record Request(String method, String path, byte[] body, boolean binary) {
		// A record.
	}

	private final FakeController _fake;

	private final String _token;

	private final String _id;

	private final String _secret;

	private final HttpClient _http = HttpClient.newHttpClient();

	private final ExecutorService _executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "fake-controller-link");
		thread.setDaemon(true);
		return thread;
	});

	private final StringBuilder _text = new StringBuilder();

	private final ByteArrayOutputStream _frame = new ByteArrayOutputStream();

	private final CompletableFuture<Map<String, Object>> _outcome = new CompletableFuture<>();

	private final CompletableFuture<Integer> _closed = new CompletableFuture<>();

	private final List<Request> _requests = new ArrayList<>();

	private final List<Map<String, Object>> _received = new ArrayList<>();

	private WebSocket _socket;

	private String _mac;

	private String _lastMac;

	private long _delay;

	private boolean _busy;

	private int _overlaps;

	private int _binaryFrames;

	private Map<String, Object> _bodyRequest;

	private ByteArrayOutputStream _body;

	private int _bodyLength;

	/**
	 * Creates a {@link FakeControllerLink} of the given fake.
	 *
	 * @param token
	 *        The token the link passes requests to the fake's routes with.
	 * @param id
	 *        The controller's id at E-Home.
	 * @param secret
	 *        The controller's secret (64 hex digits).
	 */
	public FakeControllerLink(FakeController fake, String token, String id, String secret) {
		_fake = fake;
		_token = token;
		_id = id;
		_secret = secret;
	}

	/**
	 * Opens the WebSocket and waits for E-Home's verdict: the <code>ready</code> or
	 * <code>refused</code> message.
	 */
	public Map<String, Object> connect(URI socket) throws Exception {
		_socket = _http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(socket, this)
			.get(5, TimeUnit.SECONDS);
		return _outcome.get(10, TimeUnit.SECONDS);
	}

	/**
	 * Uses the given HMAC (hex) for the next authentication instead of the right one.
	 */
	public synchronized void setMac(String mac) {
		_mac = mac;
	}

	/**
	 * The HMAC the last authentication sent.
	 */
	public synchronized String getLastMac() {
		return _lastMac;
	}

	/**
	 * Delays every answer by the given milliseconds.
	 */
	public synchronized void setDelay(long millis) {
		_delay = millis;
	}

	/**
	 * The requests served so far.
	 */
	public synchronized List<Request> getRequests() {
		return new ArrayList<>(_requests);
	}

	/**
	 * The messages of E-Home after <code>ready</code> that are no requests (<code>subscribe</code>,
	 * <code>monitor</code>, ...).
	 */
	public synchronized List<Map<String, Object>> getReceived() {
		return new ArrayList<>(_received);
	}

	/**
	 * The requests that arrived while another one was in progress.
	 */
	public synchronized int getOverlaps() {
		return _overlaps;
	}

	/**
	 * The binary frames received.
	 */
	public synchronized int getBinaryFrames() {
		return _binaryFrames;
	}

	/**
	 * Sends a message of the feed (<code>status</code>, <code>zigbee</code>, <code>monitor</code>, ...).
	 */
	public void send(Map<String, Object> message) throws Exception {
		sendText(Json.compact(message));
	}

	/**
	 * Sends a text message as it is.
	 */
	public void sendText(String text) throws Exception {
		synchronized (this) {
			_socket.sendText(text, true).get(5, TimeUnit.SECONDS);
		}
	}

	/**
	 * Closes the WebSocket normally and waits for E-Home's close.
	 */
	public void close() throws Exception {
		_socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
		_closed.get(5, TimeUnit.SECONDS);
		_executor.shutdownNow();
	}

	/**
	 * Waits for E-Home to close the socket: the close code.
	 */
	public int awaitClose() throws Exception {
		return _closed.get(5, TimeUnit.SECONDS).intValue();
	}

	@Override
	public void onOpen(WebSocket webSocket) {
		webSocket.request(1);
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		_text.append(data);
		if (last) {
			String text = _text.toString();
			_text.setLength(0);
			try {
				handle(webSocket, text);
			} catch (Exception ex) {
				_outcome.completeExceptionally(ex);
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
		byte[] bytes = new byte[data.remaining()];
		data.get(bytes);
		_frame.write(bytes, 0, bytes.length);
		if (last) {
			byte[] frame = _frame.toByteArray();
			_frame.reset();
			synchronized (this) {
				_binaryFrames++;
				if (_body != null) {
					_body.write(frame, 0, frame.length);
					if (_body.size() >= _bodyLength) {
						Map<String, Object> request = _bodyRequest;
						byte[] body = _body.toByteArray();
						_body = null;
						_bodyRequest = null;
						serve(request, body, true);
					}
				}
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		_closed.complete(Integer.valueOf(statusCode));
		_outcome.completeExceptionally(new IOException("closed " + statusCode + " " + reason));
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		_closed.complete(Integer.valueOf(-1));
		_outcome.completeExceptionally(error);
	}

	@SuppressWarnings("unchecked")
	private void handle(WebSocket webSocket, String text) throws Exception {
		Map<String, Object> message = (Map<String, Object>) Json.parse(text, 32);
		String type = String.valueOf(message.get("type"));
		switch (type) {
			case "challenge": {
				String mac;
				synchronized (this) {
					mac = _mac != null ? _mac : mac(_secret, String.valueOf(message.get("nonce")));
					_mac = null;
					_lastMac = mac;
				}
				Map<String, Object> auth = new LinkedHashMap<>();
				auth.put("type", "auth");
				auth.put("controller", _id);
				auth.put("mac", mac);
				auth.put("version", "0.1.0-fake");
				auth.put("build", "fake123");
				webSocket.sendText(Json.compact(auth), true);
				return;
			}
			case "ready":
			case "refused":
				_outcome.complete(message);
				return;
			case "request":
				synchronized (this) {
					if (message.get("length") instanceof Number length) {
						_bodyRequest = message;
						_body = new ByteArrayOutputStream();
						_bodyLength = length.intValue();
						return;
					}
				}
				String body = Json.member(text, "body", 32);
				serve(message, body == null ? null : body.getBytes(StandardCharsets.UTF_8), false);
				return;
			default:
				synchronized (this) {
					_received.add(message);
				}
				if ("monitor".equals(type) && Boolean.TRUE.equals(message.get("subscribe"))) {
					Map<String, Object> answer = new LinkedHashMap<>();
					answer.put("type", "monitor");
					answer.put("subscribed", Boolean.TRUE);
					answer.put("since", Integer.valueOf(0));
					answer.put("first", Integer.valueOf(0));
					answer.put("next", Integer.valueOf(0));
					answer.put("capacity", Integer.valueOf(128));
					answer.put("ref", message.get("ref"));
					sendText(Json.compact(answer));
				}
		}
	}

	private synchronized void serve(Map<String, Object> request, byte[] body, boolean binary) {
		_requests.add(new Request(String.valueOf(request.get("method")), String.valueOf(request.get("path")), body,
			binary));
		Object id = request.get("id");
		if (_busy) {
			_overlaps++;
			respond(id, 429, "{\"error\":\"busy\",\"code\":\"ehome.busy\"}", false);
			return;
		}
		_busy = true;
		long delay = _delay;
		_executor.execute(() -> {
			try {
				if (delay > 0) {
					Thread.sleep(delay);
				}
				HttpRequest.Builder http = HttpRequest.newBuilder(
					URI.create("http://127.0.0.1:" + _fake.getPort() + request.get("path")))
					.header("Authorization", "Bearer " + _token)
					.method(String.valueOf(request.get("method")), body == null ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofByteArray(body));
				HttpResponse<String> response = _http.send(http.build(), HttpResponse.BodyHandlers.ofString());
				boolean stream = "GET".equals(request.get("method")) && "/api/image".equals(request.get("path"))
					&& response.statusCode() == 200;
				synchronized (FakeControllerLink.this) {
					_busy = false;
					respond(id, response.statusCode(), response.body(), stream);
				}
			} catch (Exception ex) {
				synchronized (FakeControllerLink.this) {
					_busy = false;
					respond(id, 500, "{\"error\":\"failed\",\"code\":\"fake.failed\"}", false);
				}
			}
		});
	}

	private void respond(Object id, int status, String body, boolean stream) {
		Map<String, Object> head = new LinkedHashMap<>();
		head.put("type", "response");
		head.put("id", id);
		head.put("status", Integer.valueOf(status));
		byte[] bytes = (stream ? body : body.strip()).getBytes(StandardCharsets.UTF_8);
		try {
			if (stream || bytes.length > MAX_TEXT_BODY) {
				head.put("length", Integer.valueOf(bytes.length));
				_socket.sendText(Json.compact(head), true).get(5, TimeUnit.SECONDS);
				for (int offset = 0; offset < bytes.length; offset += STREAM_PIECE) {
					int length = Math.min(STREAM_PIECE, bytes.length - offset);
					_socket.sendBinary(ByteBuffer.wrap(bytes, offset, length), true).get(5, TimeUnit.SECONDS);
				}
			} else {
				String text = Json.compact(head);
				String json = bytes.length == 0 ? "null" : body.strip();
				try {
					Json.parse(json, 32);
				} catch (JsonException ex) {
					json = "null";
				}
				_socket.sendText(text.substring(0, text.length() - 1) + ",\"body\":" + json + "}", true)
					.get(5, TimeUnit.SECONDS);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * <code>HMAC-SHA256</code> of the bytes the nonce's hex digits encode, keyed with the bytes of the
	 * secret's hex digits, in lower-case hex.
	 */
	public static String mac(String secret, String nonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(HexFormat.of().parseHex(nonce)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
