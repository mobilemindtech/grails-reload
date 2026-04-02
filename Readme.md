# Grails reload agent

This plugin is an alternative to the hotswap agent. Far from being a complete tool, it only does the basics to optimize Grails' work, since we couldn't get hotswap to work satisfactorily and reliably in Grails 7. Our initial idea was just to enable changes to the method bodies, since our main application takes more than 3 minutes to restart. However, with Gemini's help, we were able to go further and achieve a result that we find very satisfactory and that will greatly help our productivity. The project has not been published on Maven Central, so it can be installed on your local Maven server, or you can use our Maven repository on GitHub:

## Features:

* Adding actions/routes to the controller
* Adding services to the controller
* Adding methods/closers to services
* Creating classes not managed by Spring
* Changes to the method bodies

Creating controllers, services, and domains requires restarting the application.

## Usage

You can clone the project and install it locally on Maven:

```bash
$ make publish
```

Or use our maven:

```groovy
repositories {
    maven { url 'https://raw.githubusercontent.com/mobilemindtech/m2/master' }
}
```  
Add the plugin to your `build.gradle`

```groovy
dependencies {
    implementation 'org.apache.grails:grails-reload:0.0.1'
}

tasks.named('bootRun') {
    doFirst {

        // Aponta para o JAR gerado pelo módulo do agente
        def reloadAgentJar = project(':reload-agent').tasks.jar.archiveFile.get().asFile.absolutePath
        def buildDirPath = layout.buildDirectory.get().asFile.absolutePath

        // Flags cruciais para o JDK da JetBrains (DCEVM)
        jvmArgs += [
                "-javaagent:${reloadAgentJar}",
                "-XX:+AllowEnhancedClassRedefinition",
                "-XX:+EnableDynamicAgentLoading",
                "-DsimpleAgentWatchPaths=${buildDirPath}/classes/groovy/main,${buildDirPath}/classes/java/main"
        ]
    }
}
```

To enable logs, add on logback-spring.xml:

`<logger name="org.apache.grails.plugins.reload" level="DEBUG"/>`



Start the watcher on application startup

```groovy

import org.apache.grails.plugins.reload.GrailsReloader

@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {

        if(Environment.current == Environment.DEVELOPMENT) {
            GrailsReloader.startWatcher()
        }

        GrailsApp.run(Application, args)
    }
}
```

