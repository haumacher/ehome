/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.command.ExecuteScriptAction;
import com.top_logic.layout.view.command.ActionArguments;
import com.top_logic.layout.view.command.ActionScope;
import com.top_logic.layout.view.command.ActionScopeDefinition;
import com.top_logic.layout.view.command.CommandArguments;
import com.top_logic.layout.view.command.ViewCommand;
import com.top_logic.layout.view.command.ViewExecutabilityRule;
import com.top_logic.layout.view.security.CommandAccess;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.visit.DefaultDescendingVisitor;

import com.top_logic.tool.execution.ExecutableState;
import com.top_logic.util.model.ModelService;

/**
 * The commands of the views of a module and the access checks they declare, for the probes of the
 * app and of the tooling (<code>TestCommandAccessProbe</code> in each module, haumacher/ehome #65)
 * and for tests evaluating the check of a single command.
 *
 * <p>
 * A command counts as changing data when one of its actions, at any depth, opens a transaction or
 * is an action of the app (a class in a package <code>de.haumacher.ehome.</code>, which covers the
 * tooling as well): the engine does not access check the Java actions of the app (CLAUDE.md).
 * An <code>&lt;execute-script&gt;</code> counts when its function calls a TL-Script function of the app
 * that declares side effects ({@link GenericMethod#isSideEffectFree()} false, e.g.
 * <code>monitorSend</code>, haumacher/ehome #66); a change of the model made by the script itself is
 * checked by the model's access rights when it is made.
 * <code>&lt;delete-object&gt;</code>, <code>&lt;create-transient&gt;</code> and
 * <code>&lt;persist-transient&gt;</code> bring their check of their own and do not count by
 * themselves.
 * </p>
 */
public class ViewCommandScan {

	/**
	 * The views of a module, relative to the module directory (surefire runs there).
	 */
	public static final Path VIEWS = Path.of("src/main/webapp/WEB-INF/views");

	/** Actions of the engine that change data. */
	private static final Set<String> ENGINE_WRITES = Set.of(
		"com.top_logic.layout.view.command.WithTransactionAction");

	/** Package prefix of the actions of the app and of the tooling. */
	private static final String APP_PACKAGE = "de.haumacher.ehome.";

	/**
	 * A command found in a view.
	 *
	 * @param view
	 *        The view, relative to the views directory.
	 * @param config
	 *        The configuration of the command.
	 * @param trigger
	 *        The names the element triggering the command supplies (e.g. the submitted value of a
	 *        <code>&lt;value-input&gt;</code>), {@link ActionScope#NONE} for a button.
	 */
	public record Site(String view, ViewCommand.Config config, ActionScope trigger) {

		/**
		 * The simple names of the actions of the command changing data, see the class comment.
		 */
		public Set<String> writes() {
			Set<String> result = new TreeSet<>();
			for (ConfigurationItem action : items(config)) {
				if (action == config || action instanceof ViewCommand.Config
					|| !(action instanceof PolymorphicConfiguration<?> polymorphic)
					|| polymorphic.getImplementationClass() == null) {
					continue;
				}
				String name = polymorphic.getImplementationClass().getName();
				if (ENGINE_WRITES.contains(name) || name.startsWith(APP_PACKAGE)) {
					result.add(polymorphic.getImplementationClass().getSimpleName());
				}
				if (action instanceof ExecuteScriptAction.Config script) {
					writingFunctions(script.getFunction(), result);
				}
			}
			return result;
		}

		/**
		 * Whether the command declares an access check.
		 */
		public boolean checked() {
			return !config.getAccess().isEmpty();
		}

		/**
		 * The access the command declares, built as the engine builds it for a command outside an
		 * enclosing UI unit.
		 *
		 * @throws com.top_logic.basic.config.ConfigurationError
		 *         If a check is invalid (an operation not applying to its type, an unknown type, an
		 *         object expression using a name the command does not have).
		 */
		public ViewExecutabilityRule access() {
			return CommandAccess.create(config, arguments().scope(), null);
		}

		/**
		 * The decision of the {@link #access() access} of the command for the current user, run with
		 * the given values of its inputs (in their order; the first one is the main argument of a
		 * command without trigger value).
		 */
		public ExecutableState decide(Object... inputValues) {
			List<Object> values = Arrays.asList(inputValues);
			Object main = values.isEmpty() ? null : values.get(0);
			ActionArguments arguments = arguments().arguments(main, values);
			return access().isExecutableFor(arguments);
		}

		private CommandArguments arguments() {
			return CommandArguments.of(config, trigger);
		}

		@Override
		public String toString() {
			return view + ": " + String.join(", ", writes());
		}
	}

	/**
	 * Adds the names of the functions of the app with side effects that the given script calls, as
	 * <code>name()</code>.
	 */
	static void writingFunctions(Expr function, Set<String> result) {
		if (function == null) {
			return;
		}
		SearchExpression compiled = SearchBuilder.toSearchExpression(ModelService.getApplicationModel(), function);
		compiled.visit(new DefaultDescendingVisitor<Void, Set<String>>() {
			@Override
			protected Void composeGenericMethod(GenericMethod expr, Set<String> names, List<Void> argumentsResult) {
				if (expr.getClass().getName().startsWith(APP_PACKAGE) && !expr.isSideEffectFree()) {
					names.add(expr.getName() + "()");
				}
				return null;
			}
		}, result);
	}

	/**
	 * All commands of all views in the given directory, in the order of the views.
	 */
	public static List<Site> commands(Path views) throws IOException {
		List<Site> result = new ArrayList<>();
		try (Stream<Path> files = Files.walk(views)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".view.xml")).sorted()::iterator) {
				String view = views.relativize(file).toString().replace('\\', '/');
				result.addAll(commands(view));
			}
		}
		return result;
	}

	/**
	 * The commands of the given view (relative to the views directory of the application).
	 */
	public static List<Site> commands(String view) {
		List<Site> result = new ArrayList<>();
		ConfigurationItem root;
		try {
			root = ViewLoader.loadConfig(ViewLoader.fullPath(view));
		} catch (Exception ex) {
			throw new AssertionError("The view " + view + " does not load.", ex);
		}
		collectCommands(view, root, null, result, new IdentityHashMap<>());
		return result;
	}

	/**
	 * The commands of the views in the given directory that change data without an access check.
	 */
	public static List<String> uncheckedCommands(Path views) throws IOException {
		List<String> result = new ArrayList<>();
		for (Site site : commands(views)) {
			if (!site.checked() && !site.writes().isEmpty()) {
				result.add(site.toString());
			}
		}
		return result;
	}

	/**
	 * The commands of the views in the given directory whose declared access does not build, with
	 * the reason.
	 */
	public static List<String> invalidChecks(Path views) throws IOException {
		List<String> result = new ArrayList<>();
		for (Site site : commands(views)) {
			if (!site.checked()) {
				continue;
			}
			try {
				site.access();
			} catch (RuntimeException ex) {
				result.add(site.view() + " (" + site.config().location() + "): " + ex.getMessage());
			}
		}
		return result;
	}

	/**
	 * The only command of the given view whose actions include one of the given simple class name.
	 */
	public static Site command(String view, String action) {
		List<Site> found = new ArrayList<>();
		for (Site site : commands(view)) {
			if (site.writes().contains(action)) {
				found.add(site);
			}
		}
		if (found.size() != 1) {
			throw new AssertionError("Not exactly one command with " + action + " in " + view + ": " + found);
		}
		return found.get(0);
	}

	private static void collectCommands(String view, Object value, ConfigurationItem parent, List<Site> result,
			Map<Object, Boolean> seen) {
		if (value instanceof ConfigurationItem item) {
			if (seen.put(item, Boolean.TRUE) != null) {
				return;
			}
			if (item instanceof ViewCommand.Config command) {
				ActionScope trigger = parent instanceof ActionScopeDefinition definition
					? definition.actionScope(command, ActionScope.NONE)
					: ActionScope.NONE;
				result.add(new Site(view, command, trigger));
			}
			for (PropertyDescriptor property : item.descriptor().getProperties()) {
				if (property.isDerived()) {
					continue;
				}
				collectCommands(view, item.value(property), item, result, seen);
			}
		} else if (value instanceof Iterable<?> list) {
			for (Object element : list) {
				collectCommands(view, element, parent, result, seen);
			}
		} else if (value instanceof Map<?, ?> map) {
			for (Object element : map.values()) {
				collectCommands(view, element, parent, result, seen);
			}
		}
	}

	/**
	 * The given configuration and all configurations nested in it.
	 */
	static List<ConfigurationItem> items(ConfigurationItem root) {
		List<ConfigurationItem> result = new ArrayList<>();
		collect(root, result, new IdentityHashMap<>());
		return result;
	}

	private static void collect(Object value, List<ConfigurationItem> result, Map<Object, Boolean> seen) {
		if (value instanceof ConfigurationItem item) {
			if (seen.put(item, Boolean.TRUE) != null) {
				return;
			}
			result.add(item);
			for (PropertyDescriptor property : item.descriptor().getProperties()) {
				if (property.isDerived()) {
					continue;
				}
				collect(item.value(property), result, seen);
			}
		} else if (value instanceof Iterable<?> list) {
			for (Object element : list) {
				collect(element, result, seen);
			}
		} else if (value instanceof Map<?, ?> map) {
			for (Object element : map.values()) {
				collect(element, result, seen);
			}
		}
	}

}
