/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

/**
 * The most bytes one memory access (<code>A_Memory_Read</code>, <code>A_Memory_Write</code>) carries
 * on the way to a device (haumacher/ehome #196).
 *
 * <p>
 * The longest application unit both the device and the path to it take is the smaller of the
 * device's maximum APDU length (property 56 of the device object, <code>PID_MAX_APDULENGTH</code>)
 * and the path's (the medium of the tunnel to the gateway, or the bus session of the controller). A
 * memory service takes three octets of it (the service with the count, the address), the rest is
 * data: the standard frame on TP (APDU 15) carries {@value #STANDARD} bytes. The count of the memory
 * services has 6 bits: whatever the APDU length, a memory access carries at most {@value #MAX} bytes
 * (the extended memory services, which carry more, are not used).
 * </p>
 */
public final class MemoryChunk {

	/** The maximum APDU length of a standard frame on TP, the one every device takes. */
	public static final int STANDARD_APDU = 15;

	/** The octets of a memory service before its data: the service with the count, the address. */
	public static final int HEADER = 3;

	/** The bytes of a memory access in a standard frame. */
	public static final int STANDARD = STANDARD_APDU - HEADER;

	/** The most bytes the count of a memory service (6 bits) names. */
	public static final int MAX = 63;

	/** The property of the device object holding its maximum APDU length. */
	public static final int PID_MAX_APDU_LENGTH = 56;

	private MemoryChunk() {
		// Static only.
	}

	/**
	 * The bytes of a memory access to a device with the given maximum APDU length on a path with the
	 * given one.
	 *
	 * @param path
	 *        The maximum APDU length of the path to the device.
	 * @param device
	 *        The maximum APDU length of the device.
	 * @return The smaller APDU length less the {@link #HEADER}, at least {@value #STANDARD}, at most
	 *         {@value #MAX}.
	 */
	public static int of(int path, int device) {
		int apdu = Math.min(path, device);
		return Math.min(MAX, Math.max(STANDARD_APDU, apdu) - HEADER);
	}

}
