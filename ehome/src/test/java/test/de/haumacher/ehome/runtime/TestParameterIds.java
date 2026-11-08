/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.I18NConstants;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.runtime.RuleDrafts;
import de.haumacher.ehome.runtime.RuleExport;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * The id of a rule parameter (haumacher/ehome #110, homecontrol <code>spec/rules.md</code> 2.1): 1..24
 * characters of <code>A-Z a-z 0-9 _ -</code>, no keyword, not <code>enabled</code>, unique in the
 * rule. An id outside is an error of the input in the edit form and in the create dialog (not a
 * warning), stated once: the controller's refusal of the same id is not shown again as a warning.
 */
@SuppressWarnings("javadoc")
public class TestParameterIds extends BasicTestCase {

	public void testCharacters() {
		assertTrue(RuleLoader.validParameterId("max-helligkeit"));
		assertTrue(RuleLoader.validParameterId("2nd-level-0123456789-abc"));
		assertTrue(RuleLoader.validParameterId("_"));
		assertFalse(RuleLoader.validParameterId(""));
		assertFalse(RuleLoader.validParameterId("max/helligkeit"));
		assertFalse(RuleLoader.validParameterId("max helligkeit"));
		assertFalse(RuleLoader.validParameterId("maximale-größe"));
		assertFalse(RuleLoader.validParameterId("max-helligkeit-0123456789"));
		assertFalse(RuleLoader.validParameterId("not"));

		assertNull(RuleExport.parameterIdProblem("max-helligkeit", null));
		assertNull(RuleExport.parameterIdProblem("", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_INVALID__ID.fill("max/helligkeit"),
			RuleExport.parameterIdProblem("max/helligkeit", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_INVALID__ID.fill("max helligkeit"),
			RuleExport.parameterIdProblem("max helligkeit", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_INVALID__ID.fill("größe"),
			RuleExport.parameterIdProblem("größe", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_INVALID__ID.fill("max-helligkeit-0123456789"),
			RuleExport.parameterIdProblem("max-helligkeit-0123456789", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_ENABLED, RuleExport.parameterIdProblem("enabled", null));
		assertEquals(I18NConstants.RULE_PARAMETER_ID_KEYWORD__ID.fill("true"),
			RuleExport.parameterIdProblem("true", null));
	}

	/**
	 * The edit form of a stored parameter: the id outside the set is an error (the form does not
	 * store it), with the rule in its message, and no warning besides; a duplicate is one as well.
	 */
	public void testEditForm() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject rule = part(project, "rules", "Rule");
			TLObject first = part(rule, "parameters", "RuleParameter");
			TLObject second = part(rule, "parameters", "RuleParameter");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule.tUpdateByName("identifier", "dimmer");
				first.tUpdateByName("identifier", "level");
				second.tUpdateByName("identifier", "other");
				tx.commit();
			}

			RuleForm form = new RuleForm(second);
			form.set("identifier", "max-helligkeit");
			assertEquals(List.of(), form.errors("identifier"));
			for (String invalid : List.of("max/helligkeit", "max helligkeit", "maximale-größe",
				"max-helligkeit-0123456789")) {
				form.set("identifier", invalid);
				assertEquals(invalid, List.of("The parameter id \"" + invalid + "\" is not 1 to 24 letters, digits, _ or -."),
					form.errors("identifier"));
				assertEquals(invalid, List.of(), form.warnings("identifier"));
			}
			form.set("identifier", "enabled");
			assertEquals(1, form.errors("identifier").size());
			assertEquals(List.of(), form.warnings("identifier"));
			form.set("identifier", "or");
			assertEquals(1, form.errors("identifier").size());
			assertTrue(form.errors("identifier").get(0), form.errors("identifier").get(0).contains("keyword"));
			form.set("identifier", "level");
			assertEquals(List.of("The parameter id \"level\" is used twice."), form.errors("identifier"));
			assertEquals(List.of(), form.warnings("identifier"));
			form.set("identifier", "other");
			assertEquals(List.of(), form.errors("identifier"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The create dialog: the draft (in the rule, not yet one of its parameters) is checked the same
	 * way, a duplicate of a stored parameter included.
	 */
	public void testCreateDialog() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject rule = part(project, "rules", "Rule");
			TLObject stored = part(rule, "parameters", "RuleParameter");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				rule.tUpdateByName("identifier", "dimmer");
				stored.tUpdateByName("identifier", "max-helligkeit");
				tx.commit();
			}
			TLObject draft = RuleDrafts.draft(rule, "parameters", runtime("RuleParameter"));
			RuleForm form = new RuleForm(draft);
			assertEquals(List.of(), form.errors("identifier"));
			form.set("identifier", "max helligkeit");
			assertEquals(List.of("The parameter id \"max helligkeit\" is not 1 to 24 letters, digits, _ or -."),
				form.errors("identifier"));
			form.set("identifier", "max-helligkeit");
			assertEquals(List.of("The parameter id \"max-helligkeit\" is used twice."), form.errors("identifier"));
			form.set("identifier", "min-helligkeit");
			assertEquals(List.of(), form.errors("identifier"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static TLObject part(TLObject owner, String reference, String type) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = RuleFunctions.create(owner, reference, runtime(type));
			tx.commit();
			return result;
		}
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterIds.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
