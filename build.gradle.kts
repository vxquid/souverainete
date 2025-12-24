import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
  kotlin("jvm") version "2.0.0"
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
  id("xyz.jpenilla.run-paper") version "2.3.1"
  id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.0"
  id("com.gradleup.shadow") version "9.2.2"
}

group = "ignis"
version = "0.3.5"
description = "Let AI make your server alive with AI-generated NPCs, quests, towns, items and a lot more."

bukkitPluginYaml {
  name = "Ignis"
  main = "vx.ignis.Ignis"
  load = BukkitPluginYaml.PluginLoadOrder.STARTUP
  depend = listOf("packetevents")
  softDepend = listOf("MythicMobs")
  authors.add("vxquid")
  apiVersion = "1.21"
}

repositories {
  mavenCentral()
  maven("https://repo.aikar.co/content/groups/aikar/")
  maven("https://repo.papermc.io/repository/maven-public/")
  maven("https://repo.codemc.io/repository/maven-releases/")
  maven("https://repo.codemc.io/repository/maven-snapshots/")
  maven("https://mvn.lumine.io/repository/maven-public/")
  maven("https://repo.opencollab.dev/main/")
}

dependencies {
  paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
  implementation(kotlin("stdlib"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("com.github.cryptomorin:XSeries:13.3.3")
  implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT") // Annotations-based commands.
  implementation(project(":nms-support"))
  implementation(project(":nms-support:base"))
  compileOnly("com.github.retrooper:packetevents-spigot:2.11.1")
  compileOnly("io.lumine:Mythic-Dist:5.10.0")
  compileOnly("org.geysermc.geyser:api:2.9.0-SNAPSHOT")
  compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
}

tasks {

  compileJava {
    // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
    // See https://openjdk.java.net/jeps/247 for more information.
    options.release = 21
  }

  javadoc {
    options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
  }

  tasks {
    shadowJar {
      archiveFileName = "ignis-${version}.jar"
      minimize()
      relocate("co.aikar.commands", "vx.ignis.command")
      relocate("co.aikar.locales", "vx.ignis.command.locales")
      relocate("kotlin", "vx.ignis.kotlin")
      relocate("com.github.retrooper.packetevents", "vx.ignis.packetevents.api")
      relocate("io.github.retrooper.packetevents", "vx.ignis.packetevents.impl")
      relocate("com.cryptomorin.xseries", "vx.ignis.utils")
    }
  }

}

repositories {
  mavenCentral()
}

java {
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
  jvmToolchain(21)
}