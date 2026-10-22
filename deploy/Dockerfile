# ============================================================
# z-observer —— 运行镜像（收端 + 管理面 z-observer-admin）
#
# 【为什么现在才有这个文件（2026-10-22）】
#   本仓此前**没有 Dockerfile**：线上那份 `zopc/z-observer:local` 是当初在节点上手工构建
#   后喂进 containerd 的，仓里没留下可复现的构建定义 ⇒ 「源码 → 镜像」这一步不在仓内。
#   卡 TASK-20261022-039 要按新源码重建镜像（清单探针打的是新代码里的 /api/internal/health），
#   所以必须先把构建定义补回来。
#
# 【基底】
#   Java 8（与本仓父链 z-boot-parent 一致；现役镜像里的 PID 1 也是 java 8）。
#   ⚠ 本机（arm64 Mac）构建集群用的镜像必须显式 `--platform linux/amd64`
#     （集群两台节点都是 amd64）⇒ 架构不匹配的镜像喂进去也起不来。
#   ⚠ 基底镜像默认走 Docker Hub；本机到 Docker Hub 不通 ⇒ 构建时用
#     `--build-arg BASE_IMAGE=<可用镜像源>/library/eclipse-temurin:8-jre-jammy` 覆盖。
#
# 【JAVA_OPTS 为什么这么写】
#   现役容器 PID 1 实测是：`java -Xms256m -Xmx1024m -jar /app/app.jar`
#   ⇒ JAVA_OPTS 是**生效**的（ENTRYPOINT 展开它），不是摆设。
#   ⇒ 这里必须复刻这个展开语义，否则堆大小会从 1024m 掉回 JVM 默认值。
#   `exec` 让 java 取代 sh 成为 PID 1（与现役一致，信号能直达 JVM）。
#
# 【时区】
#   基底默认 Etc/UTC；现役容器日志是 CST（+08:00）⇒ 显式钉 Asia/Shanghai。
# ============================================================

ARG BASE_IMAGE=eclipse-temurin:8-jre-jammy
FROM ${BASE_IMAGE}

LABEL maintainer="zifang"

# 时区：基底默认 UTC，钉成 Asia/Shanghai（与现役一致）
ENV TZ=Asia/Shanghai
RUN ln -sf /usr/share/zoneinfo/${TZ} /etc/localtime && echo ${TZ} > /etc/timezone

WORKDIR /app
# 可执行 boot jar（z-observer-admin 声明了 spring-boot-maven-plugin ⇒ repackage 后可直接 java -jar）
COPY z-observer-admin/target/z-observer-admin.jar app.jar

ENV SERVER_PORT=8080

# 三个端口：业务 HTTP / OTel collector 收口 / Prometheus 抓取口（与清单逐字一致）
EXPOSE 8080 14300 9100

# JAVA_OPTS 由 Deployment 注入（现役是 -Xms256m -Xmx1024m）
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
