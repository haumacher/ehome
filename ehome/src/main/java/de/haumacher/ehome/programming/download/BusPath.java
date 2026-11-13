/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * The way the devices of a project are reached on the bus (haumacher/ehome #148): through the
 * project's KNXnet/IP gateway (<code>ehome.project:Project#gateway</code>, a tunnel of E-Home's own)
 * or through the bus session of the project's connected controller
 * (<code>ehome.project:Project#programmingController</code>, {@link BusSessionLink}). The installer
 * chooses it ("Choose the gateway"); the two are exclusive, and nothing falls back from one to the
 * other.
 *
 * <p>
 * Everything reaching the bus for programming opens its link here: the download and the comparison
 * ({@link CalimeroReader}, {@link CalimeroWriter}), the scan and the LED
 * (<code>CalimeroScanBus</code>), the read-back. The programming logic on the link is the same for
 * both paths.
 * </p>
 */
public sealed interface BusPath permits BusPath.Gateway, BusPath.Controller {

	/** The reference of a project to the controller it is programmed through. */
	String PROGRAMMING_CONTROLLER = "programmingController";

	/**
	 * The path for a person, the same in every language: <code>Gateway 192.168.178.81:3671</code> or
	 * <code>Controller homecontrol-f883ec</code>; it fills the "through {n}" of the messages.
	 */
	String text();

	/**
	 * Opens a link to the bus on this path.
	 *
	 * @throws BusException
	 *         If the gateway does not answer.
	 * @throws TopLogicException
	 *         If the path is refused: this installation does not reach local networks (a gateway), the
	 *         controller refuses the bus session.
	 */
	KNXNetworkLink open() throws BusException;

	/**
	 * Whether the path reaches the bus through a controller.
	 */
	default boolean viaController() {
		return this instanceof Controller;
	}

	/**
	 * A path through the given gateway.
	 */
	static BusPath gateway(InetSocketAddress address) {
		return new Gateway(address);
	}

	/**
	 * The path of the project of the given object (a device, or the project itself).
	 *
	 * @throws TopLogicException
	 *         If the path cannot be used ({@link #veto(TLObject)}).
	 */
	static BusPath of(TLObject object) {
		ResKey veto = veto(object);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject project = project(object);
		TLObject controller = controller(project);
		if (controller != null) {
			String name = String.valueOf(controller.tValueByName("name"));
			return new Controller(name, ControllerConnections.get(controller));
		}
		return new Gateway(Gateways.parse(Gateways.of(project)));
	}

	/**
	 * Why the project of the given object cannot reach the bus now, <code>null</code> if it can.
	 *
	 * <p>
	 * Through a controller: it is not one of the project's own, not connected (its last contact), its
	 * firmware has no bus session, or a session through it is open already
	 * ({@link BusSessionLink#veto(TLObject)}); whether the installation reaches local networks does not
	 * matter. Through the gateway: this installation does not reach local networks (haumacher/ehome
	 * #150; the address stays with the project), the project has none, or its address is invalid.
	 * </p>
	 */
	static ResKey veto(TLObject object) {
		if (object == null) {
			return I18NConstants.ERROR_NO_BUS_PATH;
		}
		TLObject project = project(object);
		TLObject controller = controller(project);
		if (controller != null) {
			ResKey foreign = ProjectControllers.foreignVeto(project, controller);
			if (foreign != null) {
				return foreign;
			}
			return BusSessionLink.veto(controller);
		}
		ResKey localNetwork = OperatorSettings.localNetworkVeto();
		if (localNetwork != null) {
			return localNetwork;
		}
		String gateway = Gateways.of(project);
		if (gateway == null) {
			return I18NConstants.ERROR_NO_BUS_PATH;
		}
		if (Gateways.parse(gateway) == null) {
			return I18NConstants.ERROR_BAD_GATEWAY__VALUE.fill(gateway);
		}
		return null;
	}

	/**
	 * The path of the project of the given object as {@link #text()}, without checking whether it
	 * can be used now; <code>null</code> if the project has none.
	 */
	static String describe(TLObject object) {
		if (object == null) {
			return null;
		}
		TLObject project = project(object);
		TLObject controller = controller(project);
		if (controller != null) {
			return Controller.text(String.valueOf(controller.tValueByName("name")));
		}
		String gateway = Gateways.of(project);
		if (gateway == null) {
			return null;
		}
		InetSocketAddress address = Gateways.parse(gateway);
		return Gateway.text(address == null ? gateway : Gateways.format(address));
	}

	/**
	 * The controller the project is programmed through, <code>null</code> for its gateway.
	 */
	static TLObject controller(TLObject project) {
		return project.tValueByName(PROGRAMMING_CONTROLLER) instanceof TLObject controller && controller.tValid()
			? controller : null;
	}

	/**
	 * The project of the given object: its outermost container.
	 */
	static TLObject project(TLObject object) {
		TLObject root = object;
		while (root.tContainer() != null) {
			root = root.tContainer();
		}
		return root;
	}

	/**
	 * The path through a KNXnet/IP gateway: a tunnelling connection of E-Home's own.
	 *
	 * @param address
	 *        The gateway, see {@link Gateways#parse(String)}.
	 */
	record Gateway(InetSocketAddress address) implements BusPath {

		/**
		 * The {@link BusPath#text()} of a gateway with the given address text.
		 */
		static String text(String address) {
			return "Gateway " + address;
		}

		@Override
		public String text() {
			return text(Gateways.format(address));
		}

		/**
		 * {@inheritDoc}
		 *
		 * @throws TopLogicException
		 *         If this installation does not reach local networks
		 *         ({@link OperatorSettings#checkLocalNetwork()}, haumacher/ehome #150).
		 */
		@Override
		public KNXNetworkLink open() throws BusException {
			OperatorSettings.checkLocalNetwork();
			KNXNetworkLink link = null;
			try {
				InetSocketAddress remote = Gateways.resolve(address);
				InetSocketAddress local = new InetSocketAddress(localAddressTowards(remote), 0);
				try (Uninterruptible.Section section = Uninterruptible.interruptible()) {
					link = KNXNetworkLinkIP.newTunnelingLink(local, remote, false, new TPSettings());
				}
				return link;
			} catch (KNXException | IOException ex) {
				throw new BusException("No connection to the gateway " + Gateways.format(address) + ": " + ex.getMessage(), ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new BusException("Interrupted while connecting to " + Gateways.format(address) + ".", ex);
			}
		}

		/**
		 * The address of this machine from which the gateway is reached.
		 */
		public static InetAddress localAddressTowards(InetSocketAddress remote) throws IOException {
			if (remote.isUnresolved()) {
				throw new IOException("unknown host " + remote.getHostString());
			}
			try (DatagramSocket socket = new DatagramSocket()) {
				socket.connect(remote.getAddress(), remote.getPort());
				return socket.getLocalAddress();
			}
		}
	}

	/**
	 * The path through the bus session of a controller connected to E-Home ({@link BusSessionLink}).
	 *
	 * @param name
	 *        The name of the controller.
	 * @param connection
	 *        Its connection, <code>null</code> if it is not connected.
	 */
	record Controller(String name, ControllerConnection connection) implements BusPath {

		/**
		 * The {@link BusPath#text()} of a controller with the given name.
		 */
		static String text(String name) {
			return "Controller " + name;
		}

		@Override
		public String text() {
			return text(name);
		}

		/**
		 * {@inheritDoc}
		 *
		 * <p>
		 * Whether this installation reaches local networks does not matter: the controller connected
		 * to E-Home.
		 * </p>
		 */
		@Override
		public KNXNetworkLink open() {
			return BusSessionLink.open(connection, name);
		}
	}

}
