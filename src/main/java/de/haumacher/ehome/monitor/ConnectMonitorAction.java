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
import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Deployer;

/**
 * {@link ViewAction} attaching the group monitor of a project to the bus through the project's
 * gateway (<code>ehome.project:Project#gateway</code>) or, with <code>source="controller"</code>,
 * through the monitor stream of the project's HomeControl controller
 * (<code>ehome.project:Project#controller</code>, {@link ControllerBusLink}), and the view to the
 * monitor.
 *
 * <p>
 * The input is the project. The monitor is the one of the project in the session
 * ({@link MonitorSessions}); one that is still connected to the same gateway or controller is not
 * connected again, one connected to the other source is reconnected. From now on every
 * change of the monitor writes its {@link GroupMonitor#snapshot()} to the configured channel
 * ({@link WindowPublisher}); the result is the current snapshot.
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
		 * The channel receiving the snapshots of the monitor.
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
		if (!(input instanceof TLObject project) || !project.tValid()) {
			return null;
		}
		if (_source == Source.CONTROLLER) {
			return connectController(context, project);
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
		monitor.setPublisher(new WindowPublisher(context, channel, monitor));
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

	private Object connectController(ReactContext context, TLObject project) {
		TLObject controller = Deployer.controller(project);
		if (controller == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CONTROLLER);
		}
		Object name = controller.tValueByName("name");
		ControllerClient client = Deployer.client(controller);
		if (client.token() == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CONTROLLER_TOKEN__CONTROLLER.fill(name));
		}
		String controllerName = String.valueOf(name);
		GroupMonitor monitor = MonitorSessions.monitor(project);
		ViewChannel channel = ((ViewContext) context).resolveChannel(_state);
		monitor.setPublisher(new WindowPublisher(context, channel, monitor));
		if (!monitor.isConnectedTo(ControllerBusLink.description(controllerName, client))) {
			monitor.refresh(ProjectIndex.of(project));
			try {
				monitor.connect(ControllerBusLink.open(controllerName, client));
			} catch (BusException ex) {
				throw new TopLogicException(I18NConstants.ERROR_CONTROLLER_CONNECT_FAILED__CONTROLLER_DETAIL
					.fill(ControllerBusLink.description(controllerName, client), ex.getMessage()), ex);
			}
		}
		Map<String, Object> snapshot = monitor.snapshot();
		channel.set(snapshot);
		return snapshot;
	}

}
