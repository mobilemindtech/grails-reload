package org.apache.grails.plugins

import org.agent.ReloadAgent
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.springframework.context.ApplicationContext

import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds;

@Slf4j
class GrailsReloader {

    static void startWatcher(){

        def classPath = System.getProperty("simpleAgentWatchPath")

        log.info "::> start grails watcher reload in $classPath"

        Thread.start {
            def watcher = FileSystems.default.newWatchService()
            def rootPath = Paths.get(classPath)

            // Função para registrar o diretório e todas as suas subpastas
            def registerRecursive = { Path start ->
                Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    @Override
                    FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
                        return FileVisitResult.CONTINUE
                    }
                })
            }

            registerRecursive(rootPath)

            while(true) {
                def key = watcher.take()
                // O context() de um WatchKey de diretório é o caminho relativo ao diretório registrado
                // Para saber o arquivo exato, precisamos do diretório que disparou o evento
                def watchDir = (Path) key.watchable()

                key.pollEvents().each { event ->
                    Path relativePath = (Path) event.context()
                    Path fullFile = watchDir.resolve(relativePath)

                    log.debug "::> file change: $fullFile "

                    if (fullFile.toString().endsWith(".class")) {
                        Thread.sleep(500) // Debounce para o compilador terminar a escrita

                        // Lógica para extrair o nome da classe do caminho absoluto
                        // Ex: /app/build/classes/com/exemplo/User.class -> com.exemplo.User
                        String className = rootPath.relativize(fullFile)
                                .toString()
                                .replace(File.separator, ".")
                                .replace(".class", "")

                        reloadBean(className, fullFile)
                    }
                }
                key.reset()
            }
        }
    }

    private static void reloadBean(String className, Path fullFile){
        final ctx = Holders.grailsApplication.mainContext
        ReloadAgent.reloadClass(className, fullFile.toString())

        Class beanClass = Class.forName(className)
        GroovySystem.metaClassRegistry
                .removeMetaClass(beanClass)
        beanClass.metaClass = null

        def isController = className.endsWith("Controller")
        def isBean = className.endsWith("Service") || isSpringBean(ctx, beanClass)

        if (isController || isBean) {
            def routeReloader = ctx.getBean("routeReloader") as RouteReloader
            def dependencyReloader = ctx.getBean("dependencyReloader") as DependencyReloader

            // No Spring/Grails, o beanName costuma ser o nome da classe com a primeira letra minúscula
            //String beanName = Introspector.decapitalize(fullFile.toString().split(File.separator).last().replace(".class", ""))
            String[] names = ctx.getBeanNamesForType(beanClass)

            if(names.length == 0){
                log.warn "no bean registered found for class $className"
                return
            }

            String beanName = names[0]
            Object beanInstance = ctx.getBean(beanName)

            // 3. Injeta o novo Service que você acabou de escrever no código
            if(isController) {
                dependencyReloader.reautowire(beanInstance)
            } else {
                // o service precisa ser redefinido para criar novo proxy, caso contrário
                // propriedades closure não são encontradas
                dependencyReloader.rebindService(beanName, beanClass)
            }

            if(isController) {
                // 4. Mapeia novas rotas
                routeReloader.reloadController(beanName)
            }

        }
    }

    private static boolean isSpringBean(ApplicationContext ctx, Class < ? > clazz) {
        String[] beanNames = ctx.getBeanNamesForType(clazz)
        return beanNames.length > 0
    }
}
