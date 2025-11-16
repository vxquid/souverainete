pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "ignis"
include("nms-support", "nms-support:v1_21_R6", "nms-support:v1_21_R3", "nms-support:v1_21_R1")
include("nms-support:base")