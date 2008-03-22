/*
 * Copyright 2007 the original author or authors.
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

package org.gradle.initialization

import groovy.mock.interceptor.MockFor
import groovy.mock.interceptor.StubFor
import org.gradle.api.DependencyManager
import org.gradle.api.DependencyManagerFactory
import org.gradle.api.internal.dependencies.DefaultDependencyManagerFactory
import org.gradle.initialization.DefaultSettings
import org.gradle.initialization.SettingsFileHandler
import org.gradle.initialization.SettingsProcessor

/**
 * @author Hans Dockter
 */
class SettingsProcessorTest extends GroovyTestCase {
    static final File TEST_ROOT_DIR = new File('rootDir')
    SettingsProcessor settingsProcessor
    SettingsFileHandler settingsFileHandler
    DependencyManagerFactory dependencyManagerFactory
    SettingsFactory settingsFactory
    BuildSourceBuilder buildSourceBuilder
    File gradleUserHomeDir
    File buildResolverDir

    DefaultSettings expectedSettings
    MockFor settingsFactoryMocker

    void setUp() {
        expectedSettings = new DefaultSettings()
        settingsFileHandler = new SettingsFileHandler()
        settingsFactory = new SettingsFactory()
        dependencyManagerFactory = new DefaultDependencyManagerFactory(new File('root'))
        buildSourceBuilder = new BuildSourceBuilder()
        gradleUserHomeDir = new File('gradleUserHomeDir')
        buildResolverDir = new File('buildResolverDir')
        settingsProcessor = new SettingsProcessor(settingsFileHandler, settingsFactory, dependencyManagerFactory, buildSourceBuilder,
                gradleUserHomeDir, buildResolverDir)

        settingsFactoryMocker = new MockFor(SettingsFactory)
    }

    void testSettingsProcessor() {
        assert settingsProcessor.settingsFileHandler.is(settingsFileHandler)
        assert settingsProcessor.settingsFactory.is(settingsFactory)
        assert settingsProcessor.dependencyManagerFactory.is(dependencyManagerFactory)
        assert settingsProcessor.buildSourceBuilder.is(buildSourceBuilder)
        assert settingsProcessor.gradleUserHomeDir.is(gradleUserHomeDir)
        assert settingsProcessor.buildResolverDir.is(buildResolverDir)
    }

    void testCreateBasicSettings() {
        File expectedCurrentDir = new File(TEST_ROOT_DIR, 'currentDir')
        prepareSettingsFactoryMocker(expectedCurrentDir, expectedCurrentDir)
        assertEquals([], expectedSettings.projectPaths)
        settingsFactoryMocker.use(settingsProcessor.settingsFactory) {
            assert settingsProcessor.createBasicSettings(expectedCurrentDir).is(expectedSettings)
        }
        assertEquals(buildResolverDir, settingsProcessor.dependencyManagerFactory.buildResolverDir)
    }

    void testProcessWithCurrentDirAsSubproject() {
        File currentDir = new File(TEST_ROOT_DIR, 'currentDir')
        List includePaths = ['currentDir', 'path2']
        DefaultSettings settings = runCUT(TEST_ROOT_DIR, currentDir, includePaths, buildResolverDir)
        assertEquals(includePaths, settings.projectPaths)
    }

    void testProcessWithCurrentDirNoSubproject() {
        File currentDir = new File(TEST_ROOT_DIR, 'currentDir')
        DefaultSettings settings = runCUT(TEST_ROOT_DIR, currentDir, ['path1', 'path2'], buildResolverDir) {
            prepareSettingsFactoryMocker(currentDir, currentDir)
        }
        assertEquals([], settings.projectPaths)
    }

    void testProcessWithCurrentDirAsRootDir() {
        List includePaths = ['path1', 'path2']
        DefaultSettings settings = runCUT(TEST_ROOT_DIR, TEST_ROOT_DIR, includePaths, buildResolverDir)
        assertEquals(includePaths, settings.projectPaths)
    }

    void testProcessWithNullBuildResolver() {
        settingsProcessor.buildResolverDir = null
        List includePaths = ['path1', 'path2']
        DefaultSettings settings = runCUT(TEST_ROOT_DIR, TEST_ROOT_DIR, includePaths,
                new File(TEST_ROOT_DIR, DependencyManager.BUILD_RESOLVER_NAME))
        assertEquals(includePaths, settings.projectPaths)
    }

    private void prepareSettingsFactoryMocker(File expectedRootDir, File expectedCurrentDir) {
        expectedSettings.rootDir = expectedRootDir
        expectedSettings.currentDir = expectedCurrentDir
        settingsFactoryMocker.demand.createSettings(1..1) {File currentDir, File rootDir, DependencyManagerFactory dependencyManagerFactory,
                                                           BuildSourceBuilder buildSourceBuilder, File gradleUserHomeDir ->
            assertEquals(expectedRootDir, rootDir)
            assertEquals(expectedCurrentDir, currentDir)
            assert dependencyManagerFactory.is(settingsProcessor.dependencyManagerFactory)
            assert buildSourceBuilder.is(settingsProcessor.buildSourceBuilder)
            assertEquals(settingsProcessor.gradleUserHomeDir, gradleUserHomeDir)
            expectedSettings.projectPaths = []
            expectedSettings
        }

    }

    private DefaultSettings runCUT(File rootDir, File currentDir, List includePaths, File expectedBuildResolverRoot,
                                   Closure customSettingsFactoryPreparation = {}) {
        StubFor settingsFileHandlerMocker = new StubFor(SettingsFileHandler)
        String expectedSettingsText = "include \"${includePaths[0]}\", \"${includePaths[1]}\""
        boolean expectedSearchUpwards = false

        settingsFileHandlerMocker.demand.find(1..1) {File dir, boolean searchUpwards ->
            assertSame(currentDir, dir)
            assertEquals(expectedSearchUpwards, searchUpwards)
        }
        settingsFileHandlerMocker.demand.getRootDir(0..10) {rootDir}
        settingsFileHandlerMocker.demand.getSettingsText {expectedSettingsText}

        prepareSettingsFactoryMocker(rootDir, currentDir)
        customSettingsFactoryPreparation()

        DefaultSettings settings
        settingsFactoryMocker.use(settingsProcessor.settingsFactory) {
            settingsFileHandlerMocker.use(settingsProcessor.settingsFileHandler) {
//                SettingsProcessor settingsProcessor = new SettingsProcessor(new SettingsFileHandler(),
//                        new DefaultDependencyManagerFactory(), expectedBuildSourceBuilder, gradleUserHomeDir, buildResolverDir)
                settings = settingsProcessor.process(currentDir, expectedSearchUpwards)
            }
        }
        assertEquals(expectedBuildResolverRoot, settingsProcessor.dependencyManagerFactory.buildResolverDir)
        settingsFileHandlerMocker.expect.verify()
        settings
    }
}