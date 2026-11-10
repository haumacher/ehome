/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import static test.de.haumacher.ehome.ai.AssistantFixture.ACTUATOR;
import static test.de.haumacher.ehome.ai.AssistantFixture.SENSOR;
import static test.de.haumacher.ehome.ai.AssistantFixture.as;
import static test.de.haumacher.ehome.ai.AssistantFixture.deviceTitles;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tl.ai.core.agent.AgentApplicationService;
import com.top_logic.tl.ai.core.agent.AgentFactory;
import com.top_logic.tl.ai.core.agent.AgentPolicyService;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.tl.ai.core.agent.TransactionScope;
import com.top_logic.tl.ai.core.agent.approval.ApprovalCallback;
import com.top_logic.tl.ai.core.execution.AgentRunner;
import com.top_logic.tl.ai.core.message.AssistantMessage;
import com.top_logic.tl.ai.core.message.ContentPart;
import com.top_logic.tl.ai.core.message.Conversation;
import com.top_logic.tl.ai.core.message.Message;
import com.top_logic.tl.ai.core.message.TextContent;
import com.top_logic.tl.ai.core.message.ToolCall;
import com.top_logic.tl.ai.core.message.UserMessage;
import com.top_logic.tl.ai.core.provider.LLMProviderService;
import com.top_logic.tl.ai.core.provider.ProviderRequest;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Reply;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Script;
import com.top_logic.tl.ai.core.provider.ScriptedProvider;
import com.top_logic.tl.ai.core.security.AiToolAccessService;
import com.top_logic.tl.ai.core.security.RunEntryPoint;
import com.top_logic.tl.ai.core.security.RunPrincipal;
import com.top_logic.tl.ai.core.security.RunSecurity;
import com.top_logic.tl.ai.tool.api.CancellationToken;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

/**
 * The assistant of E-Home (haumacher/ehome #126) on a scripted model (<code>ai/testing-agents</code>),
 * without a network and without a key: a turn of the chat's agent as an installer reads the devices
 * of a project with the shipped data tools, and the answer ends the conversation; a viewer's call of
 * a writing tool is refused with the capability it lacks, and nothing changes; a run on the configured
 * model without a key names the missing secret.
 *
 * <p>
 * The runs are built as the chat builds them (<code>ChatProcessor.buildToolContext</code>): the
 * account of the run is the principal, the run is attended, and the tool access policy and the model
 * access rights of E-Home decide (<code>ehome.security.config.xml</code>). Approvals are given
 * automatically; what is checked is whether a call gets that far.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAssistant extends BasicTestCase {

	private static final String SCRIPTED_MODEL = "scripted-model";

	private final AssistantFixture _fixture = new AssistantFixture("ehomeAssistant");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fixture.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		_fixture.tearDown();
		super.tearDown();
	}

	public void testInstallerReadsTheDevicesOfAProject() {
		String answer = "The project has two devices: " + ACTUATOR + " and " + SENSOR + ".";
		ScriptedProvider.install(Script.of(
			Reply.of(new ToolCall("c1", "query", json("expression", deviceTitles()))),
			Reply.text(answer)));

		Conversation conversation = conversation("Which devices does the project have?");
		AgentRunner.RecordedRun run = as(_fixture.installer(), () -> run(conversation));

		assertEquals(answer, run.response());
		Message last = conversation.messages().get(conversation.size() - 1);
		assertTrue("The answer ends the conversation: " + last, last instanceof AssistantMessage);
		assertEquals(answer, text((AssistantMessage) last));

		List<ProviderRequest> sent = ScriptedProvider.requests();
		assertEquals("One request before the tool call, one after it.", 2, sent.size());
		assertTrue("The chat's agent carries the data tools: " + toolNames(sent.get(0)),
			toolNames(sent.get(0)).contains("query"));
		String toolAnswer = sent.get(1).messages().toString();
		assertTrue(toolAnswer, toolAnswer.contains(ACTUATOR));
		assertTrue(toolAnswer, toolAnswer.contains(SENSOR));
		assertTrue("E-Home's application context reaches the agent.",
			sent.get(0).systemPrompt().contains("ehome.installers"));
	}

	public void testViewerIsRefusedAWritingTool() {
		ScriptedProvider.install(Script.of(
			Reply.of(new ToolCall("c1", "updateObject",
				"{\"id\": \"" + tid(_fixture.actuator()) + "\", \"values\": {\"title\": \"Renamed\"}}")),
			Reply.text("I may not rename it.")));

		as(_fixture.viewer(), () -> run(conversation("Rename the actuator.")));

		List<ProviderRequest> sent = ScriptedProvider.requests();
		List<String> offered = toolNames(sent.get(0));
		assertFalse("A viewer is not offered a writing tool: " + offered, offered.contains("updateObject"));
		assertTrue("A viewer reads: " + offered, offered.contains("query"));
		String toolAnswer = sent.get(sent.size() - 1).messages().toString();
		assertTrue("The refusal names the capability: " + toolAnswer, toolAnswer.contains("data.write"));
		assertEquals(ACTUATOR, _fixture.actuator().tValueByName("title"));
	}

	public void testWithoutKeyTheMissingSecretIsNamed() {
		String model = AgentRuntimeService.getInstance().getDefaultModel();
		assertEquals("The configured default model (WEB-INF/conf/ehomeConf.xml).",
			envOr("tl_ai_default_model", "claude-sonnet-5-5"), model);
		if (LLMProviderService.getInstance().isServable(model)) {
			// A key is set in this environment; the run would reach the real provider.
			return;
		}
		ScriptedProvider.install(Script.of(Reply.text("Never asked.")));
		String refusal;
		try {
			AgentRunner.RecordedRun run = as(_fixture.installer(), () -> run(conversation("Hello"), null));
			assertFalse("A run without a key does not complete.", run.result().isComplete());
			refusal = run.result().errorMessage();
		} catch (TopLogicException ex) {
			refusal = Resources.getInstance().getString(ex.getErrorKey());
		}
		assertTrue("The refusal names the secret: " + refusal,
			refusal != null && refusal.contains("tl_anthropic_api_key"));
		assertTrue("No model was asked.", ScriptedProvider.requests().isEmpty());
	}

	private static AgentRunner.RecordedRun run(Conversation conversation) {
		return run(conversation, SCRIPTED_MODEL);
	}

	/**
	 * Runs the chat's agent as the chat runs it, for the account of the calling thread.
	 */
	private static AgentRunner.RecordedRun run(Conversation conversation, String model) {
		CancellationToken cancellation = new CancellationToken();
		ToolContext context = new ToolContext(
			RunSecurity.contextFor(new RunPrincipal(TLContext.currentUser(), RunEntryPoint.CHAT, false)),
			ApprovalCallback.AUTO_APPROVE, new TransactionScope(cancellation), Collections.emptySet());
		try {
			return AgentRunner.run(AgentRuntimeService.chatEntryPoint(), conversation, null, context, event -> {
				// No window to show the run in.
			}, null, null, null, null, new AgentFactory.RunOverrides(model, null, null));
		} finally {
			context.transaction().close();
		}
	}

	private static Conversation conversation(String question) {
		Conversation result = new Conversation();
		result.add(new UserMessage(UUID.randomUUID().toString(), Instant.now(),
			List.<ContentPart> of(new TextContent(question))));
		return result;
	}

	private static String text(AssistantMessage message) {
		StringBuilder result = new StringBuilder();
		for (ContentPart part : message.content()) {
			if (part instanceof TextContent text) {
				result.append(text.text());
			}
		}
		return result.toString();
	}

	private static List<String> toolNames(ProviderRequest request) {
		return request.tools().stream().map(tool -> tool.name()).toList();
	}

	private static String json(String key, String value) {
		return "{\"" + key + "\": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}

	private static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestAssistant.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}
