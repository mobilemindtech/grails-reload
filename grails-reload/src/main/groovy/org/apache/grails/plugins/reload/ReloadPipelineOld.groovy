package org.apache.grails.plugins.reload

import grails.core.GrailsApplication
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.codehaus.groovy.reflection.ClassInfo
import org.codehaus.groovy.runtime.InvokerHelper
import org.grails.core.DefaultGrailsControllerClass
import org.grails.core.artefact.ControllerArtefactHandler
import org.grails.core.artefact.ServiceArtefactHandler
import org.springframework.aop.framework.Advised
import org.springframework.beans.CachedIntrospectionResults
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

import java.beans.Introspector
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

@Slf4j
class ReloadPipelineOld {

    ApplicationContext applicationContext
    GrailsApplication grailsApplication
    ConfigurableApplicationContext configurableApplicationContext
    DefaultListableBeanFactory beanFactory

    ReloadPipelineOld() {
        this.applicationContext = Holders.grailsApplication.mainContext
        this.grailsApplication = Holders.grailsApplication
        this.configurableApplicationContext = (ConfigurableApplicationContext)this.applicationContext
        this.beanFactory = (DefaultListableBeanFactory)this.configurableApplicationContext.beanFactory
    }

    void start(GrailsReloader.FileChangedEvent fileChangedEvent){

        clearOldClass(fileChangedEvent.classFullName)

        def clazz = loadClass(fileChangedEvent.classFullName)

        invalidateCallSites(clazz)
        if(fileChangedEvent.isController() || isSpringBean(clazz)) {

            resetMetaClass(clazz)

            def beanName = getBeanName(clazz)
            reloadArtefact(beanName, clazz)
            def bean = reloadBeanDefinition(beanName, clazz)
            reautowireBean(bean)

            if(fileChangedEvent.isController()){
                reloadControllerRoutes(beanName)
            }

            printMethods(beanName)
        }
    }

    void reloadControllerRoutes(String beanName){

        def handlerMapping = applicationContext.getBean('requestMappingHandlerMapping') as RequestMappingHandlerMapping

        if (!applicationContext.containsBean(beanName)) {
            log.error "::> bean ${beanName} not found"
            return
        }


        applicationContext.getBean(beanName).class.declaredMethods.each {
            if(it.name.startsWith("other") || it.name.startsWith("\$tt__other")) {
                log.info "declaredMethod=$it.name(${it.parameterTypes?.join(', ')})"
            }
        }


        // 2. Busca o método detectHandlerMethods subindo na hierarquia
        Method detectMethod = null
        Class<?> currentClass = handlerMapping.getClass()

        while (currentClass != null && detectMethod == null) {
            detectMethod = currentClass.getDeclaredMethods().find { it.name == "detectHandlerMethods" }
            currentClass = currentClass.getSuperclass()
        }

        if (detectMethod) {
            detectMethod.setAccessible(true)
            // O método espera (Object beanName), que pode ser o String nome do bean
            detectMethod.invoke(handlerMapping, beanName)
            log.info "::> reload reutes for bean ${beanName}"
        } else {
            log.warn "::> cannot detect methods router for bean ${handlerMapping.getClass().name}"
        }
    }

    Object reloadBeanDefinition(String beanName, Class<?> clazz) {

        // 1. Remove a definição antiga
        def oldBdf = beanFactory.getBeanDefinition(beanName)

        beanFactory.destroySingleton(beanName)
        beanFactory.removeBeanDefinition(beanName)

        // 2. Define o novo Bean (isso fará o Spring gerar um NOVO Proxy no próximo getBean)
        GenericBeanDefinition beanDef = new GenericBeanDefinition()
        beanDef.setBeanClass(clazz)
        beanDef.setScope(oldBdf.getScope()) // Ou prototype, se preferir
        beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_NAME)

        // 3. Registra novamente
        beanFactory.registerBeanDefinition(beanName, beanDef)

        def bean = applicationContext.getBean(beanName)

        // Diagnóstico: confirma que o bean é da nova classe
        def actualClass = (bean instanceof Advised)
                ? ((Advised)bean).targetSource.target.class
                : bean.class

        log.info "::> bean class     : ${actualClass.name}"
        log.info "::> expected class : ${clazz.name}"
        log.info "::> same class?    : ${actualClass.is(clazz)}"
        log.info "::> classloader ok?: ${actualClass.classLoader.is(clazz.classLoader)}"

        bean
    }

    void reautowireBean(Object instance) {
        def target = instance
        if (instance instanceof Advised) {
            target = ((Advised) instance).targetSource.target
        }

        applicationContext.autowireCapableBeanFactory.autowireBean(target)

        log.info "::> reloaded bean ${target.class.simpleName}"
    }

    void reloadArtefact(String beanName, Class clazz) {

        def artefactType = ControllerArtefactHandler.TYPE

        if(beanName.endsWith("Service"))
            artefactType = ServiceArtefactHandler.TYPE

        def newArtefact = grailsApplication.addArtefact(artefactType, clazz) as DefaultGrailsControllerClass
        newArtefact.initialize() // Isso força a limpeza de alguns caches internos dependendo da versão
        log.info "Artefact [${beanName}] restarted."
    }

    private String getBeanName(Class<?> clazz){
        String[] names = applicationContext.getBeanNamesForType(clazz)
        assert names && names.size() > 0
        return  names[0]
    }

    private void clearOldClass(String className){
        // Pega a classe ANTIGA antes de recarregar
        Class<?> oldClazz = null
        try {
            oldClazz = Class.forName(className, false, applicationContext.classLoader)
        } catch(e) { /* primeira carga */ }

        // Limpa MetaClass da classe ANTIGA
        if (oldClazz) {
            GroovySystem.metaClassRegistry.removeMetaClass(oldClazz)
            ClassInfo.getClassInfo(oldClazz).setStrongMetaClass(null)
        }
    }

    private Class<?> loadClass(String className) {
        Class<?> clazz = Class.forName(className, true, applicationContext.classLoader)

        Introspector.flushFromCaches(clazz)
        clearCachedIntrospectionResults(clazz)
        AnnotationUtils.clearCache()
        beanFactory.clearMetadataCache()
        clearClassInfoMetaClass(clazz)

        // Apenas remove — não sete nada, deixa o Groovy recriar sob demanda
        GroovySystem.metaClassRegistry.removeMetaClass(clazz)

        clazz
    }

    private void resetMetaClass(Class clazz) {

        def registry = GroovySystem.metaClassRegistry

        registry.removeMetaClass(clazz)

        def mc = registry.getMetaClass(clazz)

        if (mc instanceof ExpandoMetaClass) {
            mc.initialize()
        }

    }



    private boolean isSpringBean(Class < ? > clazz) {
        String[] beanNames = applicationContext.getBeanNamesForType(clazz)
        return beanNames.length > 0
    }

    private static void clearCachedIntrospectionResults(Class<?> clazz) {
        try {
            def strongField = CachedIntrospectionResults.getDeclaredField("strongClassCache")
            strongField.accessible = true
            ((Map) strongField.get(null)).remove(clazz)

            def softField = CachedIntrospectionResults.getDeclaredField("softClassCache")
            softField.accessible = true
            ((Map) softField.get(null)).remove(clazz)
        } catch (Exception e) {
            log.warn "::> clearCachedIntrospectionResults falhou: ${e.message}"
        }
    }



    private void clearClassInfoMetaClass(Class<?> clazz) {
        try {
            def classInfo = ClassInfo.getClassInfo(clazz)

            // Zera strongMetaClass
            def strongField = ClassInfo.getDeclaredField("strongMetaClass")
            strongField.accessible = true
            strongField.set(classInfo, null)

            // Zera weakMetaClass
            def weakField = ClassInfo.getDeclaredField("weakMetaClass")
            weakField.accessible = true
            weakField.set(classInfo, null)

            // ★ Limpa o mapa de MetaClass por instância — aqui mora o HandleMetaClass
            def perInstanceField = ClassInfo.getDeclaredField("perInstanceMetaClassMap")
            perInstanceField.accessible = true
            def perInstanceMap = perInstanceField.get(classInfo)
            perInstanceMap?.clear()

            // Incrementa version para invalidar caches de call sites
            def versionField = ClassInfo.getDeclaredField("version")
            versionField.accessible = true
            ((AtomicInteger) versionField.get(classInfo)).incrementAndGet()

            log.info "::> ClassInfo limpo para ${clazz.simpleName}"
        } catch (Exception e) {
            log.warn "::> Erro ao limpar ClassInfo: ${e.message}"
        }
    }

    private void invalidateCallSites(Class clazz) {

        log.info "invalidateCallSites $clazz.name"

        ClassInfo ci = ClassInfo.getClassInfo(clazz)

        def versionField = ClassInfo.getDeclaredField("version")
        versionField.accessible = true
        ((AtomicInteger) versionField.get(ci)).incrementAndGet()

        clazz.declaredClasses.each {
            invalidateCallSites(it)
        }
    }



    private void printMethods(String beanName) {
        def bean = applicationContext.getBean(beanName)
        def target = (bean instanceof Advised) ? ((Advised)bean).targetSource.target : bean

        // Testa invocar o método diretamente via reflection para confirmar que existe
        def ttMethod = target.class.declaredMethods.find { it.name == '$tt__other' }
        log.info "Método \$tt__other existe na classe? ${ttMethod != null}"

        if (ttMethod) {
            log.info "Parâmetros: ${ttMethod.parameterTypes*.name}"
            // Testa se o MetaClass consegue encontrar o método
            def metaMethod = target.metaClass.getMetaMethod('$tt__other',
                    [DefaultTransactionStatus] as Object[])
            log.info "MetaClass encontra o método? ${metaMethod != null}"
        }

        try {
            def m = target.class.getDeclaredMethod('$tt__other',
                    org.springframework.transaction.TransactionStatus)
            m.accessible = true
            log.info "Reflection direta funciona — método encontrado: ${m}"
        } catch(e) {
            log.warn "Reflection falhou: ${e.message}"
        }


        try {
            def mc = GroovySystem.metaClassRegistry.getMetaClass(target.class)
            def status = new DefaultTransactionStatus(
                    null, true, true, false, false, null)
            mc.invokeMethod(target.class, target, '$tt__other', [status] as Object[], false, false)
            log.info "::> invokeMethod direto FUNCIONOU!"
        } catch(MissingMethodException e) {
            log.warn "::> invokeMethod direto FALHOU: ${e.message}"
        } catch(e) {
            log.warn "::> invokeMethod deu outro erro (esperado): ${e.class.simpleName}: ${e.message}"
        }

        try {
            def mc = InvokerHelper.getMetaClass(target)  // ← não via registry, via instância
            log.info "InvokerHelper.getMetaClass: ${mc.class.name}"
            def mm = mc.getMetaMethod('$tt__other', [new DefaultTransactionStatus(null,true,true,false,false,null)] as Object[])
            log.info "MetaMethod encontrado via InvokerHelper.getMetaClass: ${mm}"
        } catch(e) {
            log.warn "Erro: ${e.message}"
        }
    }

    private void printBeans(){

        log.info "--->> BEANS"

        applicationContext.beanDefinitionNames
                .findAll { it.toLowerCase().contains('transact') }
                .each { println it }

        log.info "<<--- BEANS"

        applicationContext.getBeansOfType(TransactionAttributeSource)
                .each { k, v -> println "$k => ${v.class.name}" }
    }

}
