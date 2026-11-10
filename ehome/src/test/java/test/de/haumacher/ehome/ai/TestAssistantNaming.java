/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import static test.de.haumacher.ehome.ai.AssistantFixture.as;
import static test.de.haumacher.ehome.ai.AssistantFixture.conversation;
import static test.de.haumacher.ehome.ai.AssistantFixture.inTransaction;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tl.ai.core.agent.AgentApplicationService;
import com.top_logic.tl.ai.core.agent.AgentPolicyService;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.tl.ai.core.message.ContentPart;
import com.top_logic.tl.ai.core.message.Conversation;
import com.top_logic.tl.ai.core.message.Message;
import com.top_logic.tl.ai.core.message.TextContent;
import com.top_logic.tl.ai.core.message.ToolCall;
import com.top_logic.tl.ai.core.message.ToolResultMessage;
import com.top_logic.tl.ai.core.message.UserMessage;
import com.top_logic.tl.ai.core.provider.ProviderRequest;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Reply;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Script;
import com.top_logic.tl.ai.core.provider.ScriptedProvider;
import com.top_logic.tl.ai.core.security.AiToolAccessService;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.runtime.GroupAddressIds;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;

/**
 * The owner's case of haumacher/ehome #127 on a scripted model: "Assign reasonable names to the
 * project's group addresses" in a project whose addresses have no name, only a description
 * (<code>src/test/fixtures/TestNaming.knxproj</code>). The agent lists the addresses with what tells
 * their purpose, proposes names, and writes them with their identifiers (haumacher/ehome #130) with
 * one call after the user agreed; the names and identifiers are set, the descriptions stay. A name
 * that is empty, an identifier given twice or ill-formed is refused, and nothing changes.
 */
@SuppressWarnings("javadoc")
public class TestAssistantNaming extends BasicTestCase {

	private static final String SCRIPTED_MODEL = "scripted-model";

	private static final String FIXTURE = "src/test/fixtures/TestNaming.knxproj";

	private static final String PROPOSAL = "Proposal: ... Shall I write these names?";

	private TLObject _project;

	private Person _installer;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		List<TLObject> before = new ArrayList<>(instances("ehome.project:Project"));
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		_project = projects.get(0);
		inTransaction(() -> _installer = AssistantFixture.person("ehomeNamingInstaller", "ehome.installers"));
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		inTransaction(() -> {
			if (_installer.tValid()) {
				_installer.tDelete();
			}
		});
		super.tearDown();
	}

	/**
	 * The proposal of identifiers for addresses no function uses puts the room of the connected
	 * devices first, as for a function: the fixture's push button sits in the kitchen.
	 */
	public void testProposalByRoom() {
		java.util.Collection<String> ids = de.haumacher.ehome.runtime.GroupAddressIds.propose(_project).values();
		assertFalse(ids.isEmpty());
		for (String id : ids) {
			assertTrue(id, id.startsWith("kueche."));
		}
	}

	public void testNameGroupAddresses() throws RefetchTimeout {
		List<TLObject> addresses = DeviceSettings.groupAddresses(_project);
		assertEquals(10, addresses.size());
		Map<TLObject, Object> descriptions = new HashMap<>();
		for (TLObject address : addresses) {
			assertTrue("Imported without a name.", blank(address.tValueByName("name")));
			assertNotNull("Imported with a description.", address.tValueByName("description"));
			descriptions.put(address, address.tValueByName("description"));
		}

		// The names the agent proposes: here, room and description.
		List<Map<String, Object>> names = new ArrayList<>();
		for (TLObject address : addresses) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("groupAddress", tid(address));
			entry.put("name", "Küche " + address.tValueByName("description"));
			// With the identifier derived from the name (haumacher/ehome #130).
			entry.put("identifier", identifier(address));
			names.add(entry);
		}
		Script script = (request, turn) -> {
			Message last = request.messages().get(request.messages().size() - 1);
			if (last instanceof UserMessage user && text(user).startsWith("Assign")) {
				return Reply.of(call("c1", "listGroupAddresses", "project", tid(_project), "limit", Integer.valueOf(4)));
			}
			if (last instanceof ToolResultMessage answer && answer.toolName().equals("listGroupAddresses")) {
				return answer.toString().contains("\"nextOffset\"")
					? Reply.of(call("c" + turn + 1, "listGroupAddresses", "project", tid(_project), "offset",
						Integer.valueOf(4), "limit", Integer.valueOf(10)))
					: Reply.text(PROPOSAL);
			}
			if (last instanceof UserMessage user && text(user).startsWith("Yes")) {
				return Reply.of(call("w1", "nameGroupAddresses", "names", names));
			}
			if (last instanceof UserMessage user && text(user).startsWith("Twice")) {
				return Reply.of(call("w3", "nameGroupAddresses", "names", List.of(
					Map.of("groupAddress", tid(addresses.get(0)), "identifier", "kueche.doppelt"),
					Map.of("groupAddress", tid(addresses.get(1)), "identifier", "kueche.doppelt"),
					Map.of("groupAddress", tid(addresses.get(2)), "identifier", "Küche Licht"))));
			}
			if (last instanceof UserMessage user && text(user).startsWith("Empty")) {
				return Reply.of(call("w2", "nameGroupAddresses", "names", List.of(Map.of("groupAddress",
					tid(addresses.get(0)), "name", "  "))));
			}
			return Reply.text("Done.");
		};

		// The agent lists the addresses page by page and proposes; nothing is written yet.
		Conversation conversation = conversation("Assign reasonable names to the project's group addresses.");
		run(conversation, script);
		List<ProviderRequest> sent = ScriptedProvider.requests();
		String listing = answers(sent.get(sent.size() - 1), "listGroupAddresses");
		for (TLObject address : addresses) {
			assertTrue("Every address is listed: " + tid(address), listing.contains(tid(address)));
		}
		assertTrue("The description: " + listing, listing.contains("Raumtemperatur Küche"));
		assertTrue("The range with its description: " + listing, listing.contains("\"description\": \"Küche\""));
		assertTrue("The connected device: " + listing, listing.contains("Taster Küche"));
		assertTrue("The device's room: " + listing, listing.contains("\"room\""));
		assertTrue("The direction: " + listing, listing.contains("\"direction\": \"send\""));
		assertTrue("Paged: " + listing, listing.contains("\"nextOffset\": 4"));
		for (TLObject address : addresses) {
			assertTrue(blank(address.tValueByName("name")));
		}

		// The user agrees: one call names all addresses, the descriptions stay.
		reply(conversation, "Yes, write them.");
		run(conversation, script);
		String written = answers(ScriptedProvider.requests().get(ScriptedProvider.requests().size() - 1),
			"nameGroupAddresses");
		assertTrue(written, written.contains("Named."));
		for (TLObject address : addresses) {
			assertEquals("Küche " + descriptions.get(address), address.tValueByName("name"));
			assertEquals(descriptions.get(address), address.tValueByName("description"));
			assertEquals(identifier(address), GroupAddressIds.identifier(address));
			assertEquals(identifier(address), Pages.imageId(address));
		}

		// Identifiers that are ill-formed or given twice are refused with the reasons, nothing changes.
		reply(conversation, "Twice the same identifier.");
		run(conversation, script);
		String twice = answers(ScriptedProvider.requests().get(ScriptedProvider.requests().size() - 1),
			"nameGroupAddresses");
		// In the language of the user: each refused entry by its address and the identifier.
		assertTrue(twice, twice.contains("Refused, nothing was changed"));
		for (TLObject address : addresses.subList(0, 3)) {
			assertTrue(twice, twice.contains(address.tValueByName("address") + " (" + tid(address) + ")"));
		}
		assertTrue(twice, twice.contains("kueche.doppelt") && twice.contains("Küche Licht"));
		assertEquals(identifier(addresses.get(0)), GroupAddressIds.identifier(addresses.get(0)));

		// An empty name is refused with the reason, nothing changes.
		reply(conversation, "Empty the first name.");
		run(conversation, script);
		String refused = answers(ScriptedProvider.requests().get(ScriptedProvider.requests().size() - 1),
			"nameGroupAddresses");
		assertTrue(refused, refused.contains("the name is empty"));
		assertEquals("Küche " + descriptions.get(addresses.get(0)), addresses.get(0).tValueByName("name"));
	}

	private static String identifier(TLObject address) {
		return "kueche." + GroupAddressIds.segment(String.valueOf(address.tValueByName("description")));
	}

	private static boolean blank(Object value) {
		return value == null || value.toString().isBlank();
	}

	private void run(Conversation conversation, Script script) throws RefetchTimeout {
		ScriptedProvider.install(script);
		as(_installer, () -> AssistantFixture.run(conversation, SCRIPTED_MODEL));
		PersistencyLayer.getKnowledgeBase().refetch();
	}

	private static void reply(Conversation conversation, String text) {
		conversation.add(new UserMessage(UUID.randomUUID().toString(), Instant.now(),
			List.<ContentPart> of(new TextContent(text))));
	}

	private static String text(UserMessage message) {
		StringBuilder result = new StringBuilder();
		for (ContentPart part : message.content()) {
			if (part instanceof TextContent content) {
				result.append(content.text());
			}
		}
		return result.toString();
	}

	private static String answers(ProviderRequest request, String tool) {
		StringBuilder result = new StringBuilder();
		for (Message message : request.messages()) {
			if (message instanceof ToolResultMessage answer && tool.equals(answer.toolName())) {
				result.append(answer);
			}
		}
		assertTrue("No answer of " + tool, result.length() > 0);
		return result.toString();
	}

	private static ToolCall call(String id, String tool, Object... arguments) {
		Map<String, Object> json = new LinkedHashMap<>();
		for (int n = 0; n < arguments.length; n += 2) {
			json.put((String) arguments[n], arguments[n + 1]);
		}
		return new ToolCall(id, tool, Json.write(json));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAssistantNaming.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}
