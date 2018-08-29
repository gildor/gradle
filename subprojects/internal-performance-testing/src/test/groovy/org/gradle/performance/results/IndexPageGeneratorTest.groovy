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

package org.gradle.performance.results

import groovy.json.JsonOutput
import org.gradle.performance.measure.Amount
import org.gradle.performance.measure.Duration
import org.gradle.performance.measure.MeasuredOperation
import org.gradle.performance.util.Git
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification
import spock.lang.Subject

class IndexPageGeneratorTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    @Subject
    IndexPageGenerator generator

    File resultsJson = tmpDir.file('results.json')
    ResultsStore mockStore = Mock(ResultsStore)

    def createGenerator(String json) {
        resultsJson << json
        generator = new IndexPageGenerator([], resultsJson)
    }

    def 'can sort scenarios correctly'() {
        setup:
        createGenerator(JsonOutput.toJson([
            new ScenarioBuildResultData(name: 'active1', webUrl: 'activeUrl1', successful: true),
            new ScenarioBuildResultData(name: 'active2', webUrl: 'activeUrl2', successful: false),
            new ScenarioBuildResultData(name: 'active3', webUrl: 'activeUrl3', successful: true),
        ]))

        String currentCommit = Git.current().getCommitId()

        PerformanceTestHistory archivedHistory = Mock(PerformanceTestHistory)
        PerformanceTestHistory smallRegressionHistory = Mock(PerformanceTestHistory)
        PerformanceTestHistory bigRegressionHistory = Mock(PerformanceTestHistory)


        PerformanceTestExecution smallRegressionExecution = Mock(PerformanceTestExecution)
        PerformanceTestExecution bigRegressionExecution = Mock(PerformanceTestExecution)

        when:
        _ * mockStore.getTestNames() >> ['active1', 'active2', 'active3', 'archive1', 'archive2']
        _ * mockStore.getTestResults('active1', _, _, _) >> smallRegressionHistory
        _ * mockStore.getTestResults('active2', _, _, _) >> smallRegressionHistory
        _ * mockStore.getTestResults('active3', _, _, _) >> bigRegressionHistory
        _ * mockStore.getTestResults('archive1', _, _, _) >> archivedHistory
        _ * mockStore.getTestResults('archive2', _, _, _) >> archivedHistory

        _ * smallRegressionExecution.getScenarios() >> [experiement(1), experiement(1)]
        _ * bigRegressionExecution.getScenarios() >> [experiement(1), experiement(2)]
        _ * smallRegressionExecution.getVcsCommits() >> [currentCommit]
        _ * bigRegressionExecution.getVcsCommits() >> [currentCommit]

        _ * archivedHistory.getExecutions() >> []

        _ * smallRegressionHistory.getExecutions() >> [smallRegressionExecution]
        _ * bigRegressionHistory.getExecutions() >> [bigRegressionExecution]

        then:
        generator.sortTestResults(mockStore).toList().collect { it.name } == ['active2', 'active3', 'active1', 'archive1', 'archive2']
    }

    private MeasuredOperationList experiement(int value) {
        MeasuredOperationList measuredOperationList = new MeasuredOperationList()
        measuredOperationList.add(new MeasuredOperation(totalTime: Amount.valueOf(value, Duration.SECONDS)))
        return measuredOperationList
    }
}
