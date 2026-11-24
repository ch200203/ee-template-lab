plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val archFast = providers.gradleProperty("archFast").map { it.toBoolean() }.orElse(false)
val structureReport = layout.buildDirectory.file("reports/ee/structure-report.json")

val archReportClean = tasks.register<Delete>("archReportClean") {
    delete(structureReport)
}

tasks.named("compileJava") {
    dependsOn(archReportClean)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    exclude("**/*ArchitectureTest.class")
}

tasks.register<Test>("archCheck") {
    description = "ArchUnit 구조 검사"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/*ArchitectureTest.class")
    dependsOn("testClasses")
    if (!archFast.get()) dependsOn("test")
    systemProperty(
        "ee.structureReport",
        structureReport.get().asFile.absolutePath,
    )
    systemProperty("ee.projectName", rootProject.name)
    listOf("EE_REPO", "EE_PR_NUMBER", "EE_HEAD_SHA", "EE_RUN_ID", "EE_RUN_ATTEMPT").forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
        showCauses = false
    }
    outputs.upToDateWhen { false }
}
