/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.sink;

import net.jcip.annotations.ThreadSafe;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.internal.Factory;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.logging.config.LoggingRouter;
import org.gradle.internal.logging.console.AnsiConsole;
import org.gradle.internal.logging.console.BuildLogLevelFilterRenderer;
import org.gradle.internal.logging.console.BuildStatusRenderer;
import org.gradle.internal.logging.console.ColorMap;
import org.gradle.internal.logging.console.Console;
import org.gradle.internal.logging.console.ConsoleLayoutCalculator;
import org.gradle.internal.logging.console.DefaultColorMap;
import org.gradle.internal.logging.console.DefaultWorkInProgressFormatter;
import org.gradle.internal.logging.console.StyledTextOutputBackedRenderer;
import org.gradle.internal.logging.console.ThrottlingOutputEventListener;
import org.gradle.internal.logging.console.UserInputConsoleRenderer;
import org.gradle.internal.logging.console.UserInputStandardOutputRenderer;
import org.gradle.internal.logging.console.WorkInProgressRenderer;
import org.gradle.internal.logging.dispatch.AsynchronousLogDispatcher;
import org.gradle.internal.logging.events.AddListenerEvent;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.FlushOutputEvent;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.events.RemoveListenerEvent;
import org.gradle.internal.logging.events.RenderableOutputEvent;
import org.gradle.internal.logging.format.PrettyPrefixedLogHeaderFormatter;
import org.gradle.internal.logging.text.StreamBackedStandardOutputListener;
import org.gradle.internal.logging.text.StreamingStyledTextOutput;
import org.gradle.internal.nativeintegration.console.ConsoleMetaData;
import org.gradle.internal.nativeintegration.console.FallbackConsoleMetaData;
import org.gradle.internal.time.Clock;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link OutputEventListener} implementation which renders output events to various
 * destinations. This implementation is thread-safe.
 */
@ThreadSafe
public class OutputEventRenderer implements OutputEventListener, LoggingRouter, Stoppable {
    private final Object lock = new Object();
    private final Clock clock;
    private final AtomicReference<LogLevel> logLevel = new AtomicReference<LogLevel>(LogLevel.LIFECYCLE);
    private final AsynchronousLogDispatcher renderer;
    private final OutputEventTransformer transformer = new OutputEventTransformer(new OutputEventListener() {
        @Override
        public void onOutput(OutputEvent event) {
            renderer.submit(event);
            if (userListenerChain != null) {
                userListenerChain.onOutput(event);
            }
        }
    });

    private ColorMap colourMap;
    private OutputStream originalStdOut;
    private OutputStream originalStdErr;
    private OutputEventListener stdOutListener;
    private OutputEventListener stdErrListener;
    private OutputEventListener console;
    private OutputEventListener userListenerChain;
    private ListenerBroadcast<StandardOutputListener> userStdoutListeners;
    private ListenerBroadcast<StandardOutputListener> userStderrListeners;

    public OutputEventRenderer(final Clock clock) {
        this.clock = clock;
        renderer = new AsynchronousLogDispatcher();
        renderer.start();
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            // Currently only snapshot the console output listener. Should snapshot all output listeners, and cleanup in restore()
            return new SnapshotImpl(logLevel.get(), console);
        }
    }

    @Override
    public void restore(Snapshot state) {
        RemoveListenerEvent removeEvent = null;
        synchronized (lock) {
            SnapshotImpl snapshot = (SnapshotImpl) state;
            if (snapshot.logLevel != logLevel.get()) {
                configure(snapshot.logLevel);
            }

            // TODO - also close console when it is replaced
            if (snapshot.console != console) {
                if (snapshot.console == null) {
                    removeEvent = removeChain(console);
                    console = null;
                } else {
                    throw new UnsupportedOperationException("Cannot restore previous console. This is not implemented yet.");
                }
            }
        }
        if (removeEvent != null) {
            removeEvent.waitUntilHandled();
        }
    }

    private void addChain(OutputEventListener listener) {
        listener.onOutput(new LogLevelChangeEvent(logLevel.get()));
        onOutput(new AddListenerEvent(listener));
    }

    /**
     * Caller must call {@link RemoveListenerEvent#waitUntilHandled()} on the return value, while not holding the lock
     */
    private RemoveListenerEvent removeChain(OutputEventListener listener) {
        RemoveListenerEvent event = new RemoveListenerEvent(listener);
        onOutput(event);
        return event;
    }

    public ColorMap getColourMap() {
        synchronized (lock) {
            if (colourMap == null) {
                colourMap = new DefaultColorMap();
            }
        }
        return colourMap;
    }

    @Override
    public void flush() {
        FlushOutputEvent event = new FlushOutputEvent();
        onOutput(event);
        event.waitUntilHandled();
    }

    public OutputStream getOriginalStdOut() {
        return originalStdOut;
    }

    public OutputStream getOriginalStdErr() {
        return originalStdErr;
    }

    public void attachProcessConsole(ConsoleOutput consoleOutput) {
        ConsoleConfigureAction.execute(this, consoleOutput);
    }

    @Override
    public void attachConsole(OutputStream outputStream, OutputStream errorStream, ConsoleOutput consoleOutput) {
        attachConsole(outputStream, errorStream, consoleOutput, FallbackConsoleMetaData.INSTANCE);
    }

    @Override
    public void attachConsole(OutputStream outputStream, OutputStream errorStream, ConsoleOutput consoleOutput, ConsoleMetaData consoleMetadata) {
        synchronized (lock) {
            StandardOutputListener outputListener = new StreamBackedStandardOutputListener(outputStream);
            StandardOutputListener errorListener = new StreamBackedStandardOutputListener(errorStream);
            if (consoleOutput == ConsoleOutput.Plain) {
                addPlainConsole(outputListener, errorListener, consoleMetadata != null && (consoleMetadata.isStdErr() && consoleMetadata.isStdOut()));
            } else {
                if (consoleMetadata == null) {
                    consoleMetadata = FallbackConsoleMetaData.INSTANCE;
                }
                Console console;
                if (consoleMetadata.isStdOut()) {
                    OutputStreamWriter writer = new OutputStreamWriter(outputStream);
                    console = new AnsiConsole(writer, writer, getColourMap(), consoleMetadata, true);
                } else if (consoleMetadata.isStdErr()) {
                    OutputStreamWriter writer = new OutputStreamWriter(errorStream);
                    console = new AnsiConsole(writer, writer, getColourMap(), consoleMetadata, true);
                } else {
                    console = null;
                }
                addRichConsole(console, consoleMetadata.isStdOut(), consoleMetadata.isStdErr(), outputListener, errorListener, consoleMetadata, consoleOutput == ConsoleOutput.Verbose);
            }
        }
    }

    public void attachSystemOutAndErr() {
        addSystemOutAsLoggingDestination();
        addSystemErrAsLoggingDestination();
    }

    private void addSystemOutAsLoggingDestination() {
        RemoveListenerEvent removeEvent = null;
        synchronized (lock) {
            originalStdOut = System.out;
            if (stdOutListener != null) {
                removeEvent = removeChain(stdOutListener);
            }
            stdOutListener = new LazyListener(new Factory<OutputEventListener>() {
                @Override
                public OutputEventListener create() {
                    return onNonError(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(new StreamBackedStandardOutputListener((Appendable) originalStdOut))));
                }
            });
            addChain(stdOutListener);
        }
        if (removeEvent != null) {
            removeEvent.waitUntilHandled();
        }
    }

    private void addSystemErrAsLoggingDestination() {
        RemoveListenerEvent removeEvent = null;
        synchronized (lock) {
            originalStdErr = System.err;
            if (stdErrListener != null) {
                removeEvent = removeChain(stdErrListener);
            }
            stdErrListener = new LazyListener(new Factory<OutputEventListener>() {
                @Override
                public OutputEventListener create() {
                    return onError(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(new StreamBackedStandardOutputListener((Appendable) originalStdErr))));
                }
            });
            addChain(stdErrListener);
        }
        if (removeEvent != null) {
            removeEvent.waitUntilHandled();
        }
    }

    private void removeSystemOutAsLoggingDestination() {
        RemoveListenerEvent removeEvent = null;
        synchronized (lock) {
            if (stdOutListener != null) {
                removeEvent = removeChain(stdOutListener);
                stdOutListener = null;
            }
        }
        if (removeEvent != null) {
            removeEvent.waitUntilHandled();
        }
    }

    private void removeSystemErrAsLoggingDestination() {
        RemoveListenerEvent removeEvent = null;
        synchronized (lock) {
            if (stdErrListener != null) {
                removeEvent = removeChain(stdErrListener);
                stdErrListener = null;
            }
        }
        if (removeEvent != null) {
            removeEvent.waitUntilHandled();
        }
    }

    public void addOutputEventListener(OutputEventListener listener) {
        synchronized (lock) {
            addChain(listener);
        }
    }

    public void removeOutputEventListener(OutputEventListener listener) {
        RemoveListenerEvent removeEvent;
        synchronized (lock) {
            removeEvent = removeChain(listener);
        }
        removeEvent.waitUntilHandled();
    }

    public OutputEventRenderer addRichConsole(Console console, boolean stdoutAttachedToConsole, boolean stderrAttachedToConsole, ConsoleMetaData consoleMetaData) {
        return addRichConsole(console, stdoutAttachedToConsole, stderrAttachedToConsole, consoleMetaData, false);
    }

    public OutputEventRenderer addRichConsole(Console console, boolean stdoutAttachedToConsole, boolean stderrAttachedToConsole, ConsoleMetaData consoleMetaData, boolean verbose) {
        return addRichConsole(console, stdoutAttachedToConsole, stderrAttachedToConsole, new StreamBackedStandardOutputListener((Appendable) originalStdOut), new StreamBackedStandardOutputListener((Appendable) originalStdErr), consoleMetaData, verbose);
    }

    private OutputEventRenderer addRichConsole(Console console, boolean stdoutAttachedToConsole, boolean stderrAttachedToConsole, StandardOutputListener outputListener, StandardOutputListener errorListener, ConsoleMetaData consoleMetaData, boolean verbose) {
        OutputEventListener consoleChain;
        if (stdoutAttachedToConsole && stderrAttachedToConsole) {
            OutputEventListener consoleListener = new StyledTextOutputBackedRenderer(console.getBuildOutputArea());
            consoleChain = getRichConsoleChain(console, consoleMetaData, verbose, consoleListener);
        } else if (stdoutAttachedToConsole) {
            OutputEventListener stderrChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(errorListener));
            OutputEventListener consoleListener = new ErrorOutputDispatchingListener(stderrChain, new StyledTextOutputBackedRenderer(console.getBuildOutputArea()), false);
            consoleChain = getRichConsoleChain(console, consoleMetaData, verbose, consoleListener);
        } else if (stderrAttachedToConsole) {
            OutputEventListener stdoutChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(outputListener));
            OutputEventListener consoleListener = new ErrorOutputDispatchingListener(new StyledTextOutputBackedRenderer(console.getBuildOutputArea()), stdoutChain, false);
            consoleChain = getRichConsoleChain(console, consoleMetaData, verbose, consoleListener);
        } else {
            consoleChain = getPlainConsoleChain(outputListener, errorListener, false, verbose);
        }

        return addConsoleChain(consoleChain);
    }

    private OutputEventListener getRichConsoleChain(Console console, ConsoleMetaData consoleMetaData, boolean verbose, OutputEventListener consoleListener) {
        return throttled(
            new UserInputConsoleRenderer(
                new BuildStatusRenderer(
                    new WorkInProgressRenderer(
                        new BuildLogLevelFilterRenderer(
                            new GroupingProgressLogEventGenerator(consoleListener, new PrettyPrefixedLogHeaderFormatter(), verbose)
                        ),
                        console.getBuildProgressArea(),
                        new DefaultWorkInProgressFormatter(consoleMetaData),
                        new ConsoleLayoutCalculator(consoleMetaData)
                    ),
                    console.getStatusBar(), console, consoleMetaData),
                console)
        );
    }

    public OutputEventRenderer addPlainConsole(boolean redirectStderr) {
        return addConsoleChain(getPlainConsoleChain(redirectStderr));
    }

    private OutputEventRenderer addPlainConsole(StandardOutputListener outputListener, StandardOutputListener errorListener, boolean redirectStderr) {
        return addConsoleChain(getPlainConsoleChain(outputListener, errorListener, redirectStderr, true));
    }

    private OutputEventListener getPlainConsoleChain(boolean redirectStderr) {
        return getPlainConsoleChain(new StreamBackedStandardOutputListener((Appendable) originalStdOut), new StreamBackedStandardOutputListener((Appendable) originalStdErr), redirectStderr, true);
    }

    private OutputEventListener getPlainConsoleChain(StandardOutputListener outputListener, StandardOutputListener errorListener, boolean redirectStderr, boolean verbose) {
        final OutputEventListener stdoutChain = new UserInputStandardOutputRenderer(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(outputListener)), clock);
        final OutputEventListener stderrChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(errorListener));

        return throttled(
            new BuildLogLevelFilterRenderer(
                new GroupingProgressLogEventGenerator(
                    new ErrorOutputDispatchingListener(stderrChain, stdoutChain, redirectStderr),
                    new PrettyPrefixedLogHeaderFormatter(),
                    verbose
                )
            )
        );
    }

    private OutputEventListener throttled(OutputEventListener consoleChain) {
        return new ThrottlingOutputEventListener(consoleChain, clock);
    }

    private OutputEventRenderer addConsoleChain(OutputEventListener consoleChain) {
        synchronized (lock) {
            this.console = consoleChain;
            removeSystemOutAsLoggingDestination();
            removeSystemErrAsLoggingDestination();
            addChain(this.console);
        }
        return this;
    }

    private OutputEventListener onError(final OutputEventListener listener) {
        return new LogEventDispatcher(null, listener);
    }

    private OutputEventListener onNonError(final OutputEventListener listener) {
        return new LogEventDispatcher(listener, null);
    }

    @Override
    public void enableUserStandardOutputListeners() {
        // Create all of the pipeline eagerly as soon as this is enabled, to track the state of build operations.
        // All of the pipelines do this, so should instead have a single stage that tracks this for all pipelines and that can replay the current state to new pipelines
        // Then, a pipeline can be added for each listener as required
        synchronized (lock) {
            if (userStdoutListeners == null) {
                userStdoutListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
                userStderrListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
                final OutputEventListener stdOutChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(userStdoutListeners.getSource()));
                final OutputEventListener stdErrChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(userStderrListeners.getSource()));
                userListenerChain = new BuildLogLevelFilterRenderer(
                    new ProgressLogEventGenerator(new OutputEventListener() {
                        @Override
                        public void onOutput(OutputEvent event) {
                            // Do not forward events for rendering when there are no listeners to receive
                            if (event instanceof LogLevelChangeEvent) {
                                stdOutChain.onOutput(event);
                                stdErrChain.onOutput(event);
                            } else if (event.getLogLevel() == LogLevel.ERROR && !userStderrListeners.isEmpty() && event instanceof RenderableOutputEvent) {
                                stdErrChain.onOutput(event);
                            } else if (event.getLogLevel() != LogLevel.ERROR && !userStdoutListeners.isEmpty() && event instanceof RenderableOutputEvent) {
                                stdOutChain.onOutput(event);
                            }
                        }
                    })
                );
                userListenerChain.onOutput(new LogLevelChangeEvent(logLevel.get()));
            }
        }
    }

    private void assertUserListenersEnabled() {
        if (userListenerChain == null) {
            throw new IllegalStateException("Custom standard output listeners not enabled.");
        }
        userListenerChain.onOutput(new FlushOutputEvent());
    }

    public void addStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            assertUserListenersEnabled();
            userStderrListeners.add(listener);
        }
    }

    public void addStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            assertUserListenersEnabled();
            userStdoutListeners.add(listener);
        }
    }

    public void addStandardOutputListener(OutputStream outputStream) {
        addStandardOutputListener(new StreamBackedStandardOutputListener(outputStream));
    }

    public void addStandardErrorListener(OutputStream outputStream) {
        addStandardErrorListener(new StreamBackedStandardOutputListener(outputStream));
    }

    public void removeStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            assertUserListenersEnabled();
            userStdoutListeners.remove(listener);
        }
    }

    public void removeStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            assertUserListenersEnabled();
            userStderrListeners.remove(listener);
        }
    }

    public void configure(LogLevel logLevel) {
        onOutput(new LogLevelChangeEvent(logLevel));
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event.getLogLevel() != null && event.getLogLevel().compareTo(logLevel.get()) < 0 && !isProgressEvent(event)) {
            return;
        }
        if (event instanceof LogLevelChangeEvent) {
            LogLevelChangeEvent changeEvent = (LogLevelChangeEvent) event;
            LogLevel newLogLevel = changeEvent.getNewLogLevel();
            if (newLogLevel == this.logLevel.get()) {
                return;
            }
            this.logLevel.set(newLogLevel);
        }

        synchronized (lock) {
            transformer.onOutput(event);
        }
    }

    private boolean isProgressEvent(OutputEvent event) {
        return event instanceof ProgressStartEvent || event instanceof ProgressEvent || event instanceof ProgressCompleteEvent;
    }

    @Override
    public void stop() {
        flush();
        // Do not stop the dispatch thread as this instance may be used after stop is called
        // TODO - Disallow the use of this instance after stop is called and stop the dispatch thread
    }

    private static class SnapshotImpl implements Snapshot {
        private final LogLevel logLevel;
        private final OutputEventListener console;

        SnapshotImpl(LogLevel logLevel, OutputEventListener console) {
            this.logLevel = logLevel;
            this.console = console;
        }
    }

    private static class LazyListener implements OutputEventListener {
        private Factory<OutputEventListener> factory;
        private OutputEventListener delegate;
        private LogLevelChangeEvent pendingLogLevel;

        private LazyListener(Factory<OutputEventListener> factory) {
            this.factory = factory;
        }

        @Override
        public void onOutput(OutputEvent event) {
            if (delegate == null) {
                if (event instanceof EndOutputEvent || event instanceof FlushOutputEvent) {
                    // Ignore
                    return;
                }
                if (event instanceof LogLevelChangeEvent) {
                    // Keep until the listener is created
                    pendingLogLevel = (LogLevelChangeEvent) event;
                    return;
                }
                delegate = factory.create();
                factory = null;
                if (pendingLogLevel != null) {
                    delegate.onOutput(pendingLogLevel);
                    pendingLogLevel = null;
                }
            }
            delegate.onOutput(event);
        }
    }

}
