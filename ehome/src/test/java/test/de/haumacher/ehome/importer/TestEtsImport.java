/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.LabelProvider;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.LabelResourceProvider;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GALabelProvider;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.knx.WithinGroupRange;
import de.haumacher.ehome.model.core.Externalized;
import de.haumacher.ehome.model.datapoint.DatapointMainType;
import de.haumacher.ehome.model.datapoint.DatapointSubtype;
import de.haumacher.ehome.model.datapoint.DatapointType;
import de.haumacher.ehome.model.datapoint.DatapointTypes;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.logical.GroupRange;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.physical.Connection;
import de.haumacher.ehome.model.physical.Connector;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.physical.ParameterSetting;
import de.haumacher.ehome.model.project.Project;

/**
 * Test case for {@link Importer} importing the ETS sample project <code>samples/Test.knxproj</code>.
 *
 * <p>
 * The test shares the database with the other tests of the suite, which may have imported the same
 * shared data (master data, catalogs, programs) before: it checks the objects of the projects it
 * imports, and the shared objects by the IDs the imported files define, and deletes its projects
 * again (see {@link SharedData}).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestEtsImport extends BasicTestCase {

	private static final String TEST = "samples/Test.knxproj";

	private static final String DEMO = "samples/EHomeDemo.knxproj";

	private static final String MASTER = "knx_master.xml";

	/** The projects that existed before the test. */
	private List<TLObject> _before;

	/** The projects this test imported so far. */
	private List<TLObject> _projects;

	/** The project of the first import of the Test sample. */
	private TLObject _first;

	public void testImportSample() throws Exception {
		_before = instances("ehome.project:Project");
		try {
			importSamples(_before);
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(_before));
		}
	}

	private void importSamples(List<TLObject> before) throws Exception {
		// The mask versions as the sample's master data define them, whatever other tests imported
		// before: the demo updates them (checkDemo).
		SharedData.resetMaskVersions(Files.readAllBytes(Path.of(TEST)));
		importSample(TEST);

		_projects = SharedData.newProjects(before);
		assertEquals(1, _projects.size());
		TLObject project = _projects.get(0);
		_first = project;
		assertEquals("Test", ((Project) project).getName());
		assertEquals("P-01A0", ((Project) project).getExtId());
		Object style = ((Project) project).getGroupAddressStyle();
		assertNotNull("Group address style not imported.", style);
		assertEquals("ThreeLevel", ((TLClassifier) style).getName());

		List<TLObject> addresses = SharedData.within("ehome.logical:GroupAddress", _projects);
		assertFalse("No group address imported.", addresses.isEmpty());
		for (TLObject address : addresses) {
			Object value = ((GroupAddress) address).getAddress();
			assertInstanceof("Group address not read as GA.", value, GA.class);
			assertEquals(GroupAddressStyle.ThreeLevel, ((GA) value).getStyle());
			// Labels (table cells, search and filter texts) show the same notation.
			LabelProvider labels = LabelProviderService.getInstance().getLabelProvider(value);
			if (labels instanceof LabelResourceProvider adapter) {
				labels = ((LabelResourceProvider.Config<?>) adapter.getConfig()).getLabelProvider();
			}
			assertInstanceof("GA label provider not registered.", labels, GALabelProvider.class);
			assertEquals(value.toString(), MetaLabelProvider.INSTANCE.getLabel(value));
			assertTrue(MetaLabelProvider.INSTANCE.getLabel(value).matches("\\d+/\\d+/\\d+"));
		}

		List<TLObject> programs = instances("ehome.operational:Program");
		assertFalse("No application program imported.", programs.isEmpty());
		for (TLObject program : programs) {
			assertInstanceof("Generated binding not used.", program, Program.class);
		}

		checkDatapointTypes(project, List.of(TEST));
		checkParameterSettings(project);

		// Further imports reuse the standardized datapoint types and add only those missing so far
		// (the master data of the demo project is newer).
		importSample(DEMO);
		checkDatapointTypes(project, List.of(TEST, DEMO));
		TestDownloadDataImport.checkDemo();
		Map<String, Integer> shared = sharedCounts();
		importSample(TEST);
		_projects = SharedData.newProjects(before);
		assertEquals(3, _projects.size());
		checkDatapointTypes(project, List.of(TEST, DEMO));
		assertEquals(shared, sharedCounts());

		// The manufacturer data is shared as well: the program of the Test device is part of both
		// samples and exists only once.
		for (String sample : List.of(TEST, DEMO)) {
			SharedData.assertOnce("ehome.catalog:Manufacturer", SharedData.ids(sample, MASTER, "Manufacturer"));
			SharedData.assertOnce("ehome.catalog:Product", SharedData.ids(sample, "M-[^/]+/Hardware\\.xml", "Hardware"));
			SharedData.assertOnce("ehome.operational:Program",
				SharedData.ids(sample, "M-[^/]+/M-[^/]+_A-[^/]+\\.xml", "ApplicationProgram"));
		}
		for (TLObject device : SharedData.within("ehome.physical:Device", _projects)) {
			assertNotNull(((Device) device).getProgram());
		}

		checkTranslations();
		checkDeviceDetails();
		checkConnections();
	}

	/** A connection is labeled by its group address as displayed and by its direction (issue #3). */
	private void checkConnections() {
		List<TLObject> connections = SharedData.within("ehome.physical:Connection", _projects);
		assertFalse(connections.isEmpty());
		for (TLObject connection : connections) {
			String name = (String) derived(connection, "name");
			assertTrue(name, name.matches("\\d+/\\d+/\\d+ .+ \\((Senden|Empfangen|Send|Receive)\\)"));
		}
	}

	private void checkDeviceDetails() throws Exception {
		// The device of the Test sample was never downloaded.
		TLObject plain = projectObject("ehome.physical:Device", "P-01A0-0_DI-1", _first);
		assertTrue(((Device) plain).getLoaded().isEmpty());
		assertEquals(Boolean.TRUE, derived(plain, "downloadPending"));

		// The fixture adds names, descriptions, the download state, an inactive communication object
		// and an unassigned device to it.
		importSample("src/test/fixtures/TestDetails.knxproj");
		_projects = SharedData.newProjects(_before);
		TLObject device = byTitle("Taster Küche");
		assertEquals("Taster Küche", derived(device, "name"));
		assertEquals("Neben der Tür", ((Device) device).getDescription());
		assertEquals("Unterputzdose links", ((Device) device).getInstallationHints());
		assertEquals("0008:1234ABCD", ((Device) device).getSerialNumber());
		assertNotNull(((Device) device).getConfigurationChanged());
		assertNotNull(((Device) device).getLastDownload());
		assertEquals(5, ((Device) device).getLoaded().size());
		assertEquals(Boolean.FALSE, derived(device, "downloadPending"));

		Collection<? extends Connector> connectors = ((Device) device).getConnectors();
		assertEquals(1, connectors.stream().filter(c -> !c.getActive()).count());
		assertEquals(connectors.size() - 1, connectors.stream().filter(c -> c.getActive()).count());

		TLObject unassigned = byTitle("Taster Reserve");
		TLObject project = unassigned.tContainer();
		assertEquals("ehome.project:Project", TLModelUtil.qualifiedName(project.tType()));
		assertEquals(List.of(unassigned), ((Project) project).getUnassignedDevices());
		assertSame(project, device.tContainer().tContainer().tContainer());

		checkGroups(project);
		checkProgramStructure();
	}

	private void checkProgramStructure() throws Exception {
		// The structure of a program is one tree owned by the program: channels, blocks, switches,
		// and entries placing parameters and communication objects.
		Program program = (Program) byExtId("ehome.operational:Program", "M-0008_A-10D7-11-A0E5-O000A");
		List<?> contents = program.getContents();
		assertEquals(1, contents.size());
		TLObject channel = (TLObject) contents.get(0);
		assertEquals("ehome.operational:Channel", TLModelUtil.qualifiedName(channel.tType()));
		assertSame(program, channel.tContainer());
		// A placed parameter (communication object) is the reference itself, owned by the structure
		// element placing it: 3862 of the 3921 parameter references and all 410 object references.
		List<TLObject> placed = descendants(channel).stream()
			.filter(o -> TLModelUtil.qualifiedName(o.tType()).equals("ehome.operational:ParameterView"))
			.toList();
		assertEquals(3862, placed.size());
		for (TLObject reference : placed) {
			assertNotNull(((ParameterView) reference).getParameter());
			assertTrue(((ParameterView) reference).getParameter().getViews()
				.contains(reference));
		}
		assertEquals(410, descendants(channel).stream()
			.filter(o -> TLModelUtil.qualifiedName(o.tType()).equals("ehome.operational:Interface"))
			.count());
		List<?> unplaced = program.getUnplaced();
		assertEquals(59, unplaced.size());
		for (Object reference : unplaced) {
			assertSame(program, ((TLObject) reference).tContainer());
		}

		// Labels: a switch on a parameter without value that has only a default case groups content;
		// a case names the value of the switch's parameter with its text.
		TLObject grouping = descendants(channel).stream()
			.filter(o -> TLModelUtil.qualifiedName(o.tType()).equals("ehome.operational:Switch"))
			.filter(o -> "M-0008_A-10D7-11-A0E5-O000A_P-3739_R-3739".equals(
				((Switch) o).getInput().getExtId()))
			.findFirst().orElseThrow();
		assertTrue((String) derived(grouping, "displayName"),
			((String) derived(grouping, "displayName")).matches("(Gruppe|Group) Bedienkonzept"));
		TLObject buttons = descendants(grouping).stream()
			.filter(o -> TLModelUtil.qualifiedName(o.tType()).equals("ehome.operational:Switch"))
			.filter(o -> "Bedienkonzept der Tasten 1 und 2".equals(
				((String) derived(((Switch) o).getInput(), "title")).trim()))
			.findFirst().orElseThrow();
		TLObject buttonCase = ((Switch) buttons).getAlternatives().stream()
			.filter(c -> "1".equals(c.getPattern()))
			.map(TLObject.class::cast)
			.findFirst().orElseThrow();
		String caseLabel = (String) derived(buttonCase, "displayName");
		assertTrue(caseLabel, caseLabel.startsWith("= 1 (") && caseLabel.endsWith(")"));

		// A block without a title of its own takes it from its title parameter; its own display wins
		// over that of the title parameter.
		ParameterGroup general = (ParameterGroup) byExtId("ehome.operational:ParameterGroup", "M-0008_A-10D7-11-A0E5-O000A_PB-3762", 0);
		TLObject titleParameter = general.getInput();
		assertNull(general.getDescription());
		assertEquals(derived(titleParameter, "title"), derived(general, "effectiveTitle"));
		assertEquals(derived(general, "effectiveTitle"), derived(general, "displayName"));
		assertNotNull(general.getAccess());
		assertEquals(general.getAccess(), derived(general, "effectiveAccess"));

		// A channel that depends on a parameter: a switch directly in the program, after the channel
		// independent part showing the parameter.
		importSample("src/test/fixtures/TestDynamic.knxproj");
		TestDownloadDataImport.checkFixture();
		Program dynamic = (Program) byExtId("ehome.operational:Program", "M-0008_A-10D7-11-A0E5-O000E");
		List<?> top = dynamic.getContents();
		assertEquals(2, top.size());
		assertEquals("ehome.operational:ChannelIndependent",
			TLModelUtil.qualifiedName(((TLObject) top.get(0)).tType()));
		TLObject choice = (TLObject) top.get(1);
		assertEquals("ehome.operational:Switch", TLModelUtil.qualifiedName(choice.tType()));
		assertEquals("M-0008_A-10D7-11-A0E5-O000E_P-9010_R-9010",
			((Switch) choice).getInput().getExtId());
		List<? extends Case> cases = ((Switch) choice).getAlternatives();
		assertEquals(2, cases.size());
		Case first = cases.get(0);
		assertEquals("1", first.getPattern());
		assertNotSame(Boolean.TRUE, first.getIsDefaultOrNull());
		List<?> caseContents = first.getContents();
		assertEquals(1, caseContents.size());
		assertEquals("ehome.operational:Channel",
			TLModelUtil.qualifiedName(((TLObject) caseContents.get(0)).tType()));
		Case otherwise = cases.get(1);
		assertEquals(Boolean.TRUE, otherwise.getIsDefaultOrNull());
		assertTrue(otherwise.getContents().isEmpty());

		// The known program of the Test sample was not changed by the import of the new version.
		assertEquals(1, program.getContents().size());
	}

	/**
	 * The numbers of the objects of the data shared between projects, by type.
	 */
	private static Map<String, Integer> sharedCounts() {
		Map<String, Integer> result = new TreeMap<>();
		for (String type : List.of("ehome.datapoint:DatapointMainType", "ehome.datapoint:DatapointSubtype",
			"ehome.operational:MaskVersion", "ehome.catalog:Manufacturer", "ehome.catalog:Product",
			"ehome.operational:Program")) {
			result.put(type, instances(type).size());
		}
		return result;
	}

	private static List<TLObject> descendants(TLObject node) {
		List<TLObject> result = new ArrayList<>();
		List<TLObject> todo = new ArrayList<>(List.of(node));
		while (!todo.isEmpty()) {
			TLObject current = todo.remove(todo.size() - 1);
			result.add(current);
			if (current instanceof ConfigFragment fragment) {
				todo.addAll(fragment.getContents());
			}
			if (current instanceof Switch choice) {
				todo.addAll(choice.getAlternatives());
			}
		}
		return result;
	}

	private void checkGroups(TLObject project) throws Exception {
		GroupAddress address = (GroupAddress) byDescription("ehome.logical:GroupAddress", "Raumtemperatur Küche");
		assertEquals(Boolean.TRUE, Boolean.valueOf(address.getCentral()));
		assertEquals(Boolean.TRUE, Boolean.valueOf(address.getUnfiltered()));
		assertEquals("0/0/1 GA1", derived(address, "displayName"));

		GroupRange middle = (GroupRange) byDescription("ehome.logical:GroupRange", "Küche");
		assertEquals(Boolean.TRUE, Boolean.valueOf(middle.getUnfiltered()));
		assertEquals(1, middle.getRangeStart().getValue());
		assertEquals(255, middle.getRangeEnd().getValue());
		assertEquals("0/0 MG11", derived(middle, "displayName"));
		assertEquals("0 HG1", derived(middle.tContainer(), "displayName"));
		assertSame(project, middle.tContainer().tContainer());

		// An address must lie within the range of its group.
		WithinGroupRange constraint = new WithinGroupRange(SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY,
			TypedConfiguration.newConfigItem(WithinGroupRange.Config.class));
		TLStructuredTypePart addressPart = address.tType().getPart("address");
		assertNull(constraint.check(address, addressPart));
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			address.setAddress(new GA(300, null));
			assertNotNull("Address outside its range accepted.", constraint.check(address, addressPart));
			tx.rollback();
		}
	}

	private TLObject byDescription(String typeName, String description) {
		List<TLObject> result = SharedData.within(typeName, _projects).stream()
			.filter(obj -> description.equals(((GroupAddressDefinition) obj).getDescription()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private TLObject byTitle(String title) {
		List<TLObject> result = SharedData.within("ehome.physical:Device", _projects).stream()
			.filter(d -> title.equals(((BusDevice) d).getTitle()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private void checkTranslations() {
		// The program of the Test device is written in German; EHomeDemo adds the English texts.
		TLObject parameter = byExtId("ehome.operational:Parameter", "M-0008_A-10D7-11-A0E5-O000A_P-696");
		ResKey description = ((Parameter) parameter).getDescription();
		assertEquals("Funktion der Status-LED rechts", ResKeyUtil.getTranslation(description, Locale.GERMAN));
		assertEquals("Function of right status LED", ResKeyUtil.getTranslation(description, Locale.ENGLISH));

		// The datapoint types are English in the master data, with German translations.
		ResKey temperature = ((DatapointType) datapointType("DPST-9-1")).getDescription();
		assertEquals("Temperatur (°C)", ResKeyUtil.getTranslation(temperature, Locale.GERMAN));
		assertEquals("temperature (°C)", ResKeyUtil.getTranslation(temperature, Locale.ENGLISH));
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = withExtId(typeName, extId);
		assertEquals(1, result.size());
		return result.get(0);
	}

	/**
	 * The object with the given ID in the given project.
	 */
	private static TLObject projectObject(String typeName, String extId, TLObject project) {
		List<TLObject> result = SharedData.within(typeName, List.of(project)).stream()
			.filter(obj -> extId.equals(((Externalized) obj).getExtId()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	/**
	 * The object of a project that was imported several times, from the import with the given
	 * index.
	 */
	private static TLObject byExtId(String typeName, String extId, int index) {
		return withExtId(typeName, extId).get(index);
	}

	private static List<TLObject> withExtId(String typeName, String extId) {
		return instances(typeName).stream()
			.filter(obj -> extId.equals(((Externalized) obj).getExtId()))
			.toList();
	}

	private void checkParameterSettings(TLObject project) {
		// ETS stores only the values that differ from the default of the application program.
		List<TLObject> settings = SharedData.within("ehome.physical:ParameterSetting", List.of(project));
		assertEquals(15, settings.size());

		// An enumeration value is the entry of the parameter's own type, written by its number.
		TLObject led = setting(settings, "Funktion der Status-LED rechts");
		EnumerationValue entry = ((ParameterSetting) led).getChoice();
		assertNotNull(entry);
		assertEquals(9, entry.getValue());
		assertEquals("Vergleicher ohne Vorzeichen (1 Byte)", ResKeyUtil.getTranslation(entry.getNameKey(), Locale.GERMAN));
		EnumerationType ledType = (EnumerationType) ((ParameterSetting) led).getParameter().getParameter().getType();
		assertTrue(ledType.getValues().contains(entry));
		assertEquals("9", derived(led, "encodedValue"));
		assertEquals("Vergleicher ohne Vorzeichen (1 Byte)", derived(led, "displayValue"));
		assertEquals("Betätigungsanzeige", derived(led, "displayDefault"));
		// The catalog shows the same parameter with its default.
		TLObject view = ((ParameterSetting) led).getParameter();
		assertEquals("Funktion der Status-LED rechts", ((String) derived(view, "title")).trim());
		assertEquals("Betätigungsanzeige", derived(view, "displayDefault"));
		assertNotNull(derived(view, "effectiveAccess"));

		TLObject number = setting(settings, "Vergleichswert (0...255)");
		assertEquals(Long.valueOf(42), ((ParameterSetting) number).getNumber());
		assertEquals("42", derived(number, "encodedValue"));
		assertEquals("42", derived(number, "displayValue"));
		assertEquals("0", derived(number, "displayDefault"));
		NumericType numberType = (NumericType) ((ParameterSetting) number).getParameter().getParameter().getType();
		assertEquals(0L, numberType.getMin());
		assertEquals(255L, numberType.getMax());
	}

	private static TLObject setting(List<TLObject> settings, String name) {
		List<TLObject> result = settings.stream()
			.filter(s -> name.equals(((String) derived(s, "name")).trim()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	/**
	 * Checks the datapoint types after imports of the given samples: each type of their master data
	 * exists exactly once (and in the list of all types), and the communication objects of the
	 * projects imported refer to them.
	 */
	private void checkDatapointTypes(TLObject project, List<String> samples) throws Exception {
		Set<String> mainTypes = new TreeSet<>();
		Set<String> subtypes = new TreeSet<>();
		for (String sample : samples) {
			mainTypes.addAll(SharedData.ids(sample, MASTER, "DatapointType"));
			subtypes.addAll(SharedData.ids(sample, MASTER, "DatapointSubtype"));
		}
		SharedData.assertOnce("ehome.datapoint:DatapointMainType", mainTypes);
		SharedData.assertOnce("ehome.datapoint:DatapointSubtype", subtypes);
		TLObject root = TLModelUtil.findType("ehome.datapoint:DatapointTypes").getModule().getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		List<?> types = ((DatapointTypes) root).getMainTypes();
		assertEquals(Set.copyOf(instances("ehome.datapoint:DatapointMainType")), Set.copyOf(types));
		assertEquals(types.size(), Set.copyOf(types).size());

		TLObject temperature = datapointType("DPST-9-1");
		assertEquals("9.001", derived(temperature, "code"));
		assertEquals("DPT_Value_Temp", ((DatapointSubtype) temperature).getName());
		assertEquals(16, derived(temperature, "sizeInBit"));
		assertEquals("9.001 temperature (°C)", asRoot(() -> TLContext.getContext().withLocale(Locale.ENGLISH, () -> MetaLabelProvider.INSTANCE.getLabel(temperature))));
		TLObject floats = datapointType("DPT-9");
		assertEquals("9.*", derived(floats, "code"));
		assertEquals(datapointType("DPST-1-1"), ((DatapointMainType) datapointType("DPT-1")).getDefaultSubtype());

		// The temperature object of the sample device has its datapoint type set in the project.
		assertTrue(_projects.contains(project));
		List<TLObject> connectors = SharedData.within("ehome.physical:Connector", _projects).stream()
			.filter(c -> !((Connector) c).getDatapointTypes().isEmpty())
			.toList();
		assertFalse("No datapoint type of a communication object imported.", connectors.isEmpty());
		for (TLObject connector : connectors) {
			assertEquals(temperature, derived(connector, "effectiveDatapointType"));
			// The group addresses the object sends to or receives from have its type, too.
			for (Connection connection : ((Connector) connector).getConnections()) {
				TLObject address = connection.getTarget();
				assertEquals(temperature, derived(address, "effectiveDatapointType"));
			}
		}
	}

	/**
	 * The value of a derived attribute, read as the technical administrator.
	 *
	 * <p>
	 * A derived attribute is computed with the rights of the reading user, and the test has none, so it would read nothing.
	 * </p>
	 */
	public static Object derived(TLObject obj, String attribute) {
		return asRoot(() -> obj.tValueByName(attribute));
	}

	/**
	 * Computes the given value as <code>root</code>: derived attributes, and the labels computed
	 * from them, read as <code>null</code> without a person.
	 */
	public static <T> T asRoot(Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return value.get();
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static TLObject datapointType(String extId) {
		List<TLObject> result = Stream.concat(
			instances("ehome.datapoint:DatapointMainType").stream(),
			instances("ehome.datapoint:DatapointSubtype").stream())
			.filter(t -> extId.equals(((DatapointType) t).getExtId()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	/**
	 * The given messages of an import without those about the mask versions of its master data (see
	 * <code>MasterDataUpdates</code>): which mask versions a database holds depends on the projects
	 * other tests of the suite imported into it before.
	 */
	public static List<BufferingI18NLog.Entry> withoutMasterData(List<BufferingI18NLog.Entry> messages) {
		return messages.stream().filter(m -> !String.valueOf(m.getMessage()).contains("_MASTER_DATA_")).toList();
	}

	/**
	 * Imports the given ETS project file in a transaction of its own.
	 *
	 * @return The messages of the import, see {@link Importer#getMessages()}.
	 */
	public static List<BufferingI18NLog.Entry> importSample(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ApplicationModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);
		Importer importer = new Importer(binding);
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer.getMessages();
	}

	public static List<TLObject> instances(String typeName) {
		TLClass type = (TLClass) TLModelUtil.findType(typeName);
		return MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEtsImport.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
