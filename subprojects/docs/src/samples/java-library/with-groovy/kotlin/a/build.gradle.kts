import org.gradle.api.artifacts.type.ArtifactTypeDefinition

/*
 * Copyright 2017 the original author or authors.
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

plugins {
    `java-library`
    groovy
}

dependencies {
    compile(localGroovy())
}

// tag::configure-groovy[]
configurations {
    "apiElements" {
        val compileGroovy = tasks.getByName<GroovyCompile>("compileGroovy")
        outgoing.variants.getByName("classes").artifact(mapOf(
            "file" to compileGroovy.destinationDir,
            "type" to ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
            "builtBy" to compileGroovy)
        )
    }
}
// end::configure-groovy[]
