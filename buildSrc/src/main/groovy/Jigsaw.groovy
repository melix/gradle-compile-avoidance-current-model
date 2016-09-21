import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.api.ApiJar
import org.gradle.external.javadoc.CoreJavadocOptions

@CompileStatic
class Jigsaw implements Plugin<Project> {

    void apply(Project project) {
        ApiExtension apiExtension = (ApiExtension) project.extensions.create("api", ApiExtension)
        def configurer = new Configurer(project)
        PlatformsExtension platformsExtension = (PlatformsExtension) project.extensions.create("platforms", PlatformsExtension, configurer)
        // This shouldn't probably be done automatically, it's for demo time
        platformsExtension.targetPlatforms("java${JavaVersion.current().majorVersion}".toString())
    }

    /**
     * We're using a configurer class here, that will be called whenever the platforms.targetPlatforms extension method is called.
     * We have to do this to avoid calling `afterEvaluate` everwhere. But it also means that the order of blocks in a build script
     * are important. `api` must come before `platforms`, which must come before `configurations` (at least configuration blocks that
     * reference configurations created by this plugin)
     */
    public static class Configurer {
        final Project project

        public Configurer(Project project) {
            this.project = project
        }

        ApiExtension getApiExtension() {
            (ApiExtension) project.extensions.getByName('api')
        }

        PlatformsExtension getPlatformsExtension() {
            (PlatformsExtension) project.extensions.getByName('platforms')
        }

        public void configurePlatform(String platform) {
            doConfigurePlatform((JavaCompile) project.tasks.getByName('compileJava'), platform)
        }

        private void doConfigurePlatform(JavaCompile compileTask, String platform) {
            def capitalizedPlatform = platform.capitalize()
            def compileConfiguration = project.configurations.getByName('compile')
            def taskName = "${compileTask.name}$capitalizedPlatform"
            def compilePlatformConfiguration = project.configurations.findByName("compileClasspath${ capitalizedPlatform}")?:project.configurations.create("compileClasspath${capitalizedPlatform}")
            compilePlatformConfiguration.attributes(type: 'api', platform: platform)
            compilePlatformConfiguration.extendsFrom compileConfiguration
            def runtimePlatformConfiguration = project.configurations.findByName("runtime${capitalizedPlatform}")?:project.configurations.create("runtime${capitalizedPlatform}")
            runtimePlatformConfiguration.attributes(type: 'runtime', platform: platform)
            runtimePlatformConfiguration.extendsFrom compileConfiguration
            // for each platform we're creating a new JavaCompile task which is going to fork and use
            // the specified JDK
            def platformCompile = project.tasks.create(taskName, JavaCompile, new Action<JavaCompile>() {
                @Override
                void execute(final JavaCompile task) {
                    task.options.fork = true
                    def level = "1.${platform - 'java'}"
                    String jdkHome = getPlatformsExtension().jdkFor(platform)
                    task.options.forkOptions.executable = "$jdkHome/bin/javac"
                    task.sourceCompatibility = level
                    task.targetCompatibility = level
                    task.source(compileTask.source)
                    // each platform compile defines a JDK specific source directory
                    // which will be useful when we deal with multi-release jars
                    task.source(project.file("src/main/$platform"))
                    task.destinationDir = project.file("$project.buildDir/classes/$platform/$taskName")
                    task.classpath = compilePlatformConfiguration
                }
            })
            // in addition we create an API jar task, and we use it as the artifact attached to the "api" configuration
            def apiJar = project.tasks.create("${taskName}ApiJar", ApiJar, { ApiJar apiJar ->
                apiJar.outputFile = project.file("$project.buildDir/api/${project.name}-${taskName}.jar")
                apiJar.inputs.dir(platformCompile.destinationDir).skipWhenEmpty()
                apiJar.exportedPackages = apiExtension.exports
                apiJar.dependsOn(platformCompile)
            } as Action)
            project.artifacts.add(compilePlatformConfiguration.name, [file: apiJar.outputFile, builtBy: apiJar])

            if (!compileTask.name.contains('Test')) {
                // we also need to create "jar" for each platform
                def jar = project.tasks.create("${platform}Jar", Jar) { Jar jar ->
                    jar.from project.files(platformCompile.destinationDir)
                    jar.destinationDir = project.file("$project.buildDir/libs")
                    jar.classifier = platform
                    jar.dependsOn(platformCompile)
                }
                project.tasks.getByName('build').dependsOn jar
                project.artifacts.add(runtimePlatformConfiguration.name, [file: jar.archivePath, builtBy: jar])
            }

            // and a Javadoc task
            def javadocTask = project.tasks.findByName('javadoc')
            def platformJavadoc = project.tasks.create("${platform}Javadoc", Javadoc) { Javadoc javadoc ->
              javadoc.source = platformCompile.source
              javadoc.classpath = platformCompile.classpath
              javadoc.executable = "${getPlatformsExtension().jdkFor(platform)}/bin/javadoc"
              javadoc.destinationDir = project.file("${project.buildDir}/docs/${platform}Javadoc")
            }

            if (platform=='java9' && apiExtension.moduleName) {
                // if the target platform is Java 9 and that we defined a module name in the `api` extension
                // then we're asking Gradle to generate a module file for us
                addJigsawModuleFile(taskName, platformCompile, apiExtension)
                ((CoreJavadocOptions)platformJavadoc.options).addStringOption('-html5')
            }
        }

        private void addJigsawModuleFile(String taskName, JavaCompile platformCompile, ApiExtension extension) {
            // first let's define an additional generated sources directory where to put the module descriptor
            def genDir = new File("$project.buildDir/generates-sources/${taskName}/src/main/jigsaw")
            platformCompile.source(project.files(genDir))
            platformCompile.inputs.properties(exports: extension.exports)
            // as soon as `module-info.java` is on compile classpath, `modulepath` has to be used: even if jars are
            // found on classpath, they will trigger a ClassNotFoundError during compilation unless the dependency
            // is declared in the module path. In short: classpath is ignored.
            platformCompile.options.compilerArgs.addAll(['-modulepath', platformCompile.classpath.asPath])
            platformCompile.doFirst {
                // here is the module file generation
                genDir.mkdirs()
                def requires = project.configurations.getByName('compile').files.collect { "   requires ${automaticModule(it.name)};" }.join('\n')
                def exports = extension.exports.collect { "    exports $it;" }.join('\n')
                new File(genDir, 'module-info.java').write("""module ${extension.moduleName} {
${requires}
${exports}
}""")
            }
        }

        /**
         * This nasty method tries to simulate the algorithm used by Jigsaw for "automatic modules". That is to say,
         * if a jar is on module path but this jar is *not* a module, then a module name is inferred from the name
         * of the jar (!). There are discussions with the Maven PMC about how to put that information inside jars
         * instead, and Maven pushes towards using the `pom.xml` file that they embed into jars. Anyway, if it's
         * a module, we should theorically use the module name as declared in `module-info.class`, whereas when
         * it's not a module, we need to infer the module name from the filename.
         */
        private static String automaticModule(String name) {
             int idx = name.lastIndexOf('-');
             if (idx>0) { name = name.substring(0,idx) }
             name.replace('-', '.')
        }
    }


}
