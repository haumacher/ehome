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
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

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
 * theirs in a transaction of its own.
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
		List<TLObject> missing = new ArrayList<>();
		for (TLObject rule : MetaElementUtil.getAllInstancesOf((TLClass) TLModelUtil.findType(RULE), TLObject.class)) {
			if (lacks(rule)) {
				missing.add(rule);
			}
		}
		if (!missing.isEmpty()) {
			try (Transaction tx = _kb.beginTransaction(I18NConstants.COMMIT_STATE_DATAPOINTS__COUNT.fill(missing.size()))) {
				for (TLObject rule : missing) {
					ensure(rule);
				}
				tx.commit();
			}
		}
	}

	@Override
	protected void shutDown() {
		_kb.removeModificationListener(this);
		_kb = null;
		super.shutDown();
	}

	@Override
	public Modification createModification(KnowledgeBase kb, CommitActions actions,
			Map<ObjectKey, ? extends KnowledgeItem> createdObjects,
			Map<ObjectKey, ? extends KnowledgeItem> updatedObjects,
			Map<ObjectKey, ? extends KnowledgeItem> removedObjects) {
		Set<TLObject> rules = null;
		for (Map<ObjectKey, ? extends KnowledgeItem> objects : List.of(createdObjects, updatedObjects)) {
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
		return rule.tValid() && !rule.tTransient() && !(rule.tValueByName("stateDatapoint") instanceof TLObject);
	}

	/**
	 * Gives the rule its state datapoint if it has none; the caller provides the transaction.
	 *
	 * @return The rule's state datapoint.
	 */
	public static TLObject ensure(TLObject rule) {
		if (rule.tValueByName("stateDatapoint") instanceof TLObject existing && existing.tValid()) {
			return existing;
		}
		TLObject result = ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(TYPE));
		rule.tUpdateByName("stateDatapoint", result);
		return result;
	}

	/**
	 * The state datapoint of the rule, <code>null</code> if it has none (yet).
	 */
	public static TLObject of(TLObject rule) {
		return rule != null && rule.tValid() && rule.tValueByName("stateDatapoint") instanceof TLObject datapoint
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
