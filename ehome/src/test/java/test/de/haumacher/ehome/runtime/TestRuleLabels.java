/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.CancelTimerAction;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.MatchTrigger;
import de.haumacher.ehome.model.runtime.ParameterTrigger;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.ReadAction;
import de.haumacher.ehome.model.runtime.RestartTimerAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.SetParameterAction;
import de.haumacher.ehome.model.runtime.SilenceTrigger;
import de.haumacher.ehome.model.runtime.StartTimerAction;
import de.haumacher.ehome.model.runtime.StartTrigger;
import de.haumacher.ehome.model.runtime.TimerTrigger;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleLabels;

/**
 * The short display texts of the reactions, triggers and actions of a rule (haumacher/ehome #214),
 * in German and English: names only, in the order the rule evaluates a reaction; the technical
 * summary with the ids stays for the tooltips and the refusals of the image.
 *
 * <p>
 * The expectations are the tables of the ticket.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRuleLabels extends BasicTestCase {

	private final List<TLObject> _types = new ArrayList<>();

	private Project _project;

	private Rule _rule;

	private GroupAddress _light;

	private GroupAddress _position;

	private GroupAddress _screen;

	private ApplicationDatapoint _locked;

	private ApplicationDatapoint _open;

	private ApplicationDatapoint _cinema;

	private ApplicationDatapoint _motion;

	private ApplicationDatapoint _brightness;

	private ApplicationDatapoint _winter;

	private ApplicationDatapoint _dayNight;

	private WeeklySchedule _night;

	private WeeklySchedule _workday;

	private WeeklySchedule _weekend;

	private RuleTimer _yardActive;

	private RuleTimer _projectorOff;

	private RuleTimer _playOn;

	private RuleParameter _maxBrightness;

	private RuleParameter _duration;

	private RuleState _normal;

	private RuleState _arriving;

	private RuleState _leaving;

	private RuleState _day;

	private RuleState _nightState;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject switching = mainType(1, "TEST-214-1");
			TLObject percent = mainType(5, "TEST-214-5");
			TLObject onOff = subtype(1, "TEST-214-1.001");
			TLObject upDown = subtype(8, "TEST-214-1.008");
			TLObject scaling = subtype(1, "TEST-214-5.001");
			subtypes(switching, List.of(onOff, upDown));
			subtypes(percent, List.of(scaling));

			_project = create("ehome.project:Project");
			_project.setName("Rule labels");
			_light = address("Licht Vorplatz", "1/1/1", onOff);
			_position = address("Rolladen Wohnzimmer EG Ostfenster Position", "1/1/2", scaling);
			_screen = address("Leinwand Wohnzimmer Auf/Ab", "1/1/3", upDown);
			_project.setGroupAddresses(Set.of(_light, _position, _screen));

			_locked = datapoint("tuer_verriegelt_eingang_ug", "Tür verriegelt Eingang UG");
			_open = datapoint("tuer_offen_eingang_ug", "Tür offen Eingang UG");
			_cinema = datapoint("wohnzimmer.heimkino", "Heimkino");
			_motion = datapoint("bewegung_hof", "Bewegung Hof");
			_brightness = datapoint("helligkeit_hof", "Helligkeit Hof");
			_winter = datapoint("rollaeden_winter", "Rollläden Winter");
			_dayNight = datapoint("tag_nacht", "Tag/Nacht");

			_night = schedule("rl_schliessen", "Rollläden Nacht (Sonnenuntergang)");
			_workday = schedule("hz_wz_wt_ein", "Heizung WZ Werktag ein");
			_weekend = schedule("hz_wz_we_ein", "Heizung WZ Wochenende ein");

			_rule = (Rule) RuleFunctions.create(_project, "rules", runtime("Rule"));
			_rule.setIdentifier("hof");
			_rule.setNameKey(i18n("Yard", "Hof"));
			_yardActive = timer("hofAktiv");
			_projectorOff = timer("beamerAus");
			_playOn = timer("spielAn");
			_maxBrightness = parameter("maxhelligkeit", "Max. Helligkeit", "integer", "100");
			_duration = parameter("dauer", "Dauer", "duration", "5min");
			_normal = state("normal");
			_arriving = state("ankommen");
			_leaving = state("verlassen");
			_day = state("tag");
			_nightState = state("nacht");
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject type : _types) {
				type.tDelete();
			}
			tx.commit();
		}
		_types.clear();
		super.tearDown();
	}

	// ---- triggers -----------------------------------------------------------------------------

	public void testDatapointTriggers() {
		Reaction reaction = reaction();
		DatapointTrigger change = datapointTrigger(reaction, _locked, "change", "any");
		assertLabel("Tür verriegelt Eingang UG ändert sich", "Tür verriegelt Eingang UG changes", change);

		DatapointTrigger update = datapointTrigger(reaction, _cinema, "update", "any");
		assertLabel("Heimkino empfangen", "Heimkino received", update);
		DatapointTrigger report = datapointTrigger(reaction, _cinema, "update", "report");
		assertLabel("Heimkino empfangen (Meldung)", "Heimkino received (report)", report);
		DatapointTrigger command = datapointTrigger(reaction, _cinema, "update", "command");
		assertLabel("Heimkino empfangen (Befehl)", "Heimkino received (command)", command);

		DatapointTrigger changeReport = datapointTrigger(reaction, _locked, "change", "report");
		assertLabel("Tür verriegelt Eingang UG ändert sich (Meldung)", "Tür verriegelt Eingang UG changes (report)",
			changeReport);
		DatapointTrigger changeCommand = datapointTrigger(reaction, _locked, "change", "command");
		assertLabel("Tür verriegelt Eingang UG ändert sich (Befehl)", "Tür verriegelt Eingang UG changes (command)",
			changeCommand);
	}

	public void testMatchSilence() {
		Reaction reaction = reaction();
		MatchTrigger match = part(reaction, "triggers", "MatchTrigger",
			(MatchTrigger p) -> p.setCondition("@tuer_offen_eingang_ug"));
		assertLabel("Tür offen Eingang UG wird wahr", "Tür offen Eingang UG becomes true", match);

		SilenceTrigger silence = part(reaction, "triggers", "SilenceTrigger", (SilenceTrigger p) -> {
			p.setDatapoint(_motion);
			p.setAfter("10min");
		});
		assertLabel("Bewegung Hof 10 min ohne Meldung", "Bewegung Hof silent for 10 min", silence);
	}

	public void testScheduleTimerParameter() {
		Reaction reaction = reaction();
		ScheduleTrigger schedule = part(reaction, "triggers", "ScheduleTrigger",
			(ScheduleTrigger p) -> p.setSchedule(_night));
		assertLabel("Zeitplan Rollläden Nacht (Sonnenuntergang)", "Schedule Rollläden Nacht (Sonnenuntergang)", schedule);

		TimerTrigger timer = part(reaction, "triggers", "TimerTrigger", (TimerTrigger p) -> p.setTimer(_yardActive));
		assertLabel("Timer hofAktiv abgelaufen", "Timer hofAktiv expired", timer);

		ParameterTrigger parameter = part(reaction, "triggers", "ParameterTrigger",
			(ParameterTrigger p) -> p.setParameter(_maxBrightness));
		assertLabel("Max. Helligkeit geändert", "Max. Helligkeit changed", parameter);
	}

	public void testStartTriggers() {
		Reaction reaction = reaction();
		StartTrigger some = startTrigger(reaction, true, true, false);
		assertLabel("Start (Neustart, neues Image)", "Start (reboot, new image)", some);
		StartTrigger all = startTrigger(reaction, true, true, true);
		assertLabel("Start", "Start", all);
		StartTrigger enable = startTrigger(reaction, false, false, true);
		assertLabel("Start (Einschalten)", "Start (switching on)", enable);
	}

	// ---- actions ------------------------------------------------------------------------------

	public void testWrites() {
		Reaction reaction = reaction();
		WriteAction light = write(reaction, _light, "true", false);
		assertLabel("Licht Vorplatz ← Ein", "Licht Vorplatz ← On", light);
		WriteAction off = write(reaction, _light, "false", false);
		assertLabel("Licht Vorplatz ← Aus", "Licht Vorplatz ← Off", off);

		WriteAction position = write(reaction, _position, "80", false);
		assertLabel("Rolladen Wohnzimmer EG Ostfenster Position ← 80 %",
			"Rolladen Wohnzimmer EG Ostfenster Position ← 80 %", position);

		WriteAction screen = write(reaction, _screen, "true", false);
		assertLabel("Leinwand Wohnzimmer Auf/Ab ← Ab", "Leinwand Wohnzimmer Auf/Ab ← Down", screen);
		WriteAction up = write(reaction, _screen, "false", false);
		assertLabel("Leinwand Wohnzimmer Auf/Ab ← Auf", "Leinwand Wohnzimmer Auf/Ab ← Up", up);

		WriteAction expression = write(reaction, _position,
			"if(@tuer_verriegelt_eingang_ug, 80, if(@rollaeden_winter, 100, 80))", false);
		String text = "Rolladen Wohnzimmer EG Ostfenster Position ← if(Tür verriegelt Eingang UG, 80, if(Rollläden Winter, 100, 80))";
		assertLabel(text, text, expression);

		WriteAction onlyOnChange = write(reaction, _light, "true", true);
		assertLabel("Licht Vorplatz ← Ein (nur bei Änderung)", "Licht Vorplatz ← On (only on change)", onlyOnChange);
	}

	public void testOtherActions() {
		Reaction reaction = reaction();
		ReadAction read = part(reaction, "actions", "ReadAction", (ReadAction p) -> p.setDatapoint(_cinema));
		assertLabel("Heimkino lesen", "Read Heimkino", read);

		StartTimerAction start = part(reaction, "actions", "StartTimerAction", (StartTimerAction p) -> {
			p.setTimer(_playOn);
			p.setDuration("23min");
		});
		assertLabel("Timer spielAn starten (23 min)", "Start timer spielAn (23 min)", start);

		RestartTimerAction restart = part(reaction, "actions", "RestartTimerAction", (RestartTimerAction p) -> {
			p.setTimer(_yardActive);
			p.setDuration(null);
		});
		assertLabel("Timer hofAktiv neu starten", "Restart timer hofAktiv", restart);

		CancelTimerAction cancel = part(reaction, "actions", "CancelTimerAction",
			(CancelTimerAction p) -> p.setTimer(_projectorOff));
		assertLabel("Timer beamerAus abbrechen", "Cancel timer beamerAus", cancel);

		SetParameterAction set = part(reaction, "actions", "SetParameterAction", (SetParameterAction p) -> {
			p.setParameter(_duration);
			p.setValue("10min");
		});
		assertLabel("Dauer ← 10 min", "Dauer ← 10 min", set);

		LogAction log = part(reaction, "actions", "LogAction", (LogAction p) -> {
			p.setLevel(classifier("LogLevel", "info"));
			p.setMessage("Text");
		});
		assertLabel("Log (Info): Text", "Log (info): Text", log);
	}

	// ---- reactions ----------------------------------------------------------------------------

	public void testReactionTriggerAndCondition() {
		Reaction reaction = reaction();
		datapointTrigger(reaction, _cinema, "update", "any");
		update(() -> reaction.setCondition("@\"wohnzimmer.heimkino\""));
		assertLabel("Wenn Heimkino empfangen · falls Heimkino", "When Heimkino received · if Heimkino", reaction);

		Reaction locked = reaction();
		datapointTrigger(locked, _locked, "change", "any");
		update(() -> locked.setCondition("@tuer_verriegelt_eingang_ug"));
		assertLabel("Wenn Tür verriegelt Eingang UG ändert sich · falls Tür verriegelt Eingang UG",
			"When Tür verriegelt Eingang UG changes · if Tür verriegelt Eingang UG", locked);
	}

	public void testReactionWithoutConditionAndState() {
		Reaction timer = reaction();
		part(timer, "triggers", "TimerTrigger", (TimerTrigger p) -> p.setTimer(_projectorOff));
		assertLabel("Wenn Timer beamerAus abgelaufen", "When timer beamerAus expired", timer);

		Reaction schedules = reaction();
		part(schedules, "triggers", "ScheduleTrigger", (ScheduleTrigger p) -> p.setSchedule(_workday));
		part(schedules, "triggers", "ScheduleTrigger", (ScheduleTrigger p) -> p.setSchedule(_weekend));
		assertLabel("Wenn Zeitplan Heizung WZ Werktag ein oder Heizung WZ Wochenende ein",
			"When schedule Heizung WZ Werktag ein or Heizung WZ Wochenende ein", schedules);
	}

	public void testReactionInStates() {
		Reaction arrive = reaction();
		datapointTrigger(arrive, _motion, "update", "any");
		update(() -> {
			arrive.setCondition("@bewegung_hof and @helligkeit_hof < $maxhelligkeit");
			arrive.setFrom(Set.of(_normal));
			arrive.setTo(_arriving);
		});
		// The operators of the rule language stay as they are (haumacher/ehome #214, design 4).
		assertLabel("Im Zustand normal: wenn Bewegung Hof empfangen · falls Bewegung Hof and Helligkeit Hof < Max. Helligkeit → ankommen",
			"In state normal: when Bewegung Hof received · if Bewegung Hof and Helligkeit Hof < Max. Helligkeit → ankommen",
			arrive);

		Reaction back = reaction();
		part(back, "triggers", "TimerTrigger", (TimerTrigger p) -> p.setTimer(_yardActive));
		update(() -> {
			back.setFrom(Set.of(_leaving, _arriving));
			back.setTo(_normal);
		});
		assertLabel("Im Zustand ankommen, verlassen: wenn Timer hofAktiv abgelaufen → normal",
			"In state ankommen, verlassen: when timer hofAktiv expired → normal", back);

		Reaction night = reaction();
		part(night, "triggers", "ScheduleTrigger", (ScheduleTrigger p) -> p.setSchedule(_night));
		datapointTrigger(night, _dayNight, "update", "any");
		update(() -> {
			night.setFrom(Set.of(_day));
			night.setTo(_nightState);
		});
		assertLabel("Im Zustand tag: wenn Zeitplan Rollläden Nacht (Sonnenuntergang) oder Tag/Nacht empfangen → nacht",
			"In state tag: when schedule Rollläden Nacht (Sonnenuntergang) or Tag/Nacht received → nacht", night);
	}

	public void testFurtherTriggers() {
		Reaction reaction = reaction();
		datapointTrigger(reaction, _cinema, "update", "any");
		part(reaction, "triggers", "TimerTrigger", (TimerTrigger p) -> p.setTimer(_projectorOff));
		part(reaction, "triggers", "ScheduleTrigger", (ScheduleTrigger p) -> p.setSchedule(_workday));
		startTrigger(reaction, true, false, false);
		startTrigger(reaction, true, true, true);
		assertLabel(
			"Wenn Heimkino empfangen oder Timer beamerAus abgelaufen oder Zeitplan Heizung WZ Werktag ein oder Start (Neustart) oder Start",
			"When Heimkino received or timer beamerAus expired or schedule Heizung WZ Werktag ein or start (reboot) or start",
			reaction);

		Reaction start = reaction();
		startTrigger(start, false, true, false);
		update(() -> start.setFrom(Set.of(_day, _normal)));
		assertLabel("Im Zustand normal, tag: wenn Start (neues Image)", "In state normal, tag: when start (new image)",
			start);

		Reaction empty = reaction();
		update(() -> empty.setTo(_day));
		assertLabel("Wenn – → tag", "When – → tag", empty);
	}

	// ---- the technical text -------------------------------------------------------------------

	public void testTechnicalTextKeepsIds() {
		Reaction reaction = reaction();
		datapointTrigger(reaction, _cinema, "update", "any");
		update(() -> reaction.setCondition("@\"wohnzimmer.heimkino\""));
		String summary = "when datapoint Heimkino (wohnzimmer.heimkino) (update) if Heimkino (wohnzimmer.heimkino)";
		assertEquals(summary, de(() -> RuleFunctions.summary(reaction)));
		// The label of the object, by which the refusals of the image name it, stays the summary.
		assertEquals(summary, de(() -> MetaLabelProvider.INSTANCE.getLabel(reaction)));

		WriteAction light = write(reaction, _light, "true", false);
		assertEquals("write Licht Vorplatz (1/1/1) := true", de(() -> RuleFunctions.summary(light)));
	}

	// ---- helpers ------------------------------------------------------------------------------

	private static void assertLabel(String german, String english, TLObject obj) {
		assertEquals(german, de(() -> RuleLabels.label(obj)));
		assertEquals(english, en(() -> RuleLabels.label(obj)));
	}

	private Reaction reaction() {
		return part(_rule, "reactions", "Reaction", (Reaction p) -> p.setCondition(null));
	}

	private static DatapointTrigger datapointTrigger(Reaction reaction, TLObject datapoint, String on, String origin) {
		return part(reaction, "triggers", "DatapointTrigger", (DatapointTrigger p) -> {
			p.setDatapoint((ProjectDatapoint) datapoint);
			p.setOn(classifier("UpdateKind", on));
			p.setOrigin(classifier("UpdateOrigin", origin));
		});
	}

	private static StartTrigger startTrigger(Reaction reaction, boolean boot, boolean image, boolean enable) {
		return part(reaction, "triggers", "StartTrigger", (StartTrigger p) -> {
			p.setOnBoot(boot);
			p.setOnImage(image);
			p.setOnEnable(enable);
		});
	}

	private static WriteAction write(Reaction reaction, TLObject datapoint, String value, boolean onlyOnChange) {
		return part(reaction, "actions", "WriteAction", (WriteAction p) -> {
			p.setDatapoint((ProjectDatapoint) datapoint);
			p.setValue(value);
			p.setOnlyOnChange(onlyOnChange);
		});
	}

	private ApplicationDatapoint datapoint(String id, String label) {
		ApplicationDatapoint result = (ApplicationDatapoint) RuleFunctions.create(_project, "applicationDatapoints",
			runtime("ApplicationDatapoint"));
		result.setIdentifier(id);
		result.setLabel(i18n(label, label));
		return result;
	}

	private WeeklySchedule schedule(String id, String label) {
		WeeklySchedule result = (WeeklySchedule) RuleFunctions.create(_project, "schedules", runtime("WeeklySchedule"));
		result.setIdentifier(id);
		result.setLabel(i18n(label, label));
		return result;
	}

	private RuleTimer timer(String id) {
		RuleTimer result = (RuleTimer) RuleFunctions.create(_rule, "timers", runtime("RuleTimer"));
		result.setIdentifier(id);
		return result;
	}

	private RuleParameter parameter(String id, String title, String type, String value) {
		RuleParameter result = (RuleParameter) RuleFunctions.create(_rule, "parameters", runtime("RuleParameter"));
		result.setIdentifier(id);
		result.setTitle(i18n(title, title));
		result.setType(classifier("RuleParameterType", type));
		result.setDefault(value);
		return result;
	}

	private RuleState state(String id) {
		RuleState result = (RuleState) RuleFunctions.create(_rule, "states", runtime("RuleState"));
		result.setIdentifier(id);
		return result;
	}

	private static GroupAddress address(String name, String address, TLObject type) {
		GroupAddress result = create("ehome.logical:GroupAddress");
		result.setName(name);
		result.setAddress(GA.parse(address, GroupAddressStyle.ThreeLevel));
		result.setDatapointTypes(List.of(type));
		return result;
	}

	private TLObject mainType(int number, String extId) {
		TLObject type = create("ehome.datapoint:DatapointMainType");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", extId);
		_types.add(type);
		return type;
	}

	private static TLObject subtype(int number, String extId) {
		TLObject type = create("ehome.datapoint:DatapointSubtype");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", extId);
		return type;
	}

	private static void subtypes(TLObject main, List<TLObject> subtypes) {
		main.tUpdateByName("subtypes", subtypes);
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T part(TLObject owner, String reference, String type, Consumer<T> init) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			T result = (T) RuleFunctions.create(owner, reference, runtime(type));
			init.accept(result);
			tx.commit();
			return result;
		}
	}

	private static void update(Runnable change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			change.run();
			tx.commit();
		}
	}

	private static TLClassifier classifier(String enumeration, String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(name);
	}

	private static ResKey i18n(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static String en(Supplier<String> value) {
		return inLocale(Locale.ENGLISH, value);
	}

	private static String de(Supplier<String> value) {
		return inLocale(Locale.GERMAN, value);
	}

	/** The value as the given person reads it in the given language: labels are resolved in it. */
	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuleLabels.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
