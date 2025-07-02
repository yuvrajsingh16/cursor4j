plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.intellij.ai"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.7.0")
}

intellij {
    version.set("2023.3")
    type.set("IC") // IntelliJ IDEA Community Edition
    
    plugins.set(listOf(
        "com.intellij.java",
        "Git4Idea",
        "org.jetbrains.kotlin"
    ))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("241.*")
    }
    
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

tasks.test {
    useJUnitPlatform()
}