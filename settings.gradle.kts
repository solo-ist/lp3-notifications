// Every dependency group resolves from exactly one repository. Android and
// AndroidX artifacts come only from Google's Maven; everything else
// only from Maven Central. Without these filters Gradle tries repositories in
// order and can fall back to another after a failed lookup, which is the
// class of problem behind CVE-2026-22816 and CVE-2026-22865.
val googleOnly = listOf(
    "com\\.android(\\..*)?",
    "androidx(\\..*)?",
    "com\\.google\\.testing\\.platform(\\..*)?",
)

pluginManagement {
    val googleOnly = listOf(
        "com\\.android(\\..*)?",
        "androidx(\\..*)?",
            "com\\.google\\.testing\\.platform(\\..*)?",
    )
    repositories {
        google { content { googleOnly.forEach { includeGroupByRegex(it) } } }
        mavenCentral { content { googleOnly.forEach { excludeGroupByRegex(it) } } }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { googleOnly.forEach { includeGroupByRegex(it) } } }
        mavenCentral { content { googleOnly.forEach { excludeGroupByRegex(it) } } }
    }
}

rootProject.name = "lp3-notifications"
include(":app")
