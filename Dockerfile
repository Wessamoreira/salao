# syntax=docker/dockerfile:1
#
# RT-INF-009 — imagem de produção.
#
# Empacota um jar JÁ CONSTRUÍDO (target/*.jar), em vez de rodar o Maven aqui dentro.
# O CI já executa `mvn verify` com testes e Testcontainers; refazer o build na imagem
# duplicaria tudo isso e descartaria o cache de dependências do runner. O preço é que
# `docker build` sozinho não basta — precisa de `mvn package` antes, e o CI faz os dois
# na ordem certa.

# ---------------------------------------------------------------------------
# Extração em camadas: dependências mudam raramente, código muda a todo commit.
# Sem isto, cada deploy empurra os 66 MB inteiros pela rede em vez de alguns KB.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS extrator
WORKDIR /extracao
COPY target/*.jar aplicacao.jar
RUN java -Djarmode=tools -jar aplicacao.jar extract --layers --launcher --destination saida

# ---------------------------------------------------------------------------
# Alpine (musl) pela economia de imagem: o alvo é uma VM pequena, onde tempo de
# pull e disco contam. A aplicação é Java puro com JDBC, sem biblioteca nativa —
# se alguma entrar, mover para eclipse-temurin:25-jre-noble (glibc).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S salao && adduser -S -G salao -h /app salao
WORKDIR /app

# Uma instrução por camada, da que menos muda para a que mais muda.
COPY --from=extrator --chown=salao:salao /extracao/saida/dependencies/ ./
COPY --from=extrator --chown=salao:salao /extracao/saida/spring-boot-loader/ ./
COPY --from=extrator --chown=salao:salao /extracao/saida/snapshot-dependencies/ ./
COPY --from=extrator --chown=salao:salao /extracao/saida/application/ ./

# Os dois diretórios que recebem volume em produção existem AQUI, com dono certo.
# Volume nomeado novo herda conteúdo e dono do que a imagem tem no ponto de montagem;
# se o diretório não existisse, o Docker o criaria como root e a aplicação (uid 100)
# não escreveria nele — a ficha da cliente falharia no primeiro upload, em produção.
RUN mkdir -p /dados/arquivos /app/logs && chown -R salao:salao /dados /app/logs

USER salao

EXPOSE 8080 9090

# MaxRAMPercentage em vez de -Xmx: o contêiner pode ter limite diferente por ambiente,
# e um -Xmx fixo estoura ou desperdiça. SerialGC porque o alvo tem 1-2 vCPU — G1 só
# compensa acima disso. ExitOnOutOfMemoryError para o orquestrador reiniciar em vez de
# manter um processo vivo e inútil.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# Aponta para a porta de GERENCIAMENTO (RT-INF-008), não para a da aplicação.
# O readiness real inclui os health indicators de outbox e ouvinte de cache.
#
# 127.0.0.1 e NÃO localhost: o actuator escuta no endereço IPv4 de loopback
# (management.server.address, RT-INF-008), e dentro do contêiner `localhost`
# resolve primeiro para ::1 — o wget do BusyBox tentava o IPv6, levava "connection
# refused" e o contêiner nunca ficava healthy. Com `up -d --wait` no deploy, isso é
# pipeline vermelho para sempre; num orquestrador, reinício em laço.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:9090/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
