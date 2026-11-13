/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.listener.Registration;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.layout.view.channel.ChannelSource;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;

/**
 * Source of a <code>&lt;source-channel&gt;</code> that changes whenever the state deciding whether a
 * path to the bus can be used changes without a model change (haumacher/ehome #161): a bus session
 * through a controller opens or ends ({@link BusSessionLink#addSessionListener(Runnable)}), a
 * controller connects or disconnects, or a controller's status changes what it offers (whether it has
 * reported its status, whether its firmware has the bus session).
 *
 * <p>
 * The vetoes of the commands using the bus ({@link BusPath#veto(com.top_logic.model.TLObject)}) read
 * this runtime state, which no model event reflects. A command naming the channel among its
 * <code>inputs</code> is re-evaluated whenever the channel changes, so that a command disabled
 * because a job held the controller's bus session is enabled again when the job ends, also when the
 * job ran in another view or another session.
 * </p>
 *
 * <p>
 * The value is a number counting the changes; the channel takes it at most as often as its
 * <code>update-interval</code> says.
 * </p>
 */
public class BusStateSource implements ChannelSource {

	/**
	 * Configuration of {@link BusStateSource}.
	 */
	public interface Config extends PolymorphicConfiguration<BusStateSource> {

		@Override
		@ClassDefault(BusStateSource.class)
		Class<? extends BusStateSource> getImplementationClass();
	}

	/**
	 * Creates a {@link BusStateSource} from configuration.
	 */
	@CalledByReflection
	public BusStateSource(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Registration subscribe(ViewContext context, ChannelSink sink) {
		AtomicLong changes = new AtomicLong();
		Runnable publish = () -> sink.publish(Long.valueOf(changes.incrementAndGet()));
		ControllerConnections.Listener connections = new ControllerConnections.Listener() {

			/** What the status of each controller offers, by its id, see {@link #offers(ControllerConnection)}. */
			private final Map<String, String> _offers = new ConcurrentHashMap<>();

			@Override
			public void connected(ControllerConnection connection) {
				_offers.put(String.valueOf(connection.controllerId()), offers(connection));
				publish.run();
			}

			@Override
			public void message(ControllerConnection connection, String type, Map<String, Object> message) {
				if (!"status".equals(type)) {
					return;
				}
				String offers = offers(connection);
				if (!offers.equals(_offers.put(String.valueOf(connection.controllerId()), offers))) {
					publish.run();
				}
			}

			@Override
			public void disconnected(ControllerConnection connection, String reason) {
				_offers.remove(String.valueOf(connection.controllerId()));
				publish.run();
			}
		};
		BusSessionLink.addSessionListener(publish);
		ControllerConnections.addListener(connections);
		publish.run();
		return Registration.onDispose(() -> {
			ControllerConnections.removeListener(connections);
			BusSessionLink.removeSessionListener(publish);
		});
	}

	/**
	 * What the status of the controller offers for a bus session: whether it reported its status
	 * and whether its firmware has the bus session.
	 */
	static String offers(ControllerConnection connection) {
		return connection.status().isEmpty() + "/" + BusSessionLink.capable(connection);
	}

}
