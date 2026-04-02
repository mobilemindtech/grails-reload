package org.apache.grails.plugins.reload

import grails.core.GrailsApplication
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.grails.core.artefact.ControllerArtefactHandler
import org.springframework.aop.framework.Advised
import org.springframework.beans.CachedIntrospectionResults
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.AnnotationUtils

import java.beans.Introspector

@Slf4j
class DependencyReloader {

    /**
     * Re-injeta as dependências em um objeto existente
     */
    void reautowire(Object instance) {

        def ctx =  Holders.grailsApplication.mainContext
        def autowireCapable = ctx.autowireCapableBeanFactory

        // Isso faz o Spring ler as anotações @Autowired novamente
        // e preencher os campos, mesmo os que foram adicionados via HotReload
        autowireCapable.autowireBean(instance)

        //instance.class.declaredFields.each { field ->
        //    String beanName = field.name
        //
        //
        //    if (ctx.containsBean(beanName)) {
        //        def bean = ctx.getBean(beanName)
        //        forceInjection(instance, beanName, bean)
        //    }
        //}

        // Opcional: Re-executa métodos @PostConstruct se necessário
        autowireCapable.initializeBean(instance, instance.getClass().simpleName)

        log.debug "::> reloaded bean ${instance.getClass().simpleName}"
    }

    void rebindService(String beanName, Class<?> clazz) {
        DefaultListableBeanFactory registry = (DefaultListableBeanFactory) Holders.grailsApplication
                .mainContext.autowireCapableBeanFactory

        // 1. Remove a definição antiga
        def oldBdf = registry.getBeanDefinition(beanName)
        registry.removeBeanDefinition(beanName)

        // 2. Define o novo Bean (isso fará o Spring gerar um NOVO Proxy no próximo getBean)
        //GenericBeanDefinition gbd = new GenericBeanDefinition()
        //gbd.setBeanClass(clazz)
        //gbd.setAutowireMode(GenericBeanDefinition.AUTOWIRE_BY_TYPE)

        registry.registerBeanDefinition(beanName, oldBdf)

        // 3. Importante: Como outros Beans (Controllers) têm a referência antiga do Proxy,
        // precisamos forçar a reinjeção neles também.
        log.debug "::> recreate bean definition for $beanName"
    }

    void forceInjection(Object instance, String fieldName, Object bean) {
        def field = instance.class.getDeclaredFields().find { it.name == fieldName }

        field.accessible = true

        // Se o bean for um Proxy, vamos tentar pegar o objeto real (Target)
        Object valueToInject = bean
        if (bean instanceof Advised) {
            try {
                valueToInject = ((Advised) bean).targetSource.target
            } catch (Exception e) {
                log.warn "Não foi possível extrair o target do proxy para $fieldName"
            }
        }

        try {
            field.set(instance, valueToInject)
            log.info "Sucesso: $fieldName injetado forçadamente."
        } catch (Exception e) {
            log.error "Falha crítica: Mesmo com Reflection, o Java rejeitou a atribuição."
            log.error "Tipo do Field: ${field.type.hashCode()} | Tipo do Bean: ${valueToInject.class.hashCode()}"

            // Se os HashCodes forem diferentes, confirmamos: são ClassLoaders diferentes.
        }
    }

    Class<?> loadClass(String className, ClassLoader cl) {
        Class<?> clazz = Class.forName(className, true, cl)
        def ctx = (ConfigurableApplicationContext)Holders.grailsApplication.mainContext

        // 1. Limpa o cache de introspecção do JavaBeans (Standard Java)
        Introspector.flushFromCaches(clazz)
        // 2. Limpa o cache interno do Spring (Crucial para o getBean)
        CachedIntrospectionResults.clearClassLoader(clazz.classLoader)
        AnnotationUtils.clearCache()

        // 3. Se você tiver acesso ao BeanFactory, limpa o cache de resolução de tipos
        if (ctx.beanFactory instanceof org.springframework.beans.factory.support.AbstractBeanFactory) {
            ctx.beanFactory.clearMetadataCache()
        }
        GroovySystem.metaClassRegistry.removeMetaClass(clazz)
        clazz.metaClass = null
        clazz
    }

    void refreshControllerArtefact(Class clazz) {
        def ctx = (ConfigurableApplicationContext)Holders.grailsApplication.mainContext
        def grailsApplication = ctx.getBean('grailsApplication') as GrailsApplication

        // 1. Remove a definição antiga do artefato
        grailsApplication.artefactHandlers.find { it.type == ControllerArtefactHandler.TYPE }
                ?.initialize(null) // Isso força a limpeza de alguns caches internos dependendo da versão

        // 2. O ponto crucial: O Grails armazena os 'GrailsClass' em um mapa.
        // Você precisa atualizar o artefato para a nova Classe carregada pelo DCEVM
        grailsApplication.addArtefact(ControllerArtefactHandler.TYPE, clazz)

        //log.info "Artefato Grails para ${clazz.simpleName} atualizado."
    }
}
