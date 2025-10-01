@echo off
setlocal
cd /d "%~dp0"

REM Load .env variables
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    set %%a=%%b
)

REM Compile the seeder (only needed once, you can remove if already compiled)
REM Compile to out\
javac -cp ".;libs\*" -d out CustomerSeeder.java

REM Run the seeder
java -cp "out;libs\*" ^
    -Ddb.url="%POSTGRES_URL%" ^
    -Ddb.user="%POSTGRES_USER%" ^
    -Ddb.pass="%POSTGRES_PASSWORD%" ^
    CustomerSeeder

REM Compile the seeder (only needed once, you can remove if already compiled)
REM Compile to out\
javac -cp ".;libs\*" -d out SubscriptionSeederDueToday.java

REM Run the seeder
java -cp "out;libs\*" ^
    -Ddb.url="%POSTGRES_URL%" ^
    -Ddb.user="%POSTGRES_USER%" ^
    -Ddb.pass="%POSTGRES_PASSWORD%" ^
    SubscriptionSeederDueToday

pause
