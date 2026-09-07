# dq-tool 局域网共享 E2E 测试镜像:JRE 25 + 测试公钥版 fat jar
# jar 内 license-public.key 已替换为 .lan-e2e/keys 下的测试公钥(仅测试环境,不进仓库)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY .lan-e2e/jar-patch/dq-tool-e2e.jar /app/dq-tool.jar
EXPOSE 10000
# headless 容器:不开窗/不装托盘;数据目录挂卷 /data
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Ddq.data.dir=/data", "-jar", "/app/dq-tool.jar"]
