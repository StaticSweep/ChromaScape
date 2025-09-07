#!/bin/bash

echo "Cloning SRL-Development repo to extract fonts..."

# Create fonts directory if it doesn't exist
if [ ! -d "src/main/resources/fonts" ]; then
    mkdir -p "src/main/resources/fonts"
fi

# Create UI images directory if it doesn't exist
if [ ! -d "src/main/resources/images/ui" ]; then
    mkdir -p "src/main/resources/images/ui"
fi

# Clone the repo into a temp folder
git clone --depth 1 https://github.com/Villavu/SRL-Development.git temp_srl_fonts

# Copy font files
cp -r temp_srl_fonts/fonts/* src/main/resources/fonts/ 2>/dev/null || true

# Clean up
rm -rf temp_srl_fonts

echo "Fonts copied to src/main/resources/fonts/"

# Download UI images from OSBC repo
UI_DIR="src/main/resources/images/ui"

echo "Downloading UI images..."

curl -o "$UI_DIR/chat.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/chat.png
curl -o "$UI_DIR/inv.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/inv.png
curl -o "$UI_DIR/minimap.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/minimap.png
curl -o "$UI_DIR/minimap_fixed.png" https://raw.githubusercontent.com/kelltom/OS-Bot-COLOR/main/src/images/bot/ui_templates/minimap_fixed.png

# Download compass degree images from RuneDark repo
echo "Cloning RuneDark repo to extract compass degrees..."

# Create compass directory if it doesn't exist
if [ ! -d "src/main/resources/images/ui/compass_degrees" ]; then
    mkdir -p "src/main/resources/images/ui/compass_degrees"
fi

# Clone the repo into a temp folder
git clone --depth 1 https://github.com/cemenenkoff/runedark-public.git temp_runedark

# Copy compass degree images
cp -r temp_runedark/src/img/bot/ui_templates/compass_degrees/* src/main/resources/images/ui/compass_degrees/ 2>/dev/null || true

# Clean up
rm -rf temp_runedark

echo "Compass degree images copied to src/main/resources/images/ui/compass_degrees/"
echo "UI images downloaded to $UI_DIR"

# Now generate .index files for fonts
FONT_DIR="src/main/resources/fonts"

echo "Generating index files in $FONT_DIR..."

# Loop through each directory in fonts
for font_path in "$FONT_DIR"/*; do
    if [ -d "$font_path" ]; then
        font_name=$(basename "$font_path")
        echo "Writing index for \"$font_name\"..."
        
        # Create index file with list of .bmp files
        find "$font_path" -name "*.bmp" -exec basename {} \; > "$font_path/$font_name.index"
    fi
done

echo "Done generating index files."
