plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports { mavenBom(libs.spring.modulith.bom.get().toString()) }
}

// Developer portal (US-119): the API contract and the static portal page are packaged as classpath resources under
// developer/, taken from docs/api at build time, so there is one copy of the contract in the repository.
tasks.named<Copy>("processResources") {
    from(rootProject.layout.projectDirectory.dir("docs/api")) {
        include("openapi.yaml")
        into("developer")
    }
    from(rootProject.layout.projectDirectory.dir("docs/api/portal")) {
        into("developer")
    }
}

dependencies {
    implementation(project(":backend:calc"))
    implementation(project(":backend:ledger-core"))
    implementation(project(":backend:kernel"))
    implementation(project(":backend:lending-core"))
    implementation(project(":backend:integration-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    // Core Testcontainers only: its coordinates and GenericContainer API are stable across TC 1.x and 2.x.
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit)
}
