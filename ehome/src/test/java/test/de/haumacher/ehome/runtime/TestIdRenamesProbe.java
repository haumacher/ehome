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

import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * Probe of #111: a rename stays in its project (another project with the same rule and parameter
 * ids keeps its expressions), and a parameter renamed to an id that is no identifier is written
 * quoted in the expressions.
 */
@SuppressWarnings("javadoc")
public class TestIdRenamesProbe extends BasicTestCase {

	private TLObject _first;

	private TLObject _second;

	private TLObject _parameter;

	private TLObject _firstReaction;

	private TLObject _secondReaction;

	private TLObject _secondOther;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_first = TestRuleAuthoring.createProject();
		_second = TestRuleAuthoring.createProject();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_parameter = rule(_first, "dimmer");
			_firstReaction = reaction(_parameter.tContainer(), "$a > 1");
			rule(_second, "dimmer");
			_secondReaction = reaction(rule(_second, "dimmer2").tContainer(), "$a > 1");
			_secondOther = reaction(rule(_second, "user").tContainer(), "@\"rule/dimmer/a\" > 0");
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_first, _second));
		super.tearDown();
	}

	public void testStaysInItsProject() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_parameter.tUpdateByName("identifier", "max-a");
			tx.commit();
		}
		assertEquals("$\"max-a\" > 1", _firstReaction.tValueByName("condition"));
		assertEquals("$a > 1", _secondReaction.tValueByName("condition"));
		assertEquals("@\"rule/dimmer/a\" > 0", _secondOther.tValueByName("condition"));
	}

	/** A rule with the parameter "a"; returns the parameter. */
	private static TLObject rule(TLObject project, String id) {
		TLObject rule = RuleFunctions.create(project, "rules", type("Rule"));
		rule.tUpdateByName("identifier", id);
		TLObject parameter = RuleFunctions.create(rule, "parameters", type("RuleParameter"));
		parameter.tUpdateByName("identifier", "a");
		return parameter;
	}

	private static TLObject reaction(TLObject rule, String condition) {
		TLObject reaction = RuleFunctions.create(rule, "reactions", type("Reaction"));
		reaction.tUpdateByName("condition", condition);
		return reaction;
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestIdRenamesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE, IdRenames.Module.INSTANCE));
	}

}
