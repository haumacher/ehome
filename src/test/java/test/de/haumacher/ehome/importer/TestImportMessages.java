/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;

/**
 * Test of the messages of an ETS import ({@link Importer#getMessages()}): a value that does not fit
 * its type is kept as text and reported as a warning naming the value, the parameter and its
 * location in the project file, without changing the outcome of the import.
 */
@SuppressWarnings("javadoc")
public class TestImportMessages extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestDynamic.knxproj";

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000E";

	public void testWarnings() throws Exception {
		// Other tests of the suite may have imported the fixture into the same database before.
		boolean programKnown = !programs().isEmpty();

		List<BufferingI18NLog.Entry> sample = importSample("samples/Test.knxproj");
		assertEquals(texts(sample), 0, sample.size());

		List<BufferingI18NLog.Entry> messages = importSample(FIXTURE);
		// The setting of the device is imported with every project; the value of the parameter
		// only with the program, which an import skips when it is known.
		assertEquals(texts(messages), programKnown ? 1 : 2, messages.size());
		for (BufferingI18NLog.Entry warning : messages) {
			assertEquals(Level.WARN, warning.getLevel());
			String text = text(warning);
			// The parameter, the value, the reason, and the location: the entry of the archive and
			// the line.
			assertTrue(text, text.contains("\"Helligkeit\""));
			assertTrue(text, text.contains("\"300\""));
			assertTrue(text, text.contains("Test_Percent"));
			assertTrue(text, text.matches("(?s).*\\bline [\\d,]+ column \\d+.*"));
			// No path of the server.
			assertFalse(text, text.contains("file:"));
		}
		String setting = text(messages.get(messages.size() - 1));
		assertTrue(setting, setting.contains(" " + Importer.ENTRY_SCHEME + "P-01A0/0.xml "));
		if (!programKnown) {
			String parameter = text(messages.get(0));
			assertTrue(parameter, parameter.contains(" " + Importer.ENTRY_SCHEME + "M-0008/" + PROGRAM + ".xml "));
		}

		// The values are kept as text, and the import succeeded.
		Parameter parameter = (Parameter) instances("ehome.operational:Parameter").stream()
			.filter(p -> (PROGRAM + "_P-9006").equals(p.tValueByName("extId")))
			.findFirst().orElseThrow();
		assertEquals("300", parameter.getText());
		assertNull(parameter.getNumber());
		List<TLObject> settings = instances("ehome.physical:ParameterSetting").stream()
			.filter(s -> ((ParameterView) s.tValueByName("parameter")).getParameter() == parameter)
			.toList();
		assertFalse(settings.isEmpty());
		for (TLObject value : settings) {
			assertEquals("300", value.tValueByName("text"));
			assertNull(value.tValueByName("number"));
		}
		assertEquals(1, programs().size());

		// The program is known now: a second import skips it and reports the setting only.
		List<BufferingI18NLog.Entry> again = importSample(FIXTURE);
		assertEquals(texts(again), 1, again.size());
		assertEquals(setting, text(again.get(0)));
		assertEquals(1, programs().size());
	}

	private static List<TLObject> programs() {
		return instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId")))
			.toList();
	}

	private static String texts(List<BufferingI18NLog.Entry> messages) {
		return String.join("\n", messages.stream().map(TestImportMessages::text).toList());
	}

	private static String text(BufferingI18NLog.Entry message) {
		return ResourcesModule.getInstance().getBundle(Locale.ENGLISH).getString(message.getMessage());
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImportMessages.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
