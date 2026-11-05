plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.hibernate.orm") version "7.4.5.Final"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
}

extra["springModulithVersion"] = "2.1.1"

group = "dev.jacobandersen"
version = file("version.txt").readText().trim()
description = "bastion"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "Microformats2GitHubPackages"
        url = uri("https://maven.pkg.github.com/jacobsandersen/microformats2")
        credentials {
            username = System.getenv("PACKAGES_USER") ?: System.getenv("GITHUB_ACTOR") ?: (project.findProperty("gpr.user") as String?)
            password = System.getenv("PACKAGES_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: (project.findProperty("gpr.token") as String?)
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.apache.logging.log4j:log4j-bom:2.26.1")
        mavenBom("org.springframework.modulith:spring-modulith-bom:${extra["springModulithVersion"]}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("dev.jacobandersen:microformats2:0.1.2")
    implementation("org.jobrunr:jobrunr-spring-boot-4-starter:9.0.2")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("com.github.slugify:slugify:4.0.1")
    implementation("com.bucket4j:bucket4j_jdk17-redis-common:8.14.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")
    implementation("io.lettuce:lettuce-core")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    runtimeOnly("com.github.slugify:slugify") {
        capabilities {
            requireCapability("com.github.slugify:slugify-transliterator")
        }
    }
    implementation("software.amazon.awssdk:s3:2.10.53")
    implementation("org.nibor.autolink:autolink:0.12.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.rometools:rome:2.1.0")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.0")
    testImplementation("org.awaitility:awaitility")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

hibernate {
    enhancement {
    }
}

tasks.bootJar {
    archiveFileName.set("bastion.jar")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ktlint {
    version.set("1.8.0")
}
