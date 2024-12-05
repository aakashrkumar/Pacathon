package com.buaisociety.pacman.entity.behavior;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.buaisociety.pacman.NeatConfig;
import com.buaisociety.pacman.entity.GhostEntity;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.Tile;
import com.buaisociety.pacman.maze.TileState;
import com.buaisociety.pacman.sprite.DebugDrawing;
import com.cjcrafter.neat.Client;
import com.buaisociety.pacman.entity.Direction;
import com.buaisociety.pacman.entity.Entity;
import com.buaisociety.pacman.entity.PacmanEntity;
import com.cjcrafter.neat.Neat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;

public class NeatPacmanBehavior implements Behavior {
    public static float epsilon = 1.0f;

    private final @NotNull Client client;
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

    private float[] lastOutputs = new float[NeatConfig.neatOutputNodes];

    private final Random random;
    List<Float> inputs = new ArrayList<>();

    List<Tile> highlightedTiles = new ArrayList<>();
    List<Tile> highlightedPellets = new ArrayList<>();

    public static boolean useRelative = true;

    public NeatPacmanBehavior(@NotNull Client client) {
        this.client = client;
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
        initializePacman(entity);
        updateDirections();

        highlightedTiles.clear();
        highlightedPellets.clear();
        distances = computeDistances();

        handleSpecialTrainingConditions();


        float[] inputs = buildInputs();

        float[] outputs = client.getCalculator().calculate(inputs).join();
        lastOutputs = outputs;

        Direction newDirection = selectDirectionFromOutputs(new float[]{outputs[0], outputs[1], outputs[2], outputs[3]});

        updateScore(newDirection);

        moveHistory.add(newDirection);
        return newDirection;
    }

    @Override
    public void render(@NotNull SpriteBatch batch) {
        if (pacman != null) {
            Tile nearestPellet = getNearestPellet();
            DebugDrawing.outlineTile(batch, nearestPellet, Color.GREEN);
            // Additional debug rendering can be added here
        }

        for(Tile tile : highlightedTiles){
            DebugDrawing.outlineTile(batch, tile, Color.RED);
        }

        for (Tile tile : highlightedPellets) {
            DebugDrawing.outlineTile(batch, tile, Color.BLUE);
        }
    }

    /**
     * Initializes the pacman entity if it's not already set.
     */
    private void initializePacman(@NotNull Entity entity) {
        if (pacman == null) {
            pacman = (PacmanEntity) entity;
        }
    }

    /**
     * Updates the directional fields based on Pacman's current direction.
     */
    private void updateDirections() {
        assert pacman != null;
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
                pacman.kill();
                return;
            }
        } else {
            updatesSinceLastScore++;
        }

        int maxUpdates = 60 * 10; // 10 seconds

        if(numGhosts > 0){
            maxUpdates = 60 * 60; // 1 minute
        }

        if (updatesSinceLastScore > maxUpdates) {
            pacman.kill();
        }
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
     * Builds the input array for the NEAT calculator.
     *
     * @return an array of input values
     */
    private float[] buildInputs() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
//        addMazeInfo();
        addRayCasts();
        // Disabled for debugging
        // addHistory();
//         addSuggestedPellet();
        // addGhostInfo();
        // addSuggestedPowerPellet();

        /** Disabled for inefficiency
        addVision(ghostInfo); too many inputs
        */
        // Convert List<Float> to float[]
        float[] inputArray = new float[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            inputArray[i] = inputs.get(i);
        }
        inputs.clear();
        return inputArray;
    }

    private void addMazeInfo() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        float pelletPercentage = pacman.getMaze().getPelletsRemaining() / (float) (dimensions.x() * dimensions.y());
        inputs.add(pelletPercentage);

    }

    private void addGhostInfo(){
        GhostInfo ghostInfo = gatherGhostInformation();
        float nearestDistance = 1000.0f;
        Vector2d nearestDirection = null;

        for (int i = 0; i < ghostInfo.ghostDistances.length; i++) {
            if (ghostInfo.ghostDirections[i] != null && ghostInfo.ghostDistances[i] < nearestDistance) {
                nearestDistance = ghostInfo.ghostDistances[i];
                nearestDirection = ghostInfo.ghostDirections[i];
            }
        }

        inputs.add(1.0f / (nearestDistance + epsilon));
        inputs.add(nearestDirection != null ? (float) nearestDirection.x() : 0f);
        inputs.add(nearestDirection != null ? (float) nearestDirection.y() : 0f);
        // timer
        float timeLeft = pacman.getMaze().getFrightenedTimer() <= 3 ? 0 : (float) (pacman.getMaze().getFrightenedTimer() / 200f + 0.5f);
        inputs.add(timeLeft);
    }

    private void addRayCasts() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        float maxDistance = dimensions.x() + dimensions.y(); // Maximum possible distance

        Vector2i forwardVec = pacman.getDirection().asVector();
        Vector2i rightVec = pacman.getDirection().right().asVector();
        if(!useRelative){
            forwardVec = new Vector2i(0, 1);
            rightVec = new Vector2i(1, 0);
        }

        // Define the 8 directions for raycasting
        Vector2i[] directions = new Vector2i[]{
            new Vector2i(forwardVec.x, forwardVec.y),
            new Vector2i(forwardVec.x + rightVec.x, forwardVec.y + rightVec.y),
            new Vector2i(rightVec.x, rightVec.y),
            new Vector2i(-forwardVec.x + rightVec.x, -forwardVec.y + rightVec.y),
//            new Vector2i(-forwardVec.x, -forwardVec.y),
//            new Vector2i(-forwardVec.x - rightVec.x, -forwardVec.y - rightVec.y),
//            new Vector2i(-rightVec.x, -rightVec.y),
//            new Vector2i(forwardVec.x - rightVec.x, forwardVec.y - rightVec.y)
        };

        float[] rayCastWalls = new float[directions.length];
        float[] rayCastPellets = new float[directions.length];

        for (int i = 0; i < directions.length; i++) {
            Vector2i direction = directions[i];
            Vector2i position = new Vector2i(pacman.getTilePosition());

            // Ray cast for walls
            int wallDistance = 0;
            while (isWithinBounds(position, dimensions) &&
                pacman.getMaze().getTile(position).getState() != TileState.WALL) {
                position.add(direction);
                wallDistance++;
            }

            if (isWithinBounds(position, dimensions)) {
                highlightedTiles.add(pacman.getMaze().getTile(position));
                // Normalize distance to [0,1] range - closer walls give higher values
                rayCastWalls[i] = 1.0f - (wallDistance / maxDistance);
            }

            // Reset position for pellet raycast
            position.set(pacman.getTilePosition());
            int pelletDistance = 0;

            // Ray cast for pellets
            while (isWithinBounds(position, dimensions) &&
                pacman.getMaze().getTile(position).getState() != TileState.PELLET &&
                pacman.getMaze().getTile(position).getState() != TileState.POWER_PELLET) {
                position.add(direction);
                pelletDistance++;
                if (!isWithinBounds(position, dimensions)) {
                    pelletDistance = (int)maxDistance; // If no pellet found, set to max distance
                    break;
                }
            }

            if (isWithinBounds(position, dimensions)) {
                highlightedPellets.add(pacman.getMaze().getTile(position));
                // Normalize distance to [0,1] range - closer pellets give higher values
                rayCastPellets[i] = 1.0f - (pelletDistance / maxDistance);
            }
        }

        // Add normalized distances to inputs
        for (float wallDist : rayCastWalls) {
            inputs.add(wallDist);
        }
        for (float pelletDist : rayCastPellets) {
            inputs.add(pelletDist);
        }
    }

    private void addHistory(){
        for (int i = 4; i < lastOutputs.length; i++) {
            inputs.add(lastOutputs[i]);
        }
    }

    private void addVision(GhostInfo ghostInfo){
        int visionRange = 4; // n by n grid around pacman
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Vector2i pacmanPos = pacman.getTilePosition();

        for(int y = -visionRange; y <= visionRange; y++){
            for(int x = -visionRange; x <= visionRange; x++){
                Vector2i pos = new Vector2i(pacmanPos.x() + x, pacmanPos.y() + y);
                if(isWithinBounds(pos, dimensions)){
                    Tile tile = pacman.getMaze().getTile(pos);
                    float tileState = 0;
                    if(tile.getState() == TileState.WALL){
                        tileState = 1;
                    } else if(tile.getState() == TileState.PELLET){
                        tileState = 2;
                    } else if(tile.getState() == TileState.POWER_PELLET){
                        tileState = 3;
                    }
                    else{
                        tileState = 0;
                    }
                    inputs.add(tileState);
                }else{
                    inputs.add(-1f);
                }
            }
        }
    }

    private void addSuggestedPowerPellet(){
        Tile nearestPowerPellet = getNearestPowerPellet();
        Vector2d relativePowerPelletPos = translateRelative(nearestPowerPellet.getPosition());

        Direction suggestedPowerDirection = getSuggestedDirection(nearestPowerPellet);
        Vector2d suggestedPowerDirRelative = rotateRelative(new Vector2d(
            suggestedPowerDirection.getDx(),
            suggestedPowerDirection.getDy()
        ));

        inputs.add((float) suggestedPowerDirRelative.x());
        inputs.add((float) suggestedPowerDirRelative.y());

    }
    private void addSuggestedPellet() {
        Tile nearestPellet = getNearestPellet();
        Vector2d relativePelletPos = translateRelative(nearestPellet.getPosition());

        Direction suggestedPelletDirection = getSuggestedDirection(nearestPellet);
        Vector2d suggestedPelletDirRelative = rotateRelative(new Vector2d(
            suggestedPelletDirection.getDx(),
            suggestedPelletDirection.getDy()
        ));

        inputs.add((float) suggestedPelletDirRelative.x());
        inputs.add((float) suggestedPelletDirRelative.y());
    }


    /**
     * Selects the direction based on the outputs from the NEAT calculator.
     *
     * @param outputs the output array from the NEAT calculator
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
        if (useRelative) {
            return switch (selectedIndex) {
                case 0 -> forward;
                case 1 -> left;
                case 2 -> right;
                case 3 -> behind;
                default -> throw new IllegalStateException("Unexpected output index: " + selectedIndex);
            };
        } else{
            return switch (selectedIndex) {
                case 0 -> Direction.UP;
                case 1 -> Direction.LEFT;
                case 2 -> Direction.RIGHT;
                case 3 -> Direction.DOWN;
                default -> throw new IllegalStateException("Unexpected output index: " + selectedIndex);
            };
        }

    }

    /**
     * Updates the score based on the new direction.
     *
     * @param newDirection the direction chosen
     */
    private void updateScore(Direction newDirection) {
        Vector2i newPosition = new Vector2i(
            pacman.getTilePosition().x() + newDirection.getDx(),
            pacman.getTilePosition().y() + newDirection.getDy()
        );


        Tile newTile = pacman.getMaze().getTile(newPosition.x(), newPosition.y());
//        if (newTile.getState() == TileState.PELLET) {
//            scoreModifier += 10; // Reward for collecting a pellet
//        } else if (newTile.getState() == TileState.POWER_PELLET) {
//            scoreModifier += 50; // Higher reward for collecting a power pellet
//        }

        // Optional: Penalize for invalid moves
//        if (!pacman.canMove(newDirection)) {
//            scoreModifier -= 5;
//        }

        // Optional: Small penalty to encourage faster completion
//        scoreModifier += 0.01f;


        client.setScore(
            pacman.getMaze().getLevelManager().getScore() + scoreModifier
        ); // TODO: Improve score heuristics
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
        System.out.println("No direction found");
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

        // Fallback to -1, -1 if no power pellets are found
        if (nearestPowerPellet == null) {
            nearestPowerPellet = pacman.getMaze().getTile(0, 0);
        }

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
