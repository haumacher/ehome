/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.catalog;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.knx.ProgramStructure;
import de.haumacher.ehome.knx.ProgramStructure.Position;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * Creates a catalog with a device from scratch the way the dialogs of the catalog view do: a
 * manufacturer, a catalog with a section, a product with a variant, the application program
 * "Tastsensor 3" with a parameter type of every kind, parameters with typed defaults, a
 * communication object and the configuration structure; then checks the external IDs, the labels,
 * the defaults, reordering, insertion after an element, and deleting.
 *
 * <p>
 * The objects are created with the factory and added to the references the dialogs persist into
 * (<code>catalogs.view.xml</code>, <code>create-node.view.xml</code>,
 * <code>catalogs/create-variant.view.xml</code>, <code>catalogs/create-program-element.view.xml</code>);
 * structure elements are placed with {@link ProgramStructure}, which the structure dialog calls. The
 * manufacturer is test data with a KNX ID no real manufacturer has.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCatalogFromScratch extends BasicTestCase {

	private static final int MANUFACTURER_ID = 0x7FF1;

	private KnowledgeBase _kb;

	private final List<TLObject> _created = new ArrayList<>();

	private TLObject _manufacturer;

	private TLObject _catalog;

	private TLObject _section;

	private TLObject _product;

	private TLObject _variant;

	private TLObject _assignment;

	private Program _program;

	private EnumerationType _enumeration;

	private final Map<String, Parameter> _parameters = new HashMap<>();

	private Port _port;

	private ChannelIndependent _independent;

	private ParameterGroup _general;

	private final List<Channel> _channels = new ArrayList<>();

	private Switch _switch;

	public void testTastsensor() throws Exception {
		_kb = PersistencyLayer.getKnowledgeBase();
		createCatalog();
		createProgram();
		createStructure();

		checkExtIds();
		checkLabels();
		checkDefaults();
		checkMoveAndInsert();
		checkDelete();
		checkCatalogTree();

		// The tests of the application share one database: remove the test data again.
		try (Transaction tx = _kb.beginTransaction()) {
			_manufacturer.tDelete();
			tx.commit();
		}
		// Everything of the manufacturer goes with it, the assignment owned by its product included.
		assertFalse(_program.tValid());
		assertFalse(_product.tValid());
		assertFalse(_assignment.tValid());
	}

	private void createCatalog() {
		TLObject root = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule().getSingleton("ROOT");
		try (Transaction tx = _kb.beginTransaction()) {
			// Manufacturer dialog: container ROOT, reference manufacturers.
			_manufacturer = create("ehome.catalog:Manufacturer");
			_manufacturer.tUpdateByName("name", "Gira (test data)");
			_manufacturer.tUpdateByName("id", MANUFACTURER_ID);
			add(root, "manufacturers", _manufacturer);
			tx.commit();
		}
		try (Transaction tx = _kb.beginTransaction()) {
			// Catalog dialog: container manufacturer, reference catalogs.
			_catalog = create("ehome.catalog:Catalog");
			_catalog.tUpdateByName("name", text("Push buttons", "Tastsensoren"));
			add(_manufacturer, "catalogs", _catalog);
			tx.commit();
		}
		try (Transaction tx = _kb.beginTransaction()) {
			// Section dialog: container catalog, reference sections.
			_section = create("ehome.catalog:Catalog");
			_section.tUpdateByName("name", text("Push buttons, 3-gang", "Taster, 3fach"));
			add(_catalog, "sections", _section);
			tx.commit();
		}
		try (Transaction tx = _kb.beginTransaction()) {
			// Product dialog (create-variant.view.xml): a new product of the manufacturer, the variant
			// as its first one, listed in the section - in one transaction.
			_variant = create("ehome.catalog:Variant");
			_variant.tUpdateByName("name", text("Push button 3 comfort, 3-gang", "Tastsensor 3 Komfort 3fach"));
			_variant.tUpdateByName("orderNumber", "5133 00");
			_product = create("ehome.catalog:Product");
			_product.tUpdateByName("name", "Tastsensor 3 Komfort 3fach");
			add(_manufacturer, "products", _product);
			add(_product, "variants", _variant);
			add(_section, "variants", _variant);
			tx.commit();
		}
		try (Transaction tx = _kb.beginTransaction()) {
			// Program dialog: container manufacturer, reference programs.
			_program = EhomeOperationalFactory.getInstance().createProgram();
			_program.tUpdateByName("name", text("Push button 3 comfort, 3-gang", "Tastsensor 3 Komfort 3fach"));
			add(_manufacturer, "programs", _program);
			tx.commit();
		}
		try (Transaction tx = _kb.beginTransaction()) {
			// Program assignment dialog: owned by the product of the selected variant.
			_assignment = create("ehome.catalog:ProgramAssignment");
			_assignment.tUpdateByName("program", _program);
			add((TLObject) _variant.tValueByName("product"), "programs", _assignment);
			tx.commit();
		}
		_created.addAll(List.of(_manufacturer, _catalog, _section, _product, _variant, _program, _assignment));
	}

	private void createProgram() {
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		Map<String, ParameterType> types = new HashMap<>();
		try (Transaction tx = _kb.beginTransaction()) {
			// Types dialog, one per kind: container program, reference parameterTypes. The form opens
			// with the defaults of the model, which are kept where nothing else is entered.
			VoidType none = factory.createVoidType();
			none.setName("Überschrift");
			types.put("void", none);

			TextType label = factory.createTextType();
			label.setName("Beschriftung");
			label.setPattern("[A-Za-z ]*");
			types.put("text", label);

			NumericType brightness = factory.createNumericType();
			brightness.setName("Helligkeit");
			types.put("number", brightness);

			FloatingPointType temperature = factory.createFloatingPointType();
			temperature.setName("Temperatur (°C)");
			temperature.setMin(5.0);
			temperature.setMax(35.0);
			temperature.setDecimals(1);
			types.put("float", temperature);

			_enumeration = factory.createEnumerationType();
			_enumeration.setName("Tastenfunktion");
			types.put("enum", _enumeration);

			TimeType delay = factory.createTimeType();
			delay.setName("Verzögerung");
			delay.setMax(600L);
			types.put("time", delay);

			DateType date = factory.createDateType();
			date.setName("Stichtag");
			types.put("date", date);

			IPAddressType gateway = factory.createIPAddressType();
			gateway.setName("Gateway");
			types.put("ip", gateway);

			for (ParameterType type : types.values()) {
				_program.addParameterType(type);
			}
			tx.commit();
		}
		assertEquals(8, _program.getParameterTypes().size());
		_created.addAll(types.values());

		// The defaults of the model fill the mandatory properties.
		NumericType number = (NumericType) types.get("number");
		assertEquals("unsignedInt", number.getKind().getName());
		assertEquals(8, number.getBits());
		assertEquals(0L, number.getMin());
		assertEquals(255L, number.getMax());
		assertEquals("Value", _enumeration.getKind().getName());
		assertEquals(8, _enumeration.getBits());
		assertEquals(112, ((TextType) types.get("text")).getBits());
		assertEquals("Seconds", ((TimeType) types.get("time")).getUnit().getName());
		assertEquals("DPT9", ((FloatingPointType) types.get("float")).getEncoding().getName());
		assertEquals("HostAddress", ((IPAddressType) types.get("ip")).getAddressKind().getName());
		assertEquals("IPv4", ((IPAddressType) types.get("ip")).getVersion().getName());
		assertEquals(Boolean.TRUE, (Object) ((DateType) types.get("date")).getShowsYear());

		List<EnumerationValue> entries = new ArrayList<>();
		String[][] entryTexts = { { "Switch", "Schalten" }, { "Dim", "Dimmen" }, { "Blind", "Jalousie" } };
		for (int n = 0; n < entryTexts.length; n++) {
			try (Transaction tx = _kb.beginTransaction()) {
				// Entry dialog: container the type, reference values; value and index proposed.
				EnumerationValue entry = factory.createEnumerationValue();
				entry.tUpdateByName("name", text(entryTexts[n][0], entryTexts[n][1]));
				entry.setValue(n);
				entry.setIndex(n);
				_enumeration.addValue(entry);
				entries.add(entry);
				tx.commit();
			}
		}
		_created.addAll(entries);

		try (Transaction tx = _kb.beginTransaction()) {
			// Parameter dialog: container program, reference parameters; the default in the field of
			// the type's kind.
			parameter("void", types.get("void"), "General", "Allgemein");
			parameter("text", types.get("text"), "Label of button 1", "Beschriftung Taste 1").setText("Licht");
			parameter("number", types.get("number"), "LED brightness", "LED-Helligkeit").setNumber(128L);
			parameter("float", types.get("float"), "Setpoint", "Sollwert").setDecimal(21.5);
			parameter("enum", _enumeration, "Function of the buttons", "Funktion der Tasten").setChoice(entries.get(1));
			parameter("time", types.get("time"), "Long press", "Verzögerung lang").setNumber(2L);
			parameter("date", types.get("date"), "Maintenance from", "Wartung ab").setText("2026-10-11");
			parameter("ip", types.get("ip"), "Gateway address", "Gateway-Adresse").setText("192.168.1.1");

			// Communication object dialog: container program, reference ports; the next object
			// number proposed, the flags from the defaults of the model.
			_port = factory.createPort();
			_port.setName("Taste 1");
			_port.setText(text("Button 1", "Taste 1"));
			_port.setFunction(text("Switch", "Schalten"));
			_port.setIndex(0);
			_program.addPort(_port);
			tx.commit();
		}
		_created.addAll(_parameters.values());
		_created.add(_port);
		assertEquals("Low", _port.getPriority().getName());
		assertEquals("Enabled", _port.getCommunication().getName());
		assertEquals("Disabled", _port.getReadOnInit().getName());
		assertEquals(1, _port.getSize());
		assertEquals("ReadWrite", _parameters.get("text").getAccess().getName());
	}

	private Parameter parameter(String key, ParameterType type, String en, String de) {
		Parameter result = EhomeOperationalFactory.getInstance().createParameter();
		result.setDescription(text(en, de));
		result.setType(type);
		_program.addParameter(result);
		_parameters.put(key, result);
		return result;
	}

	private void createStructure() {
		// The elements are placed with ProgramStructure, as the structure dialog does; one transaction
		// frames them all, so the IDs are given at its commit.
		try (Transaction tx = _kb.beginTransaction()) {
			EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();

			// The channel independent part, in the program (nothing selected).
			_independent = (ChannelIndependent) place(factory.createChannelIndependent(), _program, Position.INSIDE);

			// A block in it, placing two parameters and the communication object.
			_general = factory.createParameterGroup();
			_general.setName("Allgemein");
			_general.setDescription(text("General", "Allgemein"));
			place(_general, _independent, Position.INSIDE);
			place(view(_parameters.get("enum")), _general, Position.INSIDE);
			place(view(_parameters.get("number")), _general, Position.INSIDE);
			Interface object = factory.createInterface();
			object.setPort(_port);
			place(object, _general, Position.INSIDE);

			// Three channels (the buttons), each after the previous one, each with a block placing one
			// parameter.
			TLObject anchor = _independent;
			String[] perChannel = { "text", "float", "time" };
			for (int n = 1; n <= 3; n++) {
				Channel channel = factory.createChannel();
				channel.setNumber(Integer.toString(n));
				channel.setName("Taste " + n);
				channel.setDescription(text("Button " + n, "Taste " + n));
				// A channel cannot be inside the channel independent part or a channel: it goes after.
				assertEquals(Position.AFTER, ProgramStructure.defaultPosition(anchor, channel.tType()));
				place(channel, anchor, Position.AFTER);
				_channels.add(channel);
				anchor = channel;

				ParameterGroup block = factory.createParameterGroup();
				block.setName("Taste" + n);
				block.setDescription(text("Button " + n, "Taste " + n));
				place(block, channel, Position.INSIDE);
				place(view(_parameters.get(perChannel[n - 1])), block, Position.INSIDE);
			}

			// A switch on the enumeration parameter, with a case for one value and a default case, each
			// containing a block.
			_switch = factory.createSwitch();
			_switch.setInput((ParameterView) ExtIds.list(_general, "contents").get(0));
			place(_switch, _independent, Position.INSIDE);
			Case first = factory.createCase();
			first.setPattern("0");
			place(first, _switch, Position.INSIDE);
			Case otherwise = factory.createCase();
			otherwise.setIsDefault(true);
			place(otherwise, _switch, Position.INSIDE);
			for (Case alternative : List.of(first, otherwise)) {
				ParameterGroup block = factory.createParameterGroup();
				block.setName(alternative == first ? "Schalten" : "Andere");
				place(block, alternative, Position.INSIDE);
			}
			tx.commit();
		}

		assertEquals(List.of(_independent, _channels.get(0), _channels.get(1), _channels.get(2)),
			_program.getContents());
		assertEquals(3, ExtIds.list(_general, "contents").size());
	}

	/**
	 * A new structure element persisted and inserted in one transaction, as the structure dialog
	 * does.
	 */
	private TLObject place(TLObject node, TLObject anchor, Position position) {
		assertTrue(position + " " + node.tType() + " at " + anchor,
			ProgramStructure.canInsert(anchor, node.tType(), position));
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.insert(node, anchor, position);
			tx.commit();
		}
		_created.add(node);
		return node;
	}

	private static ParameterView view(Parameter parameter) {
		ParameterView result = EhomeOperationalFactory.getInstance().createParameterView();
		result.setParameter(parameter);
		return result;
	}

	private void checkExtIds() {
		String manufacturer = "M-7FF1";
		String program = ExtIds.extId(_program);
		String product = ExtIds.extId(_product);
		assertEquals(manufacturer, ExtIds.extId(_manufacturer));
		assertTrue(program, program.matches("M-7FF1_A-0001-01-[0-9A-F]{4}"));
		assertEquals(manufacturer + "_CS-1", ExtIds.extId(_catalog));
		assertEquals(manufacturer + "_CS-1-1", ExtIds.extId(_section));
		assertEquals(manufacturer + "_H-1", product);
		assertEquals(product + "_P-5133.2000", ExtIds.extId(_variant));
		assertEquals(product + "_HP-" + program.substring((manufacturer + "_A-").length()), ExtIds.extId(_assignment));

		Map<Kind, Set<String>> byKind = new HashMap<>();
		for (TLObject obj : _created) {
			Kind kind = ExtIds.kindOf(obj);
			String id = ExtIds.extId(obj);
			if (kind == null) {
				// Switches, cases and the channel independent part have no ID in ETS.
				assertNull(obj.toString(), id);
				continue;
			}
			assertNotNull("No ID: " + obj, id);
			String pattern = switch (kind) {
				case PARAMETER_TYPE -> program + "_PT-[A-Za-z0-9.]+";
				case ENUMERATION_VALUE -> ExtIds.extId(_enumeration) + "_EN-\\d+";
				case PARAMETER -> program + "_P-\\d+";
				case PARAMETER_REF -> ExtIds.extId(((ParameterView) obj).getParameter()) + "_R-\\d+";
				case PORT -> program + "_O-0";
				case PORT_REF -> program + "_O-0_R-\\d+";
				case BLOCK -> program + "_PB-\\d+";
				case CHANNEL -> program + "_CH-\\d+";
				default -> ".+";
			};
			assertTrue(id + " does not match " + pattern, id.matches(pattern));
			assertTrue("Duplicate ID " + id, byKind.computeIfAbsent(kind, k -> new HashSet<>()).add(id));
		}
		assertEquals(8, byKind.get(Kind.PARAMETER_TYPE).size());
		assertTrue(byKind.get(Kind.PARAMETER_TYPE).contains(program + "_PT-Temperatur.20.28.C2.B0C.29"));
		assertEquals(Set.of(ExtIds.extId(_enumeration) + "_EN-0", ExtIds.extId(_enumeration) + "_EN-1",
			ExtIds.extId(_enumeration) + "_EN-2"), byKind.get(Kind.ENUMERATION_VALUE));
		assertEquals(8, byKind.get(Kind.PARAMETER).size());
		assertEquals(6, byKind.get(Kind.BLOCK).size());
		assertEquals(Set.of(program + "_CH-1", program + "_CH-2", program + "_CH-3"), byKind.get(Kind.CHANNEL));
		assertNull(_switch.getExtId());
	}

	private void checkLabels() {
		for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
			for (TLObject obj : _created) {
				String label = (String) asRoot(locale, () -> MetaLabelProvider.INSTANCE.getLabel(obj));
				assertNotNull("No label: " + obj, label);
				assertFalse("Empty label: " + obj + " (" + locale + ")", label.isBlank());
				for (String attribute : List.of("displayName", "title", "effectiveTitle")) {
					if (obj.tType().getPart(attribute) != null) {
						String value = (String) asRoot(locale, () -> obj.tValueByName(attribute));
						assertNotNull(attribute + " of " + label, value);
						assertFalse(attribute + " of " + label, value.isBlank());
					}
				}
			}
			boolean de = locale == Locale.GERMAN;
			assertEquals(de ? "Kanalunabhängig" : "Independent of channels", displayName(_independent, locale));
			assertEquals(de ? "Allgemein" : "General", displayName(_general, locale));
			assertEquals(de ? "1: Taste 1" : "1: Button 1", displayName(_channels.get(0), locale));
			assertEquals(de ? "Wenn Funktion der Tasten" : "If Function of the buttons", displayName(_switch, locale));
			List<?> cases = _switch.getAlternatives();
			assertEquals(de ? "= 0 (Schalten)" : "= 0 (Switch)", displayName((TLObject) cases.get(0), locale));
			assertEquals(de ? "Sonst" : "Otherwise", displayName((TLObject) cases.get(1), locale));
			assertEquals(de ? "Taste 1" : "Button 1",
				displayName((TLObject) ExtIds.list(_general, "contents").get(2), locale));
			assertEquals(de ? "Funktion der Tasten" : "Function of the buttons",
				asRoot(locale, () -> _parameters.get("enum").tValueByName("title")));
			assertEquals(de ? "Dimmen" : "Dim", asRoot(locale, () -> _parameters.get("enum").tValueByName("displayValue")));
			assertEquals(de ? "Taste 1" : "Button 1", asRoot(locale, () -> _port.tValueByName("title")));
		}
	}

	private static String displayName(TLObject obj, Locale locale) {
		return (String) asRoot(locale, () -> obj.tValueByName("displayName"));
	}

	private void checkDefaults() throws Exception {
		Map<String, String> encoded = Map.of(
			"text", "Licht",
			"number", "128",
			"float", "21.5",
			"enum", "1",
			"time", "2",
			"date", "2026-10-11",
			"ip", "192.168.1.1");
		for (Map.Entry<String, Parameter> entry : _parameters.entrySet()) {
			Parameter parameter = entry.getValue();
			assertEquals(entry.getKey(), encoded.get(entry.getKey()), derived(parameter, "encodedValue"));
			for (String attribute : List.of("choice", "number", "decimal", "text")) {
				assertNull(entry.getKey() + "." + attribute, problem(parameter, attribute));
			}
			// A placement without a default of its own shows that of the parameter.
			for (ParameterView view : parameter.getViews()) {
				assertSame(parameter, derived(view, "defaultSource"));
				assertEquals(derived(parameter, "displayValue"), derived(view, "displayDefault"));
			}
		}
		assertNull(derived(_parameters.get("void"), "displayValue"));
		assertEquals("2 s", asRoot(Locale.ENGLISH, () -> _parameters.get("time").tValueByName("displayValue")));

		// The constraints still check the values against the types.
		try (Transaction tx = _kb.beginTransaction()) {
			_parameters.get("float").setDecimal(40.0);
			assertNotNull(problem(_parameters.get("float"), "decimal"));
			_parameters.get("number").setNumber(256L);
			assertNotNull(problem(_parameters.get("number"), "number"));
			tx.rollback();
		}
	}

	private void checkMoveAndInsert() {
		Channel first = _channels.get(0);
		Channel third = _channels.get(2);
		// A channel holds no channel, the program has no place beside it.
		assertNotNull(ProgramStructure.moveVeto(List.of(third), first, null));
		assertNotNull(ProgramStructure.moveVeto(List.of(third), null, null));
		assertNull(ProgramStructure.moveVeto(List.of(third), _program, _channels.get(1)));

		// The third channel dropped before the second one.
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.move(List.of(third), _program, _channels.get(1));
			tx.commit();
		}
		assertEquals(List.of(_independent, first, third, _channels.get(1)), _program.getContents());
		// Dropped beside the last one, it is the last again.
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.move(List.of(third), _program, null);
			tx.commit();
		}
		assertEquals(List.of(_independent, first, _channels.get(1), third), _program.getContents());

		// A new channel after the first one lands right behind it.
		Channel inserted;
		try (Transaction tx = _kb.beginTransaction()) {
			inserted = EhomeOperationalFactory.getInstance().createChannel();
			inserted.setNumber("4");
			inserted.setName("Taste 4");
			place(inserted, first, Position.AFTER);
			tx.commit();
		}
		assertEquals(2, _program.getContents().indexOf(inserted));
		assertEquals(_program.getContents().indexOf(first) + 1, _program.getContents().indexOf(inserted));
		assertEquals(ExtIds.extId(_program) + "_CH-4", inserted.getExtId());

		// A placed parameter after a placed object, in the same block.
		List<TLObject> general = ExtIds.list(_general, "contents");
		ParameterView after;
		try (Transaction tx = _kb.beginTransaction()) {
			after = view(_parameters.get("void"));
			place(after, general.get(0), Position.AFTER);
			tx.commit();
		}
		assertEquals(1, ExtIds.list(_general, "contents").indexOf(after));
		// Inside a block, nothing but placements; after a channel, no placement.
		assertFalse(ProgramStructure.canInsert(_general, inserted.tType(), Position.INSIDE));
		assertNotNull(ProgramStructure.insertVeto(_general, inserted.tType()));
		assertFalse(ProgramStructure.canInsert(first, after.tType(), Position.AFTER));
		assertTrue(ProgramStructure.canInsert(first, after.tType(), Position.INSIDE));
	}

	private void checkDelete() {
		// A parameter placed twice: deleting it removes its placements.
		Parameter enumParameter = _parameters.get("enum");
		ResKey inUse = ProgramStructure.deleteVeto(enumParameter);
		// It decides the switch.
		assertNotNull(inUse);

		Parameter number = _parameters.get("number");
		ParameterView second;
		try (Transaction tx = _kb.beginTransaction()) {
			second = view(number);
			place(second, _channels.get(0), Position.INSIDE);
			tx.commit();
		}
		List<ParameterView> views = new ArrayList<>(number.getViews());
		assertEquals(2, views.size());
		assertNull(ProgramStructure.deleteVeto(number));
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.delete(number);
			tx.commit();
		}
		assertFalse(_program.getParameters().contains(number));
		for (ParameterView view : views) {
			assertFalse(view.tValid());
		}
		assertTrue(ExtIds.list(_general, "contents").stream().allMatch(TLObject::tValid));
		assertFalse(_channels.get(0).getContents().contains(second));

		// A type in use cannot be deleted, with a reason naming it.
		ResKey veto = ProgramStructure.deleteVeto(_enumeration);
		assertNotNull(veto);
		String message = Resources.getInstance(Locale.ENGLISH).getString(veto);
		assertTrue(message, message.contains("Tastenfunktion") && message.contains("cannot be deleted"));
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.delete(_enumeration);
			fail("Type in use deleted.");
		} catch (TopLogicException ex) {
			assertEquals(veto, ex.getErrorKey());
		}
		assertTrue(_enumeration.tValid());

		// An entry that is a default cannot be deleted; an unused one can.
		EnumerationValue dim = _enumeration.getValues().get(1);
		ResKey entryInUse = ProgramStructure.deleteVeto(dim);
		assertNotNull(entryInUse);
		// The default of the enumeration parameter only.
		assertTrue(Resources.getInstance(Locale.ENGLISH).getString(entryInUse), Resources.getInstance(Locale.ENGLISH).getString(entryInUse).contains(" 1 "));
		EnumerationValue blind = _enumeration.getValues().get(2);
		assertNull(ProgramStructure.deleteVeto(blind));
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.delete(blind);
			tx.commit();
		}
		assertEquals(2, _enumeration.getValues().size());

		// An unused type can be deleted.
		ParameterType unused;
		try (Transaction tx = _kb.beginTransaction()) {
			unused = EhomeOperationalFactory.getInstance().createVoidType();
			unused.setName("Unbenutzt");
			_program.addParameterType(unused);
			tx.commit();
		}
		assertNull(ProgramStructure.deleteVeto(unused));
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.delete(unused);
			tx.commit();
		}
		assertFalse(unused.tValid());
	}

	private void checkCatalogTree() throws Exception {
		// The children of the catalog tree, by the script of the view.
		QueryExecutor children = ViewScripts.compile(ExprFormat.INSTANCE.getValue("children",
			viewScript("src/main/webapp/WEB-INF/views/catalogs.view.xml", "children")), List.of("node"));
		TLObject root = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule().getSingleton("ROOT");
		assertTrue(childrenOf(children, root).contains(_manufacturer));
		assertEquals(List.of(_catalog, _program), childrenOf(children, _manufacturer));
		assertEquals(List.of(_section), childrenOf(children, _catalog));
		assertEquals(List.of(_variant), childrenOf(children, _section));
		assertEquals(List.of(_assignment), childrenOf(children, _variant));
	}

	private static List<Object> childrenOf(QueryExecutor children, TLObject node) {
		return new ArrayList<>((Collection<?>) asRoot(Locale.ENGLISH, () -> children.execute(node)));
	}

	private static String viewScript(String file, String element) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(file))
			.getElementsByTagName(element);
		assertEquals(1, nodes.getLength());
		return ((Element) nodes.item(0)).getTextContent();
	}

	/**
	 * The value computed by the supplier as the technical administrator, in the given language
	 * (derived attributes are computed with the rights of the reading user, see
	 * {@link test.de.haumacher.ehome.importer.TestEtsImport#derived(TLObject, String)}).
	 */
	private static Object asRoot(Locale locale, Supplier<Object> supplier) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		try {
			return context.withLocale(locale, supplier);
		} finally {
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static ResKey problem(TLObject obj, String attribute) {
		TLStructuredTypePart part = obj.tType().getPart(attribute);
		TLConstraints constraints = part.getAnnotation(TLConstraints.class);
		if (constraints == null) {
			return null;
		}
		for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
			ConstraintCheck check = SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY.getInstance(config);
			if (check.type() != ConstraintCheck.ConstraintType.ERROR) {
				continue;
			}
			ResKey problem = check.check(obj, part);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private static ResKey text(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCatalogFromScratch.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
