/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.basic.util.ResKey;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.event.infoservice.InfoService;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.layout.basic.DefaultDisplayContext;
import com.top_logic.layout.basic.fragments.Fragments;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ExpressionRenames.RenamedDatapoint;
import de.haumacher.ehome.runtime.ExpressionRenames.RenamedParameter;

/**
 * Follows a renamed id in the expressions of the project (haumacher/ehome #111): when the id of a
 * rule parameter, a timer, a state or an application datapoint, or the id of the datapoint of a
 * group address (its identifier, else its address, haumacher/ehome #130) changes, the expressions of
 * the project's rules that name it are rewritten in the same transaction ({@link ExpressionRenames}).
 * An expression entered or changed that names a group address with an identifier by its address
 * (<code>@"1/2/3"</code>) is written with the identifier (<code>@kueche.licht</code>), the name of its
 * datapoint in the image.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base: it sees every commit, whichever code changed
 * the id (a form, a table, an import in Java), compares the id of each updated object with its value
 * before the transaction and rewrites the expressions in the {@link Modification} it returns, which
 * the knowledge base runs within the commit, before the commit checks. A commit refused by them (an
 * id the model's constraints refuse, such as a parameter id outside the characters of
 * haumacher/ehome #110) rolls the rewrite back with the rename. Objects created in the transaction
 * have no id to follow; an object deleted and created again is no rename.
 * </p>
 *
 * <p>
 * After the commit succeeded, the user is told how many expressions were adjusted and, for a
 * renamed parameter, that the value set on the controller is lost (homecontrol
 * <code>spec/rules.md</code> 8.4, D5), for a renamed datapoint with a persisted value or a history
 * that the controller loses them with the next deployment, since it keeps them by id (homecontrol
 * <code>spec/runtime-image.md</code> 3.3): an after-commit action of the transaction
 * ({@link CommitActions}) shows them through the {@link InfoService} of the interaction, which the
 * view layer shows in the window that saved (outside a window, e.g. in a job, nothing is shown).
 * </p>
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.runtime.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class })
public class IdRenames extends ConfiguredManagedClass<IdRenames.Config<?>> implements ModificationListener {

	/**
	 * Configuration of {@link IdRenames}.
	 */
	public interface Config<I extends IdRenames> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	/**
	 * What a commit rewrote, for the messages to the user.
	 *
	 * @param expressions
	 *        The number of expressions adjusted.
	 * @param parameters
	 *        The renamed parameters.
	 * @param datapoints
	 *        The renamed datapoints with a persisted value or a history.
	 */
	public record Report(int expressions, List<RenamedParameter> parameters, List<RenamedDatapoint> datapoints) {

		/** The datapoints a message names at most. */
		private static final int LISTED = 10;

		/**
		 * A report without renamed datapoints.
		 */
		public Report(int expressions, List<RenamedParameter> parameters) {
			this(expressions, parameters, List.of());
		}

		/**
		 * The message about the expressions adjusted, <code>null</code> if none was.
		 */
		public ResKey adjusted() {
			if (expressions == 0) {
				return null;
			}
			return expressions == 1 ? I18NConstants.EXPRESSION_ADJUSTED
				: I18NConstants.EXPRESSIONS_ADJUSTED__COUNT.fill(Integer.valueOf(expressions));
		}

		/**
		 * The notes about the renamed parameters (the value set on the controller is lost) and the
		 * renamed datapoints with a persisted value or a history (the controller keeps them by id,
		 * haumacher/ehome #130).
		 */
		public List<ResKey> notes() {
			List<ResKey> result = new ArrayList<>();
			for (RenamedParameter parameter : parameters) {
				result.add(I18NConstants.PARAMETER_VALUE_LOST__RULE_OLD_NEW.fill(parameter.rule(), parameter.oldId(),
					parameter.newId()));
			}
			if (datapoints.size() == 1) {
				RenamedDatapoint datapoint = datapoints.get(0);
				result.add(I18NConstants.DATAPOINT_VALUES_LOST__NAME_OLD_NEW.fill(name(datapoint), datapoint.oldId(),
					datapoint.newId()));
			} else if (!datapoints.isEmpty()) {
				List<String> list = new ArrayList<>();
				for (RenamedDatapoint datapoint : datapoints) {
					if (list.size() == LISTED) {
						list.add("…");
						break;
					}
					list.add(name(datapoint) + ": " + datapoint.oldId() + " → " + datapoint.newId());
				}
				result.add(I18NConstants.DATAPOINTS_VALUES_LOST__COUNT_LIST.fill(Integer.valueOf(datapoints.size()),
					String.join(", ", list)));
			}
			return result;
		}

		private static String name(RenamedDatapoint datapoint) {
			String label = datapoint.datapoint().tValid() ? DatapointNames.label(datapoint.datapoint()) : null;
			return label == null ? datapoint.newId() : label;
		}
	}

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link IdRenames} from configuration.
	 */
	public IdRenames(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		_kb = PersistencyLayer.getKnowledgeBase();
		_kb.addModificationListener(this);
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
		Map<TLObject, ExpressionRenames> renames = null;
		Map<TLObject, List<TLObject>> entered = null;
		for (KnowledgeItem item : updatedObjects.values()) {
			if (!(item.getWrapper() instanceof TLObject obj)) {
				continue;
			}
			if (ExpressionRenames.holdsExpression(obj)) {
				entered = noteEntered(entered, obj);
			}
			Kind kind = Kind.of(obj);
			if (kind == null) {
				continue;
			}
			String after = kind.id(obj);
			String before = kb.withoutModifications(() -> kind.id(obj));
			if (before == null || after == null || before.equals(after)) {
				continue;
			}
			TLObject project = RuntimeFunctions.project(obj);
			if (project == null) {
				continue;
			}
			if (renames == null) {
				renames = new LinkedHashMap<>();
			}
			kind.note(renames.computeIfAbsent(project, x -> new ExpressionRenames()), obj, before, after);
		}
		for (KnowledgeItem item : createdObjects.values()) {
			if (item.getWrapper() instanceof TLObject obj && ExpressionRenames.holdsExpression(obj)) {
				entered = noteEntered(entered, obj);
			}
		}
		if (renames == null && entered == null) {
			return Modification.NONE;
		}
		Map<TLObject, ExpressionRenames> all = renames == null ? Map.of() : renames;
		Map<TLObject, List<TLObject>> parts = entered == null ? Map.of() : entered;
		return () -> {
			Report report = apply(all);
			normalize(parts);
			if (DefaultDisplayContext.hasDisplayContext()) {
				actions.afterCommit(changes -> show(report));
			}
		};
	}

	/**
	 * Notes a part of a rule whose expression was entered or changed, by its project, if the
	 * expression names a datapoint by a quoted id (the address of a group address).
	 */
	private static Map<TLObject, List<TLObject>> noteEntered(Map<TLObject, List<TLObject>> entered, TLObject part) {
		if (!namesQuoted(part)) {
			return entered;
		}
		TLObject project = RuntimeFunctions.project(part);
		if (project == null) {
			return entered;
		}
		Map<TLObject, List<TLObject>> result = entered == null ? new LinkedHashMap<>() : entered;
		result.computeIfAbsent(project, x -> new ArrayList<>()).add(part);
		return result;
	}

	private static boolean namesQuoted(TLObject part) {
		for (String attribute : new String[] { "condition", "value", "duration", "after", "message" }) {
			if (part.tType().getPart(attribute) != null && part.tValueByName(attribute) instanceof String text
				&& text.contains("@\"")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Writes the entered expressions naming a group address by its address (<code>@"1/2/3"</code>)
	 * with the address's identifier instead (<code>@kueche.licht</code>, haumacher/ehome #130), the
	 * name the image gives its datapoint; quietly, as the canonical form of an entered text.
	 */
	static void normalize(Map<TLObject, List<TLObject>> entered) {
		for (Map.Entry<TLObject, List<TLObject>> entry : entered.entrySet()) {
			ExpressionRenames identifiers = new ExpressionRenames();
			for (TLObject address : RuntimeFunctions.groupAddresses(entry.getKey())) {
				String identifier = GroupAddressIds.identifier(address);
				String threeLevel = GroupAddressIds.address(address);
				if (identifier != null && threeLevel != null) {
					identifiers.datapoint(threeLevel, identifier);
				}
			}
			if (identifiers.isEmpty()) {
				continue;
			}
			for (TLObject part : entry.getValue()) {
				if (part.tValid()) {
					identifiers.applyTo(part);
				}
			}
		}
	}

	/**
	 * Rewrites the expressions of the projects.
	 */
	static Report apply(Map<TLObject, ExpressionRenames> renames) {
		int expressions = 0;
		List<RenamedParameter> parameters = new ArrayList<>();
		List<RenamedDatapoint> datapoints = new ArrayList<>();
		for (Map.Entry<TLObject, ExpressionRenames> entry : renames.entrySet()) {
			ExpressionRenames project = entry.getValue();
			if (!project.isEmpty()) {
				expressions += project.apply(entry.getKey());
				parameters.addAll(project.getRenamedParameters());
			}
			datapoints.addAll(keepingValues(entry.getKey(), project.getRenamedDatapoints()));
		}
		return new Report(expressions, parameters, datapoints);
	}

	/**
	 * The renamed datapoints for which the controller keeps something by their id: a persisted
	 * value or a history (set by hand or derived from the charts).
	 */
	private static List<RenamedDatapoint> keepingValues(TLObject project, List<RenamedDatapoint> renamed) {
		if (renamed.isEmpty()) {
			return List.of();
		}
		Map<TLObject, HistoryConfig.History> derived = HistoryConfig.derivedHistories(project);
		List<RenamedDatapoint> result = new ArrayList<>();
		for (RenamedDatapoint datapoint : renamed) {
			TLObject obj = datapoint.datapoint();
			boolean persist = Boolean.TRUE.equals(obj.tValueByName(Pages.isGroupAddress(obj) ? "runtimePersist" : "persist"));
			boolean history = HistoryConfig.isManual(obj) ? HistoryConfig.manual(obj) != null : derived.get(obj) != null;
			if (persist || history) {
				result.add(datapoint);
			}
		}
		return result;
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	/**
	 * The kinds of objects whose id expressions name.
	 */
	private enum Kind {

		/** A parameter of a rule: <code>$id</code>, <code>@"rule/&lt;rule&gt;/&lt;id&gt;"</code>. */
		PARAMETER("RuleParameter") {
			@Override
			void note(ExpressionRenames renames, TLObject obj, String before, String after) {
				String rule = rule(obj);
				// An id the model refuses (haumacher/ehome #110) is no name to follow; the commit fails.
				if (rule != null && RuleExport.parameterIdProblem(after, null) == null) {
					renames.parameter(rule, before, after);
				}
			}
		},

		/** A timer of a rule: <code>running(id)</code>, <code>remaining(id)</code>. */
		TIMER("RuleTimer") {
			@Override
			void note(ExpressionRenames renames, TLObject obj, String before, String after) {
				String rule = rule(obj);
				if (rule != null) {
					renames.timer(rule, before, after);
				}
			}
		},

		/** A state of a rule: <code>state() == "id"</code>, <code>state("rule") == "id"</code>. */
		STATE("RuleState") {
			@Override
			void note(ExpressionRenames renames, TLObject obj, String before, String after) {
				String rule = rule(obj);
				if (rule != null) {
					renames.state(rule, before, after);
				}
			}
		},

		/** An application datapoint: <code>@"id"</code>. */
		APPLICATION_DATAPOINT(ApplicationDatapoints.TYPE) {
			@Override
			void note(ExpressionRenames renames, TLObject obj, String before, String after) {
				renames.datapoint(obj, before, after);
			}
		},

		/**
		 * A group address: <code>@kueche.licht</code> by its identifier, else <code>@"1/2/3"</code>
		 * by its address in three levels (haumacher/ehome #130); setting, changing and removing the
		 * identifier is a rename as a change of the address without one is.
		 */
		GROUP_ADDRESS(null) {
			@Override
			String id(TLObject obj) {
				return GroupAddressIds.datapointId(obj);
			}

			@Override
			void note(ExpressionRenames renames, TLObject obj, String before, String after) {
				// An identifier the model refuses is no name to follow; the commit fails.
				if (GroupAddressIds.identifier(obj) == null || GroupAddressIds.syntaxProblem(after) == null) {
					renames.datapoint(obj, before, after);
				}
			}
		};

		private final String _runtimeType;

		Kind(String runtimeType) {
			_runtimeType = runtimeType;
		}

		/**
		 * The kind of objects of the given type, <code>null</code> if expressions do not name them.
		 */
		static Kind of(TLObject obj) {
			TLStructuredType type = obj.tType();
			if (type == null) {
				return null;
			}
			if ("ehome.runtime".equals(type.getModule().getName())) {
				String name = type.getName();
				for (Kind kind : values()) {
					if (name.equals(kind._runtimeType)) {
						return kind;
					}
				}
				return null;
			}
			return "ehome.logical".equals(type.getModule().getName()) && Pages.isGroupAddress(obj) ? GROUP_ADDRESS : null;
		}

		/**
		 * The id an expression names the object by, <code>null</code> for none.
		 */
		String id(TLObject obj) {
			return identifier(obj);
		}

		/**
		 * Notes the rename of the object.
		 */
		abstract void note(ExpressionRenames renames, TLObject obj, String before, String after);

		static String rule(TLObject part) {
			TLObject rule = part.tContainer();
			return rule == null ? null : identifier(rule);
		}
	}

	static String identifier(TLObject obj) {
		Object id = obj.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/**
	 * Shows the messages of the report to the user of the current interaction (the view layer
	 * forwards them to the window that ran the command).
	 */
	static void show(Report report) {
		// As text: the view layer's error sink renders the fragment itself, not the info area's template.
		ResKey adjusted = report.adjusted();
		if (adjusted != null) {
			InfoService.showInfo(Fragments.text(Resources.getInstance().getString(adjusted)));
		}
		for (ResKey note : report.notes()) {
			InfoService.showWarning(Fragments.text(Resources.getInstance().getString(note)));
		}
	}

	/**
	 * The module of {@link IdRenames}.
	 */
	public static final class Module extends TypedRuntimeModule<IdRenames> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<IdRenames> getImplementation() {
			return IdRenames.class;
		}
	}

}
