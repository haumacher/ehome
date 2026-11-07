/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the localized findings (haumacher/ehome #95), composed differently from
 * {@link TestLocalizedRefusals}: two functions with the same id, one commanding a datapoint that is
 * not writable. Every finding has its own German sentence from the bundle, distinct from the
 * English, which stays the reason; the stored translations read the same as the message.
 */
@SuppressWarnings("javadoc")
public class TestLocalizedRefusalsProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

	public void testSeveralFindings() throws Exception {
		TLObject project;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Localized refusals probe");
			project.tUpdateByName("extId", "P-TEST-95-PROBE");
			TLObject state = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			state.tUpdateByName("identifier", "probe.state");
			TLObject first = function("Licht A", "probe-light", state);
			TLObject second = function("Licht B", "probe-light", state);
			project.tUpdateByName("functions", List.of(first, second));
			tx.commit();
		}
		try {
			ImageReport report = RuntimeImage.build(project, 1, NOW).validate();
			assertFalse(report.isValid());
			assertTrue(report.toString(), report.getRefusals().size() >= 2);
			for (Refusal refusal : report.getRefusals()) {
				assertNotNull(refusal.toString(), refusal.message());
				String de = Resources.getInstance(Locale.GERMAN).getString(refusal.message());
				String en = Resources.getInstance(Locale.ENGLISH).getString(refusal.message());
				assertFalse(refusal + ": no German text: " + de, de.startsWith("[") || de.equals(en));
				assertFalse(refusal + ": English left in the German text: " + de,
					de.contains(" needs ") || de.contains(" must ") || de.contains(" is not"));
				assertEquals(de, Resources.getInstance(Locale.GERMAN).getString(refusal.translations()));
				assertFalse(refusal.reason().isEmpty());
			}
			assertTrue(report.toString(), report.getRefusals().stream()
				.anyMatch(r -> r.reason().contains("writable")));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static TLObject function(String name, String id, TLObject datapoint) {
		TLObject function = create("ehome.runtime:Function");
		function.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, name).build());
		function.tUpdateByName("identifier", id);
		function.tUpdateByName("kind", FunctionProposal.classifier("switch"));
		function.tUpdateByName("command", datapoint);
		function.tUpdateByName("state", datapoint);
		return function;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestLocalizedRefusalsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
