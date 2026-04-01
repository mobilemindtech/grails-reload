package com.example

class TestController {

    static defaultAction = "index"

    def myBeanService

    def index = {
        //log.error "test!! $myBeanService"
        myBeanService.test()
        myBeanService.test2()
        myBeanService.test3()
        myBeanService.test4()
        //myBeanService.invokeMethod("test2", null)
        render(text: AppUtil.test())
    }

    def simple = {
        log.error "simple test"
        render(text: 'simple test')
    }


    def simple2 = {
        log.error "simple test"
        render(text: 'simple test2')
    }

    def simple3 = {
        log.error "simple test3"
        render(text: 'simple test3 !')
    }

    def simple4 = {
        log.error "simple test4"
        render(text: 'simple test4 !')
    }


}
