package com.example

import grails.converters.JSON
import grails.gorm.transactions.Transactional

@Transactional
class PetController {

    static defaultAction = "index"

    def index = {
        def pets = Pet.list()
        log.info "index!!"
        render(pets as JSON)
    }

    def show = {
        def pet = Pet.get(params.id)
        render(pet as JSON)

    }


    def save(Pet pet) {

        log.info "params = $params"
        pet.save()
        render(text: "saved with id: $pet.id")
    }
}
