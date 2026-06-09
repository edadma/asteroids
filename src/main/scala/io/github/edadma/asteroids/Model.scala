package io.github.edadma.asteroids

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Which screen the game is on. Asteroids keep drifting in every phase so the title and
  * game-over screens have a moving backdrop; only `Playing` accepts ship control and runs
  * collisions.
  */
enum Phase:
  case Start, Playing, Paused, GameOver

/** Things that happened during a single [[Model.step]], surfaced so the frame loop can react —
  * currently to trigger sound effects, but equally usable for screen shake or particles later.
  * Populated fresh each step and cleared otherwise, so each event is observed exactly once.
  */
enum GameEvent:
  case Shot                 // the ship fired
  case Boom(size: Int)      // an asteroid of this size was destroyed
  case ShipDeath            // the ship was hit
  case LevelUp              // a wave was cleared

/** The player ship. `angle` is the nose heading in radians; `invuln` is the remaining grace
  * period after a spawn during which collisions are ignored and the ship blinks.
  */
final case class Ship(pos: Vec2, vel: Vec2, angle: Double, invuln: Double)

final case class Bullet(pos: Vec2, vel: Vec2, life: Double)

/** A rock. `size` is 3 (large), 2 (medium) or 1 (small); shooting one splits it into two of the
  * next size down until the smallest vanishes. `shape` is a per-vertex radius multiplier giving
  * each rock its own lumpy silhouette, and `rot`/`spin` tumble it as it drifts.
  */
final case class Asteroid(pos: Vec2, vel: Vec2, size: Int, spin: Double, rot: Double, shape: Vector[Double])

/** The control state sampled once per frame from the held-key set. */
final case class Input(left: Boolean, right: Boolean, thrust: Boolean, fire: Boolean)

/** The whole world as one immutable value. The frame loop replaces it each tick via [[Model.step]];
  * the painter reads it. `bounds` is the play-field size (the window), used for edge wrapping.
  */
final case class GameState(
    phase: Phase,
    ship: Ship,
    bullets: Vector[Bullet],
    asteroids: Vector[Asteroid],
    score: Int,
    lives: Int,
    level: Int,
    cooldown: Double,
    bounds: Vec2,
    events: Vector[GameEvent] = Vector.empty,
)

/** Tuning constants and the small size-dependent lookups. */
object Const:
  val ShipSize: Double     = 15.0  // nose distance from centre, in pixels
  val ShipRadius: Double   = 11.0  // collision radius
  val RotSpeed: Double     = 4.6   // radians per second
  val Thrust: Double       = 330.0 // acceleration, pixels per second^2
  val Drag: Double         = 0.6   // velocity retained per second (light space friction)
  val MaxSpeed: Double     = 600.0
  val BulletSpeed: Double  = 560.0
  val BulletLife: Double   = 1.1   // seconds before a shot expires
  val FireCooldown: Double = 0.22
  val MaxBullets: Int      = 5
  val Invuln: Double       = 2.5   // spawn grace period, seconds
  val Verts: Int           = 12    // asteroid silhouette vertex count

  def radiusFor(size: Int): Double = size match
    case 3 => 48.0
    case 2 => 27.0
    case _ => 15.0

  def scoreFor(size: Int): Int = size match
    case 3 => 20
    case 2 => 50
    case _ => 100

  def speedFor(size: Int, rng: Random): Double = size match
    case 3 => rand(rng, 28.0, 64.0)
    case 2 => rand(rng, 48.0, 104.0)
    case _ => rand(rng, 78.0, 150.0)

  def rand(rng: Random, a: Double, b: Double): Double = a + rng.nextDouble() * (b - a)

object Model:
  import Const.*

  /** The title screen: a few rocks already drifting, ship parked at centre, fresh score/lives. */
  def initial(bounds: Vec2, rng: Random): GameState =
    GameState(
      phase = Phase.Start,
      ship = Ship(bounds * 0.5, Vec2.zero, -math.Pi / 2, 0.0),
      bullets = Vector.empty,
      asteroids = spawnWave(1, bounds, rng),
      score = 0,
      lives = 3,
      level = 1,
      cooldown = 0.0,
      bounds = bounds,
    )

  /** A brand-new playthrough at level 1. */
  def newGame(bounds: Vec2, rng: Random): GameState =
    GameState(
      phase = Phase.Playing,
      ship = Ship(bounds * 0.5, Vec2.zero, -math.Pi / 2, Const.Invuln),
      bullets = Vector.empty,
      asteroids = spawnWave(1, bounds, rng),
      score = 0,
      lives = 3,
      level = 1,
      cooldown = 0.0,
      bounds = bounds,
    )

  /** Advance the world by `dt` seconds under the given input. Pure apart from `rng`, which feeds
    * the spawns and splits.
    */
  def step(s: GameState, in: Input, dt: Double, rng: Random): GameState =
    if dt <= 0.0 then return s.copy(events = Vector.empty)
    val b = s.bounds

    // A paused game is fully frozen — not even the rocks drift.
    if s.phase == Phase.Paused then return s.copy(events = Vector.empty)

    // Rocks tumble and drift on the title and game-over screens, so those backdrops stay alive.
    val movedAsteroids = s.asteroids.map { a =>
      a.copy(pos = (a.pos + a.vel * dt).wrap(b.x, b.y), rot = a.rot + a.spin * dt)
    }

    if s.phase != Phase.Playing then return s.copy(asteroids = movedAsteroids, events = Vector.empty)

    // What happened this step, surfaced to the caller (sound effects); see [[GameEvent]].
    val events = ArrayBuffer.empty[GameEvent]

    // --- Ship: rotate, thrust, drag, cap, move, decay invulnerability. ---
    val turn  = (if in.right then 1.0 else 0.0) - (if in.left then 1.0 else 0.0)
    val angle = s.ship.angle + turn * RotSpeed * dt
    var vel   = s.ship.vel
    if in.thrust then vel = vel + Vec2.polar(angle, Thrust * dt)
    vel = vel * math.pow(Drag, dt)
    val sp = vel.length
    if sp > MaxSpeed then vel = vel * (MaxSpeed / sp)
    val pos = (s.ship.pos + vel * dt).wrap(b.x, b.y)
    var ship = Ship(pos, vel, angle, math.max(0.0, s.ship.invuln - dt))

    // --- Bullets: age out expired ones, then maybe fire a new one. ---
    var cooldown = math.max(0.0, s.cooldown - dt)
    var bullets = s.bullets.collect {
      case bu if bu.life - dt > 0.0 =>
        bu.copy(pos = (bu.pos + bu.vel * dt).wrap(b.x, b.y), life = bu.life - dt)
    }
    if in.fire && cooldown <= 0.0 && bullets.size < MaxBullets then
      val dir  = Vec2.polar(angle, 1.0)
      val nose = pos + dir * ShipSize
      bullets = bullets :+ Bullet(nose, vel + dir * BulletSpeed, BulletLife)
      cooldown = FireCooldown
      events += GameEvent.Shot

    // --- Bullet vs. asteroid: first bullet inside a rock destroys both; the rock splits. ---
    val survivingBullets = ArrayBuffer.from(bullets)
    val nextAsteroids    = ArrayBuffer.empty[Asteroid]
    var gained           = 0
    for a <- movedAsteroids do
      val ar     = radiusFor(a.size)
      val hitIdx = survivingBullets.indexWhere(bu => (bu.pos - a.pos).length <= ar)
      if hitIdx >= 0 then
        survivingBullets.remove(hitIdx)
        gained += scoreFor(a.size)
        events += GameEvent.Boom(a.size)
        nextAsteroids ++= splitAsteroid(a, rng)
      else nextAsteroids += a

    // --- Ship vs. asteroid: a hit costs a life and respawns (or ends the game). ---
    var lives = s.lives
    var phase = s.phase
    if ship.invuln <= 0.0 &&
      nextAsteroids.exists(a => (a.pos - ship.pos).length <= radiusFor(a.size) + ShipRadius)
    then
      lives -= 1
      events += GameEvent.ShipDeath
      if lives <= 0 then phase = Phase.GameOver
      else ship = Ship(b * 0.5, Vec2.zero, -math.Pi / 2, Const.Invuln)

    // --- Wave cleared: next level brings more (and faster) rocks, plus a short breather. ---
    var level = s.level
    if phase == Phase.Playing && nextAsteroids.isEmpty then
      level += 1
      nextAsteroids ++= spawnWave(level, b, rng)
      ship = ship.copy(invuln = math.max(ship.invuln, 1.5))
      events += GameEvent.LevelUp

    s.copy(
      phase = phase,
      ship = ship,
      bullets = survivingBullets.toVector,
      asteroids = nextAsteroids.toVector,
      score = s.score + gained,
      lives = lives,
      level = level,
      cooldown = cooldown,
      events = events.toVector,
    )

  /** The rocks for a level: `3 + level` large asteroids, each placed clear of the centre so the
    * player never spawns inside one.
    */
  private def spawnWave(level: Int, b: Vec2, rng: Random): Vector[Asteroid] =
    Vector.fill(3 + level)(spawnAsteroid(3, farFromCentre(b, rng), rng))

  private def splitAsteroid(a: Asteroid, rng: Random): Vector[Asteroid] =
    if a.size <= 1 then Vector.empty
    else Vector.fill(2)(spawnAsteroid(a.size - 1, a.pos, rng))

  private def spawnAsteroid(size: Int, pos: Vec2, rng: Random): Asteroid =
    val heading = rng.nextDouble() * 2 * math.Pi
    Asteroid(
      pos = pos,
      vel = Vec2.polar(heading, speedFor(size, rng)),
      size = size,
      spin = rand(rng, -1.6, 1.6),
      rot = rng.nextDouble() * 2 * math.Pi,
      shape = Vector.fill(Verts)(rand(rng, 0.72, 1.10)),
    )

  /** A random point at least ~170px from the centre, so new large rocks don't appear on top of
    * the ship's spawn position.
    */
  private def farFromCentre(b: Vec2, rng: Random): Vec2 =
    val centre = b * 0.5
    var p      = Vec2(rng.nextDouble() * b.x, rng.nextDouble() * b.y)
    var tries  = 0
    while (p - centre).length < 170.0 && tries < 20 do
      p = Vec2(rng.nextDouble() * b.x, rng.nextDouble() * b.y)
      tries += 1
    p
