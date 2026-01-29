plugins {
    kotlin("jvm")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
}

group = "souverainete"
version = "1"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.10-R0.1-SNAPSHOT")
    implementation(project(":nms-support:base"))
    implementation(project(":nms-support:v1_21_R1"))
    implementation(project(":nms-support:v1_21_R3"))
    implementation(project(":nms-support:v1_21_R5"))
    implementation(project(":nms-support:v1_21_R6"))
    implementation(project(":nms-support:v1_21_R7"))
}

kotlin {
    jvmToolchain(21)
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib"))
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            javaParameters = true
        }
    }
}