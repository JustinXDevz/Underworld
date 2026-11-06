# Underworld

A no-build dimension far below the Nether void, inspired by the Unstable SMP Underworld.

* Fall out of the bottom of the Nether -> you drop into the Underworld (inventory is kept).
* Fly above Y=312 in the Underworld -> you return to the Nether.
* Jagged basalt spires, huge lava lakes, deep valleys with ancient (partly broken) bridges.
* Ruined fortresses, watch towers and mazes with loot chests, guarded by geared Wither Skeletons.
* Nobody can place or break blocks (permission `underworld.bypass` to override).
* The world is only 384 blocks tall, so Bedrock players (Geyser) can see everything.

## Build
Push to GitHub. The **Build** action produces `Underworld-jar` under Actions -> latest run -> Artifacts.

## Install
Paper 1.21.4+ and Java 21. Put the jar in `plugins/`, restart. The world is created automatically,
or with Multiverse: `/mv create underworld normal -g Underworld`
(Multiverse 5: `/mv create underworld -e normal -g Underworld`).

Commands: `/underworld tp | info | reload`. Config: `plugins/Underworld/config.yml`.
