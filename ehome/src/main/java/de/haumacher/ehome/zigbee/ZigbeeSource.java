/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.view.ProjectSource;

/**
 * Source of the <code>refresh</code> channel of the Zigbee tab of a project (haumacher/ehome #64,
 * <code>projects/zigbee.view.xml</code>): every change of the live state of the project's controller
 * ({@link ZigbeeLive}: an event, a live import done or failed) publishes a new value, from which the
 * view computes what it shows again; while the network is open for joining, {@link ZigbeeLive} ticks
 * once a second, so the permit countdown moves. The channel takes a value at most once a second (its
 * <code>update-interval</code>).
 *
 * <p>
 * A live import commits on another thread; before a change is published, the knowledge base is
 * refetched, so that the view computes from the state the import left.
 * </p>
 */
public class ZigbeeSource extends ProjectSource {

	/**
	 * Configuration of {@link ZigbeeSource}.
	 */
	public interface Config extends ProjectSource.Config<ZigbeeSource> {

		@Override
		@ClassDefault(ZigbeeSource.class)
		Class<? extends ZigbeeSource> getImplementationClass();
	}

	/**
	 * Creates a {@link ZigbeeSource} from configuration.
	 */
	@CalledByReflection
	public ZigbeeSource(InstantiationContext context, Config config) {
		super(config.getProject());
	}

	@Override
	protected Runnable listen(TLObject project, Runnable listener) {
		String controllerId = ControllerConnections.controllerId(Deployer.controller(project));
		if (controllerId == null) {
			return () -> {
				// Nothing live: a controller registered by host, or none.
			};
		}
		Runnable refetching = () -> {
			ControllerConnections.refetch();
			listener.run();
		};
		ZigbeeLive.addListener(controllerId, refetching);
		return () -> ZigbeeLive.removeListener(controllerId, refetching);
	}

}
