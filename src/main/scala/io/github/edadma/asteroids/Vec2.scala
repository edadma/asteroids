package io.github.edadma.asteroids

/** A plain 2D vector in world (pixel) space. The game logic is pure arithmetic over these —
  * no toolkit types leak in here — so the simulation could be exercised headless. Drawing code
  * converts to suit's `Offset` at the boundary.
  */
final case class Vec2(x: Double, y: Double):
  def +(o: Vec2): Vec2    = Vec2(x + o.x, y + o.y)
  def -(o: Vec2): Vec2    = Vec2(x - o.x, y - o.y)
  def *(s: Double): Vec2  = Vec2(x * s, y * s)
  def length: Double      = math.hypot(x, y)

  /** Toroidal wrap: an object leaving one edge re-enters from the opposite one. Keeps positions
    * inside `[0, w) × [0, h)` even after large steps (the double modulo handles negatives).
    */
  def wrap(w: Double, h: Double): Vec2 =
    Vec2(((x % w) + w) % w, ((y % h) + h) % h)

object Vec2:
  val zero: Vec2 = Vec2(0.0, 0.0)

  /** A vector of length `r` pointing at `angle` radians (0 = +x, increasing clockwise on screen
    * because y grows downward).
    */
  def polar(angle: Double, r: Double): Vec2 = Vec2(math.cos(angle) * r, math.sin(angle) * r)
