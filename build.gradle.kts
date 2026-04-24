import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml
import xyz.jpenilla.resourcefactory.bukkit.Permission

plugins {
  kotlin("jvm") version "2.0.0"
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
  id("xyz.jpenilla.run-paper") version "2.3.1"
  id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.0"
  id("com.gradleup.shadow") version "9.2.2"
  id("com.github.gmazzo.buildconfig") version "5.4.0"
}

val isPremiumBuild = project.findProperty("premium")?.toString()?.toBoolean() ?: true
val buildSuffix = if (isPremiumBuild) "premium" else "free"

group = "souverainete"
version = "0.8.2.1"
description = "The definitive overhaul of villager intelligence and society."

buildConfig {
  packageName("vx.sv")
  buildConfigField("Boolean", "IS_PREMIUM", isPremiumBuild.toString())
}

bukkitPluginYaml {
  name = "souverainete"
  main = "vx.sv.Souverainete"
  load = BukkitPluginYaml.PluginLoadOrder.STARTUP
  depend = listOf("packetevents")
  softDepend = listOf("MythicMobs")
  authors.add("vxquid")
  apiVersion = "1.21"

  permissions {
    register("sv.player.settings") {
      description = "Allows opening the settings menu."
      default = Permission.Default.TRUE
    }
    register("sv.quest.remove") {
      description = "Allows removing an accepted quest."
      default = Permission.Default.TRUE
    }
    register("sv.quest.track") {
      description = "Allows tracking a quest."
      default = Permission.Default.TRUE
    }
    register("sv.quest.list") {
      description = "Allows listing accepted quests."
      default = Permission.Default.TRUE
    }
    register("sv.quest.stats") {
      description = "Allows viewing quest statistics."
      default = Permission.Default.TRUE
    }
  }
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
  implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
  implementation("org.yaml:snakeyaml:1.33")
  implementation(project(":nms-support"))
  implementation(project(":nms-support:base"))
  compileOnly(files("libs/vivaldi-1.0.0-all.jar"))
  compileOnly("com.github.retrooper:packetevents-spigot:2.11.1")
  compileOnly("io.lumine:Mythic-Dist:5.10.0")
  compileOnly("org.geysermc.geyser:api:2.9.0-SNAPSHOT")
  compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
}

tasks {
  compileJava {
    options.release = 21
  }

  javadoc {
    options.encoding = Charsets.UTF_8.name()
  }

  shadowJar {
    archiveFileName = "souverainete-${project.version}-$buildSuffix.jar"

    relocate("co.aikar.commands", "vx.sv.command")
    relocate("co.aikar.locales", "vx.sv.command.locales")
    relocate("kotlin", "vx.sv.kotlin")
    relocate("com.cryptomorin.xseries", "vx.sv.utils")
    relocate("org.yaml.snakeyaml", "vx.sv.libs.snakeyaml")

    minimize()
  }
}

java {
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
  jvmToolchain(21)
}


// --- ИСПРАВЛЕНИЕ: Вызываем независимый процесс (Exec) вместо таски GradleBuild ---

// Находим правильный скрипт gradlew в зависимости от ОС (Windows или Linux/Mac)
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val gradlewPath = project.rootDir.resolve(if (isWindows) "gradlew.bat" else "gradlew").absolutePath

val buildPremium by tasks.registering(Exec::class) {
  group = "build variants"
  description = "Build Premium plugin version."

  // Изолированный запуск сборки
  commandLine(gradlewPath, "shadowJar", "-Ppremium=true")
}

val buildFree by tasks.registering(Exec::class) {
  group = "build variants"
  description = "Build Free plugin version."

  // Изолированный запуск сборки
  commandLine(gradlewPath, "shadowJar", "-Ppremium=false")

  mustRunAfter(buildPremium) // Чтобы логи не перемешивались, запускаем по очереди
}

tasks.register("buildAllVariants") {
  group = "build variants"
  description = "Build both versions."
  dependsOn(buildPremium, buildFree)
}