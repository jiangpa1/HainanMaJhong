@echo off
chcp 65001 >nul
rem 胡牌判定示例（纯 Java，不依赖 Spring/Redis/MySQL）
where mvn >nul 2>nul
if %errorlevel%==0 (
    mvn -q -DskipTests compile
    java -cp target/classes hainanjong.Main
) else (
    echo 未找到 mvn 命令，请用 IntelliJ 运行 hainanjong.Main
)
pause
