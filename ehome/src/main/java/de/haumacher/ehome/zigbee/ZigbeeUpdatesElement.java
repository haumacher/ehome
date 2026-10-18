/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.view.UIElement;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.view.ProjectUpdatesElement;

/**
 * Shows its content and, while it is displayed, keeps the Zigbee tab of a project up to date
 * (haumacher/ehome #64): every change of the live state of the project's controller
 * ({@link ZigbeeLive}: an event, a live import done or failed) writes a new value to the
 * <code>refresh</code> channel, at most every {@link #UPDATE_INTERVAL} ms; while the network is open
 * for joining, {@link ZigbeeLive} ticks once a second, so the permit countdown moves.
 *
 * <p>
 * Before the channel is written, the thread's view of the knowledge base is brought up to date: a
 * live import commits on another thread.
 * </p>
 */
public class ZigbeeUpdatesElement extends ProjectUpdatesElement {

	/** The shortest time in milliseconds between two updates of the view. */
	public static final long UPDATE_INTERVAL = 1000;

	/**
	 * Configuration of {@link ZigbeeUpdatesElement}.
	 */
	@TagName("zigbee-updates")
	public interface Config extends ProjectUpdatesElement.Config {

		@Override
		@ClassDefault(ZigbeeUpdatesElement.class)
		Class<? extends UIElement> getImplementationClass();
	}

	/**
	 * Creates a {@link ZigbeeUpdatesElement} from configuration.
	 */
	@CalledByReflection
	public ZigbeeUpdatesElement(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	protected long updateInterval() {
		return UPDATE_INTERVAL;
	}

	@Override
	protected Runnable subscribe(TLObject project, Runnable listener) {
		String controllerId = ControllerConnections.controllerId(Deployer.controller(project));
		if (controllerId == null) {
			return () -> {
				// Nothing live: a controller registered by host, or none.
			};
		}
		ZigbeeLive.addListener(controllerId, listener);
		return () -> ZigbeeLive.removeListener(controllerId, listener);
	}

	@Override
	protected void beforeUpdate() {
		ControllerConnections.refetch();
	}

}
