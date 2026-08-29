import org.jackhuang.hmcl.gradle.TerracottaConfigUpgradeTask
import org.jackhuang.hmcl.gradle.ci.GitHubActionUtils
import org.jackhuang.hmcl.gradle.l10n.CheckTranslations
import org.jackhuang.hmcl.gradle.l10n.CreateLanguageList
import org.jackhuang.hmcl.gradle.l10n.CreateLocaleNamesResourceBundle
import org.jackhuang.hmcl.gradle.l10n.UpsideDownTranslate
import org.jackhuang.hmcl.gradle.mod.ParseModDataTask
import org.jackhuang.hmcl.gradle.pack.CreateDeb
import org.jackhuang.hmcl.gradle.pack.ReleaseType
import org.jackhuang.hmcl.gradle.utils.PropertiesUtils
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.quality.Checkstyle
import groovy.json.JsonSlurper
import java.math.BigDecimal
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

val projectConfig = PropertiesUtils.load(rootProject.file("config/project.properties").toPath())

val isOfficial = GitHubActionUtils.IS_ON_OFFICIAL_REPO

val versionType = System.getenv("VERSION_TYPE") ?: if (isOfficial) "nightly" else "unofficial"
val versionRoot = System.getenv("VERSION_ROOT") ?: projectConfig.getProperty("versionRoot") ?: "3"
val buildVersion = System.getenv("BUILD_VERSION")?.takeIf(String::isNotBlank)
val nextVersionSuffix = "-next"

fun String.withNextVersionSuffix(): String {
    val suffixIndex = indexOf(nextVersionSuffix)
    require(suffixIndex < 0 || suffixIndex == length - nextVersionSuffix.length) {
        "Launcher version may contain $nextVersionSuffix only once at the end: $this"
    }
    return if (suffixIndex < 0) "$this$nextVersionSuffix" else this
}

val microsoftAuthId = System.getenv("MICROSOFT_AUTH_ID") ?: ""
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: ""

val launcherExe = System.getenv("AURA_LAUNCHER_EXE")
    ?.takeIf(String::isNotBlank)
    ?: layout.projectDirectory.file("launcher/HMCLauncher.exe").asFile.path

val selectedVersion = if (buildVersion != null) {
    buildVersion
} else {
    val shortCommit = System.getenv("GITHUB_SHA")?.lowercase()?.substring(0, 7)
    if (shortCommit.isNullOrBlank()) {
        "$versionRoot.SNAPSHOT"
    } else if (isOfficial) {
        "$versionRoot.dev-$shortCommit"
    } else {
        "$versionRoot.unofficial-$shortCommit"
    }
}
version = selectedVersion.withNextVersionSuffix()

val embedResources = configurations.register("embedResources")

val pluginSystemDirectory = rootProject.layout.projectDirectory.dir("AuraPluginSystem")

sourceSets {
    main {
        java.srcDir(pluginSystemDirectory.dir("src/main/java"))
        resources.srcDir(pluginSystemDirectory.dir("src/main/resources"))
    }
    test {
        java.srcDir(pluginSystemDirectory.dir("src/test/java"))
        resources.srcDir(pluginSystemDirectory.dir("src/test/resources"))
    }
}

dependencies {
    implementation(project(":AuraCore"))
    implementation(project(":AuraBoot"))
    implementation("libs:JFoenix")
    implementation(libs.jwebp)
    implementation(libs.fxsvgimage)
    implementation(libs.java.info)
    implementation(libs.monet.fx)
    implementation(libs.nayuki.qrcodegen)
    implementation(libs.uuid.tools)
    implementation(libs.mixin)
    implementation(libs.guava)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)

    testImplementation(libs.jimfs)

    embedResources(libs.authlib.injector)
    embedResources(libs.lwjgl.unsafe.agent)
}

fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)

fun createChecksum(file: File) {
    val algorithms = linkedMapOf(
        "SHA-1" to "sha1",
        "SHA-256" to "sha256",
        "SHA-512" to "sha512"
    )

    algorithms.forEach { (algorithm, ext) ->
        File(file.parentFile, "${file.name}.$ext").writeText(
            digest(algorithm, file.readBytes()).joinToString(separator = "", postfix = "\n") { "%02x".format(it) }
        )
    }
}

fun attachSignature(jar: File) {
    val keyLocation = System.getenv("AURA_SIGNATURE_KEY")
    if (keyLocation == null) {
        logger.warn("Missing signature key")
        return
    }

    val privatekey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(keyLocation).readBytes()))
    val signer = Signature.getInstance("SHA512withRSA")
    signer.initSign(privatekey)
    ZipFile(jar).use { zip ->
        zip.stream()
            .sorted(Comparator.comparing { it.name })
            .filter { it.name != "META-INF/hmcl_signature" }
            .forEach {
                signer.update(digest("SHA-512", it.name.toByteArray()))
                signer.update(digest("SHA-512", zip.getInputStream(it).readBytes()))
            }
    }
    val signature = signer.sign()
    FileSystems.newFileSystem(URI.create("jar:" + jar.toURI()), emptyMap<String, Any>()).use { zipfs ->
        Files.newOutputStream(zipfs.getPath("META-INF/hmcl_signature")).use { it.write(signature) }
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.checkstyleMain {
    source = fileTree("src/main/java") {
        exclude("**/org/jackhuang/hmcl/ui/image/apng/**")
    }
}
tasks.checkstyleTest {
    source = fileTree("src/test/java")
}

fun Checkstyle.configurePluginSystemCheckstyle(sourceDirectory: Directory, sourceClasspath: FileCollection) {
    source = fileTree(sourceDirectory) { include("**/*.java") }
    classpath = sourceClasspath
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    setConfigProperties("licenseHeaderFile" to rootProject.file("config/checkstyle/license-header-apache.txt"))
}

val checkstylePluginMain = tasks.register<Checkstyle>("checkstylePluginMain") {
    configurePluginSystemCheckstyle(
        pluginSystemDirectory.dir("src/main/java"),
        sourceSets.main.get().compileClasspath)
}
val checkstylePluginTest = tasks.register<Checkstyle>("checkstylePluginTest") {
    configurePluginSystemCheckstyle(
        pluginSystemDirectory.dir("src/test/java"),
        sourceSets.test.get().compileClasspath)
}
tasks.named("checkstyle") {
    dependsOn(checkstylePluginMain, checkstylePluginTest)
}

val addOpens = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding",
    "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime",
    "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css",
    "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene",
    "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage",
    "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control.skin",
    "jdk.attach/sun.tools.attach",
)

tasks.compileJava {
    options.compilerArgs.addAll(addOpens.map { "--add-exports=$it=ALL-UNNAMED" })
}

val hmclProperties = buildList {
    add("aura.version" to project.version.toString())
    add("hmcl.add-opens" to addOpens.joinToString(" "))
    System.getenv("GITHUB_SHA")?.let {
        add("aura.version.hash" to it)
    }
    add("aura.version.type" to versionType)
    add("hmcl.microsoft.auth.id" to microsoftAuthId)
    add("hmcl.curseforge.apikey" to curseForgeApiKey)
    add("hmcl.authlib-injector.version" to libs.authlib.injector.get().version!!)
    add("hmcl.lwjgl-unsafe-agent.version" to libs.lwjgl.unsafe.agent.get().version!!)
}

val hmclPropertiesFile = layout.buildDirectory.file("hmcl.properties")
val pluginTrustRootFile = layout.buildDirectory.file("generated/plugin-trust/aura-plugin-root.json")

fun exactPluginTrustRootInteger(value: Any?, field: String): Int {
    val number = value as? Number
        ?: throw GradleException("Plugin trust root $field must be an integer")
    return try {
        BigDecimal(number.toString()).intValueExact()
    } catch (exception: ArithmeticException) {
        throw GradleException("Plugin trust root $field must be an exact integer", exception)
    }
}

fun validatePluginTrustRoot(contents: String, requireOfficialRepository: Boolean) {
    val document = try {
        JsonSlurper().parseText(contents) as? Map<*, *>
    } catch (exception: RuntimeException) {
        throw GradleException("AURA_PLUGIN_ROOT_JSON is not valid JSON", exception)
    } ?: throw GradleException("AURA_PLUGIN_ROOT_JSON must contain a JSON object")
    val signed = document["signed"] as? Map<*, *>
        ?: throw GradleException("Plugin trust root is missing signed metadata")
    if (signed["_type"] != "root"
        || exactPluginTrustRootInteger(signed["schemaVersion"], "schemaVersion") != 1) {
        throw GradleException("Plugin trust root must use root schema version 1")
    }
    if (exactPluginTrustRootInteger(signed["version"], "version") <= 0) {
        throw GradleException("Plugin trust root version must be a positive integer")
    }
    val expires = try {
        Instant.parse(signed["expires"] as? String
            ?: throw GradleException("Plugin trust root is missing expires"))
    } catch (exception: RuntimeException) {
        if (exception is GradleException) throw exception
        throw GradleException("Plugin trust root expires must be an ISO-8601 instant", exception)
    }
    if (!expires.isAfter(Instant.now())) {
        throw GradleException("Plugin trust root has expired")
    }
    val keys = signed["keys"] as? Map<*, *>
        ?: throw GradleException("Plugin trust root is missing keys")
    if (keys.keys.any { it !is String || !it.matches(Regex("ed25519:[0-9a-f]{64}")) }) {
        throw GradleException("Plugin trust root must use named Ed25519 public keys")
    }
    keys.forEach { (keyId, value) ->
        val declaration = value as? Map<*, *>
            ?: throw GradleException("Plugin trust root key $keyId must be an object")
        val publicKey = declaration["publicKey"] as? String
        if (declaration["keyType"] != "ed25519" || declaration["scheme"] != "ed25519"
            || publicKey.isNullOrBlank()) {
            throw GradleException("Plugin trust root key $keyId must contain an Ed25519 public key")
        }
        val computedKeyId = try {
            val encoded = Base64.getDecoder().decode(publicKey)
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
            "ed25519:" + HexFormat.of().formatHex(digest("SHA-256", encoded))
        } catch (exception: Exception) {
            throw GradleException("Plugin trust root key $keyId is not a valid Ed25519 public key", exception)
        }
        if (computedKeyId != keyId) {
            throw GradleException("Plugin trust root key ID does not match its public key: $keyId")
        }
    }
    val roles = signed["roles"] as? Map<*, *>
        ?: throw GradleException("Plugin trust root is missing roles")
    val onlineRoleNames = setOf(
        "official-repository",
        "repository-attestor",
        "artifact-attestor",
        "trust-status",
    )
    val parsedRoles = mutableMapOf<String, Set<String>>()
    val assignedKeyIds = mutableSetOf<String>()
    roles.forEach { (roleNameValue, roleValue) ->
        val roleName = roleNameValue as? String
            ?: throw GradleException("Plugin trust root contains a non-string role name")
        if (roleName.isBlank()) {
            throw GradleException("Plugin trust root contains a blank role name")
        }
        val role = roleValue as? Map<*, *>
            ?: throw GradleException("Plugin trust root role $roleName must be an object")
        val keyIds = (role["keyIds"] as? List<*>)?.map {
            it as? String ?: throw GradleException("Plugin trust role $roleName has a non-string key ID")
        } ?: throw GradleException("Plugin trust role $roleName is missing keyIds")
        val distinctKeyIds = keyIds.toSet()
        val threshold = exactPluginTrustRootInteger(role["threshold"], "role $roleName threshold")
        if (keyIds.isEmpty() || distinctKeyIds.size != keyIds.size
            || threshold < 1 || threshold > keyIds.size || !keys.keys.containsAll(distinctKeyIds)) {
            throw GradleException("Plugin trust role $roleName has an invalid threshold or key reference")
        }
        if (roleName in onlineRoleNames) {
            if (threshold != 1) {
                throw GradleException("Online plugin trust role $roleName must use threshold one")
            }
            if (distinctKeyIds.any { it in assignedKeyIds }) {
                throw GradleException("Online plugin trust roles must not reuse signing keys")
            }
            assignedKeyIds.addAll(distinctKeyIds)
        }
        parsedRoles[roleName] = distinctKeyIds
    }

    if (requireOfficialRepository && "official-repository" !in parsedRoles) {
        throw GradleException("Configured plugin trust root is missing role: official-repository")
    }
    val certificationRoleNames = setOf("repository-attestor", "artifact-attestor", "trust-status")
    val certificationRoleCount = certificationRoleNames.count(parsedRoles::containsKey)
    if (certificationRoleCount != 0 && certificationRoleCount != certificationRoleNames.size) {
        throw GradleException("Plugin trust certification roles must all be present or all be absent")
    }

    val statusUrl = signed["statusUrl"] as? String
        ?: throw GradleException("Plugin trust root is missing statusUrl")
    if (certificationRoleCount == 0) {
        if (statusUrl.isNotBlank()) {
            throw GradleException("Plugin trust root statusUrl must be blank without certification roles")
        }
    } else {
        val statusUri = try {
            URI(statusUrl)
        } catch (exception: RuntimeException) {
            throw GradleException("Plugin trust root statusUrl is invalid", exception)
        }
        if (!statusUri.scheme.equals("https", ignoreCase = true) || statusUri.host.isNullOrBlank()
            || statusUri.userInfo != null || statusUri.fragment != null) {
            throw GradleException("Plugin trust root statusUrl must be an absolute credential-free HTTPS URL")
        }
    }
}

val createPluginTrustRoot = tasks.register("createPluginTrustRoot") {
    val configuredRoot = providers.environmentVariable("AURA_PLUGIN_ROOT_JSON")
    val developmentRoot = rootProject.layout.projectDirectory.file("config/aura-plugin-root.development.json")
    inputs.property("configuredPluginTrustRoot", configuredRoot.orElse(""))
    inputs.file(developmentRoot)
    outputs.file(pluginTrustRootFile)

    doLast {
        val configured = configuredRoot.orNull?.takeIf(String::isNotBlank)
        val contents = configured ?: developmentRoot.asFile.readText(Charsets.UTF_8)
        validatePluginTrustRoot(contents, requireOfficialRepository = configured != null)
        val target = pluginTrustRootFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(contents.trim() + "\n", Charsets.UTF_8)
    }
}
val createPropertiesFile = tasks.register("createPropertiesFile") {
    outputs.file(hmclPropertiesFile)
    hmclProperties.forEach { (k, v) -> inputs.property(k, v) }

    doLast {
        val targetFile = hmclPropertiesFile.get().asFile
        targetFile.parentFile.mkdir()
        targetFile.bufferedWriter().use {
            for ((k, v) in hmclProperties) {
                it.write("$k=$v\n")
            }
        }
    }
}

tasks.shadowJar {
    dependsOn(createPropertiesFile)

    mergeServiceFiles()

    archiveBaseName.set("Aura-Launcher")
    archiveClassifier.set(null as String?)

    exclude("**/package-info.class")
    exclude("META-INF/maven/**")

    exclude("META-INF/services/javax.imageio.spi.ImageReaderSpi")
    exclude("META-INF/services/javax.imageio.spi.ImageInputStreamSpi")

    listOf(
        "aix-*", "sunos-*", "openbsd-*", "dragonflybsd-*", "freebsd-*", "linux-*",
        "*-ppc", "*-ppc64le", "*-s390x", "*-armel",
    ).forEach { exclude("com/sun/jna/$it/**") }

    minimize {
        exclude(dependency("com.google.code.gson:.*:.*"))
        exclude(dependency("com.google.guava:guava:.*"))
        exclude(dependency("org.spongepowered:mixin:.*"))
        exclude(dependency("org.ow2.asm:.*:.*"))
        exclude(dependency("net.java.dev.jna:jna:.*"))
        exclude(dependency("libs:JFoenix:.*"))
        exclude(project(":AuraBoot"))
    }

    manifest.attributes(
        "Created-By" to "Copyright(c) 2013-2025 huangyuhui.",
        "Implementation-Version" to project.version.toString(),
        "Main-Class" to "org.jackhuang.hmcl.Main",
        "Premain-Class" to "org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinAgent",
        "Can-Redefine-Classes" to "false",
        "Can-Retransform-Classes" to "false",
        "Multi-Release" to "true",
        "Add-Opens" to addOpens.joinToString(" "),
        "Enable-Native-Access" to "ALL-UNNAMED",
        "Enable-Final-Field-Mutation" to "ALL-UNNAMED",
    )

    if (launcherExe.isNotBlank()) {
        into("assets") {
            from(file(launcherExe))
        }
    }

}

/// The Shadow JAR is Aura Launcher's distributable Java payload; resolve its path after archive naming is configured.
val jarPath = tasks.shadowJar.get().archiveFile.get().asFile

val pluginSystemLegalResources = mapOf(
    "META-INF/licenses/Aura-Plugin-System-LICENSE.txt" to pluginSystemDirectory.file("LICENSE").asFile,
    "META-INF/notices/Aura-Plugin-System-NOTICE.txt" to pluginSystemDirectory.file("NOTICE").asFile,
)

fun verifyJarEntries(jar: File, expectedEntries: Map<String, File>) {
    ZipFile(jar).use { zipFile ->
        for ((entryPath, canonicalFile) in expectedEntries) {
            val matchingEntries = zipFile.stream().filter { it.name == entryPath }.toList()
            if (matchingEntries.size != 1) {
                throw GradleException("Expected one $entryPath in ${jar.name}; found ${matchingEntries.size}")
            }
            val entry = matchingEntries.single()
            val packagedBytes = zipFile.getInputStream(entry).use { it.readBytes() }
            if (!packagedBytes.contentEquals(canonicalFile.readBytes())) {
                throw GradleException("$entryPath does not match ${canonicalFile.relativeTo(rootProject.rootDir)}")
            }
        }
    }
}

tasks.shadowJar {
    doLast {
        attachSignature(jarPath)
        verifyJarEntries(jarPath, pluginSystemLegalResources)
        createChecksum(jarPath)
    }
}

tasks.processResources {
    dependsOn(createPropertiesFile)
    dependsOn(createPluginTrustRoot)
    dependsOn(upsideDownTranslate)
    dependsOn(createLocaleNamesResourceBundle)
    dependsOn(createLanguageList)

    into("assets/") {
        from(hmclPropertiesFile)
        from(embedResources)
        from(pluginTrustRootFile)
    }

    into("assets/lang") {
        from(createLanguageList.map { it.outputFile })
        from(upsideDownTranslate.map { it.outputFile })
        from(createLocaleNamesResourceBundle.map { it.outputDirectory })
    }

    into("META-INF/licenses") {
        from(pluginSystemDirectory.file("LICENSE")) {
            rename { "Aura-Plugin-System-LICENSE.txt" }
        }
    }

    into("META-INF/notices") {
        from(pluginSystemDirectory.file("NOTICE")) {
            rename { "Aura-Plugin-System-NOTICE.txt" }
        }
    }

    inputs.property("terracotta_version", libs.versions.terracotta)
    doLast {
        upgradeTerracottaConfig.get().checkValid()
    }
}

fun artifactFile(ext: String) = jarPath.resolveSibling(jarPath.nameWithoutExtension + '.' + ext)

val makeExecutables = tasks.register("makeExecutables") {
    val extensions = listOf("exe", "sh")

    dependsOn(tasks.shadowJar)

    inputs.file(jarPath)
    outputs.files(extensions.map { artifactFile(it) })

    doLast {
        val jarContent = jarPath.readBytes()

        ZipFile(jarPath).use { zipFile ->
            for (extension in extensions) {
                val output = artifactFile(extension)
                val entry = zipFile.getEntry("assets/HMCLauncher.$extension")
                    ?: throw GradleException("HMCLauncher.$extension not found")

                output.outputStream().use { outputStream ->
                    zipFile.getInputStream(entry).use { it.copyTo(outputStream) }
                    outputStream.write(jarContent)
                }

                createChecksum(output)
            }
        }
    }
}

val makeDeb = tasks.register("makeDeb", CreateDeb::class) {
    dependsOn(makeExecutables)

    val debFile = layout.file(provider { artifactFile("deb") })

    val debChannel = when (versionType) {
        "stable" -> ReleaseType.STABLE
        "dev" -> ReleaseType.DEVELOPMENT
        else -> ReleaseType.NIGHTLY
    }

    version.set(project.version.toString())
    releaseType.set(debChannel)
    launcherClassName.set("org.jackhuang.hmcl.Launcher")
    appShFile.set(layout.file(provider { artifactFile("sh") }))
    iconFile.set(layout.projectDirectory.file("image/hmcl.png"))
    outputFile.set(debFile)

    doLast {
        createChecksum(debFile.get().asFile)
    }
}

tasks.build {
    dependsOn(makeExecutables)
    dependsOn(makeDeb)
}

fun parseToolOptions(options: String?): MutableList<String> {
    if (options == null)
        return mutableListOf()

    val builder = StringBuilder()
    val result = mutableListOf<String>()

    var offset = 0

    loop@ while (offset < options.length) {
        val ch = options[offset]
        if (Character.isWhitespace(ch)) {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }

            while (offset < options.length && Character.isWhitespace(options[offset])) {
                offset++
            }

            continue@loop
        }

        if (ch == '\'' || ch == '"') {
            offset++

            while (offset < options.length) {
                val ch2 = options[offset++]
                if (ch2 != ch) {
                    builder.append(ch2)
                } else {
                    continue@loop
                }
            }

            throw GradleException("Unmatched quote in $options")
        }

        builder.append(ch)
        offset++
    }

    if (builder.isNotEmpty()) {
        result += builder.toString()
    }

    return result
}

// For IntelliJ IDEA
tasks.withType<JavaExec> {
    if (name != "run") {
        jvmArgs(addOpens.map { "--add-opens=$it=ALL-UNNAMED" })
//        if (javaVersion >= JavaVersion.VERSION_24) {
//            jvmArgs("--enable-native-access=ALL-UNNAMED")
//        }
    }
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.shadowJar)

    group = "application"

    classpath = files(jarPath)
    workingDir = rootProject.rootDir

    val vmOptions = parseToolOptions(System.getenv("HMCL_JAVA_OPTS") ?: "-Xmx1g")
    if (vmOptions.none { it.startsWith("-Dhmcl.offline.auth.restricted=") })
        vmOptions += "-Dhmcl.offline.auth.restricted=false"

    jvmArgs(vmOptions)

    val hmclJavaHome = System.getenv("HMCL_JAVA_HOME")
    if (hmclJavaHome != null) {
        this.executable(
            file(hmclJavaHome).resolve("bin")
                .resolve(if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java")
        )
    }

    doFirst {
        logger.quiet("HMCL_JAVA_OPTS: {}", vmOptions)
        logger.quiet("HMCL_JAVA_HOME: {}", hmclJavaHome ?: System.getProperty("java.home"))
    }
}

// terracotta

val upgradeTerracottaConfig = tasks.register<TerracottaConfigUpgradeTask>("upgradeTerracottaConfig") {
    val destination = layout.projectDirectory.file("src/main/resources/assets/terracotta.json")
    val source = layout.projectDirectory.file("terracotta-template.json");

    classifiers.set(
        listOf(
            "windows-x86_64", "windows-arm64",
            "macos-x86_64", "macos-arm64",
            "linux-x86_64", "linux-arm64", "linux-loongarch64", "linux-riscv64",
            "freebsd-x86_64"
        )
    )

    version.set(libs.versions.terracotta)
    downloadURL.set($$"https://github.com/burningtnt/Terracotta/releases/download/v${version}/terracotta-${version}-${classifier}-pkg.tar.gz")

    templateFile.set(source)
    outputFile.set(destination)
}

// Check Translations

tasks.register<CheckTranslations>("checkTranslations") {
    val dir = layout.projectDirectory.dir("src/main/resources/assets/lang")

    englishFile.set(dir.file("I18N.properties"))
    simplifiedChineseFile.set(dir.file("I18N_zh_CN.properties"))
    traditionalChineseFile.set(dir.file("I18N_zh.properties"))
    classicalChineseFile.set(dir.file("I18N_lzh.properties"))
}

// l10n

val generatedDir = layout.buildDirectory.dir("generated")

val upsideDownTranslate = tasks.register<UpsideDownTranslate>("upsideDownTranslate") {
    inputFile.set(layout.projectDirectory.file("src/main/resources/assets/lang/I18N.properties"))
    outputFile.set(generatedDir.map { it.file("generated/i18n/I18N_en_Qabs.properties") })
}

val createLanguageList = tasks.register<CreateLanguageList>("createLanguageList") {
    resourceBundleDir.set(layout.projectDirectory.dir("src/main/resources/assets/lang"))
    resourceBundleBaseName.set("I18N")
    additionalLanguages.set(listOf("en-Qabs"))
    outputFile.set(generatedDir.map { it.file("languages.json") })
}

val createLocaleNamesResourceBundle = tasks.register<CreateLocaleNamesResourceBundle>("createLocaleNamesResourceBundle") {
    dependsOn(createLanguageList)

    languagesFile.set(createLanguageList.flatMap { it.outputFile })
    outputDirectory.set(generatedDir.map { it.dir("generated/LocaleNames") })
}

// mcmod data

tasks.register<ParseModDataTask>("parseModData") {
    inputFile.set(layout.projectDirectory.file("mod.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/mod_data.txt"))
}

tasks.register<ParseModDataTask>("parseModPackData") {
    inputFile.set(layout.projectDirectory.file("modpack.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/modpack_data.txt"))
}
