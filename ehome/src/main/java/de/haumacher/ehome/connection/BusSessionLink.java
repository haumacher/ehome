/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.io.IOException;
import java.text.DateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.top_logic.basic.Logger;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXAckTimeoutException;
import tuwien.auto.calimero.KNXAddress;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.link.AbstractLink;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.TPSettings;

/**
 * A Calimero {@link KNXNetworkLink} through the bus session of a controller connected to E-Home
 * (haumacher/ehome #148, homecontrol #85, <code>spec/api.md</code> "Bus session"): the frames go as
 * <code>{"type": "bus", "op": "send"}</code> over the controller's {@link ControllerConnection} and
 * through the controller's KNX tunnel, the indications the tunnel receives for its individual address
 * (and broadcasts) come back as <code>frames</code>. The controller only forwards: the transport
 * connection, the load procedures and the management procedures run in E-Home on this link exactly as
 * on a tunnel of E-Home's own.
 *
 * <p>
 * {@link #open(ControllerConnection, String)} starts the session (<code>open</code>, answered with
 * <code>opened</code>: the tunnel's individual address becomes the link's own address, the longest
 * APDU its maximum). A frame is sent only when the outcome of the one before has come
 * (<code>sent</code>): one frame of the session waits on the controller at a time, in order. The
 * outcome is the confirmation of the link: <code>confirmed</code> is a positive confirmation,
 * <code>negative</code> and <code>no_confirmation</code> fail a send waiting for the confirmation
 * ({@link #sendRequestWait(KNXAddress, tuwien.auto.calimero.Priority, byte[])}) and pass one that does
 * not wait, <code>lost</code> ends the link. A full queue on the controller is waited out briefly;
 * any other refusal of a frame fails its send with the controller's reason. {@link #close()} ends the
 * session (<code>close</code>); the session's end by the controller (<code>closed</code>: idle, the
 * tunnel went down, an overflow) or the end of the controller's connection closes the link, and its
 * listeners learn the reason ({@link tuwien.auto.calimero.link.NetworkLinkListener#linkClosed(CloseEvent)}).
 * </p>
 *
 * <p>
 * One session per controller at a time: E-Home refuses a second one through the same controller
 * ({@link #sessionOpen(String)}), as the controller does (<code>bus.busy</code>). The capability is the
 * member <code>ehome.bus</code> of the controller's status ({@link #capable(ControllerConnection)});
 * an older firmware is refused before anything is sent.
 * </p>
 *
 * <p>
 * The link changes nothing by itself: it carries what the programming sends, and the one class building
 * the services that change a device stays <code>CalimeroWriter</code>, behind its switch.
 * </p>
 */
public final class BusSessionLink extends AbstractLink<AutoCloseable> implements ControllerConnection.Listener {

	/** The type of the messages of the bus session. */
	public static final String TYPE = "bus";

	/** The time to wait for the answer to <code>open</code>. */
	public static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * The time to wait for the outcome of a frame: the controller reports <code>no_confirmation</code>
	 * after 3 s on the tunnel, after the frames queued before it.
	 */
	public static final Duration OUTCOME_TIMEOUT = Duration.ofSeconds(10);

	/** Refusal: the controller has no KNX tunnel (a build without KNX). */
	public static final String CODE_KNX_UNAVAILABLE = "bus.knx_unavailable";

	/** Refusal or end: the controller's KNX tunnel is not up. */
	public static final String CODE_TUNNEL_DOWN = "bus.tunnel_down";

	/** Refusal: a session is open already. */
	public static final String CODE_BUSY = "bus.busy";

	/** Refusal: no session is open. */
	public static final String CODE_NO_SESSION = "bus.no_session";

	/** Refusal: the controller's queue for the tunnel is full. */
	public static final String CODE_QUEUE_FULL = "bus.queue_full";

	/** End: E-Home closed the session. */
	public static final String CODE_CLOSED = "bus.closed";

	/** How often a frame refused for a full queue is offered again. */
	private static final int QUEUE_RETRIES = 20;

	/** The pause before a frame refused for a full queue is offered again. */
	private static final long QUEUE_PAUSE_MILLIS = 50;

	/** The sessions open, by the id of their controller. */
	private static final Map<String, BusSessionLink> SESSIONS = new ConcurrentHashMap<>();

	/** The references of the messages, unique among all sessions. */
	private static final AtomicLong REFS = new AtomicLong();

	private final ControllerConnection _connection;

	private final String _controller;

	private final Object _sendLock = new Object();

	private final Map<String, CompletableFuture<Map<String, Object>>> _waiting = new ConcurrentHashMap<>();

	private volatile String _openRef;

	private volatile boolean _opened;

	private volatile String _closeRef;

	private volatile String _end;

	private volatile int _idleSeconds;

	private BusSessionLink(ControllerConnection connection, String controller) {
		super((AutoCloseable) null, "controller " + controller, new TPSettings());
		_connection = connection;
		_controller = controller;
	}

	/**
	 * Whether the controller's firmware has the bus session: its status names the member
	 * <code>ehome.bus</code>.
	 */
	public static boolean capable(ControllerConnection connection) {
		return connection.status().get("ehome") instanceof Map<?, ?> ehome && ehome.containsKey(TYPE);
	}

	/**
	 * Whether a bus session through the controller with the given id is open in E-Home.
	 */
	public static boolean sessionOpen(String controllerId) {
		return controllerId != null && SESSIONS.containsKey(controllerId);
	}

	/**
	 * Why programming through the given controller is refused now, <code>null</code> if a session can
	 * be opened: it is reached on the LAN only, not connected (naming its last contact), has not
	 * reported its status yet, its firmware has no bus session, or a session through it is open
	 * already.
	 */
	public static ResKey veto(TLObject controller) {
		String name = String.valueOf(controller.tValueByName("name"));
		String id = ControllerConnections.controllerId(controller);
		if (id == null) {
			return I18NConstants.ERROR_BUS_NOT_ENROLLED__CONTROLLER.fill(name);
		}
		ControllerConnection connection = ControllerConnections.get(id);
		if (connection == null) {
			return offline(controller);
		}
		return veto(connection, name);
	}

	/**
	 * Why programming through the given connection is refused now, <code>null</code> if a session can
	 * be opened.
	 */
	public static ResKey veto(ControllerConnection connection, String name) {
		if (connection.status().isEmpty()) {
			return I18NConstants.ERROR_BUS_NO_STATUS__CONTROLLER.fill(name);
		}
		if (!capable(connection)) {
			return I18NConstants.ERROR_BUS_FIRMWARE__CONTROLLER.fill(name);
		}
		if (sessionOpen(connection.controllerId())) {
			return I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill(name);
		}
		return null;
	}

	/**
	 * Why an enrolled controller that is not connected cannot be programmed through: its last contact,
	 * or that it never connected.
	 */
	public static ResKey offline(TLObject controller) {
		Object name = controller.tValueByName("name");
		if (controller.tValueByName("lastContact") instanceof Date date) {
			return I18NConstants.ERROR_BUS_OFFLINE__CONTROLLER_LAST_CONTACT.fill(name,
				DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Resources.getInstance().getLocale())
					.format(date));
		}
		return I18NConstants.ERROR_BUS_NEVER_CONNECTED__CONTROLLER.fill(name);
	}

	/**
	 * Opens a bus session through the controller of the given connection.
	 *
	 * @param name
	 *        The name of the controller, for a person.
	 * @return The link, open; {@link #close()} ends the session.
	 * @throws TopLogicException
	 *         If the controller refuses the session or does not answer: its connection closed, the
	 *         firmware has no bus session, a session is open already, the KNX tunnel is down, ...
	 */
	public static BusSessionLink open(ControllerConnection connection, String name) {
		return open(connection, name, OPEN_TIMEOUT);
	}

	/**
	 * Opens a bus session as {@link #open(ControllerConnection, String)} does, waiting the given time
	 * for the controller's answer.
	 */
	public static BusSessionLink open(ControllerConnection connection, String name, Duration timeout) {
		if (connection == null || !connection.isReady()) {
			throw new TopLogicException(I18NConstants.ERROR_BUS_CONNECTION_ENDED__CONTROLLER_REASON.fill(name,
				"not connected"));
		}
		ResKey veto = veto(connection, name);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		BusSessionLink link = new BusSessionLink(connection, name);
		if (SESSIONS.putIfAbsent(connection.controllerId(), link) != null) {
			link.close();
			throw new TopLogicException(I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill(name));
		}
		boolean done = false;
		try {
			link.start(timeout);
			done = true;
			return link;
		} finally {
			if (!done) {
				link.close();
			}
		}
	}

	private void start(Duration timeout) {
		_connection.addListener(this);
		String ref = ref();
		_openRef = ref;
		CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
		_waiting.put(ref, answer);
		Map<String, Object> request = message("open", ref);
		Map<String, Object> result;
		try {
			_connection.send(request);
			result = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (IOException | ExecutionException ex) {
			throw new TopLogicException(I18NConstants.ERROR_BUS_CONNECTION_ENDED__CONTROLLER_REASON.fill(_controller,
				reason(ex)), ex);
		} catch (TimeoutException ex) {
			throw new TopLogicException(I18NConstants.ERROR_BUS_NO_ANSWER__CONTROLLER.fill(_controller), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TopLogicException(I18NConstants.ERROR_BUS_NO_ANSWER__CONTROLLER.fill(_controller), ex);
		} finally {
			_waiting.remove(ref);
		}
		if ("refused".equals(result.get("event"))) {
			throw new TopLogicException(refusal(result));
		}
		IndividualAddress address;
		try {
			address = new IndividualAddress(String.valueOf(result.get("address")));
		} catch (Exception ex) {
			throw new TopLogicException(I18NConstants.ERROR_BUS_REFUSED__CONTROLLER_REASON_CODE.fill(_controller,
				"no individual address in " + result, "opened"), ex);
		}
		getKNXMedium().setDeviceAddress(address);
		if (result.get("max_apdu") instanceof Number apdu && apdu.intValue() >= 15) {
			getKNXMedium().setMaxApduLength(Math.min(apdu.intValue(), 254));
		}
		_idleSeconds = result.get("idle_s") instanceof Number idle ? idle.intValue() : 0;
		_opened = true;
		Logger.info("Bus session opened through controller " + _controller + ": tunnel address " + address
			+ ", APDU up to " + getKNXMedium().maxApduLength() + " octets, idle after " + _idleSeconds + " s.",
			BusSessionLink.class);
	}

	private ResKey refusal(Map<String, Object> refused) {
		String code = String.valueOf(refused.get("code"));
		String error = Objects.toString(refused.get("error"), code);
		return switch (code) {
			case CODE_BUSY -> I18NConstants.ERROR_BUS_SESSION_OPEN__CONTROLLER.fill(_controller);
			case CODE_TUNNEL_DOWN -> I18NConstants.ERROR_BUS_TUNNEL_DOWN__CONTROLLER_REASON.fill(_controller, error);
			case CODE_KNX_UNAVAILABLE -> I18NConstants.ERROR_BUS_NO_KNX__CONTROLLER_REASON.fill(_controller, error);
			default -> I18NConstants.ERROR_BUS_REFUSED__CONTROLLER_REASON_CODE.fill(_controller, error, code);
		};
	}

	/**
	 * The individual address of the controller's tunnel, the source of the frames of this link.
	 */
	public IndividualAddress address() {
		return getKNXMedium().getDeviceAddress();
	}

	/**
	 * The time after which the controller ends a session without frames, 0 if it did not say.
	 */
	public int idleSeconds() {
		return _idleSeconds;
	}

	/**
	 * Why the session ended, <code>null</code> while it is open.
	 */
	public String end() {
		return _end;
	}

	@Override
	protected void onSend(KNXAddress dst, byte[] msg, boolean waitForCon) {
		throw new IllegalStateException("The bus session carries cEMI frames only.");
	}

	@Override
	protected void onSend(CEMILData msg, boolean waitForCon) throws KNXTimeoutException, KNXLinkClosedException {
		byte[] frame = msg.toByteArray();
		String hex = HexFormat.of().withUpperCase().formatHex(frame);
		synchronized (_sendLock) {
			for (int attempt = 0;; attempt++) {
				Map<String, Object> answer = exchange(hex);
				String event = String.valueOf(answer.get("event"));
				if ("sent".equals(event)) {
					String outcome = String.valueOf(answer.get("outcome"));
					switch (outcome) {
						case "confirmed":
							return;
						case "negative":
							if (waitForCon) {
								throw new KNXAckTimeoutException("negative confirmation of the frame to " + msg.getDestination()
									+ " through the controller " + _controller + ": not sent on the bus");
							}
							return;
						case "no_confirmation":
							if (waitForCon) {
								throw new KNXTimeoutException("no confirmation of the frame to " + msg.getDestination()
									+ " through the controller " + _controller);
							}
							return;
						case "lost":
							throw new KNXLinkClosedException("the frame to " + msg.getDestination() + " was lost: the KNX tunnel of the controller "
								+ _controller + " went down");
						default:
							throw new KNXTimeoutException("the controller " + _controller + " reported the outcome " + outcome);
					}
				}
				String code = String.valueOf(answer.get("code"));
				String error = Objects.toString(answer.get("error"), code);
				if (CODE_QUEUE_FULL.equals(code) && attempt < QUEUE_RETRIES) {
					pause();
					continue;
				}
				if (CODE_NO_SESSION.equals(code) || CODE_TUNNEL_DOWN.equals(code)) {
					throw new KNXLinkClosedException("the controller " + _controller + " refused the frame: " + error
						+ " (" + code + ")");
				}
				throw new FrameRefusedException("the controller " + _controller + " refused the frame to "
					+ msg.getDestination() + ": " + error + " (" + code + ")");
			}
		}
	}

	private Map<String, Object> exchange(String hex) throws KNXTimeoutException, KNXLinkClosedException {
		String end = _end;
		if (end != null || !isOpen()) {
			throw new KNXLinkClosedException(end == null ? "the bus session through the controller " + _controller
				+ " is closed" : end);
		}
		String ref = ref();
		CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
		_waiting.put(ref, answer);
		try {
			Map<String, Object> request = message("send", ref);
			request.put("frame", hex);
			_connection.send(request);
			return answer.get(OUTCOME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (IOException ex) {
			throw new KNXLinkClosedException("sending through the controller " + _controller + " failed: "
				+ ex.getMessage(), ex);
		} catch (ExecutionException ex) {
			throw new KNXLinkClosedException(reason(ex), ex);
		} catch (TimeoutException ex) {
			throw new KNXTimeoutException("no outcome of the frame from the controller " + _controller + " within "
				+ OUTCOME_TIMEOUT.toSeconds() + " s");
		} catch (InterruptedException ex) {
			close();
			Thread.currentThread().interrupt();
			throw new KNXLinkClosedException("the bus session through the controller " + _controller
				+ " was closed (interrupted)", ex);
		} finally {
			_waiting.remove(ref);
		}
	}

	private void pause() throws KNXLinkClosedException {
		try {
			Thread.sleep(QUEUE_PAUSE_MILLIS);
		} catch (InterruptedException ex) {
			close();
			Thread.currentThread().interrupt();
			throw new KNXLinkClosedException("the bus session through the controller " + _controller
				+ " was closed (interrupted)", ex);
		}
	}

	@Override
	protected void onClose() {
		boolean end;
		synchronized (this) {
			end = _end == null;
			if (end) {
				_end = "the bus session through the controller " + _controller + " was closed";
			}
		}
		_connection.removeListener(this);
		SESSIONS.remove(_connection.controllerId(), this);
		failWaiting(_end);
		// Also after an open that failed by its time: a late "opened" leaves a session on the controller
		// that would refuse the next one until it idles.
		if (end && _openRef != null && _connection.isReady()) {
			String ref = ref();
			_closeRef = ref;
			try {
				_connection.send(message("close", ref));
			} catch (IOException ex) {
				// The connection ends the session anyway.
			}
		}
	}

	// ControllerConnection.Listener: called on the thread reading the socket, must not block.

	@Override
	public void message(ControllerConnection connection, String type, Map<String, Object> message) {
		if (!TYPE.equals(type)) {
			return;
		}
		String event = String.valueOf(message.get("event"));
		Object ref = message.get("ref");
		String refText = ref == null ? null : ref.toString();
		switch (event) {
			case "opened":
			case "refused":
			case "sent": {
				if ("opened".equals(event) && refText != null && refText.equals(_openRef)) {
					// From here on the session's events are this link's (on the socket's thread, in order).
					_opened = true;
				}
				CompletableFuture<Map<String, Object>> waiting = refText == null ? null : _waiting.get(refText);
				if (waiting != null) {
					waiting.complete(message);
				}
				return;
			}
			case "frames":
				if (_opened && _end == null) {
					frames(message);
				}
				return;
			case "closed":
				// A "closed" before the session's "opened" belongs to an earlier session; one with
				// a reference of another session as well.
				if (_opened && (refText == null || refText.equals(_closeRef))) {
					if (refText == null) {
						ended("the controller " + _controller + " ended the bus session: "
							+ Objects.toString(message.get("reason"), "") + " (" + message.get("code") + ")");
					}
				}
				return;
			default:
				return;
		}
	}

	@Override
	public void closed(ControllerConnection connection, String reason) {
		ended("the connection of the controller " + _controller + " to E-Home ended ("
			+ (reason == null || reason.isBlank() ? "no reason" : reason) + ")");
	}

	private void frames(Map<String, Object> message) {
		if (message.get("dropped") instanceof Number dropped && dropped.longValue() > 0) {
			Logger.info("The controller " + _controller + " dropped " + dropped + " indications of the bus session.",
				BusSessionLink.class);
		}
		if (!(message.get("frames") instanceof List<?> frames)) {
			return;
		}
		for (Object frame : frames) {
			byte[] bytes;
			try {
				bytes = HexFormat.of().parseHex(String.valueOf(frame));
			} catch (IllegalArgumentException ex) {
				Logger.warn("The controller " + _controller + " sent a frame that is no hex: " + frame,
					BusSessionLink.class);
				continue;
			}
			notifier.frameReceived(new FrameEvent(this, bytes));
		}
	}

	/**
	 * The session ended without E-Home closing it: the link is closed, a frame waiting for its outcome
	 * fails, and the listeners learn the reason (on a thread of its own: the socket's thread must not
	 * wait for them).
	 */
	private void ended(String reason) {
		synchronized (this) {
			if (_end != null) {
				return;
			}
			_end = reason;
		}
		Logger.info("Bus session ended: " + reason, BusSessionLink.class);
		_connection.removeListener(this);
		SESSIONS.remove(_connection.controllerId(), this);
		failWaiting(reason);
		Thread thread = new Thread(() -> notifier.connectionClosed(new CloseEvent(this, CloseEvent.SERVER_REQUEST, reason)),
			"bus-session-end");
		thread.setDaemon(true);
		thread.start();
	}

	private void failWaiting(String reason) {
		for (CompletableFuture<Map<String, Object>> waiting : _waiting.values()) {
			waiting.completeExceptionally(new IOException(reason));
		}
	}

	private static Map<String, Object> message(String op, String ref) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", TYPE);
		message.put("op", op);
		message.put("ref", ref);
		return message;
	}

	private static String ref() {
		return "ehome-" + REFS.incrementAndGet();
	}

	private static String reason(Exception ex) {
		Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
		return cause.getMessage();
	}

	/**
	 * The controller refused a frame for a reason that does not end the session (a frame it does not
	 * forward): the send fails, the link stays open.
	 */
	public static final class FrameRefusedException extends KNXTimeoutException {

		/**
		 * Creates a {@link FrameRefusedException}.
		 */
		public FrameRefusedException(String message) {
			super(message);
		}
	}

}
