/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.top_logic.basic.Logger;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

/**
 * The controllers connected to E-Home (haumacher/ehome #54): the authenticated
 * {@link ControllerConnection} of each controller id, and what follows from a connection for the
 * knowledge base.
 *
 * <p>
 * A controller has at most one connection: when it connects again (after a network failure the old
 * socket may still look open), the older one is closed. The online state is not stored: it is whether
 * the registry holds a connection, so a restart of E-Home cannot leave a controller shown online.
 * What is stored with the controller (<code>ehome.runtime:Controller</code>) is the last contact (at
 * the connection and at its end) and the firmware version and build it reported.
 * </p>
 *
 * <p>
 * Threads: the connections report from the threads reading their sockets; every write to the
 * knowledge base goes to one thread of its own ({@link #execute(Runnable)}), in a system interaction
 * and a transaction, so the socket threads never wait for a commit. A watchdog closes a socket that
 * did not authenticate within {@link #AUTH_TIMEOUT}; the container closes one that received nothing
 * (no message, no ping) within {@link #IDLE_TIMEOUT} (the controller pings every 30 s).
 * </p>
 */
public final class ControllerConnections {

	/** The time a controller has to authenticate after the socket opened. */
	public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);

	/** The time without any message after which a connection counts as dead. */
	public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);

	/**
	 * What the authentication needs to know of a controller.
	 *
	 * @param secret
	 *        The secret (64 hex digits).
	 * @param name
	 *        The name of the controller in E-Home.
	 * @param installation
	 *        The installation's name sent in <code>ready</code>.
	 * @param publicKey
	 *        The public key the controller should hold ({@link InstallationKeys#target(TLObject)} of its
	 *        project), offered to it after <code>ready</code> when it holds another; <code>null</code>
	 *        for a project without a key.
	 * @param keyFingerprint
	 *        The fingerprint of that key, <code>null</code> for none.
	 */
	public record Credentials(String secret, String name, String installation, String publicKey,
			String keyFingerprint) {
		// A record.
	}

	/**
	 * Receiver of what happens to all connections: a hook for the parts of E-Home following the
	 * controllers (the Zigbee device list, ...). Called on the socket threads: it must not block.
	 */
	public interface Listener {

		/** A controller connected and was accepted. */
		default void connected(ControllerConnection connection) {
			// Ignored by default.
		}

		/** A message of a controller's feed (see {@link ControllerConnection.Listener}). */
		default void message(ControllerConnection connection, String type, Map<String, Object> message) {
			// Ignored by default.
		}

		/** The connection of a controller ended. */
		default void disconnected(ControllerConnection connection, String reason) {
			// Ignored by default.
		}
	}

	private static final Map<String, ControllerConnection> CONNECTIONS = new ConcurrentHashMap<>();

	private static final List<ControllerConnection> OPENING = new CopyOnWriteArrayList<>();

	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private static final ExecutorService KB = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "controller-connections");
		thread.setDaemon(true);
		return thread;
	});

	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "controller-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	static {
		WATCHDOG.scheduleWithFixedDelay(ControllerConnections::watch, 1, 1, TimeUnit.SECONDS);
	}

	private ControllerConnections() {
		// Static registry.
	}

	/**
	 * The connection of the controller with the given id, <code>null</code> if it is not connected.
	 */
	public static ControllerConnection get(String controllerId) {
		if (controllerId == null) {
			return null;
		}
		ControllerConnection connection = CONNECTIONS.get(controllerId);
		return connection != null && connection.isReady() ? connection : null;
	}

	/**
	 * The connection of the given controller (<code>ehome.runtime:Controller</code>),
	 * <code>null</code> if it is not connected.
	 */
	public static ControllerConnection get(TLObject controller) {
		return get(controllerId(controller));
	}

	/**
	 * The id of an enrolled controller, <code>null</code> for one registered by host.
	 */
	public static String controllerId(TLObject controller) {
		if (controller == null || !controller.tValid()) {
			return null;
		}
		Object id = controller.tValueByName("controllerId");
		return id == null || id.toString().isBlank() ? null : id.toString();
	}

	/**
	 * Adds a receiver of what happens to all connections.
	 */
	public static void addListener(Listener listener) {
		LISTENERS.add(listener);
	}

	/**
	 * Removes a receiver added with {@link #addListener(Listener)}.
	 */
	public static void removeListener(Listener listener) {
		LISTENERS.remove(listener);
	}

	/**
	 * Runs a job writing the knowledge base on the registry's thread, in a system interaction.
	 */
	public static void execute(Runnable job) {
		KB.execute(() -> {
			try {
				ThreadContext.inSystemContext(ControllerConnections.class, () -> {
					refetch();
					job.run();
				});
			} catch (RuntimeException ex) {
				Logger.error("Updating a controller failed.", ex, ControllerConnections.class);
			}
		});
	}

	/**
	 * Runs the job on the thread of {@link #execute(Runnable)} after the jobs given so far, waits for
	 * it, and brings the calling thread's view of the knowledge base up to date: a change to a
	 * controller that must not conflict with the updates of its connection (its deletion).
	 *
	 * @throws RuntimeException
	 *         The job's failure.
	 */
	public static void executeAndWait(Runnable job) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		KB.execute(() -> {
			try {
				ThreadContext.inSystemContext(ControllerConnections.class, () -> {
					refetch();
					job.run();
				});
				done.complete(null);
			} catch (RuntimeException | Error ex) {
				done.completeExceptionally(ex);
			}
		});
		try {
			done.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the controller update.", ex);
		} catch (ExecutionException ex) {
			if (ex.getCause() instanceof RuntimeException failure) {
				throw failure;
			}
			throw new IllegalStateException(ex.getCause());
		} finally {
			refetch();
		}
	}

	/**
	 * Waits until the jobs given to {@link #execute(Runnable)} so far are done, and brings the
	 * calling thread's view of the knowledge base up to date (for tests).
	 */
	public static void awaitKnowledgeBase() throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		KB.execute(done::countDown);
		done.await(30, TimeUnit.SECONDS);
		refetch();
	}

	/**
	 * Brings the calling thread's view of the knowledge base up to date: a thread outside a user's
	 * request sees the commits of other threads only after a refetch.
	 */
	public static void refetch() {
		try {
			PersistencyLayer.getKnowledgeBase().refetch();
		} catch (RefetchTimeout ex) {
			Logger.warn("The knowledge base could not be refetched.", ex, ControllerConnections.class);
		}
	}

	/**
	 * Closes the connection of a forgotten controller (its object was deleted): with
	 * {@link ControllerConnection#REFUSED_FORGOTTEN}, so it does not come back.
	 */
	public static void forget(String controllerId) {
		ControllerConnection connection = controllerId == null ? null : CONNECTIONS.get(controllerId);
		if (connection != null) {
			connection.refuse(ControllerConnection.REFUSED_FORGOTTEN, "The controller was forgotten in E-Home.");
		}
	}

	/**
	 * A socket was opened: the watchdog checks that it authenticates in time.
	 */
	static void opening(ControllerConnection connection) {
		OPENING.add(connection);
	}

	/**
	 * The credentials of the controller with the given id, <code>null</code> if there is none (read
	 * in a system interaction on the calling thread).
	 */
	static Credentials credentials(String controllerId) {
		return ThreadContext.inSystemContext(ControllerConnections.class, () -> {
			refetch();
			TLObject controller = find(controllerId);
			if (controller == null) {
				return null;
			}
			Object secret = controller.tValueByName("secret");
			if (secret == null || !secret.toString().matches("[0-9a-fA-F]{64}")) {
				return null;
			}
			Signatures.Keys target = InstallationKeys.target(controller.tContainer());
			return new Credentials(secret.toString(), Objects.toString(controller.tValueByName("name"), ""),
				Objects.toString(controller.tValueByName("installation"), ""),
				target == null ? null : target.publicKey(), target == null ? null : target.fingerprint());
		});
	}

	/**
	 * The enrolled controller with the given id, <code>null</code> if there is none.
	 */
	public static TLObject find(String controllerId) {
		if (controllerId == null) {
			return null;
		}
		TLClass type = (TLClass) TLModelUtil.findType("ehome.runtime:Controller");
		for (TLObject controller : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (controllerId.equals(controller.tValueByName("controllerId"))) {
				return controller;
			}
		}
		return null;
	}

	/**
	 * A controller was accepted.
	 */
	static void ready(ControllerConnection connection) {
		OPENING.remove(connection);
		ControllerConnection before = CONNECTIONS.put(connection.controllerId(), connection);
		if (before != null && before != connection) {
			before.close(ControllerConnection.CLOSE_NORMAL, ControllerConnection.CLOSED_REPLACED);
		}
		Logger.info("Controller " + connection.controllerId() + " (" + connection.name() + ") connected, firmware "
			+ connection.version() + " " + connection.build() + ".", ControllerConnections.class);
		Instant now = connection.since();
		String version = connection.version();
		String build = connection.build();
		execute(() -> store(connection.controllerId(), now, version, build));
		deviceKey(connection);
		for (Listener listener : LISTENERS) {
			try {
				listener.connected(connection);
			} catch (RuntimeException ex) {
				Logger.error("A listener of the controller connections failed.", ex, ControllerConnections.class);
			}
		}
	}

	/**
	 * The controller named the installation key it holds (its <code>auth</code> or
	 * <code>key_accepted</code>): stored, and a rotation it completes ends
	 * ({@link InstallationKeys#deviceKey(String, String)}).
	 */
	static void deviceKey(ControllerConnection connection) {
		String fingerprint = connection.deviceKey();
		execute(() -> InstallationKeys.deviceKey(connection.controllerId(), fingerprint));
	}

	/**
	 * The controller reported another firmware version or build.
	 */
	static void firmwareChanged(ControllerConnection connection) {
		String version = connection.version();
		String build = connection.build();
		execute(() -> store(connection.controllerId(), null, version, build));
	}

	static void message(ControllerConnection connection, String type, Map<String, Object> message) {
		for (Listener listener : LISTENERS) {
			try {
				listener.message(connection, type, message);
			} catch (RuntimeException ex) {
				Logger.error("A listener of the controller connections failed.", ex, ControllerConnections.class);
			}
		}
	}

	/**
	 * The connection of an accepted controller ended.
	 */
	static void closed(ControllerConnection connection, String reason) {
		OPENING.remove(connection);
		if (!CONNECTIONS.remove(connection.controllerId(), connection)) {
			// Replaced by a newer connection, which is the controller's state now.
			return;
		}
		Logger.info("Controller " + connection.controllerId() + " disconnected: " + reason, ControllerConnections.class);
		Instant now = Instant.now();
		execute(() -> store(connection.controllerId(), now, null, null));
		for (Listener listener : LISTENERS) {
			try {
				listener.disconnected(connection, reason);
			} catch (RuntimeException ex) {
				Logger.error("A listener of the controller connections failed.", ex, ControllerConnections.class);
			}
		}
	}

	/**
	 * A socket ended before its controller was accepted.
	 */
	static void dropped(ControllerConnection connection) {
		OPENING.remove(connection);
	}

	private static void store(String controllerId, Instant contact, String version, String build) {
		TLObject controller = find(controllerId);
		if (controller == null) {
			return;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			if (contact != null) {
				controller.tUpdateByName("lastContact", Date.from(contact));
			}
			if (version != null && !version.equals(controller.tValueByName("firmwareVersion"))) {
				controller.tUpdateByName("firmwareVersion", version);
			}
			if (build != null && !build.equals(controller.tValueByName("firmwareBuild"))) {
				controller.tUpdateByName("firmwareBuild", build);
			}
			tx.commit();
		}
	}

	private static void watch() {
		try {
			long now = System.nanoTime();
			for (ControllerConnection connection : new ArrayList<>(OPENING)) {
				if (connection.isReady()) {
					OPENING.remove(connection);
				} else if (now - connection.opened() > AUTH_TIMEOUT.toNanos()) {
					OPENING.remove(connection);
					connection.refuse(ControllerConnection.REFUSED_TIMEOUT, "No authentication within "
						+ AUTH_TIMEOUT.toSeconds() + " s.");
				}
			}
		} catch (RuntimeException ex) {
			Logger.error("The watchdog of the controller connections failed.", ex, ControllerConnections.class);
		}
	}

}
