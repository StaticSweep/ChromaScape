<div align="center">

  ![Java](https://img.shields.io/badge/Java-17-blue)
  ![Platform](https://img.shields.io/badge/Platform-Windows-orange)
  ![Repo Size](https://img.shields.io/github/repo-size/StaticSweep/ChromaScape)

</div>

<div align="center">
  <h1>
    <img src="src/main/resources/static/imagesWeb/CS.png" alt="logo" width="27">
    ChromaScape
  </h1>
</div>

---

ChromaScape is a low-level, colour-based automation framework designed for game clients like Old School RuneScape. Inspired by Kelltom's [OSBC](https://github.com/kelltom/OS-Bot-COLOR/tree/main), [SRL-dev](https://github.com/Villavu/SRL-Development/tree/master), Nick Cemenenkoff's [RuneDark](https://github.com/cemenenkoff/runedark-public) and [SlyAutomation](https://github.com/slyautomation/), it focuses on education, prototyping, and safe, human-like interaction using colour and pixel-based logic.

Whether you're just starting with bot development or building advanced automation systems, ChromaScape provides a modular, structured framework to help you prototype fast and learn by doing.

# Setup
Due to the constraints of remote input, this project currently supports Windows only.

1. Install JDK 17.0.12
2. Install an IDE (IntelliJ IDEA recommended)
3. Clone this repository
4. Run `CVTemplates.bat` to download template dependencies
5. (Optional) Pat yourself on the back :]

# Documentation and Tutorials
- Please visit the [Wiki](https://github.com/StaticSweep/ChromaScape/wiki) for detailed guides on writing scripts with this framework.
- Feel free to join the [Discord](https://discord.gg/4FjPfDXd46)

# Features

## Architecture
ChromaScape provides a highly modular botting framework that uses dependency injection where possible to maximize flexibility and reusability across different automation tasks.
Due to the separation of domain and core utilities it provides a greater level of control and expansion.

## Mouse input


https://github.com/user-attachments/assets/a7a39096-6d65-4ff6-8843-f3661cc9a92d


### - Remote input

ChromaScape uses advanced remote input techniques to function as a virtual second mouse dedicated to the client window. Unlike traditional input methods, this approach never hijacks your physical mouse, so you can continue using your PC without interruption while the bot runs in the background.

This is achieved through KInput, which provides utilities to send mouse and keyboard events directly into a client’s Java Canvas object. By design, it cannot click outside this canvas and requires the Process ID of the target client to operate.

ChromaScape uses the [64-bit](https://github.com/ThatOneGuyScripts/KInput) supported version of KInput. The [original](https://github.com/Kasi-R/KInput) KInput source is also available for reference.

Feel free to browse the code or build from source if you’d like to dive deeper into how it works.

### - Humanised mouse movement
To further reduce bot detection risks, ChromaScape uses humanised mouse movement patterns that mimic real user behavior. Through a combination of multiple bezier paths, easing functions and the ability to overshoot/undershoot then recorrect - this produces surprisingly natural-looking behavior.

## Web-Based Control Panel

The UI is built with Spring Boot and served locally. This gives you a powerful way to view logs, manage scripts, and extend functionality - all from a browser tab. It's fully customizable with HTML/CSS/JS, so power users can tweak or overhaul it without modifying core bot code or needing to worry about tight coupling.

## Colour and Image Detection

### - Colour Picker
This utility allows you to pick exact pixel colours directly from the screen. It’s useful for identifying precise colour values needed to detect specific game elements or interface components. The picker supports real-time sampling and stores these colours for use in detection routines. Inspired by ThatOneGuy's [BCD](https://github.com/ThatOneGuyScripts/BetterColorDetection)

<img width="1298" height="751" alt="colourScreenshot" src="https://github.com/user-attachments/assets/b93eb66c-2a61-40ba-9abb-24fb0596d7b5" />

### - Colour Detection
Using the colours obtained from the picker, the framework scans defined screen regions to find matching outlines or clusters of pixels. This process enables the bot to recognise in-game objects, UI elements, or indicators by their unique colour signatures. The detection logic is optimized to handle slight variations in colour due to lighting or graphical effects by allowing for a lower and upper range of colours.

### - Image and Sprite Detection
ChromaScape includes functionality for identifying images or sprites by comparing pixel patterns against stored templates. This technique allows robust detection of complex objects like UI elements or Sprites.

## Optical Character Recognition (OCR)
ChromaScape utilises template matching for accurate and fast OCR. This solution - as opposed to machine learning - provides for ocr at runtime. This was inspired by SRL and OSBC.

### Note on dependencies:
This project downloads specific fonts and UI elements from the [OSBC](https://github.com/kelltom/OS-Bot-COLOR/tree/main), [RuneDark](https://github.com/cemenenkoff/runedark-public) and [SRL-dev](https://github.com/Villavu/SRL-Development/tree/master) projects to enable accurate template matching, OCR, and UI consistency. These resources are used solely for educational and research purposes and they are not packaged directly within this repository.
