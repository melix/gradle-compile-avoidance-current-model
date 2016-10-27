package me.champeau.gradle.ca

import groovy.transform.CompileStatic

@CompileStatic
class PlatformsExtension {
    final CompileAvoidance.Configurer configurer
    final Set<String> targetPlatforms = []
    final Map<String, String> jdks = [:]

    PlatformsExtension(CompileAvoidance.Configurer configurer) {
        this.configurer = configurer
    }

    void targetPlatforms(String... platforms) {
        platforms.each { String it ->
            if (targetPlatforms.add(it)) {
                configurer.configurePlatform(it)
            }
        }
    }

    String jdkFor(String platform) {
        def level = "1.${platform - 'java'}"
        jdks[platform]?:"/opt/jdk${level}.0"
    }
}
