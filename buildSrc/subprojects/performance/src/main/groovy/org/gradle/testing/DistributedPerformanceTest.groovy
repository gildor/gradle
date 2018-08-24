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

package org.gradle.testing

import com.google.common.base.Splitter
import com.google.common.collect.Lists
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.GradleException
import groovy.json.JsonOutput
import org.gradle.api.tasks.*
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.initialization.BuildCancellationToken
import org.gradle.process.CommandLineArgumentProvider
import groovyx.net.http.RESTClient
import groovyx.net.http.ContentType

import javax.inject.Inject
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.gradle.api.Action
import org.gradle.process.JavaExecSpec
import groovy.transform.CompileStatic

import org.jetbrains.teamcity.rest.TeamCityInstance
import org.jetbrains.teamcity.rest.TeamCityInstanceFactory
import org.jetbrains.teamcity.rest.Build
import org.jetbrains.teamcity.rest.BuildState
import org.jetbrains.teamcity.rest.BuildStatus
import org.jetbrains.teamcity.rest.BuildId
import org.openmbee.junit.model.JUnitTestSuite
import org.openmbee.junit.JUnitMarshalling

/**
 * Runs each performance test scenario in a dedicated TeamCity job.
 *
 * The test runner is instructed to just write out the list of scenarios
 * to run instead of actually running the tests. Then this list is used
 * to schedule TeamCity jobs for each individual scenario. This task
 * blocks until all the jobs have finished and aggregates their status.
 */
@CompileStatic
@CacheableTask
class DistributedPerformanceTest extends PerformanceTest {
    private final static Logger LOGGER = Logging.getLogger(DistributedPerformanceTest);

    @Internal
    String coordinatorBuildId

    @Internal
    String branchName

    @Input
    String buildTypeId

    @Input
    String workerTestTaskName

    @Input
    String teamCityUrl

    @Input
    String teamCityUsername

    @Internal
    String teamCityPassword

    @OutputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    File scenarioList

    @OutputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    File reportDir

    private TeamCityInstance client

    private RESTClient httpClient

    private List<String> scheduledBuildIds = Lists.newArrayList()

    private List<Build> finishedBuilds = Lists.newArrayList()

    private Map<String, List<JUnitTestSuite>> testResultsForScenarios = [:]

    private File workerTestResultsTempDir

    private final JUnitXmlTestEventsGenerator testEventsGenerator

    private final BuildCancellationToken cancellationToken

    @Inject
    DistributedPerformanceTest(BuildCancellationToken cancellationToken) {
        this.testEventsGenerator = new JUnitXmlTestEventsGenerator(listenerManager.createAnonymousBroadcaster(TestListener.class), listenerManager.createAnonymousBroadcaster(TestOutputListener.class))
        this.cancellationToken = cancellationToken
        jvmArgumentProviders.add(new CommandLineArgumentProvider() {
            @Override
            Iterable<String> asArguments() {
                return ["-Dorg.gradle.performance.scenario.list=$scenarioList".toString()]
            }
        })
    }

    @Override
    void addTestListener(TestListener listener) {
        testEventsGenerator.addTestListener(listener)
    }

    @Override
    void addTestOutputListener(TestOutputListener listener) {
        testEventsGenerator.addTestOutputListener(listener)
    }

    void setScenarioList(File scenarioList) {
        this.scenarioList = scenarioList
    }

    @TaskAction
    void executeTests() {
        LOGGER.quiet("start")
        createWorkerTestResultsTempDir()
        try {
            doExecuteTests()
        } catch (Exception e) {
            LOGGER.error("", e)
        } finally {
            testEventsGenerator.release()
            generatePerformanceReport()
            cleanTempFiles()
        }
    }

    private File generateResultJson() {
        File resultJson = new File(workerTestResultsTempDir, 'results.json')
        List resultData = finishedBuilds.collect {
            // org.gradle.performance.results.ScenarioBuildResultData
            [name: getScenarioId(it),
             buildTypeId: it.buildConfigurationId.toString(),
             webUrl: it.homeUrl,
             successful: it.status == BuildStatus.SUCCESS]
        }
        resultJson.text = JsonOutput.toJson(resultData)
        return resultJson
    }

    static String getScenarioId(Build build) {
        return build.parameters.find { it.name == 'scenario'} .value
    }

    private void generatePerformanceReport() {
        project.delete(reportDir)
        File resultJson = generateResultJson()
        project.javaexec(new Action<JavaExecSpec>() {
            void execute(JavaExecSpec spec) {
                spec.setMain("org.gradle.performance.results.ReportGenerator")
                spec.args(resultStoreClass, reportDir.getPath(), resultJson.getPath())
                spec.systemProperties(databaseParameters)
                spec.systemProperty("org.gradle.performance.execution.channel", channel)
                spec.setClasspath(DistributedPerformanceTest.this.getClasspath())
            }
        })
    }

    private void createWorkerTestResultsTempDir() {
        workerTestResultsTempDir = File.createTempFile("worker-test-results", "")
        workerTestResultsTempDir.delete()
        workerTestResultsTempDir.mkdir()
    }

    private void cleanTempFiles() {
        workerTestResultsTempDir.deleteDir()
    }

    private void doExecuteTests() {
        scenarioList.delete()

        fillScenarioList()

        List<Scenario> scenarios = scenarioList.readLines()
            .collect { line ->
            def parts = Splitter.on(';').split(line).toList()
            new Scenario(id: parts[0], estimatedRuntime: Long.parseLong(parts[1]), templates: parts.subList(2, parts.size()))
        }
        .sort { -it.estimatedRuntime }

        initClient()

        Build coordinatorBuild = locateBuild(coordinatorBuildId)
        testEventsGenerator.coordinatorBuild = coordinatorBuild

        scenarios.each {
            schedule(it, findLastChangeId(coordinatorBuild))
        }

        scheduledBuildIds.each {
            join(it)
        }

        checkForErrors()
    }

    private void fillScenarioList() {
        scenarioList.text = 'help on k9AndroidBuild;0;k9AndroidBuild'
        // super.executeTests()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void schedule(Scenario scenario, String lastChangeId) {
        Map<String, Object> requestData = [
            buildType: [id: buildTypeId],
            properties: [
                [name: 'scenario', value: scenario.id],
                [name: 'templates', value: scenario.templates.join(' ')],
                [name: 'baselines', value: baselines ?: 'defaults'],
                [name: 'warmups', value: warmups ?: 'defaults'],
                [name: 'runs', value: runs ?: 'defaults'],
                [name: 'checks', value: checks ?: 'all'],
                [name: 'channel', value: channel ?: 'commits'],
            ]
        ]
        if (branchName) {
            requestData['branchName'] = branchName
        }
        if (lastChangeId) {
            requestData['lastChanges'] = [change: [id: lastChangeId]]
        }

        String requestJson = JsonOutput.toJson(requestData)

        logger.info("Scheduling $scenario.id, estimated runtime: $scenario.estimatedRuntime, coordinatorBuildId: $coordinatorBuildId, lastChangeId: $lastChangeId, build request: $requestJson")

        def response = httpClient.post(
            path: "buildQueue",
            requestContentType: ContentType.JSON,
            body: requestJson
        )

        /*
        {
            "id": 14585813,
            "buildTypeId": "Gradle_Check_NoDaemon_Java8_Oracle_Windows_workers",
            "number": "921",
            "status": "FAILURE",
            "state": "finished",
            "branchName": "master",
            "href": "/app/rest/builds/id:14585813",
            "webUrl": "https://builds.gradle.org/viewLog.html?buildId=14585813&buildTypeId=Gradle_Check_NoDaemon_Java8_Oracle_Windows_workers",
            "statusText": "Gradle exception (new); exit code 1 (new)",
            "buildType": {
                "id": "Gradle_Check_NoDaemon_Java8_Oracle_Windows_workers",
                "name": "Test Coverage - NoDaemon Java8 Oracle Windows (workers)",
                "projectName": "Gradle / Check / Release Accept / Test Coverage - NoDaemon Java8 Oracle Windows",
                "projectId": "Gradle_Check_NoDaemon_Java8_Oracle_Windows",
                "href": "/app/rest/buildTypes/id:Gradle_Check_NoDaemon_Java8_Oracle_Windows_workers",
                "webUrl": "https://builds.gradle.org/viewType.html?buildTypeId=Gradle_Check_NoDaemon_Java8_Oracle_Windows_workers"
            },
            "lastChanges": {
                "change": [{
                    "id": 476592,
                    "version": "46ea4a59b549acea726dde8caa87307237a9679e",
                    "username": "gary",
                    "date": "20180730T194944+0000",
                    "href": "/app/rest/changes/id:476592",
                    "webUrl": "https://builds.gradle.org/viewModification.html?modId=476592&personal=false"
                }],
                "count": 1
            },
            ...
        }
         */
        String workerBuildId = response.data.id

        cancellationToken.addCallback {
            cancel(workerBuildId)
        }
        def scheduledChangeId = findLastChangeIdInJson(response.data)
        if (lastChangeId && scheduledChangeId != lastChangeId) {
            throw new RuntimeException("The requested change id is different than the actual one. requested change id: $lastChangeId in coordinatorBuildId: $coordinatorBuildId , actual change id: $scheduledChangeId in workerBuildId: $workerBuildId\nbuild: ${build}")
        }
        scheduledBuildIds += workerBuildId
    }

    private Build locateBuild(String buildId) {
        return buildId == null ? null : client.build(new BuildId(buildId))
    }

    private String findLastChangeId(Build build) {
        return (build == null || build.changes.empty) ? null : build.changes[0].version
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private String findLastChangeIdInJson(def data) {
        return data?.lastChanges?.change[0]
    }

    private void join(String workerBuildId) {
        boolean finished = false
        Build build = null
        while (!finished) {
            build = locateBuild(workerBuildId)
            finished = build.state == BuildState.FINISHED
            if (!finished) {
                println "Waiting for scenario build $workerBuildId to finish"
                sleep(TimeUnit.MINUTES.toMillis(1))
            }
        }
        finishedBuilds += build

        try {
            List<JUnitTestSuite> results = fetchTestResults(build)
            testResultsForScenarios.put(workerBuildId, results)
            fireTestListener(results, build)
        } catch (e) {
            e.printStackTrace(System.err)
        }
    }

    void cancel(String buildId) {
        locateBuild(buildId)?.cancel("cancelled by user", false /* reAddIntoQueue */)
    }

    private void fireTestListener(List<JUnitTestSuite> results, Build build) {
        results.each {
            testEventsGenerator.processTestSuite(it, build)
        }
    }

    private List<JUnitTestSuite> fetchTestResults(Build build) {
        PipedOutputStream os = new PipedOutputStream()
        PipedInputStream is = new PipedInputStream(os)
        String artifactPathName = "results/performance/build/test-results-${workerTestTaskName}.zip"

        Thread.start {
            build.downloadArtifact(artifactPathName, os)
        }

        return parseXmlsInZip(is)
    }

    List<JUnitTestSuite> parseXmlsInZip(InputStream inputStream) {
        def parsedXmls = []
        new ZipInputStream(inputStream).withStream { zipInput ->
            def entry
            while (entry = zipInput.nextEntry) {
                if (!entry.isDirectory() && entry.name.endsWith('.xml')) {
                    parsedXmls.add(JUnitMarshalling.unmarshalTestSuite(zipInput))
                }
            }
        }
        parsedXmls
    }

    private void checkForErrors() {
        List<Build> failedBuilds = finishedBuilds.findAll { it.status != BuildStatus.SUCCESS }
        if (failedBuilds) {
            throw new GradleException("${failedBuilds.size()} performance tests failed. See ${new File(reportDir, "performance-tests/scenario-report.html")} for details.")
        }
    }

    private void initClient() {
        client = TeamCityInstanceFactory.httpAuth(teamCityUrl, teamCityUsername, teamCityPassword)
        httpClient = new RESTClient("$teamCityUrl/httpAuth/app/rest/9.1")
        httpClient.auth.basic(teamCityUsername, teamCityPassword)
        httpClient.headers['Origin'] = teamCityUrl
    }

    private static class Scenario {
        String id
        long estimatedRuntime
        List<String> templates
    }
}
