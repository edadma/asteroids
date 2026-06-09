package io.github.edadma.asteroids

import io.github.edadma.suit.*
import io.github.edadma.suit.dsl.*

import scala.collection.mutable
import scala.util.Random

/** Vector-graphics drawing for the game, straight onto suit's [[Canvas]]. Everything is white
  * line-work on a black field in the classic arcade style: the ship is a concave arrowhead, the
  * rocks are tumbling lumpy polygons, shots are little glowing dots. The HUD and the title /
  * game-over messages are drawn here too rather than as widgets, so the whole frame is one
  * self-contained paint pass.
  */
object Render:
  private val White: Color = Color(224, 226, 235)
  private val Dim: Color   = Color(120, 122, 140)
  private val Flame: Color = Color(255, 150, 60)
  private val Shot: Color   = Color(255, 240, 180)

  def render(c: Canvas, size: Size, s: GameState, thrusting: Boolean, rng: Random): Unit =
    c.fillRect(Rect(0, 0, size.width, size.height), Color.black)

    for a <- s.asteroids do drawAsteroid(c, a)
    for bu <- s.bullets do c.fillCircle(Offset(bu.pos.x, bu.pos.y), 2.2, Shot)

    // The ship hides on the game-over screen and blinks while invulnerable after a respawn.
    if s.phase != Phase.GameOver then
      val visible = s.ship.invuln <= 0.0 || ((s.ship.invuln * 8).toInt % 2 == 0)
      if visible then drawShip(c, s.ship.pos, s.ship.angle, Const.ShipSize, thrusting, rng)

    drawHud(c, size, s)
    drawMessages(c, size, s)

  /** The arrowhead ship (and, at small sizes, the life icons). Points: nose, left rear, the back
    * notch, right rear — drawn as a closed outline. With `thrusting`, a flickering flame trails
    * from the notch.
    */
  private def drawShip(c: Canvas, pos: Vec2, a: Double, sz: Double, thrusting: Boolean, rng: Random): Unit =
    val nose  = pos + Vec2.polar(a, sz)
    val left  = pos + Vec2.polar(a + 2.5, sz * 0.9)
    val notch = pos + Vec2.polar(a + math.Pi, sz * 0.55)
    val right = pos + Vec2.polar(a - 2.5, sz * 0.9)
    closedPoly(c, Seq(nose, left, notch, right), 1.8, White)

    if thrusting then
      val flick = Const.rand(rng, 0.5, 1.2)
      val tip   = pos + Vec2.polar(a + math.Pi, sz * (0.9 + 0.7 * flick))
      val baseL = pos + Vec2.polar(a + 2.7, sz * 0.5)
      val baseR = pos + Vec2.polar(a - 2.7, sz * 0.5)
      c.line(off(baseL), off(tip), 1.6, Flame)
      c.line(off(baseR), off(tip), 1.6, Flame)

  private def drawAsteroid(c: Canvas, a: Asteroid): Unit =
    val r = Const.radiusFor(a.size)
    val n = a.shape.length
    val pts =
      (0 until n).map { i =>
        val ang = a.rot + (2 * math.Pi * i / n)
        val rad = r * a.shape(i)
        Vec2(a.pos.x + math.cos(ang) * rad, a.pos.y + math.sin(ang) * rad)
      }
    closedPoly(c, pts, 1.6, White)

  private def drawHud(c: Canvas, size: Size, s: GameState): Unit =
    c.drawText(Offset(18, 14), s"SCORE ${s.score}", TextStyle(20, White, FontWeight.Bold))
    c.drawText(Offset(18, 42), s"LEVEL ${s.level}", TextStyle(15, Dim))
    // Remaining lives as small ship icons in the top-right corner.
    for i <- 0 until s.lives do
      drawShip(c, Vec2(size.width - 24 - i * 26, 30), -math.Pi / 2, 9.0, false, null)

  private def drawMessages(c: Canvas, size: Size, s: GameState): Unit =
    s.phase match
      case Phase.Start =>
        centre(c, size, "ASTEROIDS", 60, White, -54)
        centre(c, size, "press ENTER to play", 22, White, 16)
        centre(c, size, "← → or A D  rotate     ↑ or W  thrust     SPACE  fire", 15, Dim, 50)
        centre(c, size, "P pause      R restart      ESC title", 15, Dim, 78)
      case Phase.Paused =>
        centre(c, size, "PAUSED", 56, White, -20)
        centre(c, size, "press P to resume", 20, White, 28)
        centre(c, size, "R restart      ESC quit to title", 15, Dim, 60)
      case Phase.GameOver =>
        centre(c, size, "GAME OVER", 60, Color(255, 120, 120), -40)
        centre(c, size, s"final score ${s.score}", 24, White, 22)
        centre(c, size, "press ENTER to play again      ESC title", 16, Dim, 58)
      case Phase.Playing => ()

  private def closedPoly(c: Canvas, pts: Seq[Vec2], w: Double, color: Color): Unit =
    val n = pts.length
    for i <- 0 until n do c.line(off(pts(i)), off(pts((i + 1) % n)), w, color)

  /** Horizontally-centred text, `dy` pixels above/below the vertical middle. The width and line
    * height come from the canvas's own `measureText` — the same measurer `drawText` uses — so
    * Inter's proportional glyphs centre exactly rather than by estimate.
    */
  private def centre(c: Canvas, size: Size, txt: String, fs: Double, color: Color, dy: Double): Unit =
    val style = TextStyle(fs, color, FontWeight.Bold)
    val m     = c.measureText(txt, style)
    c.drawText(Offset((size.width - m.width) / 2, size.height / 2 + dy - m.height / 2), txt, style)

  private inline def off(v: Vec2): Offset = Offset(v.x, v.y)

/** The game as a suit application. Window-sized so [[GameState]] always has real `bounds`; the
  * world lives in a `useRef` that a [[useFrame]] callback steps each frame and the canvas painter
  * reads, so a frame advances and repaints without reconciling the tree. The canvas auto-focuses
  * on mount so the keyboard works immediately — no click required.
  */
object Game:
  val W = 960
  val H = 720

  // SDL/USB scancodes not provided by suit's `Key` object.
  private val KeyD = 7
  private val KeyW = 26
  private val KeyP = 19
  private val KeyR = 21

  private def readInput(held: mutable.Set[Int]): Input =
    Input(
      left = held(Key.Left) || held(Key.A),
      right = held(Key.Right) || held(KeyD),
      thrust = held(Key.Up) || held(KeyW),
      fire = held(Key.Space),
    )

  val App = view {
    val rngR      = useRef(new Random())
    val rng       = rngR.current
    val state     = useRef(Model.initial(Vec2(W, H), rng))
    val held      = useRef(mutable.Set.empty[Int])
    val lastTime  = useRef(-1.0)
    val canvasRef = useRef[RenderObject | Null](null)
    val overlay   = useOverlay()

    // Give the canvas keyboard focus once it exists, so the title screen accepts ENTER right away,
    // and bring up audio now that the SDL window (and so SDL itself) is initialised.
    useEffect(
      () => {
        val fm = overlay.focus
        val ro = canvasRef.current
        if fm != null && ro != null then fm.asInstanceOf[FocusManager].focus(ro)
        Sound.init()
        noCleanup
      },
      Array.empty[Any],
    )

    useFrame { now =>
      val last = lastTime.current
      // Clamp dt so a hitch (or the first frame) can't teleport everything across the screen.
      val dt = if last < 0 then 0.0 else math.min(0.05, (now - last) / 1000.0)
      lastTime.current = now
      val next = Model.step(state.current, readInput(held.current), dt, rng)
      state.current = next
      // Turn this step's events into sound effects.
      val evs = next.events
      if evs.nonEmpty then evs.foreach(Sound.play)
    }

    def onKeyDown(e: KeyEvent): Unit =
      held.current += e.scancode
      // Game-flow keys are edge-triggered (ignore auto-repeat).
      if !e.repeat then
        val s = state.current
        e.scancode match
          case Key.Enter => // start / play again from the title or game-over screen
            if s.phase == Phase.Start || s.phase == Phase.GameOver then
              state.current = Model.newGame(s.bounds, rng)
          case KeyP => // pause / resume
            if s.phase == Phase.Playing then state.current = s.copy(phase = Phase.Paused)
            else if s.phase == Phase.Paused then state.current = s.copy(phase = Phase.Playing)
          case KeyR => // restart a fresh game
            if s.phase != Phase.Start then state.current = Model.newGame(s.bounds, rng)
          case Key.Escape => // stop: abandon the game, back to the title screen
            if s.phase == Phase.Playing || s.phase == Phase.Paused then
              state.current = Model.initial(s.bounds, rng)
          case _ => ()

    def onKeyUp(e: KeyEvent): Unit = held.current -= e.scancode

    canvas(
      width = W.toDouble,
      height = H.toDouble,
      ref = canvasRef,
      focusable = true,
      onKeyDown = onKeyDown,
      onKeyUp = onKeyUp,
    ) { (c, size) =>
      val s         = state.current
      val thrusting = s.phase == Phase.Playing && readInput(held.current).thrust
      Render.render(c, size, s, thrusting, rng)
    }
  }

@main def main(): Unit =
  Suit.run("Asteroids", Game.W, Game.H) { Game.App() }
