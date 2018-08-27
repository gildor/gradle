/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskStateInternal;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A task executer that cancels the build and marks the task as failed if it exceeds its timeout.
 */
public class TimeoutTaskExecuter implements TaskExecuter {
    private final TaskExecuter delegate;
    private final ScheduledExecutorService executor;

    public TimeoutTaskExecuter(TaskExecuter delegate, ScheduledExecutorService executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public void execute(TaskInternal task, TaskStateInternal state, TaskExecutionContext context) {
        CancelBuildOnTimeout cancelOnTimeout = new CancelBuildOnTimeout(task, Thread.currentThread());
        ScheduledFuture<?> periodicCheck = executor.scheduleAtFixedRate(cancelOnTimeout, 0, 100, TimeUnit.MILLISECONDS);
        try {
            delegate.execute(task, state, context);
        } finally {
            if (cancelOnTimeout.timedOut) {
                state.setAborted(task + " exceeded its timeout");
            }
            periodicCheck.cancel(true);
            Thread.interrupted();
        }
    }

    private class CancelBuildOnTimeout implements Runnable {
        private final long start;
        private final long timeout;
        private final Thread thread;

        private volatile boolean timedOut;

        private CancelBuildOnTimeout(TaskInternal task, Thread thread) {
            this.thread = thread;
            this.start = System.currentTimeMillis();
            this.timeout = task.getTimeoutInMillis();
        }

        @Override
        public void run() {
            if (timedOut) {
                return;
            }
            if (runtime() > timeout) {
                timedOut = true;
                thread.interrupt();
            }
        }

        private long runtime() {
            return System.currentTimeMillis() - start;
        }
    }
}
