package com.buaisociety.pacman.entity.behavior;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.buaisociety.pacman.entity.Direction;
import com.buaisociety.pacman.entity.Entity;
import com.buaisociety.pacman.entity.GhostEntity;
import com.buaisociety.pacman.entity.PacmanEntity;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.Tile;
import com.buaisociety.pacman.maze.TileState;
import com.buaisociety.pacman.sprite.DebugDrawing;
import com.cjcrafter.neat.compute.Calculator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;

/**
 * TournamentBehavior class migrated from NeatPacmanBehavior.
 * Implements the Behavior interface and uses a neural network to decide Pacman's movement.
 */
public class TournamentBehavior implements Behavior {

    private final Calculator calculator;
    private @Nullable PacmanEntity pacman;

    private Direction forward = Direction.UP;
    private Direction left = Direction.LEFT;
    private Direction right = Direction.RIGHT;
    private Direction behind = Direction.DOWN;

    // Score modifiers help us maintain "multiple pools" of points.
    // This is great for training, because we can take away points from
    // specific pools of points instead of subtracting from all.
    private float scoreModifier = 0;

    public final List<Direction> moveHistory = new ArrayList<>();

    private int lastScore = 0;
    private int updatesSinceLastScore = 0;

    private int[][] distances;

    public int numGhosts = 0;

    public int visionRange = 2;

    private final Random random;

    int movesMade;

    ArrayList<Vector2d> positions = new ArrayList<Vector2d>();

    public TournamentBehavior(@NotNull Calculator calculator) {
        this.calculator = calculator;
        this.random = new Random();

    }

    /**
     * Returns the desired direction that the entity should move towards.
     *
     * @param entity the entity to get the direction for
     * @return the desired direction for the entity
     */
    @NotNull
    @Override
    public Direction getDirection(@NotNull Entity entity) {
        // --- DO NOT REMOVE ---
        if (pacman == null) {
        }
        pacman = (PacmanEntity) entity;

        int newScore = pacman.getMaze().getLevelManager().getScore();
        if (lastScore != newScore) {
            lastScore = newScore;
            updatesSinceLastScore = 0;
        } else {
            updatesSinceLastScore++;
        }

        if (updatesSinceLastScore > 60 * 40) { // 40 seconds
            pacman.kill();
            updatesSinceLastScore = 0;
        }
        // --- END OF DO NOT REMOVE ---

        // Initialize directions based on current direction
        updateDirections();

        // Compute distances using BFS
        distances = computeDistances();

        // Handle special training conditions (similar to original behavior)
        handleSpecialTrainingConditions();

        // Perform ray casting to detect walls
        float[] rayCastDistances = performRayCasting();

        // Get nearest pellets
        Tile nearestPellet = getNearestPellet();
        Vector2d relativePelletPos = translateRelative(nearestPellet.getPosition());

        Direction suggestedPelletDirection = getSuggestedDirection(nearestPellet);
        Vector2d suggestedPelletDirRelative = rotateRelative(new Vector2d(
            suggestedPelletDirection.getDx(),
            suggestedPelletDirection.getDy()
        ));

        // Get nearest power pellets
        Tile nearestPowerPellet = getNearestPowerPellet();
        Vector2d relativePowerPelletPos = translateRelative(nearestPowerPellet.getPosition());

        Direction suggestedPowerDirection = getSuggestedDirection(nearestPowerPellet);
        Vector2d suggestedPowerDirRelative = rotateRelative(new Vector2d(
            suggestedPowerDirection.getDx(),
            suggestedPowerDirection.getDy()
        ));

        // Gather ghost information
        GhostInfo ghostInfo = gatherGhostInformation();

        // Build inputs for the neural network
        float[] inputs = buildInputs(rayCastDistances, relativePelletPos, suggestedPelletDirRelative,
            nearestPellet, relativePowerPelletPos, suggestedPowerDirRelative, ghostInfo);

        // Calculate outputs from the neural network
        float[] outputs = calculator.calculate(inputs).join();

        // Select the direction based on outputs
        Direction newDirection = selectDirectionFromOutputs(outputs);

        // Add the new direction to move history
        moveHistory.add(newDirection);
        positions.add(new Vector2d(pacman.getTilePosition().x(), pacman.getTilePosition().y()));
        if (positions.size() > 100 && updatesSinceLastScore > 60) {
            positions.remove(0);
            if(positions.get(0).equals(positions.get(99))) {
                Random random = new Random();
//                newDirection = Direction.values()[random.nextInt(4)];
            }
        }


        return newDirection;
    }

    /**
     * Updates the directional fields based on Pacman's current direction.
     */
    private void updateDirections() {
        forward = pacman.getDirection();
        left = pacman.getDirection().left();
        right = pacman.getDirection().right();
        behind = pacman.getDirection().behind();
    }

    /**
     * Handles special training conditions such as score updates and kill conditions.
     */
    private void handleSpecialTrainingConditions() {
        int currentScore = pacman.getMaze().getLevelManager().getScore();
        if (currentScore > lastScore) {
            lastScore = currentScore;
            updatesSinceLastScore = 0;
            if (currentScore > 100_000) {
//                pacman.kill();
                return;
            }
        } else {
            updatesSinceLastScore++;
        }
        int maxUpdates = 60 * 10; // 10 seconds
        if (lastScore > 5000) {
            maxUpdates = 60 * 20; // 20 seconds
        }

        if (lastScore > 10000) {
            maxUpdates = 60 * 30; // 30 seconds
        }
        if (numGhosts > 0) {
            if (lastScore > 200) {
                maxUpdates = 60 * 120; // 2 minutes
            } else {
                maxUpdates = 60 * 3; // 3 seconds
            }
        }
        if (updatesSinceLastScore > maxUpdates) {
//            pacman.kill();
        }
    }

    /**
     * Performs ray casting in all four directions to detect distances to walls.
     *
     * @return an array containing distances in forward, left, right, and behind directions
     */
    private float[] performRayCasting() {
        float[] rayCast = new float[4];
        Vector2ic dimensions = pacman.getMaze().getDimensions();

        for (int i = 0; i < 4; i++) {
            Direction direction = getDirectionByIndex(i);
            Vector2i position = new Vector2i(pacman.getTilePosition().x(), pacman.getTilePosition().y());

            while (isWithinBounds(position, dimensions) &&
                pacman.getMaze().getTile(position).getState() != TileState.WALL) {
                position.add(direction.getDx(), direction.getDy());
                if (!isWithinBounds(position, dimensions)) {
                    break;
                }
                rayCast[i] = (float) Math.sqrt(
                    Math.pow((position.x() - pacman.getTilePosition().x()) / (double) dimensions.x(), 2) +
                        Math.pow((position.y() - pacman.getTilePosition().y()) / (double) dimensions.y(), 2)
                );
            }
        }

        return rayCast;
    }

    /**
     * Retrieves the corresponding Direction based on the index.
     *
     * @param index the index (0: forward, 1: left, 2: right, 3: behind)
     * @return the corresponding Direction
     */
    private Direction getDirectionByIndex(int index) {
        return switch (index) {
            case 0 -> forward;
            case 1 -> left;
            case 2 -> right;
            case 3 -> behind;
            default -> throw new IllegalStateException("Unexpected value: " + index);
        };
    }

    /**
     * Checks if the given position is within the maze boundaries.
     *
     * @param position   the position to check
     * @param dimensions the dimensions of the maze
     * @return true if within bounds, false otherwise
     */
    private boolean isWithinBounds(Vector2i position, Vector2ic dimensions) {
        return position.x() >= 0 && position.x() < dimensions.x() &&
            position.y() >= 0 && position.y() < dimensions.y();
    }

    /**
     * Gathers information about the ghosts in the maze.
     *
     * @return a GhostInfo object containing distances and directions of ghosts
     */
    private GhostInfo gatherGhostInformation() {
        List<GhostEntity> ghosts = getGhostEntities();
        numGhosts = ghosts.size();
        float[] ghostDistances = new float[4];
        Vector2d[] ghostDirections = new Vector2d[4];
        Arrays.fill(ghostDistances, 1);

        int ghostIndex = 0;
        for (GhostEntity ghost : ghosts) {
            if (ghostIndex >= 4) break; // Limit to 4 ghosts
            float distance = distances[ghost.getTilePosition().x()][ghost.getTilePosition().y()];
            if (distance == -1) continue;
            Tile ghostTile = pacman.getMaze().getTile(ghost.getTilePosition());
            Direction direction = getFirstStepToTile(ghostTile);
            if (direction == null) continue;

            ghostDistances[ghostIndex] = distance / (pacman.getMaze().getDimensions().x() + pacman.getMaze().getDimensions().y());
            ghostDirections[ghostIndex] = rotateRelative(new Vector2d(direction.getDx(), direction.getDy()));
            ghostIndex++;
        }

        return new GhostInfo(ghostDistances, ghostDirections);
    }

    /**
     * Retrieves all ghost entities from the maze.
     *
     * @return a list of GhostEntity objects
     */
    private List<GhostEntity> getGhostEntities() {
        List<Entity> entities = pacman.getMaze().getEntities();
        List<GhostEntity> ghosts = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof GhostEntity ghost) {
                ghosts.add(ghost);
            }
        }
        return ghosts;
    }

    /**
     * Builds the input array for the neural network.
     *
     * @param rayCastDistances           distances from ray casting
     * @param relativePelletPos          relative position to the nearest pellet
     * @param suggestedPelletDirRelative relative direction to the nearest pellet
     * @param nearestPellet              the nearest pellet tile
     * @param relativePowerPelletPos     relative position to the nearest power pellet
     * @param suggestedPowerDirRelative  relative direction to the nearest power pellet
     * @param ghostInfo                  information about ghosts
     * @return an array of input values
     */
    private float[] buildInputs(float[] rayCastDistances, Vector2d relativePelletPos,
                                Vector2d suggestedPelletDirRelative, Tile nearestPellet,
                                Vector2d relativePowerPelletPos, Vector2d suggestedPowerDirRelative,
                                GhostInfo ghostInfo) {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        return new float[]{
            // Suggested pellet direction (x, y)
            (float) suggestedPelletDirRelative.x(),
            (float) suggestedPelletDirRelative.y(),

            // Relative power pellet coordinates
//            (float) relativePowerPelletPos.x() / dimensions.x(),
//            (float) relativePowerPelletPos.y() / dimensions.y(),

            // Suggested power pellet direction (x, y)
            (float) suggestedPowerDirRelative.x(),
            (float) suggestedPowerDirRelative.y(),

            // Ghost distances
            ghostInfo.ghostDistances[0],
//            ghostInfo.ghostDistances[1],
//            ghostInfo.ghostDistances[2],
//            ghostInfo.ghostDistances[3],

            // Ghost directions (x, y) for each ghost
            ghostInfo.ghostDirections[0] != null ? (float) ghostInfo.ghostDirections[0].x() : 0,
            ghostInfo.ghostDirections[0] != null ? (float) ghostInfo.ghostDirections[0].y() : 0,
//            ghostInfo.ghostDirections[1] != null ? (float) ghostInfo.ghostDirections[1].x() : 0,
//            ghostInfo.ghostDirections[1] != null ? (float) ghostInfo.ghostDirections[1].y() : 0,
//            ghostInfo.ghostDirections[2] != null ? (float) ghostInfo.ghostDirections[2].x() : 0,
//            ghostInfo.ghostDirections[2] != null ? (float) ghostInfo.ghostDirections[2].y() : 0,
//            ghostInfo.ghostDirections[3] != null ? (float) ghostInfo.ghostDirections[3].x() : 0,
//            ghostInfo.ghostDirections[3] != null ? (float) ghostInfo.ghostDirections[3].y() : 0,

            // Frightened timer status
            pacman.getMaze().getFrightenedTimer() <= 3 ? 0 : (float) (pacman.getMaze().getFrightenedTimer() / 200f + 0.5f),

            // Random input for variability
//            random.nextFloat(),
        };
    }

    /**
     * Selects the direction based on the outputs from the neural network.
     *
     * @param outputs the output array from the neural network
     * @return the selected Direction
     */
    private Direction selectDirectionFromOutputs(float[] outputs) {
        int selectedIndex = 0;
        float maxOutput = outputs[0];

        for (int i = 1; i < outputs.length; i++) {
            if (outputs[i] > maxOutput) {
                maxOutput = outputs[i];
                selectedIndex = i;
            }
        }

        return switch (selectedIndex) {
            case 0 -> forward;
            case 1 -> left;
            case 2 -> right;
            case 3 -> behind;
            default -> throw new IllegalStateException("Unexpected output index: " + selectedIndex);
        };
    }



    /**
     * Retrieves the first step direction towards the target tile using the pre-computed distances.
     *
     * @param targetTile the destination tile
     * @return the Direction to move in, or null if no path exists
     */
    @Nullable
    public Direction getFirstStepToTile(@NotNull Tile targetTile) {
        Vector2ic targetPos = targetTile.getPosition();
        Vector2ic currentPos = pacman.getTilePosition();
        Vector2ic dimensions = pacman.getMaze().getDimensions();

        // If we're already at the target, no need to move
        if (currentPos.equals(targetPos)) {
            return null;
        }

        // If target is unreachable (distance == -1), return null
        if (distances[targetPos.x()][targetPos.y()] == -1) {
            return null;
        }

        // Start from the target and work backwards to find the first step
        Vector2i backtrackPos = new Vector2i(targetPos);
        int currentDistance = distances[targetPos.x()][targetPos.y()];

        while (currentDistance > 1) {
            boolean stepFound = false;

            for (Direction dir : Direction.values()) {
                int nextX = backtrackPos.x() + dir.getDx();
                int nextY = backtrackPos.y() + dir.getDy();

                if (!isWithinBounds(new Vector2i(nextX, nextY), dimensions)) {
                    continue;
                }

                if (distances[nextX][nextY] == currentDistance - 1) {
                    backtrackPos.set(nextX, nextY);
                    currentDistance--;
                    stepFound = true;
                    break;
                }
            }

            if (!stepFound) {
                return null; // Path is broken
            }
        }

        // Determine the direction from Pacman's current position to the backtrack position
        for (Direction dir : Direction.values()) {
            int stepX = currentPos.x() + dir.getDx();
            int stepY = currentPos.y() + dir.getDy();

            if (stepX == backtrackPos.x() && stepY == backtrackPos.y()) {
                return dir;
            }
        }

        return null; // Should not reach here
    }

    /**
     * Translates the given coordinates to be relative to Pacman's position and orientation.
     *
     * @param coordinates the coordinates to translate
     * @return the translated coordinates
     */
    private Vector2d translateRelative(Vector2ic coordinates) {
        Vector2i pacmanPos = pacman.getTilePosition();
        Vector2d relative = new Vector2d(
            coordinates.x() - pacmanPos.x(),
            coordinates.y() - pacmanPos.y()
        );
        return rotateRelative(relative);
    }

    /**
     * Rotates the given coordinates based on Pacman's current direction.
     *
     * @param coordinates the coordinates to rotate
     * @return the rotated coordinates
     */
    private Vector2d rotateRelative(Vector2d coordinates) {
        // Assuming rotation based on forward and right directions
        double rotatedX = coordinates.x() * forward.getDx() + coordinates.y() * right.getDx();
        double rotatedY = coordinates.x() * forward.getDy() + coordinates.y() * right.getDy();
        return new Vector2d(rotatedX, rotatedY);
    }

    /**
     * Finds the nearest power pellet in the maze.
     *
     * @return the nearest power pellet tile
     */
    @NotNull
    public Tile getNearestPowerPellet() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Tile nearestPowerPellet = null;

        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.POWER_PELLET) {
                    if (nearestPowerPellet == null ||
                        distances[x][y] < distances[nearestPowerPellet.getPosition().x()][nearestPowerPellet.getPosition().y()]) {
                        nearestPowerPellet = tile;
                    }
                }
            }
        }

        // Fallback to (0,0) if no power pellets are found
        if (nearestPowerPellet == null) {
            nearestPowerPellet = pacman.getMaze().getTile(0, 0);
        }

        System.out.println(nearestPowerPellet.getPosition());

        return nearestPowerPellet;
    }

    /**
     * Finds the nearest pellet (regular or power) in the maze.
     *
     * @return the nearest pellet tile
     */
    @NotNull
    public Tile getNearestPellet() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Tile nearestPellet = null;

        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.PELLET || tile.getState() == TileState.POWER_PELLET) {
                    if (nearestPellet == null ||
                        distances[x][y] < distances[nearestPellet.getPosition().x()][nearestPellet.getPosition().y()]) {
                        nearestPellet = tile;
                    }
                }
            }
        }


        if(nearestPellet == null) {
            nearestPellet = pacman.getMaze().getTile(0, 0);
        }
        System.out.println(nearestPellet.getPosition());
        return nearestPellet;
    }

    /**
     * Computes the distances from Pacman's current position to all reachable tiles using BFS.
     *
     * @return a 2D array of distances
     */
    public int[][] computeDistances() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Vector2ic pacmanPos = pacman.getTilePosition();
        int[][] distance = new int[dimensions.x()][dimensions.y()];

        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                distance[x][y] = -1;
            }
        }

        distance[pacmanPos.x()][pacmanPos.y()] = 0;
        Queue<Vector2i> queue = new LinkedList<>();
        queue.add(new Vector2i(pacmanPos));

        while (!queue.isEmpty()) {
            Vector2i current = queue.poll();
            for (Direction dir : Direction.values()) {
                int nextX = current.x() + dir.getDx();
                int nextY = current.y() + dir.getDy();

                if (!isWithinBounds(new Vector2i(nextX, nextY), dimensions)) {
                    continue;
                }

                if (pacman.getMaze().getTile(nextX, nextY).getState() != TileState.WALL &&
                    distance[nextX][nextY] == -1) {
                    distance[nextX][nextY] = distance[current.x()][current.y()] + 1;
                    queue.add(new Vector2i(nextX, nextY));
                }
            }
        }

        return distance;
    }

    /**
     * Retrieves the suggested direction towards the target tile.
     *
     * @param targetTile the target tile
     * @return the suggested Direction
     */
    @Nullable
    private Direction getSuggestedDirection(Tile targetTile) {
        Direction direction = getFirstStepToTile(targetTile);
        return direction != null ? direction : pacman.getDirection();
    }

    /**
     * Helper class to store ghost distances and directions.
     */
    private static class GhostInfo {
        float[] ghostDistances;
        Vector2d[] ghostDirections;

        GhostInfo(float[] ghostDistances, Vector2d[] ghostDirections) {
            this.ghostDistances = ghostDistances;
            this.ghostDirections = ghostDirections;
        }
    }
}
