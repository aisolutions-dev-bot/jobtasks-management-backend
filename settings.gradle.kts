pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id(extra["quarkusPluginId"] as String) version (extra["quarkusPluginVersion"] as String)
    }
}

rootProject.name = "jobtasks-management-backend"
