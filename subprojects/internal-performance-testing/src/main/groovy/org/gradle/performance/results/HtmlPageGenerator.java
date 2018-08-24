/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.performance.results;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.MinMaxPriorityQueue;
import com.googlecode.jatl.Html;
import org.gradle.api.Transformer;
import org.gradle.performance.measure.Amount;
import org.gradle.performance.measure.DataSeries;
import org.gradle.performance.measure.Duration;
import org.gradle.performance.util.Git;
import org.gradle.reporting.ReportRenderer;
import org.gradle.util.GradleVersion;

import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.gradle.performance.measure.DataSeries.*;
import static org.gradle.performance.results.PrettyCalculator.*;
import static org.gradle.performance.results.PrettyCalculator.percentageString;

public abstract class HtmlPageGenerator<T> extends ReportRenderer<T, Writer> {
    protected final FormatSupport format = new FormatSupport();

    protected int getDepth() {
        return 0;
    }

    protected void headSection(Html html) {
        String rootDir = getDepth() == 0 ? "" : "../";
        html.meta()
            .httpEquiv("Content-Type")
            .content("text/html; charset=utf-8");
        html.link()
            .rel("stylesheet")
            .type("text/css")
            .href(rootDir + "css/style.css")
            .end();
        html.script()
            .src(rootDir + "js/jquery.min-1.11.0.js")
            .end();
        html.script()
            .src(rootDir + "js/flot-0.8.1-min.js")
            .end();
        html.script()
            .src(rootDir + "js/flot.selection.min.js")
            .end();
        html.script()
            .src(rootDir + "js/report.js")
            .end();
        html.script()
            .src(rootDir + "js/performanceGraph.js")
            .end();
    }

    protected void footer(Html html) {
        html.div()
            .id("footer")
            .text(String.format("Generated at %s by %s", format.executionTimestamp(), GradleVersion.current()))
            .end();
    }

    public static class NavigationItem {
        private final String text;
        private final String link;

        public NavigationItem(String text, String link) {
            this.text = text;
            this.link = link;
        }

        public String getLink() {
            return link;
        }

        public String getText() {
            return text;
        }
    }

    protected class MetricsHtml extends Html {
        public MetricsHtml(Writer writer) {
            super(writer);
        }

        protected void textCell(Object obj) {
            td();
            if (obj != null) {
                text(obj.toString());
            }
            end();
        }

        protected void textCell(Boolean obj) {
            td();
            if (obj != null) {
                text(obj ? "yes" : "no");
            }
            end();
        }

        protected void textCell(Collection<?> obj) {
            td();
            if (obj != null) {
                if (obj.isEmpty()) {
                    span().classAttr("empty").text("-").end();
                } else {
                    text(Joiner.on(" ").join(obj));
                }
            }
            end();
        }

        protected Html nav() {
            return start("nav");
        }


        protected void navigation(List<NavigationItem> navigationItems) {
            nav().id("navigation");
            ul();
            for (NavigationItem navigationItem : navigationItems) {
                li().a().href(navigationItem.getLink()).text(navigationItem.getText()).end().end();
            }
            end();
            end();
        }

        protected int getColumnsForSamples() {
            return 2;
        }

        protected void renderDateAndBranch(PerformanceTestExecution execution) {
            textCell(format.timestamp(new Date(execution.getStartTime())));
            textCell(execution.getVcsBranch());
        }

        protected void renderHeaderForSamples(String label) {
            th().colspan(String.valueOf(getColumnsForSamples())).text(label).end();
        }

        protected void renderSamplesForExperiment(PerformanceTestExecution execution, Transformer<DataSeries<Duration>, MeasuredOperationList> transformer) {
            renderSamplesForExperiment(extractExperimentData(execution, transformer));
        }

        protected void renderSamplesForExperiment(ExperimentData experiments) {
            for (DataSeries<Duration> data : experiments.experimentData) {
                if (data == null) {
                    td().text("").end();
                    td().text("").end();
                } else {
                    Amount<Duration> value = data.getMedian();
                    Amount<Duration> se = data.getStandardError();
                    String classAttr = "numeric";
                    if (value.equals(experiments.experimentWithMinMedian)) {
                        classAttr += " min-value";
                    }
                    if (value.equals(experiments.experimentWithMaxMedian)) {
                        classAttr += " max-value";
                    }
                    td()
                        .classAttr(classAttr)
                        .title("median: " + value + ", min: " + data.getMin() + ", max: " + data.getMax() + ", se: " + se + ", values: " + data)
                        .text(value.format())
                        .end();
                    td()
                        .classAttr("numeric more-detail")
                        .text("se: " + se.format())
                        .end();
                }
            }

            if (experiments.regressionPercentage != null) {
                td()
                    .classAttr("numeric")
                    .text(percentageString(experiments.regressionPercentage))
                    .end();
                td()
                    .classAttr("numeric more-detail")
                    .text(percentageString(experiments.confidencePercentage))
                    .end();
            } else {
                td().text("").end();
                td().text("").end();
            }
        }
    }

    protected static class ExperimentData {
        protected PerformanceTestExecution execution;
        protected List<DataSeries<Duration>> experimentData;
        protected Amount<Duration> experimentWithMaxMedian;
        protected Amount<Duration> experimentWithMinMedian;
        protected Integer regressionPercentage;
        protected Integer confidencePercentage;
        protected Boolean slower;

        protected ExperimentData(PerformanceTestExecution execution, List<DataSeries<Duration>> experimentData, Amount<Duration> experimentWithMaxMedian, Amount<Duration> experimentWithMinMedian) {
            this.experimentData = experimentData;
            this.experimentWithMaxMedian = experimentWithMaxMedian;
            this.experimentWithMinMedian = experimentWithMinMedian;
            determineRegression();
        }

        private void determineRegression() {
            if(experimentData.isEmpty()) {
                return;
            }
            Optional<DataSeries<Duration>> experimentalGroup = Lists.reverse(experimentData).stream().filter(Objects::nonNull).findFirst();
            Optional<DataSeries<Duration>> controlGroup = experimentData.stream().filter(Objects::nonNull).findFirst();
            if(experimentalGroup.isPresent() && controlGroup.isPresent()) {
                regressionPercentage = percentChange(experimentalGroup.get().getAverage(), controlGroup.get().getAverage()).intValue();
                confidencePercentage = percentage(confidenceInDifference(experimentalGroup.get(), controlGroup.get()));
                slower = experimentalGroup.get().significantlyGreaterThan(controlGroup.get());
            }
        }

        protected boolean isSlower() {
            return Boolean.FALSE.equals(slower);
        }
    }

    protected ExperimentData extractExperimentData(PerformanceTestExecution execution, Transformer<DataSeries<Duration>, MeasuredOperationList> transformer) {
        MinMaxPriorityQueue<Amount> minMaxCalculator = MinMaxPriorityQueue.create();
        List<DataSeries<Duration>> experimentData = new ArrayList<>();

        execution.getScenarios().stream().map(transformer::transform).forEach(data -> {
            if (data.isEmpty()) {
                experimentData.add(null);
            } else {
                experimentData.add(data);
                minMaxCalculator.add(data.getMedian());

            }
        });
        return new ExperimentData(execution, experimentData, minMaxCalculator.peekFirst(), minMaxCalculator.peekLast());
    }

    protected List<? extends PerformanceTestExecution> filterForRequestedCommit(List<? extends PerformanceTestExecution> results) {
        String commitId = Git.current().getCommitId();
        if (commitId == null) {
            return results;
        }
        for (PerformanceTestExecution execution : results) {
            if (execution.getVcsCommits().contains(commitId)) {
                return results;
            }
        }
        return Collections.emptyList();
    }

    protected String urlEncode(String str) {
        try {
            return URLEncoder.encode(str, Charsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
