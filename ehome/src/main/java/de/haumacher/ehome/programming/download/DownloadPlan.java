/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.top_logic.basic.util.ResKey;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * What a download into a device will do, computed from the project data alone
 * ({@link DownloadPlanner}): the ordered {@link Operation operations}, or the reasons why the
 * download is refused.
 *
 * <p>
 * The plan is what the user sees before confirming a download, and exactly what the
 * {@link DownloadExecutor} performs.
 * </p>
 */
public class DownloadPlan {

	/**
	 * What a download writes into the device.
	 */
	public enum Kind {
		/** The application with its parameters and the group address tables (ETS: application program). */
		FULL,

		/** The individual address only, into the device in programming mode. */
		ADDRESS;
	}

	private final String _device;

	private final Integer _individualAddress;

	private final Kind _kind;

	private final ResKey _procedure;

	private final DownloadImage _image;

	private final List<Block> _unloaded;

	private final List<Operation> _operations;

	private final List<ResKey> _problems;

	private final long _revision;

	DownloadPlan(long revision, String device, Integer individualAddress, Kind kind, ResKey procedure,
			DownloadImage image, List<Block> unloaded, List<Operation> operations, List<ResKey> problems) {
		_revision = revision;
		_unloaded = Collections.unmodifiableList(unloaded);
		_device = device;
		_individualAddress = individualAddress;
		_kind = kind;
		_procedure = procedure;
		_image = image;
		_operations = Collections.unmodifiableList(operations);
		_problems = Collections.unmodifiableList(problems);
	}

	/**
	 * The commit number of the revision of the project data the plan was computed from: the state of
	 * the device the download brings into the real device.
	 */
	public long getRevision() {
		return _revision;
	}

	/**
	 * The device, for a person: its individual address and ID.
	 */
	public String getDevice() {
		return _device;
	}

	/**
	 * The individual address of the device (area, line, device in 4, 4 and 8 bits),
	 * <code>null</code> if it is incomplete.
	 */
	public Integer getIndividualAddress() {
		return _individualAddress;
	}

	/**
	 * What the download writes.
	 */
	public Kind getKind() {
		return _kind;
	}

	/**
	 * Whose load procedure the plan follows, for a person; <code>null</code> for a download of the
	 * address or a plan refused before choosing a procedure.
	 */
	public ResKey getProcedure() {
		return _procedure;
	}

	/**
	 * The download image the plan writes, <code>null</code> for a download of the address.
	 */
	public DownloadImage getImage() {
		return _image;
	}

	/**
	 * The blocks of the image the procedure does not write: they lie in segments whose allocation
	 * lacks the flag 0x80 (ETS leaves them as they are; a comparison skips them).
	 */
	public List<Block> getUnloadedBlocks() {
		return _unloaded;
	}

	/**
	 * The operations in the order they are performed; meaningless if the plan has
	 * {@link #getProblems() problems}.
	 */
	public List<Operation> getOperations() {
		return _operations;
	}

	/**
	 * Why the download is refused; empty if it can be performed.
	 */
	public List<ResKey> getProblems() {
		return _problems;
	}

	/**
	 * Whether the download can be performed.
	 */
	public boolean isExecutable() {
		return _problems.isEmpty();
	}

	/**
	 * The number of bytes the plan writes into memory and properties.
	 */
	public int bytesWritten() {
		return _operations.stream().mapToInt(Operation::bytesWritten).sum();
	}

	/**
	 * The plan as text, one line per operation, for a person and for tests.
	 */
	public String dump() {
		StringBuilder out = new StringBuilder();
		out.append("Download plan (").append(_kind).append(") of ").append(_device).append('\n');
		if (_procedure != null) {
			out.append("Procedure: ").append(english(_procedure)).append('\n');
		}
		int number = 1;
		for (Operation operation : _operations) {
			out.append(number++).append(". ");
			if (!operation.step().isEmpty()) {
				out.append('[').append(operation.step()).append("] ");
			}
			out.append(operation.describe()).append('\n');
		}
		for (Block block : _unloaded) {
			out.append("Not loaded: ").append(block.name()).append(String.format(" %04X-%04X", block.address(), block.end() - 1))
				.append('\n');
		}
		for (ResKey problem : _problems) {
			out.append("Problem: ").append(english(problem)).append('\n');
		}
		return out.toString();
	}

	/**
	 * The English text of a message.
	 */
	public static String english(ResKey message) {
		return Resources.getInstance(Locale.ENGLISH).getString(message);
	}

	@Override
	public String toString() {
		return dump();
	}

}
