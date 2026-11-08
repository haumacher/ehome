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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportProjectFileJob;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileExport;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the project file (haumacher/ehome #117) together with features it did not test: the copy
 * is independent of the original (a renamed parameter rewrites only the copy's expressions, the copy
 * survives the deletion of the original), and a label over 63 bytes in the file (haumacher/ehome
 * #116) is refused.
 */
@SuppressWarnings("javadoc")
public class TestProjectFileProbe extends BasicTestCase {

	private static final Instant TIME = Instant.parse("2026-11-08T10:00:00Z");

	public void testCopyIsIndependent() throws Exception {
		List<TLObject> before = instances(ProjectFile.PROJECT_TYPE);
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(SharedData.newProjects(before));
		TLObject original = projects.get(0);
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				new RuleImport().importImage(original,
					Files.readString(new File("samples/KNX-Testsystem.home.json").toPath(), StandardCharsets.UTF_8));
				tx.commit();
			}
			byte[] file = export(original);
			importFile(file);
			List<TLObject> imported = SharedData.newProjects(before);
			imported.remove(original);
			projects.addAll(imported);
			assertEquals(1, imported.size());
			TLObject copy = imported.get(0);
			String image = Json.write(RuntimeImage.build(copy, 1, TIME).getJson());
			assertEquals(Json.write(RuntimeImage.build(original, 1, TIME).getJson()), image);

			// Renaming a parameter of the copy rewrites the copy's expressions only.
			String originalBefore = Json.write(RuntimeImage.build(original, 1, TIME).getJson());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				parameter(copy, "frost", "below").tUpdateByName("identifier", "unter-grenze");
				tx.commit();
			}
			String renamed = Json.write(RuntimeImage.build(copy, 1, TIME).getJson());
			String copyTexts = texts(rule(copy, "frost"));
			assertTrue(copyTexts, copyTexts.contains("$\"unter-grenze\""));
			assertFalse(copyTexts, copyTexts.contains("$below"));
			assertTrue(texts(rule(original, "frost")).contains("$below"));
			assertEquals(originalBefore, Json.write(RuntimeImage.build(original, 1, TIME).getJson()));

			// The copy survives the deletion of the original.
			SharedData.deleteProjects(List.of(original));
			projects.remove(original);
			assertTrue(copy.tValid());
			assertEquals(renamed, Json.write(RuntimeImage.build(copy, 1, TIME).getJson()));
			// ... and exports and imports again.
			byte[] again = export(copy);
			List<TLObject> beforeAgain = instances(ProjectFile.PROJECT_TYPE);
			importFile(again);
			List<TLObject> third = SharedData.newProjects(beforeAgain);
			projects.addAll(third);
			assertEquals(1, third.size());
			assertEquals(renamed, Json.write(RuntimeImage.build(third.get(0), 1, TIME).getJson()));

			// A label over 63 bytes in the file is refused, nothing imported.
			byte[] tooLong = withDocument(again, document -> {
				Map<String, Object> project = map(document.get(ProjectFile.PROJECT_MEMBER));
				for (Object rule : (List<?>) project.get("rules")) {
					if ("frost".equals(map(rule).get("identifier"))) {
						Map<String, Object> parameter = map(((List<?>) map(rule).get("parameters")).get(0));
						parameter.put("title", Map.of("de", "Ä".repeat(32), "en", "x"));
					}
				}
			});
			List<TLObject> beforeRefused = instances(ProjectFile.PROJECT_TYPE);
			try {
				importFile(tooLong);
				fail("A label over 63 bytes must be refused.");
			} catch (TopLogicException ex) {
				// Expected.
			}
			assertEquals(beforeRefused.size(), instances(ProjectFile.PROJECT_TYPE).size());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static TLObject rule(TLObject project, String id) {
		for (Object r : (List<?>) project.tValueByName("rules")) {
			if (id.equals(((TLObject) r).tValueByName("identifier"))) {
				return (TLObject) r;
			}
		}
		throw new AssertionError("No rule " + id);
	}

	/** The texts of all attributes of the given object and its parts. */
	private static String texts(TLObject obj) {
		StringBuilder result = new StringBuilder();
		for (com.top_logic.model.TLStructuredTypePart part : obj.tType().getAllParts()) {
			Object value = obj.tValue(part);
			if (value instanceof String text) {
				result.append(part.getName()).append('=').append(text).append('\n');
			} else if (part instanceof com.top_logic.model.TLReference ref && ref.isComposite() && !ref.isDerived()) {
				if (value instanceof java.util.Collection<?> children) {
					for (Object child : children) {
						result.append(texts((TLObject) child));
					}
				} else if (value instanceof TLObject child) {
					result.append(texts(child));
				}
			}
		}
		return result.toString();
	}

	private static TLObject parameter(TLObject project, String rule, String id) {
		for (Object r : (List<?>) project.tValueByName("rules")) {
			TLObject candidate = (TLObject) r;
			if (rule.equals(candidate.tValueByName("identifier"))) {
				for (Object p : (List<?>) candidate.tValueByName("parameters")) {
					if (id.equals(((TLObject) p).tValueByName("identifier"))) {
						return (TLObject) p;
					}
				}
			}
		}
		throw new AssertionError("No parameter " + rule + "/" + id);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	private static byte[] export(TLObject project) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ProjectFileExport.export(project, out);
		return out.toByteArray();
	}

	private static Map<?, ?> importFile(byte[] file) throws Exception {
		return (Map<?, ?>) new RecordingJobMonitor().run(new ImportProjectFileJob(null, null),
			List.of(BinaryDataFactory.createBinaryData(file, ProjectFile.CONTENT_TYPE, "Probe.ehome")));
	}

	private static byte[] withDocument(byte[] file, java.util.function.Consumer<Map<String, Object>> change)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(file));
				ZipOutputStream zip = new ZipOutputStream(out)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				byte[] content = in.readAllBytes();
				if (entry.getName().equals(ProjectFile.JSON_ENTRY)) {
					Map<String, Object> document = map(Json.parse(new String(content, StandardCharsets.UTF_8), 200));
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

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectFileProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			IdRenames.Module.INSTANCE));
	}

}
