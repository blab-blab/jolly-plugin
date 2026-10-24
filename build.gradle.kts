plugins { java }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.3 is a beta at time of writing; pin to the build your server runs
    compileOnly("io.papermc.paper:paper-api:26.3.build.143-beta")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
