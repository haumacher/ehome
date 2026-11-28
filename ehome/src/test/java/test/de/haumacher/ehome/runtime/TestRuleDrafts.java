/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Set;

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
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.runtime.AstroSchedule;
import de.haumacher.ehome.model.runtime.PageSection;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleDrafts;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * The create dialogs of rules, schedule entries, application datapoints and control pages
 * (haumacher/ehome #91), on the functions the dialog calls: a draft stores nothing (no object, no
 * revision), "Create" stores exactly one object with the entered values at its place, and the
 * fields of a draft are checked as those of a stored object (a controller's refusal as a warning, a
 * text that does not parse as an error). An empty project serves.
 */
@SuppressWarnings("javadoc")
public class TestRuleDrafts extends BasicTestCase {

	public void testScheduleEntry() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject existing = part(project, "schedules", "WeeklySchedule");
			long revision = revision();
			TLObject draft = RuleDrafts.draft(project, "schedules", runtime("AstroSchedule"));
			assertTrue(draft.tTransient());
			assertEquals("entry-2", ((AstroSchedule) draft).getIdentifier());
			assertEquals(List.of("label", "identifier", "sun", "offset", "days", "output"), RuleDrafts.fields(draft));

			// The fields of the dialog are checked at the place the entry goes to.
			RuleForm form = new RuleForm(draft);
			form.set("offset", "13h");
			assertEquals(form.warnings("offset").toString(), 1, form.warnings("offset").size());
			assertTrue(form.warnings("offset").get(0), form.warnings("offset").get(0).contains("at most 12 hours"));
			assertEquals(List.of(), form.errors("offset"));
			form.set("offset", "-30 minutes");
			assertEquals("A text that does not parse is an error.", 1, form.errors("offset").size());
			form.set("offset", "-30min");
			assertEquals(List.of(), form.errors("offset"));
			assertEquals(List.of(), form.warnings("offset"));

			// Cancel: nothing stored.
			assertEquals(revision, revision());
			assertEquals(List.of(existing), RuleFunctions.objects(project, "schedules"));

			// Create: one entry at the end, with the values entered.
			AstroSchedule entry = (AstroSchedule) draft;
			entry.setOffset("-30min");
			entry.setLabel(ResKey.text("Dusk"));
			entry.setDays(Set.of(weekday("saturday"), weekday("sunday")));
			TLObject created;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				created = RuleDrafts.add(draft);
				tx.commit();
			}
			assertFalse(created.tTransient());
			assertEquals(List.of(existing, created), RuleFunctions.objects(project, "schedules"));
			AstroSchedule astro = (AstroSchedule) created;
			assertEquals("entry-2", astro.getIdentifier());
			assertEquals("-30min", astro.getOffset());
			assertEquals(2, ((Collection<?>) astro.getDays()).size());
			assertEquals("sunset", ((com.top_logic.model.TLClassifier) astro.getSun()).getName());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testRuleParts() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject rule = part(project, "rules", "Rule");
			TLObject reaction = part(rule, "reactions", "Reaction");
			long revision = revision();

			TLObject trigger = RuleDrafts.draft(reaction, "triggers", runtime("DatapointTrigger"));
			assertEquals(List.of("datapoint"), RuleDrafts.fields(trigger));
			List<String> warnings = new RuleForm(trigger).warnings("datapoint");
			assertEquals("A trigger without datapoint is refused: " + warnings, 1, warnings.size());

			TLObject write = RuleDrafts.draft(reaction, "actions", runtime("WriteAction"));
			RuleForm writeForm = new RuleForm(write);
			writeForm.set("value", "1 +");
			assertEquals(1, writeForm.errors("value").size());
			assertEquals(revision, revision());
			assertEquals(List.of(), RuleFunctions.objects(reaction, "triggers"));
			assertEquals(List.of(), RuleFunctions.objects(reaction, "actions"));

			TLObject timer = RuleDrafts.draft(rule, "timers", runtime("RuleTimer"));
			RuleTimer timerDraft = (RuleTimer) timer;
			assertEquals("t1", timerDraft.getIdentifier());
			timerDraft.setIdentifier("off");
			timerDraft.setDuration("5min");
			Rule second = (Rule) RuleDrafts.draft(project, "rules", runtime("Rule"));
			assertEquals("rule-2", second.getIdentifier());
			second.setNameKey(ResKey.text("Second"));
			TLObject stored;
			TLObject secondRule;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				stored = RuleDrafts.add(timer);
				secondRule = RuleDrafts.add(second);
				tx.commit();
			}
			assertEquals(List.of(stored), RuleFunctions.objects(rule, "timers"));
			assertEquals("off", ((RuleTimer) stored).getIdentifier());
			assertEquals("5min", ((RuleTimer) stored).getDuration());
			assertEquals(List.of(rule, secondRule), RuleFunctions.objects(project, "rules"));
			assertTrue(((Number) ((Rule) secondRule).getOrder()).intValue()
				> ((Number) ((Rule) rule).getOrder()).intValue());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testPages() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			TLObject first;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				first = Pages.insert(project, null, Pages.PAGE, List.of());
				((de.haumacher.ehome.model.runtime.Page) first).setNameKey(ResKey.text("first"));
				((de.haumacher.ehome.model.runtime.Page) Pages.insert(project, first, Pages.PAGE, List.of()))
					.setNameKey(ResKey.text("second"));
				tx.commit();
			}
			long revision = revision();
			TLObject page = RuleDrafts.pageDraft(project, first, Pages.PAGE);
			assertEquals(List.of("name"), RuleDrafts.fields(page));
			TLObject section = RuleDrafts.pageDraft(project, first, Pages.SECTION);
			assertEquals(revision, revision());

			((PageSection) section).setTitle(ResKey.text("Living room"));
			((de.haumacher.ehome.model.runtime.Page) page).setNameKey(ResKey.text("Added"));
			TLObject added;
			TLObject addedPage;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				added = RuleDrafts.add(section);
				addedPage = RuleDrafts.add(page);
				tx.commit();
			}
			assertEquals(List.of(added), RuleFunctions.objects(first, "sections"));
			assertNotNull(((PageSection) added).getTitle());

			List<TLObject> pages = RuleFunctions.objects(project, "pages");
			assertEquals(3, pages.size());
			assertSame("A page goes after the selected one.", addedPage, pages.get(1));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static long revision() {
		return PersistencyLayer.getKnowledgeBase().getHistoryManager().getLastRevision();
	}

	private static TLObject part(TLObject owner, String reference, String type) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = RuleFunctions.create(owner, reference, runtime(type));
			if (result instanceof Rule rule) {
				// A name is entered, never preset (haumacher/ehome #205).
				rule.setNameKey(ResKey.text("Rule"));
			} else if (result instanceof de.haumacher.ehome.model.runtime.ScheduleEntry entry) {
				entry.setLabel(ResKey.text("Entry"));
			}
			tx.commit();
			return result;
		}
	}

	private static TLClassifier weekday(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:Weekday")).getClassifier(name);
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleDrafts.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
