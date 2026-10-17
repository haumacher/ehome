/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual;

import java.io.File;

import com.top_logic.basic.AliasManager;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;

import de.haumacher.ehome.virtual.trace.TraceStore;

/**
 * The service of the virtual device in the application: the one {@link VirtualInterfaceManager},
 * its traces in the application's data directory, and the virtual interface stopped when the
 * application shuts down.
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.virtual.config.xml</code>.
 * </p>
 */
@ServiceDependencies({})
public class VirtualInterfaces extends ConfiguredManagedClass<VirtualInterfaces.Config> {

	/**
	 * Configuration of {@link VirtualInterfaces}.
	 */
	public interface Config extends ConfiguredManagedClass.Config<VirtualInterfaces> {

		/**
		 * The directory of the traces (<code>traces/</code>) and of the files of the KNXnet/IP server
		 * (<code>server/</code>); aliases such as <code>%STORAGE_PATH%</code> are expanded.
		 */
		@Name("directory")
		@StringDefault("%STORAGE_PATH%/virtual-device")
		String getDirectory();
	}

	private VirtualInterfaceManager _manager;

	/**
	 * Creates a {@link VirtualInterfaces} from configuration.
	 */
	public VirtualInterfaces(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		File directory = new File(AliasManager.getInstance().replace(getConfig().getDirectory()));
		_manager = new VirtualInterfaceManager(new TraceStore(new File(directory, "traces")),
			new File(directory, "server"));
	}

	@Override
	protected void shutDown() {
		if (_manager != null) {
			_manager.stop();
			_manager = null;
		}
		super.shutDown();
	}

	/**
	 * The manager of the virtual interface.
	 */
	public VirtualInterfaceManager manager() {
		return _manager;
	}

	/**
	 * The service.
	 */
	public static VirtualInterfaces getInstance() {
		return Module.INSTANCE.getImplementationInstance();
	}

	/**
	 * The module of {@link VirtualInterfaces}.
	 */
	public static final class Module extends TypedRuntimeModule<VirtualInterfaces> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<VirtualInterfaces> getImplementation() {
			return VirtualInterfaces.class;
		}
	}

}
