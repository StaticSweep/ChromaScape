#!/bin/bash
set -e

echo "Cloning SRL-Development repo to extract fonts..."

# Create fonts directory if it doesn't exist
mkdir -p "src/main/resources/fonts"

# Create UI images directory if it doesn't exist
mkdir -p "src/main/resources/images/ui"

# Clone the repo into a temp folder
git clone --depth 1 https://github.com/Villavu/SRL-Development.git temp_srl_fonts

# Copy font files
cp -r temp_srl_fonts/fonts/. src/main/resources/fonts/

# Clean up
rm -rf temp_srl_fonts

echo "Fonts copied to src/main/resources/fonts/"

# Download UI images from OSBC repo
UI_DIR="src/main/resources/images/ui"

echo "Downloading UI images..."

BASE_URL="https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates"
curl -o "$UI_DIR/chat.png"          "$BASE_URL/chat.png"
curl -o "$UI_DIR/inv.png"           "$BASE_URL/inv.png"
curl -o "$UI_DIR/minimap.png"       "$BASE_URL/minimap.png"
curl -o "$UI_DIR/minimap_fixed.png" "$BASE_URL/minimap_fixed.png"

# Download Mouse Click images
MOUSE_DIR="src/main/resources/images/mouse_clicks"

mkdir -p "$MOUSE_DIR"

echo "Downloading Mouse Click images to $MOUSE_DIR..."

MOUSE_URL="https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/mouse_clicks"
curl -o "$MOUSE_DIR/red_1.png" "$MOUSE_URL/red_1.png"
curl -o "$MOUSE_DIR/red_2.png" "$MOUSE_URL/red_2.png"
curl -o "$MOUSE_DIR/red_3.png" "$MOUSE_URL/red_3.png"
curl -o "$MOUSE_DIR/red_4.png" "$MOUSE_URL/red_4.png"

echo "Mouse clicks downloaded."

echo "UI images downloaded to $UI_DIR"

# Generate .index files for fonts
FONT_DIR="src/main/resources/fonts"

echo "Generating index files in $FONT_DIR..."

for font_path in "$FONT_DIR"/*/; do
    font_name=$(basename "$font_path")
    echo "Writing index for \"$font_name\"..."
    # List all .bmp filenames (basename only) into the index file
    find "$font_path" -maxdepth 1 -name "*.bmp" -printf "%f\n" \
        > "${font_path}${font_name}.index"
done

echo "Done generating index files."
