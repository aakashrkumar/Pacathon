package com.buaisociety.pacman.entity.behavior;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.buaisociety.pacman.entity.GhostEntity;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.Tile;
import com.buaisociety.pacman.maze.TileState;
import com.buaisociety.pacman.sprite.DebugDrawing;
import com.cjcrafter.neat.Client;
import com.buaisociety.pacman.entity.Direction;
import com.buaisociety.pacman.entity.Entity;
import com.buaisociety.pacman.entity.PacmanEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.*;

public class NeatPacmanBehavior implements Behavior {

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

    public ArrayList<Direction> moveHistory = new ArrayList<>();

    int lastScore = 0;
    int updateSinceLastScore = 0;

    int[][] distances;

    Random random;

    public NeatPacmanBehavior(@NotNull Client client) {
        this.client = client;
        random = new Random();
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
        if (pacman == null) {
            pacman = (PacmanEntity) entity;
        }


        distances = computeDistances();
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        // We are going to use these directions a lot for different inputs. Get them all once for clarity and brevity
        forward = pacman.getDirection();
        left = pacman.getDirection().left();
        right = pacman.getDirection().right();
        behind = pacman.getDirection().behind();


        // SPECIAL TRAINING CONDITIONS
        // TODO: Make changes here to help with your training...
        int newScore = pacman.getMaze().getLevelManager().getScore();
        if (newScore > lastScore) {
            lastScore = newScore;
            updateSinceLastScore = 0;
            if(newScore > 100_000){
                pacman.kill();
                return Direction.UP;
            }
        } else {
            updateSinceLastScore++;
        }

        if (updateSinceLastScore > 60 * 10) {
            pacman.kill();
            return Direction.UP;

        }

        // END OF SPECIAL TRAINING CONDITIONS


        // Input nodes 1, 2, 3, and 4 show if the pacman can move in the forward, left, right, and behind directions
        boolean canMoveForward = pacman.canMove(forward);
        boolean canMoveLeft = pacman.canMove(left);
        boolean canMoveRight = pacman.canMove(right);
        boolean canMoveBehind = pacman.canMove(behind);

        float[] rayCast = new float[4];
        for (int i = 0; i < 4; i++) {
            Direction direction = switch (i) {
                case 0 -> forward;
                case 1 -> left;
                case 2 -> right;
                case 3 -> behind;
                default -> throw new IllegalStateException("Unexpected value: " + i);
            };
            Vector2i position = new Vector2i(pacman.getTilePosition().x(), pacman.getTilePosition().y());
            while (pacman.getMaze().getTile(position).getState() != TileState.WALL) {
                position.add(direction.getDx(), direction.getDy());
                if (position.x() < 0 || position.x() >= pacman.getMaze().getDimensions().x() || position.y() < 0 || position.y() >= pacman.getMaze().getDimensions().y()) {
                    break;
                }
                rayCast[i] = (float) Math.sqrt(Math.pow((0.0 + position.x() - pacman.getTilePosition().x()) / dimensions.x(), 2) + Math.pow((position.y() - pacman.getTilePosition().y() + 0.0) / dimensions.y(), 2));
            }
        }

        Tile nearestPellet = getNearestPellet();
        Vector2d relativeCoordinates = translateRelative(nearestPellet.getPosition());

        Direction suggestedDirection = getFirstStepToTile(nearestPellet);
        if (suggestedDirection == null) {
            suggestedDirection = pacman.getDirection();
        }
        // transform suggested direction to be relative to pacman
        Vector2d suggestedDirectionRelative = rotateRelative(new Vector2d(suggestedDirection.getDx(), suggestedDirection.getDy()));

        Tile nearestPowerPellet = nearestPowerPellet();
        if(nearestPowerPellet == null){
            nearestPowerPellet = pacman.getMaze().getTile(pacman.getTilePosition());
        }
        Vector2d relativePowerPellet = translateRelative(nearestPowerPellet.getPosition());

        Direction suggestedPowerDirection = getFirstStepToTile(nearestPowerPellet);
        if (suggestedPowerDirection == null) {
            suggestedPowerDirection = pacman.getDirection();
        }
        Vector2d suggestedPowerDirectionRelative = rotateRelative(new Vector2d(suggestedPowerDirection.getDx(), suggestedPowerDirection.getDy()));


        List<Entity> entities = pacman.getMaze().getEntities();

        List<GhostEntity> ghosts = new ArrayList<>();
        for(Entity mapEntity : entities){
            if(mapEntity instanceof PacmanEntity){
                continue;
            }
            if(mapEntity instanceof GhostEntity){
                ghosts.add((GhostEntity) mapEntity);
            }
        }

        int[] ghostDistances = new int[4];
        Vector2d[] ghostDirections = new Vector2d[4];
        for(int i = 0; i < 4; i++){
            ghostDistances[i] = -1;
            ghostDirections[i] = null;
        }
        int ghostIndex = 0;
        for(GhostEntity ghost : ghosts){
            int distance = distances[ghost.getTilePosition().x()][ghost.getTilePosition().y()];
            if(distance == -1){
                continue;
            }
            Vector2i vector2i = ghost.getTilePosition();
            Tile tile = pacman.getMaze().getTile(vector2i);
            Direction direction = getFirstStepToTile(tile);
            if(direction == null){
                continue;
            }
            ghostDistances[ghostIndex] = distance;
            Vector2d relativeDirection = new Vector2d(direction.getDx(), direction.getDy());
            relativeDirection = rotateRelative(relativeDirection);
            ghostDirections[ghostIndex] = relativeDirection;
            ghostIndex++;
        }


        float[] inputs = new float[]{
            rayCast[0],
            rayCast[1],
            rayCast[2],
            rayCast[3],
            (float) relativeCoordinates.x() / (float) dimensions.x(),
            (float) relativeCoordinates.y() / (float) dimensions.y(),

            // suggested direction x, y
            (float) suggestedDirectionRelative.x(),
            (float) suggestedDirectionRelative.y(),
            // distance to nearest pellet
            distances[nearestPellet.getPosition().x()][nearestPellet.getPosition().y()],

//            (float) relativePowerPellet.x() / (float) dimensions.x(),
//            (float) relativePowerPellet.y() / (float) dimensions.y(),
//            (float) suggestedPowerDirectionRelative.x(),
//            (float) suggestedPowerDirectionRelative.y(),
//            // distance to nearest power pellet
//            distances[nearestPowerPellet.getPosition().x()][nearestPowerPellet.getPosition().y()],
//            ghostDistances[0],
//            ghostDistances[1],
//            ghostDistances[2],
//            ghostDistances[3],
//            ghostDirections[0] == null ? 0 : (float) ghostDirections[0].x(),
//            ghostDirections[0] == null ? 0 : (float) ghostDirections[0].y(),
//            ghostDirections[1] == null ? 0 : (float) ghostDirections[1].x(),
//            ghostDirections[1] == null ? 0 : (float) ghostDirections[1].y(),
//            ghostDirections[2] == null ? 0 : (float) ghostDirections[2].x(),
//            ghostDirections[2] == null ? 0 : (float) ghostDirections[2].y(),
//            ghostDirections[3] == null ? 0 : (float) ghostDirections[3].x(),
//            ghostDirections[3] == null ? 0 : (float) ghostDirections[3].y(),

        //            pacman.getDirection().ordinal() / 4.0f,
        //            (float) pacman.getTilePosition().x() / (float) dimensions.x(),
        //            (float) pacman.getTilePosition().y() / (float) dimensions.y(),

            random.nextFloat() * 2 - 1,
        };

//        System.out.println(Arrays.toString(inputs));


        float[] outputs = client.getCalculator().calculate(inputs).join();

        int index = 0;
        float max = outputs[0];
        for (int i = 1; i < outputs.length; i++) {
            if (outputs[i] > max) {
                max = outputs[i];
                index = i;
            }
        }

        Direction newDirection = switch (index) {
            case 0 -> pacman.getDirection();
            case 1 -> pacman.getDirection().left();
            case 2 -> pacman.getDirection().right();
            case 3 -> pacman.getDirection().behind();
            default -> throw new IllegalStateException("Unexpected value: " + index);
        };

        // ideas
        /*
         * Give reward for moving in direction of nearest pellet
         * Give reward for eating pellet
         * Give reward for eating power pellet
         * Give reward for eating fruit
         * Give reward for eating ghost
         * Give penalty for getting eaten by ghost
         * Being alive is a penalty unless you run out of power pellets
         *
         * Reward engineering
         */
        // Modded Rewards
        Vector2ic newPosition = new Vector2i(pacman.getTilePosition().x() + newDirection.getDx(), pacman.getTilePosition().y() + newDirection.getDy());
//        if(newPosition.distanceSquared(nearestPellet.getPosition()) < pacman.getTilePosition().distanceSquared(nearestPellet.getPosition())){
//            scoreModifier += 5;
//        }

//        if(pacman.getMaze().getTile((Vector2i) newPosition).getState() == TileState.PELLET){
//            scoreModifier += 100;
//        }
//
//        if (!pacman.canMove(newDirection)) {
//            scoreModifier -= 10;
//        }

//        scoreModifier -= 0.1f;

        client.setScore(
            pacman.getMaze().getLevelManager().getScore() + scoreModifier
        ); // need to improve score hurisitcs


        moveHistory.add(newDirection);
        return newDirection;
    }


    @Override
    public void render(@NotNull SpriteBatch batch) {
        // TODO: You can render debug information here
        /*
        if (pacman != null) {
            DebugDrawing.outlineTile(batch, pacman.getMaze().getTile(pacman.getTilePosition()), Color.RED);
            DebugDrawing.drawDirection(batch, pacman.getTilePosition().x() * Maze.TILE_SIZE, pacman.getTilePosition().y() * Maze.TILE_SIZE, pacman.getDirection(), Color.RED);
        }
         */
        if (pacman != null) {
            Tile nearestPellet = getNearestPellet();
            DebugDrawing.outlineTile(batch, nearestPellet, Color.GREEN);
        }
    }

    /**
     * Gets the first direction Pacman should move to reach the target tile via the shortest path.
     * Uses the pre-computed distances array to work backwards from the target to find the optimal first step.
     *
     * @param targetTile the destination tile we want to reach
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
        Vector2i currentBacktrack = new Vector2i(targetPos);
        int currentDistance = distances[targetPos.x()][targetPos.y()];

        // Keep backtracking until we find a position adjacent to our current position
        while (currentDistance > 1) {
            boolean foundStep = false;

            // Check all adjacent tiles
            for (Direction dir : Direction.values()) {
                int nextX = currentBacktrack.x() + dir.getDx();
                int nextY = currentBacktrack.y() + dir.getDy();

                // Skip if out of bounds
                if (nextX < 0 || nextX >= dimensions.x() ||
                    nextY < 0 || nextY >= dimensions.y()) {
                    continue;
                }

                // If this adjacent tile is one step closer to Pacman
                if (distances[nextX][nextY] == currentDistance - 1) {
                    currentBacktrack.set(nextX, nextY);
                    currentDistance--;
                    foundStep = true;
                    break;
                }
            }

            // If we couldn't find a valid step back, something's wrong with the path
            if (!foundStep) {
                return null;
            }
        }

        // Now currentBacktrack should be adjacent to Pacman's position
        // Find which direction leads to it
        for (Direction dir : Direction.values()) {
            int stepX = currentPos.x() + dir.getDx();
            int stepY = currentPos.y() + dir.getDy();

            if (stepX == currentBacktrack.x() && stepY == currentBacktrack.y()) {
                return dir;
            }
        }

        // If we get here, something went wrong
        return null;
    }

    /**
     * Translates the given coordinates to be relative to Pacman's position and orientation.
     * This is useful for converting the target tile's position to a relative position.
     *
     * @param coordinates the coordinates to translate
     * @return the translated coordinates
     */
    public Vector2d translateRelative(Vector2ic coordinates) {
        // new coordinates are relative to pacman

        Vector2i pacmanCoordinates = pacman.getTilePosition();
        Vector2d relative = new Vector2d(coordinates.x() - pacmanCoordinates.x(), coordinates.y() - pacmanCoordinates.y());
        // also orient the coordinates to the direction of pacman
        double newCoordinatesX = relative.x() * forward.getDx() + relative.x() * right.getDx();
        double newCoordinatesY = relative.y() * forward.getDy() + relative.y() * right.getDy();
        relative.set(newCoordinatesX, newCoordinatesY);
        return relative;
    }


    public Vector2d rotateRelative(Vector2d coordinates){
        Vector2d relative = new Vector2d(coordinates.x(), coordinates.y());
        // also orient the coordinates to the direction of pacman
        double newCoordinatesX = relative.x() * forward.getDx() + relative.x() * right.getDx();
        double newCoordinatesY = relative.y() * forward.getDy() + relative.y() * right.getDy();
        relative.set(newCoordinatesX, newCoordinatesY);
        return relative;
    }
    public Vector2d translateRelative(Vector2d coordinates){
        Vector2i pacmanCoordinates = pacman.getTilePosition();
        Vector2d relative = new Vector2d(coordinates.x - pacmanCoordinates.x(), coordinates.y - pacmanCoordinates.y);
        double newCoordinatesX = relative.x * forward.getDx() + relative.x * right.getDx();
        double newCoordinatesY = relative.y * forward.getDy() + relative.y * right.getDy();
        relative.set(newCoordinatesX, newCoordinatesY);
        return relative;
    }

    public Tile nearestPowerPellet() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Tile nearestPowerPellet = null;
        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.POWER_PELLET) {
                    if (nearestPowerPellet == null) {
                        nearestPowerPellet = tile;
                    } else {
                        if (distances[x][y] < distances[nearestPowerPellet.getPosition().x()][nearestPowerPellet.getPosition().y()]) {
                            nearestPowerPellet = tile;
                        }
                    }
                }
            }
        }
        return nearestPowerPellet;
    }

    public Tile getNearestPellet() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Tile nearestPellet = null;
        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.PELLET || tile.getState() == TileState.POWER_PELLET) {
                    if (nearestPellet == null) {
                        nearestPellet = tile;
                    } else {
                        if (distances[x][y] < distances[nearestPellet.getPosition().x()][nearestPellet.getPosition().y()]) {
                            nearestPellet = tile;
                        }
                    }
                }
            }
        }
        return nearestPellet;
    }

    public int[][] computeDistances() {
        // path search
        Vector2ic dimensions = this.pacman.getMaze().getDimensions();
        Vector2ic pacmanPosition = this.pacman.getTilePosition();
        int[][] distance = new int[dimensions.x()][dimensions.y()];
        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                distance[x][y] = -1;
            }
        }
        distance[pacmanPosition.x()][pacmanPosition.y()] = 0;
        Queue<Vector2ic> queue = new LinkedList<>();
        queue.add(pacmanPosition);
        while (!queue.isEmpty()) {
            Vector2ic current = queue.poll();
            for (Direction direction : Direction.values()) {
                int dx = direction.getDx();
                int dy = direction.getDy();
                Vector2i next = new Vector2i(current.x() + dx, current.y() + dy);
                if (next.x() < 0 || next.x() >= dimensions.x() || next.y() < 0 || next.y() >= dimensions.y()) {
                    continue;
                }
                if (this.pacman.getMaze().getTile(next).getState() != TileState.WALL && distance[next.x()][next.y()] == -1) { // also avoid ghosts
                    distance[next.x()][next.y()] = distance[current.x()][current.y()] + 1;
                    queue.add(next);
                }
            }
        }

        return distance;
    }
}
