/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The WebSocket <code>/ws</code> of a {@link FakeController} with the monitor stream (homecontrol
 * <code>spec/api.md</code>, "WebSocket <code>/ws</code>" and "Monitor"), as the POSIX build of the
 * controller serves it: on a port of its own (<code>ws_port</code>).
 *
 * <p>
 * A minimal WebSocket server (RFC 6455: the handshake, unfragmented text frames, ping and close)
 * speaking the protocol: <code>hello</code> on connect, the authentication by the token in the first
 * message (a wrong token: an <code>error</code> message and the close code 1008 with
 * <code>auth.token_invalid</code>), the monitor subscription with <code>since</code> (a sequence
 * beyond the newest event: <code>reset: true</code>; one that was overwritten: a <code>gap</code>
 * marker), the event batches, <code>send</code> and <code>read</code> (answered with a
 * <code>result</code>, followed by the <code>tx</code> event marked <code>origin: "monitor"</code>)
 * and <code>ping</code>. A test scripts the events ({@link #emit(Map)}), drops the connections
 * ({@link #dropConnections()}), restarts the controller ({@link #restart()}) or revokes the token
 * ({@link #revoke()}).
 * </p>
 */
public class FakeMonitorStream {

	/** The events the controller holds. */
	public static final int CAPACITY = 128;

	/** The individual address of the controller's tunnel. */
	public static final String TUNNEL_ADDRESS = "1.1.250";

	private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private final ServerSocket _server;

	private final String _token;

	private final List<Map<String, Object>> _ring = new ArrayList<>();

	private final List<Client> _clients = new ArrayList<>();

	private final List<Map<String, Object>> _requests = new ArrayList<>();

	private final long _start = System.currentTimeMillis();

	private long _seq;

	private int _connections;

	private String _refuseNext;

	private volatile boolean _stopped;

	/**
	 * Creates and starts a {@link FakeMonitorStream} on a free port of <code>127.0.0.1</code>.
	 */
	public FakeMonitorStream(String token) throws IOException {
		_token = token;
		_server = new ServerSocket();
		_server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
		Thread accept = new Thread(this::accept, "fake controller ws");
		accept.setDaemon(true);
		accept.start();
	}

	/**
	 * The port of the WebSocket.
	 */
	public int getPort() {
		return _server.getLocalPort();
	}

	/**
	 * Stops it.
	 */
	public void stop() {
		_stopped = true;
		try {
			_server.close();
		} catch (IOException ex) {
			// Closed anyway.
		}
		dropConnections();
	}

	/**
	 * The number of WebSocket connections accepted so far.
	 */
	public synchronized int getConnections() {
		return _connections;
	}

	/**
	 * The number of connections that subscribed the monitor stream.
	 */
	public synchronized int getSubscribers() {
		return (int) _clients.stream().filter(c -> c._position >= 0).count();
	}

	/**
	 * The <code>send</code> and <code>read</code> requests received, as sent.
	 */
	public synchronized List<Map<String, Object>> getRequests() {
		return new ArrayList<>(_requests);
	}

	/**
	 * Refuses the next <code>send</code> or <code>read</code> with the given code (e.g.
	 * <code>monitor.tunnel_down</code>).
	 */
	public synchronized void refuseNext(String code) {
		_refuseNext = code;
	}

	/**
	 * The sequence number the next event gets.
	 */
	public synchronized long getNext() {
		return _seq;
	}

	/**
	 * Adds an event (the members besides <code>seq</code> and <code>ms</code>, which it gets) and sends
	 * it to the subscribers.
	 */
	public synchronized void emit(Map<String, Object> event) {
		Map<String, Object> stored = new LinkedHashMap<>();
		stored.put("seq", Long.valueOf(_seq++));
		stored.put("ms", Long.valueOf(System.currentTimeMillis() - _start));
		stored.putAll(event);
		_ring.add(stored);
		while (_ring.size() > CAPACITY) {
			_ring.remove(0);
		}
		for (Client client : new ArrayList<>(_clients)) {
			client.push();
		}
	}

	/**
	 * A <code>knx</code> event received from the bus.
	 *
	 * @param name
	 *        The datapoint name, <code>null</code> for an address no datapoint maps.
	 * @param value
	 *        The datapoint value, <code>null</code> for none.
	 */
	public static Map<String, Object> knx(String src, String dst, String service, String data, boolean shortForm,
			String name, Object value) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("kind", "knx");
		event.put("dir", "rx");
		event.put("src", src);
		event.put("dst", dst);
		event.put("service", service);
		event.put("data", data);
		if (shortForm) {
			event.put("short", Boolean.TRUE);
		}
		event.put("prio", Integer.valueOf(3));
		event.put("hops", Integer.valueOf(6));
		if (name != null) {
			event.put("dp", Integer.valueOf(4));
			event.put("name", name);
		}
		if (value != null) {
			event.put("value", value);
		}
		return event;
	}

	/**
	 * Closes all connections abruptly, as a failing network does.
	 */
	public void dropConnections() {
		List<Client> clients;
		synchronized (this) {
			clients = new ArrayList<>(_clients);
			_clients.clear();
		}
		for (Client client : clients) {
			client.kill();
		}
	}

	/**
	 * Restarts the controller: the connections drop, the events are gone, the sequence starts at 0.
	 */
	public void restart() {
		synchronized (this) {
			_ring.clear();
			_seq = 0;
		}
		dropConnections();
	}

	/**
	 * Revokes the token: the connections are closed with 1008 <code>auth.session_ended</code>, and
	 * the token is refused from now on.
	 */
	public void revoke() {
		List<Client> clients;
		synchronized (this) {
			_revoked = true;
			clients = new ArrayList<>(_clients);
			_clients.clear();
		}
		for (Client client : clients) {
			client.refuse("auth.session_ended", "the token was revoked");
		}
	}

	private boolean _revoked;

	private void accept() {
		while (!_stopped) {
			try {
				Socket socket = _server.accept();
				Client client = new Client(socket);
				synchronized (this) {
					_connections++;
					_clients.add(client);
				}
				Thread thread = new Thread(client::run, "fake controller ws client");
				thread.setDaemon(true);
				thread.start();
			} catch (IOException ex) {
				return;
			}
		}
	}

	private final class Client {

		private final Socket _socket;

		private OutputStream _out;

		private boolean _authenticated;

		/** The sequence of the next event to send; -1 while not subscribed. */
		long _position = -1;

		Client(Socket socket) {
			_socket = socket;
		}

		void run() {
			try {
				InputStream in = _socket.getInputStream();
				_out = _socket.getOutputStream();
				if (!handshake(in)) {
					kill();
					return;
				}
				sendJson(Map.of("type", "hello", "authenticated", Boolean.FALSE, "onboarded", Boolean.TRUE,
					"device", "homecontrol-fake", "version", "0.0.0-fake"));
				while (true) {
					int b0 = in.read();
					if (b0 < 0) {
						break;
					}
					int opcode = b0 & 0x0F;
					byte[] payload = payload(in);
					if (opcode == 8) {
						sendFrame(8, new byte[0]);
						break;
					}
					if (opcode == 9) {
						sendFrame(10, payload);
						continue;
					}
					if (opcode == 1) {
						message(new String(payload, StandardCharsets.UTF_8));
					}
				}
			} catch (IOException ex) {
				// Connection gone.
			} finally {
				synchronized (FakeMonitorStream.this) {
					_clients.remove(this);
				}
				kill();
			}
		}

		private boolean handshake(InputStream in) throws IOException {
			ByteArrayOutputStream header = new ByteArrayOutputStream();
			while (!header.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
				int c = in.read();
				if (c < 0) {
					return false;
				}
				header.write(c);
			}
			String key = null;
			for (String line : header.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
				int colon = line.indexOf(':');
				if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("sec-websocket-key")) {
					key = line.substring(colon + 1).trim();
				}
			}
			if (key == null) {
				return false;
			}
			String accept;
			try {
				accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
					.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
			} catch (NoSuchAlgorithmException ex) {
				throw new IOException(ex);
			}
			synchronized (this) {
				_out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
					+ "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				_out.flush();
			}
			return true;
		}

		private byte[] payload(InputStream in) throws IOException {
			int b1 = readByte(in);
			long length = b1 & 0x7F;
			if (length == 126) {
				length = (readByte(in) << 8) | readByte(in);
			} else if (length == 127) {
				length = 0;
				for (int n = 0; n < 8; n++) {
					length = (length << 8) | readByte(in);
				}
			}
			byte[] mask = new byte[4];
			boolean masked = (b1 & 0x80) != 0;
			if (masked) {
				readFully(in, mask);
			}
			byte[] payload = new byte[(int) length];
			readFully(in, payload);
			if (masked) {
				for (int n = 0; n < payload.length; n++) {
					payload[n] ^= mask[n % 4];
				}
			}
			return payload;
		}

		private void message(String text) throws IOException {
			Map<?, ?> message;
			try {
				message = (Map<?, ?>) Json.parse(text, 8);
			} catch (JsonException | ClassCastException ex) {
				sendJson(Map.of("type", "error", "code", "ws.bad_message", "error", "not JSON"));
				return;
			}
			String type = String.valueOf(message.get("type"));
			if (!_authenticated) {
				if (!"auth".equals(type)) {
					refuse("auth.required", "authenticate first");
					return;
				}
				boolean revoked;
				synchronized (FakeMonitorStream.this) {
					revoked = _revoked;
				}
				if (revoked || !_token.equals(message.get("token"))) {
					refuse("auth.token_invalid", "unknown or revoked token");
					return;
				}
				_authenticated = true;
				sendJson(Map.of("type", "auth", "ok", Boolean.TRUE));
				return;
			}
			switch (type) {
				case "ping":
					sendJson(Map.of("type", "pong"));
					break;
				case "monitor":
					monitor(message);
					break;
				default:
					sendJson(Map.of("type", "error", "code", "ws.unknown_type", "error", "unknown type"));
			}
		}

		private void monitor(Map<?, ?> message) throws IOException {
			if (message.containsKey("subscribe")) {
				Map<String, Object> answer = new LinkedHashMap<>();
				answer.put("type", "monitor");
				synchronized (FakeMonitorStream.this) {
					if (Boolean.TRUE.equals(message.get("subscribe"))) {
						long first = _ring.isEmpty() ? _seq : ((Number) _ring.get(0).get("seq")).longValue();
						boolean reset = false;
						long since = _seq;
						if (message.get("since") instanceof Number number) {
							since = number.longValue();
							if (since > _seq) {
								since = first;
								reset = true;
							}
						}
						answer.put("subscribed", Boolean.TRUE);
						answer.put("since", Long.valueOf(since));
						answer.put("first", Long.valueOf(first));
						answer.put("next", Long.valueOf(_seq));
						answer.put("capacity", Integer.valueOf(CAPACITY));
						if (reset) {
							answer.put("reset", Boolean.TRUE);
						}
						sendJson(answer);
						_position = since;
						push();
					} else {
						_position = -1;
						answer.put("subscribed", Boolean.FALSE);
						sendJson(answer);
					}
				}
				return;
			}
			String result = message.containsKey("send") ? "send" : message.containsKey("read") ? "read" : null;
			if (result == null) {
				sendJson(Map.of("type", "error", "code", "ws.bad_message", "error", "no subscribe, send or read"));
				return;
			}
			Map<?, ?> knx = (Map<?, ?>) ((Map<?, ?>) message.get(result)).get("knx");
			Map<String, Object> answer = new LinkedHashMap<>();
			answer.put("type", "monitor");
			answer.put("result", result);
			if (message.get("ref") != null) {
				answer.put("ref", message.get("ref"));
			}
			String refusal;
			synchronized (FakeMonitorStream.this) {
				@SuppressWarnings("unchecked")
				Map<String, Object> request = (Map<String, Object>) message;
				_requests.add(request);
				refusal = _refuseNext;
				_refuseNext = null;
			}
			if (refusal != null) {
				answer.put("ok", Boolean.FALSE);
				answer.put("code", refusal);
				answer.put("error", "refused by the test");
				sendJson(answer);
				return;
			}
			answer.put("ok", Boolean.TRUE);
			sendJson(answer);
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("kind", "knx");
			event.put("dir", "tx");
			event.put("src", TUNNEL_ADDRESS);
			event.put("dst", knx.get("address"));
			event.put("service", "read".equals(result) ? "read" : knx.get("service") == null ? "write" : knx.get("service"));
			event.put("data", "read".equals(result) ? "" : knx.get("data"));
			if (Boolean.TRUE.equals(knx.get("short"))) {
				event.put("short", Boolean.TRUE);
			}
			event.put("prio", Integer.valueOf(3));
			event.put("hops", Integer.valueOf(6));
			event.put("origin", "monitor");
			emit(event);
		}

		/** Called while holding the lock of the stream. */
		void push() {
			if (_position < 0 || _ring.isEmpty()) {
				return;
			}
			List<Map<String, Object>> events = new ArrayList<>();
			long first = ((Number) _ring.get(0).get("seq")).longValue();
			if (_position < first) {
				Map<String, Object> gap = new LinkedHashMap<>();
				gap.put("kind", "gap");
				gap.put("seq", Long.valueOf(_position));
				gap.put("count", Long.valueOf(first - _position));
				events.add(gap);
				_position = first;
			}
			for (Map<String, Object> event : _ring) {
				if (((Number) event.get("seq")).longValue() >= _position) {
					events.add(event);
				}
			}
			if (events.isEmpty()) {
				return;
			}
			_position = _seq;
			Map<String, Object> batch = new LinkedHashMap<>();
			batch.put("type", "monitor");
			batch.put("now_ms", Long.valueOf(System.currentTimeMillis() - _start));
			batch.put("events", events);
			batch.put("next", Long.valueOf(_seq));
			try {
				sendJson(batch);
			} catch (IOException ex) {
				kill();
			}
		}

		void refuse(String code, String reason) {
			try {
				sendJson(Map.of("type", "error", "code", code, "error", reason));
				byte[] text = code.getBytes(StandardCharsets.UTF_8);
				byte[] payload = new byte[text.length + 2];
				payload[0] = (byte) (1008 >>> 8);
				payload[1] = (byte) 1008;
				System.arraycopy(text, 0, payload, 2, text.length);
				sendFrame(8, payload);
			} catch (IOException ex) {
				// Gone anyway.
			}
			// Let the client read the close frame before the connection ends.
			Thread closer = new Thread(() -> {
				try {
					Thread.sleep(200);
				} catch (InterruptedException ex) {
					// Close now.
				}
				kill();
			});
			closer.setDaemon(true);
			closer.start();
		}

		private void sendJson(Map<String, Object> message) throws IOException {
			sendFrame(1, Json.write(message).getBytes(StandardCharsets.UTF_8));
		}

		private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
			ByteArrayOutputStream frame = new ByteArrayOutputStream();
			frame.write(0x80 | opcode);
			if (payload.length < 126) {
				frame.write(payload.length);
			} else if (payload.length < 65536) {
				frame.write(126);
				frame.write(payload.length >>> 8);
				frame.write(payload.length);
			} else {
				frame.write(127);
				for (int n = 7; n >= 0; n--) {
					frame.write((int) ((long) payload.length >>> (8 * n)));
				}
			}
			frame.write(payload);
			_out.write(frame.toByteArray());
			_out.flush();
		}

		void kill() {
			try {
				_socket.close();
			} catch (IOException ex) {
				// Closed anyway.
			}
		}
	}

	private static int readByte(InputStream in) throws IOException {
		int result = in.read();
		if (result < 0) {
			throw new EOFException();
		}
		return result;
	}

	private static void readFully(InputStream in, byte[] buffer) throws IOException {
		int done = 0;
		while (done < buffer.length) {
			int count = in.read(buffer, done, buffer.length - done);
			if (count < 0) {
				throw new EOFException();
			}
			done += count;
		}
	}

}
