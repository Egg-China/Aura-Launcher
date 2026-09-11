rootProject.name = "AuraLauncher"
include(
    "AuraLauncher",
    "AuraNative",
    "AuraCore",
    "AuraBoot"
)

val minecraftLibraries = listOf("HMCLTransformerDiscoveryService", "HMCLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
