rootProject.name = "AuraLauncher"
include(
    "AuraLauncher",
    "AuraCore",
    "AuraBoot"
)

val minecraftLibraries = listOf("HMCLTransformerDiscoveryService", "HMCLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
