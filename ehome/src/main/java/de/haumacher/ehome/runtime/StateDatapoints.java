/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.service.event.PendingChanges;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleStateDatapoint;
import de.haumacher.ehome.query.Queries;

/**
 * Gives every rule its state datapoint (haumacher/ehome #140, homecontrol <code>spec/rules.md</code>
 * 8.8): the part <code>ehome.runtime:Rule#stateDatapoint</code>, an
 * <code>ehome.runtime:RuleStateDatapoint</code>, which a trigger, a virtual device or a control page
 * names for the datapoint <code>rule/&lt;id&gt;/state</code>. The image has that datapoint while the
 * rule has states; a reference to it of a rule without states is refused there, at its field.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base: whichever code creates a rule or a state of
 * a rule (a form, the import of rules, a project file, an agent through the model), the commit gets
 * the rule's state datapoint in the {@link Modification} it returns, which the knowledge base runs
 * within the commit. At the start, the rules stored without one (before haumacher/ehome #140) get
 * theirs in a transaction of its own; a query of the database finds them ({@link #missing()}), the
 * rules that have theirs are not read.
 * </p>
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.runtime.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class })
public class StateDatapoints extends ConfiguredManagedClass<StateDatapoints.Config<?>> implements ModificationListener {

	/**
	 * Configuration of {@link StateDatapoints}.
	 */
	public interface Config<I extends StateDatapoints> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	/** The type of the rules. */
	static final String RULE = "ehome.runtime:Rule";

	/** The type of the state datapoint. */
	static final String TYPE = "ehome.runtime:RuleStateDatapoint";

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link StateDatapoints} from configuration.
	 */
	public StateDatapoints(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		_kb = PersistencyLayer.getKnowledgeBase();
		_kb.addModificationListener(this);
		List<TLObject> missing = missing();
		if (!missing.isEmpty()) {
			try (Transaction tx = _kb.beginTransaction(I18NConstants.COMMIT_STATE_DATAPOINTS__COUNT.fill(missing.size()))) {
				for (TLObject rule : missing) {
					ensure(rule);
				}
				tx.commit();
			}
		}
	}

	/**
	 * The rules stored without a state datapoint: found by one query of the database, the rules
	 * whose identifier is not the source of a link of <code>ehome.runtime:Rule#stateDatapoint</code>
	 * (haumacher/ehome #172). Since haumacher/ehome #140 every rule gets its datapoint in the commit
	 * creating it, so the start reads none of the rules of all projects; only the rules of a database
	 * from before load.
	 */
	public static List<TLObject> missing() {
		List<TLObject> result = new ArrayList<>();
		TLReference reference = (TLReference) TLModelUtil.findPart(RULE + "#stateDatapoint");
		for (TLObject rule : Queries.withoutValue(RULE, reference)) {
			if (lacks(rule)) {
				result.add(rule);
			}
		}
		return result;
	}

	@Override
	protected void shutDown() {
		_kb.removeModificationListener(this);
		_kb = null;
		super.shutDown();
	}

	@Override
	public Modification createModification(KnowledgeBase kb, CommitActions actions, PendingChanges changes) {
		Set<TLObject> rules = null;
		for (Map<ObjectKey, ? extends KnowledgeItem> objects : List.of(changes.getCreated(), changes.getUpdated())) {
			for (KnowledgeItem item : objects.values()) {
				if (!(item.getWrapper() instanceof TLObject obj) || !obj.tValid()) {
					continue;
				}
				TLObject rule = isRule(obj) ? obj : obj.tContainer() != null && isRule(obj.tContainer()) ? obj.tContainer() : null;
				if (rule != null && lacks(rule)) {
					if (rules == null) {
						rules = new LinkedHashSet<>();
					}
					rules.add(rule);
				}
			}
		}
		if (rules == null) {
			return Modification.NONE;
		}
		Set<TLObject> missing = rules;
		return () -> {
			for (TLObject rule : missing) {
				ensure(rule);
			}
		};
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	private static boolean isRule(TLObject obj) {
		return obj.tType() != null && "Rule".equals(obj.tType().getName())
			&& "ehome.runtime".equals(obj.tType().getModule().getName());
	}

	private static boolean lacks(TLObject rule) {
		return rule.tValid() && !rule.tTransient() && !(((Rule) rule).getStateDatapoint() instanceof TLObject);
	}

	/**
	 * Gives the rule its state datapoint if it has none; the caller provides the transaction.
	 *
	 * @return The rule's state datapoint.
	 */
	public static TLObject ensure(TLObject rule) {
		if (((Rule) rule).getStateDatapoint() instanceof TLObject existing && existing.tValid()) {
			return existing;
		}
		RuleStateDatapoint result = (RuleStateDatapoint) ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType(TYPE));
		((Rule) rule).setStateDatapoint(result);
		return result;
	}

	/**
	 * The state datapoint of the rule, <code>null</code> if it has none (yet).
	 */
	public static TLObject of(TLObject rule) {
		return rule instanceof Rule stored && stored.tValid() && stored.getStateDatapoint() instanceof TLObject datapoint
			&& datapoint.tValid() ? datapoint : null;

	}

	/**
	 * The module of {@link StateDatapoints}.
	 */
	public static final class Module extends TypedRuntimeModule<StateDatapoints> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<StateDatapoints> getImplementation() {
			return StateDatapoints.class;
		}
	}

}
