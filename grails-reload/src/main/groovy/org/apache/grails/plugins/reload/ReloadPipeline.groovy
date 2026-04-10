package org.apache.grails.plugins.reload

import grails.core.GrailsApplication
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.agent.ReloadAgent
import org.apache.grails.plugins.reload.GrailsReloader.FileChangedEvent
import org.codehaus.groovy.reflection.ClassInfo
import org.codehaus.groovy.runtime.InvokerHelper
import org.grails.core.artefact.ControllerArtefactHandler
import org.grails.core.artefact.ServiceArtefactHandler
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

import java.lang.reflect.Method

@Slf4j
class ReloadPipeline {

    ApplicationContext applicationContext
    GrailsApplication grailsApplication
    ConfigurableApplicationContext configurableApplicationContext
    DefaultListableBeanFactory beanFactory

    ReloadPipeline() {

        this.applicationContext = Holders.grailsApplication.mainContext
        this.grailsApplication = Holders.grailsApplication
        this.configurableApplicationContext = (ConfigurableApplicationContext) applicationContext
        this.beanFactory = (DefaultListableBeanFactory) configurableApplicationContext.beanFactory
    }

    void start(List<FileChangedEvent> events) {
        reloadDCM(events)
        events.each(this.&startPipeline)
    }

    private void reloadDCM(List<FileChangedEvent> fileChangedEvents){

        //fileChangedEvents.each {
        //    println "::> DCM reload class: ${it.classSimpleName}"
        //}


        def classesDef = fileChangedEvents
                .inject(new HashMap<String, String>()) { acc, it ->
                    acc << [(it.classFullName): it.absoluteFile]
                    acc
                }

        def reloadedClasses = ReloadAgent.reloadClass(classesDef)


        //log.info "DCM reloaded ${reloadedClasses.size()} classes"

        reloadedClasses.each {cls ->
            invalidateClass(cls)
            fileChangedEvents
                    .find { it.classFullName == cls.name }
                    .loadedClass = cls
        }
    }

    private void startPipeline(FileChangedEvent event){
        String className = event.classFullName

        if(className.contains("\$")) return

        Class<?> clazz = event.loadedClass

        log.debug "::> Reload class ${event.classSimpleName}"

        if (event.isController() || isSpringBean(clazz)) {

            String beanName = getBeanName(clazz)

            reloadArtefact(beanName, clazz)

            Object bean = reloadBean(beanName, clazz)

            if (event.isController()) {
                reloadControllerRoutes(beanName, clazz)
            }


            //debug(beanName)

            //log.info "::> Reload finished for ${event.classSimpleName}"
        }
    }

    /* ------------------------------------------------------------
       SPRING BEAN RELOAD
       ------------------------------------------------------------ */

    Object reloadBean(String beanName, Class clazz) {

        if (!beanFactory.containsBeanDefinition(beanName)) {
            log.info "bean not found for name $beanName"
            return null
        }

        def oldDef = beanFactory.getBeanDefinition(beanName)

        beanFactory.destroySingleton(beanName)
        beanFactory.removeBeanDefinition(beanName)
        beanFactory.clearMetadataCache()

        GenericBeanDefinition gbd = new GenericBeanDefinition()
        gbd.beanClass = clazz
        gbd.scope = 'prototype'
        gbd.autowireMode = AbstractBeanDefinition.AUTOWIRE_BY_TYPE

        beanFactory.registerBeanDefinition(beanName, gbd)

        Object bean = applicationContext.getBean(beanName)

        def target = (bean instanceof Advised) ? ((Advised)bean).targetSource.target : bean
        target.metaClass = null
        InvokerHelper.getMetaClass(target.class).initialize()

        return bean
    }

    /* ------------------------------------------------------------
       GRAILS ARTEFACT RELOAD
       ------------------------------------------------------------ */

    void reloadArtefact(String beanName, Class clazz) {

        String type = ControllerArtefactHandler.TYPE

        if (beanName.endsWith("Service"))
            type = ServiceArtefactHandler.TYPE

        def artefact = grailsApplication.addArtefact(type, clazz)
        artefact.initialize()

        //log.info "::> artefact reloaded ${clazz.name}"
    }


    /* ------------------------------------------------------------
       CONTROLLER ROUTE RELOAD
       ------------------------------------------------------------ */

    void reloadControllerRoutes(String beanName, Class<?> clazz){

        def handlerMapping = applicationContext.getBean('requestMappingHandlerMapping') as RequestMappingHandlerMapping

        if (!applicationContext.containsBean(beanName)) {
            log.error "::> bean ${beanName} not found"
            return
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
            //log.info "::> reload reutes for bean ${beanName}"
        } else {
            log.warn "::> cannot detect methods router for bean ${handlerMapping.getClass().name}"
        }
    }


    private void invalidateClass(Class clazz) {

        def f = clazz.getDeclaredField("__\$stMC")
        f.accessible = true
        f.setBoolean(null, false)


        def registry = GroovySystem.metaClassRegistry

        registry.removeMetaClass(clazz)

        def ci = ClassInfo.getClassInfo(clazz)
        ci.setStrongMetaClass(null)
        ci.setWeakMetaClass(null)
        ci.clearModifiedExpandos()

        InvokerHelper.removeClass(clazz)
        ClassInfo.remove(clazz)

        clazz.declaredClasses.each { inner ->
            registry.removeMetaClass(inner)
            InvokerHelper.removeClass(inner)
            ClassInfo.remove(inner)
            def ciInner = ClassInfo.getClassInfo(inner)
            ciInner.setStrongMetaClass(null)
            ciInner.setWeakMetaClass(null)
        }

        ClassInfo.getAllClassInfo().each {
            it.setStrongMetaClass(null)
            it.setWeakMetaClass(null)
        }

        ClassInfo.clearModifiedExpandos()

        def mc = GroovySystem.metaClassRegistry.getMetaClass(clazz)
        mc.initialize()
    }



    /* ------------------------------------------------------------
       UTILS
       ------------------------------------------------------------ */

    private String getBeanName(Class<?> clazz) {

        String[] names = applicationContext.getBeanNamesForType(clazz)

        if (!names || names.length == 0)
            throw new IllegalStateException("Bean not found for ${clazz.name}")

        return names[0]
    }

    private boolean isSpringBean(Class<?> clazz) {

        String[] beanNames = applicationContext.getBeanNamesForType(clazz)

        return beanNames.length > 0
    }

    private void debug(String beanName) {
        def bean = applicationContext.getBean(beanName)
        def target = (bean instanceof Advised) ? ((Advised)bean).targetSource.target : bean

        // Testa invocar o método diretamente via reflection para confirmar que existe
        def ttMethod = target.class.declaredMethods.find { it.name == '$tt__other' }
        log.info "Método \$tt__other existe na classe? ${ttMethod != null}"
        ttMethod = target.class.declaredMethods.find { it.name == 'other' }
        log.info "Método other existe na classe? ${ttMethod != null}"

        if (ttMethod) {
            log.info "Parâmetros: ${ttMethod.parameterTypes*.name}"
            // Testa se o MetaClass consegue encontrar o método
            def metaMethod = target.metaClass.getMethods().find { it.name == '$tt__other'}
            log.info "MetaClass encontra o método \$tt__other? ${metaMethod != null}"
            metaMethod = target.metaClass.getMethods().find { it.name == 'other'}
            log.info "MetaClass encontra o método other? ${metaMethod != null}"
        }



        try {
            def mc = GroovySystem.metaClassRegistry.getMetaClass(target.class)
            def status = new DefaultTransactionStatus(
                    null, true, true, false, false, null)
            mc.invokeMethod(target.class, target, '$tt__other', [status] as Object[], false, false)
            log.info "::> metaClassRegistry.invokeMethod direto FUNCIONOU!"
        } catch(MissingMethodException e) {
            log.warn "::> metaClassRegistry.invokeMethod direto FALHOU: ${e.message}"
        } catch(e) {
            log.warn "::> metaClassRegistry.invokeMethod deu outro erro (esperado): ${e.class.simpleName}: ${e.message}"
        }




        try {

            def mc = InvokerHelper.getMetaClass(target)  // ← não via registry, via instância

            log.info "InvokerHelper.getMetaClass: ${mc.class.name}"
            def mm = mc.getMetaMethod('$tt__other', [new DefaultTransactionStatus(null,true,true,false,false,null)] as Object[])

            log.info "InvokerHelper.getMetaClass method \$tt__other found ${mm != null}"

            def status = new DefaultTransactionStatus(
                    null, true, true, false, false, null)
            mc.invokeMethod(target.class, target, '$tt__other', [status] as Object[], false, false)

            log.info "MetaMethod encontrado via InvokerHelper.getMetaClass: ${mm}"
        } catch(MissingMethodException e) {
            log.warn "::> InvokerHelper.invokeMethod direto FALHOU: ${e.message}"
        } catch(e) {
            log.warn "::> InvokerHelper.invokeMethod deu outro erro (esperado): ${e.class.simpleName}: ${e.message}"
        }
    }
}