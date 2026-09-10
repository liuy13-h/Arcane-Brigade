package com.arcanebrigade.core;

/** Dependency-free regression checks for authored map layouts and environmental damage. */
public final class ArenaMapTest {
    public static void main(String[] args) {
        for (ArenaMap map : ArenaMap.values()) {
            World world = new World(17L, map);
            int hero = world.spawnWizard(0f, 0f, HeroClass.WIZARD);
            world.step(Balance.FIXED_STEP, new InputCommand());
            check(world.obstacleCount() == map.obstacles().length,
                    map + " must spawn every authored collider exactly once");
            check(world.trapCount() == map.traps().length && world.trapCount() > 0,
                    map + " must expose its authored hazards");
            check(world.arenaMap() == map, "selected map must survive world construction");

            InputCommand runToCorner = new InputCommand();
            runToCorner.set(1f, 1f);
            for (int frame = 0; frame < 600; frame++) world.step(Balance.FIXED_STEP, runToCorner);
            check(Math.abs(world.x[hero]) <= map.halfWidth() - world.r[hero] + 0.01f,
                    map + " must keep the player inside its single-screen width");
            check(Math.abs(world.y[hero]) <= map.halfHeight() - world.r[hero] + 0.01f,
                    map + " must keep the player inside its single-screen height");

            ArenaMap.Trap first = map.traps()[0];
            world.x[hero] = first.x();
            world.y[hero] = first.y();
            world.px[hero] = first.x();
            world.py[hero] = first.y();
            float before = world.hp[hero];
            for (int frame = 0; frame < 720 && world.hp[hero] >= before; frame++) {
                world.step(Balance.FIXED_STEP, new InputCommand());
            }
            check(world.hp[hero] < before, map + " trap must actually damage a player in its active window");
        }
        System.out.println("OK: ArenaMapTest");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
