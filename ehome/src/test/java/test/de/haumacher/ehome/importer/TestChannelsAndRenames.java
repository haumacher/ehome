/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.etsImport.ImportProjectFileJob;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Rename;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileExport;

/**
 * The channels of a device with the descriptions the project gives them (ETS 6:
 * <code>ChannelInstances</code>) and renamed parameter blocks (<code>Rename</code>,
 * haumacher/ehome #122), with the artificial fixture <code>src/test/fixtures/TestChannels.knxproj</code>
 * (see <code>make-fixtures.py</code>).
 *
 * <p>
 * The editor's tree of a device (<code>devicePages</code>, <code>deviceText</code>) titles a channel
 * "number: title – description", with "(inactive)" for a channel the project deactivated, and a block
 * or channel with the text of the rename of a case taken (in the module instance with its arguments
 * filled). The project file carries the descriptions. The import does not warn about
 * <code>ChannelInstances</code>, <code>Rename</code> and <code>ProjectTraces</code>, but still about
 * KNX Secure (<code>Security</code>).
 * </p>
 *
 * <p>
 * Runs in the database shared with the other tests and deletes its projects again; the program of the
 * fixture stays. One import of the fixture and one of its project file.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestChannelsAndRenames extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestChannels.knxproj";

	private static final String P = "M-0008_A-FFF9-01-0000_";

	public void testChannels() throws Exception {
		List<TLObject> before = instances(ProjectFile.PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			Importer importer = importFixture();
			projects.addAll(SharedData.newProjects(before));
			List<String> warnings = warnings(importer);
			assertEquals(warnings.toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("Security"));

			checkProgram();
			TLObject described = device(projects, 1);
			assertEquals(List.of(
				"1: Kanal A – Binäreingang Küchentür",
				"  Allgemein",
				"  LED oben rechts",
				"2: Kanal B (frei) – Reserve (inaktiv)",
				"  Reserve",
				"Modul 1 – Modulkanal Flur",
				"  Wert 1 (ein)"), tree(described, Locale.GERMAN));
			assertEquals(List.of(
				"1: Channel A – Binäreingang Küchentür",
				"  Allgemein",
				"  LED top right",
				"2: Channel B (free) – Reserve (inactive)",
				"  Reserve",
				"Modul 1 – Modulkanal Flur",
				"  Wert 1 (ein)"), tree(described, Locale.ENGLISH));

			TLObject plain = device(projects, 2);
			List<String> defaults = List.of("1: Kanal A", "  Allgemein", "  LED", "2: Kanal B", "  Reserve", "Modul 1",
				"  Wert");
			assertEquals(defaults, tree(plain, Locale.GERMAN));

			// The title follows the branch taken.
			ParameterView function = view(plain, P + "P-1_R-1");
			try (Transaction tx = kb().beginTransaction()) {
				DeviceSettings.setValue(plain, function, Long.valueOf(1));
				tx.commit();
			}
			assertEquals(List.of("1: Kanal A", "  Allgemein", "  LED oben rechts", "2: Kanal B (frei)", "  Reserve",
				"Modul 1", "  Wert"), tree(plain, Locale.GERMAN));
			try (Transaction tx = kb().beginTransaction()) {
				DeviceSettings.setValue(plain, function, null);
				tx.commit();
			}
			assertEquals(defaults, tree(plain, Locale.GERMAN));

			// The project file carries the channel descriptions.
			String channels = channelInstances(described);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ProjectFileExport.export(described.tContainer().tContainer().tContainer(), out);
			new RecordingJobMonitor().run(new ImportProjectFileJob(null, null), List.of(
				BinaryDataFactory.createBinaryData(out.toByteArray(), ProjectFile.CONTENT_TYPE, "Kanäle.ehome")));
			List<TLObject> imported = SharedData.newProjects(before);
			imported.removeAll(projects);
			projects.addAll(imported);
			assertEquals(1, imported.size());
			TLObject copy = device(imported, 1);
			assertEquals(channels, channelInstances(copy));
			assertEquals(tree(described, Locale.GERMAN), tree(copy, Locale.GERMAN));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void checkProgram() {
		Program program = (Program) instances("ehome.operational:Program").stream()
			.filter(p -> (P + "").startsWith(((Program) p).getExtId() + "_")).findFirst().orElseThrow();
		List<Rename> renames = new ArrayList<>();
		collectRenames(program, renames);
		List<String> found = new ArrayList<>();
		for (Rename rename : renames) {
			found.add(rename.getExtId() + " " + rename.getTarget().tValueByName("extId") + " "
				+ Resources.getInstance(Locale.GERMAN).getString(rename.getText()) + " / "
				+ Resources.getInstance(Locale.ENGLISH).getString(rename.getText()));
		}
		assertEquals(List.of(
			P + "MD-1_PR-1 " + P + "MD-1_PB-1 Wert {{No}} (ein) / Wert {{No}} (ein)",
			P + "PR-1 " + P + "PB-2 LED oben rechts / LED top right",
			P + "PR-2 " + P + "CH-2 Kanal B (frei) / Channel B (free)"), found.stream().sorted().toList());
	}

	private static void collectRenames(TLObject obj, List<Rename> result) {
		if (obj instanceof Rename rename) {
			result.add(rename);
		}
		for (String attribute : List.of("contents", "alternatives", "modules")) {
			if (obj.tType().getPart(attribute) != null) {
				for (Object element : (Collection<?>) obj.tValueByName(attribute)) {
					collectRenames((TLObject) element, result);
				}
			}
		}
	}

	/**
	 * The pages of the device's editor as its tree shows them, indented by their depth.
	 */
	private static List<String> tree(TLObject device, Locale locale) {
		return inLocale(locale, () -> {
			List<String> result = new ArrayList<>();
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			pages(device, configuration, device, "", result);
			return result;
		});
	}

	private static void pages(TLObject device, DeviceConfiguration configuration, Object page, String indent,
			List<String> result) {
		for (ConfigFragment child : configuration.getPages(page)) {
			result.add(indent + DeviceTexts.label(device, child));
			pages(device, configuration, child, indent + "  ", result);
		}
	}

	private static String channelInstances(TLObject device) {
		StringBuilder result = new StringBuilder();
		for (Object element : (Collection<?>) device.tValueByName("channelInstances")) {
			TLObject instance = (TLObject) element;
			result.append(instance.tValueByName("extId")).append(' ').append(instance.tValueByName("channel"))
				.append(' ').append(instance.tValueByName("description")).append(' ')
				.append(instance.tValueByName("active")).append('\n');
		}
		return result.toString();
	}

	private static ParameterView view(TLObject device, String id) {
		return new DeviceConfiguration(device).getActiveViews().values().stream()
			.filter(v -> id.equals(v.getExtId())).findFirst().orElseThrow();
	}

	private static TLObject device(List<TLObject> projects, int address) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", projects).stream()
			.filter(d -> Integer.valueOf(address).equals(d.tValueByName("address"))).toList();
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person person = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(person);
			if (person == null) {
				context.setContextId(contextId);
			}
		}
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

	private static List<String> warnings(Importer importer) {
		return TestEtsImport.withoutMasterData(importer.getMessages()).stream()
			.filter(m -> m.getLevel() == Level.WARN || m.getLevel() == Level.ERROR)
			.map(m -> Resources.getInstance().getString((ResKey) m.getMessage())).toList();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestChannelsAndRenames.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
