/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.mgmt.Destination;
import tuwien.auto.calimero.mgmt.ManagementClientImpl;

/**
 * Read-only tool taking the memory read-back of a device of the user's KNX test system: the
 * reference for the download image (haumacher/ehome #6).
 *
 * <p>
 * Usage: <code>ReadDeviceMemory &lt;name&gt; &lt;individual address&gt; [&lt;start&gt;:&lt;length&gt; | p:&lt;object&gt;:&lt;property&gt; ...]</code>,
 * start and length hexadecimal, object index and property id decimal, e.g.
 * <code>ReadDeviceMemory DI-2 1.3.5 4000:201 4201:1ff p:2:7</code>. Writes the device descriptor, the
 * first element of each property (<code>&lt;name&gt;-&lt;address&gt;-properties.txt</code>) and one hex dump
 * per range (<code>&lt;name&gt;-&lt;address&gt;-&lt;start&gt;.hex</code>) into
 * <code>src/test/fixtures/readback/</code>.
 * </p>
 *
 * <p>
 * The tool is strictly read-only: it only reads the device descriptor, memory and properties. It connects to the
 * one gateway of the test system ({@link #GATEWAY}), never to a discovered one: another reachable
 * router is the user's home installation.
 * </p>
 */
public class ReadDeviceMemory {

	/** The KNXnet/IP router of the test system (MAC 00-24-6D-01-58-64), programmed from EHomeDemo.knxproj. */
	public static final InetSocketAddress GATEWAY = new InetSocketAddress("192.168.178.81", 3671);

	/** The most bytes a memory read returns in a standard frame. */
	private static final int CHUNK = 12;

	private static final File OUT = new File("src/test/fixtures/readback");

	/**
	 * Entry point, see the class comment.
	 */
	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: ReadDeviceMemory <name> <individual address> [<start>:<length> | p:<object>:<property> ...]");
			System.exit(1);
		}
		String name = args[0];
		IndividualAddress device = new IndividualAddress(args[1]);
		List<int[]> ranges = new ArrayList<>();
		List<int[]> properties = new ArrayList<>();
		for (int n = 2; n < args.length; n++) {
			String[] parts = args[n].split(":");
			if (parts[0].equals("p")) {
				properties.add(new int[] { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) });
			} else {
				ranges.add(new int[] { Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16) });
			}
		}
		OUT.mkdirs();

		InetSocketAddress local = new InetSocketAddress(localAddressTowards(GATEWAY.getAddress()), 0);
		System.out.println("Connecting " + local + " -> " + GATEWAY);
		KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(local, GATEWAY, false, new TPSettings());
		try {
			ManagementClientImpl management = new ManagementClientImpl(link);
			try {
				Destination destination = management.createDestination(device, true);
				try {
					byte[] descriptor = management.readDeviceDesc(destination, 0);
					String fileBase = name + "-" + device;
					System.out.println(fileBase + " descriptor " + HexFormat.of().formatHex(descriptor));
					try (PrintWriter out = writer(new File(OUT, fileBase + "-descriptor.hex"))) {
						header(out, name, device, "device descriptor type 0");
						out.println(HexFormat.of().formatHex(descriptor));
					}
					if (!properties.isEmpty()) {
						readProperties(management, destination, fileBase, name, device, properties);
					}
					for (int[] range : ranges) {
						readRange(management, destination, fileBase, name, device, range[0], range[1]);
					}
				} finally {
					destination.destroy();
				}
			} finally {
				management.detach();
			}
		} finally {
			link.close();
		}
	}

	private static void readRange(ManagementClientImpl management, Destination destination, String fileBase, String name,
			IndividualAddress device, int start, int length) throws InterruptedException, IOException {
		byte[] memory = new byte[length];
		int read = 0;
		String problem = null;
		int chunkSize = CHUNK;
		while (read < length) {
			int chunk = Math.min(chunkSize, length - read);
			try {
				byte[] bytes = management.readMemory(destination, start + read, chunk);
				System.arraycopy(bytes, 0, memory, read, bytes.length);
				read += bytes.length;
			} catch (KNXException ex) {
				if (chunk > 1) {
					// The readable memory may end within the chunk: read on byte by byte to its end.
					chunkSize = 1;
					continue;
				}
				problem = String.format("read of %04X failed: %s", start + read, ex.getMessage());
				break;
			}
		}
		File file = new File(OUT, String.format("%s-%04X.hex", fileBase, start));
		try (PrintWriter out = writer(file)) {
			header(out, name, device, String.format("memory %04X, %d bytes", start, length));
			if (problem != null) {
				out.println("# " + problem);
			}
			for (int offset = 0; offset < read; offset += 16) {
				StringBuilder line = new StringBuilder(String.format("%04X:", start + offset));
				for (int n = offset; n < Math.min(read, offset + 16); n++) {
					line.append(' ').append(String.format("%02X", memory[n] & 0xFF));
				}
				out.println(line);
			}
		}
		System.out.println(file + ": " + read + " of " + length + " bytes" + (problem == null ? "" : ", " + problem));
	}

	private static void readProperties(ManagementClientImpl management, Destination destination, String fileBase,
			String name, IndividualAddress device, List<int[]> properties) throws InterruptedException, IOException {
		File file = new File(OUT, fileBase + "-properties.txt");
		try (PrintWriter out = writer(file)) {
			header(out, name, device, "properties (object index, property id: first element)");
			for (int[] property : properties) {
				String value;
				try {
					value = HexFormat.of().formatHex(management.readProperty(destination, property[0], property[1], 1, 1));
				} catch (KNXException ex) {
					value = "# " + ex.getMessage();
				}
				String line = property[0] + " " + property[1] + ": " + value;
				out.println(line);
				System.out.println(line);
			}
		}
	}

	private static void header(PrintWriter out, String name, IndividualAddress device, String what) {
		out.println("# Read-back of " + name + " (" + device + ") on the test system (gateway " + GATEWAY.getHostString() + "), "
			+ what + ". Taken " + LocalDate.now() + " with " + ReadDeviceMemory.class.getSimpleName() + ".");
	}

	private static PrintWriter writer(File file) throws IOException {
		return new PrintWriter(file, StandardCharsets.UTF_8);
	}

	private static InetAddress localAddressTowards(InetAddress remote) throws IOException {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(remote, GATEWAY.getPort());
			return socket.getLocalAddress();
		}
	}

}
