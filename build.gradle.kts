plugins {
    id("java")
    id("jvm-test-suite")
    id("com.google.protobuf") version "0.10.0"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
    application
}

group = "dps"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val grpcVersion = "1.81.0"
val protobufVersion = "4.34.1"

dependencies {
    // Protocol Buffers
    implementation("com.google.protobuf:protobuf-java:${protobufVersion}")

    // gRPC
    runtimeOnly("io.grpc:grpc-netty-shaded:${grpcVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")

    // Spring library
    implementation("org.springframework.boot:spring-boot-starter-web")

    // For asynchronous Spring clients
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }

    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }

    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("org.assertj:assertj-core:3.27.7")
                implementation("org.mockito:mockito-core:5.14.2")
                implementation("org.mockito:mockito-junit-jupiter:5.14.2")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-starter-webmvc-test")
                implementation("io.moquette:moquette-broker:0.17") {
                    exclude(group = "org.slf4j", module = "slf4j-reload4j")
                }
            }
            targets {
                all {
                    testTask.configure {
                        finalizedBy(tasks.jacocoTestReport)
                    }
                }
            }
        }
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) {
                exclude("proto/generated/**",
                    "dps/sensor/Simulator.class",
                    "dps/sensor/MonitoringSensor.class",
                    "dps/sensor/Measurement.class",
                    "dps/Main.class",
                    "dps/pline/ProductionLine.class"
                )
            }
        }
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

application {
    mainClass = "dps.Main"
}


