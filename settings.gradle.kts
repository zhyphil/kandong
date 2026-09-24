pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "KanDong"
include(":app", ":fixture")

include(":compat")

// Independent synthetic-image experiment; not included in the production APK.
include(":qualitylab")

include(":graphics")

// Fixed synthetic OCR inputs only; independent from the product applications.
include(":ocrlab")

// Independent, packaged synthetic same-tensor feasibility probe.
include(":modelprobe")
