pluginManagement {
    repositories {
        // AGP comes from Google's Maven, the Kotlin plugin from Maven Central: no Gradle Plugin Portal.
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Wayfinder"
include(":app")
