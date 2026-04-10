package org.apache.grails.plugins.reload


import groovy.transform.TupleConstructor
import groovy.util.logging.Slf4j
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
        Class<?> loadedClass

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
    private static final  Map<String, String> FILES_HASH = new HashMap<>()

    static void startWatcher(){

        def classPaths = System.getProperty("simpleAgentWatchPaths")

        log.info "::> start grails watcher reload in $classPaths"

        for(var classPath : classPaths.split(",")) {
            if(!new File(classPath).exists()){
                log.warn "path $classPath does not exists"
                continue
            }
            watch(classPath)

            def site = FILES_HASH.size()
            log.info "load hash to files on $classPath.."
            new File(classPath)
                    .eachDirRecurse {file ->
                        file.eachFileMatch(~/.*.class/) {f ->
                            FILES_HASH.put(f.absolutePath, generateCheckSum(f.toPath()))
                        }
                    }
            log.info "loaded hashs to ${FILES_HASH.size() - site} files!"
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
                
                log.debug "::> ${events.size()} files to reloaded via ReloadAgent"

                dispatchEvents(events)

                log.debug "::> Grails reload is done!"
            }
        }
    }

    private static void dispatchEvents(List<FileChangedEvent> fileChangedEvents){
        def events = fileChangedEvents.findAll {
            hasContentChanged(it.absolutePath)
        }
        new ReloadPipeline().start(events)
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

    private static boolean hasContentChanged(Path file) {
        String currentHash = generateCheckSum(file) // Ou use MessageDigest

        if(!FILES_HASH.containsKey(file.toString())){
            return  true
        }

        String oldHash = FILES_HASH.get(file.toString())

        if (currentHash == oldHash) {
            return false // Bytecode idêntico, ignore o reload
        }

        FILES_HASH.put(file.toString(), currentHash)
        return true
    }

    private static String generateCheckSum(Path file){
        byte[] bytes = Files.readAllBytes(file)
        return ClassSignatureHash.getFingerprint(bytes).md5()
    }
}
