package org.agent;


import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ReloadAgent {
    private static Instrumentation inst;

    public static void premain(String agentArgs, Instrumentation _inst) {
        inst = _inst;
        System.out.println("[ReloadAgent] HotReload enabled via JBR/DCEVM");
    }

    /*
    public static void reloadClass(String className, String classFilePath) {
        try {
            byte[] b = Files.readAllBytes(Paths.get(classFilePath));
            Class<?> clazz = Class.forName(className);
            ClassDefinition definition = new ClassDefinition(clazz, b);
            inst.redefineClasses(definition);
            System.out.println("[ReloadAgent] Classe " + className + " redefinida!");
        } catch (Exception e) {
            System.err.println("[ReloadAgent] Erro ao recarregar " + className + ": " + e.getMessage());
        }
    }
    */

    public static List<Class<?>> reloadClass(Map<String, String> classesDefs) {
        try {

            ClassDefinition[] definitions = new ClassDefinition[classesDefs.size()];
            var index = 0;

            for (Map.Entry<String, String> classDef : classesDefs.entrySet()) {

                var className = classDef.getKey();
                var classFile = classDef.getValue();
                byte[] b = Files.readAllBytes(Paths.get(classFile));
                Class<?> clazz = Class.forName(className);
                definitions[index++] = new ClassDefinition(clazz, b);
            }

            inst.redefineClasses(definitions);

            try {
                Class<?> registryClass = Class.forName("groovy.lang.MetaClassRegistry");
                // Se estiver usando Groovy, você pode precisar disparar a limpeza via reflection
                // ou simplesmente chamar no lado do Groovy após o reload.
            } catch (Exception e) { /* não é groovy, ignore */ }

            List<Class<?>> results = new ArrayList<>();

            for (var def : definitions) {
                results.add(def.getDefinitionClass());
            }

            return results;

        }catch (Exception e) {
            System.err.println("::> ReloadAgent: erro ao redefinir classe: " + e.getMessage());
        }

        return new ArrayList<>();
    }

    public static void reloadClass2(String className, String classFilePath) {
        try {
            byte[] b = Files.readAllBytes(Paths.get(classFilePath));
            Class<?> clazz = Class.forName(className);

            // Redefine a classe (DCEVM faz a mágica aqui)
            ClassDefinition definition = new ClassDefinition(clazz, b);
            inst.redefineClasses(definition);

            // ESSENCIAL PARA GROOVY: Limpar o cache de meta-classes
            // Isso força o Groovy a re-analisar a classe e ver o método novo
            try {
                Class<?> registryClass = Class.forName("groovy.lang.MetaClassRegistry");
                // Se estiver usando Groovy, você pode precisar disparar a limpeza via reflection
                // ou simplesmente chamar no lado do Groovy após o reload.
            } catch (Exception e) { /* não é groovy, ignore */ }

            System.out.println("[ReloadAgent] Class " + className + " reloaded!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}