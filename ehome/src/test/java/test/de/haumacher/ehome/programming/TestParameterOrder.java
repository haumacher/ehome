/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramPages;
import de.haumacher.ehome.knx.ProgramPages.Page;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Test of the order of the items on a parameter page (haumacher/ehome #88): the parameters in the
 * order of their <code>DisplayOrder</code>, as ETS shows them, a communication object after the
 * parameter that brought it in, on the page "Bedienkonzept" of the Gira Tastsensor 3 of
 * <code>samples/KNX-Testsystem.knxproj</code> (program <code>M-0008_A-10D7-11-A0E5-O000A</code>),
 * whose program places the cases of a key's LED before the field selecting them.
 */
@SuppressWarnings("javadoc")
public class TestParameterOrder extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000A";

	private static final String PAGE = "Bedienkonzept";

	/** The page with <code>Funktion_T1T2 = 1</code> and <code>Status_LED_T3 = 5</code>. */
	private static final List<String> SET = List.of(
		"300 Bedienkonzept",
		"310 Bedienkonzept der Tasten 1 und 2",
		"311 Tastenauswertung",
		"320 Bedienkonzept der Tasten 3 und 4",
		"330 Bedienkonzept der Tasten 5 und 6",
		"1970 Funktion der Status-LED links",
		"2970 Funktion der Status-LED rechts",
		"3970 Funktion der Status-LED links",
		"#38",
		"3972 Ansteuerung der Status-LED über Objektwert",
		"4970 Funktion der Status-LED rechts",
		"5970 Funktion der Status-LED links",
		"6970 Funktion der Status-LED rechts");

	/** The page with the default values. */
	private static final List<String> DEFAULTS = List.of(
		"300 Bedienkonzept",
		"310 Bedienkonzept der Tasten 1 und 2",
		"320 Bedienkonzept der Tasten 3 und 4",
		"330 Bedienkonzept der Tasten 5 und 6",
		"1970 Funktion der Status-LED links",
		"2970 Funktion der Status-LED rechts",
		"3970 Funktion der Status-LED links",
		"4970 Funktion der Status-LED rechts",
		"5970 Funktion der Status-LED links",
		"6970 Funktion der Status-LED rechts");

	public void testOrder() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			TLObject device = device(project);
			clearSettings(device);
			assertEquals(DEFAULTS, describe(items(device)));

			set(device, "Funktion_T1T2", 1);
			set(device, "Status_LED_T3", 5);
			assertEquals(SET, describe(items(device)));

			// The change list shows the items of a page in the same order.
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			ConfigFragment page = page(configuration, device);
			List<Option> shown = new ArrayList<>();
			configuration.getShownItems().forEach((item, on) -> {
				if (on == page) {
					shown.add(item);
				}
			});
			assertEquals(SET, describe(shown));

			// The catalog lists the parameters of the page by their display order.
			ProgramPages pages = new ProgramPages(DeviceConfiguration.program(device));
			Parameter title = ((ParameterGroup) page).getInput().getParameter();
			Page catalogPage = pages.getPages().stream()
				// The page by its title parameter: the title text depends on the locale.
				.filter(p -> p.getParameters().containsKey(title))
				.findFirst().get();
			List<Integer> orders = new ArrayList<>();
			for (ParameterView view : catalogPage.getParameters().values()) {
				orders.add(view.getOrder());
			}
			List<Integer> sorted = new ArrayList<>(orders);
			sorted.sort(null);
			assertEquals(sorted, orders);
			assertEquals(Integer.valueOf(300), orders.get(0));
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static List<Option> items(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		return configuration.getItems(page(configuration, device));
	}

	private static ConfigFragment page(DeviceConfiguration configuration, Object page) {
		for (ConfigFragment inner : configuration.getPages(page)) {
			if (inner instanceof ParameterGroup group && PAGE.equals(group.getName())) {
				return inner;
			}
			ConfigFragment result = page(configuration, inner);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	private static List<String> describe(List<Option> items) {
		List<String> result = new ArrayList<>();
		for (Option item : items) {
			if (item instanceof ParameterView view) {
				result.add(view.getOrder() + " " + text(view));
			} else {
				result.add("#" + ((Interface) item).getPort().getIndex());
			}
		}
		return result;
	}

	private static String text(ParameterView view) {
		Object text = view.tValueByName("description");
		if (text == null) {
			text = view.getParameter().tValueByName("description");
		}
		// The texts of the ticket, in German whatever the locale of the suite; ETS breaks and indents
		// texts with white space.
		return Resources.getInstance(Locale.GERMAN).getString((ResKey) text).trim().replaceAll("\\s+", " ");
	}

	private static void set(TLObject device, String name, int value) {
		for (Option item : items(device)) {
			if (item instanceof ParameterView view && name.equals(view.getParameter().getName())) {
				try (Transaction tx = kb().beginTransaction()) {
					DeviceSettings.setValue(device, view, Integer.valueOf(value));
					tx.commit();
				}
				return;
			}
		}
		fail("Not on the page: " + name);
	}

	private static void clearSettings(TLObject device) {
		try (Transaction tx = kb().beginTransaction()) {
			for (Object setting : new ArrayList<>((Collection<?>) device.tValueByName("settings"))) {
				((TLObject) setting).tDelete();
			}
			tx.commit();
		}
	}

	private static TLObject device(TLObject project) {
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					Program program = DeviceConfiguration.program((TLObject) device);
					if (program != null && PROGRAM.equals(program.getExtId())) {
						return (TLObject) device;
					}
				}
			}
		}
		throw new AssertionError("No device of " + PROGRAM);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterOrder.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
