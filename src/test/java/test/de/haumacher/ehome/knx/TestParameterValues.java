/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.annotate.util.ConstraintCheck.ConstraintType;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;

/**
 * Test of the typed parameter values ({@link ParameterValues}, <code>ehome.operational:ParameterValue</code>):
 * decoding on import, the ETS notation and display derived from them, the defaults of placed
 * parameters, the type properties, the constraints, and a repeated import.
 */
@SuppressWarnings("javadoc")
public class TestParameterValues extends BasicTestCase {

	private static final String SAMPLE = "samples/Test.knxproj";

	private static final String TYPED = "src/test/fixtures/TestDynamic.knxproj";

	private static final String TYPED_PROGRAM = "M-0008_A-10D7-11-A0E5-O000D_";

	private static final List<String> HOLDER_TYPES = List.of("ehome.operational:Parameter",
		"ehome.operational:ParameterView", "ehome.physical:ParameterSetting");

	public void testParameterValues() throws Exception {
		importSample(SAMPLE);
		Map<String, Integer> counts = counts();
		checkRoundTrip(SAMPLE);
		checkDefaults();

		// A second import of the same project reuses the program: nothing is duplicated, and no
		// holder loses its value.
		importSample(SAMPLE);
		assertEquals(counts, counts());
		checkRoundTrip(SAMPLE);

		importSample(TYPED);
		checkRoundTrip(TYPED);
		checkTypedFixture();
		checkConstraints();
	}

	/**
	 * Every value of the given project, as ETS wrote it, is the derived ETS notation of its holder.
	 */
	private void checkRoundTrip(String file) throws Exception {
		Map<String, String> expected = etsValues(file);
		int checked = 0;
		for (String typeName : HOLDER_TYPES) {
			for (TLObject holder : instances(typeName)) {
				String key = key(holder);
				if (!expected.containsKey(key)) {
					// From another file.
					continue;
				}
				String value = expected.get(key);
				assertEquals("Value of " + key + " (" + typeName + ")", value == null || value.isEmpty() ? null : value,
					derived(holder, "encodedValue"));
				checked++;
			}
		}
		assertTrue("Only " + checked + " values checked.", checked >= expected.size());
	}

	private static String key(TLObject holder) {
		if (holder.tType().getPart("extId") != null) {
			return (String) holder.tValueByName("extId");
		}
		// A setting: the device and the placed parameter it sets.
		return holder.tContainer().tValueByName("extId") + "/"
			+ ((TLObject) holder.tValueByName("parameter")).tValueByName("extId");
	}

	/**
	 * The values of parameters, parameter references and device settings of an ETS file by the key
	 * of their holder (see {@link #key(TLObject)}); <code>null</code> for a reference without a
	 * default of its own.
	 */
	private static Map<String, String> etsValues(String file) throws Exception {
		Map<String, String> result = new HashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
			InputStream entryIn = new FilterInputStream(zip) {
				@Override
				public void close() throws IOException {
					// Keep the archive open.
				}
			};
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.getName().endsWith(".xml")) {
					continue;
				}
				XMLStreamReader in = XMLInputFactory.newInstance().createXMLStreamReader(entryIn);
				String device = null;
				while (in.hasNext()) {
					if (in.next() != XMLStreamConstants.START_ELEMENT) {
						continue;
					}
					switch (in.getLocalName()) {
						case "Parameter":
							if (in.getAttributeValue(null, "ParameterType") != null) {
								result.put(in.getAttributeValue(null, "Id"), in.getAttributeValue(null, "Value"));
							}
							break;
						case "ParameterRef":
							result.put(in.getAttributeValue(null, "Id"), in.getAttributeValue(null, "Value"));
							break;
						case "DeviceInstance":
							device = in.getAttributeValue(null, "Id");
							break;
						case "ParameterInstanceRef":
							result.put(device + "/" + in.getAttributeValue(null, "RefId"),
								in.getAttributeValue(null, "Value"));
							break;
						default:
							break;
					}
				}
				in.close();
			}
		}
		return result;
	}

	private void checkDefaults() {
		// A reference without a default of its own takes that of its parameter.
		TLObject inherited = byExtId("ehome.operational:ParameterView", "M-0008_A-10D7-11-A0E5-O000A_P-3766_R-3766");
		Parameter parameter = ((ParameterView) inherited).getParameter();
		assertSame(parameter, derived(inherited, "defaultSource"));
		assertNull(derived(inherited, "encodedValue"));
		assertEquals("3", derived(parameter, "displayValue"));
		assertEquals(derived(parameter, "displayValue"), derived(inherited, "displayDefault"));

		// A reference with a default of its own: an entry of the parameter's enumeration type.
		TLObject own = byExtId("ehome.operational:ParameterView", "M-0008_A-10D7-11-A0E5-O000A_P-3769_R-3770");
		assertSame(own, derived(own, "defaultSource"));
		assertEquals("1", derived(own, "encodedValue"));
		assertEquals("0", derived(((ParameterView) own).getParameter(), "encodedValue"));
		EnumerationValue choice = (EnumerationValue) own.tValueByName("choice");
		assertTrue(((EnumerationType) ((ParameterView) own).getParameter().getType()).getValues().contains(choice));
		assertEquals(derived(own, "displayValue"), derived(own, "displayDefault"));
		assertNotNull(derived(own, "displayDefault"));
	}

	private void checkTypedFixture() {
		Parameter decimal = parameter("9001");
		FloatingPointType floatType = (FloatingPointType) decimal.getType();
		assertEquals("DPT9", floatType.getEncoding().getName());
		assertEquals(0.0, floatType.getMin(), 0.0);
		assertEquals(100.0, floatType.getMax(), 0.0);
		assertEquals(Integer.valueOf(1), floatType.getDecimals());
		assertEquals(Double.valueOf(20.5), decimal.getDecimal());
		assertEquals("20.5", derived(decimal, "encodedValue"));
		assertTrue((String) derived(decimal, "displayValue"), ((String) derived(decimal, "displayValue")).matches("20[.,]5"));

		// The device of the fixture sets the floating point parameter.
		TLObject setting = instances("ehome.physical:ParameterSetting").stream()
			.filter(s -> ((ParameterView) s.tValueByName("parameter")).getParameter() == decimal)
			.findFirst().orElseThrow();
		assertEquals(Double.valueOf(21.75), setting.tValueByName("decimal"));
		assertEquals("21.75", derived(setting, "encodedValue"));
		// Shown with the one decimal place of the type's display format.
		assertTrue((String) derived(setting, "displayValue"), ((String) derived(setting, "displayValue")).matches("21[.,]8"));
		assertTrue((String) derived(setting, "displayDefault"), ((String) derived(setting, "displayDefault")).matches("20[.,]5"));

		Parameter time = parameter("9002");
		TimeType timeType = (TimeType) time.getType();
		assertEquals("Seconds", timeType.getUnit().getName());
		assertEquals(16, timeType.getBits());
		assertEquals(0L, timeType.getMin());
		assertEquals(3600L, timeType.getMax());
		assertEquals(Long.valueOf(90), time.getNumber());
		assertEquals("90", derived(time, "encodedValue"));
		assertEquals("90 s", derived(time, "displayValue"));

		Parameter text = parameter("9003");
		TextType textType = (TextType) text.getType();
		assertEquals(80, textType.getBits());
		assertEquals("[A-ZÄÖÜ][a-zäöüß]+", textType.getPattern());
		assertEquals("Küche", text.getText());
		assertEquals("Küche", derived(text, "encodedValue"));
		assertEquals("Küche", derived(text, "displayValue"));

		Parameter date = parameter("9004");
		assertEquals(Boolean.FALSE, (Object) ((DateType) date.getType()).getShowsYear());
		assertEquals("2025-08-06", date.getText());
		assertEquals("2025-08-06", derived(date, "encodedValue"));

		Parameter address = parameter("9005");
		IPAddressType addressType = (IPAddressType) address.getType();
		assertEquals("GatewayAddress", addressType.getAddressKind().getName());
		// No version in the file means IPv4.
		assertEquals("IPv4", addressType.getVersion().getName());
		assertEquals("192.168.1.1", address.getText());
		assertEquals("192.168.1.1", derived(address, "encodedValue"));

		// Each of them is placed in the structure, so that the program view shows it.
		for (String id : List.of("9001", "9002", "9003", "9004", "9005")) {
			ParameterView view = (ParameterView) byExtId("ehome.operational:ParameterView",
				TYPED_PROGRAM + "P-" + id + "_R-" + id);
			assertNotNull(view.tContainer());
			assertSame(view.getParameter(), derived(view, "defaultSource"));
			assertSame(view.getParameter().getType(), derived(view, "valueType"));
		}
		assertSame(decimal.getType(), derived(setting, "valueType"));
	}

	private void checkConstraints() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject number = setting("Vergleichswert (0...255)");
		TLObject led = setting("Funktion der Status-LED rechts");
		Parameter text = parameter("9003");
		Parameter decimal = parameter("9001");
		EnumerationType ledType = (EnumerationType) ((ParameterView) led.tValueByName("parameter")).getParameter().getType();
		EnumerationValue foreign = instances("ehome.operational:EnumerationType").stream()
			.map(EnumerationType.class::cast)
			.filter(t -> t != ledType)
			.flatMap(t -> t.getValues().stream())
			.findFirst().orElseThrow();

		// The imported values are valid.
		assertNull(problem(number, "number"));
		assertNull(problem(led, "choice"));
		assertNull(warning(text, "text"));
		assertNull(problem(decimal, "decimal"));

		try (Transaction tx = kb.beginTransaction()) {
			number.tUpdateByName("number", 255L);
			assertNull(problem(number, "number"));
			number.tUpdateByName("number", 256L);
			assertNotNull("Number above the maximum accepted.", problem(number, "number"));
			assertTrue(problem(number, "number").toString(), problem(number, "number").toString().contains("255"));

			led.tUpdateByName("choice", foreign);
			assertNotNull("Entry of another type accepted.", problem(led, "choice"));

			// Size and pattern of a text are warnings, never errors.
			text.setText("Abcdefghijk");
			assertNotNull("Text longer than its size accepted.", warning(text, "text"));
			assertNull(problem(text, "text"));
			text.setText("küche");
			assertNotNull("Text not matching the pattern accepted.", warning(text, "text"));
			assertNull(problem(text, "text"));
			text.setText("Bad");
			assertNull(warning(text, "text"));

			// A pattern Java cannot compile (ETS writes .NET expressions) restricts nothing.
			TextType textType = (TextType) text.getType();
			textType.setPattern("(?<name");
			text.setText("küche");
			assertNull(warning(text, "text"));

			decimal.setDecimal(100.5);
			assertNotNull("Decimal above the maximum accepted.", problem(decimal, "decimal"));

			// A value that does not fit its type is kept as text, not lost, and reported.
			TLObject numberParameter = ((ParameterView) number.tValueByName("parameter")).getParameter();
			numberParameter.tUpdateByName("number", null);
			assertNotNull(ParameterValues.decode(numberParameter, "300"));
			assertNull(numberParameter.tValueByName("number"));
			assertEquals("300", numberParameter.tValueByName("text"));
			assertEquals("300", ParameterValues.encode(numberParameter));
			assertNotNull(ParameterValues.decode(numberParameter, "zwölf"));
			assertEquals("zwölf", ParameterValues.encode(numberParameter));
			tx.rollback();
		}

		// The constraints guard the database as well, not only the forms.
		try (Transaction tx = kb.beginTransaction()) {
			number.tUpdateByName("number", 300L);
			try {
				tx.commit();
				fail("Number above the maximum committed.");
			} catch (Exception ex) {
				// Expected.
			}
		}
		assertEquals(Long.valueOf(42), number.tValueByName("number"));

		// A text violating its pattern is a warning only: it is committed, so that a manufacturer's
		// pattern never rejects an import.
		try (Transaction tx = kb.beginTransaction()) {
			text.setText("küche");
			tx.commit();
		}
		assertEquals("küche", text.getText());
		assertEquals("küche", derived(text, "encodedValue"));
		try (Transaction tx = kb.beginTransaction()) {
			text.setText("Küche");
			tx.commit();
		}

		assertTrue(ParameterValues.matchesPattern("Küche", "[A-ZÄÖÜ][a-zäöüß]+"));
		assertFalse(ParameterValues.matchesPattern("Küche 2", "[A-ZÄÖÜ][a-zäöüß]+"));
		assertTrue(ParameterValues.matchesPattern("anything", "(?<name"));
	}

	/**
	 * The first problem the constraints of the given attribute report for the current value, as
	 * the commit check sees it (without a user).
	 */
	private static ResKey problem(TLObject obj, String attribute) {
		return report(obj, attribute, ConstraintType.ERROR);
	}

	/**
	 * The first warning the constraints of the given attribute report for the current value.
	 */
	private static ResKey warning(TLObject obj, String attribute) {
		return report(obj, attribute, ConstraintType.WARNING);
	}

	private static ResKey report(TLObject obj, String attribute, ConstraintType kind) {
		TLStructuredTypePart part = obj.tType().getPart(attribute);
		TLConstraints constraints = part.getAnnotation(TLConstraints.class);
		assertNotNull("No constraints at " + part, constraints);
		for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
			ConstraintCheck check = SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY.getInstance(config);
			if (check.type() != kind) {
				continue;
			}
			ResKey problem = check.check(obj, part);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private static TLObject setting(String name) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject setting : instances("ehome.physical:ParameterSetting")) {
			if (name.equals(((String) derived(setting, "name")).trim())) {
				result.add(setting);
			}
		}
		assertFalse(result.isEmpty());
		return result.get(0);
	}

	private static Parameter parameter(String id) {
		return (Parameter) byExtId("ehome.operational:Parameter", TYPED_PROGRAM + "P-" + id);
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals(1, result.size());
		return result.get(0);
	}

	private static Map<String, Integer> counts() {
		Map<String, Integer> result = new HashMap<>();
		for (String typeName : List.of("ehome.operational:Parameter", "ehome.operational:ParameterView",
			"ehome.operational:EnumerationValue", "ehome.operational:EnumerationType",
			"ehome.operational:NumericType", "ehome.operational:TextType", "ehome.operational:VoidType")) {
			result.put(typeName, instances(typeName).size());
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterValues.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
