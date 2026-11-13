/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

/**
 * Makes the account creating a project its owner (haumacher/ehome #152): whichever way the project is
 * created (the dialog "New project", an ETS import, a project file, an agent through the model), the
 * commit creating it names the current user as its owner, unless the creating code named owners
 * itself. A project created without a user (in the system context) gets no owner; administrators act
 * for it.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base, registered in
 * <code>WEB-INF/autoconf/ehome.project.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class })
public class ProjectOwners extends ConfiguredManagedClass<ProjectOwners.Config<?>> implements ModificationListener {

	/**
	 * Configuration of {@link ProjectOwners}.
	 */
	public interface Config<I extends ProjectOwners> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link ProjectOwners} from configuration.
	 */
	public ProjectOwners(InstantiationContext context, Config<?> config) {
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
		Person creator = TLContext.currentUser();
		if (creator == null) {
			return Modification.NONE;
		}
		List<TLObject> projects = null;
		for (KnowledgeItem item : createdObjects.values()) {
			if (item.getWrapper() instanceof TLObject obj && obj.tValid() && !obj.tTransient() && isProject(obj)
				&& ProjectMembers.accounts(obj, ProjectMembers.OWNERS).isEmpty()) {
				if (projects == null) {
					projects = new ArrayList<>();
				}
				projects.add(obj);
			}
		}
		if (projects == null) {
			return Modification.NONE;
		}
		List<TLObject> unowned = projects;
		return () -> {
			for (TLObject project : unowned) {
				ProjectMembers.ownIfUnowned(project, creator);
			}
		};
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	private static boolean isProject(TLObject obj) {
		TLStructuredType type = obj.tType();
		return type != null && ProjectMembers.PROJECT_TYPE.equals(TLModelUtil.qualifiedName(type));
	}

	/**
	 * Singleton reference for {@link ProjectOwners}.
	 */
	public static final class Module extends TypedRuntimeModule<ProjectOwners> {

		/** The singleton instance. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton constructor.
		}

		@Override
		public Class<ProjectOwners> getImplementation() {
			return ProjectOwners.class;
		}
	}

}
