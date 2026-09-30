.PHONY: test verify run scenarios

test:
	mvn -B test

verify:
	mvn -B verify

run:
	mvn -q spring-boot:run

scenarios:
	mvn -q package -DskipTests
	java -jar target/agentic-sdlc-url-shortener-1.0.0.jar --spring.main.web-application-type=none --spring.main.banner-mode=off --logging.level.root=ERROR sdlc scenarios --workspace .
