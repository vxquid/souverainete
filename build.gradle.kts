import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml
import xyz.jpenilla.resourcefactory.bukkit.Permission

plugins {
  kotlin("jvm") version "2.3.0"
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
  id("xyz.jpenilla.run-paper") version "3.0.2" // Adds runServer and runMojangMappedServer tasks for testing
  id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.1" // Generates plugin.yml based on the Gradle config
  id("com.gradleup.shadow") version "9.2.2"
  id("com.github.gmazzo.buildconfig") version "5.4.0"
}

val isPremiumBuild = project.findProperty("premium")?.toString()?.toBoolean() ?: true
val buildSuffix = if (isPremiumBuild) "premium" else "free"
val javaVersion = 25

group = "souverainete"
version = "1.0.6"
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
  apiVersion = "26.2"

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
  paperweight.paperDevBundle("26.2.build.+")
  implementation(kotlin("stdlib"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("com.github.cryptomorin:XSeries:13.7.0") // Обновлено до 13.7.0 (поддержка MC 26)
  implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
  implementation("org.yaml:snakeyaml:1.33") // Специфическая версия YAML сохранена
  compileOnly(files("libs/vivaldi-1.0.0-all.jar"))
  compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
  compileOnly("org.geysermc.geyser:api:2.9.0-SNAPSHOT")
  compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
}

tasks {
  compileJava {
    options.release = javaVersion
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
  toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

kotlin {
  jvmToolchain(javaVersion)
}


// --- ИСПРАВЛЕНИЕ: Оптимизированный запуск независимых процессов для Windows и Linux ---

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val gradlewFile = project.rootDir.resolve(if (isWindows) "gradlew.bat" else "gradlew")

// На Linux/macOS выдаем файлу gradlew права на выполнение программно
if (!isWindows && gradlewFile.exists()) {
  gradlewFile.setExecutable(true)
}

val gradlewPath = gradlewFile.absolutePath

val buildPremium by tasks.registering(Exec::class) {
  group = "construct variants"
  description = "Build Premium plugin version."

  // На Linux запускаем скрипт через командный интерпретатор "sh", чтобы избежать ошибок доступа
  if (isWindows) {
    commandLine(gradlewPath, "shadowJar", "-Ppremium=true")
  } else {
    commandLine("sh", gradlewPath, "shadowJar", "-Ppremium=true")
  }
}

val buildFree by tasks.registering(Exec::class) {
  group = "construct variants"
  description = "Build Free plugin version."

  if (isWindows) {
    commandLine(gradlewPath, "shadowJar", "-Ppremium=false")
  } else {
    commandLine("sh", gradlewPath, "shadowJar", "-Ppremium=false")
  }

  mustRunAfter(buildPremium) // Чтобы логи не перемешивались, запускаем по очереди
}

tasks.register("buildAllVariants") {
  group = "construct variants"
  description = "Build both versions."
  dependsOn(buildPremium, buildFree)
}