# Mischievous-Tetris

Serverless multiplayer versus Tetris with some fun twists implemented in Java 15 for CSSE490 Intro to Distributed Systems.

## Features

### Versus Tetris

It's Tetris at the core! Score points by completing lines and compete against the other players to be the last one standing.

### Attacking and Defending

When you complete lines, you gain Ammo. Ammo can be spent on attacking others, defending against attacks from others, or using Bombs.

Ammo will automatically be used to block incoming Attacks - pieces that other players send to your board.

You can toggle into Attack mode to spend your own ammo to send your current piece to another player's board instead, in your chosen location and rotation.

Your current Ammo is shown as a bar on the right side of your well, in addition to being displayed by your score. Ammo is capped at 20, so spend it!

Attacks coming towards you will queue up as a red bar on the left of your well. Attacks will be automatically blocked at the cost of some ammo, but if you don't have enough, they will land one at a time whenever you finish placing your piece.

### Bombs

Turn your piece into a 2-tile-square-radius bomb at the cost of Ammo to clear up part of your board. You get a free one every so often. When you can place a bomb (for free or for an ammo cost) your piece ghost will blink one tile red.

### Random Events

Random events can occur that affect every player. The more players are in the game, the more common events are.

Random events will be announced at the bottom of the screen, and include:

- Receive additional ammo
- Lose some of your ammo
- Clear a row
- Clear a column
- Attacks become cheaper for a short time
- Remove queued attacks against you
- Instantly get full ammo
- A scattering of sand-pieces land randomly on the board

## Matchmaking Server

A matchmaking server must be running so that clients can discover each other at first. As soon as the game is started, the matchmaking server can be shut down and the game will continue, since it's only used for the initial connection.

To start the matchmaking server from the released jar:

```bash
java -cp .\TetrisClient-1.0.0-all.jar matchmaking.Matchmaker -port 26000 -addr localhost
```

## Client

Clients are given the ip and port of the matchmaking server on launch.

To start the client from the released jar:

```bash
java -cp .\TetrisClient-1.0.0-all.jar networking.RealClient -port 26000 -addr localhost
```

### Console:

Type `help` for a list of console commands.

Type `/start` to start the game on your end. Each client must enter this once they're ready to start.

### Controls:

A/D - Move piece left/right

J/K - Rotate piece counterclockwise/clockwise

L - Rotate piece 180 degrees

S - Soft drop piece

Space - Fast drop piece

Shift - toggle attack/defend mode (red ghost is attack, green is defend)

Q - Detonate bomb in place (costs Ammo if it's on cooldown)

E - Fast drop and detonate bomb (costs Ammo if it's on cooldown)

<!-- R - Secret splat -->

## Dev Setup

We're all using IntelliJ and our run configurations should be stored with the project files.

To build a version for shipping, run the Gradle `shadowJar` task (we're using Shadow in case we had dependencies, which we don't right now).
