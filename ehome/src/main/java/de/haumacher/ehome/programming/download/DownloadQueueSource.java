/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.listener.Registration;
import com.top_logic.layout.view.ViewContext;
import com.top_logic.layout.view.channel.ChannelRef;
import com.top_logic.layout.view.channel.ChannelSink;
import com.top_logic.layout.view.channel.ChannelSource;
import com.top_logic.layout.view.channel.ViewChannel;
import com.top_logic.model.TLObject;

/**
 * Source of a <code>&lt;source-channel&gt;</code> that changes whenever the {@link DownloadQueue} of the
 * project of the view changes (haumacher/ehome #218): an entry appended, raised, cancelled, started,
 * progressing, finished or removed, by whatever session or the worker.
 *
 * <p>
 * The queue is runtime state no model event reflects. The table of the queue and its commands name
 * the channel among their inputs, so that the rows and the vetoes follow the worker. Only changes of
 * the queue of the project in the channel {@link Config#getProject()} of the view are published.
 * </p>
 *
 * <p>
 * The value is a number counting the changes; the channel takes it at most as often as its
 * <code>update-interval</code> says.
 * </p>
 */
public class DownloadQueueSource implements ChannelSource {

	/**
	 * Configuration of {@link DownloadQueueSource}.
	 */
	public interface Config extends PolymorphicConfiguration<DownloadQueueSource> {

		@Override
		@ClassDefault(DownloadQueueSource.class)
		Class<? extends DownloadQueueSource> getImplementationClass();

		/**
		 * The channel of the view holding the project (or a device of it) whose queue is followed.
		 */
		@Name("project")
		@StringDefault("project")
		String getProject();
	}

	private final ChannelRef _project;

	/**
	 * Creates a {@link DownloadQueueSource} from configuration.
	 */
	@CalledByReflection
	public DownloadQueueSource(InstantiationContext context, Config config) {
		_project = new ChannelRef(config.getProject());
	}

	@Override
	public Registration subscribe(ViewContext context, ChannelSink sink) {
		ViewChannel project = context.resolveChannel(_project);
		AtomicLong changes = new AtomicLong();
		AtomicReference<Object> key = new AtomicReference<>(key(project.get()));
		Runnable publish = () -> sink.publish(Long.valueOf(changes.incrementAndGet()));
		Registration projectChanges = project.addListener((sender, oldValue, newValue) -> {
			key.set(key(newValue));
			publish.run();
		});
		Consumer<Object> queueChanges = changed -> {
			if (Objects.equals(changed, key.get())) {
				publish.run();
			}
		};
		DownloadQueue.addListener(queueChanges);
		publish.run();
		return Registration.onDispose(() -> {
			DownloadQueue.removeListener(queueChanges);
			projectChanges.dispose();
		});
	}

	private static Object key(Object project) {
		return project instanceof TLObject object && object.tValid() ? DownloadQueue.key(object) : null;
	}

}
