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

ChromaScape is a colour-based automation framework designed for Old School RuneScape. Inspired by Kelltom's [OSBC](https://github.com/kelltom/OS-Bot-COLOR/tree/main), [SRL-dev](https://github.com/Villavu/SRL-Development/tree/master), Nick Cemenenkoff's [RuneDark](https://github.com/cemenenkoff/runedark-public) and [SlyAutomation](https://github.com/slyautomation/), it focuses on education, iterative prototyping and human like interaction using solely pixel based logic.

Whether you're just starting out or building advanced automation systems, ChromaScape provides a modular, structured framework to help you create robust bots and learn by doing.

# Setup
Check out a full **step by step guide** on how to get started [Read the Installation Guide](https://github.com/StaticSweep/ChromaScape/wiki/Pre%E2%80%90requisite-installations)

# Documentation and Tutorials
- Please visit the [Wiki](https://github.com/StaticSweep/ChromaScape/wiki) for detailed guides on writing scripts with this framework.
- Feel free to join the [Discord](https://discord.gg/4FjPfDXd46)

# Features

## Architecture
ChromaScape provides a layered architecture with tightly scoped utilities that each serve a specific role.

Due to the separation of domain, core and actions utilities it provides a greater level of control and expansion, the nature of these utilities is detailed in the wiki.

## Mouse input
Here's a very simple scripting example from the [DemoMiningScript](https://github.com/StaticSweep/ChromaScape/blob/main/src/main/java/com/chromascape/scripts/DemoMiningScript.java) that you'll see working below.
```java
  private void clickOre() {
    try {
      BufferedImage gameView = controller().zones().getGameView();
      Point clickLoc = PointSelector.getRandomPointInColour(gameView, "Cyan", 15);
      if (clickLoc == null) {
        stop();
        return;
      }
      controller().mouse().moveTo(clickLoc, "medium");
      controller().mouse().leftClick();
    } catch (Exception e) {
      logger.error(e);
      logger.error(e.getStackTrace());
    }
  }
```


https://github.com/user-attachments/assets/1267df86-db5c-4189-8c8d-e2f5fe047cab


### - Remote input

ChromaScape uses advanced remote input techniques to function as a virtual second mouse dedicated to the client window. Unlike traditional input methods, this approach never hijacks your physical mouse, so you can continue using your PC without interruption while the bot runs in the background.

This is achieved through KInput.

ChromaScape uses a slightly modified version of the [64-bit](https://github.com/ThatOneGuyScripts/KInput) supported version of KInput. The [original](https://github.com/Kasi-R/KInput) KInput source is also available for reference.
There are instructions on how to build KInput from source within the third_party directory in `DEV_README.md`

### - Humanised mouse movement
To further reduce bot detection risks, ChromaScape uses an adapted version of [WindMouse](https://ben.land/post/2021/04/25/windmouse-human-mouse-movement/), a physics based calculation of gravity and wind to ensure pixel imperfections unlike bezier curves. WindMouse has been a successful staple within the community for over a decade, and provides exceedingly human mouse movements.

## Web-Based Control Panel


https://github.com/user-attachments/assets/b1c601e9-b58a-4a54-865b-739a25ddf898


The UI is built with Spring Boot, a mature industry framework, and served locally. This gives you a powerful way to view logs, manage scripts, see the bot's sensor information, and extend functionality. It's fully customizable with basic HTML/CSS/JS, so power users can tweak or overhaul it without modifying core framework utilities or needing to worry about tight coupling.

**Newly: you can now have runelite covered by other applications while the bot runs**
> You can't minimise it or put it entirely offscreen yet, but you can place other applications on top and have it run in the background.

## Colour and Image Detection

### - Colour Picker
This tool allows you to pick exact pixel colours directly from the screen. It’s useful for identifying precise colour values needed to detect specific game elements or interface components. The colour picker supports real-time sampling and stores these colours for use in detection routines. Advanced users may choose to instantiate and store colours in code, per their preference. Inspired by ThatOneGuy's [BCD](https://github.com/ThatOneGuyScripts/BetterColorDetection)

<img width="1298" height="751" alt="Colour_Picker" src="https://github.com/user-attachments/assets/650deb75-97c1-46af-b2f7-414af9ce63e6" />

> Note: You will need all other colours except your desired one to be black.

### - Colour Detection
Using the colours obtained from the picker, the framework scans defined screen regions to find matching outlines or clusters of pixels. This process enables the bot to recognise NPCs, static objects and or RuneLite's indicators by their unique colour signatures. The detection logic is optimized to handle slight variations in colour due to lighting or graphical effects by allowing for a lower and upper range of HSV colours.

### - Image and Sprite Detection
ChromaScape includes functionality for identifying images or sprites by comparing pixel patterns against stored templates. This technique allows detection of complex objects like UI elements, sprites, items, and RuneLite plugin additions.

## Optical Character Recognition (OCR)
ChromaScape utilises template matching for accurate and fast OCR. This solution - as opposed to machine learning - provides for ocr at runtime. This was inspired by SRL and OSBC.

### Note on dependencies:
This project downloads specific fonts and UI elements from the [OSBC](https://github.com/kelltom/OS-Bot-COLOR/tree/main), [RuneDark](https://github.com/cemenenkoff/runedark-public) and [SRL-dev](https://github.com/Villavu/SRL-Development/tree/master) projects to enable accurate template matching, OCR, and UI consistency. These resources are used solely for educational and research purposes and they are not packaged directly within this repository.
