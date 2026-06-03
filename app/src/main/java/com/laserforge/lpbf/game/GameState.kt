package com.laserforge.lpbf.game

/**
 * Mirrors the HTML's `gameState` field. Each state has the same behaviour as the original.
 *
 * - [SPREADING]: recoater is sliding across the powder bed, particles falling.
 *   Transitions to [DRAWING] once the recoater reaches the end.
 * - [DRAWING]: the user can interact (joystick, touch, FIRE, Next Layer, Finish).
 * - [FINISHED]: terminal. Showcase mode, match score shown.
 */
enum class GameState { SPREADING, DRAWING, FINISHED }
