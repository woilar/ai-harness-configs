@echo off
if exist "%~dp0scripts\install.py" (
  py -3 "%~dp0scripts\install.py" %*
) else (
  py -3 "%~dp0install.py" %*
)
