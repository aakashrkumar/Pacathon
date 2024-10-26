package com.buaisociety.pacman;

import com.buaisociety.pacman.entity.EntityType;
import com.buaisociety.pacman.event.CreateMazeEvent;
import com.buaisociety.pacman.event.EntityPreSpawnEvent;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.TileState;
import com.buaisociety.pacman.util.EventListener;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2ic;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Just a quick utility class to help with your special training conditions.
 * You can add as many methods as needed to help with your training. This is
 * good at keeping all your "weird conditions" in one place, so you don't
 * accidentally mess up the logic somewhere else in the game.
 *
 * <p>For example, you can prevent certain entities from spawning, modify the
 * maze, or even change the behavior of the entities during training.
 */
public final class SpecialTrainingConditions {
    // Prevent instantiation
    private SpecialTrainingConditions() {
    }

    public static @NotNull EventListener<EntityPreSpawnEvent> onEntityPreSpawn() {
        return event -> {
            // Prevent ghosts from spawning during training
            if (event.getEntityType() == EntityType.GHOST) {
//                event.setCancelled(true);
            }
        };
    }

    public static @NotNull EventListener<CreateMazeEvent> onCreateMaze() {
        return event -> {
            Maze maze = event.getMaze();

            // Go through each cell in the maze and try to remove pellets
            // During training, this is useful since the randomness will help
            // prevent overfitting
            double pelletDensity = ThreadLocalRandom.current().nextDouble();
            Vector2ic dimensions = maze.getDimensions();
            for (int x = 0; x < dimensions.x(); x++) {
                for (int y = 0; y < dimensions.y(); y++) {
                    TileState tileState = maze.getTile(x, y).getState();
                    if (tileState != TileState.PELLET && tileState != TileState.POWER_PELLET)
                        continue;

                    if (ThreadLocalRandom.current().nextDouble() < pelletDensity) {
//                        maze.getTile(x, y).setState(TileState.SPACE);
                    }
                }
            }

            // Recalculate maze pellets after removing some
            maze.initTiles();

            // spawn in a fruit by default to help Pacman learn to eat them
            if (ThreadLocalRandom.current().nextDouble() < 0.75) {
                maze.spawnFruit();
            }
        };
    }
}
