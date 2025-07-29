@echo off
echo Cloning SRL-Development repo to extract fonts...

REM Create fonts directory if it doesn't exist
if not exist "src\main\resources\fonts" (
    mkdir "src\main\resources\fonts"
)

REM Create UI images directory if it doesn't exist
if not exist "src\main\resources\images\ui" (
    mkdir "src\main\resources\images\ui"
)

REM Clone the repo into a temp folder
git clone --depth 1 https://github.com/Villavu/SRL-Development.git temp_srl_fonts

REM Copy font files
xcopy /E /Y "temp_srl_fonts\fonts\*" "src\main\resources\fonts\"

REM Clean up
rmdir /S /Q temp_srl_fonts

echo Fonts copied to src\main\resources\fonts\

REM Download UI images from OSBC repo
set "UI_DIR=src\main\resources\images\ui"

echo Downloading UI images...

curl -o "%UI_DIR%\chat.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/chat.png
curl -o "%UI_DIR%\inv.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/inv.png
curl -o "%UI_DIR%\minimap.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/minimap.png
curl -o "%UI_DIR%\minimap_fixed.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/minimap_fixed.png

echo UI images downloaded to %UI_DIR%

REM Now generate .index files for fonts
setlocal enabledelayedexpansion
set "FONT_DIR=src\main\resources\fonts"

echo Generating index files in !FONT_DIR!...

for /D %%D in ("%FONT_DIR%\*") do (
    set "FONT_PATH=%%~fD"
    set "FONT_NAME=%%~nxD"
    echo Writing index for "!FONT_NAME!"...

    > "!FONT_PATH!\!FONT_NAME!.index" (
        for %%F in ("!FONT_PATH!\*.bmp") do (
            echo %%~nxF
        )
    )
)

endlocal
echo Done generating index files.
