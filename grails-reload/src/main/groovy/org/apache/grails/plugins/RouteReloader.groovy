package org.apache.grails.plugins


import grails.util.Holders
import groovy.util.logging.Slf4j
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

import java.lang.reflect.Method

@Slf4j
class RouteReloader {
    /**
     * Força o Spring a re-mapear os métodos de um Controller
     * @param beanName O nome do bean (ex: "meuController")
     */
    void reloadController(String beanName) {

        def ctx = Holders.grailsApplication.mainContext
        def handlerMapping = ctx.getBean('requestMappingHandlerMapping') as RequestMappingHandlerMapping

        if (!ctx.containsBean(beanName)) {
            log.error "::> bean ${beanName} not found"
            return
        }

        Object controller = ctx.getBean(beanName)
        Class<?> beanType = controller.getClass()

        // 1. Limpa mapeamentos existentes
        handlerMapping.getHandlerMethods()
                .keySet()
                .findAll { info ->
                    handlerMapping.getHandlerMethods().get(info).beanType == beanType
                }.each { info ->
                    handlerMapping.unregisterMapping(info)
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
            log.debug "::> reload reutes for bean ${beanName}"
        } else {
            log.warn "::> cannot detect methods router for bean ${handlerMapping.getClass().name}"
        }
    }
}