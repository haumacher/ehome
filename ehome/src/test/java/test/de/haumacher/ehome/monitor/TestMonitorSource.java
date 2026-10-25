/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.listener.Registration;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.react.window.ReactWindowRegistry;
import com.top_logic.layout.react.window.WindowEntry;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.MonitorSource;
import de.haumacher.ehome.monitor.MonitorSource.Window;
import de.haumacher.ehome.monitor.ProjectIndex;

/**
 * The source of the group monitor's view (TL #29814, {@link MonitorSource}): a displayed view gets
 * the current snapshot at once and one per change, in the language of its user; a view hidden in an
 * open window keeps the monitor attached to the bus; once no open window shows the monitor any more,
 * it is detached, at once when the view goes with its window, at the next change when the window of a
 * hidden view is closed. Costs well below a second: an empty project, a mocked bus.
 */
@SuppressWarnings("javadoc")
public class TestMonitorSource extends BasicTestCase {

	private static final int SENDER = 0x1305;

	private static final int ADDRESS = 34;

	public void testLifecycle() throws Exception {
		TLObject project;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", "Monitor source");
			tx.commit();
		}
		try {
			check(project);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project) throws Exception {
		GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
		MockBusLink link = new MockBusLink();
		monitor.connect(link);

		ReactWindowRegistry registry = new ReactWindowRegistry("test-session");
		registry.getOrCreateWindow("first");
		Window first = new Window(registry, "first");
		Window second = new Window(registry, "second");
		assertFalse(first.isGone());
		assertTrue(second.isGone());
		assertFalse("Without windows, nothing is ever gone.", new Window(null, "any").isGone());

		// A closed page stays in the windows of the session, unloaded: gone once a reload is no longer
		// expected, back when a page is displayed again.
		WindowEntry unloadedEntry = registry.getOrCreateWindow("unloaded");
		Window unloaded = new Window(registry, "unloaded");
		assertFalse(unloaded.isUnloaded());
		unloadedEntry.markUnloaded();
		long now = System.currentTimeMillis();
		assertTrue(unloaded.isUnloaded());
		assertFalse(unloaded.isGone(now));
		assertTrue(unloaded.isGone(now + MonitorSource.UNLOAD_GRACE));
		unloadedEntry.markDisplayed();
		assertFalse(unloaded.isGone(now + MonitorSource.UNLOAD_GRACE));
		registry.windowClosed("unloaded");

		// Displayed: the current snapshot at once, one per change, in the language of the user.
		List<Map<String, Object>> shown = new ArrayList<>();
		Registration view = MonitorSource.subscribe(monitor, first, sink(shown), Resources.getInstance(Locale.GERMAN));
		assertEquals(1, shown.size());
		assertEquals(Boolean.TRUE, shown.get(0).get("connected"));
		String german = (String) shown.get(0).get("sendingStatus");
		link.write(SENDER, ADDRESS, 0x01);
		assertEquals(2, shown.size());
		assertEquals(1, ((List<?>) shown.get(1).get("log")).size());
		Map<String, Object> english = monitor.snapshot(Resources.getInstance(Locale.ENGLISH));
		assertFalse(german + " / " + english.get("sendingStatus"), german.equals(english.get("sendingStatus")));

		// Hidden in an open window: nothing is published, the monitor stays attached.
		view.dispose();
		link.write(SENDER, ADDRESS, 0x00);
		assertEquals(2, shown.size());
		assertTrue(monitor.isConnected());
		assertEquals(1, monitor.getPublishers().size());

		// Displayed again in the same window: the window holds one publisher, not two.
		view = MonitorSource.subscribe(monitor, first, sink(shown), Resources.getInstance(Locale.GERMAN));
		assertEquals(3, shown.size());
		assertEquals(1, monitor.getPublishers().size());
		view.dispose();

		// A view going with its window while another window hides the view: still attached.
		Registration gone = MonitorSource.subscribe(monitor, second, sink(new ArrayList<>()),
			Resources.getInstance(Locale.GERMAN));
		gone.dispose();
		pause();
		assertTrue(monitor.isConnected());

		// The window hiding the view is closed: the next change detaches the monitor.
		registry.windowClosed("first");
		assertTrue(first.isGone());
		link.write(SENDER, ADDRESS, 0x01);
		awaitDisconnect(monitor, link);
		assertTrue(monitor.getPublishers().isEmpty());

		// A view going with the only window that shows the monitor detaches it at once.
		MockBusLink again = new MockBusLink();
		monitor.connect(again);
		Registration last = MonitorSource.subscribe(monitor, second, sink(new ArrayList<>()),
			Resources.getInstance(Locale.GERMAN));
		last.dispose();
		awaitDisconnect(monitor, again);
	}

	@SuppressWarnings("unchecked")
	private static ChannelSink sink(List<Map<String, Object>> shown) {
		return value -> shown.add((Map<String, Object>) value);
	}

	private static void pause() throws InterruptedException {
		Thread.sleep(100);
	}

	private static void awaitDisconnect(GroupMonitor monitor, MockBusLink link) throws InterruptedException {
		for (int n = 0; n < 100 && monitor.isConnected(); n++) {
			Thread.sleep(20);
		}
		assertFalse(monitor.isConnected());
		assertTrue(link.isClosed());
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMonitorSource.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
