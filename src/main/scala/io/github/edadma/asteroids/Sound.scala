package io.github.edadma.asteroids

import io.github.edadma.sdl3.*

import scala.util.Random

/** Procedurally-synthesised sound effects, played through SDL3's core audio. Nothing is loaded
  * from disk: each effect is generated as a fresh buffer of float32 PCM the moment it is needed —
  * a few thousand samples, well under a millisecond — and handed to SDL, which plays it on its
  * own audio thread. Because the buffers are generated rather than sampled, every shot and every
  * explosion is given slightly different pitch, length and decay, so they never sound canned.
  *
  * A small pool of audio streams lets effects overlap: SDL mixes all open streams to the device,
  * and each new effect is dropped onto the most idle stream so a still-playing sound is not cut.
  */
object Sound:
  val Rate = 44100

  private val rng                         = new Random()
  private val Voices                      = 8
  private var streams: Array[AudioStream] = Array.empty
  private var ready                       = false

  // A continuous sound (the thruster) needs its own stream we keep topped up — unlike the one-shot
  // effects, which fire and forget onto the pool. `rumble` is a seamless loop we re-queue while
  // thrusting so the stream never drains.
  private var thrustVoice: Option[AudioStream] = None
  private var rumble: Array[Float]             = Array.empty

  // The saucer's warble is also continuous (until it leaves or is shot), so it gets its own
  // topped-up stream too, with a distinct loop per saucer size.
  private var saucerVoice: Option[AudioStream] = None
  private var warbleBig: Array[Float]          = Array.empty
  private var warbleSmall: Array[Float]        = Array.empty

  /** Bring up audio and open the voice pool. Call once the SDL window exists (audio is a separate
    * subsystem from video). Safe to call more than once; a failure just leaves sound disabled. */
  def init(): Unit =
    if !ready && initAudio() then
      streams = Array.fill(Voices)(openAudioStream(Rate, 1))
      val tv = openAudioStream(Rate, 1)
      thrustVoice = if tv.isNull then None else Some(tv)
      rumble = Synth.rumbleLoop()
      val sv = openAudioStream(Rate, 1)
      saucerVoice = if sv.isNull then None else Some(sv)
      warbleBig = Synth.warbleLoop(small = false)
      warbleSmall = Synth.warbleLoop(small = true)
      ready = streams.nonEmpty && streams.forall(s => !s.isNull) && thrustVoice.isDefined && saucerVoice.isDefined

  /** Keep the thruster rumble going while `on`, silent otherwise. Called every frame: while
    * thrusting it tops the dedicated stream up to a small backlog so playback never gaps between
    * frames; when not, it stops feeding and the short remaining queue drains to silence. Because
    * it is its own stream, the rumble mixes under the one-shot effects rather than competing for a
    * pool voice. */
  def setThrust(on: Boolean): Unit =
    if ready && on then
      thrustVoice.foreach { v =>
        val target = rumble.length * 4 * 2 // bytes: keep ~2 loop buffers queued ahead
        while v.queued < target do v.put(rumble)
      }

  /** Keep the saucer warble going while one is on screen, silent otherwise. Same topped-up-stream
    * pattern as [[setThrust]]; the loop differs by saucer size. */
  def setSaucer(present: Boolean, small: Boolean): Unit =
    if ready && present then
      saucerVoice.foreach { v =>
        val loop   = if small then warbleSmall else warbleBig
        val target = loop.length * 4 * 2
        while v.queued < target do v.put(loop)
      }

  def play(e: GameEvent): Unit =
    if ready then
      val buf = e match
        case GameEvent.Shot         => Synth.shoot()
        case GameEvent.Boom(sz)     => Synth.explosion(sz, rng)
        case GameEvent.ShipDeath    => Synth.shipDeath(rng)
        case GameEvent.LevelUp      => Synth.levelUp(rng)
        case GameEvent.SaucerShot   => Synth.saucerShot()
        case GameEvent.SaucerDeath(_) => Synth.explosion(2, rng)
      idleVoice().put(buf)

  // The stream with the least queued audio: a new effect lands on a free voice and overlaps the
  // others instead of being appended after one that is still playing.
  private def idleVoice(): AudioStream =
    var best  = streams(0)
    var bestQ = best.queued
    var i     = 1
    while i < Voices do
      val q = streams(i).queued
      if q < bestQ then
        best = streams(i)
        bestQ = q
      i += 1
    best

/** The waveform generators. Each returns a mono float32 buffer in [-1, 1] at [[Sound.Rate]]. */
object Synth:
  import Sound.Rate

  private inline def rand(rng: Random, a: Double, b: Double): Double = a + rng.nextDouble() * (b - a)

  // A few-millisecond fade-in so a buffer that begins at full amplitude does not click.
  private def attack(i: Int): Double =
    val a = (0.004 * Rate).toInt
    if i < a then i.toDouble / a else 1.0

  private inline def clamp(x: Double): Float =
    (if x > 1.0 then 1.0 else if x < -1.0 then -1.0 else x).toFloat

  /** The fire "pew": a square wave whose pitch sweeps downward under a fast exponential decay.
    * Deterministic — every shot sounds identical, which reads better than a pitch that jumps
    * around during rapid fire. (Explosions still vary, where the variation sounds natural.) */
  def shoot(): Array[Float] =
    val dur = 0.12
    val n   = (dur * Rate).toInt
    val f0  = 900.0
    val f1  = 360.0
    val amp = 0.28
    val out = new Array[Float](n)
    var phase = 0.0
    var i     = 0
    while i < n do
      val t = i.toDouble / n
      val f = f0 + (f1 - f0) * t
      phase += 2 * math.Pi * f / Rate
      val sq  = if math.sin(phase) >= 0 then 1.0 else -1.0
      val env = math.exp(-3.5 * t) * attack(i)
      out(i) = clamp(sq * env * amp)
      i += 1
    out

  /** A rock blowing up: low-passed white noise plus a short low sine thump, both decaying. Larger
    * rocks are longer, louder and boomier (lower noise cutoff, lower thump). Decay, thump pitch
    * and the noise itself are random each time. */
  def explosion(size: Int, rng: Random): Array[Float] =
    val baseDur = size match
      case 3 => 0.60
      case 2 => 0.42
      case _ => 0.28
    val dur    = baseDur * rand(rng, 0.9, 1.12)
    val n      = (dur * Rate).toInt
    val amp    = 0.30 + 0.06 * size
    val cutoff = size match // one-pole low-pass coefficient: smaller = boomier
      case 3 => 0.10
      case 2 => 0.16
      case _ => 0.24
    val decay  = rand(rng, 2.6, 3.4)
    val thumpF = rand(rng, 42, 72)
    val out    = new Array[Float](n)
    var lp     = 0.0
    var i      = 0
    while i < n do
      val t     = i.toDouble / n
      val white = rng.nextDouble() * 2.0 - 1.0
      lp += cutoff * (white - lp)
      val env   = math.exp(-decay * t) * attack(i)
      val thump = math.sin(2 * math.Pi * thumpF * (i.toDouble / Rate)) * math.exp(-5.0 * t) * 0.5
      out(i) = clamp((lp * 1.7 + thump) * env * amp)
      i += 1
    out

  /** The player's ship exploding: a bigger, longer, lower boom than any rock. */
  def shipDeath(rng: Random): Array[Float] =
    val dur    = rand(rng, 0.8, 1.0)
    val n      = (dur * Rate).toInt
    val decay  = rand(rng, 2.0, 2.6)
    val thumpF = rand(rng, 30, 48)
    val out    = new Array[Float](n)
    var lp     = 0.0
    var i      = 0
    while i < n do
      val t     = i.toDouble / n
      val white = rng.nextDouble() * 2.0 - 1.0
      lp += 0.08 * (white - lp)
      val env   = math.exp(-decay * t) * attack(i)
      val thump = math.sin(2 * math.Pi * thumpF * (i.toDouble / Rate)) * math.exp(-3.5 * t) * 0.7
      out(i) = clamp((lp * 1.8 + thump) * env * 0.55)
      i += 1
    out

  /** A seamlessly-looping low rumble for the ship's thruster: low-passed noise (so it's a boomy
    * roar, not a hiss) with the loop boundary crossfaded so repeats don't click. Built once from a
    * fixed seed, then re-queued continuously while thrusting. */
  def rumbleLoop(): Array[Float] =
    val n   = (0.15 * Rate).toInt // loop length
    val xf  = (0.02 * Rate).toInt // crossfade length at the seam
    val rng = new Random(1)       // fixed seed → one stable loop buffer
    // Generate a little past the loop end; those extra samples are blended into the head so the
    // end flows smoothly back into the start.
    val raw = new Array[Double](n + xf)
    var lp  = 0.0
    var i   = 0
    while i < raw.length do
      val white = rng.nextDouble() * 2.0 - 1.0
      lp += 0.10 * (white - lp) // one-pole low-pass
      raw(i) = lp
      i += 1
    val out  = new Array[Float](n)
    val gain = 0.5
    i = 0
    while i < n do
      val v =
        if i < xf then
          val t = i.toDouble / xf
          raw(i) * t + raw(n + i) * (1.0 - t)
        else raw(i)
      out(i) = clamp(v * gain)
      i += 1
    out

  /** The saucer's signature warble, modelled on the original cabinet's analog circuit: an LM566
    * voltage-controlled oscillator whose pitch is swept continuously by a 555 timer's charge /
    * discharge ramp. So the frequency *glides* smoothly up and down (one buzzy "whoop" per loop)
    * rather than switching between two tones. A triangle low-frequency sweep modulates a square
    * carrier; the loop is one full sweep, with a tiny crossfade hiding the carrier-phase seam. The
    * small saucer is pitched higher and sweeps faster than the big one. */
  def warbleLoop(small: Boolean): Array[Float] =
    val period = if small then 0.15 else 0.20 // one full pitch sweep = one loop
    val n      = (period * Rate).toInt
    val xf     = (0.008 * Rate).toInt         // short crossfade to mask the carrier seam
    val center = if small then 700.0 else 500.0
    val depth  = if small then 260.0 else 220.0
    val amp    = 0.16
    // Generate a little past the loop end so the tail can be blended into the head.
    val len   = n + xf
    val raw   = new Array[Double](len)
    var phase = 0.0
    var i     = 0
    while i < len do
      // Triangle sweep over `period`: 0 at the ends, 1 in the middle → frequency rises then falls.
      val lp  = (i.toDouble / n) % 1.0
      val tri = 1.0 - math.abs(2.0 * lp - 1.0)
      val f   = (center - depth) + 2.0 * depth * tri
      phase += 2.0 * math.Pi * f / Rate
      raw(i) = if math.sin(phase) >= 0 then 1.0 else -1.0
      i += 1
    val out = new Array[Float](n)
    i = 0
    while i < n do
      val v =
        if i < xf then
          val t = i.toDouble / xf
          raw(i) * t + raw(n + i) * (1.0 - t)
        else raw(i)
      out(i) = clamp(v * amp)
      i += 1
    out

  /** The saucer firing — a quick downward square chirp, zappier and higher than the player's shot
    * so the two are easy to tell apart. Deterministic. */
  def saucerShot(): Array[Float] =
    val n   = (0.10 * Rate).toInt
    val f0  = 1400.0
    val f1  = 500.0
    val out = new Array[Float](n)
    var phase = 0.0
    var i     = 0
    while i < n do
      val t = i.toDouble / n
      val f = f0 + (f1 - f0) * t
      phase += 2 * math.Pi * f / Rate
      val sq  = if math.sin(phase) >= 0 then 1.0 else -1.0
      val env = math.exp(-4.0 * t) * attack(i)
      out(i) = clamp(sq * env * 0.22)
      i += 1
    out

  /** A short rising two-step chime when a wave is cleared. */
  def levelUp(rng: Random): Array[Float] =
    val dur   = 0.26
    val n     = (dur * Rate).toInt
    val out   = new Array[Float](n)
    var phase = 0.0
    var i     = 0
    while i < n do
      val t = i.toDouble / n
      val f = if t < 0.5 then 520.0 else 780.0
      phase += 2 * math.Pi * f / Rate
      val sq  = if math.sin(phase) >= 0 then 1.0 else -1.0
      val env = math.exp(-2.2 * t) * attack(i)
      out(i) = clamp(sq * env * 0.18)
      i += 1
    out
