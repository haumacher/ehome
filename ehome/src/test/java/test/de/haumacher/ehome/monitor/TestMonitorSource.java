/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.base.context.SubSessionListener;
import com.top_logic.base.context.TLSubSessionContext;
import com.top_logic.basic.listener.Registration;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.MonitorSource;
import de.haumacher.ehome.monitor.ProjectIndex;

/**
 * The source of the group monitor's view ({@link MonitorSource}): a displayed view gets the current
 * snapshot at once and one per change, in the language of its user; a view hidden in an open window
 * keeps the monitor attached to the bus; once no open window shows the monitor any more, it is
 * detached: when the sub-session of the last window that hid the view ends, or when the view goes
 * after its window ended, whichever comes last (TL #30088). Costs well below a second: an empty project, a mocked bus.
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

		Window first = new Window();
		Window second = new Window();

		// Displayed: the current snapshot at once, one per change, in the language of the user.
		List<Map<String, Object>> shown = new ArrayList<>();
		Registration view = MonitorSource.subscribe(monitor, first.context(), sink(shown),
			Resources.getInstance(Locale.GERMAN));
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
		pause();
		assertTrue(monitor.isConnected());
		assertEquals(1, monitor.getPublishers().size());
		assertEquals(1, first._listeners.size());

		// Displayed again in the same window: the window holds the monitor once, not twice.
		view = MonitorSource.subscribe(monitor, first.context(), sink(shown), Resources.getInstance(Locale.GERMAN));
		assertEquals(3, shown.size());
		assertEquals(2, monitor.getPublishers().size());
		assertEquals(1, first._listeners.size());
		view.dispose();

		// A window ending while its view is displayed, while another window hides the view: still
		// attached, also after the view of the ended window goes.
		Registration gone = MonitorSource.subscribe(monitor, second.context(), sink(new ArrayList<>()),
			Resources.getInstance(Locale.GERMAN));
		second.end();
		gone.dispose();
		pause();
		assertTrue(monitor.isConnected());

		// The window hiding the view ends: the monitor is detached.
		first.end();
		awaitDisconnect(monitor, link);
		assertTrue(monitor.getPublishers().isEmpty());

		// A view going after the only window that shows the monitor ended detaches it.
		MockBusLink again = new MockBusLink();
		monitor.connect(again);
		Window last = new Window();
		Registration lastView = MonitorSource.subscribe(monitor, last.context(), sink(new ArrayList<>()),
			Resources.getInstance(Locale.GERMAN));
		last.end();
		pause();
		assertTrue("The view is still displayed.", monitor.isConnected());
		lastView.dispose();
		awaitDisconnect(monitor, again);

		// Without a window, only the displayed view keeps the monitor attached.
		MockBusLink alone = new MockBusLink();
		monitor.connect(alone);
		MonitorSource.subscribe(monitor, null, sink(new ArrayList<>()), Resources.getInstance(Locale.GERMAN))
			.dispose();
		awaitDisconnect(monitor, alone);
	}

	/**
	 * The sub-session of a window, of which the monitor uses only its unbound listeners.
	 */
	private static final class Window {

		final List<SubSessionListener> _listeners = new ArrayList<>();

		private final TLSubSessionContext _context = (TLSubSessionContext) Proxy.newProxyInstance(
			TLSubSessionContext.class.getClassLoader(), new Class<?>[] { TLSubSessionContext.class },
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "addUnboundListener":
						_listeners.add((SubSessionListener) args[0]);
						return null;
					case "removeUnboundListener":
						_listeners.remove(args[0]);
						return null;
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});

		TLSubSessionContext context() {
			return _context;
		}

		/** The window is torn down, or its session ends. */
		void end() {
			List<SubSessionListener> listeners = new ArrayList<>(_listeners);
			_listeners.clear();
			for (SubSessionListener listener : listeners) {
				listener.notifySubSessionUnbound(_context);
			}
		}
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
