/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.base.services.simpleajax.HTMLFragment;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.xml.TagWriter;
import com.top_logic.event.infoservice.DefaultInfoServiceItem;
import com.top_logic.event.infoservice.InfoService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.DisplayContext;
import com.top_logic.layout.basic.DefaultDisplayContext;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ConstraintVeto;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.ProposeIdentifiersAction;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The readable identifier of a group address (haumacher/ehome #130): the id of its datapoint in the
 * image, the name rules reference it by; the rules follow when it is set, changed or removed, an
 * expression entered with the address is written with the identifier; ill-formed, reserved and
 * duplicate identifiers (also against an application datapoint) are refused with the reason; the
 * proposal fills the missing ones, valid and unique.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressIds extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	private TLObject _dpt;

	private TLObject _project;

	private TLObject _licht;

	private TLObject _status;

	private TLObject _other;

	private TLObject _holiday;

	private TLObject _rule;

	private TLObject _reaction;

	private TLObject _write;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_dpt = create("ehome.datapoint:DatapointMainType");
			_dpt.tUpdateByName("number", Integer.valueOf(1));
			_dpt.tUpdateByName("extId", "TEST-GROUP-ADDRESS-IDS-1");
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Group address ids");
			_licht = address("Licht Küche", "1/2/3");
			_licht.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_licht.tUpdateByName("runtimePersist", Boolean.TRUE);
			_status = address("Licht Küche Status", "1/2/4");
			_other = address("", "1/2/5");
			_project.tUpdateByName("groupAddresses", List.of(_licht, _status, _other));

			_holiday = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			_holiday.tUpdateByName("identifier", "urlaub");
			_holiday.tUpdateByName("writable", Boolean.TRUE);

			_rule = RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.tUpdateByName("identifier", "licht");
			_reaction = RuleFunctions.create(_rule, "reactions", runtime("Reaction"));
			TLObject trigger = RuleFunctions.create(_reaction, "triggers", runtime("DatapointTrigger"));
			trigger.tUpdateByName("datapoint", _status);
			_reaction.tUpdateByName("condition", "@\"1/2/4\" and not @urlaub");
			_write = RuleFunctions.create(_reaction, "actions", runtime("WriteAction"));
			_write.tUpdateByName("datapoint", _licht);
			_write.tUpdateByName("value", "not @\"1/2/3\"");
			tx.commit();
		}
		entries().clear();
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_dpt.tDelete();
			tx.commit();
		}
		super.tearDown();
	}

	/**
	 * Set, the identifier names the datapoint: the rules follow, the image names the datapoint by it
	 * with the address as its KNX binding, texts show it; removed, everything returns to the address.
	 */
	public void testIdentifier() {
		assertEquals("1/2/3", Pages.imageId(_licht));
		update(_licht, "identifier", "kueche.licht");

		assertEquals("kueche.licht", Pages.imageId(_licht));
		assertEquals("not @kueche.licht", _write.tValueByName("value"));
		assertEquals("@\"1/2/4\" and not @urlaub", _reaction.tValueByName("condition"));
		assertEquals("Licht Küche (kueche.licht)", DatapointNames.name(_licht));
		Map<String, Object> datapoint = imageDatapoint("kueche.licht");
		assertEquals(Map.of("dpt", "1.000", "send", "1/2/3"), datapoint.get("knx"));
		assertNull(imageDatapoint("1/2/3"));
		List<String> messages = messages();
		assertEquals(2, messages.size());
		assertEquals("1 expression adjusted to the new id.", messages.get(0));
		assertTrue(messages.get(1), messages.get(1).contains("Licht Küche") && messages.get(1).contains("1/2/3")
			&& messages.get(1).contains("kueche.licht") && messages.get(1).contains("lost"));

		// The address may change: the identifier keeps naming the datapoint, the binding follows.
		update(_licht, "address", GA.parse("1/2/7", GroupAddressStyle.ThreeLevel));
		assertEquals("not @kueche.licht", _write.tValueByName("value"));
		assertEquals(Map.of("dpt", "1.000", "send", "1/2/7"), imageDatapoint("kueche.licht").get("knx"));
		assertEquals(List.of(), messages());

		// Changed and removed.
		update(_licht, "identifier", "kueche.decke");
		assertEquals("not @kueche.decke", _write.tValueByName("value"));
		update(_licht, "identifier", null);
		assertEquals("not @\"1/2/7\"", _write.tValueByName("value"));
		assertEquals("1/2/7", Pages.imageId(_licht));
		assertNotNull(imageDatapoint("1/2/7"));
	}

	/**
	 * An expression entered with the address of a group address that has an identifier is written
	 * with the identifier; the address still resolves a reference given by it.
	 */
	public void testEnteredAddress() {
		update(_status, "identifier", "kueche.licht_status");
		assertEquals("@kueche.licht_status and not @urlaub", _reaction.tValueByName("condition"));
		messages();

		update(_reaction, "condition", "@\"1/2/4\" or @\"1/2/5\"");
		assertEquals("@kueche.licht_status or @\"1/2/5\"", _reaction.tValueByName("condition"));
		TLObject log;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			log = RuleFunctions.create(_reaction, "actions", runtime("LogAction"));
			log.tUpdateByName("message", "Status {@\"1/2/4\"}");
			tx.commit();
		}
		assertEquals("Status {@kueche.licht_status}", log.tValueByName("message"));
		// Quietly, as the canonical form of an entered text.
		assertEquals(List.of(), messages());
	}

	/**
	 * Ill-formed, too long, reserved and duplicate identifiers are refused, each with its reason, in
	 * the form and on commit; an application datapoint cannot take an address's identifier either.
	 */
	public void testRefusals() {
		Resources resources = Resources.getInstance(Locale.ENGLISH);
		for (String id : List.of("1licht", "küche.licht", "kueche..licht", "kueche.licht-1", "true", "and.x", " kueche",
			"kueche/licht")) {
			ResKey problem = GroupAddressIds.problem(id, _licht);
			assertNotNull(id, problem);
			assertTrue(resources.getString(problem), resources.getString(problem).contains("no datapoint name"));
		}
		assertNull(GroupAddressIds.problem("kueche.licht_1.2", _licht));
		assertNull(GroupAddressIds.problem("_x", _licht));
		assertNull(GroupAddressIds.problem("", _licht));
		assertNull(GroupAddressIds.problem(null, _licht));
		assertTrue(resources.getString(GroupAddressIds.problem("a".repeat(64), _licht)).contains("at most 63 bytes"));
		assertNull(GroupAddressIds.problem("a".repeat(63), _licht));
		for (String id : List.of("knx.time", "knx.date", "knx.datetime", "zigbee.lampe")) {
			assertTrue(id, resources.getString(GroupAddressIds.problem(id, _licht)).contains("reserved"));
		}
		assertEquals("The application datapoint New datapoint (urlaub) has the id urlaub already.",
			resources.getString(GroupAddressIds.problem("urlaub", _licht)));

		update(_status, "identifier", "kueche.status");
		assertEquals("The group address Licht Küche Status (kueche.status) has the identifier kueche.status already.",
			resources.getString(GroupAddressIds.problem("kueche.status", _licht)));
		assertNull(GroupAddressIds.problem("kueche.status", _status));

		// On commit, also from Java.
		assertRefused(_licht, "identifier", "kueche.status", "has the identifier kueche.status already");
		assertRefused(_licht, "identifier", "kueche licht", "no datapoint name");
		assertRefused(_holiday, "identifier", "kueche.status", "group address");
		assertNull(GroupAddressIds.identifier(_licht));
		assertEquals("urlaub", ApplicationDatapoints.id(_holiday));
		assertEquals("not @\"1/2/3\"", _write.tValueByName("value"));

		// Several at once are checked against the state after the change: two may exchange theirs.
		Map<TLObject, String> exchange = new LinkedHashMap<>();
		exchange.put(_licht, "kueche.status");
		exchange.put(_status, "kueche.licht");
		assertEquals(Map.of(), GroupAddressIds.problems(_project, exchange));
		Map<TLObject, String> twice = new LinkedHashMap<>();
		twice.put(_licht, "x.y");
		twice.put(_other, "x.y");
		assertEquals(Set.of(_licht, _other), GroupAddressIds.problems(_project, twice).keySet());
	}

	/**
	 * The proposal: from the function using an address (room, name without the room, role), else
	 * from the name or the description; valid and unique; addresses without anything to derive from
	 * and those with an identifier stay as they are.
	 */
	public void testProposal() {
		TLObject function;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject building = create("ehome.building:Building");
			building.tUpdateByName("name", "Haus");
			TLObject room = create("ehome.building:Room");
			room.tUpdateByName("name", "Küche");
			building.tUpdateByName("parts", List.of(room));
			_project.tUpdateByName("buildings", List.of(building));
			function = create("ehome.runtime:Function");
			function.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Licht Küche")
				.add(Locale.ENGLISH, "Licht Küche").build());
			function.tUpdateByName("kind",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:FunctionKind")).getClassifier("light"));
			function.tUpdateByName("room", room);
			function.tUpdateByName("command", _licht);
			function.tUpdateByName("state", _status);
			_project.tUpdateByName("functions", List.of(function));
			TLObject same = address("Licht Küche", "1/2/6");
			TLObject described = address(null, "1/2/8");
			described.tUpdateByName("description", "1. OG Flur Präsenz\nmit Helligkeit");
			TLObject nothing = address(null, "1/2/9");
			_project.tUpdateByName("groupAddresses", List.of(_licht, _status, _other, same, described, nothing));
			tx.commit();
		}
		Map<TLObject, String> proposal = GroupAddressIds.propose(_project);
		List<String> ids = new ArrayList<>(proposal.values());
		assertEquals(List.of("kueche.licht", "kueche.licht.state", "licht_kueche", "_1_og_flur_praesenz"), ids);
		for (String id : ids) {
			assertNull(id, GroupAddressIds.syntaxProblem(id));
		}

		// Unique among the identifiers the project has, the application datapoints included.
		update(_other, "identifier", "licht_kueche");
		assertEquals(List.of("kueche.licht", "kueche.licht.state", "licht_kueche_2", "_1_og_flur_praesenz"),
			new ArrayList<>(GroupAddressIds.propose(_project).values()));
		update(_holiday, "identifier", "kueche.licht");
		assertEquals("kueche.licht_2", GroupAddressIds.propose(_project).get(_licht));
		update(_holiday, "identifier", "urlaub");

		// A long name is shortened to 63 bytes.
		update(_other, "identifier", null);
		update(_other, "name", "Ein sehr langer Name einer Gruppenadresse, der weit über das Limit hinausgeht");
		String long_ = GroupAddressIds.propose(_project).get(_other);
		assertEquals(63, long_.length());
		assertNull(GroupAddressIds.syntaxProblem(long_));

		String confirmation = ProposeIdentifiersAction.confirmation(_project);
		assertTrue(confirmation, confirmation.contains("1/2/3 Licht Küche → kueche.licht"));

		assertEquals(5, ProposeIdentifiersAction.apply(_project));
		assertEquals("kueche.licht", GroupAddressIds.identifier(_licht));
		assertEquals("not @kueche.licht", _write.tValueByName("value"));
		assertEquals("@kueche.licht.state and not @urlaub", _reaction.tValueByName("condition"));
		Set<String> unique = new HashSet<>();
		for (TLObject address : de.haumacher.ehome.runtime.RuntimeFunctions.groupAddresses(_project)) {
			String identifier = GroupAddressIds.identifier(address);
			if (identifier != null) {
				assertTrue(identifier, unique.add(identifier));
				assertNull(GroupAddressIds.problem(identifier, address));
			}
		}
		assertEquals(5, unique.size());
		assertEquals(Map.of(), GroupAddressIds.propose(_project));
		assertEquals(0, ProposeIdentifiersAction.apply(_project));
	}

	// ---- helpers -----------------------------------------------------------------------------

	private TLObject address(String name, String address) {
		TLObject result = create("ehome.logical:GroupAddress");
		if (name != null) {
			result.tUpdateByName("name", name);
		}
		result.tUpdateByName("address", GA.parse(address, GroupAddressStyle.ThreeLevel));
		result.tUpdateByName("datapointTypes", List.of(_dpt));
		return result;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> imageDatapoint(String id) {
		for (Object datapoint : (List<?>) RuntimeImage.build(_project, 1, NOW).getJson().get("datapoints")) {
			if (id.equals(((Map<?, ?>) datapoint).get("id"))) {
				return (Map<String, Object>) datapoint;
			}
		}
		return null;
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static void assertRefused(TLObject obj, String attribute, Object value, String reason) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
			fail("The commit must be refused: " + value);
		} catch (TopLogicException ex) {
			List<String> problems = ConstraintVeto.problems(ex);
			assertNotNull(ex.toString(), problems);
			assertTrue(problems.toString(), problems.toString().contains(reason));
		}
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	/** The messages the commits gave the user since the last call, as texts. */
	private static List<String> messages() {
		List<HTMLFragment> entries = entries();
		List<String> result = new ArrayList<>();
		for (HTMLFragment entry : entries) {
			result.add(text(((DefaultInfoServiceItem) entry).getMessage()));
		}
		entries.clear();
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<HTMLFragment> entries() {
		DisplayContext context = DefaultDisplayContext.getDisplayContext();
		if (!context.isSet(InfoService.INFO_SERVICE_ENTRIES)) {
			context.set(InfoService.INFO_SERVICE_ENTRIES, new ArrayList<>());
		}
		return context.get(InfoService.INFO_SERVICE_ENTRIES);
	}

	private static String text(HTMLFragment fragment) {
		StringWriter buffer = new StringWriter();
		try {
			fragment.write(DefaultDisplayContext.getDisplayContext(), new TagWriter(buffer));
		} catch (IOException ex) {
			throw new AssertionError(ex);
		}
		return buffer.toString();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressIds.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			IdRenames.Module.INSTANCE));
	}

}
