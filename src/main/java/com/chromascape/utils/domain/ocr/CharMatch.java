package com.chromascape.utils.domain.ocr;

/**
 * Objects to store Ocr match information.
 *
 * @param character The character found.
 * @param x Top left X co-ordinate.
 * @param y Top left Y co-ordinate.
 * @param width Width of the character's image.
 * @param height Height of the character's image.
 */
public record CharMatch(String character, int x, int y, int width, int height) {}
