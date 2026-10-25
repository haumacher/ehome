/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.net.InetSocketAddress;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.ExternallyNamed;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Format;
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelRefFormat;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.Gateways;

/**
 * {@link ViewAction} attaching the group monitor of a project to the bus through the project's
 * gateway (<code>ehome.project:Project#gateway</code>) or, with <code>source="controller"</code>,
 * through the monitor stream of a HomeControl controller ({@link ControllerBusLink}), and the view
 * to the monitor.
 *
 * <p>
 * The input is the project. With <code>source="controller"</code> it may instead be a dictionary
 * naming the controller (haumacher/ehome #56, {@link ControllerTarget#of}): <code>project</code>,
 * <code>controller</code> (a registered controller), <code>host</code>, <code>port</code> and
 * <code>token</code> (one entered ad hoc, or a token entered for the registered one); a project alone
 * stands for its own controller. The monitor is the one of the project in the session
 * ({@link MonitorSessions}); one that is still connected to the same gateway or controller is not
 * connected again, one connected to the other source is reconnected. The current
 * {@link GroupMonitor#snapshot()} is written to the configured channel and is the result; the later
 * changes reach the channel through its source ({@link MonitorSource}).
 * </p>
 */
public class ConnectMonitorAction implements ViewAction {

	/**
	 * Configuration of {@link ConnectMonitorAction}.
	 */
	@TagName("group-monitor-connect")
	public interface Config extends PolymorphicConfiguration<ConnectMonitorAction> {

		@Override
		@ClassDefault(ConnectMonitorAction.class)
		Class<? extends ConnectMonitorAction> getImplementationClass();

		/**
		 * The channel receiving the snapshot of the connected monitor.
		 */
		@Name("state")
		@Mandatory
		@Format(ChannelRefFormat.class)
		ChannelRef getState();

		/**
		 * What the monitor connects to.
		 */
		@Name("source")
		Source getSource();
	}

	/**
	 * What the monitor connects to.
	 */
	public enum Source implements ExternallyNamed {
		/** The KNXnet/IP gateway of the project. */
		GATEWAY("gateway"),
		/** The monitor stream of the project's HomeControl controller. */
		CONTROLLER("controller");

		private final String _name;

		Source(String name) {
			_name = name;
		}

		@Override
		public String getExternalName() {
			return _name;
		}
	}

	private final ChannelRef _state;

	private final Source _source;

	/**
	 * Creates a {@link ConnectMonitorAction} from configuration.
	 */
	@CalledByReflection
	public ConnectMonitorAction(InstantiationContext context, Config config) {
		_state = config.getState();
		_source = config.getSource();
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		if (_source == Source.CONTROLLER) {
			if (input instanceof Map<?, ?> choice) {
				if (!(choice.get("project") instanceof TLObject project) || !project.tValid()) {
					return null;
				}
				return connectController(context, project, ControllerTarget.of(project, choice.get("controller"),
					choice.get("host"), choice.get("port"), choice.get("token")));
			}
			if (!(input instanceof TLObject project) || !project.tValid()) {
				return null;
			}
			return connectController(context, project, ControllerTarget.of(project, null, null, null, null));
		}
		if (!(input instanceof TLObject project) || !project.tValid()) {
			return null;
		}
		Object gatewayText = project.tValueByName("gateway");
		if (gatewayText == null || gatewayText.toString().isBlank()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_GATEWAY);
		}
		InetSocketAddress gateway = Gateways.parse(gatewayText.toString());
		if (gateway == null) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_GATEWAY__TEXT.fill(gatewayText));
		}

		GroupMonitor monitor = MonitorSessions.monitor(project);
		ViewChannel channel = ((ViewContext) context).resolveChannel(_state);
		if (!monitor.isConnectedTo(Gateways.format(gateway))) {
			monitor.refresh(ProjectIndex.of(project));
			try {
				monitor.connect(CalimeroBusLink.open(gateway));
			} catch (BusException ex) {
				throw new TopLogicException(
					I18NConstants.ERROR_CONNECT_FAILED__GATEWAY_DETAIL.fill(Gateways.format(gateway), ex.getMessage()),
					ex);
			}
		}
		Map<String, Object> snapshot = monitor.snapshot();
		channel.set(snapshot);
		return snapshot;
	}

	private Object connectController(ReactContext context, TLObject project, ControllerTarget target) {
		GroupMonitor monitor = MonitorSessions.monitor(project);
		ViewChannel channel = ((ViewContext) context).resolveChannel(_state);
		target.attach(monitor);
		Map<String, Object> snapshot = monitor.snapshot();
		channel.set(snapshot);
		return snapshot;
	}

}
