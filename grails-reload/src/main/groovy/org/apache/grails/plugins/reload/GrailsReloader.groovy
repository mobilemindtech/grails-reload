package org.apache.grails.plugins.reload

import groovy.transform.TupleConstructor
import org.agent.ReloadAgent
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.springframework.context.ApplicationContext
import org.springframework.core.task.VirtualThreadTaskExecutor

import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds

@Slf4j
class GrailsReloader {

    @TupleConstructor
    static class FileChangedEvent {
        Path relativePath
        Path rootPath
        Path watchDir

        String getClassFullName() {
            rootPath.relativize(absolutePath)
                    .toString()
                    .replace(File.separator, ".")
                    .replace(".class", "")
        }

        String getClassSimpleName(){
            def simpleName = classFullName

            if(simpleName.contains("."))
                simpleName = simpleName.split('\\.').last()
            simpleName
        }

        boolean isClosure() {
            classSimpleName.contains("\$")
        }

        String getMainClassSimpleName(){
            if(closure) classSimpleName.split('\\$')[0]
            else classSimpleName
        }

        Path getAbsolutePath(){
            watchDir.resolve(relativePath)
        }

        String getAbsoluteFile(){
            absolutePath.toString()
        }

        boolean isController() {
            classSimpleName.endsWith("Controller")
        }

        boolean isService() {
            classSimpleName.endsWith("Service")
        }
    }

    private static final VirtualThreadTaskExecutor executor = new VirtualThreadTaskExecutor()
    private static final List<FileChangedEvent> EVENTS = new ArrayList<>()
    private static long LAST_EVENT_RECEIVED = 0

    static void startWatcher(){

        def classPaths = System.getProperty("simpleAgentWatchPaths")

        log.info "::> start grails watcher reload in $classPaths"

        for(var classPath : classPaths.split(",")) {
            if(!new File(classPath).exists()){
                log.warn "path $classPath does not exists"
                continue
            }
            watch(classPath)
        }
    }

    private static void watch(String classPath){
        executor.execute {
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

                    LAST_EVENT_RECEIVED = System.currentTimeMillis()

                    Path relativePath = (Path) event.context()

                    if (!relativePath.toString().endsWith(".class")) {
                        return
                    }

                    synchronized (EVENTS) {
                        def r = EVENTS
                                .find {
                                    it.relativePath.toString() == relativePath.toString()
                                }
                        if (!r) {

                            // Lógica para extrair o nome da classe do caminho absoluto
                            // Ex: /app/build/classes/com/exemplo/User.class -> com.exemplo.User

                            def reloadFile = new FileChangedEvent(
                                    rootPath: rootPath,
                                    watchDir: watchDir,
                                    relativePath: relativePath,
                            )
                            EVENTS.add(reloadFile)
                        }
                    }
                }
                key.reset()
            }
        }

        executor.execute {
            while(true){

                if(EVENTS.isEmpty()){
                    Thread.sleep(300)
                    continue
                }

                if(System.currentTimeMillis() - LAST_EVENT_RECEIVED < 1500){
                    log.debug "::> waiting for compilation to finish..."
                    Thread.sleep(1000)
                    continue
                }

                List<FileChangedEvent> events = []

                synchronized (EVENTS){

                    def compiling = EVENTS.any {
                        !isFileReady(it.watchDir.resolve(it.relativePath))
                    }

                    if( compiling){
                        continue
                    }

                    events.addAll(EVENTS)
                    EVENTS.clear()
                }

                if(events.isEmpty())
                    continue
                
                log.debug "::> ${events.size()} files to reload"

                events.groupBy { it.mainClassSimpleName }
                    .each {files ->
                        files.value
                                .sort {x, y ->
                                    x.classSimpleName.contains('$') ? -1 : 1
                                }
                                .each(this.&processEvent)
                    }

                log.debug "::> Grails reload done!"
            }
        }
    }

    static boolean isFileReady(Path path) {
        try {
            // Tenta ler os bytes. Se o arquivo estiver incompleto ou
            // sendo escrito, isso pode falhar ou retornar tamanho zero.
            byte[] bytes = Files.readAllBytes(path)
            if (bytes.length < 4) {
                return false // Arquivo vazio/corrompido
            }

            // O "Magic Number" de todo .class Java é 0xCAFEBABE
            // Se os primeiros 4 bytes não forem esses, a escrita não terminou.
            def magic = ByteBuffer.wrap(bytes[0..3] as byte[]).getInt()
            return magic == (int)0xCAFEBABE
        } catch (Exception ignored) {
            return false
        }
    }

    private static void processEvent(FileChangedEvent fileChangedEvent){

        //def fileTime = Files.getLastModifiedTime(fileChangedEvent.absolutePath)
        log.debug "::> file changed: ${fileChangedEvent.absoluteFile}"

        final ctx = Holders.grailsApplication.mainContext
        def routeReloader = ctx.getBean("routeReloader") as RouteReloader
        def dependencyReloader = ctx.getBean("dependencyReloader") as DependencyReloader

        ReloadAgent.reloadClass(fileChangedEvent.classFullName, fileChangedEvent.absoluteFile)

        Class beanClass = dependencyReloader.loadClass(fileChangedEvent.classFullName, ctx.classLoader)

        def isController = fileChangedEvent.isController()
        def isBean = fileChangedEvent.isService() || isSpringBean(ctx, beanClass)

        if (isController || isBean) {

            // No Spring/Grails, o beanName costuma ser o nome da classe com a primeira letra minúscula
            //String beanName = Introspector.decapitalize(fullFile.toString().split(File.separator).last().replace(".class", ""))
            String[] names = ctx.getBeanNamesForType(beanClass)

            if(isController){
                dependencyReloader.refreshControllerArtefact(beanClass)
                return
            }

            String beanName = names[0]
            Object beanInstance = ctx.getBean(beanName)

            dependencyReloader.reautowire(beanInstance)

            // 3. Injeta o novo Service que você acabou de escrever no código
            if(!isController) {
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
