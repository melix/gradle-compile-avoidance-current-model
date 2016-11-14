package me.champeau.gradle.ca

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions
import org.gradle.jvm.tasks.api.ApiJar

@CompileStatic
class CompileAvoidance implements Plugin<Project> {

    void apply(Project project) {
        ApiExtension apiExtension = (ApiExtension) project.extensions.create("api", ApiExtension)
        def apiConfiguration = project.configurations.create("api")
        apiConfiguration.transitive = false
        apiConfiguration.canBeResolved = false
        apiConfiguration.canBeConsumed = false
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
            def apiConfiguration = project.configurations.getByName('api')
            def compileConfiguration = project.configurations.getByName('compile')
            compileConfiguration.extendsFrom apiConfiguration
            // should really be a bucket too, but it causes issues with IDEA plugin
            //compileConfiguration.asBucket()

            // Configurations for platform specific dependencies
            def platformApiConfiguration = project.configurations.create("api${capitalizedPlatform}")
            platformApiConfiguration.canBeResolved = false
            platformApiConfiguration.canBeConsumed = false
            platformApiConfiguration.extendsFrom(apiConfiguration)
            def platformCompileConfiguration = project.configurations.create("compile${capitalizedPlatform}")
            platformCompileConfiguration.canBeResolved = false
            platformCompileConfiguration.canBeConsumed = false
            platformCompileConfiguration.extendsFrom(platformApiConfiguration)
            platformCompileConfiguration.extendsFrom(compileConfiguration)

            Configuration compileClasspathConfiguration = this.compileClasspathConfiguration("compileClasspath${capitalizedPlatform}", platform, platformCompileConfiguration, true)
            Configuration apiCompileConfiguration = this.compileClasspathConfiguration("apiCompile${capitalizedPlatform}", platform, platformApiConfiguration, false)
            Configuration runtimePlatformConfiguration = runtimeClasspathConfiguration(capitalizedPlatform, platform, platformCompileConfiguration)

            def taskName = "${compileTask.name - 'Java'}$capitalizedPlatform"
            // for each platform we're creating a new JavaCompile task which is going to fork and use
            // the specified JDK
            def platformCompile = createPlatformCompileTask(taskName, platform, compileTask, compileClasspathConfiguration)
            // in addition we create an API jar task, and we use it as the artifact attached to the "api" configuration
            def apiJar = createApiJarTask(taskName, platformCompile)
            project.artifacts.add(apiCompileConfiguration.name, [file: apiJar.outputFile, builtBy: apiJar])

            // we also need to create "jar" for each platform
            def jar = createPlatformJarTask(platform, platformCompile)
            project.tasks.getByName('build').dependsOn jar
            project.artifacts.add(runtimePlatformConfiguration.name, [file: jar.archivePath, builtBy: jar])

            // and a Javadoc task
            def platformJavadoc = createPlatformJavadocTask(platform, platformCompile)

            if (platform == 'java9') {
                configureJava9(taskName, platformCompile, platformJavadoc)
            }
        }

        private void configureJava9(String taskName, JavaCompile platformCompile, Javadoc platformJavadoc) {
            if (apiExtension.moduleName) {
                // if the target platform is Java 9 and that we defined a module name in the `api` extension
                // then we're asking Gradle to generate a module file for us
                addJigsawModuleFile(taskName, platformCompile, apiExtension)
            }
            ((CoreJavadocOptions) platformJavadoc.options).addStringOption('-html5')
        }

        private Configuration runtimeClasspathConfiguration(String capitalizedPlatform, String platform, Configuration compileConfiguration) {
            def runtimePlatformConfiguration = project.configurations.findByName("runtime${capitalizedPlatform}") ?: project.configurations.create("runtime${capitalizedPlatform}")
            runtimePlatformConfiguration.attributes(type: 'runtime', platform: platform)
            runtimePlatformConfiguration.extendsFrom compileConfiguration
            runtimePlatformConfiguration
        }

        private Configuration compileClasspathConfiguration(String configName, String platform, Configuration parentConfiguration, boolean query) {
            def compilePlatformConfiguration = project.configurations.findByName(configName) ?: project.configurations.create(configName)
            compilePlatformConfiguration.attributes(type: 'api', platform: platform)
            compilePlatformConfiguration.extendsFrom parentConfiguration
            if (query) {
                compilePlatformConfiguration.canBeConsumed = false
            } else {
                compilePlatformConfiguration.canBeResolved = false
            }
            compilePlatformConfiguration
        }

        private Javadoc createPlatformJavadocTask(String platform, JavaCompile platformCompile) {
            project.tasks.create("${platform}Javadoc", Javadoc) { Javadoc javadoc ->
                javadoc.source = platformCompile.source
                javadoc.classpath = platformCompile.classpath
                javadoc.executable = "${getPlatformsExtension().jdkFor(platform)}/bin/javadoc"
                javadoc.destinationDir = project.file("${project.buildDir}/docs/${platform}Javadoc")
            }
        }

        private Jar createPlatformJarTask(String platform, JavaCompile platformCompile) {
            project.tasks.create("${platform}Jar", Jar) { Jar jar ->
                jar.from project.files(platformCompile.destinationDir)
                jar.destinationDir = project.file("$project.buildDir/libs")
                jar.classifier = platform
                jar.dependsOn(platformCompile)
            }
        }

        private ApiJar createApiJarTask(String taskName, JavaCompile platformCompile) {
            project.tasks.create("${taskName}ApiJar", ApiJar, { ApiJar apiJar ->
                apiJar.outputFile = project.file("$project.buildDir/api/${project.name}-${taskName}.jar")
                apiJar.inputs.dir(platformCompile.destinationDir).skipWhenEmpty()
                apiJar.exportedPackages = apiExtension.exports
                apiJar.dependsOn(platformCompile)
            } as Action)
        }

        private JavaCompile createPlatformCompileTask(GString taskName, String platform, JavaCompile compileTask, Configuration compilePlatformConfiguration) {
            project.tasks.create(taskName, JavaCompile, new Action<JavaCompile>() {
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
                    task.doFirst(new Action<Task>() {
                        @Override
                        void execute(final Task t) {
                            project.logger.lifecycle "Compile classpath for ${project.path} (${platform}): ${((JavaCompile)t).classpath.files*.name}"
                        }
                    })
                }
            })
        }

        private void addJigsawModuleFile(String taskName, JavaCompile platformCompile, ApiExtension extension) {
            // first let's define an additional generated sources directory where to put the module descriptor
            def genDir = new File("$project.buildDir/generated-sources/${taskName}/src/main/jigsaw")
            platformCompile.source(project.files(genDir))
            platformCompile.inputs.properties(exports: extension.exports)
            // as soon as `module-info.java` is on compile classpath, `modulepath` has to be used: even if jars are
            // found on classpath, they will trigger a ClassNotFoundError during compilation unless the dependency
            // is declared in the module path. In short: classpath is ignored.
            platformCompile.options.compilerArgs.addAll(['-modulepath', platformCompile.classpath.asPath])
            platformCompile.doFirst {
                // here is the module file generation
                genDir.mkdirs()
                def tmpConf = project.configurations.getByName('compile').copy()
                tmpConf.canBeConsumed = false
                def requires = tmpConf.files.collect {
                    "   requires ${automaticModule(it.name)};"
                }.join('\n')
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
            if (idx > 0) {
                name = name.substring(0, idx)
            }
            name.replace('-', '.') - '.jar'
        }
    }


}
