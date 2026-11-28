/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.physical.Connector;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.template.DeviceTemplate;
import de.haumacher.ehome.model.template.ObjectOverlay;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.GroupAddressTypes;

/**
 * The device templates of the installation (haumacher/ehome #103, module <code>ehome.template</code>):
 * at most one per application program, each with an overlay per communication object of the
 * program. What a template's overlay gives an object applies to every device of the program through
 * {@link ObjectTypes}.
 */
public final class DeviceTemplates {

	private static final String TEMPLATE = "ehome.template:DeviceTemplate";

	private static final String OVERLAY = "ehome.template:ObjectOverlay";

	/** The untyped connected objects of a project by program, as computed for a revision of the knowledge base. */
	private record Cached(long revision, Map<TLObject, Set<Interface>> untyped) {
		// Value.
	}

	private static final Map<TLObject, Cached> UNTYPED = new WeakHashMap<>();

	private DeviceTemplates() {
		// Static only.
	}

	/**
	 * The singleton holding the templates (<code>ehome.template:DeviceTemplates</code>).
	 */
	public static TLObject root() {
		TLModule module = TLModelUtil.findModule("ehome.template");
		return module == null ? null : module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

	/**
	 * The template of the given application program, <code>null</code> if it has none.
	 */
	public static TLObject template(TLObject program) {
		if (program == null) {
			return null;
		}
		for (TLObject template : program.tReferers(reference(TEMPLATE, "program"))) {
			if (template != null && template.tValid() && template.tContainer() != null) {
				return template;
			}
		}
		return null;
	}

	/**
	 * Creates the template of the given application program, with an overlay without a type for
	 * each of its communication objects (each placement), ordered by the number of the object.
	 *
	 * <p>
	 * Must be called within a transaction.
	 * </p>
	 *
	 * @return The new template.
	 * @throws TopLogicException
	 *         If the program has a template already, or is no application program.
	 */
	public static TLObject create(TLObject program) {
		if (!ExtIds.isA(program, "ehome.operational:Program")) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROGRAM__VALUE.fill(program));
		}
		TLObject existing = template(program);
		if (existing != null) {
			throw new TopLogicException(I18NConstants.ERROR_TEMPLATE_EXISTS__PROGRAM.fill(program));
		}
		de.haumacher.ehome.model.template.DeviceTemplates root = (de.haumacher.ehome.model.template.DeviceTemplates) root();
		DeviceTemplate template = (DeviceTemplate) create(TEMPLATE);
		template.setProgram((Program) program);
		List<ObjectOverlay> overlays = new ArrayList<>();
		for (Interface placed : placements(program)) {
			ObjectOverlay overlay = (ObjectOverlay) create(OVERLAY);
			overlay.setPlacement(placed);
			overlays.add(overlay);
		}
		template.setOverlays(overlays);
		Set<DeviceTemplate> templates = new LinkedHashSet<>(root.getTemplates());
		templates.add(template);
		root.setTemplates(templates);
		return template;
	}

	/**
	 * The communication objects of the program as placed in its structure, by the number of the
	 * object.
	 */
	public static List<Interface> placements(TLObject program) {
		List<Interface> result = new ArrayList<>();
		if (program == null) {
			return result;
		}
		for (Port port : ((Program) program).getPorts()) {
			result.addAll(port.getInterfaces());
		}
		result.sort(Comparator.comparingInt((Interface i) -> i.getPort().getIndex())
			.thenComparing(i -> String.valueOf(i.getDisplayName())));
		return result;
	}

	/**
	 * The products that run the program (through their program assignments).
	 */
	public static List<TLObject> products(TLObject program) {
		Set<TLObject> result = new LinkedHashSet<>();
		if (program != null) {
			for (TLObject assignment : program.tReferers(reference("ehome.catalog:ProgramAssignment", "program"))) {
				if (assignment != null && assignment.tValid() && assignment.tContainer() != null) {
					result.add(assignment.tContainer());
				}
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The communication objects of the program for which E-Home cannot determine a datapoint type in
	 * a device of the given project that connects them to a group address: no type in the chain of
	 * {@link ObjectTypes}, and a size other than 1 bit (DPT 1) or 4 bits (DPT 3), which alone decide
	 * the main type. Each object (placement) is listed once, whichever devices connect it, in the
	 * order of {@link #placements(TLObject)}.
	 *
	 * <p>
	 * The search starts at the project (its devices running the program, their connectors): its cost
	 * is bound by the project, independent of the number of projects (haumacher/ehome #166). It is
	 * computed once per project and revision of the knowledge base for all programs.
	 * </p>
	 *
	 * @param project
	 *        The project, mandatory: no other project is read.
	 */
	public static List<Interface> untypedConnected(TLObject program, TLObject project) {
		Objects.requireNonNull(project, "The project is mandatory.");
		Set<Interface> found = untypedByProgram(project).getOrDefault(program, Set.of());
		List<Interface> result = new ArrayList<>();
		if (found.isEmpty()) {
			return result;
		}
		for (Interface placed : placements(program)) {
			if (found.contains(placed)) {
				result.add(placed);
			}
		}
		return result;
	}

	private static Map<TLObject, Set<Interface>> untypedByProgram(TLObject project) {
		// The state of the data the caller sees (its session revision), not the last commit of others.
		long revision = HistoryUtils.getSessionRevision().getCommitNumber();
		synchronized (UNTYPED) {
			Cached cached = UNTYPED.get(project);
			if (cached != null && cached.revision() == revision) {
				return cached.untyped();
			}
		}
		Map<TLObject, Set<Interface>> result = new HashMap<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			TLObject program = DeviceConfiguration.program(device);
			if (program == null) {
				continue;
			}
			for (Connector connector : ((Device) device).getConnectors()) {
				if (!(connector.getPort() instanceof Interface placed) || connector.getConnections().isEmpty()) {
					continue;
				}
				int size = ObjectTypes.size(placed);
				if (size != 1 && size != 4 && ObjectTypes.effective(connector) == null) {
					result.computeIfAbsent(program, p -> new HashSet<>()).add(placed);
				}
			}
		}
		synchronized (UNTYPED) {
			UNTYPED.put(project, new Cached(revision, result));
		}
		return result;
	}

	/**
	 * The application program the placed communication object belongs to, <code>null</code> for none.
	 */
	public static TLObject program(Interface placed) {
		Port port = placed == null ? null : placed.getPort();
		for (TLObject current = port; current != null; current = current.tContainer()) {
			if (ExtIds.isA(current, "ehome.operational:Program")) {
				return current;
			}
		}
		return null;
	}

	/**
	 * The datapoint types offered for the placed object: those of its size, all if none has its size.
	 */
	public static List<TLObject> typeChoices(Interface placed) {
		if (placed == null) {
			return GroupAddressTypes.allTypes();
		}
		List<TLObject> result = GroupAddressTypes.ofSize(Integer.valueOf(ObjectTypes.size(placed)));
		return result.isEmpty() ? GroupAddressTypes.allTypes() : result;
	}

	private static TLReference reference(String type, String name) {
		return (TLReference) ((TLClass) TLModelUtil.findType(type)).getPart(name);
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

}
