plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "souverainete"
version = "1"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
    implementation(project(":nms-support:base"))
}

kotlin {
    jvmToolchain(21)
}