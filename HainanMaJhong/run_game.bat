@echo off
chcp 65001 >nul
rem 启动 Spring Boot 应用（含 Redis/MySQL 持久化演示对局）
where mvn >nul 2>nul
if %errorlevel%==0 (
    mvn spring-boot:run
) else (
    echo 未找到 mvn 命令，请用 IntelliJ 运行 hainanjong.HainanMaJhongApplication
)
pause
