package com.example

import grails.artefact.gsp.TagLibraryInvoker
import grails.converters.JSON
import grails.core.GrailsApplication
import grails.core.GrailsControllerClass
import grails.gorm.transactions.Transactional
import org.codehaus.groovy.runtime.InvokerHelper
import org.grails.core.DefaultGrailsControllerClass
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.aop.framework.Advised

@Transactional

class PetController {

    static defaultAction = "index"

    GrailsApplication grailsApplication


    PetController(){

    }

    def index = {
        def pets = Pet.list()
        log.info "index!!"
        render(pets as JSON)
    }

    def show = {
        def pet = Pet.get(params.id)
        render(pet as JSON)
    }

    def debug(){


        log.info "actions = ${controllerClass.getActions()}, ${controllerClass.hashCode()}"
        def target = (this instanceof Advised) ? ((Advised)this).targetSource.target : this

        log.info "other = ${target.class.methods.find { it.name == "other" } != null}"
        log.info "\$tt_other = ${target.class.methods.find { it.name == "\$tt_other" } != null}"

        log.info(">> ${InvokerHelper.getMetaClass(target.class).methods.find { it.name == "\$tt_other" } != null}")
        log.info(">> ${target.metaClass.methods.find { it.name == "\$tt_other" } != null}")

        log.info ">> this = ${this.hashCode()}, ${this.class.name}"

        render(text: 'other')
    }


    
    def other(){ render(text: 'other') }

    def other2(){ render(text: 'other2') }


    def save(Pet pet) {
        log.info "params = $params"
        pet.save()
        render(text: "saved with id: $pet.id")
    }

}
