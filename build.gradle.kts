plugins {
    java
    application
    kotlin("jvm") version "2.0.0"
    jacoco
}

group = "me.cfpq.pointsto.miner"
version = "1.0-SNAPSHOT"

val jacoDbVersion: String by rootProject
val slf4jVersion: String by rootProject
val kotlinLoggingVersion: String by rootProject

val hibernateVersion: String by project
val springBootVersion: String by project
val guavaVersion: String by project
val commonsLangVersion: String by project
val commonsIoVersion: String by project
val junitVersion: String by project
val jacksonVersion: String by project
val mockitoVersion: String by project
val gsonVersion: String by project

application {
    mainClass.set("me.cfpq.pointsto.miner.MainKt")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

//tasks {
//    // Ensure classes are in the runtime classpath
//    withType<JavaExec> {
//        classpath = sourceSets.main.get().runtimeClasspath
//    }
//}

dependencies {
//    implementation(group = "org.jacodb", name = "jacodb-api", version = jacoDbVersion)
//    implementation(group = "org.jacodb", name = "jacodb-core", version = jacoDbVersion)
//    implementation(group = "org.jacodb", name = "jacodb-analysis", version = jacoDbVersion)
//    implementation(group = "org.jacodb", name = "jacodb-approximations", version = jacoDbVersion)
    implementation("com.github.UnitTestBot.jacodb:jacodb-api-common:d7dd9d343b")
    implementation("com.github.UnitTestBot.jacodb:jacodb-api-jvm:d7dd9d343b")
    implementation("com.github.UnitTestBot.jacodb:jacodb-api-storage:d7dd9d343b")
    implementation("com.github.UnitTestBot.jacodb:jacodb-core:d7dd9d343b")

    implementation(group =  "org.slf4j", name = "slf4j-simple", version = slf4jVersion)
    implementation(group = "io.github.microutils", name = "kotlin-logging", version = kotlinLoggingVersion)

    // For some future analysis it may be needed to use a separate `ClassLoader` for libs under analysis,
    // but right now graph mining doesn't necessarily require it, so for the sake of simplicity it's not used. 
    runtimeOnly("com.google.guava:guava:$guavaVersion")
    runtimeOnly("org.apache.commons:commons-lang3:$commonsLangVersion")
    runtimeOnly("commons-io:commons-io:$commonsIoVersion")
    runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    runtimeOnly("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    runtimeOnly("org.mockito:mockito-core:$mockitoVersion")
    runtimeOnly("com.google.code.gson:gson:$gsonVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
}

kotlin {
    jvmToolchain(19)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    maxHeapSize = "5g"
}


tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
    }
}
