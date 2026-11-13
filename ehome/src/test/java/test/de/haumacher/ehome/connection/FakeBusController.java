/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Enrollment;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * A controller connected to E-Home in the process of the test, with the bus session of homecontrol
 * #85 (<code>spec/api.md</code>, "Bus session"): enrolled for a project with a code
 * ({@link Enrollment}), authenticated on a {@link ControllerConnection} whose transport is this fake
 * ({@link ControllerConnection.Peer}), registered with the {@link ControllerConnections} as a real
 * socket would be, and reporting a status with or without the member <code>ehome.bus</code>.
 *
 * <p>
 * It answers E-Home's <code>bus</code> messages as the firmware does: <code>open</code> with
 * <code>opened</code> (the tunnel address, {@link #ADDRESS} unless set, 264, 254, 60) or <code>bus.busy</code>
 * while a session is open, <code>send</code> with the outcome its {@link Bus} gives (or
 * <code>bus.no_session</code>), <code>close</code> with <code>closed</code>. A test can refuse the next
 * message of an op with a code ({@link #refuseNext(String, String, String)}), deliver indications
 * ({@link #frames(byte[]...)}), end the session as the controller does ({@link #end(String, String)})
 * and end the connection ({@link #disconnect(String)}). The answers go back on a thread of their own,
 * in order, as the socket's reading thread delivers them.
 * </p>
 */
@SuppressWarnings("javadoc")
public class FakeBusController implements ControllerConnection.Peer {

	/** The individual address of the fake's tunnel. */
	public static final String ADDRESS = "1.1.250";

	/**
	 * The bus behind the fake's tunnel.
	 */
	public interface Bus {

		/**
		 * Puts a frame E-Home sent on the bus.
		 *
		 * @return The outcome of the tunnel: <code>confirmed</code>, <code>negative</code>,
		 *         <code>no_confirmation</code> or <code>lost</code>.
		 */
		String send(FakeBusController controller, byte[] frame) throws Exception;
	}

	private final ExecutorService _replies = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "fake-bus-controller");
		thread.setDaemon(true);
		return thread;
	});

	private final List<Map<String, Object>> _received = new ArrayList<>();

	private final Map<String, Deque<String[]>> _refusals = new LinkedHashMap<>();

	private final CompletableFuture<String> _nonce = new CompletableFuture<>();

	private ControllerConnection _connection;

	private TLObject _controller;

	private volatile Bus _bus = (controller, frame) -> "confirmed";

	private volatile boolean _session;

	private volatile boolean _silent;

	private volatile String _address = ADDRESS;

	private volatile String _closedWith;

	/**
	 * Enrolls a controller of the given name for the project and connects it.
	 *
	 * @param bus
	 *        Whether its status names the bus session (a firmware from 2026-11).
	 */
	public static FakeBusController connect(TLObject project, String name, boolean bus) throws Exception {
		Instant now = Instant.now();
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("code", Enrollment.createCode(project, null, now));
		request.put("name", name);
		request.put("version", "0.1.0");
		Enrollment.Answer enrolled = Enrollment.enroll(request, "http://127.0.0.1/ehome", now);
		if (enrolled.status() != 200) {
			throw new AssertionError("Not enrolled: " + enrolled.body());
		}
		ControllerConnections.refetch();
		String id = (String) enrolled.body().get("controller");
		String secret = (String) enrolled.body().get("secret");
		FakeBusController result = new FakeBusController();
		result._connection = new ControllerConnection(id, result);
		result._connection.open();
		String nonce = result._nonce.get(5, TimeUnit.SECONDS);
		Map<String, Object> auth = new LinkedHashMap<>();
		auth.put("type", "auth");
		auth.put("controller", id);
		auth.put("mac", HexFormat.of().formatHex(ControllerConnection.hmac(secret, nonce)));
		auth.put("version", "0.1.0");
		result._connection.received(Json.compact(auth));
		if (!result._connection.isReady()) {
			throw new AssertionError("Not authenticated: " + result._received);
		}
		result.status(bus);
		ControllerConnections.awaitKnowledgeBase();
		result._controller = ControllerConnections.find(id);
		return result;
	}

	/**
	 * Sends a status of the controller, with or without the member <code>ehome.bus</code>.
	 */
	public void status(boolean bus) {
		Map<String, Object> ehome = new LinkedHashMap<>();
		ehome.put("enabled", Boolean.TRUE);
		ehome.put("state", "connected");
		if (bus) {
			Map<String, Object> session = new LinkedHashMap<>();
			session.put("open", Boolean.valueOf(_session));
			ehome.put(BusSessionLink.TYPE, session);
		}
		Map<String, Object> device = new LinkedHashMap<>();
		device.put("name", "fake");
		device.put("version", "0.1.0");
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("type", "status");
		status.put("device", device);
		status.put("ehome", ehome);
		_connection.received(Json.compact(status));
	}

	/**
	 * The connection E-Home holds for this controller.
	 */
	public ControllerConnection connection() {
		return _connection;
	}

	/**
	 * The controller in E-Home (<code>ehome.runtime:Controller</code>).
	 */
	public TLObject controller() {
		return _controller;
	}

	/**
	 * Sets the bus behind the tunnel.
	 */
	public void setBus(Bus bus) {
		_bus = bus;
	}

	/**
	 * Sets the individual address of the fake's tunnel, which <code>opened</code> reports (default
	 * {@link #ADDRESS}): the address a real tunnel behind the fake was assigned.
	 */
	public void setAddress(String address) {
		_address = address;
	}

	/**
	 * Makes the fake ignore E-Home's <code>open</code> (a controller that does not answer).
	 */
	public void setSilent(boolean silent) {
		_silent = silent;
	}

	/**
	 * Whether a bus session is open on the fake.
	 */
	public boolean sessionOpen() {
		return _session;
	}

	/**
	 * Refuses the next message of E-Home with the given op.
	 */
	public synchronized void refuseNext(String op, String code, String error) {
		_refusals.computeIfAbsent(op, x -> new ArrayDeque<>()).add(new String[] { code, error });
	}

	/**
	 * The bus messages E-Home sent so far.
	 */
	public synchronized List<Map<String, Object>> busMessages() {
		return _received.stream().filter(m -> BusSessionLink.TYPE.equals(m.get("type"))).toList();
	}

	/**
	 * The frames of E-Home's <code>send</code> messages so far, as hex.
	 */
	public synchronized List<String> framesSent() {
		return busMessages().stream().filter(m -> "send".equals(m.get("op"))).map(m -> (String) m.get("frame")).toList();
	}

	/**
	 * Delivers indications of the tunnel to E-Home (<code>frames</code>).
	 */
	public void frames(byte[]... frames) {
		List<Object> hex = new ArrayList<>();
		for (byte[] frame : frames) {
			hex.add(HexFormat.of().withUpperCase().formatHex(frame));
		}
		Map<String, Object> message = event("frames", null);
		message.put("frames", hex);
		reply(message);
	}

	/**
	 * Ends the session as the controller does (<code>closed</code> without a reference).
	 */
	public void end(String code, String reason) {
		_session = false;
		Map<String, Object> message = event("closed", null);
		message.put("code", code);
		message.put("reason", reason);
		reply(message);
	}

	/**
	 * Ends the connection (the network failed).
	 */
	public void disconnect(String reason) throws Exception {
		_session = false;
		_replies.submit(() -> _connection.closed(reason)).get(5, TimeUnit.SECONDS);
	}

	/**
	 * Waits until the answers given so far are delivered.
	 */
	public void sync() throws Exception {
		_replies.submit(() -> {
			// Nothing.
		}).get(10, TimeUnit.SECONDS);
	}

	/**
	 * Ends the connection and the fake.
	 */
	public void stop() throws Exception {
		if (_connection.isReady()) {
			disconnect("test end");
		}
		_replies.shutdownNow();
	}

	// ControllerConnection.Peer

	@Override
	public void sendText(String text) {
		Map<String, Object> message;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = (Map<String, Object>) Json.parse(text, 16);
			message = parsed;
		} catch (JsonException ex) {
			throw new AssertionError("E-Home sent no JSON: " + text, ex);
		}
		synchronized (this) {
			_received.add(message);
		}
		if ("challenge".equals(message.get("type"))) {
			_nonce.complete((String) message.get("nonce"));
			return;
		}
		if (BusSessionLink.TYPE.equals(message.get("type"))) {
			_replies.execute(() -> bus(message));
		}
	}

	@Override
	public void sendBinary(byte[] data) {
		throw new AssertionError("No binary messages expected.");
	}

	@Override
	public void close(int code, String reason) {
		_closedWith = code + " " + reason;
	}

	@Override
	public String remote() {
		return "fake";
	}

	/**
	 * The close E-Home sent, <code>null</code> if none.
	 */
	public String closedWith() {
		return _closedWith;
	}

	private void bus(Map<String, Object> message) {
		String op = String.valueOf(message.get("op"));
		Object ref = message.get("ref");
		String[] refusal;
		synchronized (this) {
			Deque<String[]> refusals = _refusals.get(op);
			refusal = refusals == null ? null : refusals.poll();
		}
		if (refusal != null) {
			refused(op, ref, refusal[0], refusal[1]);
			return;
		}
		switch (op) {
			case "open": {
				if (_silent) {
					return;
				}
				if (_session) {
					refused(op, ref, "bus.busy", "a bus session is open already: one at a time");
					return;
				}
				_session = true;
				Map<String, Object> opened = event("opened", ref);
				opened.put("address", _address);
				opened.put("max_frame", Integer.valueOf(264));
				opened.put("max_apdu", Integer.valueOf(254));
				opened.put("idle_s", Integer.valueOf(60));
				answer(opened);
				return;
			}
			case "send": {
				if (!_session) {
					refused(op, ref, "bus.no_session", "no bus session is open");
					return;
				}
				String outcome;
				try {
					outcome = _bus.send(this, HexFormat.of().parseHex((String) message.get("frame")));
				} catch (Exception ex) {
					refused(op, ref, "bus.bad_frame", String.valueOf(ex.getMessage()));
					return;
				}
				Map<String, Object> sent = event("sent", ref);
				sent.put("outcome", outcome);
				answer(sent);
				if ("lost".equals(outcome)) {
					end("bus.tunnel_down", "the KNX tunnel went down: test");
				}
				return;
			}
			case "close": {
				if (!_session) {
					refused(op, ref, "bus.no_session", "no bus session is open");
					return;
				}
				_session = false;
				Map<String, Object> closed = event("closed", ref);
				closed.put("code", "bus.closed");
				closed.put("reason", "closed by E-Home");
				answer(closed);
				return;
			}
			default:
				refused("?", ref, "bus.bad_request", "\"op\" is open, send or close");
		}
	}

	private void refused(String op, Object ref, String code, String error) {
		Map<String, Object> refused = event("refused", ref);
		refused.put("op", op);
		refused.put("code", code);
		refused.put("error", error);
		answer(refused);
	}

	private static Map<String, Object> event(String event, Object ref) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", BusSessionLink.TYPE);
		message.put("event", event);
		if (ref != null) {
			message.put("ref", ref);
		}
		return message;
	}

	/** An answer given on the replying thread itself. */
	private void answer(Map<String, Object> message) {
		_connection.received(Json.compact(message));
	}

	/** A message queued behind the answers given so far. */
	private void reply(Map<String, Object> message) {
		_replies.execute(() -> answer(message));
	}

}
