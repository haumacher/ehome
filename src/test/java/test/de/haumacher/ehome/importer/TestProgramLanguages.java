/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;

/**
 * The languages of a known application program (haumacher/ehome #38): a program imported again from
 * a file written in another language gains the texts of the languages it lacks and keeps those it
 * has, so that the texts do not depend on the order of the imports.
 *
 * <p>
 * The fixtures <code>TestLanguagesA.knxprod</code> and <code>TestLanguagesB.knxprod</code>
 * (<code>make-fixtures.py</code>) hold two small artificial programs, each once in German (German
 * only) and once in English (with German and English translations); A holds the first in German and
 * the second in English, B the other way round. Importing A, then B imports each program in one of
 * the two orders. <code>TestRoundTrip</code> checks the same with the samples
 * (<code>Test.knxproj</code>, then <code>EHomeDemo.knxproj</code>).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProgramLanguages extends BasicTestCase {

	private static final String A = "src/test/fixtures/TestLanguagesA.knxprod";

	private static final String B = "src/test/fixtures/TestLanguagesB.knxprod";

	/** The program written in German in A, in English in B. */
	private static final String GERMAN_FIRST = "M-0008_A-FFF3-01-0000";

	/** The program written in English in A, in German in B. */
	private static final String ENGLISH_FIRST = "M-0008_A-FFF4-01-0000";

	private static final String PROGRAM = "ehome.operational:Program";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		deletePrograms();
	}

	@Override
	protected void tearDown() throws Exception {
		deletePrograms();
		super.tearDown();
	}

	/**
	 * Both orders give each program the German and the English texts of the files, and record both
	 * languages.
	 */
	public void testImportOrders() throws Exception {
		assertEquals(Map.of(), importProduct(A).getAddedLanguages());
		assertEquals("de", program(GERMAN_FIRST).tValueByName("languages"));
		assertEquals("de en", program(ENGLISH_FIRST).tValueByName("languages"));
		// A text of a language the program lacks is a stand-in, the text of the base language.
		assertEquals("Verzögerung", text(GERMAN_FIRST, "ehome.operational:Parameter", "_P-2", "description",
			Locale.ENGLISH));

		assertEquals(Map.of(GERMAN_FIRST, Set.of(Locale.ENGLISH)), added(importProduct(B)));
		for (String program : List.of(GERMAN_FIRST, ENGLISH_FIRST)) {
			assertEquals("de en", program(program).tValueByName("languages"));
			assertEquals(expected(program), texts(program));
		}

		// Nothing more to add.
		assertEquals(Map.of(), importProduct(A).getAddedLanguages());
		assertEquals(Map.of(), importProduct(B).getAddedLanguages());
		assertEquals(expected(GERMAN_FIRST), texts(GERMAN_FIRST));
	}

	/**
	 * The texts of a language a known program has stay as they are, also when edited after the
	 * import; those of a language it lacks are added.
	 */
	public void testKeepsTexts() throws Exception {
		importProduct(A);
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			setText(ENGLISH_FIRST, "ehome.operational:Parameter", "_P-2", "description", Locale.ENGLISH, "Edited");
			setText(GERMAN_FIRST, "ehome.operational:Channel", "_CH-1", "description", Locale.GERMAN, "Bearbeitet");
			tx.commit();
		}

		importProduct(B);
		assertEquals("Edited", text(ENGLISH_FIRST, "ehome.operational:Parameter", "_P-2", "description",
			Locale.ENGLISH));
		assertEquals("Verzögerung", text(ENGLISH_FIRST, "ehome.operational:Parameter", "_P-2", "description",
			Locale.GERMAN));
		assertEquals("Bearbeitet", text(GERMAN_FIRST, "ehome.operational:Channel", "_CH-1", "description",
			Locale.GERMAN));
		assertEquals("Channel A", text(GERMAN_FIRST, "ehome.operational:Channel", "_CH-1", "description",
			Locale.ENGLISH));
	}

	/**
	 * The texts both files give each program: German, and English with the en-US translation
	 * "Switch-on delay" replacing the English text "Delay".
	 */
	private static Map<String, String> expected(String program) {
		String number = program.equals(GERMAN_FIRST) ? "1" : "2";
		Map<String, String> result = new LinkedHashMap<>();
		result.put("name de", "TEST: Sprachen " + number + " (künstliche Testdaten)");
		result.put("name en", "TEST: languages " + number + " (artificial test data)");
		result.put("value de", "Schalten");
		result.put("value en", "Switching");
		result.put("parameter de", "Verzögerung");
		result.put("parameter en", "Switch-on delay");
		result.put("port de", "Rückmeldung");
		result.put("port en", "Feedback");
		result.put("channel de", "Kanal A");
		result.put("channel en", "Channel A");
		result.put("group de", "Allgemein");
		result.put("group en", "General");
		return result;
	}

	private static Map<String, String> texts(String program) {
		Map<String, String> result = new LinkedHashMap<>();
		for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
			String language = " " + locale.getLanguage();
			result.put("name" + language, text(program(program), "name", locale));
		}
		texts(result, "value", program, "ehome.operational:EnumerationValue", "_PT-Betriebsart_EN-1", "name");
		texts(result, "parameter", program, "ehome.operational:Parameter", "_P-2", "description");
		texts(result, "port", program, "ehome.operational:Port", "_O-1", "text");
		texts(result, "channel", program, "ehome.operational:Channel", "_CH-1", "description");
		texts(result, "group", program, "ehome.operational:ParameterGroup", "_PB-1", "description");
		Map<String, String> sorted = new LinkedHashMap<>();
		for (String key : expected(program).keySet()) {
			sorted.put(key, result.get(key));
		}
		return sorted;
	}

	private static void texts(Map<String, String> result, String key, String program, String type, String suffix,
			String attribute) {
		for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
			result.put(key + " " + locale.getLanguage(), text(program, type, suffix, attribute, locale));
		}
	}

	private static String text(String program, String type, String suffix, String attribute, Locale locale) {
		return text(object(type, program + suffix), attribute, locale);
	}

	private static String text(TLObject obj, String attribute, Locale locale) {
		return ResKeyUtil.getTranslation((ResKey) obj.tValueByName(attribute), locale);
	}

	private static void setText(String program, String type, String suffix, String attribute, Locale locale,
			String text) {
		TLObject obj = object(type, program + suffix);
		ResKey.Builder builder = ResKey.builder();
		for (Locale other : List.of(Locale.GERMAN, Locale.ENGLISH)) {
			builder.add(other, other.equals(locale) ? text : text(obj, attribute, other));
		}
		obj.tUpdateByName(attribute, builder.build());
	}

	private static Map<String, Set<Locale>> added(Importer importer) {
		Map<String, Set<Locale>> result = new LinkedHashMap<>();
		importer.getAddedLanguages().forEach((program, languages) -> result.put((String) program.tValueByName("extId"),
			languages));
		return result;
	}

	private static TLObject program(String id) {
		return object(PROGRAM, id);
	}

	private static TLObject object(String type, String id) {
		List<TLObject> result = instances(type).stream().filter(obj -> id.equals(obj.tValueByName("extId"))).toList();
		assertEquals(id, 1, result.size());
		return result.get(0);
	}

	/**
	 * Imports the given product file in a transaction of its own.
	 */
	private static Importer importProduct(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static void deletePrograms() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject program : instances(PROGRAM)) {
				if (Set.of(GERMAN_FIRST, ENGLISH_FIRST).contains(program.tValueByName("extId"))) {
					program.tDelete();
				}
			}
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgramLanguages.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
