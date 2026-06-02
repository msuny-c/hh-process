ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG WILDFLY_IMAGE=quay.io/wildfly/wildfly:40.0.0.Final-jdk17

FROM ${MAVEN_IMAGE} AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM ${WILDFLY_IMAGE}

ENV HH_PROCESS_HOME=/opt/jboss/hh-process

USER root
RUN mkdir -p ${HH_PROCESS_HOME}/transaction-logs ${HH_PROCESS_HOME}/data \
    && chown -R jboss:root ${HH_PROCESS_HOME} \
    && chmod -R g+rwX ${HH_PROCESS_HOME}

COPY infra/wildfly/start-wildfly.sh ${HH_PROCESS_HOME}/start-wildfly.sh
RUN chmod +x ${HH_PROCESS_HOME}/start-wildfly.sh \
    && chown jboss:root ${HH_PROCESS_HOME}/start-wildfly.sh

USER jboss
COPY --from=build /app/target/ROOT.war ${JBOSS_HOME}/standalone/deployments/ROOT.war
RUN touch ${JBOSS_HOME}/standalone/deployments/ROOT.war.dodeploy

EXPOSE 8080 8443 8009 9990 9993
CMD ["/opt/jboss/hh-process/start-wildfly.sh"]
