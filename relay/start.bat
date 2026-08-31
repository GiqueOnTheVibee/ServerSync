@echo off
setlocal
title ServerSync Relay

set "JAR="
for %%F in (ServerSync-Relay-*.jar serversync-relay-*.jar) do (
  if not defined JAR set "JAR=%%F"
)

if not defined JAR (
  echo Could not find a ServerSync Relay JAR in this folder.
  echo Put start.bat next to the relay JAR and try again.
  pause
  exit /b 1
)

echo Starting %JAR%...
java -Xms128M -Xmx512M -jar "%JAR%"

echo.
echo ServerSync Relay stopped with exit code %ERRORLEVEL%.
pause
endlocal
