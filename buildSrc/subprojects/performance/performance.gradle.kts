apply(plugin = "org.gradle.kotlin.kotlin-dsl")

repositories {
    jcenter()
}

dependencies {
    api(project(":integrationTesting"))
    implementation(project(":build"))
    implementation("org.codehaus.groovy.modules.http-builder:http-builder:0.7.2") {
        // Xerces on the runtime classpath is breaking some of our doc tasks
        exclude(group = "xerces")
    }
    implementation("org.jetbrains.teamcity:teamcity-rest-client:1.0.5")
    implementation("org.openmbee.junit:junit-xml-parser:1.0.0")
}
