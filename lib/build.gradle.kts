import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20-Beta"
    kotlin("plugin.serialization") version "1.7.20-Beta"
    `java-library`
}

val gremlinVersion = "3.4.11"

dependencies {
    // common
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // gremlin
    implementation("org.apache.tinkerpop:tinkergraph-gremlin:$gremlinVersion")
    implementation("org.apache.tinkerpop:gremlin-driver:$gremlinVersion")
    implementation("org.apache.tinkerpop:gremlin-groovy:$gremlinVersion")

//    serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.3.2")

    // logs
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")

    // tests
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

allprojects {

    repositories {
        maven {
            setUrl("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }

    group = "com.stepango.kremlin"
    version = "1.0.0"

    val javaVersion = "11"

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = javaVersion
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}
