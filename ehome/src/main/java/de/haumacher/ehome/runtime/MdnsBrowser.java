/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import de.haumacher.ehome.runtime.DnsMessage.DnsException;
import de.haumacher.ehome.runtime.DnsMessage.Question;
import de.haumacher.ehome.runtime.DnsMessage.Record;

/**
 * A one-shot browser for DNS-SD services over multicast DNS (RFC 6762, RFC 6763): asks for the
 * instances of a service type and collects what the responders answer within a time window.
 *
 * <p>
 * The query is sent from an ephemeral port (a "legacy unicast" query, RFC 6762 section 6.7), so the
 * responders answer to the browser directly and nothing needs to listen on port 5353 (where the
 * system's own responder, e.g. Avahi, already listens). An instance whose answer lacks its host and
 * port (<code>SRV</code>), its attributes (<code>TXT</code>) or the address of its host
 * (<code>A</code>) is asked for them in follow-up queries within the window.
 * </p>
 */
public final class MdnsBrowser {

	/** The multicast group and port of multicast DNS over IPv4. */
	public static final InetSocketAddress MDNS_GROUP = new InetSocketAddress(mdnsAddress(), 5353);

	/**
	 * Where a query is sent.
	 *
	 * @param address
	 *        The group (or, for a test, a unicast address) and port.
	 * @param networkInterface
	 *        The interface to send a multicast query on; <code>null</code> for the system's choice.
	 */
	public record Target(InetSocketAddress address, NetworkInterface networkInterface) {
		// Pure data.
	}

	/**
	 * A service instance found.
	 *
	 * @param instance
	 *        The instance name (the first label of the full name): the host name of the controller,
	 *        unique per device, e.g. <code>homecontrol-a1b2c3</code> (firmware before haumacher/homecontrol
	 *        a5db51a announced <code>HomeControl</code> for every device).
	 * @param host
	 *        The host name the instance runs on, e.g. <code>homecontrol-a1b2c3.local</code>.
	 * @param address
	 *        The IPv4 address of the host as text; the address of the responder if the host's address
	 *        was not answered.
	 * @param port
	 *        The port of the service.
	 * @param attributes
	 *        The text attributes, keys in lower case.
	 */
	public record Service(String instance, String host, String address, int port, Map<String, String> attributes) {
		// Pure data.
	}

	private MdnsBrowser() {
		// Static functions only.
	}

	/**
	 * The targets of a search in the local networks: the multicast DNS group on every interface that
	 * is up, supports multicast, is no loopback interface and has an IPv4 address.
	 */
	public static List<Target> localNetworks() throws SocketException {
		List<Target> result = new ArrayList<>();
		for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!networkInterface.isUp() || networkInterface.isLoopback() || !networkInterface.supportsMulticast()) {
				continue;
			}
			boolean ipv4 = Collections.list(networkInterface.getInetAddresses()).stream()
				.anyMatch(Inet4Address.class::isInstance);
			if (ipv4) {
				result.add(new Target(MDNS_GROUP, networkInterface));
			}
		}
		return result;
	}

	/**
	 * Searches the instances of a service type.
	 *
	 * @param serviceType
	 *        The service type with its domain, e.g. <code>_homecontrol._tcp.local</code>.
	 * @param windowMillis
	 *        How long to collect answers.
	 * @param targets
	 *        Where to send the query.
	 * @return The instances found that announced their host and port, in the order of their first
	 *         answer.
	 */
	public static List<Service> browse(String serviceType, long windowMillis, List<Target> targets) throws IOException {
		Collector collector = new Collector(serviceType);
		try (MulticastSocket socket = new MulticastSocket(new InetSocketAddress(0))) {
			socket.setTimeToLive(255);
			int id = ThreadLocalRandom.current().nextInt(1, 0x10000);
			send(socket, targets, new DnsMessage(id, 0, List.of(new Question(serviceType, DnsMessage.PTR, true)), List.of()));
			long start = System.currentTimeMillis();
			long end = start + windowMillis;
			// Follow-up queries for what is missing: the host and attributes of an instance, then the
			// address of its host.
			long[] followUps = { start + windowMillis * 2 / 5, start + windowMillis * 7 / 10 };
			int followUp = 0;
			byte[] buffer = new byte[9000];
			while (true) {
				long now = System.currentTimeMillis();
				if (followUp < followUps.length && now >= followUps[followUp]) {
					followUp++;
					List<Question> missing = collector.missing();
					if (!missing.isEmpty()) {
						send(socket, targets, new DnsMessage(id, 0, missing, List.of()));
					}
					continue;
				}
				long wait = (followUp < followUps.length ? followUps[followUp] : end) - now;
				if (wait <= 0) {
					break;
				}
				socket.setSoTimeout((int) wait);
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				try {
					socket.receive(packet);
				} catch (SocketTimeoutException ex) {
					continue;
				}
				try {
					DnsMessage message = DnsMessage.read(packet.getData(), packet.getLength());
					if (message.isResponse()) {
						collector.add(message, packet.getAddress());
					}
				} catch (DnsException ex) {
					// Not an answer to read; another one may follow.
				}
			}
		}
		return collector.services();
	}

	private static void send(MulticastSocket socket, List<Target> targets, DnsMessage query) throws IOException {
		byte[] bytes = query.write();
		IOException problem = null;
		int sent = 0;
		for (Target target : targets) {
			try {
				if (target.networkInterface() != null) {
					socket.setNetworkInterface(target.networkInterface());
				}
				socket.send(new DatagramPacket(bytes, bytes.length, target.address()));
				sent++;
			} catch (IOException ex) {
				// An interface that cannot send (e.g. without a route to the group); try the others.
				problem = ex;
			}
		}
		if (sent == 0 && problem != null) {
			throw problem;
		}
	}

	/**
	 * Merges the records of all answers.
	 */
	static final class Collector {

		private final String _type;

		/** The instance names (full) by their key, in the order of the first answer. */
		private final Map<String, String> _instances = new LinkedHashMap<>();

		private final Map<String, Record> _srv = new LinkedHashMap<>();

		private final Map<String, Record> _txt = new LinkedHashMap<>();

		private final Map<String, String> _addresses = new LinkedHashMap<>();

		private final Map<String, String> _responders = new LinkedHashMap<>();

		Collector(String type) {
			_type = DnsMessage.key(type);
		}

		void add(DnsMessage message, InetAddress responder) {
			Set<String> instances = new LinkedHashSet<>();
			for (Record record : message.records()) {
				String owner = DnsMessage.key(record.name());
				switch (record.type()) {
					case DnsMessage.PTR:
						if (owner.equals(_type) && record.ttl() > 0) {
							_instances.putIfAbsent(DnsMessage.key(record.target()), record.target());
							instances.add(DnsMessage.key(record.target()));
						}
						break;
					case DnsMessage.SRV:
						_srv.put(owner, record);
						instances.add(owner);
						break;
					case DnsMessage.TXT:
						_txt.put(owner, record);
						break;
					case DnsMessage.A:
						_addresses.putIfAbsent(owner, record.address().getHostAddress());
						break;
					default:
						break;
				}
			}
			for (String instance : instances) {
				_responders.putIfAbsent(instance, responder.getHostAddress());
			}
		}

		/**
		 * The questions for what the known instances did not answer yet.
		 */
		List<Question> missing() {
			List<Question> result = new ArrayList<>();
			for (Map.Entry<String, String> instance : _instances.entrySet()) {
				Record srv = _srv.get(instance.getKey());
				if (srv == null) {
					result.add(new Question(instance.getValue(), DnsMessage.SRV, true));
				} else if (!_addresses.containsKey(DnsMessage.key(srv.target()))) {
					result.add(new Question(srv.target(), DnsMessage.A, true));
				}
				if (!_txt.containsKey(instance.getKey())) {
					result.add(new Question(instance.getValue(), DnsMessage.TXT, true));
				}
			}
			return result;
		}

		List<Service> services() {
			List<Service> result = new ArrayList<>();
			for (Map.Entry<String, String> instance : _instances.entrySet()) {
				Record srv = _srv.get(instance.getKey());
				if (srv == null) {
					continue;
				}
				String full = instance.getValue();
				String suffix = "." + _type;
				String name = DnsMessage.key(full).endsWith(suffix) ? full.substring(0, full.length() - suffix.length()
					- (full.endsWith(".") ? 1 : 0)) : full;
				String host = srv.target().endsWith(".") ? srv.target().substring(0, srv.target().length() - 1)
					: srv.target();
				String address = _addresses.get(DnsMessage.key(host));
				if (address == null) {
					address = _responders.get(instance.getKey());
				}
				Record txt = _txt.get(instance.getKey());
				result.add(new Service(name, host, address, srv.port(), txt == null ? Map.of() : txt.attributes()));
			}
			return result;
		}
	}

	private static InetAddress mdnsAddress() {
		try {
			return InetAddress.getByAddress(new byte[] { (byte) 224, 0, 0, (byte) 251 });
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
