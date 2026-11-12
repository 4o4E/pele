import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm") version "2.4.21"
  id("com.ncorti.ktfmt.gradle") version "0.27.0"
  id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "top.e404.pele"

// The upstream Pele version this ports (PELE_VERSION in Pele.kt), plus our own revision counter
// for releases of the port between upstream versions: 1.12.0.1, 1.12.0.2, ... See UPDATE.md.
version = "1.12.0.1-java11.2-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `./gradlew ktfmtFormat` formats the sources; `ktfmtCheck` (part of `check`) fails on unformatted
// code.
ktfmt { googleStyle() }

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.add("-Xjdk-release=11")
  }
}

java { withSourcesJar() }

// The jars carry upstream's MIT license and say which upstream commit they port.
tasks.withType<Jar>().configureEach {
  from(rootDir.resolve("../LICENSE")) { into("META-INF") }
  manifest {
    attributes(
      "Implementation-Title" to "pele-kotlin",
      "Implementation-Version" to project.version,
    )
  }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(11) }

// JUnit 6 requires Java 17; keep the test bytecode and dependencies on that baseline.
tasks.compileTestKotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.set(listOf("-Xjdk-release=17"))
  }
}

tasks.compileTestJava { options.release.set(17) }

fun Test.configurePeleTests(stackSize: String = "4m") {
  useJUnitPlatform()
  maxHeapSize = "2g"
  jvmArgs("-Duser.timezone=UTC", "-Xss$stackSize")
  systemProperty(
    "pele.testData",
    System.getProperty("pele.testData")
      ?: layout.projectDirectory.dir("testData").asFile.absolutePath,
  )
  System.getProperty("pele.type")?.let { systemProperty("pele.type", it) }
  System.getProperty("pele.svgOut")?.let { systemProperty("pele.svgOut", it) }
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = false
  }
}

tasks.test { configurePeleTests() }

tasks.register<Test>("testSmallStack") {
  description = "Checks public API and large flat inputs with a 1 MB thread stack."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  configurePeleTests("1m")
  filter { includeTestsMatching("pele.PeleTest") }
}

// `\b` and a few other regex behaviours differ on JDK <= 18, so the suite also runs on 17.
tasks.register<Test>("testJdk17") {
  description = "Runs the test suite on a JDK 17 toolchain."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
  configurePeleTests()
}

// Differential fuzzing driver for scripts/run.sh.
tasks.register<JavaExec>("fuzzRender") {
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("pele.FuzzMainKt")
  args(providers.gradleProperty("inputs").getOrElse("inputs.txt"))
  jvmArgs("-Xss4m")
  providers.gradleProperty("jdk").orNull?.let { v ->
    javaLauncher.set(
      javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v.toInt())) }
    )
  }
}

tasks.register<JavaExec>("benchmarkJourney") {
  description = "Reports journey rendering time and allocation as task counts grow."
  group = "verification"
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("pele.JourneyBenchmarkKt")
  maxHeapSize = "512m"
}

mavenPublishing {
  coordinates(project.group.toString(), "pele-kotlin", project.version.toString())
  configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
  pom {
    name.set("Pele Kotlin Java 11")
    description.set("Pele Kotlin port rebuilt for Java 11.")
    url.set("https://github.com/4o4E/pele")
    licenses {
      license {
        name.set("MIT License")
        url.set("https://opensource.org/licenses/MIT")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("4o4E")
        name.set("4o4E")
        email.set("869951226@qq.com")
        organization.set("4o4E")
        organizationUrl.set("https://github.com/4o4E")
      }
    }
    scm {
      url.set("https://github.com/4o4E/pele")
      connection.set("scm:git:https://github.com/4o4E/pele.git")
      developerConnection.set("scm:git:ssh://git@github.com/4o4E/pele.git")
    }
  }
}

publishing {
  repositories {
    maven {
      name = "nexus"
      url = uri("https://nexus.e404.top:3443/repository/maven-snapshots/")
      credentials {
        username = providers.gradleProperty("nexus.username").orNull
        password = providers.gradleProperty("nexus.password").orNull
      }
    }
  }
}
