
.PHONY: help
help:
	@echo "Usage:"
	@echo "  make clean       	- Clean all"
	@echo "  make run        	- Run test app"
	@echo "  make publish 	    - Publish local maven"

clean:
	./gradlew clean

run:
	./gradlew :test-app:bootRun

publish:
	./gradlew :reload-agent:publishToMavenLocal && ./gradlew :grails-reload:publishToMavenLocal

