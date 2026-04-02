package com.example

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import grails.util.Environment
import groovy.transform.CompileStatic
import org.apache.grails.plugins.reload.GrailsReloader

@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {

        if(Environment.current == Environment.DEVELOPMENT) {
            GrailsReloader.startWatcher()
        }

        GrailsApp.run(Application, args)
    }
}
