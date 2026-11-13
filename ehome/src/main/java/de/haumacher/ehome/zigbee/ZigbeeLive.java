/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.top_logic.basic.Logger;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.DaemonThreads;
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Deployer;

/**
 * The Zigbee devices of the projects follow their connected controllers by themselves (haumacher/ehome
 * #64): a {@link ControllerConnections.Listener} that re-reads a controller's export when its
 * <code>zigbee</code> events say the device list changed, and the live state of the controllers'
 * networks the Zigbee tab shows (the permit countdown, the devices being interviewed, failed or gone).
 *
 * <p>
 * <b>The import.</b> An event in {@link #IMPORT_EVENTS} schedules a read of the export (all pages of
 * <code>GET /api/zigbee/export</code> over the connection, {@link ZigbeeController#fetch(String, ControllerClient)})
 * and the same import as "Read" ({@link ZigbeeImport}) into every project whose controller it is, with
 * the network facts stored on the controller ({@link ZigbeeController#storeNetwork}). The imports of
 * a controller are debounced: the first runs {@link #DELAY} after the event that asked for it, a
 * further one no earlier than {@link #MIN_INTERVAL} after the start of the previous one, and every
 * event until then is part of it; an event during an import asks for one more afterwards. A
 * <code>permit</code> event changes only the live state. Events of a controller no project deploys
 * to are ignored (logged at debug level).
 * </p>
 *
 * <p>
 * <b>Threads.</b> The events arrive on the socket threads, which must not block: they only update the
 * live state and schedule. The read runs on the single thread of this class: a request over the
 * connection blocks until its response, which the socket thread delivers, so it must not run there,
 * and not on the connections' writer thread either, which would stall the last-contact updates of
 * every controller for the time of the read. The import itself, a write of the knowledge base, goes
 * to the connections' writer thread ({@link ControllerConnections#executeAndWait(Runnable)}), the one
 * thread that writes what the connections learn, so it cannot conflict with the last contact stored
 * for the same controller.
 * </p>
 *
 * <p>
 * <b>The live state</b> is held in memory per controller id, as the online state of the connections
 * is: the last <code>permit</code> event (seconds and arrival time; the countdown is computed when it
 * is shown), the devices currently interviewing, failed or gone, the last successful read (time and
 * whether it was live or on request) and the failure of the last live import. A disconnect clears the
 * permit and the devices' states. The views listening ({@link #addListener(String, Runnable)}) are
 * told of every change, and once a second while a network is open for joining.
 * </p>
 */
public final class ZigbeeLive implements ControllerConnections.Listener {

	/** The events after which the export is read again. */
	public static final Set<String> IMPORT_EVENTS =
		Set.of("joined", "interviewed", "interview_failed", "left", "removed", "changed", "network");

	/** The time between an event and the import it schedules, so that a burst becomes one import. */
	public static final Duration DELAY = Duration.ofMillis(300);

	/** The least time between the starts of two imports of one controller. */
	public static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

	/** The time {@link #stop()} waits for an interrupted read to end. */
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * The live state of a device, as the controller's events tell it.
	 */
	public enum DeviceState {
		/** The device joined, or the controller interviews it. */
		INTERVIEWING,
		/** The interview failed; the controller tries again when it hears the device. */
		INTERVIEW_FAILED,
		/** The device announced its leave; rejoining restores it. */
		LEFT;
	}

	/**
	 * Since when, and for how long, the controller's network accepts joining devices.
	 *
	 * @param seconds
	 *        The time the controller named (0: closed).
	 * @param at
	 *        When it named it.
	 */
	public record Permit(int seconds, Instant at) {

		/**
		 * The seconds left at the given time, 0 when closed.
		 */
		public long remaining(Instant now) {
			return Math.max(0, seconds - Duration.between(at, now).toSeconds());
		}
	}

	/**
	 * The last successful read of a controller's export.
	 *
	 * @param at
	 *        When E-Home read it.
	 * @param live
	 *        Whether it followed the controller's events, not a user's request.
	 */
	public record Read(Instant at, boolean live) {
		// A record.
	}

	/**
	 * A failed live import.
	 *
	 * @param at
	 *        When it failed.
	 * @param reason
	 *        Why.
	 */
	public record Failure(Instant at, ResKey reason) {
		// A record.
	}

	/**
	 * What is known of one controller.
	 */
	private static final class State {

		volatile Permit _permit;

		volatile Read _read;

		volatile Failure _failure;

		final Map<String, DeviceState> _devices = new ConcurrentHashMap<>();

		/** The scheduled import, guarded by this object. */
		ScheduledFuture<?> _scheduled;

		/** Whether an import runs, guarded by this object. */
		boolean _running;

		/** Whether an event arrived while an import ran, guarded by this object. */
		boolean _again;

		/** The start of the last import (nano time), guarded by this object. */
		long _lastStart = Long.MIN_VALUE;
	}

	private static final ZigbeeLive INSTANCE = new ZigbeeLive();

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();

	private static final Map<String, List<Runnable>> LISTENERS = new ConcurrentHashMap<>();

	/**
	 * The thread of the reads and the countdown, while started; written under the class's lock, read
	 * without it (under the lock of a {@link State}, which {@link #stop()} takes after the class's).
	 */
	private static volatile ScheduledExecutorService _executor;

	private static boolean _started;

	private static ScheduledFuture<?> _ticker;

	/** The thread of {@link #_executor}, <code>null</code> when stopped. */
	private static DaemonThreads _threads;

	private ZigbeeLive() {
		// Singleton.
	}

	/**
	 * Starts following the controllers' events (at the start of the application; again is harmless).
	 */
	public static synchronized void start() {
		if (!_started) {
			_started = true;
			_threads = new DaemonThreads("zigbee-live");
			_executor = Executors.newSingleThreadScheduledExecutor(_threads);
			ControllerConnections.addListener(INSTANCE);
			_ticker = _executor.scheduleWithFixedDelay(ZigbeeLive::tick, 1, 1, TimeUnit.SECONDS);
		}
	}

	/**
	 * Stops following the controllers' events, ends its thread (haumacher/ehome #81: at the end of the
	 * application, also when it ends during its start) and forgets the live state. A read running
	 * is interrupted.
	 */
	public static synchronized void stop() {
		_started = false;
		ControllerConnections.removeListener(INSTANCE);
		if (_ticker != null) {
			_ticker.cancel(false);
			_ticker = null;
		}
		for (State state : STATES.values()) {
			synchronized (state) {
				if (state._scheduled != null) {
					state._scheduled.cancel(false);
					state._scheduled = null;
				}
			}
		}
		STATES.clear();
		ScheduledExecutorService executor = _executor;
		DaemonThreads threads = _threads;
		_executor = null;
		_threads = null;
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
				// A terminated executor's thread may still be running its last instructions.
				threads.join(STOP_TIMEOUT);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Adds a receiver of the changes of the live state of the controller with the given id (an event,
	 * an import done or failed); called on other threads, it must not block.
	 */
	public static void addListener(String controllerId, Runnable listener) {
		LISTENERS.computeIfAbsent(controllerId, id -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/**
	 * Removes a receiver added with {@link #addListener(String, Runnable)}.
	 */
	public static void removeListener(String controllerId, Runnable listener) {
		List<Runnable> listeners = LISTENERS.get(controllerId);
		if (listeners != null) {
			listeners.remove(listener);
		}
	}

	/**
	 * The last <code>permit</code> event or read of the controller with the given id while it is
	 * connected; <code>null</code> if there was none.
	 */
	public static Permit permit(String controllerId) {
		State state = controllerId == null ? null : STATES.get(controllerId);
		return state == null ? null : state._permit;
	}

	/**
	 * The last successful read of the controller's export since the start of E-Home, <code>null</code>
	 * for none.
	 */
	public static Read lastRead(String controllerId) {
		State state = controllerId == null ? null : STATES.get(controllerId);
		return state == null ? null : state._read;
	}

	/**
	 * The failure of the last live import of the controller, <code>null</code> if it succeeded (or
	 * none ran).
	 */
	public static Failure failure(String controllerId) {
		State state = controllerId == null ? null : STATES.get(controllerId);
		return state == null ? null : state._failure;
	}

	/**
	 * The live state of the device of the controller, <code>null</code> if the events tell nothing
	 * about it (the stored <code>interviewed</code> flag tells the rest).
	 */
	public static DeviceState deviceState(String controllerId, String ieee) {
		State state = controllerId == null || ieee == null ? null : STATES.get(controllerId);
		return state == null ? null : state._devices.get(ieee);
	}

	/**
	 * The export of the controller was read and imported (live or on a user's request): the read is
	 * recorded, the failure forgotten and the network's permit time taken as the live one.
	 */
	public static void imported(String controllerId, ZigbeeExport.Export export, boolean live) {
		if (controllerId == null) {
			return;
		}
		State state = state(controllerId);
		Instant now = Instant.now();
		state._read = new Read(now, live);
		state._failure = null;
		if (export.network() != null && export.network().permitJoinSeconds() != null) {
			state._permit = new Permit(export.network().permitJoinSeconds().intValue(), now);
		}
		changed(controllerId);
	}

	@Override
	public void message(ControllerConnection connection, String type, Map<String, Object> message) {
		if (!"zigbee".equals(type)) {
			return;
		}
		String controllerId = connection.controllerId();
		State state = state(controllerId);
		String event = String.valueOf(message.get("event"));
		String ieee = message.get("ieee") instanceof String text ? ZigbeeFunctionTable.normalizeIeee(text) : null;
		switch (event) {
			case "permit":
				int seconds = message.get("permit_join_s") instanceof Number number ? number.intValue() : 0;
				state._permit = new Permit(seconds, Instant.now());
				break;
			case "joined":
			case "interviewing":
				put(state, ieee, DeviceState.INTERVIEWING);
				break;
			case "interview_failed":
				put(state, ieee, DeviceState.INTERVIEW_FAILED);
				break;
			case "left":
				put(state, ieee, DeviceState.LEFT);
				break;
			case "interviewed":
			case "removed":
				put(state, ieee, null);
				break;
			case "changed":
				// Among others "back after a leave"; a device gone stale has not left.
				if (ieee != null) {
					state._devices.remove(ieee, DeviceState.LEFT);
				}
				break;
			default:
				break;
		}
		if (IMPORT_EVENTS.contains(event)) {
			schedule(controllerId, state);
		}
		changed(controllerId);
	}

	@Override
	public void disconnected(ControllerConnection connection, String reason) {
		String controllerId = connection.controllerId();
		State state = STATES.get(controllerId);
		if (state == null) {
			return;
		}
		state._permit = null;
		state._devices.clear();
		synchronized (state) {
			if (state._scheduled != null) {
				state._scheduled.cancel(false);
				state._scheduled = null;
			}
		}
		changed(controllerId);
	}

	private static void put(State state, String ieee, DeviceState deviceState) {
		if (ieee == null) {
			return;
		}
		if (deviceState == null) {
			state._devices.remove(ieee);
		} else {
			state._devices.put(ieee, deviceState);
		}
	}

	private static State state(String controllerId) {
		return STATES.computeIfAbsent(controllerId, id -> new State());
	}

	private static void schedule(String controllerId, State state) {
		synchronized (state) {
			if (state._running) {
				state._again = true;
				return;
			}
			if (state._scheduled != null) {
				return;
			}
			long now = System.nanoTime();
			long delay = DELAY.toNanos();
			if (state._lastStart != Long.MIN_VALUE) {
				delay = Math.max(delay, state._lastStart + MIN_INTERVAL.toNanos() - now);
			}
			ScheduledExecutorService executor = _executor;
			if (executor == null) {
				// Stopped.
				return;
			}
			try {
				state._scheduled = executor.schedule(() -> run(controllerId, state), delay, TimeUnit.NANOSECONDS);
			} catch (RejectedExecutionException ex) {
				// Stopped meanwhile.
			}
		}
	}

	private static void run(String controllerId, State state) {
		synchronized (state) {
			state._scheduled = null;
			state._running = true;
			state._lastStart = System.nanoTime();
		}
		try {
			// The read of the knowledge base, the commit of the import and the refetch after it need an interaction.
			ThreadContext.inSystemContext(ZigbeeLive.class, () -> importExport(controllerId, state));
		} catch (RuntimeException ex) {
			Logger.error("The live import of the Zigbee devices of controller " + controllerId + " failed.", ex,
				ZigbeeLive.class);
		} finally {
			synchronized (state) {
				state._running = false;
				if (state._again) {
					state._again = false;
					schedule(controllerId, state);
				}
			}
		}
	}

	/**
	 * The controller of a live import and the projects deployed to it.
	 */
	private record Target(String name, List<TLObject> projects) {
		// A record.
	}

	private static void importExport(String controllerId, State state) {
		ControllerConnection connection = ControllerConnections.get(controllerId);
		if (connection == null) {
			// Gone meanwhile; it reads again after its next event.
			return;
		}
		ControllerConnections.refetch();
		TLObject found = ControllerConnections.find(controllerId);
		Target target = found == null ? null
			: new Target(String.valueOf(found.tValueByName("name")), ZigbeeController.projects(found));
		if (target == null || target.projects().isEmpty()) {
			Logger.debug("Zigbee event of controller " + connection.name() + " (" + controllerId
				+ ") ignored: no project deploys to it.", ZigbeeLive.class);
			return;
		}
		try {
			ControllerClient client =
				ControllerClient.over(connection, ZigbeeController.connectionLabel(), Deployer.TIMEOUT);
			ZigbeeExport.Export export =
				ZigbeeExport.read(ZigbeeController.fetch(target.name(), client));
			List<String> summaries = new ArrayList<>();
			ControllerConnections.executeAndWait(() -> {
				TLObject controller = ControllerConnections.find(controllerId);
				if (controller == null) {
					return;
				}
				try (Transaction tx = PersistencyLayer.getKnowledgeBase()
					.beginTransaction(I18NConstants.COMMIT_ZIGBEE_LIVE__CONTROLLER.fill(target.name()))) {
					for (TLObject project : ZigbeeController.projects(controller)) {
						summaries.add(ZigbeeImport.importExport(project, export).summary());
					}
					ZigbeeController.storeNetwork(controller, export, true);
					tx.commit();
				}
			});
			Logger.info("Zigbee devices of controller " + target.name() + " imported live: "
				+ String.join(" ", summaries).replace('\n', ' '), ZigbeeLive.class);
			imported(controllerId, export, true);
		} catch (TopLogicException ex) {
			failed(controllerId, state, target.name(), ex.getErrorKey(), ex);
		} catch (RuntimeException ex) {
			failed(controllerId, state, target.name(),
				I18NConstants.ERROR_LIVE_IMPORT__DETAIL.fill(String.valueOf(ex.getMessage())), ex);
		}
	}

	private static void failed(String controllerId, State state, String name, ResKey reason, Exception ex) {
		String message = "The live import of the Zigbee devices of controller " + name + " (" + controllerId
			+ ") failed: " + Resources.getInstance().getString(reason);
		if (ex instanceof TopLogicException) {
			// A refusal with its reason (unreachable, refused, an export E-Home cannot read): no stack needed.
			Logger.warn(message, ZigbeeLive.class);
		} else {
			Logger.warn(message, ex, ZigbeeLive.class);
		}
		state._failure = new Failure(Instant.now(), reason);
		changed(controllerId);
	}

	/**
	 * Tells the listeners of every controller whose network is open for joining once a second, so the
	 * countdown they show moves; once more when it closed.
	 */
	private static void tick() {
		Instant now = Instant.now();
		for (Map.Entry<String, State> entry : STATES.entrySet()) {
			Permit permit = entry.getValue()._permit;
			if (permit != null && permit.seconds() > 0
				&& Duration.between(permit.at(), now).toSeconds() <= permit.seconds() + 1) {
				changed(entry.getKey());
			}
		}
	}

	private static void changed(String controllerId) {
		List<Runnable> listeners = LISTENERS.get(controllerId);
		if (listeners == null) {
			return;
		}
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException ex) {
				Logger.error("A listener of the Zigbee live state failed.", ex, ZigbeeLive.class);
			}
		}
	}

}
