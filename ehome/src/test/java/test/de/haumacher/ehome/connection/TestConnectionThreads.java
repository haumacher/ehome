/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.ControllerConnectionsRegistration;
import de.haumacher.ehome.zigbee.ZigbeeLiveRegistration;

/**
 * The threads of the controller connections and of the Zigbee live import end with the application
 * (haumacher/ehome #81): Tomcat reported <code>controller-watchdog</code> and <code>zigbee-live</code>
 * running after every undeploy, one more pair per deployment.
 *
 * <p>
 * The listeners of <code>WEB-INF/web.xml</code> are started and ended in the container's order (the
 * end in reverse): after the end no thread of theirs is alive, also when the end follows the start at
 * once, and when it comes without a start (the start of the application failed before them). A job
 * given after the end is dropped and starts no thread again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestConnectionThreads extends BasicTestCase {

	private static final Set<String> NAMES = Set.of("controller-connections", "controller-watchdog", "zigbee-live");

	private final ControllerConnectionsRegistration _connections = new ControllerConnectionsRegistration();

	private final ZigbeeLiveRegistration _zigbee = new ZigbeeLiveRegistration();

	@Override
	protected void tearDown() throws Exception {
		// The registry serves the following tests again, as before its first end.
		ControllerConnections.start();
		super.tearDown();
	}

	public void testStopAfterUse() throws Exception {
		start();
		ControllerConnections.awaitKnowledgeBase();
		assertEquals(NAMES, Set.copyOf(threads()));
		stop();
		assertEquals(List.of(), threads());
	}

	public void testStopRightAfterStart() {
		start();
		stop();
		assertEquals(List.of(), threads());
	}

	public void testStopWithoutStart() {
		stop();
		assertEquals(List.of(), threads());
		stop();
		assertEquals(List.of(), threads());
	}

	public void testNoRestartAfterStop() throws Exception {
		start();
		stop();
		AtomicBoolean ran = new AtomicBoolean();
		ControllerConnections.execute(() -> ran.set(true));
		ControllerConnections.awaitKnowledgeBase();
		assertFalse(ran.get());
		assertEquals(List.of(), threads());
	}

	private void start() {
		_connections.contextInitialized(null);
		_zigbee.contextInitialized(null);
	}

	private void stop() {
		_zigbee.contextDestroyed(null);
		_connections.contextDestroyed(null);
	}

	private static List<String> threads() {
		return Thread.getAllStackTraces().keySet().stream()
			.filter(Thread::isAlive)
			.map(Thread::getName)
			.filter(NAMES::contains)
			.sorted()
			.toList();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestConnectionThreads.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
