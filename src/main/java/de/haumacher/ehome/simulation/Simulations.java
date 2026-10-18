/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.AliasManager;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

/**
 * The running simulations of the application: at most one per project, shared by everybody who
 * looks at the project, all stopped when the application shuts down. A simulation is transient:
 * only its parameter overrides are kept with the project ({@link Overrides}).
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.simulation.config.xml</code>.
 * </p>
 */
@ServiceDependencies({})
public class Simulations extends ConfiguredManagedClass<Simulations.Config> {

	/**
	 * Configuration of {@link Simulations}.
	 */
	public interface Config extends ConfiguredManagedClass.Config<Simulations> {

		/**
		 * The directory of the files of the KNXnet/IP servers of the virtual installations; aliases
		 * such as <code>%STORAGE_PATH%</code> are expanded.
		 */
		@Name("directory")
		@StringDefault("%STORAGE_PATH%/simulation")
		String getDirectory();
	}

	private final Map<ObjectKey, ProjectSimulation> _running = new HashMap<>();

	private File _directory;

	/**
	 * Creates a {@link Simulations} from configuration.
	 */
	public Simulations(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		_directory = new File(AliasManager.getInstance().replace(getConfig().getDirectory()));
	}

	@Override
	protected void shutDown() {
		List<ProjectSimulation> running;
		synchronized (this) {
			running = new ArrayList<>(_running.values());
			_running.clear();
		}
		for (ProjectSimulation simulation : running) {
			simulation.close();
		}
		super.shutDown();
	}

	/**
	 * The simulation of the project, <code>null</code> if none runs.
	 */
	public synchronized ProjectSimulation get(TLObject project) {
		return project == null ? null : _running.get(project.tId());
	}

	/**
	 * Starts the simulation of the project.
	 *
	 * @param startUtc
	 *        The instant the simulated clock starts at, in seconds since the epoch.
	 * @throws TopLogicException
	 *         If one runs already, or it cannot be started.
	 */
	public ProjectSimulation start(TLObject project, long startUtc) {
		synchronized (this) {
			if (_running.containsKey(project.tId())) {
				throw new TopLogicException(I18NConstants.ERROR_ALREADY_RUNNING);
			}
		}
		File workDir = new File(_directory, "server-" + project.tId().getObjectName());
		ProjectSimulation result = ProjectSimulation.start(project, startUtc, workDir);
		synchronized (this) {
			if (_running.putIfAbsent(project.tId(), result) != null) {
				result.close();
				throw new TopLogicException(I18NConstants.ERROR_ALREADY_RUNNING);
			}
		}
		return result;
	}

	/**
	 * Stops the simulation of the project; its overrides are written to the project first.
	 *
	 * @throws TopLogicException
	 *         If none runs.
	 */
	public void stop(TLObject project) {
		ProjectSimulation stopped;
		synchronized (this) {
			stopped = _running.remove(project.tId());
		}
		if (stopped == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_RUNNING);
		}
		try {
			stopped.saveOverrides();
		} finally {
			stopped.close();
		}
	}

	/**
	 * The service.
	 */
	public static Simulations getInstance() {
		return Module.INSTANCE.getImplementationInstance();
	}

	/**
	 * The module of {@link Simulations}.
	 */
	public static final class Module extends TypedRuntimeModule<Simulations> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<Simulations> getImplementation() {
			return Simulations.class;
		}
	}

}
