plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    api(libs.jna)
    api(libs.gson)
    compileOnlyApi(libs.jetbrains.annotations)

    testImplementation(libs.jetbrains.annotations)
    testImplementation(libs.junit.jupiter)
}