dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    implementation(project(":modules:api"))
    implementation(project(":modules:core"))
    implementation(project(":modules:database"))
    implementation(project(":modules:config"))
    implementation(project(":modules:registry"))
    implementation(project(":modules:assets"))
    implementation(project(":modules:animation"))
    implementation(project(":modules:gui"))
    implementation(project(":modules:world"))
    implementation(project(":modules:ai"))
    implementation(project(":modules:admin"))
    implementation(project(":modules:gameplay"))
    implementation(project(":modules:content"))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(
            mapOf(
                "version" to project.version
            )
        )
    }
}
