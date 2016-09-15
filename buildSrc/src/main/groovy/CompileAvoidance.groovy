import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.api.ApiJar

@CompileStatic
class CompileAvoidance implements Plugin<Project> {
    void apply(Project project) {
        ApiExtension extension = (ApiExtension) project.extensions.create("api", ApiExtension)
        def compileConfiguration = project.configurations.getByName('compileClasspath')
        compileConfiguration.attributes(type: 'api')
        project.tasks.withType(JavaCompile).all { JavaCompile compileTask ->
            def apiJar = project.tasks.create("${compileTask.name}ApiJar", ApiJar, { ApiJar apiJar ->
                apiJar.outputFile = project.file("$project.buildDir/api/${project.name}-${compileTask.name}.jar")
                apiJar.inputs.dir(compileTask.destinationDir).skipWhenEmpty()
                apiJar.exportedPackages = [] as Set
                apiJar.doFirst {
                    apiJar.exportedPackages = extension.exports
                }
                apiJar.dependsOn(compileTask)
            } as Action)
            attachArtifact(project, apiJar)
        }
    }

    @CompileDynamic
    void attachArtifact(Project p, ApiJar jar) {
        p.artifacts {
            compileClasspath file: jar.outputFile, builtBy: jar
        }
    }

}
