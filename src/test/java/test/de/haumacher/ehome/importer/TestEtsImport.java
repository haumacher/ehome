/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

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
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GALabelProvider;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.operational.Program;

/**
 * Test case for {@link Importer} importing the ETS sample project <code>samples/Test.knxproj</code>.
 */
@SuppressWarnings("javadoc")
public class TestEtsImport extends BasicTestCase {

	public void testImportSample() throws Exception {
		importSample("samples/Test.knxproj");

		List<TLObject> projects = instances("ehome.project:Project");
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		assertEquals("Test", project.tValueByName("name"));
		assertEquals("P-01A0", project.tValueByName("extId"));
		Object style = project.tValueByName("groupAddressStyle");
		assertNotNull("Group address style not imported.", style);
		assertEquals("ThreeLevel", ((TLClassifier) style).getName());

		List<TLObject> addresses = instances("ehome.logical:GroupAddress");
		assertFalse("No group address imported.", addresses.isEmpty());
		for (TLObject address : addresses) {
			Object value = address.tValueByName("address");
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

		checkDatapointTypes(37, 273);
		checkParameterSettings();

		// Further imports reuse the standardized datapoint types and add only those missing so far
		// (the master data of the demo project is newer).
		importSample("samples/EHomeDemo.knxproj");
		checkDatapointTypes(38, 279);
		importSample("samples/Test.knxproj");
		assertEquals(3, instances("ehome.project:Project").size());
		checkDatapointTypes(38, 279);

		// The manufacturer data is shared as well: the program of the Test device is part of both
		// samples and exists only once.
		assertEquals(318, instances("ehome.catalog:Manufacturer").size());
		assertEquals(3, instances("ehome.catalog:Product").size());
		assertEquals(3, instances("ehome.operational:Program").size());
		for (TLObject device : instances("ehome.physical:Device")) {
			assertNotNull(device.tValueByName("program"));
		}

		checkTranslations();
		checkDeviceDetails();
	}

	private void checkDeviceDetails() throws Exception {
		// The device of the Test sample was never downloaded.
		TLObject plain = byExtId("ehome.physical:Device", "P-01A0-0_DI-1", 0);
		assertTrue(((Collection<?>) plain.tValueByName("loaded")).isEmpty());
		assertEquals(Boolean.TRUE, derived(plain, "downloadPending"));

		// The fixture adds names, descriptions, the download state, an inactive communication object
		// and an unassigned device to it.
		importSample("src/test/fixtures/TestDetails.knxproj");
		TLObject device = byTitle("Taster Küche");
		assertEquals("Taster Küche", derived(device, "name"));
		assertEquals("Neben der Tür", device.tValueByName("description"));
		assertEquals("Unterputzdose links", device.tValueByName("installationHints"));
		assertEquals("0008:1234ABCD", device.tValueByName("serialNumber"));
		assertNotNull(device.tValueByName("lastModified"));
		assertNotNull(device.tValueByName("lastDownload"));
		assertEquals(5, ((Collection<?>) device.tValueByName("loaded")).size());
		assertEquals(Boolean.FALSE, derived(device, "downloadPending"));

		Collection<?> connectors = (Collection<?>) device.tValueByName("connectors");
		assertEquals(1, connectors.stream().filter(c -> Boolean.FALSE.equals(((TLObject) c).tValueByName("active"))).count());
		assertEquals(connectors.size() - 1, connectors.stream().filter(c -> Boolean.TRUE.equals(((TLObject) c).tValueByName("active"))).count());

		TLObject unassigned = byTitle("Taster Reserve");
		TLObject project = unassigned.tContainer();
		assertEquals("ehome.project:Project", TLModelUtil.qualifiedName(project.tType()));
		assertEquals(List.of(unassigned), project.tValueByName("unassignedDevices"));
		assertSame(project, device.tContainer().tContainer().tContainer());
	}

	private static TLObject byTitle(String title) {
		List<TLObject> result = instances("ehome.physical:Device").stream()
			.filter(d -> title.equals(d.tValueByName("title")))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private void checkTranslations() {
		// The program of the Test device is written in German; EHomeDemo adds the English texts.
		TLObject parameter = byExtId("ehome.operational:Parameter", "M-0008_A-10D7-11-A0E5-O000A_P-696");
		ResKey description = (ResKey) parameter.tValueByName("description");
		assertEquals("Funktion der Status-LED rechts", ResKeyUtil.getTranslation(description, Locale.GERMAN));
		assertEquals("Function of right status LED", ResKeyUtil.getTranslation(description, Locale.ENGLISH));

		// The datapoint types are English in the master data, with German translations.
		ResKey temperature = (ResKey) datapointType("DPST-9-1").tValueByName("description");
		assertEquals("Temperatur (°C)", ResKeyUtil.getTranslation(temperature, Locale.GERMAN));
		assertEquals("temperature (°C)", ResKeyUtil.getTranslation(temperature, Locale.ENGLISH));
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = withExtId(typeName, extId);
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
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
	}

	private void checkParameterSettings() {
		// ETS stores only the values that differ from the default of the application program.
		List<TLObject> settings = instances("ehome.physical:ParameterSetting");
		assertEquals(15, settings.size());

		TLObject led = setting(settings, "Funktion der Status-LED rechts");
		assertEquals("9", led.tValueByName("value"));
		assertEquals("Vergleicher ohne Vorzeichen (1 Byte)", derived(led, "displayValue"));
		assertEquals("Betätigungsanzeige", derived(led, "displayDefault"));
		// The catalog shows the same parameter with its default.
		TLObject view = (TLObject) led.tValueByName("parameter");
		assertEquals("Funktion der Status-LED rechts", ((String) derived(view, "title")).trim());
		assertEquals("Betätigungsanzeige", derived(view, "displayDefault"));
		assertNotNull(derived(view, "effectiveAccess"));

		TLObject number = setting(settings, "Vergleichswert (0...255)");
		assertEquals("42", derived(number, "displayValue"));
		assertEquals("0", derived(number, "displayDefault"));
	}

	private static TLObject setting(List<TLObject> settings, String name) {
		List<TLObject> result = settings.stream()
			.filter(s -> name.equals(((String) derived(s, "name")).trim()))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private void checkDatapointTypes(int mainTypes, int subtypes) throws Exception {
		assertEquals(mainTypes, instances("ehome.datapoint:DatapointMainType").size());
		assertEquals(subtypes, instances("ehome.datapoint:DatapointSubtype").size());
		TLObject root = TLModelUtil.findType("ehome.datapoint:DatapointTypes").getModule().getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		assertEquals(mainTypes, ((List<?>) root.tValueByName("types")).size());

		TLObject temperature = datapointType("DPST-9-1");
		assertEquals("9.001", temperature.tValueByName("code"));
		assertEquals("DPT_Value_Temp", temperature.tValueByName("name"));
		assertEquals(16, temperature.tValueByName("sizeInBit"));
		assertEquals("9.001 temperature (°C)", MetaLabelProvider.INSTANCE.getLabel(temperature));
		TLObject floats = datapointType("DPT-9");
		assertEquals("9.*", floats.tValueByName("code"));
		assertEquals(datapointType("DPST-1-1"), datapointType("DPT-1").tValueByName("defaultSubtype"));

		// The temperature object of the sample device has its datapoint type set in the project.
		List<TLObject> connectors = instances("ehome.physical:Connector").stream()
			.filter(c -> !((List<?>) c.tValueByName("datapointTypes")).isEmpty())
			.toList();
		assertFalse("No datapoint type of a communication object imported.", connectors.isEmpty());
		for (TLObject connector : connectors) {
			assertEquals(temperature, derived(connector, "effectiveDatapointType"));
			// The group addresses the object sends to or receives from have its type, too.
			for (Object connection : (Collection<?>) connector.tValueByName("connections")) {
				TLObject address = (TLObject) ((TLObject) connection).tValueByName("target");
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
	private static Object derived(TLObject obj, String attribute) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return obj.tValueByName(attribute);
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
			.filter(t -> extId.equals(t.tValueByName("extId")))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private static void importSample(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ApplicationModelBinding binding = new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false);
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				new Importer(binding).importStream(in);
				tx.commit();
			}
		}
	}

	private static List<TLObject> instances(String typeName) {
		TLClass type = (TLClass) TLModelUtil.findType(typeName);
		return MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEtsImport.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
