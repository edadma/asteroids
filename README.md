# Asteroids

A small retro vector-arcade game built on [`suit`](https://github.com/edadma/suit) —
the Scala Native + SDL3 + Cairo UI toolkit. The whole game is one suit `canvas` widget:
a `useFrame` callback steps an immutable `GameState` each frame and the painter draws it
as white line-work on a black field, the classic arcade look.

## Run

```
sbt run
```

This compiles, links a native binary, and launches the window. To produce the standalone
executable without running it:

```
sbt nativeLink
# -> target/scala-3.8.4/asteroids
```

Requires the Scala Native toolchain (Clang) plus SDL3, Cairo and FreeType, which `suit`
links against transitively.

## Controls

| Key | Action |
|-----|--------|
| ← / → or A / D | rotate |
| ↑ or W | thrust |
| Space | fire |
| Enter | start / play again (title and game-over screens) |
| P | pause / resume |
| R | restart (fresh game) |
| Esc | stop — return to the title screen |

## Rules

- Shoot rocks to score: large **20**, medium **50**, small **100**. Large rocks split into
  two mediums, mediums into two smalls, smalls vanish.
- Clear every rock to advance a level — each level spawns more, faster rocks.
- You have **3 lives**. A collision costs a life and respawns you at centre with a brief
  blinking invulnerability. Lose them all and it's game over.
- The screen wraps: fly off one edge, reappear on the opposite one.

## Layout

- `Vec2.scala` — toolkit-free 2D vector math; the simulation is pure arithmetic over it.
- `Model.scala` — `GameState` and the pure `step` function (movement, firing, collisions,
  splitting, wave/level progression). No toolkit types.
- `Main.scala` — the suit app (`Game.App`), input handling, and `Render`, which draws the
  whole frame onto suit's `Canvas`.
