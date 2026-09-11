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
            check(map.terrain().length > 0, map + " must expose interactive terrain");
            check(world.arenaMap() == map, "selected map must survive world construction");
            check(map.expeditionNodeCount() >= 7 && map.routeSectionCount() >= 5,
                    map + " must expose a multi-node snake expedition instead of one fixed room");
            check(map.expeditionNode(0).role() == ArenaMap.ExpeditionRole.CORE
                            && map.isWalkable(0f, 0f, Balance.WIZARD_RADIUS),
                    map + " original map must remain a walkable expedition core");
            boolean hasBoss = false;
            for (int i = 0; i < map.expeditionNodeCount(); i++) {
                ArenaMap.ExpeditionNode node = map.expeditionNode(i);
                hasBoss |= node.role() == ArenaMap.ExpeditionRole.BOSS;
                check(map.isWalkable(node.x(), node.y(), Balance.WIZARD_RADIUS),
                        map + " every authored expedition node must be physically reachable terrain");
            }
            check(hasBoss && !map.isWalkable(map.halfWidth() - 50f, map.halfHeight() - 50f, Balance.WIZARD_RADIUS),
                    map + " must use a route mask rather than a larger rectangular room");

            World spawnCheck = new World(73L, map);
            spawnCheck.spawnWizard(0f, 0f, HeroClass.WIZARD);
            spawnCheck.step(Balance.FIXED_STEP, new InputCommand());
            for (int n = 0; n < 6; n++) spawnCheck.spawnEnemyVariant(World.V_NORMAL);
            for (int id = 0; id < spawnCheck.highWater(); id++) {
                if (spawnCheck.alive[id] && spawnCheck.kind[id] == World.KIND_ENEMY) {
                    check(map.isWalkable(spawnCheck.x[id], spawnCheck.y[id], spawnCheck.r[id]),
                            map + " enemies must spawn on reachable snake-route terrain");
                }
            }

            ArenaMap.Terrain firstTerrain = map.terrain()[0];
            check(map.terrainAt(firstTerrain.x(), firstTerrain.y()) == firstTerrain
                            && Math.abs(map.movementMultiplierAt(firstTerrain.x(), firstTerrain.y())
                            - firstTerrain.movementMultiplier()) < 0.001f,
                    map + " terrain must expose its authored movement effect");
            world.x[hero] = firstTerrain.x();
            world.y[hero] = firstTerrain.y();
            InputCommand runEast = new InputCommand();
            runEast.set(1f, 0f);
            world.step(Balance.FIXED_STEP, runEast);
            float expectedTerrainStep = HeroClass.baseSpeed(HeroClass.WIZARD)
                    * firstTerrain.movementMultiplier() * Balance.FIXED_STEP;
            check(Math.abs(world.x[hero] - firstTerrain.x() - expectedTerrainStep) < 0.05f,
                    map + " terrain must change actual player movement speed");

            for (ArenaMap.Obstacle obstacle : map.obstacles()) {
                check(obstacle.rule() == ArenaMap.ObstacleRule.SOLID
                                && obstacle.blocksMovement() && obstacle.blocksProjectiles() && !obstacle.destructible(),
                        map + " authored cover must explicitly use solid movement and projectile blocking");
                world.x[hero] = obstacle.x() + obstacle.footprintHalfWidth() - 1f;
                world.y[hero] = obstacle.y();
                world.step(Balance.FIXED_STEP, new InputCommand());
                world.step(Balance.FIXED_STEP, new InputCommand());
                check(!obstacle.overlapsCircle(world.x[hero], world.y[hero], world.r[hero]),
                        map + " " + obstacle.shape() + " obstacle must physically block the player");
            }

            InputCommand runToCorner = new InputCommand();
            runToCorner.set(1f, 1f);
            for (int frame = 0; frame < 600; frame++) world.step(Balance.FIXED_STEP, runToCorner);
            check(Math.abs(world.x[hero]) <= map.halfWidth() - world.r[hero] + 0.01f,
                    map + " must keep the player inside its authored map width");
            check(Math.abs(world.y[hero]) <= map.halfHeight() - world.r[hero] + 0.01f,
                    map + " must keep the player inside its authored map height");

            ArenaMap.Trap first = map.traps()[0];
            for (ArenaMap.Trap trap : map.traps()) {
                float damageHalfW = trap.isLane() ? trap.halfWidth() : trap.radius();
                float damageHalfH = trap.isLane() ? trap.halfHeight() : trap.radius();
                check(trap.visualHalfWidth() > damageHalfW + Balance.WIZARD_RADIUS
                                && trap.visualHalfHeight() > damageHalfH + Balance.WIZARD_RADIUS,
                        map + " warning art must extend beyond the real player damage boundary");
                assertFourTrapStages(map, trap);
            }
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

        ArenaMap.Trap arrowLane = ArenaMap.STONE_CRYPT.traps()[2];
        check(arrowLane.isLane() && arrowLane.contains(arrowLane.x(), arrowLane.y(), 0f)
                        && !arrowLane.contains(arrowLane.x(), arrowLane.y() - arrowLane.halfHeight() - 1f, 0f),
                "crypt arrow mechanism must be a narrow horizontal damage lane");
        check(arrowLane.visualHalfHeight() > arrowLane.halfHeight() + Balance.WIZARD_RADIUS,
                "crypt arrow warning must be wider than its real player damage lane");

        World crypt = new World(23L, ArenaMap.STONE_CRYPT);
        int laneHero = crypt.spawnWizard(arrowLane.x(), arrowLane.y(), HeroClass.WIZARD);
        float laneBefore = crypt.hp[laneHero];
        for (int frame = 0; frame < 120 && crypt.hp[laneHero] >= laneBefore; frame++) {
            crypt.step(Balance.FIXED_STEP, new InputCommand());
        }
        check(crypt.hp[laneHero] < laneBefore, "crypt arrow lane must damage a player during its active window");
        check(hasShape(ArenaMap.DESERT_RUINS, ArenaMap.ObstacleShape.CAPSULE)
                        && hasShape(ArenaMap.DESERT_RUINS, ArenaMap.ObstacleShape.BOX),
                "desert must use a narrow grounded footprint instead of oversized circles");
        check(hasShape(ArenaMap.STONE_CRYPT, ArenaMap.ObstacleShape.CIRCLE)
                        && hasShape(ArenaMap.STONE_CRYPT, ArenaMap.ObstacleShape.CAPSULE)
                        && hasShape(ArenaMap.STONE_CRYPT, ArenaMap.ObstacleShape.BOX),
                "crypt must use circle, capsule and box colliders by object type");
        System.out.println("OK: ArenaMapTest");
    }

    private static boolean hasShape(ArenaMap map, ArenaMap.ObstacleShape shape) {
        for (ArenaMap.Obstacle obstacle : map.obstacles()) {
            if (obstacle.shape() == shape) return true;
        }
        return false;
    }

    private static void assertFourTrapStages(ArenaMap map, ArenaMap.Trap trap) {
        float anchor = (trap.cycle() - trap.phase(0f)) % trap.cycle();
        check(trap.telegraphing(anchor + 0.01f), map + " trap must start with a visible telegraph");
        check(trap.arming(anchor + trap.telegraph() + 0.01f), map + " trap must have a distinct arming beat");
        check(trap.active(anchor + trap.telegraph() + trap.arming() + 0.01f),
                map + " trap must only damage after arming");
        check(trap.recovering(anchor + trap.telegraph() + trap.arming() + trap.active() + 0.01f),
                map + " trap must recover before its next warning");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
