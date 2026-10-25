/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * Probe of the ETS import on the XML import of TopLogic 8.0.0-alpha11: the shared objects found by
 * <code>&lt;resolve-existing&gt;</code> and the parts of programs made known per program, the
 * languages bound by <code>&lt;with-var&gt;</code>, a known program skipped by
 * <code>&lt;unless-known-program&gt;</code>.
 *
 * <p>
 * Two projects with programs of their own go into one database, then the first one again: every
 * shared object exists once under its ETS ID, the repeated import knows the program and creates
 * none, and the texts of the programs are those of the file's language (German) and of the
 * application's fallback language (English, filled with the German text unless the shared program
 * gained English texts of its own before), while an empty text of the file leaves the attribute
 * unset.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAlpha11ImportProbe extends BasicTestCase {

	private static final String DETAILS = "src/test/fixtures/TestDetails.knxproj";

	private static final String DYNAMIC = "src/test/fixtures/TestDynamic.knxproj";

	private static final List<String> SHARED_MODULES = List.of("ehome.datapoint", "ehome.catalog", "ehome.operational");

	private static final String PROGRAM = "ehome.operational:Program";

	public void testTwoProjectsAndRepeat() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importProject(DETAILS);
			importProject(DYNAMIC);
			Importer again = importProject(DETAILS);

			assertShared();
			int empty = 0;
			for (String file : List.of(DETAILS, DYNAMIC)) {
				for (String tag : List.of("Manufacturer", "Hardware", "Product", "CatalogSection")) {
					SharedData.assertOnce(type(tag), SharedData.ids(file, "M-0008/.*|knx_master.xml", tag));
				}
				SharedData.assertOnce(PROGRAM, SharedData.ids(file, "M-0008/M-0008_A-.*", "ApplicationProgram"));
				empty += assertTexts(file);
			}
			assertTrue(empty > 0);

			String program = SharedData.ids(DETAILS, "M-0008/M-0008_A-.*", "ApplicationProgram").iterator().next();
			assertEquals(List.of(), again.getCreated(PROGRAM));
			assertEquals(List.of(program), again.getKnown(PROGRAM).stream().map(p -> p.tValueByName("extId")).toList());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private static String type(String tag) {
		switch (tag) {
			case "Manufacturer":
				return "ehome.catalog:Manufacturer";
			case "Hardware":
				return "ehome.catalog:Product";
			case "Product":
				return "ehome.catalog:Variant";
			default:
				return "ehome.catalog:Catalog";
		}
	}

	/**
	 * Every object of the shared modules exists once under its ETS ID.
	 */
	private static void assertShared() {
		for (TLModule module : ModelService.getApplicationModel().getModules()) {
			if (!SHARED_MODULES.contains(module.getName())) {
				continue;
			}
			for (TLClass type : module.getClasses()) {
				if (type.isAbstract() || type.getPart("extId") == null) {
					continue;
				}
				Set<String> ids = new HashSet<>();
				for (TLObject obj : instances(TLModelUtil.qualifiedName(type))) {
					String id = (String) obj.tValueByName("extId");
					assertTrue("Shared object " + id + " of " + type + " twice.", id == null || ids.add(id));
				}
			}
		}
	}

	/**
	 * The name of the program and the descriptions of its parameters in German (the language of the
	 * file) and English (the fallback language); a parameter with an empty text and no translation
	 * has no description.
	 *
	 * @return The number of parameters checked to have no description.
	 */
	private static int assertTexts(String file) throws IOException, XMLStreamException {
		ProgramTexts texts = ProgramTexts.read(file);
		TLObject program = only(PROGRAM, texts._programId);
		// The program is shared: an earlier test may have added the English texts of another file.
		String languages = String.valueOf(program.tValueByName("languages"));
		assertTrue(languages, languages.matches("de( .*)?"));
		boolean programEnglish = languages.contains("en");
		assertEquals(texts._programName, text(program, "name", Locale.GERMAN));
		assertEnglish(programEnglish, texts._programName, text(program, "name", Locale.ENGLISH));

		Map<String, TLObject> parameters = new HashMap<>();
		for (TLObject parameter : instances("ehome.operational:Parameter")) {
			String id = (String) parameter.tValueByName("extId");
			if (id != null && id.startsWith(texts._programId + "_")) {
				parameters.put(id, parameter);
			}
		}
		int withText = 0;
		int empty = 0;
		for (Map.Entry<String, String> entry : texts._parameters.entrySet()) {
			TLObject parameter = parameters.get(entry.getKey());
			assertNotNull(entry.getKey(), parameter);
			// A German translation of the file's German text replaces it.
			String german = texts._translated.getOrDefault(entry.getKey(), entry.getValue());
			if (!german.isEmpty()) {
				withText++;
				assertEquals(entry.getKey(), german, text(parameter, "description", Locale.GERMAN));
				// The fallback language takes the file's own text, a translation goes to its language only.
				String english = entry.getValue().isEmpty() ? null : entry.getValue();
				if (english != null || !programEnglish) {
					assertEnglish(programEnglish, english, text(parameter, "description", Locale.ENGLISH));
				}
			} else if (!programEnglish) {
				empty++;
				assertNull(entry.getKey(), parameter.tValueByName("description"));
			}
		}
		assertTrue(file, withText > 0);
		return empty;
	}

	/**
	 * The English text of a program that has no English texts of its own is the German one of the
	 * file (filled in for the fallback language), else one of its own.
	 */
	private static void assertEnglish(boolean english, String german, String text) {
		if (english) {
			assertTrue(german, text != null && !text.isEmpty());
		} else {
			assertEquals(german, text);
		}
	}

	private static String text(TLObject obj, String attribute, Locale locale) {
		ResKey value = (ResKey) obj.tValueByName(attribute);
		if (value == null) {
			return null;
		}
		assertTrue(String.valueOf(value), value instanceof LiteralKey);
		return ((LiteralKey) value).getTranslations().get(locale);
	}

	private static TLObject only(String type, String extId) {
		List<TLObject> result = instances(type).stream().filter(obj -> extId.equals(obj.tValueByName("extId"))).toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static Importer importProject(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer;
	}

	/**
	 * The texts of the application program of a project file.
	 */
	private static final class ProgramTexts {

		String _programId;

		String _programName;

		/** The <code>Text</code> of each parameter, by its ID. */
		final Map<String, String> _parameters = new LinkedHashMap<>();

		/** The non-empty German translations of the <code>Text</code> of elements, by their IDs. */
		final Map<String, String> _translated = new HashMap<>();

		static ProgramTexts read(String file) throws IOException, XMLStreamException {
			ProgramTexts result = new ProgramTexts();
			try (ZipFile zip = new ZipFile(file)) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					if (entry.getName().matches("M-0008/M-0008_A-.*\\.xml")) {
						try (InputStream in = zip.getInputStream(entry)) {
							result.read(XMLStreamUtil.getDefaultInputFactory().createXMLStreamReader(in));
						}
					}
				}
			}
			assertNotNull(file, result._programId);
			return result;
		}

		private void read(XMLStreamReader in) throws XMLStreamException {
			String element = null;
			String language = null;
			while (in.hasNext()) {
				if (in.next() != XMLStreamConstants.START_ELEMENT) {
					continue;
				}
				switch (in.getLocalName()) {
					case "ApplicationProgram":
						_programId = in.getAttributeValue(null, "Id");
						_programName = in.getAttributeValue(null, "Name");
						break;
					case "Parameter":
						_parameters.put(in.getAttributeValue(null, "Id"), Objects.toString(in.getAttributeValue(null, "Text"), ""));
						break;
					case "Language":
						language = in.getAttributeValue(null, "Identifier");
						break;
					case "TranslationElement":
						element = in.getAttributeValue(null, "RefId");
						break;
					case "Translation":
						String text = in.getAttributeValue(null, "Text");
						if ("de-DE".equals(language) && "Text".equals(in.getAttributeValue(null, "AttributeName"))
							&& text != null && !text.isEmpty()) {
							_translated.put(element, text);
						}
						break;
					default:
						break;
				}
			}
		}

	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAlpha11ImportProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
