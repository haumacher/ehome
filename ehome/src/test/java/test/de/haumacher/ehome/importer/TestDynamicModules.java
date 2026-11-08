/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.FillTextParameter;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * The import of dynamic modules (haumacher/ehome #119) with the artificial fixture
 * <code>src/test/fixtures/TestModules.knxproj</code> (see <code>make-fixtures.py</code>): a program
 * with one module definition placed twice ("Kanal A" at the top of the dynamic part, "Kanal B" in a
 * case of a switch), and a device with settings and objects of both instances.
 *
 * <ul>
 * <li>The expansion: object numbers, memory offsets, texts in both languages, the IDs of the
 * instances, the place of the structure, the device's settings and objects with their group
 * addresses, the labels filling <code>{{0}}</code>; a second import duplicates nothing.</li>
 * <li>The completion of the program as an earlier import left it (marked, without the parts of its
 * modules): an import of the file adds them at their places and removes the mark.</li>
 * <li>A device planned in the app: the module parameters with their defaults in the configuration,
 * their values in the download image.</li>
 * </ul>
 *
 * <p>
 * Runs in the database shared with the other tests: it checks the objects of its project and the
 * program of its fixture by their IDs, and deletes its projects again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDynamicModules extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestModules.knxproj";

	private static final String PROGRAM = "M-0008_A-FFF7-01-0000";

	private static final String P = PROGRAM + "_";

	/** The parts of the instance "Kanal A". */
	private static final String A = P + "MD-1_M-1_MI-1_";

	/** The parts of the instance "Kanal B". */
	private static final String B = P + "MD-1_M-2_MI-1_";

	private static final String MODULE_PART = "_MD-";

	public void testImport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = importFixture();
			assertEquals(List.of(), warnings(importer));
			checkProgram();
			checkDevice(device(before));

			// A second import reuses the program with its expanded parts: nothing is duplicated.
			List<Integer> counts = programCounts();
			Importer again = importFixture();
			assertEquals(List.of(), warnings(again));
			assertEquals(counts, programCounts());
			assertTrue(again.getCreated("ehome.operational:Program").isEmpty());
			assertTrue(again.getCreated("ehome.operational:Parameter").isEmpty());
			assertTrue(again.getCreated("ehome.operational:Port").isEmpty());
			assertEquals(List.of(program()), again.getKnown("ehome.operational:Program"));
			SharedData.deleteProjects(SharedData.newProjects(before));
			checkProgram();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The program as an earlier import left it (marked, without the parts of its modules) is completed
	 * by an import of the file; the devices of the project imported with it get their module settings
	 * and objects.
	 */
	public void testCompletion() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			SharedData.deleteProjects(SharedData.newProjects(before));
			stripModules();
			Program program = program();
			assertTrue(program.getUsesModules());
			assertEquals(2, program.getContents().size());
			assertEquals(1, program.getPorts().size());
			assertEquals(2, program.getParameters().size());

			Importer importer = importFixture();
			assertEquals(List.of(), warnings(importer));
			String completed = String.valueOf(I18NConstants.INFO_MODULES_COMPLETED__PROGRAM.fill(PROGRAM));
			assertTrue(importer.getMessages().stream().anyMatch(
				m -> m.getLevel() == Level.INFO && String.valueOf(m.getMessage()).contains(completed)));
			assertTrue(importer.getCreated("ehome.operational:Program").isEmpty());
			checkProgram();
			checkDevice(device(before));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A device planned in the app (haumacher/ehome #104) of the program: the parameters of its modules
	 * with their defaults in its configuration and in its download image.
	 */
	public void testPlanning() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			TLObject imported = device(before);
			TLObject planned;
			try (Transaction tx = kb().beginTransaction()) {
				planned = DevicePlanning.create(imported.tContainer(), (TLObject) imported.tValueByName("type"),
					(TLObject) imported.tValueByName("program"), Integer.valueOf(2), "TEST geplant", null);
				tx.commit();
			}
			assertTrue(list(planned, "settings").isEmpty());

			DeviceConfiguration configuration = new DeviceConfiguration(planned);
			Map<?, Object> shown = configuration.getShownItems();
			TLObject mode = byId(shown.keySet(), A + "P-1_R-1");
			TLObject description = byId(shown.keySet(), A + "P-2_R-1");
			assertNotNull(shown.keySet().toString(), mode);
			assertNotNull(description);
			assertEquals(A + "CH-1", channel(shown.get(mode)));
			// "Kanal B" lies in the case of the switch "Kanal B" = "Ein"; its default is "Aus".
			assertNull(byId(shown.keySet(), B + "P-1_R-1"));
			TLObject defaultMode = (TLObject) DeviceConfiguration.valueHolder(planned,
				(de.haumacher.ehome.model.operational.ParameterView) mode).tValueByName("choice");
			assertEquals(Integer.valueOf(2), defaultMode.tValueByName("value"));

			DownloadPlan plan = DownloadPlanner.plan(planned, DownloadPlan.Kind.FULL);
			assertTrue(plan.getProblems().toString(), plan.isExecutable());
			DownloadImage image = plan.getImage();
			// "Modus" = 2 (Treppenlicht): 2 bits at bit offset 4 of byte 0x60 + 2 of the segment at 0x100;
			// "Beschreibung" = "Flur" in the 4 bytes from 0x60 + 3.
			assertEquals(image.dump(), Integer.valueOf(0x08), image.byteAt(0x162));
			assertEquals(Integer.valueOf('F'), image.byteAt(0x163));
			assertEquals(Integer.valueOf('r'), image.byteAt(0x166));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The text of a communication object filled with the value of its text parameter.
	 */
	public void testFillTextParameter() {
		assertEquals("Kanal B: WZ", FillTextParameter.fill("Kanal B: {{0}}", "WZ"));
		assertEquals("Kanal B", FillTextParameter.fill("Kanal B: {{0}}", ""));
		assertEquals("Kanal B", FillTextParameter.fill("Kanal B - {{0}}", null));
		assertEquals("Kanal B: frei", FillTextParameter.fill("Kanal B: {{0:frei}}", ""));
		assertEquals("Kanal B: 3", FillTextParameter.fill("Kanal B: {{0}}", Double.valueOf(3)));
		assertEquals("Wert $1", FillTextParameter.fill("Wert {{0}}", "$1"));
		assertEquals("Kanal {{ChNo}}", FillTextParameter.fill("Kanal {{ChNo}}", "WZ"));
		assertNull(FillTextParameter.fill(null, "WZ"));
	}

	private void checkProgram() {
		Program program = program();
		assertFalse(program.getUsesModules());

		// Object numbers: Number + ObjNumberBase (A: 1, B: 3).
		assertEquals(Integer.valueOf(0), part(program.getPorts(), P + "O-0").tValueByName("index"));
		assertEquals(Integer.valueOf(1), part(program.getPorts(), A + "O-2-0").tValueByName("index"));
		assertEquals(Integer.valueOf(2), part(program.getPorts(), A + "O-2-1").tValueByName("index"));
		assertEquals(Integer.valueOf(3), part(program.getPorts(), B + "O-2-0").tValueByName("index"));
		assertEquals(Integer.valueOf(4), part(program.getPorts(), B + "O-2-1").tValueByName("index"));
		assertEquals(5, program.getPorts().size());

		// Memory: Offset + ParamOffsBase (A: 0x60, B: 0x70), the bit offset kept.
		assertLocation(part(program.getParameters(), A + "P-1"), 0x62, 4);
		assertLocation(part(program.getParameters(), A + "P-2"), 0x63, 0);
		assertLocation(part(program.getParameters(), B + "P-1"), 0x72, 4);
		assertLocation(part(program.getParameters(), B + "P-2"), 0x73, 0);
		assertEquals(6, program.getParameters().size());

		// The name of the channel substituted in every language.
		TLObject port = part(program.getPorts(), A + "O-2-0");
		assertEquals("Kanal A: Schalten", text(port, "text", Locale.GERMAN));
		assertEquals("Channel A: Switching", text(port, "text", Locale.ENGLISH));
		assertEquals("Kanal B: Status", text(part(program.getPorts(), B + "O-2-1"), "text", Locale.GERMAN));
		assertEquals("Channel B: Mode", text(part(program.getParameters(), B + "P-1"), "description", Locale.ENGLISH));

		// The structure: "Kanal A" in place of its module, "Kanal B" in the case of the switch.
		List<?> contents = program.getContents();
		assertEquals(List.of("ehome.operational:ChannelIndependent", "ehome.operational:Channel",
			"ehome.operational:Switch"), types(contents));
		TLObject channelA = (TLObject) contents.get(1);
		assertEquals(A + "CH-1", channelA.tValueByName("extId"));
		assertEquals("Kanal A", text(channelA, "description", Locale.GERMAN));
		assertEquals("Channel A", text(channelA, "description", Locale.ENGLISH));
		List<TLObject> cases = list((TLObject) contents.get(2), "alternatives");
		assertEquals(2, cases.size());
		List<TLObject> inCase = list(cases.get(0), "contents");
		assertEquals(1, inCase.size());
		assertEquals(B + "CH-1", inCase.get(0).tValueByName("extId"));
		assertTrue(list(cases.get(1), "contents").isEmpty());

		// The block of the channel: the references of the instance, its switch, its object.
		TLObject block = list(channelA, "contents").get(0);
		assertEquals(A + "PB-1", block.tValueByName("extId"));
		assertEquals("Kanal A", text(block, "description", Locale.GERMAN));
		List<TLObject> items = list(block, "contents");
		assertEquals(Arrays.asList(A + "P-1_R-1", A + "P-2_R-1", null, A + "O-2-1_R-1"),
			items.stream().map(i -> (String) i.tValueByName("extId")).toList());
		TLObject choose = items.get(2);
		assertEquals(A + "P-1_R-1", ((TLObject) choose.tValueByName("input")).tValueByName("extId"));
		TLObject placed = list(list(choose, "alternatives").get(0), "contents").get(0);
		assertEquals(A + "O-2-0_R-1", placed.tValueByName("extId"));
		assertEquals(A + "P-2_R-1", ((TLObject) placed.tValueByName("textParameter")).tValueByName("extId"));
		assertEquals("Kanal A: {{0}}", text(placed, "name", Locale.GERMAN));
		assertEquals("Channel A: {{0}}", text(placed, "name", Locale.ENGLISH));
		assertTrue(((Collection<?>) program.tValueByName("unplaced")).isEmpty());
	}

	private void checkDevice(TLObject device) {
		// The settings of the program and of the instance "Kanal A".
		Map<String, TLObject> settings = list(device, "settings").stream()
			.collect(Collectors.toMap(s -> (String) ((TLObject) s.tValueByName("parameter")).tValueByName("extId"), s -> s));
		assertEquals(Set.of(P + "P-1_R-1", A + "P-1_R-1", A + "P-2_R-1", B + "P-2_R-1"), settings.keySet());
		assertEquals(Integer.valueOf(1),
			((TLObject) settings.get(A + "P-1_R-1").tValueByName("choice")).tValueByName("value"));
		assertEquals("WZ", settings.get(A + "P-2_R-1").tValueByName("text"));

		// The objects with their group addresses.
		Map<String, TLObject> connectors = list(device, "connectors").stream()
			.collect(Collectors.toMap(c -> (String) ((TLObject) c.tValueByName("port")).tValueByName("extId"), c -> c));
		assertEquals(Set.of(P + "O-0_R-1", A + "O-2-0_R-1", A + "O-2-1_R-1", B + "O-2-0_R-1"), connectors.keySet());
		assertEquals(Set.of("send P-01A0-0_GA-1"), connections(connectors.get(P + "O-0_R-1")));
		assertEquals(Set.of("send P-01A0-0_GA-2"), connections(connectors.get(A + "O-2-0_R-1")));
		assertEquals(Set.of("send P-01A0-0_GA-3"), connections(connectors.get(A + "O-2-1_R-1")));
		assertEquals(Set.of("send P-01A0-0_GA-4", "receive P-01A0-0_GA-1"), connections(connectors.get(B + "O-2-0_R-1")));

		// The labels: {{0}} filled with the device's value of the text parameter, its default, or left
		// out with an empty value; in a program without modules as well (the object "Zentral").
		assertEquals("0 Zentral Haus: Ein/Aus", label(connectors.get(P + "O-0_R-1"), Locale.GERMAN));
		assertEquals("1 Kanal A: WZ: Ein/Aus", label(connectors.get(A + "O-2-0_R-1"), Locale.GERMAN));
		assertEquals("2 Kanal A: Status: Status", label(connectors.get(A + "O-2-1_R-1"), Locale.GERMAN));
		assertEquals("3 Kanal B: Ein/Aus", label(connectors.get(B + "O-2-0_R-1"), Locale.GERMAN));
	}

	/**
	 * Removes what the expansion of the modules added to the program, and marks it: the program as an
	 * import before haumacher/ehome #119 left it.
	 */
	private static void stripModules() {
		Program program = program();
		try (Transaction tx = kb().beginTransaction()) {
			for (Object option : new ArrayList<>(program.getContents())) {
				TLObject obj = (TLObject) option;
				if (isModulePart(obj)) {
					obj.tDelete();
					continue;
				}
				for (TLObject alternative : list(obj, "alternatives")) {
					for (TLObject inner : list(alternative, "contents")) {
						if (isModulePart(inner)) {
							inner.tDelete();
						}
					}
				}
			}
			for (TLObject parameter : new ArrayList<TLObject>(program.getParameters())) {
				if (isModulePart(parameter)) {
					TLObject location = (TLObject) parameter.tValueByName("address");
					parameter.tDelete();
					location.tDelete();
				}
			}
			for (TLObject port : new ArrayList<TLObject>(program.getPorts())) {
				if (isModulePart(port)) {
					port.tDelete();
				}
			}
			program.setUsesModules(true);
			tx.commit();
		}
	}

	private static boolean isModulePart(TLObject obj) {
		Object id = obj.tValueByName("extId");
		return id != null && id.toString().contains(MODULE_PART);
	}

	private static Importer importFixture() throws Exception {
		KnowledgeBase kb = kb();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(FIXTURE)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static List<ResKey> warnings(Importer importer) {
		List<BufferingI18NLog.Entry> messages = TestEtsImport.withoutMasterData(importer.getMessages());
		return messages.stream().filter(m -> m.getLevel() == Level.WARN || m.getLevel() == Level.ERROR)
			.map(BufferingI18NLog.Entry::getMessage).toList();
	}

	private static List<Integer> programCounts() {
		Program program = program();
		int views = program.getParameters().stream().mapToInt(p -> p.getViews().size()).sum();
		int interfaces = program.getPorts().stream().mapToInt(p -> p.getInterfaces().size()).sum();
		return List.of(instances("ehome.operational:Program").size(), instances("ehome.operational:Parameter").size(),
			instances("ehome.operational:Port").size(), instances("ehome.operational:ParameterView").size(),
			instances("ehome.operational:Interface").size(), instances("ehome.operational:Channel").size(),
			program.getParameters().size(), program.getPorts().size(), views, interfaces);
	}

	private static Program program() {
		List<TLObject> programs = instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId")))
			.toList();
		assertEquals(1, programs.size());
		return (Program) programs.get(0);
	}

	private static TLObject device(List<TLObject> before) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", SharedData.newProjects(before));
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static void assertLocation(TLObject parameter, int byteOffset, int bitOffset) {
		TLObject location = (TLObject) parameter.tValueByName("address");
		assertEquals(Integer.valueOf(byteOffset), location.tValueByName("byteOffset"));
		assertEquals(Integer.valueOf(bitOffset), location.tValueByName("bitOffset"));
	}

	private static TLObject part(Collection<? extends TLObject> parts, String id) {
		TLObject result = byId(parts, id);
		assertNotNull(id, result);
		return result;
	}

	private static TLObject byId(Collection<?> objects, String id) {
		for (Object obj : objects) {
			if (obj instanceof TLObject tlObject && id != null && id.equals(tlObject.tValueByName("extId"))) {
				return tlObject;
			}
		}
		return null;
	}

	private static String channel(Object page) {
		for (Object current = page; current instanceof TLObject obj; current = obj.tContainer()) {
			if ("ehome.operational:Channel".equals(TLModelUtil.qualifiedName(obj.tType()))) {
				return (String) obj.tValueByName("extId");
			}
		}
		return null;
	}

	private static String text(TLObject obj, String attribute, Locale locale) {
		return ResKeyUtil.getTranslation((ResKey) obj.tValueByName(attribute), locale);
	}

	private static Set<String> connections(TLObject connector) {
		return list(connector, "connections").stream()
			.map(c -> ((TLClassifier) c.tValueByName("kind")).getName() + " "
				+ ((TLObject) c.tValueByName("target")).tValueByName("extId"))
			.collect(Collectors.toSet());
	}

	private static String label(TLObject connector, Locale locale) {
		return inLocale(locale, () -> (String) derived(connector, "displayName"));
	}

	private static <T> T inLocale(Locale locale, Supplier<T> function) {
		TLContext context = TLContext.getContext();
		Locale before = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return function.get();
		} finally {
			context.setCurrentLocale(before);
		}
	}

	private static List<String> types(List<?> objects) {
		return objects.stream().map(o -> TLModelUtil.qualifiedName(((TLObject) o).tType())).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value instanceof Collection<?> collection ? List.copyOf((Collection<TLObject>) collection) : List.of();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDynamicModules.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
