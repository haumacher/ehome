/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.TestEtsImport;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * Probe of the typed parameter values composed with the other features: several projects in one
 * database, a known program imported again, binary enumerations, parameters without a value, user
 * edits that survive a re-import, a placement whose parameter changes its type, and a floating
 * point type without a display format.
 */
public class TestParameterValuesProbe extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000A_";

	private static final String TYPED_PROGRAM = "M-0008_A-10D7-11-A0E5-O000D_";

	public void testProbe() throws Exception {
		importSample("src/test/fixtures/TestDetails.knxproj");
		importSample("src/test/fixtures/TestDynamic.knxproj");
		Map<String, Integer> counts = counts();

		// The program of the sample is known from the first fixture: nothing is added again.
		importSample("samples/Test.knxproj");
		assertEquals(counts, counts());

		checkProgramTypes();
		checkBinaryEnumeration();
		checkVoidParameters();
		checkInvariants();
		checkLanguages();
		checkEditSurvivesReimport();
		checkTypeChange();
		checkDecimalWithoutFormat();
	}

	/** The types of the typed fixture belong to its program, so the types tab lists them. */
	private void checkProgramTypes() {
		TLObject program = byExtId("ehome.operational:Program", TYPED_PROGRAM.substring(0, TYPED_PROGRAM.length() - 1));
		Collection<?> types = (Collection<?>) program.tValueByName("parameterTypes");
		Map<String, String> ids = Map.of(
			"PT-Test.5FFloat", "ehome.operational:FloatingPointType",
			"PT-Test.5FTime", "ehome.operational:TimeType",
			"PT-Test.5FPattern", "ehome.operational:TextType",
			"PT-Test.5FDate", "ehome.operational:DateType",
			"PT-Test.5FIP", "ehome.operational:IPAddressType");
		for (Map.Entry<String, String> id : ids.entrySet()) {
			assertTrue(id.getKey(), types.contains(byExtId(id.getValue(), TYPED_PROGRAM + id.getKey())));
		}
	}

	/** An enumeration over binary values: its entry is chosen by the numeric value ETS writes. */
	private void checkBinaryEnumeration() {
		Parameter hardware = (Parameter) byExtId("ehome.operational:Parameter", PROGRAM + "P-3720");
		EnumerationType type = (EnumerationType) hardware.getType();
		assertEquals("BinaryValue", type.getKind().getName());
		EnumerationValue choice = hardware.getChoice();
		assertNotNull("Binary enumeration not decoded.", choice);
		assertEquals(1, choice.getValue());
		assertTrue(type.getValues().contains(choice));
		assertEquals("1", derived(hardware, "encodedValue"));
		assertEquals("$00 0a 00 00 00 3d", derived(hardware, "displayValue"));
		assertNull(hardware.getText());
	}

	/** A parameter of a type without values holds nothing. */
	private void checkVoidParameters() {
		List<Parameter> voids = instances("ehome.operational:Parameter").stream()
			.map(Parameter.class::cast)
			.filter(p -> p.getType() instanceof VoidType)
			.toList();
		assertFalse(voids.isEmpty());
		for (Parameter p : voids) {
			assertNull(p.getChoice());
			assertNull(p.getNumber());
			assertNull(p.getDecimal());
			assertNull(p.getText());
			assertNull(derived(p, "encodedValue"));
			assertNull(derived(p, "displayValue"));
		}
	}

	/** The fallback of a placed parameter's default, over every placement and every setting. */
	private void checkInvariants() {
		int own = 0;
		for (TLObject obj : instances("ehome.operational:ParameterView")) {
			ParameterView view = (ParameterView) obj;
			TLObject source = (TLObject) derived(view, "defaultSource");
			boolean hasOwn = derived(view, "encodedValue") != null;
			assertSame(hasOwn ? view : view.getParameter(), source);
			assertEquals(derived(source, "displayValue"), derived(view, "displayDefault"));
			assertSame(view.getParameter().getType(), derived(view, "valueType"));
			if (hasOwn) {
				own++;
			}
		}
		assertTrue("No placement with a default of its own.", own > 0);
		for (TLObject setting : instances("ehome.physical:ParameterSetting")) {
			TLObject view = (TLObject) setting.tValueByName("parameter");
			assertEquals(derived(view, "displayDefault"), derived(setting, "displayDefault"));
			assertNotNull("Setting without a value: " + derived(setting, "name"), derived(setting, "encodedValue"));
		}
	}

	/** The display of a value follows the language: the unit of a time, the decimal separator. */
	private void checkLanguages() {
		TLObject time = byExtId("ehome.operational:ParameterView", TYPED_PROGRAM + "P-9002_R-9002");
		TLObject decimal = byExtId("ehome.operational:ParameterView", TYPED_PROGRAM + "P-9001_R-9001");
		assertEquals("90 s", inLocale(Locale.GERMAN, time, "displayDefault"));
		assertEquals("90 s", inLocale(Locale.ENGLISH, time, "displayDefault"));
		assertEquals("20,5", inLocale(Locale.GERMAN, decimal, "displayDefault"));
		assertEquals("20.5", inLocale(Locale.ENGLISH, decimal, "displayDefault"));
	}

	/** A default the user sets in the catalog stays when the project is imported again. */
	private void checkEditSurvivesReimport() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ParameterView view = (ParameterView) byExtId("ehome.operational:ParameterView", PROGRAM + "P-696_R-696");
		assertNull(derived(view, "encodedValue"));
		EnumerationType type = (EnumerationType) view.getParameter().getType();
		EnumerationValue other = type.getValues().stream()
			.filter(v -> v != view.getParameter().getChoice())
			.findFirst().orElseThrow();
		try (Transaction tx = kb.beginTransaction()) {
			view.setChoice(other);
			tx.commit();
		}
		assertSame(view, derived(view, "defaultSource"));
		assertEquals(Integer.toString(other.getValue()), derived(view, "encodedValue"));
		assertEquals(derived(view, "displayValue"), derived(view, "displayDefault"));

		importSample("src/test/fixtures/TestDetails.knxproj");
		assertSame(other, view.getChoice());
		assertEquals(Integer.toString(other.getValue()), derived(view, "encodedValue"));
	}

	/** A placement whose parameter changes to one of another kind: the old value is refused, the new one accepted. */
	private void checkTypeChange() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ParameterView view = (ParameterView) byExtId("ehome.operational:ParameterView", PROGRAM + "P-696_R-696");
		Parameter numeric = instances("ehome.operational:Parameter").stream()
			.map(Parameter.class::cast)
			.filter(p -> p.getType() instanceof NumericType)
			.findFirst().orElseThrow();
		NumericType type = (NumericType) numeric.getType();
		try (Transaction tx = kb.beginTransaction()) {
			view.setParameter(numeric);
			assertNotNull("Entry of the old type accepted for the new one.", problem(view, "choice"));
			view.setChoice(null);
			assertNull(problem(view, "choice"));
			view.setNumber(type.getMax());
			assertNull(problem(view, "number"));
			assertEquals(Long.toString(type.getMax()), derived(view, "encodedValue"));
			assertSame(view, derived(view, "defaultSource"));
			view.setNumber(type.getMax() + 1);
			assertNotNull("Number above the maximum accepted.", problem(view, "number"));
			tx.rollback();
		}
	}

	/** A floating point type without display format shows the value as it is. */
	private void checkDecimalWithoutFormat() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
			FloatingPointType type = factory.createFloatingPointType();
			type.setName("Probe");
			type.setEncoding((TLClassifier) TLModelUtil.findPart("ehome.operational:FloatEncoding#DPT9"));
			type.setMin(0);
			type.setMax(10);
			Parameter parameter = factory.createParameter();
			parameter.setName("probe");
			parameter.setType(type);
			parameter.setAccess((TLClassifier) TLModelUtil.findPart("ehome.operational:AccessKind#ReadWrite"));
			parameter.setDecimal(2.5);
			assertEquals("2.5", derived(parameter, "encodedValue"));
			assertEquals("2,5", inLocale(Locale.GERMAN, parameter, "displayValue"));
			parameter.setDecimal(3.0);
			assertEquals("3", derived(parameter, "encodedValue"));
			assertEquals("3", inLocale(Locale.GERMAN, parameter, "displayValue"));
			assertNull(problem(parameter, "decimal"));
			parameter.setDecimal(10.5);
			assertNotNull(problem(parameter, "decimal"));
			tx.rollback();
		}
	}

	/** A derived attribute read as root (like {@link TestEtsImport#derived}), in the given language. */
	private static Object inLocale(Locale locale, TLObject obj, String attribute) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return obj.tValueByName(attribute);
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static ResKey problem(TLObject obj, String attribute) {
		TLStructuredTypePart part = obj.tType().getPart(attribute);
		TLConstraints constraints = part.getAnnotation(TLConstraints.class);
		assertNotNull("No constraints at " + part, constraints);
		for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
			ConstraintCheck check = SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY.getInstance(config);
			ResKey problem = check.check(obj, part);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals("Objects " + typeName + " with ID " + extId, 1, result.size());
		return result.get(0);
	}

	private static Map<String, Integer> counts() {
		Map<String, Integer> result = new HashMap<>();
		for (String typeName : List.of("ehome.operational:Program", "ehome.operational:Parameter",
			"ehome.operational:ParameterView", "ehome.operational:EnumerationValue",
			"ehome.operational:EnumerationType", "ehome.operational:FloatingPointType",
			"ehome.operational:TimeType")) {
			result.put(typeName, instances(typeName).size());
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterValuesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
