# LaserForge — LPBF Metal 3D Printing Simulator (Native Android)

A native Kotlin / OpenGL ES 2.0 port of the LPBF Metal 3D Printing Simulator
originally written in HTML + Three.js.

The 3D scene, game logic, controls, and visual styling are all faithfully
reproduced in a single Android Studio project — no WebView, no external 3D
libraries.

## Features

- 3 game modes: **Free Draw**, **2D Shape** (letters/numbers + symbols),
  **3D Shape** (solid/frame × pyramid/cube/cylinder/cone/sphere).
- 3D chamber with recoater, powder particles, build platform,
  grid floor, chamber walls, powder stock.
- 4 laser shapes: **Gaussian**, **Top Hat**, **Doughnut**, **Elliptical**.
- 4 sliders: laser width, laser power, layer thickness, laser shape.
- Joystick to move the laser + **FIRE** toggle to deposit voxels.
- **Next Layer / Finish / Reset** action buttons (gradient styled).
- 5 camera control buttons (zoom in/out, rotate left/right, reset).
- 2D coordinate status bar (X/Y + pointer state) and bottom bar
  (Layer / Height / Laser status).
- Match-score calculation for 2D/3D modes.
- Collapsible instructions card.

## Build

The project uses the same Gradle/AGP/Kotlin versions as the reference
`sandblitz` project that lives next to it, plus the Android SDK that ships
with the Unity install. Open the project in Android Studio and it should
build out of the box; or, from a shell:

```bash
export JAVA_HOME=/snap/android-studio/225/jbr
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Project Layout

```
app/src/main/java/com/laserforge/lpbf/
├── MainActivity.kt              — wires UI buttons, sliders, joystick, sliders
│                                  to the GameEngine; per-frame orchestration
│                                  via Choreographer.
├── game/
│   ├── GameState.kt             — SPREADING, DRAWING, FINISHED state enum.
│   ├── GameEngine.kt            — game logic + scene graph (voxels, particles,
│                                  recoater, laser beam, ghost layer, snapshot).
│   ├── TargetShape.kt           — 2D (alphanumeric/emoji) + 3D (Pyramid,
│                                  Cube, Cylinder, Cone, Sphere × solid/frame)
│                                  voxel generation, ported from the HTML.
│   └── MatchCalculator.kt       — match score (0..100), identical formula.
├── render/
│   ├── MatrixUtil.kt            — 4×4 column-major matrix utilities
│                                  (perspective, lookAt, multiply, invert…).
│   ├── Material.kt              — albedo + alpha + emissive.
│   ├── Light.kt                 — ambient / directional / point lights.
│   ├── Mesh.kt                  — interleaved position+normal mesh, plus
│                                  builders (box, plane, cylinder, sphere,
│                                  ring, grid) and a `LineMesh` for grids.
│   ├── Camera.kt                — orbit camera (theta / phi / distance).
│   ├── Scene.kt                 — ambient + directional + point lights.
│   ├── ShaderProgram.kt         — compile/link, plus the lit vertex/fragment
│                                  shaders (Lambert + ambient + emissive,
│                                  up to 3 directional + 1 point light).
│   └── GameRenderer.kt          — `GLSurfaceView.Renderer` that owns the
│                                  shaders, camera, lights and a per-frame
│                                  object list.
└── ui/
    ├── GameView.kt              — main `GLSurfaceView` + touch handling
                                  (single-finger draw, two-finger pinch
                                  / rotate, raycast against the powder plane).
    ├── TargetPreviewView.kt     — small `GLSurfaceView` that spins the
                                  3D target shape (200×150dp).
    ├── JoystickView.kt          — custom view that draws the radial-gradient
                                  base + dashed inner ring + gradient handle.
    └── CoordinateBarView.kt     — the two status bars (top: X/Y/pointer,
                                  bottom: LAYER/HEIGHT/LASER).
```

## Implementation Notes

- The 3D scene uses a hand-rolled OpenGL ES 2.0 pipeline (no Three.js, no
  external 3D library). One vertex/fragment shader pair handles all lit
  materials using Lambert + ambient + emissive; a point light is supported
  for the laser beam. Shadow maps are intentionally skipped to keep the
  renderer simple; the chamber is lit well by the directional + ambient
  combination.
- The engine uses an immutable `Snapshot` data class to hand off per-frame
  state from the UI thread (where `engine.tick()` runs) to the GL thread
  (where `applySnapshot()` consumes it). All engine mutations take the
  engine's monitor; this keeps the game state race-free.
- Coordinate labels in the original HTML were 3D text sprites; we
  substitute small cyan/yellow tick boxes at the same positions to keep
  the visual cue without depending on a font-rendering pipeline.
- The original HTML used `MeshStandardMaterial` with `PCFSoftShadowMap`;
  this port uses a Lambert + ambient approximation. The result is
  visually faithful for the simple box/cylinder/plane shapes used in the
  game.
- The voxel pattern math (gaussian / tophat / doughnut / elliptical),
  powder particle physics, recoater slide, match-scoring formula, and
  3D shape voxel generation are all ported line-for-line from the HTML.
- The scene is laid out to mirror the HTML: powder stock is on the
  left, the recoater sweeps toward the build area on the right, and the
  default camera view (`theta = -π/2`, `phi = π/4`) frames the chamber
  the same way the original did.

## License

Released under the [MIT License](LICENSE).
