import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.api.ApiJar

@CompileStatic
class CompileAvoidance implements Plugin<Project> {
    void apply(Project project) {
        ApiExtension extension = (ApiExtension) project.extensions.create("api", ApiExtension)
        PlatformsExtension platforms = (PlatformsExtension) project.extensions.create("platforms", PlatformsExtension)

        project.afterEvaluate {
            List<String> targetPlatforms = platforms.targetPlatforms as List
            if (!targetPlatforms) {
                targetPlatforms << 'java7'
            }
            def compileConfiguration = project.configurations.getByName('compile')
            List<JavaCompile> compileTasks = project.tasks.withType(JavaCompile).collect()
            compileTasks.each { JavaCompile compileTask ->
                targetPlatforms.each { String platform ->
                    def capitalizedPlatform = platform.capitalize()
                    def taskName = "${compileTask.name}$capitalizedPlatform"
                    def compilePlatformConfiguration = project.configurations.findByName("compileClasspath${ capitalizedPlatform}")?:project.configurations.create("compileClasspath${capitalizedPlatform}")
                    compilePlatformConfiguration.attributes(type: 'api', platform: platform)
                    compilePlatformConfiguration.extendsFrom compileConfiguration
                    def platformCompile = project.tasks.create(taskName, JavaCompile, new Action<JavaCompile>() {
                        @Override
                        void execute(final JavaCompile task) {
                            task.options.fork = true
                            def level = "1.${platform - 'java'}"
                            task.options.forkOptions.executable = "/opt/jdk${level}.0/bin/javac"
                            task.sourceCompatibility = level
                            task.targetCompatibility = level
                            task.source = compileTask.source
                            task.destinationDir = project.file("$project.buildDir/classes/$platform/$taskName")
                            task.classpath = compilePlatformConfiguration
                        }
                    })
                    def apiJar = project.tasks.create("${taskName}ApiJar", ApiJar, { ApiJar apiJar ->
                        apiJar.outputFile = project.file("$project.buildDir/api/${project.name}-${taskName}.jar")
                        apiJar.inputs.dir(platformCompile.destinationDir).skipWhenEmpty()
                        apiJar.exportedPackages = extension.exports
                        apiJar.dependsOn(platformCompile)
                    } as Action)
                    project.artifacts.add(compilePlatformConfiguration.name, [file: apiJar.outputFile, builtBy: apiJar])

                    if (!compileTask.name.contains('Test')) {
                        def jar = project.tasks.create("${platform}Jar", Jar) { Jar jar ->
                            jar.from project.files(platformCompile.destinationDir)
                            jar.destinationDir = project.file("$project.buildDir/libs")
                            jar.classifier = platform
                            jar.dependsOn(platformCompile)
                        }
                        project.tasks.getByName('build').dependsOn jar
                    }
                }
            }

        }

    }

}
