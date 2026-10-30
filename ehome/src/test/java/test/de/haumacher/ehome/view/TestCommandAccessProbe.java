/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.command.ViewCommand;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

/**
 * Probe of the access checks of the views after the migration to TopLogic 8.0.0-alpha12 (TL #29856):
 * every command whose actions change data declares an <code>&lt;access&gt;</code> check, so that a
 * viewer (role <code>ehome.Viewer</code>) cannot run it.
 *
 * <p>
 * The engine checks the access of its own data-changing actions only through the declaration of the
 * command; the Java actions of the app are not access checked at all (CLAUDE.md). A command counts as
 * changing data when one of its actions, at any depth, opens a transaction or is an action of the
 * app; <code>&lt;delete-object&gt;</code> checks the right to delete by itself.
 * </p>
 *
 * <p>
 * The commands of {@link #BASELINE} had no check before the migration either (dialog buttons reached
 * through a checked command, the app's jobs and connections); they are reviewed in haumacher/ehome
 * #65. A new command without a check fails, and so does a baseline entry that got its check.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCommandAccessProbe extends BasicTestCase {

	private static final Path VIEWS = Path.of("src/main/webapp/WEB-INF/views");

	/** Actions of the engine that change data. */
	private static final Set<String> ENGINE_WRITES = Set.of(
		"com.top_logic.layout.view.command.WithTransactionAction");

	/** The commands without a check, as view and the actions found in the command. */
	private static final List<String> BASELINE = List.of(
		"catalogs/create-structure.view.xml: WithTransactionAction",
		"catalogs/create-variant.view.xml: WithTransactionAction",
		"controllers.view.xml: ControllerStatusAction",
		"controllers/controller-token.view.xml: WithTransactionAction",
		"controllers/create-controller.view.xml: WithTransactionAction",
		"controllers/discover-controllers.view.xml: DiscoverControllersAction",
		"discover.view.xml: DiscoverGatewaysAction",
		"projects/choose-gateway.view.xml: DiscoverGatewaysAction",
		"projects/choose-gateway.view.xml: WithTransactionAction",
		"projects/choose-location.view.xml: WithTransactionAction",
		"projects/choose-location.view.xml: WithTransactionAction",
		"projects/connect-controller.view.xml: ConnectMonitorAction",
		"projects/connect-group-address.view.xml: WithTransactionAction",
		"projects/connect-object.view.xml: WithTransactionAction",
		"projects/create-group-address.view.xml: WithTransactionAction",
		"projects/deployment.view.xml: ExportRuntimeImageAction",
		"projects/deployment.view.xml: ControllerStatusAction",
		"projects/device-item.view.xml: WithTransactionAction",
		"projects/device-item.view.xml: WithTransactionAction",
		"projects/device-item.view.xml: WithTransactionAction",
		"projects/device-item.view.xml: WithTransactionAction",
		"projects/import-password.view.xml: ImportEtsProjectAction",
		"projects/monitor.view.xml: ConnectMonitorAction",
		"projects/overview.view.xml: ExportKnxprojAction",
		"projects/place-device.view.xml: WithTransactionAction",
		"projects/scan-choose.view.xml: WithTransactionAction",
		"projects/scan.view.xml: ScanJob",
		"projects/scan.view.xml: WithTransactionAction",
		"projects/scan.view.xml: ReadBackJob",
		"projects/scan.view.xml: LedJob",
		"projects/scan.view.xml: LedJob",
		"projects/set-datapoint-type.view.xml: WithTransactionAction",
		"projects/set-datapoint-type.view.xml: WithTransactionAction",
		"projects/zigbee-bind.view.xml: WithTransactionAction"
	);

	public void testDataChangingCommandsAreChecked() throws Exception {
		List<String> unchecked = new ArrayList<>();
		try (Stream<Path> files = Files.walk(VIEWS)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".view.xml")).sorted()::iterator) {
				String view = VIEWS.relativize(file).toString().replace('\\', '/');
				for (ConfigurationItem item : items(ViewLoader.loadConfig(ViewLoader.fullPath(view)))) {
					if (item instanceof ViewCommand.Config command && command.getAccess().isEmpty()) {
						Set<String> writes = new TreeSet<>();
						for (ConfigurationItem action : items(command)) {
							if (action == command || action instanceof ViewCommand.Config
								|| !(action instanceof PolymorphicConfiguration<?> config)
								|| config.getImplementationClass() == null) {
								continue;
							}
							String name = config.getImplementationClass().getName();
							if (ENGINE_WRITES.contains(name) || name.startsWith("de.haumacher.ehome.")) {
								writes.add(config.getImplementationClass().getSimpleName());
							}
						}
						if (!writes.isEmpty()) {
							unchecked.add(view + ": " + String.join(", ", writes));
						}
					}
				}
			}
		}
		List<String> added = new ArrayList<>(unchecked);
		BASELINE.forEach(added::remove);
		List<String> fixed = new ArrayList<>(BASELINE);
		unchecked.forEach(fixed::remove);
		assertEquals("Commands changing data without an access check:\n" + String.join("\n", added), List.of(), added);
		assertEquals("Commands of the baseline that got their check (remove them from it):\n" + String.join("\n", fixed),
			List.of(), fixed);
	}

	/**
	 * The given configuration and all configurations nested in it.
	 */
	private static List<ConfigurationItem> items(ConfigurationItem root) {
		List<ConfigurationItem> result = new ArrayList<>();
		collect(root, result, new IdentityHashMap<>());
		return result;
	}

	private static void collect(Object value, List<ConfigurationItem> result, Map<Object, Boolean> seen) {
		if (value instanceof ConfigurationItem item) {
			if (seen.put(item, Boolean.TRUE) != null) {
				return;
			}
			result.add(item);
			for (PropertyDescriptor property : item.descriptor().getProperties()) {
				if (property.isDerived()) {
					continue;
				}
				collect(item.value(property), result, seen);
			}
		} else if (value instanceof Iterable<?> list) {
			for (Object element : list) {
				collect(element, result, seen);
			}
		} else if (value instanceof Map<?, ?> map) {
			for (Object element : map.values()) {
				collect(element, result, seen);
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCommandAccessProbe.class,
			ModelService.Module.INSTANCE, SearchBuilder.Module.INSTANCE));
	}

}
