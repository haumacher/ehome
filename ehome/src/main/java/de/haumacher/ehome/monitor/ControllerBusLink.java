/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.top_logic.basic.Logger;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * {@link BusLink} through the monitor stream of a HomeControl controller (haumacher/ehome #41,
 * homecontrol <code>spec/api.md</code>, "WebSocket <code>/ws</code>" and "Monitor").
 *
 * <p>
 * The link asks the controller for the port of its WebSocket (<code>GET /api/auth/session</code>,
 * <code>ws_port</code>), connects to <code>ws://host:port/ws</code>, authenticates with the bearer
 * token in the first message (<code>{"type": "auth", "token": ...}</code>) and subscribes to the
 * monitor stream. The <code>knx</code> events become {@link BusTelegram}s, with the datapoint name
 * and value the controller reports; the other events ({@link LinkEvent.Kind}) are reported as
 * {@link LinkEvent}s. The time of an event is the time it was received minus its age on the
 * controller's clock (<code>now_ms - ms</code>).
 * </p>
 *
 * <p>
 * Sending goes through the controller (<code>send</code> with the raw data of the telegram,
 * <code>read</code>); the controller reports a sent telegram as <code>tx</code> event with
 * <code>origin: "monitor"</code> ({@link #reportsOwnTelegrams()}). A refusal of the controller
 * (<code>monitor.tunnel_down</code>, ...) is a {@link BusException} naming its code.
 * </p>
 *
 * <p>
 * When the stream ends (the controller restarts, the network fails, the connection is closed with
 * another code than 1008), the link reconnects, waiting {@link #RETRY_MILLIS} first and doubling
 * the wait up to {@link #MAX_RETRY_MILLIS}; it subscribes with the sequence number after the last
 * event it got (<code>since</code>), so the events in between are caught up (or a <code>gap</code>
 * reported). An answer with <code>reset: true</code> (the controller restarted) is reported as
 * {@link LinkEvent.Kind#RESET}. While it reconnects, {@link #problem()} says why. A close with 1008
 * (the token was refused or revoked) ends the link for good.
 * </p>
 */
public class ControllerBusLink implements BusLink {

	/** The close code of a refused authentication (policy violation). */
	public static final int CLOSE_POLICY = 1008;

	/** The first wait before reconnecting, in milliseconds. */
	public static final long RETRY_MILLIS = 1000;

	/** The longest wait before reconnecting, in milliseconds. */
	public static final long MAX_RETRY_MILLIS = 30000;

	/** The time between two keep-alive messages (the controller's UI sends one every 20 s). */
	public static final long PING_MILLIS = 20000;

	private static final int MAX_NESTING = 8;

	/**
	 * Thrown when the controller refuses the connection: its code (e.g.
	 * <code>auth.token_invalid</code>) and reason.
	 */
	static final class Refused extends Exception {

		private final String _code;

		Refused(String code, String reason) {
			super(reason == null || reason.isBlank() ? code : reason + " (" + code + ")");
			_code = code;
		}

		String code() {
			return _code;
		}
	}

	private final String _name;

	private final ControllerClient _client;

	private final long _retryMillis;

	private final long _maxRetryMillis;

	private final HttpClient _http;

	private final ScheduledExecutorService _executor;

	private final Object _lock = new Object();

	private final Map<Long, CompletableFuture<Map<String, Object>>> _results = new ConcurrentHashMap<>();

	private final AtomicLong _refs = new AtomicLong();

	private final List<Object> _buffer = new ArrayList<>();

	private Listener _listener;

	private volatile Connection _connection;

	private volatile String _problem;

	private volatile boolean _closing;

	private volatile int _localAddress;

	private volatile long _next = -1;

	private long _retry;

	private ScheduledFuture<?> _ping;

	/**
	 * Connects to the monitor stream of the given controller.
	 *
	 * @param name
	 *        The name of the controller, for a person; <code>null</code> for one entered ad hoc.
	 * @param client
	 *        The controller's address and token.
	 * @throws BusException
	 *         If the controller cannot be reached or refuses the token.
	 */
	public static ControllerBusLink open(String name, ControllerClient client) throws BusException {
		return open(name, client, RETRY_MILLIS, MAX_RETRY_MILLIS, PING_MILLIS);
	}

	/**
	 * Connects to the monitor stream of the given controller, with the given times of the
	 * reconnection and the keep-alive.
	 *
	 * @see #open(String, ControllerClient)
	 */
	public static ControllerBusLink open(String name, ControllerClient client, long retryMillis, long maxRetryMillis,
			long pingMillis) throws BusException {
		ControllerBusLink result = new ControllerBusLink(name, client, retryMillis, maxRetryMillis);
		try {
			result.connect();
		} catch (Refused ex) {
			result.close();
			throw new BusException("The controller " + result.description() + " refused the connection: "
				+ ex.getMessage(), ex);
		} catch (IOException ex) {
			result.close();
			throw new BusException("No connection to the controller " + result.description() + ": " + ex.getMessage(),
				ex);
		}
		if (pingMillis > 0) {
			result._ping = result._executor.scheduleWithFixedDelay(result::ping, pingMillis, pingMillis,
				TimeUnit.MILLISECONDS);
		}
		return result;
	}

	private ControllerBusLink(String name, ControllerClient client, long retryMillis, long maxRetryMillis) {
		_name = name;
		_client = client;
		_retryMillis = retryMillis;
		_maxRetryMillis = maxRetryMillis;
		_retry = retryMillis;
		_http = HttpClient.newBuilder().connectTimeout(client.timeout()).build();
		_executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "controller-monitor " + client.address());
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * The text {@link #description()} gives for a controller: its name and address, only its address
	 * for a controller without name (one entered ad hoc).
	 */
	public static String description(String name, ControllerClient client) {
		return name == null ? client.address() : name + " (" + client.address() + ")";
	}

	@Override
	public String description() {
		return description(_name, _client);
	}

	@Override
	public void setListener(Listener listener) {
		List<Object> buffered;
		synchronized (_lock) {
			_listener = listener;
			if (listener == null) {
				return;
			}
			buffered = new ArrayList<>(_buffer);
			_buffer.clear();
		}
		for (Object item : buffered) {
			deliver(listener, item);
		}
	}

	@Override
	public int localAddress() {
		return _localAddress;
	}

	@Override
	public String problem() {
		return _problem;
	}

	@Override
	public boolean viaController() {
		return true;
	}

	@Override
	public boolean reportsOwnTelegrams() {
		return true;
	}

	/**
	 * The sequence number of the next event the link expects; -1 before the controller answered the
	 * subscription.
	 */
	public long getNext() {
		return _next;
	}

	@Override
	public void send(int destination, byte[] apdu) throws BusException {
		Connection connection = _connection;
		if (connection == null || _problem != null) {
			throw new BusException("The connection to the controller " + description() + " is interrupted.");
		}
		BusTelegram.Service service =
			BusTelegram.Service.ofCode(tuwien.auto.calimero.DataUnitBuilder.getAPDUService(apdu));
		Map<String, Object> knx = new LinkedHashMap<>();
		knx.put("address", groupAddress(destination));
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "monitor");
		if (service == BusTelegram.Service.READ) {
			message.put("read", Map.of("knx", knx));
		} else {
			byte[] data = DatapointCodec.data(apdu);
			knx.put("data", HexFormat.ofDelimiter(" ").withUpperCase().formatHex(data));
			if (apdu.length <= 2) {
				knx.put("short", Boolean.TRUE);
			}
			if (service == BusTelegram.Service.RESPONSE) {
				knx.put("service", "response");
			}
			message.put("send", Map.of("knx", knx));
		}
		long ref = _refs.incrementAndGet();
		message.put("ref", Long.valueOf(ref));
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		_results.put(Long.valueOf(ref), result);
		try {
			connection.send(Json.write(message));
			Map<String, Object> answer = result.get(_client.timeout().toMillis(), TimeUnit.MILLISECONDS);
			if (!Boolean.TRUE.equals(answer.get("ok"))) {
				throw new BusException("The controller refused the telegram to " + groupAddress(destination) + ": "
					+ answer.get("error") + " (" + answer.get("code") + ")");
			}
		} catch (IOException | ExecutionException ex) {
			throw new BusException("Sending through the controller " + description() + " failed: " + ex.getMessage(), ex);
		} catch (TimeoutException ex) {
			throw new BusException("The controller " + description() + " did not answer the telegram.", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BusException("Interrupted while sending through the controller.", ex);
		} finally {
			_results.remove(Long.valueOf(ref));
		}
	}

	@Override
	public void close() {
		_closing = true;
		synchronized (_lock) {
			_listener = null;
			_buffer.clear();
		}
		_executor.shutdownNow();
		Connection connection = _connection;
		_connection = null;
		if (connection != null) {
			connection.close();
		}
	}

	/**
	 * Connects, authenticates and subscribes; on success the connection is the current one.
	 */
	private void connect() throws IOException, Refused {
		int port = _client.port();
		ControllerClient.Answer session = _client.session();
		if (session.ok() && session.body().get("ws_port") instanceof Number wsPort && wsPort.intValue() > 0) {
			port = wsPort.intValue();
		}
		URI uri;
		try {
			String host = _client.host().contains(":") ? "[" + _client.host() + "]" : _client.host();
			uri = new URI("ws://" + host + ":" + port + "/ws");
		} catch (URISyntaxException ex) {
			throw new IOException("Invalid controller address " + _client.address() + ": " + ex.getMessage(), ex);
		}
		Connection connection = new Connection();
		long timeout = _client.timeout().toMillis();
		try {
			connection._socket = _http.newWebSocketBuilder().connectTimeout(_client.timeout())
				.buildAsync(uri, connection).get(timeout, TimeUnit.MILLISECONDS);
			Map<String, Object> answer = connection._subscribed.get(timeout, TimeUnit.MILLISECONDS);
			_connection = connection;
			if (answer.get("next") instanceof Number next && _next < 0) {
				_next = next.longValue();
			}
			if (Boolean.TRUE.equals(answer.get("reset"))) {
				emit(new LinkEvent(System.currentTimeMillis(), LinkEvent.Kind.RESET, Map.of(), null, null));
			}
			connection.flush();
		} catch (ExecutionException ex) {
			connection.close();
			if (ex.getCause() instanceof Refused refused) {
				throw refused;
			}
			throw new IOException(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage(), ex);
		} catch (TimeoutException ex) {
			connection.close();
			throw new IOException("The controller did not answer within " + timeout + " ms.", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			connection.close();
			throw new IOException("Interrupted while connecting.", ex);
		}
	}

	private void ping() {
		Connection connection = _connection;
		if (connection != null && _problem == null) {
			try {
				connection.send("{\"type\":\"ping\"}");
			} catch (IOException ex) {
				// The close of the connection reports it.
			}
		}
	}

	/**
	 * The connection ended unexpectedly.
	 */
	private void lost(Connection connection, int code, String reason) {
		if (_closing || _connection != connection) {
			return;
		}
		_connection = null;
		if (code == CLOSE_POLICY) {
			ended("The controller ended the connection: " + reason);
			return;
		}
		_problem = "The connection to the controller was interrupted (" + (reason == null || reason.isBlank()
			? "close code " + code : reason) + "); reconnecting.";
		_retry = _retryMillis;
		notifyState();
		scheduleReconnect();
	}

	private void ended(String reason) {
		_closing = true;
		_problem = null;
		Listener listener;
		synchronized (_lock) {
			listener = _listener;
		}
		if (listener != null) {
			listener.closed(reason);
		}
		_executor.shutdown();
	}

	private void scheduleReconnect() {
		if (_closing) {
			return;
		}
		try {
			_executor.schedule(this::reconnect, _retry, TimeUnit.MILLISECONDS);
		} catch (RuntimeException ex) {
			// Closed meanwhile.
		}
	}

	private void reconnect() {
		if (_closing) {
			return;
		}
		try {
			connect();
			_problem = null;
			_retry = _retryMillis;
			notifyState();
		} catch (Refused ex) {
			ended("The controller refused the connection: " + ex.getMessage());
		} catch (IOException ex) {
			_problem = "The controller cannot be reached (" + ex.getMessage() + "); next attempt in "
				+ (_retry / 1000.0) + " s.";
			_retry = Math.min(_maxRetryMillis, _retry * 2);
			notifyState();
			scheduleReconnect();
		}
	}

	private void notifyState() {
		Listener listener;
		synchronized (_lock) {
			listener = _listener;
		}
		if (listener != null) {
			listener.stateChanged();
		}
	}

	/**
	 * Takes an event batch of the controller.
	 */
	private void events(Map<String, Object> batch, long received) {
		long now = batch.get("now_ms") instanceof Number number ? number.longValue() : -1;
		if (batch.get("events") instanceof List<?> events) {
			for (Object event : events) {
				if (event instanceof Map<?, ?> map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> fields = (Map<String, Object>) map;
					long time = received;
					if (now >= 0 && fields.get("ms") instanceof Number ms) {
						time = received - Math.max(0, now - ms.longValue());
					}
					try {
						Object item = convert(fields, time);
						if (item != null) {
							emit(item);
						}
					} catch (RuntimeException ex) {
						Logger.warn("Ignoring a monitor event the controller sent: " + Json.write(fields), ex,
							ControllerBusLink.class);
					}
				}
			}
		}
		if (batch.get("next") instanceof Number next) {
			_next = next.longValue();
		}
	}

	/**
	 * The {@link BusTelegram} or {@link LinkEvent} of an event, <code>null</code> to ignore it.
	 */
	private Object convert(Map<String, Object> event, long time) {
		String kind = String.valueOf(event.get("kind"));
		String name = event.get("name") instanceof String text && !text.isBlank() ? text : null;
		Object value = event.get("value");
		if (!"knx".equals(kind)) {
			return new LinkEvent(time, LinkEvent.Kind.of(kind), event, name, value);
		}
		BusTelegram.Service service = service(String.valueOf(event.get("service")));
		if (service == null) {
			return null;
		}
		int source = individualAddress(String.valueOf(event.get("src")));
		boolean tx = "tx".equals(event.get("dir"));
		if (tx && source != 0) {
			_localAddress = source;
		}
		byte[] data = service == BusTelegram.Service.READ ? new byte[0] : hex(event.get("data"));
		int priority = event.get("prio") instanceof Number prio ? prio.intValue() : BusTelegram.PRIORITY_LOW;
		int hops = event.get("hops") instanceof Number number ? number.intValue() : BusTelegram.DEFAULT_HOP_COUNT;
		boolean own = tx && "monitor".equals(event.get("origin"));
		return new BusTelegram(time, source, groupAddress(String.valueOf(event.get("dst"))), service, priority,
			Boolean.TRUE.equals(event.get("repeated")), hops, data, own, name, value);
	}

	private void emit(Object item) {
		Listener listener;
		synchronized (_lock) {
			listener = _listener;
			if (listener == null) {
				if (!_closing) {
					_buffer.add(item);
				}
				return;
			}
		}
		deliver(listener, item);
	}

	private static void deliver(Listener listener, Object item) {
		if (item instanceof BusTelegram telegram) {
			listener.received(telegram);
		} else {
			listener.event((LinkEvent) item);
		}
	}

	private static BusTelegram.Service service(String name) {
		for (BusTelegram.Service service : BusTelegram.Service.values()) {
			if (service.classifier().equals(name)) {
				return service;
			}
		}
		return null;
	}

	/**
	 * The 16-bit individual address of its text area.line.device.
	 */
	static int individualAddress(String text) {
		String[] parts = text.split("\\.");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Invalid individual address: " + text);
		}
		return (Integer.parseInt(parts[0]) << 12) | (Integer.parseInt(parts[1]) << 8) | Integer.parseInt(parts[2]);
	}

	/**
	 * The 16-bit group address of its text in three levels (main/middle/sub), two levels (main/sub)
	 * or as a number.
	 */
	static int groupAddress(String text) {
		String[] parts = text.split("/");
		switch (parts.length) {
			case 3:
				return (Integer.parseInt(parts[0]) << 11) | (Integer.parseInt(parts[1]) << 8)
					| Integer.parseInt(parts[2]);
			case 2:
				return (Integer.parseInt(parts[0]) << 11) | Integer.parseInt(parts[1]);
			case 1:
				return Integer.parseInt(parts[0]);
			default:
				throw new IllegalArgumentException("Invalid group address: " + text);
		}
	}

	/**
	 * A group address in three levels, as the controller writes it.
	 */
	static String groupAddress(int address) {
		return ((address >>> 11) & 0x1F) + "/" + ((address >>> 8) & 0x07) + "/" + (address & 0xFF);
	}

	private static byte[] hex(Object text) {
		if (text == null) {
			return new byte[0];
		}
		return HexFormat.of().parseHex(text.toString().replaceAll("\\s", ""));
	}

	/**
	 * One WebSocket connection to the controller: the authentication, the subscription and then the
	 * stream.
	 */
	private final class Connection implements WebSocket.Listener {

		volatile WebSocket _socket;

		final CompletableFuture<Map<String, Object>> _subscribed = new CompletableFuture<>();

		private final StringBuilder _text = new StringBuilder();

		private final Object _sendLock = new Object();

		/** Batches received before the connection became the current one. */
		private final List<Object[]> _early = new ArrayList<>();

		private boolean _current;

		@Override
		public void onOpen(WebSocket webSocket) {
			_socket = webSocket;
			webSocket.request(1);
			Map<String, Object> auth = new LinkedHashMap<>();
			auth.put("type", "auth");
			auth.put("token", _client.token() == null ? "" : _client.token());
			webSocket.sendText(Json.write(auth), true);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			_text.append(data);
			if (last) {
				String text = _text.toString();
				_text.setLength(0);
				try {
					handle(text);
				} catch (RuntimeException ex) {
					Logger.error("Handling a message of the controller failed: " + text, ex, ControllerBusLink.class);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			if (!_subscribed.isDone()) {
				_subscribed.completeExceptionally(statusCode == CLOSE_POLICY ? new Refused(reason, null)
					: new IOException("The controller closed the connection (" + statusCode + " " + reason + ")."));
			} else {
				lost(this, statusCode, reason);
			}
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (!_subscribed.isDone()) {
				_subscribed.completeExceptionally(error);
			} else {
				lost(this, -1, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
			}
		}

		private void handle(String text) {
			Map<String, Object> message;
			try {
				if (!(Json.parse(text, MAX_NESTING) instanceof Map<?, ?> map)) {
					return;
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> object = (Map<String, Object>) map;
				message = object;
			} catch (JsonException ex) {
				Logger.warn("The controller sent no JSON: " + text, ControllerBusLink.class);
				return;
			}
			String type = String.valueOf(message.get("type"));
			switch (type) {
				case "auth":
					if (Boolean.TRUE.equals(message.get("ok"))) {
						subscribe();
					} else {
						_subscribed.completeExceptionally(new Refused(String.valueOf(message.get("code")),
							(String) message.get("error")));
					}
					break;
				case "error":
					if (!_subscribed.isDone()) {
						_subscribed.completeExceptionally(new Refused(String.valueOf(message.get("code")),
							(String) message.get("error")));
					} else {
						Logger.warn("The controller reported an error: " + text, ControllerBusLink.class);
					}
					break;
				case "monitor":
					monitor(message);
					break;
				default:
					// hello, pong, status, ...
			}
		}

		private void subscribe() {
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("type", "monitor");
			message.put("subscribe", Boolean.TRUE);
			long next = _next;
			if (next >= 0) {
				message.put("since", Long.valueOf(next));
			}
			_socket.sendText(Json.write(message), true);
		}

		private void monitor(Map<String, Object> message) {
			if (message.containsKey("subscribed")) {
				if (Boolean.TRUE.equals(message.get("subscribed"))) {
					_subscribed.complete(message);
				}
				return;
			}
			if (message.containsKey("result")) {
				Object ref = message.get("ref");
				CompletableFuture<Map<String, Object>> result =
					ref instanceof Number number ? _results.get(Long.valueOf(number.longValue())) : null;
				if (result != null) {
					result.complete(message);
				}
				return;
			}
			long received = System.currentTimeMillis();
			synchronized (this) {
				if (!_current) {
					_early.add(new Object[] { message, Long.valueOf(received) });
					return;
				}
			}
			events(message, received);
		}

		/**
		 * Makes the connection the current one: delivers the batches received before.
		 */
		void flush() {
			List<Object[]> early;
			synchronized (this) {
				_current = true;
				early = new ArrayList<>(_early);
				_early.clear();
			}
			for (Object[] batch : early) {
				@SuppressWarnings("unchecked")
				Map<String, Object> message = (Map<String, Object>) batch[0];
				events(message, ((Long) batch[1]).longValue());
			}
		}

		/**
		 * Sends a message, waiting until the previous one is sent (a {@link WebSocket} takes one at a
		 * time).
		 */
		void send(String text) throws IOException {
			WebSocket socket = _socket;
			if (socket == null || socket.isOutputClosed()) {
				throw new IOException("The connection is closed.");
			}
			try {
				synchronized (_sendLock) {
					socket.sendText(text, true).get(_client.timeout().toMillis(), TimeUnit.MILLISECONDS);
				}
			} catch (ExecutionException | TimeoutException ex) {
				throw new IOException("Sending to the controller failed: " + ex.getMessage(), ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while sending.", ex);
			}
		}

		void close() {
			WebSocket socket = _socket;
			if (socket != null) {
				socket.abort();
			}
		}
	}

}
