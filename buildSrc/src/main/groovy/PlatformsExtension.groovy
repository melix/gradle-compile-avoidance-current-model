import groovy.transform.CompileStatic

@CompileStatic
class PlatformsExtension {
    final Set<String> targetPlatforms = []
    final Map<String, String> jdks = [:]

    void targetPlatforms(String... platforms) {
        platforms.each { String it ->
            targetPlatforms.add(it)
        }
    }

    String jdkFor(String platform) {
        def level = "1.${platform - 'java'}"
        jdks[platform]?:"/opt/jdk${level}.0"
    }
}
