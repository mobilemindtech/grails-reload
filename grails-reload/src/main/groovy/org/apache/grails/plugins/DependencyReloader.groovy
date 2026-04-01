package org.apache.grails.plugins

import grails.util.Holders
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition

@Slf4j
class DependencyReloader {

    /**
     * Re-injeta as dependências em um objeto existente
     */
    void reautowire(Object bean) {

        def autowireCapable = Holders.grailsApplication
                .mainContext
                .autowireCapableBeanFactory

        // Isso faz o Spring ler as anotações @Autowired novamente
        // e preencher os campos, mesmo os que foram adicionados via HotReload
        autowireCapable.autowireBean(bean)

        // Opcional: Re-executa métodos @PostConstruct se necessário
        autowireCapable.initializeBean(bean, bean.getClass().simpleName)

        log.debug "::> reloaded bean ${bean.getClass().simpleName}"
    }

    void rebindService(String beanName, Class<?> clazz) {
        DefaultListableBeanFactory factory = (DefaultListableBeanFactory) Holders.grailsApplication
                .mainContext.autowireCapableBeanFactory

        // 1. Remove a definição antiga
        if (factory.containsBeanDefinition(beanName)) {
            factory.removeBeanDefinition(beanName)
        }

        // 2. Define o novo Bean (isso fará o Spring gerar um NOVO Proxy no próximo getBean)
        GenericBeanDefinition gbd = new GenericBeanDefinition()
        gbd.setBeanClass(clazz)
        gbd.setAutowireMode(GenericBeanDefinition.AUTOWIRE_BY_TYPE)

        factory.registerBeanDefinition(beanName, gbd)

        // 3. Importante: Como outros Beans (Controllers) têm a referência antiga do Proxy,
        // precisamos forçar a reinjeção neles também.
        log.debug "::> recreate bean $beanName"
    }
}
