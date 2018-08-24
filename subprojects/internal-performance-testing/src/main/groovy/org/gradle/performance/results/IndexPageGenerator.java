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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

public class IndexPageGenerator extends HtmlPageGenerator<ResultsStore> {
    private final List<NavigationItem> navigationItems;
    private final Map<String, ScenarioBuildResultData> scenarioBuildResultData;

    public IndexPageGenerator(List<NavigationItem> navigationItems, File resultJson) {
        this.navigationItems = navigationItems;
        this.scenarioBuildResultData = readBuildResultData(resultJson);
    }

    private Map<String, ScenarioBuildResultData> readBuildResultData(File resultJson) {
        try {
            List<ScenarioBuildResultData> list = new ObjectMapper().readValue(resultJson, new TypeReference<List<ScenarioBuildResultData>>() { });
            return list.stream().collect(toMap(ScenarioBuildResultData::getName, Function.identity()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void render(final ResultsStore store, Writer writer) throws IOException {
        new MetricsHtml(writer) {
            {
                html();
                    head();
                        headSection(this);
                        title().text("Profile report for channel " + ResultsStoreHelper.determineChannel()).end();
                    end();
                    body();

                    navigation(navigationItems);

                    div().id("content");
                        div().id("controls").end();
                        renderAllScenarios();
                    end();
                    footer(this);
                endAll();
            }

            private void renderAllScenarios() {
                TreeSet<PerformanceTestScenario> results = sortTestResults(store);
                renderSummary(results);
                results.forEach(this::renderScenario);
            }

            private void renderSummary(TreeSet<PerformanceTestScenario> results) {
                long successCount = scenarioBuildResultData.values().stream().filter(ScenarioBuildResultData::isSuccessful).count();
                long otherCount = results.size() - successCount;
                h2().text("" + otherCount + " failed scenarios").end();
                h2().text("" + successCount + " successful scenarios").end();
            }

            private void renderScenario(PerformanceTestScenario scenario) {
                if (scenario.isArchived()) {
                    renderArchivedScenario(scenario);
                } else {
                    renderActiveScenario(scenario);
                }
            }

            private void renderArchivedScenario(PerformanceTestScenario scenario) {
                String url = "tests/" + urlEncode(scenario.history.getId()) + ".html";
                h2().a().href(url).text("Archived test:" + scenario.history.getDisplayName()).end().end();
            }

            private String getTestDescription(PerformanceTestScenario scenario) {
                return scenario.isSuccessful() ? "Test: " : "Failed test: ";
            }

            private void renderActiveScenario(PerformanceTestScenario scenario) {
                h2().classAttr("test-execution");
                    a().href(scenario.buildResultData.getWebUrl()).text(getTestDescription(scenario) + scenario).end();
                end();
                table().classAttr("history");
                tr().classAttr("control-groups");
                    th().colspan("2").end();
                    th().colspan(String.valueOf(scenario.history.getScenarioCount() * getColumnsForSamples())).text("Average execution time").end();
                end();
                tr();
                    th().text("Date").end();
                    th().text("Branch").end();
                    scenario.history.getScenarioLabels().forEach(this::renderHeaderForSamples);
                    th().text("Regression").end();
                end();
                for (ExperimentData experiment: scenario.experiments) {
                    tr();
                        renderDateAndBranch(experiment.execution);
                        renderSamplesForExperiment(experiment);
                    end();
                }
                end();
                div().classAttr("details");
                    a().href("tests/" + urlEncode(scenario.history.getId()) + ".html").text("details...").end();
                end();
            }
        };
    }

    private TreeSet<PerformanceTestScenario> sortTestResults(ResultsStore store) {
        Comparator<PerformanceTestScenario> comparator = Comparator
            .comparing(PerformanceTestScenario::isArchived)
            .thenComparing(PerformanceTestScenario::isSuccessful)
            .thenComparing(PerformanceTestScenario::getRegressionPercentage);

        return store.getTestNames().stream().map(scenarioName -> {
            PerformanceTestHistory testHistory = store.getTestResults(scenarioName, 5, 14, ResultsStoreHelper.determineChannel());

            return new PerformanceTestScenario(testHistory, scenarioBuildResultData.get(scenarioName));
        }).collect(() -> new TreeSet<>(comparator), TreeSet::add, TreeSet::addAll);
    }

    private class PerformanceTestScenario {
        private PerformanceTestHistory history;
        private List<ExperimentData> experiments;
        private ScenarioBuildResultData buildResultData;

        private PerformanceTestScenario(PerformanceTestHistory history, ScenarioBuildResultData buildResultData) {
            this.history = history;
            experiments = filterForRequestedCommit(history.getExecutions())
                .stream()
                .map(execution -> extractExperimentData(execution, MeasuredOperationList::getTotalTime))
                .collect(Collectors.toList());
            this.buildResultData = buildResultData;
        }

        private boolean isSuccessful() {
            return scenarioBuildResultData == null || buildResultData.isSuccessful();
        }

        private boolean isArchived() {
            return experiments.isEmpty();
        }

        private int getRegressionPercentage() {
            return experiments.isEmpty() ? 0 : experiments.get(0).regressionPercentage;
        }
    }
}
