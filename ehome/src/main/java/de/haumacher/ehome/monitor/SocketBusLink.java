/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.programming.download.BusException;

/**
 * {@link BusLink} through the monitor stream of a controller connected to E-Home (haumacher/ehome
 * #54): the same messages as {@link ControllerBusLink} reads from the controller's own WebSocket, over
 * the controller's {@link ControllerConnection} instead.
 *
 * <p>
 * The link subscribes to the monitor stream on the connection. When the connection ends, the link
 * waits for the controller to connect again ({@link #problem()} says so) and then subscribes with the
 * sequence number after the last event it got, so the events in between are caught up (or a gap
 * reported); an answer with <code>reset: true</code> (the controller restarted) is reported as
 * {@link LinkEvent.Kind#RESET}. Sending goes through the controller as with
 * {@link ControllerBusLink}.
 * </p>
 */
public class SocketBusLink implements BusLink, ControllerConnections.Listener {

	private static final String SUBSCRIBE_REF = "ehome-monitor";

	private final String _name;

	private final String _controllerId;

	private final Duration _timeout;

	private final Object _lock = new Object();

	private final List<Object> _buffer = new ArrayList<>();

	private final Map<Long, CompletableFuture<Map<String, Object>>> _results = new ConcurrentHashMap<>();

	private final AtomicLong _refs = new AtomicLong();

	private Listener _listener;

	private volatile ControllerConnection _connection;

	private volatile CompletableFuture<Map<String, Object>> _subscribed = new CompletableFuture<>();

	private volatile String _problem;

	private volatile boolean _closing;

	private volatile int _localAddress;

	private volatile long _next = -1;

	private SocketBusLink(String name, String controllerId, Duration timeout) {
		_name = name;
		_controllerId = controllerId;
		_timeout = timeout;
	}

	/**
	 * Subscribes to the monitor stream of the connected controller with the given id.
	 *
	 * @param name
	 *        The name of the controller, for a person.
	 * @throws BusException
	 *         If the controller is not connected or does not answer the subscription.
	 */
	public static SocketBusLink open(String name, String controllerId, Duration timeout) throws BusException {
		SocketBusLink result = new SocketBusLink(name, controllerId, timeout);
		ControllerConnections.addListener(result);
		ControllerConnection connection = ControllerConnections.get(controllerId);
		if (connection == null) {
			result.close();
			throw new BusException("The controller " + result.description() + " is not connected to E-Home.");
		}
		try {
			result.subscribe(connection);
			result._subscribed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (IOException | ExecutionException ex) {
			result.close();
			throw new BusException("The controller " + result.description() + " refused the monitor stream: "
				+ ex.getMessage(), ex);
		} catch (TimeoutException ex) {
			result.close();
			throw new BusException("The controller " + result.description() + " did not answer the subscription.", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			result.close();
			throw new BusException("Interrupted while subscribing.", ex);
		}
		return result;
	}

	/**
	 * The text {@link #description()} gives for a controller over its E-Home connection.
	 */
	public static String description(String name) {
		return name + " (E-Home)";
	}

	@Override
	public String description() {
		return description(_name);
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

	@Override
	public void send(int destination, byte[] apdu) throws BusException {
		ControllerConnection connection = _connection;
		if (connection == null || !connection.isReady()) {
			throw new BusException("The controller " + description() + " is not connected to E-Home.");
		}
		Map<String, Object> message = ControllerBusLink.sendMessage(destination, apdu);
		long ref = _refs.incrementAndGet();
		message.put("ref", Long.valueOf(ref));
		CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
		_results.put(Long.valueOf(ref), result);
		try {
			connection.send(message);
			Map<String, Object> answer = result.get(_timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!Boolean.TRUE.equals(answer.get("ok"))) {
				throw new BusException("The controller refused the telegram to "
					+ ControllerBusLink.groupAddress(destination) + ": " + answer.get("error") + " (" + answer.get("code")
					+ ")");
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
		ControllerConnections.removeListener(this);
		synchronized (_lock) {
			_listener = null;
			_buffer.clear();
		}
		ControllerConnection connection = _connection;
		_connection = null;
		if (connection != null && connection.isReady()) {
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("type", "monitor");
			message.put("subscribe", Boolean.FALSE);
			try {
				connection.send(message);
			} catch (IOException ex) {
				// Closed anyway.
			}
		}
	}

	private void subscribe(ControllerConnection connection) throws IOException {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "monitor");
		message.put("subscribe", Boolean.TRUE);
		long next = _next;
		if (next >= 0) {
			message.put("since", Long.valueOf(next));
		}
		message.put("ref", SUBSCRIBE_REF);
		_connection = connection;
		connection.send(message);
	}

	// ControllerConnections.Listener

	@Override
	public void connected(ControllerConnection connection) {
		if (_closing || !_controllerId.equals(connection.controllerId())) {
			return;
		}
		_subscribed = new CompletableFuture<>();
		try {
			subscribe(connection);
		} catch (IOException ex) {
			_problem = "The monitor stream of the controller " + description() + " could not be subscribed: "
				+ ex.getMessage();
			notifyState();
		}
	}

	@Override
	public void disconnected(ControllerConnection connection, String reason) {
		if (_closing || !_controllerId.equals(connection.controllerId())) {
			return;
		}
		if (_connection == connection) {
			_connection = null;
		}
		_problem = "The controller " + description() + " is not connected to E-Home (" + reason
			+ "); waiting for it to connect again.";
		notifyState();
	}

	@Override
	public void message(ControllerConnection connection, String type, Map<String, Object> message) {
		if (_closing || !"monitor".equals(type) || !_controllerId.equals(connection.controllerId())) {
			return;
		}
		if (message.containsKey("subscribed")) {
			if (Boolean.TRUE.equals(message.get("subscribed"))) {
				boolean first = _next < 0;
				if (message.get("next") instanceof Number next && first) {
					_next = next.longValue();
				}
				if (Boolean.TRUE.equals(message.get("reset"))) {
					emit(new LinkEvent(System.currentTimeMillis(), LinkEvent.Kind.RESET, Map.of(), null, null));
				}
				if (_problem != null) {
					_problem = null;
					notifyState();
				}
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
		long next = ControllerBusLink.events(message, System.currentTimeMillis(), this::emit,
			source -> _localAddress = source);
		if (next >= 0) {
			_next = next;
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

}
