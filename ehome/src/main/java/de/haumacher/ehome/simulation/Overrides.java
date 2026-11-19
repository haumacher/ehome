/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.Color;
import de.haumacher.ehome.rules.Values;

/**
 * The parameter overrides of the simulation of a project, kept with the project: what a controller
 * persists of the parameters (<code>spec/rules.md</code> 8.4), stored at the rules of the project.
 *
 * <ul>
 * <li>The enable switch of a rule: <code>ehome.runtime:Rule#simulationEnabled</code>, the literal
 * <code>true</code> or <code>false</code>, empty for the default.</li>
 * <li>Every other parameter: <code>ehome.runtime:RuleParameter#simulationValue</code>, a literal of
 * the parameter's type as the rule editor writes it (<code>1min</code>, <code>21.5</code>), empty
 * for the default.</li>
 * <li>The state of a rule with states (<code>spec/rules.md</code> 8.5):
 * <code>ehome.runtime:Rule#simulationState</code>, the id of the state, empty for none.</li>
 * </ul>
 *
 * <p>
 * A simulation starts with these overrides ({@link #load(TLObject)}); what the simulation's store
 * holds after a command of the user (also what the actions of the rules set) is written back
 * ({@link #save(TLObject, ParameterStore)}). An override the rules no longer accept (a changed type
 * or range) is dropped by the engine at the start, and so from the project at the next save.
 * </p>
 */
public final class Overrides {

	/** The id of the enable switch among the parameters of a rule. */
	public static final String ENABLED = "enabled";

	private Overrides() {
		// Functions only.
	}

	/**
	 * The stored overrides of the project.
	 */
	public static ParameterStore load(TLObject project) {
		ParameterStore store = new ParameterStore();
		for (Rule rule : ((Project) project).getRules()) {
			String ruleId = text(rule.getIdentifier());
			if (ruleId == null) {
				continue;
			}
			String enabled = text(rule.getSimulationEnabled());
			Val parsedEnabled = enabled == null ? null : parse(enabled, Type.BOOLEAN);
			if (parsedEnabled != null) {
				store.write(ruleId, ENABLED, parsedEnabled);
			}
			String state = text(rule.getSimulationState());
			if (state != null) {
				store.write(ruleId, RuleSet.STATE_KEY, Val.ofString(state));
			}
			for (RuleParameter parameter : rule.getParameters()) {
				String id = text(parameter.getIdentifier());
				String value = text(parameter.getSimulationValue());
				Type type = type(parameter);
				if (id == null || value == null || type == null) {
					continue;
				}
				Val parsed = parse(value, type);
				if (parsed != null) {
					store.write(ruleId, id, parsed);
				}
			}
		}
		return store;
	}

	/**
	 * Writes the overrides of the store to the project, where they differ (in a transaction of its
	 * own).
	 *
	 * @return Whether something was written.
	 */
	public static boolean save(TLObject project, ParameterStore store) {
		List<Runnable> changes = new ArrayList<>();
		Map<String, Val> overrides = store.overrides();
		for (Rule rule : ((Project) project).getRules()) {
			String ruleId = text(rule.getIdentifier());
			if (ruleId == null) {
				continue;
			}
			Val enabled = overrides.get(ParameterStore.key(ruleId, ENABLED));
			String wanted = enabled == null || enabled.type() != Type.BOOLEAN ? null : text(enabled, Type.BOOLEAN);
			if (!Objects.equals(wanted, text(rule.getSimulationEnabled()))) {
				changes.add(() -> rule.setSimulationEnabled(wanted));
			}
			Val state = overrides.get(ParameterStore.key(ruleId, RuleSet.STATE_KEY));
			String wantedState = state == null || state.type() != Type.STRING ? null : state.text();
			if (!Objects.equals(wantedState, text(rule.getSimulationState()))) {
				changes.add(() -> rule.setSimulationState(wantedState));
			}
			for (RuleParameter parameter : rule.getParameters()) {
				String id = text(parameter.getIdentifier());
				Type type = type(parameter);
				if (id == null || type == null) {
					continue;
				}
				Val value = overrides.get(ParameterStore.key(ruleId, id));
				String text = value == null ? null : text(value, type);
				String stored = text(parameter.getSimulationValue());
				if (!Objects.equals(text, stored)) {
					changes.add(() -> parameter.setSimulationValue(text));
				}
			}
		}
		if (changes.isEmpty()) {
			return false;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase()
			.beginTransaction(I18NConstants.COMMIT_SIMULATION_SAVED__PROJECT.fill(MetaLabelProvider.INSTANCE.getLabel(project)))) {
			for (Runnable change : changes) {
				change.run();
			}
			tx.commit();
		}
		return true;
	}

	/**
	 * The type of a parameter of a rule, <code>null</code> if it has none.
	 */
	public static Type type(RuleParameter parameter) {
		return parameter.getType() instanceof TLClassifier classifier ? Type.fromName(classifier.getName())
			: null;
	}

	/**
	 * A literal of the given type (<code>1min</code>, <code>21.5</code>) as a value,
	 * <code>null</code> if it is none.
	 */
	public static Val parse(String text, Type type) {
		try {
			Values.Literal literal = Values.fromJson(type, RuleText.parseValue(text.trim(), type));
			return literal.isOk() ? literal.value() : null;
		} catch (SyntaxError ex) {
			return null;
		}
	}

	/**
	 * The literal of a value of the given parameter type, as the rule editor writes it.
	 */
	public static String text(Val value, Type type) {
		return RuleText.printValue(json(value), type);
	}

	/**
	 * The JSON literal value of a value of a parameter (<code>spec/rules.md</code> 3.1).
	 */
	public static Object json(Val value) {
		switch (value.type()) {
			case BOOLEAN:
				return Boolean.valueOf(value.bool());
			case INTEGER:
			case DURATION:
			case ENUMERATION:
				return Long.valueOf(value.integer());
			case REAL:
				return Double.valueOf(value.real());
			case STRING:
				return value.text();
			case TIME_OF_DAY: {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("hour", Long.valueOf(value.time().hour()));
				result.put("minute", Long.valueOf(value.time().minute()));
				result.put("second", Long.valueOf(value.time().second()));
				if (value.time().dayOfWeek() != 0) {
					result.put("dayOfWeek", Long.valueOf(value.time().dayOfWeek()));
				}
				return result;
			}
			case COLOR: {
				Color color = value.color();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("red", Long.valueOf(color.red()));
				result.put("green", Long.valueOf(color.green()));
				result.put("blue", Long.valueOf(color.blue()));
				if ((color.valid() & Color.WHITE) != 0) {
					result.put("white", Long.valueOf(color.white()));
				}
				return result;
			}
			default:
				throw new IllegalArgumentException("No parameter value: " + value);
		}
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String result = value.toString().trim();
		return result.isEmpty() ? null : result;
	}

}
