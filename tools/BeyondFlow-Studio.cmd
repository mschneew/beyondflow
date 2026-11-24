@echo off
rem BeyondFlow Studio: SSH-Tunnel zur BeyondFlow-VM mit automatischem Wiederaufbau, dann Browser oeffnen
rem Anpassen: KEY = privater Schluessel, VM = oeffentliche IP der BeyondFlow-VM
set KEY=C:\Pfad\zu\beyondflow.key
set VM=<oeffentliche-IP-der-VM>
start "" http://localhost:8080/studio.html
:loop
ssh -i "%KEY%" -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes -N -L 8080:127.0.0.1:8080 opc@%VM%
echo Verbindung getrennt - neuer Versuch in 5 Sekunden ...
timeout /t 5 /nobreak >nul
goto loop
