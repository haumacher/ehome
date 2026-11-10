/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.projectfile;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.etsImport.ConstraintVeto;
import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.etsImport.ImportProjectFileJob;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileExport;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.ProposeIdentifiersAction;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * The project file <code>.ehome</code> (haumacher/ehome #117): the test home of the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code> with <code>samples/KNX-Testsystem.home.json</code> and
 * the Hue plug of the controller's Zigbee network, as {@code TestTestHome} sets it up), with a virtual
 * device, a control page with a chart, the proposed functions and a dismissed one, a history archive,
 * a follower of the simulation and an enrolled controller as deploy target, is exported and imported
 * as a new project by the import job.
 *
 * <ul>
 * <li>Both projects build the same runtime image (with the same revision and time; an image is
 * signed only when deployed).</li>
 * <li>Both have the same model content, compared generically over every attribute of every object of
 * the project (the KNX part included), apart from what the file leaves out: the secrets (installation
 * keys, enrollment, of a controller all but name, host and port) and the operating history. None of
 * the secrets is in the file, and the imported project has none.</li>
 * <li>The original project still exists with the same ID: the report warns that both would deploy to
 * the same controllers, and says that the controllers have to be paired again.</li>
 * <li>A file whose data the model's constraints refuse is refused with the problem and the object
 * named, and nothing is imported; so is a file of a newer major version.</li>
 * </ul>
 *
 * <p>
 * The exported file is written to <code>target/projectfile/</code>. The test imports the sample's <code>.knxproj</code> twice (once as the sample, once from the file);
 * in a database that does not know the sample's programs yet, the first import costs a few seconds
 * more than the budget of a test class.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProjectFile extends BasicTestCase {

	/** Where the exported file is written, for inspection. */
	private static final Path OUTPUT = Path.of("target/projectfile");

	private static final Instant TIME = Instant.parse("2026-10-19T10:00:00Z");

	private static final String SECRET = "5ec7e75ec7e75ec7e75ec7e75ec7e75ec7e75ec7e75ec7e75ec7e75ec7e75ec7";

	private static final String TOKEN = "hct_tokentokentoken";

	/** What the file leaves out: secrets and operating history, by the type defining it. */
	private static final Set<String> LEFT_OUT = Set.of(
		"ehome.project:Project#enrollmentCode", "ehome.project:Project#enrollmentExpires",
		"ehome.project:Project#enrollmentCreatedBy", "ehome.project:Project#installationKey",
		"ehome.project:Project#installationPublicKey", "ehome.project:Project#newInstallationKey",
		"ehome.project:Project#newInstallationPublicKey", "ehome.project:Project#recordings",
		"ehome.project:Project#deployments", "ehome.project:Project#imageRevision",
		"ehome.project:Project#unidentifiedDevices", "ehome.runtime:Rule#simulationEnabled",
		"ehome.runtime:Rule#simulationState", "ehome.runtime:RuleParameter#simulationValue",
		"ehome.physical:Device#downloadRevision");

	private static final Set<String> CONTROLLER_KEPT = Set.of("name", "host", "port");

	public void testRoundTrip() throws Exception {
		List<TLObject> before = instances(ProjectFile.PROJECT_TYPE);
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(SharedData.newProjects(before));
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			setUp(project);

			// Readable identifiers for the group addresses (haumacher/ehome #130), proposed on the test
			// home: valid and unique; the rules follow, and the image stays as acceptable as it was.
			ImageReport imageBefore = RuntimeImage.build(project, 1, TIME).validate();
			assertTrue(ProposeIdentifiersAction.apply(project) > 0);
			Set<String> identifiers = new HashSet<>();
			for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
				String identifier = GroupAddressIds.identifier(address);
				if (identifier != null) {
					assertTrue(identifier, identifiers.add(identifier));
					assertNull(identifier, GroupAddressIds.problem(identifier, address));
					assertEquals(identifier, Pages.imageId(address));
				}
			}
			assertTrue(identifiers.toString(), identifiers.size() > 10);
			String rules = Json.write(RuntimeImage.build(project, 1, TIME).getJson().get("rules"));
			assertFalse(rules, rules.matches("(?s).*@\\\\\"\\d+/\\d+/\\d+.*"));
			ImageReport imageAfter = RuntimeImage.build(project, 1, TIME).validate();
			assertEquals(imageAfter.toString(), imageBefore.isValid(), imageAfter.isValid());
			assertEquals(imageAfter.toString(), imageBefore.getRefusals().size(), imageAfter.getRefusals().size());

			Map<String, Object> image = RuntimeImage.build(project, 1, TIME).getJson();
			String dump = dump(project);

			byte[] file = export(project);
			Files.createDirectories(OUTPUT);
			Files.write(OUTPUT.resolve("KNX-Testsystem" + ProjectFile.EXTENSION), file);
			String text = new String(file, StandardCharsets.ISO_8859_1);
			for (String secret : List.of(SECRET, TOKEN)) {
				assertFalse("Secret in the file: " + secret, text.contains(secret));
			}

			Map<?, ?> report = importFile(file, "Testhaus.ehome");
			List<TLObject> imported = SharedData.newProjects(before);
			imported.remove(project);
			projects.addAll(imported);
			assertEquals(1, imported.size());
			TLObject copy = imported.get(0);
			assertSame(copy, report.get(ImportEtsProjectJob.PROJECT));
			assertEquals(project.tValueByName("extId"), copy.tValueByName("extId"));

			assertEquals(Json.write(image), Json.write(RuntimeImage.build(copy, 1, TIME).getJson()));
			assertEquals(dump, dump(copy));
			assertNull(copy.tValueByName("installationKey"));
			TLObject controller = (TLObject) copy.tValueByName("controller");
			assertNotNull(controller);
			assertNull(controller.tValueByName("token"));
			assertNull(controller.tValueByName("secret"));

			List<String> messages = messages(report);
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("ID " + project.tValueByName("extId"))
				&& m.contains("same controllers")));
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("Pair the controllers again")));

			// Exported again, the copy gives the same document.
			assertEquals(Json.write(ProjectFileExport.document(project, TIME)),
				Json.write(ProjectFileExport.document(copy, TIME)));

			// Data the model's constraints refuse (an archive finer than the controller keeps) refuses
			// the import with the problem and the object named, and nothing is imported.
			byte[] refused = withDocument(file, document -> {
				for (Object entry : (List<?>) document.get(ProjectFile.GROUP_ADDRESSES_MEMBER)) {
					if (((Map<?, ?>) entry).get("historyArchives") instanceof List<?> archives) {
						@SuppressWarnings("unchecked")
						Map<String, Object> archive = (Map<String, Object>) archives.get(0);
						archive.put("resolution", Long.valueOf(3600));
					}
				}
			});
			List<TLObject> beforeRefused = instances(ProjectFile.PROJECT_TYPE);
			try {
				importFile(refused, "Refused.ehome");
				fail("Data violating the constraints must be refused.");
			} catch (TopLogicException ex) {
				String message = Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
				assertTrue(message, message.contains("slots") && message.contains("History archive"));
			}
			assertEquals(beforeRefused.size(), instances(ProjectFile.PROJECT_TYPE).size());

			// A newer major version is refused before anything is imported.
			byte[] newer = withDocument(file, document -> document.put(ProjectFile.VERSION_MEMBER, "2.0"));
			List<TLObject> beforeNewer = instances(ProjectFile.PROJECT_TYPE);
			try {
				importFile(newer, "Neuer.ehome");
				fail("A file of a newer major version must be refused.");
			} catch (TopLogicException ex) {
				String message = Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
				assertTrue(message, message.contains("2.0") && message.contains("newer E-Home"));
			}
			assertEquals(beforeNewer.size(), instances(ProjectFile.PROJECT_TYPE).size());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * The test home with what the file has to carry besides.
	 */
	private static void setUp(TLObject project) throws Exception {
		TLObject temperature = address(project, "Raumtemperatur");
		TLObject type = instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> "DPST-9-1".equals(t.tValueByName("extId"))).findFirst().orElseThrow();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			GroupAddressTypes.setType(temperature, type);
			for (String key : List.of("Taste1", "Taste2", "Taste3", "Taste4", "Taste6")) {
				address(project, key).tUpdateByName("runtimeWritable", Boolean.TRUE);
			}
			ZigbeeImport.importExport(project,
				ZigbeeExport.parse(read("src/test/fixtures/zigbee/hue-steckdose.export.json")));
			commit(tx);
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			new RuleImport().importImage(project, read("samples/KNX-Testsystem.home.json"));
			commit(tx);
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			// Settings of a group address: a history with an archive, a unit.
			temperature.tUpdateByName("historyManual", Boolean.TRUE);
			temperature.tUpdateByName("historyRetention", Integer.valueOf(172800));
			temperature.tUpdateByName("historySpacing", Integer.valueOf(300));
			TLObject archive = create("ehome.logical:HistoryArchive");
			archive.tUpdateByName("resolution", Integer.valueOf(86400));
			archive.tUpdateByName("retention", Integer.valueOf(365 * 86400));
			temperature.tUpdateByName("historyArchives", List.of(archive));
			temperature.tUpdateByName("runtimeUnit", "°C");

			// Functions, one of them dismissed.
			List<TLObject> functions = FunctionProposal.apply(project);
			assertTrue(functions.size() > 1);
			FunctionProposal.dismiss(functions.get(functions.size() - 1));

			// A virtual device in a room, bound to group addresses and the plug.
			// The sample has no buildings: a room created in E-Home, holding the plug.
			TLObject building = create("ehome.building:Building");
			building.tUpdateByName("name", "Testhaus");
			project.tUpdateByName("buildings", List.of(building));
			ExtIds.assign(building);
			TLObject room = create("ehome.building:Room");
			room.tUpdateByName("name", "Büro");
			building.tUpdateByName("parts", List.of(room));
			ExtIds.assign(room);
			for (TLObject plug : SharedData.within("ehome.zigbee:ZigbeeDevice", List.of(project))) {
				plug.tUpdateByName("location", room);
			}
			TLObject block = BlockInstances.create(project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()),
				room, ResKey.builder().add(Locale.ENGLISH, "Heating").add(Locale.GERMAN, "Heizung").build());
			BlockInstances.bind(BlockInstances.port(block, "temperature"), temperature);
			BlockInstances.bind(BlockInstances.port(block, "window"),
				((List<?>) project.tValueByName("applicationDatapoints")).stream().map(TLObject.class::cast)
					.findFirst().orElseThrow());
			BlockInstances.parameter(block, "comfort").tUpdateByName("value", Double.valueOf(21.5));

			// A page with a datapoint, a rule's switch and a chart of the temperature.
			TLObject page = Pages.insert(project, null, Pages.PAGE, null);
			TLObject section = Pages.insert(project, page, Pages.SECTION, null);
			Pages.insert(project, section, Pages.DATAPOINT, List.of(BlockInstances.port(block, "valve")));
			Pages.insert(project, section, Pages.RULE,
				List.of(((List<?>) project.tValueByName("rules")).get(0)));
			TLObject chart = Pages.insert(project, section, Pages.CHART, null);
			Pages.insert(project, chart, Pages.SERIES, List.of(temperature));

			// A follower of the simulation.
			TLObject device = SharedData.within("ehome.physical:Device", List.of(project)).stream()
				.filter(d -> ((Collection<?>) d.tValueByName("connectors")).size() > 1).findFirst().orElseThrow();
			List<?> connectors = new ArrayList<>((Collection<?>) device.tValueByName("connectors"));
			TLObject follower = create("ehome.runtime:ObjectFollower");
			follower.tUpdateByName("device", device);
			follower.tUpdateByName("source", connectors.get(0));
			follower.tUpdateByName("follower", connectors.get(1));
			follower.tUpdateByName("inverted", Boolean.TRUE);
			project.tUpdateByName("simulationFollowers", List.of(follower));

			// An enrolled controller as deploy target, and the secrets the file must not carry.
			TLObject controller = create(ProjectFile.CONTROLLER_TYPE);
			controller.tUpdateByName("name", "Flur");
			controller.tUpdateByName("host", "192.168.178.20");
			controller.tUpdateByName("port", Integer.valueOf(8080));
			controller.tUpdateByName("token", TOKEN);
			controller.tUpdateByName("controllerId", "0123456789abcdef");
			controller.tUpdateByName("secret", SECRET);
			project.tUpdateByName("controllers", List.of(controller));
			project.tUpdateByName("controller", controller);
			project.tUpdateByName("installationKey", SECRET);
			project.tUpdateByName("ntpServer", "pool.ntp.org");
			project.tUpdateByName("gateway", "192.168.178.81:3671");
			((TLObject) ((List<?>) project.tValueByName("rules")).get(0)).tUpdateByName("simulationEnabled", "false");
			commit(tx);
		}
	}

	/**
	 * Commits, naming the problems of a refused commit.
	 */
	private static void commit(Transaction tx) {
		try {
			tx.commit();
		} catch (TopLogicException ex) {
			List<String> problems = ConstraintVeto.problems(ex);
			if (problems == null) {
				throw ex;
			}
			throw new AssertionError("Commit refused: " + problems, ex);
		}
	}

	private static TLObject address(TLObject project, String name) {
		return SharedData.within(ProjectFile.GROUP_ADDRESS_TYPE, List.of(project)).stream()
			.filter(a -> name.equals(a.tValueByName("name"))).findFirst().orElseThrow();
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static byte[] export(TLObject project) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ProjectFileExport.export(project, out);
		return out.toByteArray();
	}

	private static Map<?, ?> importFile(byte[] file, String name) throws Exception {
		Object result = new RecordingJobMonitor().run(new ImportProjectFileJob(null, null),
			List.of(BinaryDataFactory.createBinaryData(file, ProjectFile.CONTENT_TYPE, name)));
		return (Map<?, ?>) result;
	}

	private static List<String> messages(Map<?, ?> report) {
		List<String> result = new ArrayList<>();
		for (Object message : (List<?>) report.get(ImportEtsProjectJob.MESSAGES)) {
			result.add(String.valueOf(((Map<?, ?>) message).get(ImportEtsProjectJob.MESSAGE)));
		}
		return result;
	}

	/**
	 * The given file with its document changed.
	 */
	private static byte[] withDocument(byte[] file, Consumer<Map<String, Object>> change) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(file));
				ZipOutputStream zip = new ZipOutputStream(out)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				byte[] content = in.readAllBytes();
				if (entry.getName().equals(ProjectFile.JSON_ENTRY)) {
					@SuppressWarnings("unchecked")
					Map<String, Object> document =
						(Map<String, Object>) Json.parse(new String(content, StandardCharsets.UTF_8), 200);
					change.accept(document);
					content = Json.write(document).getBytes(StandardCharsets.UTF_8);
				}
				zip.putNextEntry(new ZipEntry(entry.getName()));
				zip.write(content);
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	// ---- The generic comparison ----------------------------------------------------------------

	/**
	 * Every attribute of every object of the project (reached by its composite references), apart
	 * from what the file leaves out, with references written as paths within the project or as the
	 * external ID of a shared object; the parts of unordered references sorted.
	 */
	private static String dump(TLObject project) {
		Map<TLObject, String> paths = new java.util.HashMap<>();
		paths.put(project, "");
		assignPaths(project, "", paths);
		return dump(project, paths, "");
	}

	private static void assignPaths(TLObject obj, String path, Map<TLObject, String> paths) {
		for (TLStructuredTypePart part : parts(obj)) {
			if (part instanceof TLReference reference && reference.isComposite()) {
				int index = 0;
				for (TLObject child : values(obj.tValue(part))) {
					String childPath = path + "/" + part.getName() + "/" + key(child, ++index);
					paths.put(child, childPath);
					assignPaths(child, childPath, paths);
				}
			}
		}
	}

	private static String key(TLObject obj, int index) {
		for (String name : List.of("extId", "identifier", "ieee", "port", "parameter")) {
			if (obj.tType().getPart(name) != null && obj.tValueByName(name) instanceof String text) {
				return text;
			}
		}
		if (obj.tType().getPart("function") != null && obj.tType().getPart("endpoint") != null) {
			return obj.tValueByName("function") + "@" + obj.tValueByName("endpoint");
		}
		if (obj.tType().getPart("port") != null && obj.tValueByName("port") instanceof TLObject port) {
			// A communication object of a device: by the program's object.
			return String.valueOf(port.tValueByName("extId"));
		}
		return Integer.toString(index);
	}

	private static String dump(TLObject obj, Map<TLObject, String> paths, String indent) {
		StringBuilder out = new StringBuilder();
		out.append(TLModelUtil.qualifiedName(obj.tType())).append('\n');
		Map<String, String> lines = new TreeMap<>();
		for (TLStructuredTypePart part : parts(obj)) {
			Object value = obj.tValue(part);
			String text;
			if (part instanceof TLReference reference && reference.isComposite()) {
				List<String> children = new ArrayList<>();
				for (TLObject child : values(value)) {
					children.add(dump(child, paths, indent + "\t\t"));
				}
				if (!reference.isOrdered()) {
					children.sort(null);
				}
				text = children.isEmpty() ? null : "\n" + String.join("", children);
			} else if (part instanceof TLReference) {
				List<String> targets = new ArrayList<>();
				for (TLObject target : values(value)) {
					targets.add(ref(target, paths));
				}
				if (!part.isOrdered()) {
					targets.sort(null);
				}
				text = targets.isEmpty() ? null : targets.toString();
			} else {
				text = primitive(value);
			}
			if (text != null) {
				lines.put(part.getName(), text);
			}
		}
		for (Map.Entry<String, String> line : lines.entrySet()) {
			out.append(indent).append('\t').append(line.getKey()).append(": ").append(line.getValue()).append('\n');
		}
		return out.toString();
	}

	private static String ref(TLObject target, Map<TLObject, String> paths) {
		if (target instanceof TLClassifier classifier) {
			return classifier.getName();
		}
		String path = paths.get(target);
		if (path != null) {
			return path;
		}
		if (target.tType().getPart("extId") != null) {
			return TLModelUtil.qualifiedName(target.tType()) + " " + target.tValueByName("extId");
		}
		return TLModelUtil.qualifiedName(target.tType()) + " " + target.tValueByName("name") + " "
			+ target.tValueByName("host") + ":" + target.tValueByName("port");
	}

	private static String primitive(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof ResKey key) {
			Map<String, String> texts = new LinkedHashMap<>();
			for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
				texts.put(locale.getLanguage(), Resources.getInstance(locale).getString(key));
			}
			return texts.toString();
		}
		if (value instanceof Date date) {
			return Long.toString(date.getTime());
		}
		if (value instanceof Collection<?> collection && collection.isEmpty()) {
			return null;
		}
		return value.toString();
	}

	private static List<TLStructuredTypePart> parts(TLObject obj) {
		List<TLStructuredTypePart> result = new ArrayList<>();
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part.isDerived() || part instanceof TLReference reference && reference.isBackwards()) {
				continue;
			}
			String owner = TLModelUtil.qualifiedName(part.getDefinition().getOwner());
			if (LEFT_OUT.contains(owner + "#" + part.getName())
				|| owner.equals(ProjectFile.CONTROLLER_TYPE) && !CONTROLLER_KEPT.contains(part.getName())) {
				continue;
			}
			result.add(part);
		}
		return result;
	}

	private static List<TLObject> values(Object value) {
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(TLObject.class::cast).toList();
		}
		return value == null ? List.of() : List.of((TLObject) value);
	}

	private static String read(String path) throws Exception {
		return Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectFile.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE, IdRenames.Module.INSTANCE));
	}

}
