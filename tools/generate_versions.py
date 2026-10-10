import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD_VERSION = "1.3.1"

TARGETS = {
    "1.21.4": ("1.21.4", ">=1.21.4 <1.21.5", "1.13-SNAPSHOT", "8.14.1", 21, "0.17.3", "0.119.4+1.21.4", "1.7.3+1.21.4-fabric", "13.0.4", True),
    "1.21.5": ("1.21.5", ">=1.21.5 <1.21.6", "1.13-SNAPSHOT", "8.14.1", 21, "0.17.3", "0.128.2+1.21.5", "1.9.3+1.21.5-fabric", "14.0.2", True),
    "1.21.6-1.21.8": ("1.21.8", ">=1.21.6 <1.21.9", "1.13-SNAPSHOT", "8.14.1", 21, "0.17.3", "0.136.1+1.21.8", "1.7.5+1.21.6-fabric", "15.0.2", True),
    "1.21.9-1.21.10": ("1.21.10", ">=1.21.9 <1.21.11", "1.13-SNAPSHOT", "8.14.1", 21, "0.17.3", "0.138.4+1.21.10", "1.8.3+1.21.9-fabric", "16.0.1", True),
    "1.21.11": ("1.21.11", ">=1.21.11 <1.21.12", "1.14-SNAPSHOT", "9.2.1", 21, "0.18.2", "0.141.6+1.21.11", "1.9.2+1.21.11-fabric", "17.0.1", True),
    "26.1": ("26.1.2", ">=26.1 <26.2", "1.16-SNAPSHOT", "9.4.0", 25, "0.18.6", "0.155.3+26.1.2", "1.9.3+26.1-fabric", "18.0.2", False),
    "26.2": ("26.2", ">=26.2 <26.3", "1.17-SNAPSHOT", "9.5.1", 25, "0.19.3", "0.161.0+26.2", "1.9.3+26.2-fabric", "20.0.3", False),
    "26.3": ("26.3", ">=26.3 <26.4", "1.17-SNAPSHOT", "9.7.1", 25, "0.19.5", "0.161.0+26.3", "1.9.3+26.3-fabric", "21.0.0", False),
}

SETTINGS = """pluginManagement {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        gradlePluginPortal()
    }
}

rootProject.name = 'FastImageFrames'
"""

BUILD_OBFUSCATED = """plugins {
    id 'fabric-loom' version "${loom_version}"
}

version = mod_version
group = 'fastimageframes'

base {
    archivesName = "FastImageFrames-${target_name}-fabric"
}

repositories {
    maven { url "https://maven.terraformersmc.com/releases/" }
    maven { url "https://api.modrinth.com/maven" }
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"
    modImplementation include("maven.modrinth:midnightlib:${midnightlib_version}")
    modCompileOnly "com.terraformersmc:modmenu:${modmenu_version}"
    findProperty('imageframeclient_jar')?.with { modLocalRuntime files(it) }
}

apply from: '../../gradle/common.gradle'
"""

BUILD_UNOBFUSCATED = """plugins {
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

version = mod_version
group = 'fastimageframes'

base {
    archivesName = "FastImageFrames-${target_name}-fabric"
}

repositories {
    maven { url "https://maven.terraformersmc.com/releases/" }
    maven { url "https://api.modrinth.com/maven" }
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"
    implementation include("maven.modrinth:midnightlib:${midnightlib_version}")
    compileOnly "com.terraformersmc:modmenu:${modmenu_version}"
    findProperty('imageframeclient_jar')?.with { localRuntime files(it) }
}

apply from: '../../gradle/common.gradle'
"""

src_wrapper = os.path.join(ROOT, "gradle", "wrapper")
for name, (mc, dep, loom, gradle, java, loader, fapi, midnight, modmenu, obf) in TARGETS.items():
    d = os.path.join(ROOT, "versions", name)
    os.makedirs(os.path.join(d, "gradle", "wrapper"), exist_ok=True)
    shutil.copy(os.path.join(ROOT, "gradlew"), d)
    shutil.copy(os.path.join(ROOT, "gradlew.bat"), d)
    shutil.copy(os.path.join(src_wrapper, "gradle-wrapper.jar"), os.path.join(d, "gradle", "wrapper"))
    with open(os.path.join(d, "gradle", "wrapper", "gradle-wrapper.properties"), "w", newline="\n") as f:
        f.write("distributionBase=GRADLE_USER_HOME\n"
                "distributionPath=wrapper/dists\n"
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle}-bin.zip\n"
                "networkTimeout=10000\n"
                "validateDistributionUrl=true\n"
                "zipStoreBase=GRADLE_USER_HOME\n"
                "zipStorePath=wrapper/dists\n")
    with open(os.path.join(d, "settings.gradle"), "w", newline="\n") as f:
        f.write(SETTINGS)
    with open(os.path.join(d, "gradle.properties"), "w", newline="\n") as f:
        f.write("org.gradle.jvmargs=-Xmx2G\n"
                "org.gradle.parallel=true\n\n"
                f"target_name={name}\n"
                f"mod_version={MOD_VERSION}\n"
                f"minecraft_version={mc}\n"
                f"minecraft_dependency={dep}\n"
                f"java_version={java}\n"
                f"loom_version={loom}\n"
                f"loader_version={loader}\n"
                f"fabric_api_version={fapi}\n"
                f"midnightlib_version={midnight}\n"
                f"modmenu_version={modmenu}\n")
    with open(os.path.join(d, "build.gradle"), "w", newline="\n") as f:
        f.write(BUILD_OBFUSCATED if obf else BUILD_UNOBFUSCATED)
print("generated", ", ".join(TARGETS))
