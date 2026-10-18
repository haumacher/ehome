/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import de.haumacher.ehome.runtime.DiscoverControllersAction;
import de.haumacher.ehome.runtime.DnsMessage;
import de.haumacher.ehome.runtime.DnsMessage.DnsException;
import de.haumacher.ehome.runtime.DnsMessage.Question;
import de.haumacher.ehome.runtime.DnsMessage.Record;
import de.haumacher.ehome.runtime.MdnsBrowser;

/**
 * Multicast DNS responders of service instances on one socket, for the tests of the controller
 * discovery (haumacher/ehome #42): joined to the multicast DNS group on the given interface, or on a
 * unicast port of the loopback address if joining fails. A query is answered to its sender.
 *
 * <p>
 * Started from the command line (<code>java -cp target/classes:target/test-classes
 * test.de.haumacher.ehome.runtime.FakeMdnsResponder &lt;port&gt; &lt;controller port&gt;</code>), it
 * announces a controller <code>homecontrol-fake</code> at <code>127.0.0.1</code> on the loopback
 * interface (port 5353: next to the system's responder) until it is stopped, for trying the search
 * in the running application with the interface <code>lo</code>.
 * </p>
 */
public final class FakeMdnsResponder implements AutoCloseable {

	private final MulticastSocket _socket;

	private final InetSocketAddress _target;

	private final List<Announcement> _announcements = new ArrayList<>();

	private int _queries;

	private FakeMdnsResponder(MulticastSocket socket, InetSocketAddress target) {
		_socket = socket;
		_target = target;
		Thread thread = new Thread(this::serve, "fake mdns responder");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Starts a responder on the given interface.
	 *
	 * @param localPort
	 *        The port to listen on, 0 for a free one.
	 */
	public static FakeMdnsResponder start(NetworkInterface networkInterface, int localPort) throws IOException {
		MulticastSocket socket = new MulticastSocket(new InetSocketAddress(localPort));
		int port = socket.getLocalPort();
		try {
			socket.joinGroup(new InetSocketAddress(MdnsBrowser.MDNS_GROUP.getAddress(), port), networkInterface);
			return new FakeMdnsResponder(socket, new InetSocketAddress(MdnsBrowser.MDNS_GROUP.getAddress(), port));
		} catch (SocketException ex) {
			System.err.println("Multicast on the loopback interface is not available (" + ex.getMessage()
				+ "): the discovery is tested over unicast.");
			return new FakeMdnsResponder(socket, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
		}
	}

	/**
	 * Where a query reaches the responder.
	 */
	public InetSocketAddress target() {
		return _target;
	}

	/**
	 * Adds a service instance to answer for.
	 */
	public synchronized void announce(Announcement announcement) {
		_announcements.add(announcement);
	}

	/**
	 * The number of queries received.
	 */
	public synchronized int queries() {
		return _queries;
	}

	@Override
	public void close() {
		_socket.close();
	}

	private void serve() {
		byte[] buffer = new byte[9000];
		while (!_socket.isClosed()) {
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			try {
				_socket.receive(packet);
				DnsMessage query = DnsMessage.read(packet.getData(), packet.getLength());
				if (query.isResponse()) {
					continue;
				}
				List<Record> answers = answer(query);
				if (!answers.isEmpty()) {
					byte[] bytes = new DnsMessage(query.id(), DnsMessage.FLAGS_RESPONSE, query.questions(), answers)
						.write();
					_socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
				}
			} catch (IOException | DnsException ex) {
				// Closed, or no query.
			}
		}
	}

	private synchronized List<Record> answer(DnsMessage query) throws IOException {
		_queries++;
		List<Record> result = new ArrayList<>();
		for (Question question : query.questions()) {
			String name = DnsMessage.key(question.name());
			for (Announcement announcement : _announcements) {
				String full = DnsMessage.key(announcement.fullName());
				if (question.type() == DnsMessage.PTR && name.equals(DnsMessage.key(announcement.type()))) {
					result.add(Record.ptr(announcement.type(), 4500, announcement.fullName()));
					if (announcement.complete()) {
						result.add(srv(announcement));
						result.add(Record.txt(announcement.fullName(), 4500, announcement.txt()));
						result.add(address(announcement));
					}
				} else if (question.type() == DnsMessage.SRV && name.equals(full)) {
					result.add(srv(announcement));
				} else if (question.type() == DnsMessage.TXT && name.equals(full)) {
					result.add(Record.txt(announcement.fullName(), 4500, announcement.txt()));
				} else if (question.type() == DnsMessage.A && name.equals(DnsMessage.key(announcement.host()))) {
					result.add(address(announcement));
				}
			}
		}
		return result;
	}

	private static Record srv(Announcement announcement) {
		return Record.srv(announcement.fullName(), 120, announcement.host(), announcement.port());
	}

	private static Record address(Announcement announcement) throws IOException {
		return Record.address(announcement.host(), 120, InetAddress.getByName(announcement.address()));
	}

	/**
	 * A service instance a responder answers for.
	 *
	 * @param complete
	 *        Whether the answer to the question for the instances carries the host, attributes and
	 *        address too; otherwise they are answered only when asked for.
	 */
	public record Announcement(String instance, String type, String host, String address, int port, List<String> txt,
			boolean complete) {

		String fullName() {
			return instance + "." + type;
		}
	}

	/**
	 * Announces a controller on the loopback interface until the process is stopped.
	 *
	 * @param args
	 *        The port to listen on (5353) and the port of the controller's web API.
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
		FakeMdnsResponder responder = start(loopback, Integer.parseInt(args[0]));
		responder.announce(new Announcement("HomeControl", DiscoverControllersAction.SERVICE_TYPE,
			"homecontrol-fake.local", "127.0.0.1", Integer.parseInt(args[1]), List.of("version=0.0.0-fake", "build=fake"),
			true));
		System.out.println("Announcing homecontrol-fake on " + responder.target());
		Thread.currentThread().join();
	}

}
