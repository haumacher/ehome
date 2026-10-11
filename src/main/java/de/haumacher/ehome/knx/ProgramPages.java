/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Switch;

/**
 * The pages of an application program as its catalog shows them (haumacher/ehome #20): the
 * channels, the channel independent part and the parameter blocks in the order of the structure,
 * each with the parameters and communication objects placed on it, and the parameters and
 * communication objects placed nowhere.
 *
 * <p>
 * The structure is walked without a device, so no value selects a case: every case of every switch
 * is part of the walk, and a page or a placement inside a case belongs to the page around the
 * switch, as {@link de.haumacher.ehome.programming.DeviceConfiguration} places it when the case is
 * taken. A page is named by its path in the structure (channel › block › sub-block), as the change
 * list of a device names it; pages of different cases with the same path (the same block written
 * out for each function of a key) are one page here, holding the placements of all of them. A
 * parameter shown as the title of a block is placed on that block.
 * </p>
 *
 * <p>
 * The pages are listed with their parameters (communication objects), each once per page with the
 * first of its placements there; a parameter placed on several pages is listed on each. The views
 * read them as rows of a table grouped by the page (dictionaries, see {@link #parameterRows(String,
 * boolean)} and {@link #portRows(String, boolean)}).
 * </p>
 */
public class ProgramPages {

	/** Key of a row: the path text of the page; the text for "not placed" in the last group. */
	public static final String PAGE = "page";

	/** Key of a row: the parameter (communication object) definition. */
	public static final String DEFINITION = "definition";

	/**
	 * Key of a row: the first placement of the definition on the page (a {@link ParameterView}, an
	 * {@link Interface}); <code>null</code> for a definition placed nowhere.
	 */
	public static final String PLACEMENT = "placement";

	/** Key of a row: the number of placements of the definition in the structure. */
	public static final String PLACEMENTS = "placements";

	private static final String SEPARATOR = " › ";

	private final Program _program;

	/** The pages in the order of the structure, by their path text. */
	private final Map<String, Page> _pages = new LinkedHashMap<>();

	private final Map<Parameter, Set<ParameterView>> _parameterPlacements = new HashMap<>();

	private final Map<Port, Set<Interface>> _portPlacements = new HashMap<>();

	/**
	 * One page: the parameters and communication objects placed on it, each with its first
	 * placement there, in the order of the structure.
	 */
	public static final class Page {

		private final String _title;

		private final Map<Parameter, ParameterView> _parameters = new LinkedHashMap<>();

		private final Map<Port, Interface> _ports = new LinkedHashMap<>();

		Page(String title) {
			_title = title;
		}

		/**
		 * The path of the page in the structure.
		 */
		public String getTitle() {
			return _title;
		}

		/**
		 * The parameters placed on the page, each with its first placement there.
		 */
		public Map<Parameter, ParameterView> getParameters() {
			return _parameters;
		}

		/**
		 * The communication objects placed on the page, each with its first placement there.
		 */
		public Map<Port, Interface> getPorts() {
			return _ports;
		}
	}

	/**
	 * Creates the {@link ProgramPages} of the given program.
	 */
	public ProgramPages(Program program) {
		_program = program;
		walk(program, null, null);
	}

	/**
	 * The program.
	 */
	public Program getProgram() {
		return _program;
	}

	/**
	 * The pages in the order of the structure.
	 */
	public List<Page> getPages() {
		return new ArrayList<>(_pages.values());
	}

	/**
	 * The parameters of the program placed nowhere in the structure, by name.
	 */
	public List<Parameter> getUnplacedParameters() {
		List<Parameter> result = new ArrayList<>();
		for (Parameter parameter : _program.getParameters()) {
			if (!_parameterPlacements.containsKey(parameter)) {
				result.add(parameter);
			}
		}
		result.sort(Comparator.comparing((Parameter p) -> text(p.getName()))
			.thenComparing(p -> text(ExtIds.extId(p))));
		return result;
	}

	/**
	 * The communication objects of the program placed nowhere in the structure, by number.
	 */
	public List<Port> getUnplacedPorts() {
		List<Port> result = new ArrayList<>();
		for (Port port : _program.getPorts()) {
			if (!_portPlacements.containsKey(port)) {
				result.add(port);
			}
		}
		result.sort(Comparator.comparingInt(Port::getIndex).thenComparing(p -> text(p.getName())));
		return result;
	}

	/**
	 * The number of placements of the given parameter in the structure.
	 */
	public int placements(Parameter parameter) {
		Set<ParameterView> placements = _parameterPlacements.get(parameter);
		return placements == null ? 0 : placements.size();
	}

	/**
	 * The number of placements of the given communication object in the structure.
	 */
	public int placements(Port port) {
		Set<Interface> placements = _portPlacements.get(port);
		return placements == null ? 0 : placements.size();
	}

	/**
	 * The rows of the parameters, grouped by page in the order of the structure, the parameters
	 * placed nowhere last.
	 *
	 * @param filter
	 *        The text a row must contain (ignoring case) in the name or text of its parameter, the
	 *        text of its placement or the name of its type; empty for all rows.
	 * @param unplacedOnly
	 *        Whether only the parameters placed nowhere are listed.
	 * @return Per row a dictionary with {@link #PAGE}, {@link #DEFINITION}, {@link #PLACEMENT} and
	 *         {@link #PLACEMENTS}.
	 */
	public List<Map<String, Object>> parameterRows(String filter, boolean unplacedOnly) {
		String term = normalize(filter);
		List<Map<String, Object>> result = new ArrayList<>();
		if (!unplacedOnly) {
			for (Page page : _pages.values()) {
				for (Map.Entry<Parameter, ParameterView> entry : page.getParameters().entrySet()) {
					if (matches(entry.getKey(), entry.getValue(), term)) {
						result.add(row(page.getTitle(), entry.getKey(), entry.getValue(), placements(entry.getKey())));
					}
				}
			}
		}
		String unplaced = notPlaced();
		for (Parameter parameter : getUnplacedParameters()) {
			if (matches(parameter, null, term)) {
				result.add(row(unplaced, parameter, null, 0));
			}
		}
		return result;
	}

	/**
	 * The rows of the communication objects, grouped by page in the order of the structure, the
	 * objects placed nowhere last.
	 *
	 * @param filter
	 *        The text a row must contain (ignoring case) in the name, text, function or description
	 *        of its object or the text of its placement; empty for all rows.
	 * @param unplacedOnly
	 *        Whether only the objects placed nowhere are listed.
	 * @return Per row a dictionary with {@link #PAGE}, {@link #DEFINITION}, {@link #PLACEMENT} and
	 *         {@link #PLACEMENTS}.
	 */
	public List<Map<String, Object>> portRows(String filter, boolean unplacedOnly) {
		String term = normalize(filter);
		List<Map<String, Object>> result = new ArrayList<>();
		if (!unplacedOnly) {
			for (Page page : _pages.values()) {
				for (Map.Entry<Port, Interface> entry : page.getPorts().entrySet()) {
					if (matches(entry.getKey(), entry.getValue(), term)) {
						result.add(row(page.getTitle(), entry.getKey(), entry.getValue(), placements(entry.getKey())));
					}
				}
			}
		}
		String unplaced = notPlaced();
		for (Port port : getUnplacedPorts()) {
			if (matches(port, null, term)) {
				result.add(row(unplaced, port, null, 0));
			}
		}
		return result;
	}

	/**
	 * The first row of the given parameter or communication object: on the first page it is placed
	 * on, else in the group of the definitions placed nowhere; <code>null</code> for anything else.
	 */
	public Map<String, Object> rowOf(TLObject definition) {
		if (definition instanceof Parameter parameter) {
			for (Page page : _pages.values()) {
				ParameterView view = page.getParameters().get(parameter);
				if (view != null) {
					return row(page.getTitle(), parameter, view, placements(parameter));
				}
			}
			return row(notPlaced(), parameter, null, 0);
		}
		if (definition instanceof Port port) {
			for (Page page : _pages.values()) {
				Interface placed = page.getPorts().get(port);
				if (placed != null) {
					return row(page.getTitle(), port, placed, placements(port));
				}
			}
			return row(notPlaced(), port, null, 0);
		}
		return null;
	}

	private static Map<String, Object> row(String page, TLObject definition, Option placement, int placements) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(PAGE, page);
		row.put(DEFINITION, definition);
		row.put(PLACEMENT, placement);
		row.put(PLACEMENTS, Integer.valueOf(placements));
		return row;
	}

	/**
	 * The title of the group of the definitions placed nowhere.
	 */
	public static String notPlaced() {
		return Resources.getInstance().getString(I18NConstants.NOT_PLACED);
	}

	private void walk(ConfigFragment fragment, Page page, String path) {
		for (Option option : fragment.getContents()) {
			walk(option, page, path);
		}
	}

	private void walk(Option option, Page page, String path) {
		if (option instanceof ParameterView view) {
			addParameter(page(page, path), view);
		} else if (option instanceof Interface placed) {
			addPort(page(page, path), placed);
		} else if (option instanceof Switch choice) {
			// Without a device no value selects a case: every case is part of the catalog.
			for (ConfigFragment alternative : choice.getAlternatives()) {
				walk(alternative, page, path);
			}
		} else if (option instanceof ConfigFragment fragment) {
			Page inner = page;
			String innerPath = path;
			if (isPage(fragment)) {
				// ETS indents the texts of sub-blocks with spaces.
				String label = MetaLabelProvider.INSTANCE.getLabel(fragment).trim();
				innerPath = path == null ? label : path + SEPARATOR + label;
				inner = _pages.computeIfAbsent(innerPath, Page::new);
			}
			if (option instanceof ParameterGroup group && group.getInput() != null) {
				// The parameter a block shows as its title is placed on the block.
				addParameter(inner, group.getInput());
			}
			walk(fragment, inner, innerPath);
		}
	}

	/**
	 * The page an entry outside of every page goes to: the program itself.
	 */
	private Page page(Page page, String path) {
		if (page != null) {
			return page;
		}
		return _pages.computeIfAbsent(MetaLabelProvider.INSTANCE.getLabel(_program), Page::new);
	}

	private static boolean isPage(ConfigFragment fragment) {
		return fragment instanceof Channel || fragment instanceof ChannelIndependent
			|| fragment instanceof ParameterGroup;
	}

	private void addParameter(Page page, ParameterView view) {
		Parameter parameter = view.getParameter();
		if (parameter == null) {
			return;
		}
		_parameterPlacements.computeIfAbsent(parameter, x -> new LinkedHashSet<>()).add(view);
		page.getParameters().putIfAbsent(parameter, view);
	}

	private void addPort(Page page, Interface placed) {
		Port port = placed.getPort();
		if (port == null) {
			return;
		}
		_portPlacements.computeIfAbsent(port, x -> new LinkedHashSet<>()).add(placed);
		page.getPorts().putIfAbsent(port, placed);
	}

	private static boolean matches(Parameter parameter, ParameterView view, String term) {
		if (term.isEmpty()) {
			return true;
		}
		return contains(parameter.getName(), term)
			|| contains(parameter.tValueByName("description"), term)
			|| view != null && (contains(view.getName(), term) || contains(view.tValueByName("description"), term))
			|| parameter.getType() != null && (contains(parameter.getType().getName(), term)
				|| contains(MetaLabelProvider.INSTANCE.getLabel(parameter.getType()), term));
	}

	private static boolean matches(Port port, Interface placed, String term) {
		if (term.isEmpty()) {
			return true;
		}
		return contains(port.getName(), term)
			|| contains(port.tValueByName("text"), term)
			|| contains(port.tValueByName("function"), term)
			|| contains(port.getDescription(), term)
			|| placed != null && (contains(placed.tValueByName("name"), term)
				|| contains(placed.tValueByName("function"), term)
				|| contains(placed.getDescription(), term));
	}

	private static boolean contains(Object value, String term) {
		String text;
		if (value instanceof ResKey key) {
			text = Resources.getInstance().getString(key, null);
		} else if (value instanceof Collection<?> || value == null) {
			return false;
		} else {
			text = value.toString();
		}
		return text != null && normalize(text).contains(term);
	}

	private static String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

}
