@echo off
rem Fabrique Klaxon.exe a cote de ce fichier (il faut Python).
cd /d "%~dp0"
python -m pip install -q paho-mqtt pystray pillow pyinstaller
python -m PyInstaller --onefile --windowed --name Klaxon --icon "%~dp0icon.ico" --hidden-import pystray._win32 --distpath . --workpath build --specpath build --clean -y klaxon.py
rmdir /s /q build
