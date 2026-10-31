pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Mapbox Maps SDK. Public release repo; no secret download token needed.
    maven {
      url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
      content { includeGroupByRegex("com\\.mapbox.*") }
    }
  }
}

rootProject.name = "Lidar Metal Detector"

include(":app")
