/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
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
 * Probe of the languages of a known program (haumacher/ehome #38) with cases the delivery's test
 * does not cover: a program from a database before #38 (no recorded languages) gains nothing, and
 * the file written in English imported first, then the German one, adds nothing either.
 */
@SuppressWarnings("javadoc")
public class TestProgramLanguagesProbe extends BasicTestCase {

	private static final String A = "src/test/fixtures/TestLanguagesA.knxprod";

	private static final String B = "src/test/fixtures/TestLanguagesB.knxprod";

	private static final String GERMAN_FIRST = "M-0008_A-FFF3-01-0000";

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

	public void testOldProgramGainsNothing() throws Exception {
		importProduct(A);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			program(GERMAN_FIRST).tUpdateByName("languages", null);
			tx.commit();
		}
		String before = name(GERMAN_FIRST, Locale.ENGLISH);
		for (TLObject program : importProduct(B).getAddedLanguages().keySet()) {
			assertFalse(GERMAN_FIRST.equals(program.tValueByName("extId")));
		}
		assertNull(program(GERMAN_FIRST).tValueByName("languages"));
		assertEquals(before, name(GERMAN_FIRST, Locale.ENGLISH));
	}

	public void testEnglishFileFirst() throws Exception {
		importProduct(B);
		assertEquals("de en", program(GERMAN_FIRST).tValueByName("languages"));
		String english = name(GERMAN_FIRST, Locale.ENGLISH);
		String german = name(GERMAN_FIRST, Locale.GERMAN);
		importProduct(A);
		assertEquals("de en", program(GERMAN_FIRST).tValueByName("languages"));
		assertEquals(english, name(GERMAN_FIRST, Locale.ENGLISH));
		assertEquals(german, name(GERMAN_FIRST, Locale.GERMAN));
		assertEquals("de en", program(ENGLISH_FIRST).tValueByName("languages"));
		assertEquals("TEST: languages 2 (artificial test data)", name(ENGLISH_FIRST, Locale.ENGLISH));
		assertEquals("TEST: Sprachen 2 (künstliche Testdaten)", name(ENGLISH_FIRST, Locale.GERMAN));
	}

	private static String name(String program, Locale locale) {
		return ResKeyUtil.getTranslation((ResKey) program(program).tValueByName("name"), locale);
	}

	private static TLObject program(String id) {
		List<TLObject> result = instances(PROGRAM).stream().filter(obj -> id.equals(obj.tValueByName("extId"))).toList();
		assertEquals(id, 1, result.size());
		return result.get(0);
	}

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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgramLanguagesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
