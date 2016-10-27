# Compile Avoidance for the current Gradle model

This project demonstrates how to implement compile avoidance using the new configuration attributes feature of 
Gradle 3.3. To execute this demo, you need the latest development version of Gradle, built from source.

This demo adds support:

- for declaring the API of a component (as exported packages)
- for declaring API dependencies of a component (that is to say, dependencies which are exported when compiling against this component)
- for declaring internal dependencies of a component (dependencies which are **not** found on classpath of components compiling against this component)
- for declaring the target platforms of a component (as Java platforms), demonstrating _variant aware dependency resolution_

# Running the demo

The demo makes use of [composite builds](https://docs.gradle.org/current/userguide/composite_builds.html) to build the plugin
and run the demo. Running the demo can be done using:

```
gradle run
```

which will execute the demo program for all platforms. Alternatively, you can execute:

```
gradle javaXRun
```

to run a specific version (for example, `java7Run` to only run the Java 7 version). Or:

```
gradle compileJavaX

```

to compile a specific version of the component.

```
gradle build
```

would build all components for all platforms.

# The plugin
## Exported vs internal dependencies

When applied on a project, the plugin creates an additional `api` configuration. This configuration can be used to
declare the **exported dependencies** of this module. For example, if a component declares:

```
dependencies {
   api 'com.foo:foo:1.0'
}
```

Then it means that the `foo` module is required to compile this component, but also that its types belong to the
public API of the component (they are _exported_). On the contrary, if the component declares a dependency using
the `compile` configuration:

```
dependencies {
   api 'com.bar:bar:1.0'
}
```

then the `bar` module is also required for compilation, but will **not** be exported when other components depend on it.
In other words, the `bar` module is an internal dependency.

## Declaring an API

The plugin also declares an `api` extension which allows describing which packages are exported. For example:

```
api {
   exports 'com.foo'
}
```

means that all classes from the `com.foo` package will be visible from consumers. If the component also has a
`com.foo.internal` package, then all classes from this package will **not** be visible from consumers.

## Declaring target platforms

Each component can declare the platforms it targets. A library will be built for each of the platform, using the
appropriate JDK (forking) and target bytecode version. Target platforms can be registered using the `platforms` extension:

```
platforms {
   targetPlatform 'java7', 'java8'
}
```

It's worth noting that the plugin will try to locate the JDK in `/opt/jdk1.x.0` (for example, if the platform is `java7`
then it will try to find a JDK in `/opt/jdk1.7.0`). If your JDK is not found in that place, you can configure the path
to the JDK:

```
platforms {
   targetPlatform 'java7', 'java8'
   jdks.java7 = '/Library/Java/JavaVirtualMachines/jdk1.7.0_79.jdk'
   jdks.java8 = '/Library/Java/JavaVirtualMachines/jdk1.8.0_73.jdk'
}
```

# Demonstrating compile avoidance
## Layout of the project

The project consists of 3 modules:

- `core` depends on `utils`
- `utils` depends on `someLib` and _exports_ it. It also depends on `commons-lang3` but does **not** export it.
- `someLib` is a regular Java project

## Initial setup

First, let's compile the Java 7 version of our `core` project:

```
$ gradle compileJava7
:demo
:demo:someLib:compileJava
warning: [options] bootstrap class path not set in conjunction with -source 1.7
1 warning
:demo:someLib:processResources UP-TO-DATE
:demo:someLib:classes
:demo:someLib:jar
:demo:utils:compileJava7
Compile classpath for :utils (java7): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar:/home/cchampeau/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-lang3/3.5/6c6c702c89bfff3cd9e80b04d668c5e190d588c6/commons-lang3-3.5.jar
:demo:utils:compileJava7ApiJar
:demo:core:compileJava7
Compile classpath for :core (java7): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/utils/build/api/utils-compileJava7.jar:/home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar
:compileJava7
```

Logging of the compile classpath is intentional in this demo plugin. We can see that:

- `someLib` is a regular module that doesn't apply the `CompileAvoidance` plugin. As such, it is compiled regularly and
since `utils` depends on `someLib` we need to generate the `jar`, as in usual Gradle projects.
- then `utils`, Java 7 variant, is compiled. It's compile classpath includes `someLib.jar`, but also `commons-lang3-3.5.jar`
- then we generate an "API Jar" for `utils`, which strips down the result of compilation using the API specification of `utils`.
In particular, it excludes non exported packages, method bodies, ...
- then we can compile `core`, Java 7 variant. This time we can see that the compile classpath includes the "API Jar" of `utils`, 
but also `someLib.jar`, which is exported by `utils`, but **not** `commons-lang3`. It means that we won't leak internal dependencies 
of `utils` when compiling `core`. This comes with numerous advantages:

- `core` cannot compile against internal (non exported) classes of `utils`
- `core` cannot accidently depend on transitive dependencies of `utils`

Let's see what happens with different use cases.

## Modifying a transitive dependency version

We're going to update `utils` so that it uses `commons-lang3` version 3.4 instead of 3.5, then build again:

```
$ gradle compileJava7
:demo
:demo:someLib:compileJava UP-TO-DATE
:demo:someLib:processResources UP-TO-DATE
:demo:someLib:classes UP-TO-DATE
:demo:someLib:jar UP-TO-DATE
:demo:utils:compileJava7
Compile classpath for :utils (java7): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar:/home/cchampeau/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-lang3/3.4/5fe28b9518e58819180a43a850fbc0dd24b7c050/commons-lang3-3.4.jar
:demo:utils:compileJava7ApiJar UP-TO-DATE
:demo:core:compileJava7 UP-TO-DATE
:compileJava7
```

we can see that we need to recompile `utils`, but then Gradle realizes that the implementation didn't change, so the API jar is identical.
Since we didn't leak `commons-lang3` into the compile classpath of `core`, the classpath is identical, so the `core` compile task
is up-to-date.

Conclusion: changing an internal dependency will not trigger recompilation of downstream dependencies.

## Changing an internal class

Now we're going to change the implementation of `com.acme.utils.internal.StringInternal`. Edit the file and add
any **public** method to it. For example:

```
public class StringInternal implements Function<String, String> {
   ...
   public void blah() { System.out.println("Hello"); }
}
```

Then recompile the project:

```
$ gradle compileJava7
:demo
:demo:someLib:compileJava UP-TO-DATE
:demo:someLib:processResources UP-TO-DATE
:demo:someLib:classes UP-TO-DATE
:demo:someLib:jar UP-TO-DATE
:demo:utils:compileJava7
Compile classpath for :utils (java7): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar:/home/cchampeau/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-lang3/3.4/5fe28b9518e58819180a43a850fbc0dd24b7c050/commons-lang3-3.4.jar
:demo:utils:compileJava7ApiJar
:demo:core:compileJava7 UP-TO-DATE
:compileJava7
```

Again, we need to recompile `utils`, because an internal class changed. We also need to regenerate the API jar. But once the API jar is generated,
we realize that the ABI (application binary interface) of the component didn't change. So we don't need to compile `core`. It's up-to-date.

Conclusion: Changing a public method of an internal class doesn't trigger recompilation of downstream dependencies.

## Changing the implementation of a public method of an API class

Now, we're going to change the implementation of `com.acme.utils.StringUtils` which is an exported class. Change the
body of the `apply` method:

```
    @Override
    public String apply(final String from) {
        return StringUtils.capitalize(from) + " updated";
    }
```

then recompile the project:

```
$ gradle compileJava7
:demo
:demo:someLib:compileJava UP-TO-DATE
:demo:someLib:processResources UP-TO-DATE
:demo:someLib:classes UP-TO-DATE
:demo:someLib:jar UP-TO-DATE
:demo:utils:compileJava7
Compile classpath for :utils (java7): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar:/home/cchampeau/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-lang3/3.4/5fe28b9518e58819180a43a850fbc0dd24b7c050/commons-lang3-3.4.jar
:demo:utils:compileJava7ApiJar
:demo:core:compileJava7 UP-TO-DATE
:compileJava7
```

Again we need to recompile `utils` and generate the API jar, but again we realize that the ABI didn't change, so there's no
need to recompile `core`!

Conclusion: Changing the implementation of a public method of an exported API class doesn't trigger recompilation of downstream dependencies.

Of course, same is valid for private, protected members of an API class: downstream dependencies wouldn't be recompiled.

# Experimental support for Jigsaw

In addition to this, the plugin supports compiling Jigsaw modules. If:

- `targetPlatforms` has `java9` and
- `moduleName` is specified

Then Gradle will automatically generate a `module-info.java` file and build a Jigsaw module, using `modulepath` instead
of `classpath`:

```
api {
    moduleName = 'core'
    exports 'com.acme'
}

platforms {
    targetPlatforms 'java7', 'java8', 'java9'
}

```

then calling: `gradle java9Jar` will show:

```
:demo
:demo:someLib:compileJava UP-TO-DATE
:demo:someLib:processResources UP-TO-DATE
:demo:someLib:classes UP-TO-DATE
:demo:someLib:jar UP-TO-DATE
:demo:utils:compileJava UP-TO-DATE
:demo:utils:processResources UP-TO-DATE
:demo:utils:classes UP-TO-DATE
:demo:utils:jar UP-TO-DATE
:demo:core:compileJava9
Compile classpath for :core (java9): /home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/utils/build/libs/utils.jar:/home/cchampeau/DEV/PROJECTS/GITHUB/gradle-compile-avoidance-current-model/demo/someLib/build/libs/someLib.jar:/home/cchampeau/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-lang3/3.5/6c6c702c89bfff3cd9e80b04d668c5e190d588c6/commons-lang3-3.5.jar
:demo:core:java9Jar UP-TO-DATE
:java9Jar
```

And we can see that Gradle created a module info file for us:

```
 cat demo/core/build/generated-sources/compileJava9/src/main/jigsaw/module-info.java
module core {
   requires utils;
   requires someLib;
   requires commons.lang3;
    exports com.acme;
}
```

N.B: At this point the current generation is _wrong_, you would notice that it has exported `commons.lang3` and `someLib`
but it shouldn't.

