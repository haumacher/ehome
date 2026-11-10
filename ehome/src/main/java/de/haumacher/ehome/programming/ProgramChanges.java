/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

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
import com.top_logic.knowledge.objects.KnowledgeAssociation;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.modules.ModuleInstances;

/**
 * Brings a device whose program changed in line with the new program, whichever code changed it (the
 * device form, haumacher/ehome #121): the instances of the new program's dynamic modules are
 * computed, and the R values of its parameter calculations from its L values (haumacher/ehome #123),
 * as for a device created in E-Home.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base: it compares the program assignment of each
 * updated device with its value at the last commit (its historic version) and updates the device in the
 * {@link Modification} it returns, which runs within the commit. A calculation that cannot be
 * evaluated or fails refuses the commit with its reason, so the program change is rolled back with it.
 * A device created in the transaction is computed by its creator.
 * </p>
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.physical.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class })
public class ProgramChanges extends ConfiguredManagedClass<ProgramChanges.Config<?>> implements ModificationListener {

	private static final String DEVICE = "ehome.physical:Device";

	private static final String PROGRAM = "program";

	/**
	 * Configuration of {@link ProgramChanges}.
	 */
	public interface Config<I extends ProgramChanges> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link ProgramChanges} from configuration.
	 */
	public ProgramChanges(InstantiationContext context, Config<?> config) {
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
		// The program reference may be stored in a link (a new one is created) or in the device (updated).
		Set<TLObject> candidates = new LinkedHashSet<>();
		for (Map<ObjectKey, ? extends KnowledgeItem> items : List.of(updatedObjects, createdObjects)) {
			for (KnowledgeItem item : items.values()) {
				KnowledgeItem owner = item instanceof KnowledgeAssociation link ? link.getSourceObject() : item;
				if (owner != null && !createdObjects.containsKey(owner.tId())
					&& owner.getWrapper() instanceof TLObject device && ExtIds.isA(device, DEVICE)) {
					candidates.add(device);
				}
			}
		}
		List<TLObject> changed = new ArrayList<>();
		for (TLObject device : candidates) {
			if (!device.tValid()) {
				continue;
			}
			Object after = device.tValueByName(PROGRAM);
			// The program the device had at the last commit (its historic version).
			TLObject historic = WrapperHistoryUtils.getWrapper(HistoryUtils.getLastRevision(), device);
			Object before = historic == null ? null : historic.tValueByName(PROGRAM);
			if (before instanceof TLObject old && after instanceof TLObject now
				&& !WrapperHistoryUtils.equalsUnversioned(old, now)) {
				changed.add(device);
			}
		}
		if (changed.isEmpty()) {
			return Modification.NONE;
		}
		return () -> {
			for (TLObject device : changed) {
				if (device.tValid()) {
					ModuleInstances.update(device);
					DeviceCalculations.recompute(device);
				}
			}
		};
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	/**
	 * The module of {@link ProgramChanges}.
	 */
	public static final class Module extends TypedRuntimeModule<ProgramChanges> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<ProgramChanges> getImplementation() {
			return ProgramChanges.class;
		}
	}

}
