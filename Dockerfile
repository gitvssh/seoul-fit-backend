# 멀티 스테이지 빌드를 사용하여 이미지 크기 최적화
# Stage 1: 빌드 단계
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Gradle 캐시를 활용하기 위해 의존성 파일 먼저 복사
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./

# 의존성 다운로드 (캐시 활용)
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src ./src
RUN ./gradlew clean build -x test --no-daemon

# Stage 2: 실행 단계
FROM eclipse-temurin:21-jre-alpine AS runtime

# 클러스터 내부 수동 배치 트리거용 curl과 non-root 사용자
RUN apk add --no-cache curl && \
    addgroup -g 1000 appgroup && \
    adduser -D -u 1000 -G appgroup appuser

WORKDIR /app

# 빌드된 JAR 파일 복사
COPY --from=builder /app/build/libs/*.jar app.jar
COPY infra/runtime/seoul-fit-backend-entrypoint.sh /usr/local/bin/seoul-fit-backend-entrypoint

# 로그 디렉토리 생성
RUN mkdir -p /app/logs && \
    chown -R appuser:appgroup /app && \
    chmod 0555 /usr/local/bin/seoul-fit-backend-entrypoint

# non-root 사용자로 전환
USER appuser

# 헬스체크 추가
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 애플리케이션 포트
EXPOSE 8080

# JVM 옵션을 환경변수로 설정 가능
ENV JVM_OPTS="-Xmx512m -Xms256m"

# 실행 (환경변수는 docker run 또는 docker-compose에서 주입)
ENTRYPOINT ["/usr/local/bin/seoul-fit-backend-entrypoint"]


# The release image cannot be produced unless the exact production entrypoint
# validates identity, emits the fixed first record, and leaves only JSON after
# that record on the combined stdout/stderr stream.
FROM runtime AS runtime-contract

USER root
COPY infra/runtime/runtime-contract-java.sh /tmp/runtime-contract-bin/java
RUN chmod 0555 /tmp/runtime-contract-bin/java
USER appuser

RUN set -eu; \
    contract_log=/tmp/seoul-fit-backend-runtime-contract.log; \
    PATH=/tmp/runtime-contract-bin:${PATH} \
    OTEL_SERVICE_NAME=seoul-fit-backend \
    OTEL_SERVICE_NAMESPACE=seoul-fit \
    OTEL_SERVICE_VERSION=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
    OTEL_SERVICE_INSTANCE_ID=00000000-0000-4000-8000-000000000001 \
    K8S_POD_UID=00000000-0000-4000-8000-000000000001 \
    DEPLOYMENT_ENVIRONMENT_NAME=dev \
      /usr/local/bin/seoul-fit-backend-entrypoint >"${contract_log}" 2>&1; \
    test "$(wc -l < "${contract_log}")" -eq 2; \
    test "$(sed -n '1p' "${contract_log}")" = homelab-runtime-start-v1; \
    test "$(grep -c -x homelab-runtime-start-v1 "${contract_log}")" -eq 1; \
    test "$(sed -n '2p' "${contract_log}")" = \
      '{"log_schema":"spring_boot_otel_json_v1","event_name":"runtime.contract.after-marker"}'; \
    touch /tmp/seoul-fit-backend-runtime-contract.ok; \
    rm -f "${contract_log}"


FROM runtime AS release

COPY --from=runtime-contract /tmp/seoul-fit-backend-runtime-contract.ok \
  /etc/seoul-fit-backend-runtime-contract.ok
