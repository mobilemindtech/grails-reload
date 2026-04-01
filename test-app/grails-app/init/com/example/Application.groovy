package com.example

import grails.util.Environment
import groovy.transform.CompileStatic

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import org.apache.grails.plugins.GrailsReloader

@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {

        if(Environment.current == Environment.DEVELOPMENT) {
            GrailsReloader.startWatcher()
        }

        GrailsApp.run(Application, args)
    }
}
