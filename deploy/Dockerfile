FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /src
COPY . .
ENV MAVEN_OPTS="-Xmx3g"
RUN mvn -B -pl transitclockApi,transitclockWebapp -am package -DskipTests

FROM eclipse-temurin:17-jdk-jammy

ARG TOMCAT_VERSION=9.0.122

ENV CATALINA_HOME=/usr/local/tomcat \
    TRANSITCLOCK_HOME=/usr/local/transitclock \
    JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED" \
    LANG=C.UTF-8

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      nginx \
      postgresql-client \
      python3 \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${CATALINA_HOME}" \
 && curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
      | tar -xz -C "${CATALINA_HOME}" --strip-components=1 \
 && rm -rf "${CATALINA_HOME}/webapps/"* \
 && sed -i 's/<Connector port="8080"/<Connector port="8081"/' "${CATALINA_HOME}/conf/server.xml"

RUN mkdir -p \
      "${TRANSITCLOCK_HOME}/bin" \
      "${TRANSITCLOCK_HOME}/config" \
      "${TRANSITCLOCK_HOME}/db" \
      "${TRANSITCLOCK_HOME}/data" \
      "${TRANSITCLOCK_HOME}/logs" \
      "${TRANSITCLOCK_HOME}/cache"

COPY --from=build /src/transitclock/target/Core.jar ${TRANSITCLOCK_HOME}/Core.jar
COPY --from=build /src/transitclockApi/target/api.war ${CATALINA_HOME}/webapps/api.war
COPY --from=build /src/transitclockWebapp/target/web.war ${CATALINA_HOME}/webapps/web.war
COPY deploy/config/hibernate.cfg.xml ${TRANSITCLOCK_HOME}/config/hibernate.cfg.xml
COPY deploy/entrypoint.sh ${TRANSITCLOCK_HOME}/bin/entrypoint.sh

RUN chmod 755 ${TRANSITCLOCK_HOME}/bin/entrypoint.sh \
 && cp ${TRANSITCLOCK_HOME}/config/hibernate.cfg.xml ${TRANSITCLOCK_HOME}/hibernate.cfg.xml \
 && cp ${TRANSITCLOCK_HOME}/config/hibernate.cfg.xml ${TRANSITCLOCK_HOME}/hsql_hibernate.cfg.xml

WORKDIR ${TRANSITCLOCK_HOME}
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/ready || exit 1

ENTRYPOINT ["/usr/local/transitclock/bin/entrypoint.sh"]
